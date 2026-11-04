package hu.sporolos.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import hu.sporolos.core.Json;
import hu.sporolos.core.Packaging;
import hu.sporolos.core.PriceRepository;
import hu.sporolos.core.PromoMerger;
import hu.sporolos.data.Catalog;
import hu.sporolos.data.Promo;
import hu.sporolos.i18n.Lang;
import hu.sporolos.i18n.Translator;
import hu.sporolos.market.Market;
import hu.sporolos.market.Markets;
import hu.sporolos.data.PromoStore;
import hu.sporolos.core.WeeklyPlanner;
import hu.sporolos.store.Ids;
import hu.sporolos.store.Storage;
import hu.sporolos.model.Models.Deal;
import hu.sporolos.model.Models.PriceEntry;
import hu.sporolos.model.Models.Store;
import hu.sporolos.model.Models.Suggestion;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.GZIPOutputStream;

/**
 * Beépített JDK HTTP szerver: REST API a /api alatt, statikus fájlok (resources/static) minden máshol.
 *
 * Közös paraméterek (minden boltfüggő végpontnál):
 *   stores=aldi,lidl,…  a felhasználó boltjai (az összehasonlítás és a megtakarítás csak ezekhez számol)
 *   bulk=1              nagy kiszerelések is jöhetnek
 *   cards=lidl,tesco    van hűségkártyája/appja ezekhez a láncokhoz (a kártyás akciók csak így számítanak)
 *
 * Végpontok:
 *  GET    /api/status                                   – adatforrás, dátum, boltok
 *  GET    /api/suggestions?store=lidl&tag=vega&limit=12 – rangsorolt receptek egy boltra
 *  GET    /api/recipe?id=lecso&store=lidl&servings=6    – egy recept bevásárlólistával + ára a boltjaidban
 *  GET    /api/deals?store=lidl&limit=30&ingredients=true – a bolt kiemelt árai
 *  GET    /api/item?id=5998…                            – egy termék ára minden láncban
 *  GET    /api/search?q=tej&store=lidl&limit=30         – keresés a termékek között
 *  GET    /api/ingredients                              – a recept-alapanyagok listája (akció felviteléhez)
 *  GET    /api/plan?store=lidl&people=2&dinners=5&budget=15000&diets=vega&equipment=tuzhely,suto
 *              &liked=a,b&disliked=c&keep=a,_,b&exclude=d&seed=42 – heti vacsoraterv közös bevásárlólistával
 *  GET    /api/recipes?store=lidl&diets=…&equipment=…    – az összes recept (fotóval, tápértékkel, árral)
 *
 * Háztartás-profil (fiók nélkül; az id egy véletlen, megosztható azonosító):
 *  POST   /api/profile                                  – új profil (törzs: beállítások) → {id, settings, …}
 *  GET    /api/profile?id=…                             – beállítások, értékelések
 *  PUT    /api/profile?id=…                             – beállítások mentése
 *  POST   /api/rate?id=…        {recipe, liked}         – „Nom vagy nem” (liked: true / false / null)
 *  DELETE /api/rate?id=…                                – minden értékelés törlése
 *  GET    /api/week?id=…[&action=new|swap|set|repeat|fresh&day=2&recipe=…] – a háztartás heti terve (mentve marad);
 *              repeat: a következő nap is ebből (több adag, maradék), fresh: maradéknap helyett új recept
 *  POST   /api/check?id=…       {key, item, on}         – bevásárlólista-tétel kipipálása
 *  GET    /api/promos                                   – a láncok saját akciói és állapotuk
 *  POST   /api/promos                                   – új akció (JSON), vagy import: {"text": "<CSV vagy JSON>"}
 *  DELETE /api/promos?id=…                              – kézi akció törlése
 *  POST   /api/refresh                                  – árak újratöltése
 */
public final class WebServer {

    /** Kéréstörzs-korlát: a nyilvános végpontok kis JSON-t várnak, az admin (import, akciók) nagyobbat is. */
    private static final int MAX_BODY = 1_000_000;
    private static final int MAX_PUBLIC_BODY = 64 * 1024;

    /**
     * Content-Security-Policy: csak a saját forrásokból tölt (nincs inline szkript, eval, külső betű vagy CDN), a
     * stílusok a CSSOM-on át állíthatók (az nem esik a CSP alá), keretbe nem tehető (clickjacking ellen).
     */
    static final String CSP = "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; "
            + "font-src 'self'; connect-src 'self'; manifest-src 'self'; worker-src 'self'; object-src 'none'; "
            + "base-uri 'none'; form-action 'self'; frame-ancestors 'none'";

    private final Markets markets;
    private final Storage storage;
    private final AdminApi admin;
    private HttpServer server;
    private ExecutorService executor;
    /** A kérés országa (a pénznem a {@link Market#current()}-ben). */
    private static final ThreadLocal<Markets.Runtime> CUR = new ThreadLocal<>();

    public WebServer(PriceRepository repo) {
        this(repo, null);
    }

    public WebServer(PriceRepository repo, Storage storage) {
        this(repo, storage, null, null);
    }

    public WebServer(PriceRepository repo, Storage storage, hu.sporolos.data.CatalogSource source, String adminPassword) {
        this(new Markets(repo), storage, source, adminPassword);
    }

    /**
     * @param adminPassword ha nem null, bekapcsol az admin felület (/admin), és az akciók írása,
     *                      valamint az árfrissítés admin belépéshez kötött
     */
    public WebServer(Markets markets, Storage storage, hu.sporolos.data.CatalogSource source, String adminPassword) {
        this.markets = markets;
        this.storage = storage;
        this.admin = adminPassword == null ? null : new AdminApi(markets.hungary().repo(), storage, source, adminPassword);
        if (admin != null && source != null) {
            admin.onCatalogChange(() -> markets.catalogChanged(hu.sporolos.data.CatalogSource.pretty(source.products(), "products"),
                    hu.sporolos.data.CatalogSource.pretty(source.recipes(), "recipes")));
        }
        ApiMapper.catalog = () -> repo().catalog();
    }

    /** A kérés országa. */
    private Markets.Runtime rt() {
        Markets.Runtime r = CUR.get();
        return r == null ? markets.hungary() : r;
    }

    /** A kérés országának ártára. */
    private PriceRepository repo() {
        return rt().repo();
    }

    /** A kérés országának árállapota (ha még töltődik: {@link Markets.Loading}). */
    private PriceRepository.State st() {
        return markets.state(rt());
    }

    /** Melyik ország árait kéri a kérés: a háztartás-profil országa, különben a „market” paraméter. */
    private Markets.Runtime resolveMarket(String path, Map<String, String> q) {
        boolean profileBased = path.equals("/api/week") || path.equals("/api/extras") || path.equals("/api/rate")
                || path.equals("/api/check") || path.equals("/api/profile");
        if (profileBased && storage != null && Ids.valid(q.get("id"))) {
            try {
                Map<String, Object> prof = storage.profile(q.get("id"));
                if (prof != null && prof.get("country") != null) return markets.get(Json.str(prof.get("country")));
            } catch (IOException | RuntimeException ignored) {
                // a profil hibáját a végpont jelzi
            }
        }
        return markets.get(q.get("market"));
    }

    public int start(String host, int port) throws IOException {
        applyServerTimeouts();
        server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.createContext("/api/", guarded(this::api, true));
        server.createContext("/healthz", guarded(this::health, false));
        server.createContext("/metrics", guarded(this::metricsEndpoint, false));
        server.createContext("/", guarded(this::staticFile, false));
        // kérésenként egy virtuális szál: a lassú kliensek (mobilnet) nem foglalják le a néhány valódi szálat;
        // a párhuzamosan dolgozó API-kérések számát a {@link #inFlight} szemafor korlátozza
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.start();
        startedAt = System.currentTimeMillis();
        return server.getAddress().getPort();
    }

    /** Azonnali leállítás (tesztek). */
    public void stop() {
        stop(0);
    }

    /**
     * Szabályos leállítás: nem fogad új kapcsolatot, a futó kéréseket legfeljebb {@code drainSeconds} másodpercig
     * hagyja befejeződni (SIGTERM esetén – Docker, Kubernetes – a shutdown hook hívja).
     */
    public void stop(int drainSeconds) {
        if (server != null) server.stop(Math.max(0, drainSeconds));
        if (executor != null) executor.shutdownNow();
    }

    /**
     * A JDK webszerver időkorlátai (ha a rendszertulajdonságokban nincs más megadva): egy lassan csöpögtetett
     * kérés (slowloris) ne tarthasson nyitva kapcsolatot a végtelenségig. A beállítást a JDK az első szerver
     * létrehozásakor olvassa be.
     */
    static void applyServerTimeouts() {
        System.getProperties().putIfAbsent("sun.net.httpserver.maxReqTime", "30");     // kérés beolvasása, mp
        System.getProperties().putIfAbsent("sun.net.httpserver.maxRspTime", "120");    // válasz elküldése, mp
        System.getProperties().putIfAbsent("sun.net.httpserver.idleInterval", "30");   // üresjárati kapcsolat, mp
        System.getProperties().putIfAbsent("sun.net.httpserver.maxIdleConnections", "500");
    }

    /** A kérés közös beállításai. */
    private record Prefs(Set<String> stores, boolean bulk, Set<String> cards) {}

    private RateLimiter limiter = new RateLimiter(0);
    /** Admin-belépés: IP-címenként legfeljebb 10 próbálkozás percenként (jelszótalálgatás ellen). */
    private final RateLimiter loginLimiter = new RateLimiter(10);
    private boolean trustProxy;
    /** Egyszerre ennyi API-kérés dolgozhat; a többi legfeljebb {@link #queueWaitMs}-ig vár, aztán 503. */
    private java.util.concurrent.Semaphore inFlight = new java.util.concurrent.Semaphore(64, true);
    private int maxConcurrent = 64;
    private long queueWaitMs = 5_000;
    private boolean accessLog;
    private volatile long startedAt = System.currentTimeMillis();
    private final Metrics metrics = new Metrics();

    /** Legfeljebb ennyi API-kérés fut egyszerre (CPU-igényes heti tervezés, adatbázis-kapcsolatok védelme). */
    public WebServer maxConcurrent(int n) {
        this.maxConcurrent = Math.max(1, n);
        this.inFlight = new java.util.concurrent.Semaphore(this.maxConcurrent, true);
        return this;
    }

    /** Meddig várhat egy kérés szabad helyre, mielőtt 503-at kap (alap: 5 s). */
    public WebServer queueWait(long ms) {
        this.queueWaitMs = Math.max(0, ms);
        return this;
    }

    /** A memóriában tartott statikus fájlok száma (teszthez: véletlen URL-ek nem növelik). */
    public static int staticCacheSize() { return STATIC.size(); }

    /** Kérésnapló a szabványos kimenetre (a profil-azonosító kitakarva). */
    public WebServer accessLog(boolean on) {
        this.accessLog = on;
        return this;
    }

    /** A metrikák (/metrics) – teszteknek és diagnosztikának. */
    Metrics metrics() { return metrics; }

    /**
     * Kéréskorlát IP-címenként (percenként; 0 = nincs). {@code trustProxy}: terheléselosztó / reverse proxy mögött
     * az X-Forwarded-For első címe a kliens (csak akkor kapcsold be, ha a proxy ezt maga állítja be).
     */
    public WebServer rateLimit(int perMinute, boolean trustProxy) {
        this.limiter = new RateLimiter(perMinute);
        this.trustProxy = trustProxy;
        return this;
    }

    private String clientKey(HttpExchange ex) {
        if (trustProxy) {
            String ip = forwardedClient(ex.getRequestHeaders().get("X-Forwarded-For"));
            if (ip != null) return ip;
        }
        return ex.getRemoteAddress() == null ? "?" : ex.getRemoteAddress().getAddress().getHostAddress();
    }

    /**
     * A kliens címe az X-Forwarded-For-ból: az UTOLSÓ elem, mert azt a közvetlenül előttünk álló (megbízható) proxy
     * írta. Az első elemet a kliens maga is küldheti (az nginx $proxy_add_x_forwarded_for hozzáfűz a kliens
     * fejlécéhez), azzal a kéréskorlát véletlen értékekkel megkerülhető lenne. Egy proxy-réteget feltételez.
     */
    public static String forwardedClient(List<String> headers) {
        if (headers == null || headers.isEmpty()) return null;
        String last = headers.get(headers.size() - 1);
        String[] parts = last.split(",");
        for (int i = parts.length - 1; i >= 0; i--) {
            String p = parts[i].trim();
            if (!p.isEmpty()) return p;
        }
        return null;
    }

    /** A válasz nyelve: a ?lang= paraméter, különben a böngésző Accept-Language fejléce. */
    private static String requestLang(HttpExchange ex, Map<String, String> q) {
        return q.containsKey("lang") ? Lang.normalize(q.get("lang"))
                : Lang.fromAcceptLanguage(ex.getRequestHeaders().getFirst("Accept-Language"));
    }

    /** Hibaüzenet JSON-ban, a kérés nyelvén (a Lang már be van állítva, vagy itt állítjuk be). */
    private static void sendError(HttpExchange ex, int code, String message) throws IOException {
        boolean own = !Lang.isSet();
        if (own) Lang.set(requestLang(ex, query(ex.getRequestURI().getRawQuery())));
        try {
            send(ex, code, "application/json; charset=utf-8", Json.write(localized(Map.of("error", message))));
        } finally {
            if (own) Lang.clear();
        }
    }

    private void api(HttpExchange ex) throws IOException {
        String client = clientKey(ex);
        if (limiter.enabled() && !limiter.allow(client)) {
            metrics.rejected("rate_limit");
            ex.getResponseHeaders().set("Retry-After", String.valueOf(RateLimiter.retryAfterSeconds(System.currentTimeMillis())));
            sendError(ex, 429, "Túl sok kérés – próbáld újra egy perc múlva.");
            return;
        }
        if (ex.getRequestURI().getPath().equals("/api/admin/login") && !loginLimiter.allow(client)) {
            metrics.rejected("login_limit");
            ex.getResponseHeaders().set("Retry-After", String.valueOf(RateLimiter.retryAfterSeconds(System.currentTimeMillis())));
            sendError(ex, 429, "Túl sok kérés – próbáld újra egy perc múlva.");
            return;
        }
        boolean acquired;
        try {
            acquired = inFlight.tryAcquire(queueWaitMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            acquired = false;
        }
        if (!acquired) {
            metrics.rejected("overload");
            ex.getResponseHeaders().set("Retry-After", "5");
            sendError(ex, 503, "A szerver most túlterhelt – próbáld újra pár másodperc múlva.");
            return;
        }
        try {
            apiInner(ex);
        } finally {
            inFlight.release();
        }
    }

    private void apiInner(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            Map<String, String> q = query(ex.getRequestURI().getRawQuery());
            Markets.Runtime runtime = path.startsWith("/api/admin/") ? markets.hungary() : resolveMarket(path, q);
            CUR.set(runtime);
            Market.set(runtime.market());
            Lang.set(requestLang(ex, q));
            if (path.startsWith("/api/admin/")) {
                if (admin == null) throw new NotFound("Az admin felület nincs bekapcsolva");
                Object res;
                if (path.equals("/api/admin/login")) {
                    if (!method.equals("POST")) throw new IllegalArgumentException("POST kell");
                    res = admin.login(readBody(ex, MAX_PUBLIC_BODY));
                } else {
                    admin.require(ex.getRequestHeaders().getFirst("X-Admin-Token"));
                    res = admin.handle(path, method, q, method.equals("GET") || method.equals("DELETE") ? "" : readBody(ex, MAX_BODY));
                }
                send(ex, 200, "application/json; charset=utf-8", Json.write(res));
                return;
            }
            boolean isRefresh = path.equals("/api/refresh");
            boolean isPromos = path.equals("/api/promos");
            boolean writes = path.equals("/api/rate") || path.equals("/api/check");
            boolean isExtras = path.equals("/api/extras");
            boolean allowed = method.equals("GET") && !isRefresh && !writes
                    || method.equals("POST") && (isRefresh || isPromos || writes || path.equals("/api/profile"))
                    || method.equals("PUT") && path.equals("/api/profile")
                    || method.equals("DELETE") && (isPromos || path.equals("/api/rate") || isExtras)
                    || method.equals("POST") && isExtras;
            if (!allowed) {
                send(ex, 405, "application/json; charset=utf-8", Json.write(Map.of("error", "Nem támogatott kérés: " + method)));
                return;
            }
            // az árfrissítés és az akciók írása az admin dolga (ha be van kapcsolva)
            if (admin != null && (isRefresh || isPromos && !method.equals("GET"))) {
                admin.require(ex.getRequestHeaders().getFirst("X-Admin-Token"));
            }
            Object body = switch (path) {
                case "/api/status" -> status(st(), prefs(q));
                case "/api/markets" -> marketsJson();
                case "/api/deals" -> deals(st(), q, prefs(q));
                case "/api/suggestions" -> suggestions(st(), q, prefs(q));
                case "/api/recipe" -> recipe(st(), q, prefs(q));
                case "/api/item" -> item(st(), q, prefs(q));
                case "/api/search" -> search(st(), q, prefs(q));
                case "/api/ingredients" -> ingredients();
                case "/api/plan" -> plan(st(), q, prefs(q));
                case "/api/recipes" -> recipes(st(), q, prefs(q));
                case "/api/offers" -> offers(st(), q, prefs(q));
                case "/api/extras" -> extrasEndpoint(method, q, method.equals("POST") ? readBody(ex, MAX_PUBLIC_BODY) : "");
                case "/api/promos" -> switch (method) {
                    case "POST" -> addPromo(readBody(ex, MAX_BODY), prefs(q));
                    case "DELETE" -> deletePromo(q, prefs(q));
                    default -> promos(st(), prefs(q));
                };
                case "/api/refresh" -> status(repo().reload(), prefs(q));
                case "/api/profile" -> switch (method) {
                    case "POST" -> createProfile(readBody(ex, MAX_PUBLIC_BODY));
                    case "PUT" -> saveProfile(q, readBody(ex, MAX_PUBLIC_BODY));
                    default -> profileJson(requireId(q));
                };
                case "/api/rate" -> method.equals("DELETE") ? clearRatings(q) : rate(q, readBody(ex, MAX_PUBLIC_BODY));
                case "/api/week" -> week(q);
                case "/api/check" -> check(q, readBody(ex, MAX_PUBLIC_BODY));
                default -> throw new NotFound("Ismeretlen végpont: " + path);
            };
            send(ex, 200, "application/json; charset=utf-8", Json.write(localized(body)));
        } catch (Markets.Loading e) {
            Markets.Runtime r = e.runtime();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("error", e.getMessage());
            m.put("loading", r.repo().loading() || r.repo().lastError() == null);
            m.put("failed", r.repo().lastError() != null && !r.repo().loading());
            m.put("market", marketJson(r));
            m.put("progress", Math.round(r.progress() * 1000) / 1000.0);
            m.put("phase", r.phase());
            send(ex, 503, "application/json; charset=utf-8", Json.write(localized(m)));
        } catch (AdminApi.Unauthorized e) {
            send(ex, 401, "application/json; charset=utf-8", Json.write(localized(Map.of("error", e.getMessage()))));
        } catch (NotFound e) {
            send(ex, 404, "application/json; charset=utf-8", Json.write(localized(Map.of("error", e.getMessage()))));
        } catch (IllegalArgumentException e) {
            send(ex, 400, "application/json; charset=utf-8", Json.write(localized(Map.of("error", e.getMessage()))));
        } catch (hu.sporolos.store.PgStorage.Busy e) {
            metrics.rejected("db_busy");
            ex.getResponseHeaders().set("Retry-After", "5");
            send(ex, 503, "application/json; charset=utf-8", Json.write(localized(Map.of("error", e.getMessage()))));
        } catch (Exception | StackOverflowError e) {
            System.err.println("[hiba] " + requestId(ex) + " " + ex.getRequestMethod() + " " + redact(ex.getRequestURI()) + ": " + e);
            e.printStackTrace();
            send(ex, 500, "application/json; charset=utf-8",
                    Json.write(localized(Map.of("error", "Belső hiba: " + e.getClass().getSimpleName() + " (" + requestId(ex) + ")"))));
        } finally {
            CUR.remove();
            Market.clear();
            Lang.clear();
        }
    }

    /** A válasz a kérés nyelvén (magyarul változatlan). */
    private static Object localized(Object body) {
        Translator t = Translator.forLang(Lang.current());
        return t == null ? body : t.apply(body);
    }

    private static Prefs prefs(Map<String, String> q) {
        return new Prefs(csv(q.get("stores")), "1".equals(q.get("bulk")) || "true".equals(q.get("bulk")), csv(q.get("cards")));
    }

    private static Set<String> csv(String s) {
        Set<String> out = new TreeSet<>();
        if (s == null) return out;
        for (String x : s.split(",")) if (!x.isBlank()) out.add(x.trim());
        return out;
    }

    // ---------- végpontok ----------

    private Map<String, Object> status(PriceRepository.State s, Prefs p) {
        var a = s.analysis(p.cards());
        Map<String, Object> m = new LinkedHashMap<>();
        LocalDate date = s.snapshot().date();
        m.put("source", s.snapshot().source());
        m.put("demo", s.snapshot().demo());
        m.put("date", date.toString());
        m.put("stale", date.isBefore(LocalDate.now().minusDays(1)));
        m.put("itemCount", s.snapshot().entries().size());
        m.put("historyDays", s.historyDays());
        m.put("loadedAt", s.loadedAt().toString());
        m.put("warnings", s.warnings());
        m.put("activePromos", a.promoStatuses().stream().filter(x -> x.status() == PromoMerger.Status.ACTIVE).count());
        List<Object> stores = new ArrayList<>();
        for (Store st : s.stores()) {
            Map<String, Object> sm = new LinkedHashMap<>();
            sm.put("id", st.id());
            sm.put("name", st.name());
            sm.put("color", st.color());
            sm.put("cardName", st.cardName());
            sm.put("itemCount", s.itemCounts().getOrDefault(st.id(), 0));
            sm.put("dealCount", a.deals().dealCount(st.id()));
            List<Suggestion> sug = a.recommender().suggestionsFor(st.id(), p.stores(), p.bulk());
            sm.put("recipeCount", sug.size());
            sm.put("topScore", sug.isEmpty() ? 0 : Math.round(sug.get(0).score() * 1000) / 1000.0);
            stores.add(sm);
        }
        m.put("stores", stores);
        m.put("market", marketJson(rt()));
        m.put("markets", marketsJson());
        return m;
    }

    /** Egy ország a felületnek (pénznem, árforrás, betöltöttség). */
    private static Map<String, Object> marketJson(Markets.Runtime r) {
        Market mk = r.market();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", mk.id());
        m.put("name", mk.name());
        m.put("flag", mk.flag());
        m.put("currency", mk.currency());
        m.put("symbol", mk.symbol());
        m.put("rate", mk.rate());
        m.put("decimals", mk.decimals());
        m.put("source", mk.source());
        m.put("sourceUrl", mk.sourceUrl());
        m.put("beta", mk.beta());
        m.put("loaded", r.repo().peek() != null);
        m.put("loading", r.repo().loading());
        return m;
    }

    private List<Object> marketsJson() {
        List<Object> out = new ArrayList<>();
        for (Markets.Runtime r : markets.all()) out.add(marketJson(r));
        return out;
    }

    private static Object deals(PriceRepository.State s, Map<String, String> q, Prefs p) {
        String store = requireStore(s, q);
        var a = s.analysis(p.cards());
        int limit = intParam(q, "limit", 30, 1, 200);
        boolean onlyIngredients = "true".equals(q.get("ingredients"));
        var rec = a.recommender();
        return a.deals().dealsForStore(store, onlyIngredients ? 100_000 : limit).stream()
                .filter(d -> !onlyIngredients || rec.ingredientFor(d.entry()) != null)
                .limit(limit)
                .map(d -> ApiMapper.deal(d, rec.ingredientFor(d.entry())))
                .toList();
    }

    private static Object suggestions(PriceRepository.State s, Map<String, String> q, Prefs p) {
        String store = requireStore(s, q);
        int limit = intParam(q, "limit", 12, 1, 200);
        String tag = q.getOrDefault("tag", "");
        return s.analysis(p.cards()).recommender().suggestionsFor(store, p.stores(), p.bulk()).stream()
                .filter(x -> tag.isBlank() || x.recipe().tags().contains(tag))
                .limit(limit)
                .map(ApiMapper::suggestion).toList();
    }

    private Object recipe(PriceRepository.State s, Map<String, String> q, Prefs p) {
        String store = requireStore(s, q);
        String id = q.getOrDefault("id", "");
        int servings = intParam(q, "servings", 0, 0, 40);
        var rec = s.analysis(p.cards()).recommender();
        Suggestion main = rec.suggestion(id, store, servings, p.stores(), p.bulk());
        if (main == null) throw new NotFound("Nincs ilyen recept: " + id);
        Map<String, Object> m = new LinkedHashMap<>(ApiMapper.suggestion(main));
        List<Object> stores = new ArrayList<>();
        for (Store st : s.stores()) {
            if (!p.stores().isEmpty() && !p.stores().contains(st.id()) && !st.id().equals(store)) continue;
            Suggestion other = rec.suggestion(id, st.id(), main.servings(), p.stores(), p.bulk());
            Map<String, Object> b = ApiMapper.suggestionBrief(other);
            b.put("name", st.name());
            b.put("color", st.color());
            stores.add(b);
        }
        m.put("stores", stores);
        return m;
    }

    private Object item(PriceRepository.State s, Map<String, String> q, Prefs p) throws IOException {
        String id = q.getOrDefault("id", "");
        var a = s.analysis(p.cards());
        List<PriceEntry> offers = a.byItem().get(id);
        if (offers == null || offers.isEmpty()) throw new NotFound("Nincs ilyen termék: " + id);
        Map<String, Object> m = new LinkedHashMap<>();
        PriceEntry first = offers.get(0);
        m.put("id", id);
        m.put("name", first.productName());
        m.put("category", first.categoryName());
        List<Object> list = new ArrayList<>();
        offers.stream().sorted(Comparator.comparingDouble(PriceEntry::price)).forEach(e -> {
            Map<String, Object> o = ApiMapper.entry(e);
            o.put("storeName", storeName(s, e.storeId()));
            o.put("mine", p.stores().isEmpty() || p.stores().contains(e.storeId()));
            o.put("deal", ApiMapper.dealSummary(a.deals().dealFor(e)));
            list.add(o);
        });
        m.put("offers", list);
        // árelőzmény (az ajanlatok.hu-hoz hasonlóan 8 hétre): lánconként napi árak és összegzés
        if (storage != null && rt().def() == null) {
            LocalDate today = s.snapshot().date();
            var hist = storage.itemHistory(id, today.minusDays(56), today.plusDays(1));
            List<Object> h = new ArrayList<>();
            for (var e : hist.entrySet()) {
                if (e.getValue().size() < 2) continue;
                Map<String, Object> hm = new LinkedHashMap<>();
                hm.put("store", e.getKey());
                hm.put("storeName", storeName(s, e.getKey()));
                List<Object> pts = new ArrayList<>();
                for (var d : e.getValue().entrySet()) pts.add(List.of(d.getKey().toString(), d.getValue()));
                hm.put("points", pts);
                List<Double> vals = new ArrayList<>(e.getValue().values());
                double now = vals.get(vals.size() - 1);
                hm.put("min", vals.stream().mapToDouble(Double::doubleValue).min().orElse(now));
                hm.put("max", vals.stream().mapToDouble(Double::doubleValue).max().orElse(now));
                hm.put("median", hu.sporolos.data.Stats.median(vals));
                hm.put("daysCheaperOrEqual", vals.stream().filter(v -> v <= now + 0.5).count() - 1);
                hm.put("days", vals.size());
                h.add(hm);
            }
            m.put("history", h);
        }
        return m;
    }

    /**
     * A boltjaid összes kiemelt ára egy listában (az akciós újság-oldalak mintájára): a legnagyobb
     * megtakarítás elöl, lánc szerint szűrhetően.
     */
    private Object offers(PriceRepository.State s, Map<String, String> q, Prefs p) {
        var a = s.analysis(p.cards());
        int limit = intParam(q, "limit", 60, 1, 300);
        boolean onlyIngredients = !"false".equals(q.get("ingredients"));
        Set<String> only = csv(q.get("store"));
        String query = localQuery(Catalog.normalize(q.getOrDefault("q", "")));
        List<Deal> all = new ArrayList<>();
        for (Store st : s.stores()) {
            if (!p.stores().isEmpty() && !p.stores().contains(st.id())) continue;
            if (!only.isEmpty() && !only.contains(st.id())) continue;
            all.addAll(a.deals().dealsForStore(st.id(), 1000));
        }
        var rec = a.recommender();
        List<Object> out = new ArrayList<>();
        all.sort(Comparator.comparingDouble(Deal::score).reversed());
        for (Deal d : all) {
            String ing = rec.ingredientFor(d.entry());
            if (onlyIngredients && ing == null) continue;
            if (!query.isEmpty() && !Catalog.normalize(d.entry().productName() + " " + d.entry().categoryName()).contains(query)) continue;
            Map<String, Object> m = ApiMapper.deal(d, ing);
            m.put("storeName", storeName(s, d.entry().storeId()));
            out.add(m);
            if (out.size() >= limit) break;
        }
        return out;
    }

    // ---------- saját tételek a bevásárlólistán ----------

    private List<Map<String, Object>> extras(String id) throws IOException {
        String doc = requireStorage().document("extras-" + id);
        List<Map<String, Object>> out = new ArrayList<>();
        if (doc != null) for (Object o : Json.arr(Json.parse(doc))) out.add(Json.obj(o));
        return out;
    }

    private Object extrasEndpoint(String method, Map<String, String> q, String body) throws IOException {
        String id = requireId(q);
        Household.load(requireStorage(), id, markets);
        List<Map<String, Object>> list = extras(id);
        if (method.equals("POST")) {
            Map<String, Object> in = Json.obj(Json.parse(body));
            String name = in.get("name") == null ? null : Json.str(in.get("name")).trim();
            if (name == null || name.isEmpty() || name.length() > 120) throw new IllegalArgumentException("Adj meg egy tételt");
            if (list.size() >= 60) throw new IllegalArgumentException("Legfeljebb 60 saját tétel lehet");
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("name", name);
            String itemId = in.get("itemId") == null ? null : Json.str(in.get("itemId"));
            if (itemId != null && st().byItem().containsKey(itemId)) e.put("itemId", itemId);
            list.add(e);
        } else if (method.equals("DELETE")) {
            int n = intParam(q, "n", -1, -1, 100);
            if (n < 0 || n >= list.size()) throw new IllegalArgumentException("Nincs ilyen tétel");
            list.remove(n);
        }
        if (!method.equals("GET")) storage.saveDocument("extras-" + id, Json.write(list));
        return Map.of("extras", list);
    }

    /**
     * Egy saját tétel ára egy láncban: ugyanaz a termék, ha ott is kapható; különben a lánc legolcsóbb
     * hasonló terméke (ugyanabban az Árfigyelő-kategóriában, ugyanabban az egységben, a választott
     * kiszerelésre átszámolva) – a sajátmárkás termékek ugyanis láncról láncra mások.
     */
    private static PriceEntry extraIn(PriceRepository.Analysis a, Map<String, Object> extra, String storeId) {
        Object itemId = extra.get("itemId");
        if (itemId == null) return null;
        List<PriceEntry> same = a.byItem().getOrDefault(String.valueOf(itemId), List.of());
        PriceEntry best = null;
        for (PriceEntry e : same) {
            if (e.storeId().equals(storeId) && (best == null || e.price() < best.price())) best = e;
        }
        if (best != null || same.isEmpty()) return best;
        PriceEntry ref = same.get(0);
        PriceEntry sim = null;
        // ismeretlen kategóriánál (pl. horvát kávé) a név első szava a hasonlóság (kava → kava)
        String first = ref.categoryId() == 0 ? firstWord(ref.productName()) : null;
        for (PriceEntry e : a.byStore().getOrDefault(storeId, List.of())) {
            if (e.categoryId() != ref.categoryId() || !e.unit().equals(ref.unit())) continue;
            if (first != null && (first.length() < 3 || !first.equals(firstWord(e.productName())))) continue;
            if (e.quantity() <= 0 || e.quantity() < ref.quantity() * 0.4 || e.quantity() > ref.quantity() * 2.5) continue;
            if (sim == null || e.unitPrice() < sim.unitPrice()) sim = e;
        }
        return sim;
    }

    private Object search(PriceRepository.State s, Map<String, String> q, Prefs p) {
        String query = Catalog.normalize(q.getOrDefault("q", ""));
        if (query.length() < 2) throw new IllegalArgumentException("Legalább 2 karakteres keresőszó kell");
        query = localQuery(query);
        String store = q.get("store");
        var a = s.analysis(p.cards());
        int limit = intParam(q, "limit", 30, 1, 100);
        List<String> tokens = Arrays.asList(query.split(" "));
        List<PriceEntry> hits = new ArrayList<>();
        SearchIndex idx = searchIndex(a);
        for (int i = 0; i < idx.entries.length; i++) {
            PriceEntry e = idx.entries[i];
            if (store != null && !store.isBlank() && !e.storeId().equals(store)) continue;
            String n = idx.names[i];
            if (tokens.stream().allMatch(n::contains)) hits.add(e);
        }
        hits.sort(Comparator.comparingDouble(PriceEntry::unitPrice));
        return hits.stream().limit(limit).map(e -> {
            Map<String, Object> o = ApiMapper.entry(e);
            o.put("storeName", storeName(s, e.storeId()));
            o.put("deal", ApiMapper.dealSummary(a.deals().dealFor(e)));
            return o;
        }).toList();
    }

    /** A keresés gyorsításához: a termékek normalizált neve (elemzésenként egyszer számolva). */
    private record SearchIndex(PriceEntry[] entries, String[] names) {}

    /** Országonként az utolsó elemzés indexe (azonosság szerint: új árállapotnál újraépül, a régi felszabadul). */
    private final Map<String, Object[]> searchCache = new java.util.concurrent.ConcurrentHashMap<>();

    private SearchIndex searchIndex(PriceRepository.Analysis a) {
        String key = rt().market().id();
        Object[] cached = searchCache.get(key);
        if (cached != null && cached[0] == a) return (SearchIndex) cached[1];
        PriceEntry[] es = a.entries().toArray(new PriceEntry[0]);
        String[] ns = new String[es.length];
        for (int i = 0; i < es.length; i++) ns[i] = Catalog.normalize(es[i].productName() + " " + es[i].categoryName());
        SearchIndex idx = new SearchIndex(es, ns);
        searchCache.put(key, new Object[]{a, idx});
        return idx;
    }

    private static String firstWord(String name) {
        String n = Catalog.normalize(name);
        int i = n.indexOf(' ');
        return i < 0 ? n : n.substring(0, i);
    }

    /**
     * A keresőszó a piac nyelvén. Előbb a felület nyelvéről magyarra („milk” → „tej”, „mlijeko” → „tej”), aztán
     * külföldi árlistánál magyarról az ország nyelvére („tej” → „mlijeko”, a piac szinonimalistája szerint).
     */
    private String localQuery(String query) {
        Translator tr = Translator.forLang(Lang.current());
        if (tr != null) {
            String hu = tr.searchToHungarian(query);
            // ha a szó már a piac nyelvén van (horvát felhasználó a horvát boltban), maradjon az eredeti is
            if (rt().def() != null && !hu.equals(query) && rt().def().search().containsValue(query)) return query;
            query = hu;
        }
        if (rt().def() == null) return query;
        Map<String, String> syn = rt().def().search();
        String whole = syn.get(query);
        if (whole != null) return whole;
        StringBuilder sb = new StringBuilder();
        for (String t : query.split(" ")) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(syn.getOrDefault(t, t));
        }
        return sb.toString();
    }

    private Object plan(PriceRepository.State s, Map<String, String> q, Prefs p) {
        String store = requireStore(s, q);
        var a = s.analysis(p.cards());
        List<String> keep = new ArrayList<>();
        for (String x : q.getOrDefault("keep", "").split(",", -1)) keep.add(x.equals("_") || x.isBlank() ? null : x.trim());
        var req = new WeeklyPlanner.Request(
                intParam(q, "people", 2, 1, 12), intParam(q, "dinners", 5, 1, 7), intParam(q, "budget", 0, 0, 1_000_000),
                csv(q.get("diets")), csv(q.get("equipment")), csv(q.get("liked")), csv(q.get("disliked")),
                keep, csv(q.get("exclude")), longParam(q, "seed", 1), p.stores(), p.bulk());
        var plan = new WeeklyPlanner(repo().catalog(), a.recommender()).plan(store, req);
        return planJson(s, store, req.people(), plan);
    }

    private Map<String, Object> planJson(PriceRepository.State s, String store, int people, WeeklyPlanner.Plan plan) {
        return planJson(s, store, people, plan, null);
    }

    private static final java.util.List<String> DAY_NAMES = java.util.Arrays.asList(WeeklyPlanner.DAYS);

    /**
     * @param start a hét első napja (a napok neve és dátuma ehhez igazodik); null = hétfőtől, dátum nélkül
     */
    private Map<String, Object> planJson(PriceRepository.State s, String store, int people, WeeklyPlanner.Plan plan,
                                         LocalDate start) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("store", store);
        m.put("storeName", storeName(s, store));
        m.put("people", people);
        m.put("budget", plan.budget());
        m.put("checkoutTotal", plan.checkoutTotal());
        m.put("staplesTotal", plan.staplesTotal());
        m.put("overBudget", plan.overBudget());
        m.put("avgKcal", Math.round(plan.avgKcal()));
        m.put("avgProtein", Math.round(plan.avgProtein()));
        m.put("candidates", plan.candidateCount());
        m.put("notes", plan.notes());
        m.put("pantry", plan.pantry());
        List<Object> days = new ArrayList<>();
        for (var d : plan.days()) {
            Map<String, Object> dm = new LinkedHashMap<>();
            int idx = Math.max(0, DAY_NAMES.indexOf(d.label()));
            if (start != null) {
                LocalDate date = start.plusDays(idx);
                dm.put("label", WeeklyPlanner.DAYS[date.getDayOfWeek().getValue() - 1]);
                dm.put("date", date.toString());
            } else {
                dm.put("label", d.label());
            }
            dm.put("index", idx);
            dm.put("kept", d.kept());
            dm.put("shareOfList", d.shareOfList());
            dm.put("leftoverOf", d.leftoverOf());
            dm.put("coversDays", d.coversDays());
            dm.put("suggestion", ApiMapper.suggestion(d.suggestion()));
            days.add(dm);
        }
        m.put("days", days);
        List<Object> aisles = aislesJson(plan.items());
        m.put("aisles", aisles);
        return m;
    }

    // ---------- háztartás-profil, heti terv ----------

    private Storage requireStorage() {
        if (storage == null) throw new IllegalArgumentException("A profilok tárolása nincs bekapcsolva");
        return storage;
    }

    private static String requireId(Map<String, String> q) {
        String id = q.get("id");
        if (!Ids.valid(id)) throw new IllegalArgumentException("Hiányzó vagy érvénytelen profil-azonosító");
        return id;
    }

    private List<String> storeIds() {
        return rt().storeIds();
    }

    private Object createProfile(String body) throws IOException {
        String id = Ids.newProfileId();
        Map<String, Object> in = body.isBlank() ? Map.of() : Json.obj(Json.parse(body));
        requireStorage().saveProfile(id, Household.from(in, markets).toJson());
        return profileJson(id);
    }

    private Object saveProfile(Map<String, String> q, String body) throws IOException {
        String id = requireId(q);
        Household.load(requireStorage(), id, markets); // létezik-e
        requireStorage().saveProfile(id, Household.from(Json.obj(Json.parse(body)), markets).toJson());
        return profileJson(id);
    }

    private Map<String, Object> profileJson(String id) throws IOException {
        Household h = Household.load(requireStorage(), id, markets);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("settings", h.toJson());
        List<String> liked = new ArrayList<>(), disliked = new ArrayList<>();
        storage.ratings(id).forEach((r, l) -> (l ? liked : disliked).add(r));
        m.put("liked", liked);
        m.put("disliked", disliked);
        m.put("storage", storage.describe());
        return m;
    }

    private Object rate(Map<String, String> q, String body) throws IOException {
        String id = requireId(q);
        Household.load(requireStorage(), id, markets);
        Map<String, Object> in = Json.obj(Json.parse(body));
        String recipe = in.get("recipe") == null ? null : Json.str(in.get("recipe"));
        if (recipe == null || repo().catalog().recipe(recipe) == null) throw new IllegalArgumentException("Ismeretlen recept: " + recipe);
        Object liked = in.get("liked");
        storage.rate(id, recipe, liked == null ? null : Boolean.TRUE.equals(liked));
        return profileJson(id);
    }

    private Object clearRatings(Map<String, String> q) throws IOException {
        String id = requireId(q);
        Household.load(requireStorage(), id, markets);
        storage.clearRatings(id);
        return profileJson(id);
    }

    private Object check(Map<String, String> q, String body) throws IOException {
        String id = requireId(q);
        Household.load(requireStorage(), id, markets);
        Map<String, Object> in = Json.obj(Json.parse(body));
        String key = Json.str(in.get("key")), item = Json.str(in.get("item"));
        if (key == null || item == null || key.length() > 200 || item.length() > 200) throw new IllegalArgumentException("Hibás tétel");
        storage.setChecked(id, key, item, Boolean.TRUE.equals(in.get("on")));
        return Map.of("checked", storage.checked(id, key));
    }

    /** Az új hét első napja: ma (vasárnap a következő hétfő – vasárnap jellemzően a jövő hetet tervezik). */
    static LocalDate weekStart(LocalDate today) {
        return today.getDayOfWeek() == java.time.DayOfWeek.SUNDAY ? today.plusDays(1) : today;
    }

    private Object week(Map<String, String> q) throws IOException {
        String id = requireId(q);
        Household h = Household.load(requireStorage(), id, markets);
        PriceRepository.State s = st();
        var a = s.analysis(h.cards());
        List<String> allowed = new ArrayList<>();
        for (Store st : s.stores()) if (h.stores().isEmpty() || h.stores().contains(st.id())) allowed.add(st.id());
        if (allowed.isEmpty()) allowed.addAll(storeIds());

        // mentett hét: "recept" = aznap főzünk, "=k" = a k. nap maradéka
        Map<String, Object> saved = storage.week(id);
        List<String> slots = new ArrayList<>();
        List<String> excluded = new ArrayList<>();
        long seed = 1;
        String savedStore = null;
        LocalDate start = null;
        if (saved != null && h.planKey().equals(saved.get("planKey"))) {
            slots.addAll(Household.strings(saved.get("ids")));
            excluded.addAll(Household.strings(saved.get("excluded")));
            seed = (long) Json.num(saved.get("seed"), 1);
            savedStore = saved.get("store") == null ? null : Json.str(saved.get("store"));
            try {
                Object st0 = saved.get("start") != null ? saved.get("start") : saved.get("date");
                if (st0 != null) start = LocalDate.parse(Json.str(st0));
            } catch (RuntimeException ignored) {
                // régi mentés dátum nélkül
            }
        }
        int n = h.dinners();
        while (slots.size() < n) slots.add(null);
        while (slots.size() > n) slots.remove(slots.size() - 1);
        if (saved == null || !h.planKey().equals(saved.get("planKey"))) {
            // új hét: kétnaponta főzésnél minden második nap az előző maradéka
            if (Household.EVERY_OTHER.equals(h.cookMode())) for (int i = 1; i < n; i += 2) slots.set(i, "=" + (i - 1));
        }

        String action = q.getOrDefault("action", "");
        int day = intParam(q, "day", -1, -1, 6);
        if (!action.isEmpty() && !action.equals("new") && (day < 0 || day >= n)) throw new IllegalArgumentException("Hibás nap");
        // egy régi (több mint egy hete kezdődött) hét helyett újat kezdünk, a mostani napoktól
        LocalDate today = LocalDate.now();
        if (start == null || start.isBefore(today.minusDays(6)) || action.equals("new")) start = weekStart(today);
        if (action.equals("options")) return weekOptions(s, h, id, slots, excluded, day);
        switch (action) {
            case "new" -> {
                excluded.clear();
                for (String x : slots) if (x != null && !x.startsWith("=")) excluded.add(x);
                for (int i = 0; i < n; i++) if (slots.get(i) != null && !slots.get(i).startsWith("=")) slots.set(i, null);
                seed++;
            }
            case "swap" -> {
                int cook = cookDay(slots, day);
                String cur = slots.get(cook);
                if (cur != null) excluded.add(cur);
                slots.set(cook, null);
                seed++;
            }
            case "set" -> {
                String recipe = q.get("recipe");
                if (recipe == null || repo().catalog().recipe(recipe) == null) throw new IllegalArgumentException("Ismeretlen recept");
                // ha egy maradéknapra teszünk receptet, az a nap ezentúl főzős nap
                slots.set(day, recipe);
                detachLeftovers(slots, day);
            }
            case "repeat" -> {
                // a következő nap is ebből: többet főzünk, a maradék holnapra
                if (day + 1 >= n) throw new IllegalArgumentException("Ez a hét utolsó vacsorája");
                int cook = cookDay(slots, day);
                String next = slots.get(day + 1);
                if (next != null && !next.startsWith("=")) excluded.add(next);
                detachLeftovers(slots, day + 1);
                slots.set(day + 1, "=" + cook);
            }
            case "fresh" -> {
                // a maradéknap helyett aznap mást főzünk
                if (slots.get(day) != null && slots.get(day).startsWith("=")) {
                    slots.set(day, null);
                    seed++;
                }
            }
            case "" -> { }
            default -> throw new IllegalArgumentException("Ismeretlen művelet: " + action);
        }
        while (excluded.size() > 40) excluded.remove(0);

        List<String> keep = new ArrayList<>();
        List<Integer> leftoverOf = new ArrayList<>();
        for (String x : slots) {
            boolean lo = x != null && x.startsWith("=");
            keep.add(lo ? null : x);
            leftoverOf.add(lo ? Integer.parseInt(x.substring(1)) : -1);
        }
        Map<String, Boolean> ratings = storage.ratings(id);
        Set<String> liked = new TreeSet<>(), disliked = new TreeSet<>();
        ratings.forEach((r, l) -> (l ? liked : disliked).add(r));

        // a tervezés boltja: a megadott; automatikusnál a mentett, vagy ahol ma a legjobb a kínálat
        String planStore = h.autoStore()
                ? (savedStore != null && allowed.contains(savedStore) ? savedStore : bestStore(a, allowed, h))
                : h.store();
        WeeklyPlanner planner = new WeeklyPlanner(repo().catalog(), a.recommender());
        var req = new WeeklyPlanner.Request(h.people(), n, h.budget(), h.diets(), h.equipment(),
                liked, disliked, keep, Set.copyOf(excluded), seed, h.stores(), h.bulk(), leftoverOf);
        var plan = planner.plan(planStore, req);

        // ugyanez a hét a többi boltban
        List<WeeklyPlanner.StoreTotal> totals = new ArrayList<>();
        List<Suggestion> cooked = WeeklyPlanner.cooked(plan);
        for (String st : allowed) totals.add(planner.priceIn(st, cooked, h.bulk()));
        // a saját tételek (pl. kávé, tej) is számítanak abban, hol a legolcsóbb a heti bevásárlás
        List<Map<String, Object>> extras = extras(id);
        Map<String, Double> extraTotal = new HashMap<>();
        Map<String, Integer> extraMissing = new HashMap<>();
        for (String st : allowed) {
            for (Map<String, Object> x : extras) {
                if (x.get("itemId") == null) continue;
                PriceEntry e = extraIn(a, x, st);
                if (e == null) extraMissing.merge(st, 1, Integer::sum);
                else extraTotal.merge(st, e.price(), Double::sum);
            }
        }
        java.util.function.ToDoubleFunction<WeeklyPlanner.StoreTotal> rank = t -> t.checkout()
                + extraTotal.getOrDefault(t.storeId(), 0.0) + 400.0 * (t.estimated() + extraMissing.getOrDefault(t.storeId(), 0));
        String store = planStore;
        if (h.autoStore()) {
            // automatikus: ahol a hét a legolcsóbb (a becsült, ott nem kapható tételek büntetésével)
            WeeklyPlanner.StoreTotal cheapest = totals.stream().min(Comparator.comparingDouble(rank)).orElse(null);
            // egy csere miatt ne ugráljon a bolt: a mostani marad, ha legfeljebb ~3%-kal (150 Ft-tal) drágább
            WeeklyPlanner.StoreTotal current = totals.stream().filter(t -> t.storeId().equals(planStore)).findFirst().orElse(null);
            if (cheapest != null && current != null && savedStore != null && !action.equals("new")) {
                double cr = rank.applyAsDouble(current), br = rank.applyAsDouble(cheapest);
                if (cr - br <= Math.max(150, 0.03 * cr)) cheapest = current;
            }
            if (cheapest != null && !cheapest.storeId().equals(planStore)) {
                store = cheapest.storeId();
                List<String> ids = new ArrayList<>();
                for (var d : plan.days()) ids.add(d.leftoverOf() >= 0 ? null : d.suggestion().recipe().id());
                var req2 = new WeeklyPlanner.Request(h.people(), n, h.budget(), h.diets(), h.equipment(),
                        liked, disliked, ids, Set.copyOf(excluded), seed, h.stores(), h.bulk(), leftoverOf);
                plan = planner.plan(store, req2);
            }
        }

        List<String> newSlots = new ArrayList<>();
        int idx = 0;
        for (var d : plan.days()) {
            newSlots.add(d.leftoverOf() >= 0 ? "=" + d.leftoverOf() : d.suggestion().recipe().id());
            idx++;
        }
        Map<String, Object> w = new LinkedHashMap<>();
        w.put("planKey", h.planKey());
        w.put("store", store);
        w.put("ids", newSlots);
        w.put("excluded", excluded);
        w.put("seed", seed);
        w.put("date", LocalDate.now().toString());
        w.put("start", start.toString());
        storage.saveWeek(id, w);

        Map<String, Object> m = planJson(s, store, h.people(), plan, start);
        m.put("start", start.toString());
        m.put("today", today.toString());
        m.put("autoStore", h.autoStore());
        List<Object> compare = new ArrayList<>();
        // ugyanazzal a mércével, mint az automatikus választás (a nem kapható, becsült tételek büntetésével)
        totals.sort(Comparator.comparingDouble(rank));
        for (var t : totals) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("store", t.storeId());
            c.put("name", storeName(s, t.storeId()));
            c.put("color", s.stores().stream().filter(x -> x.id().equals(t.storeId())).map(Store::color).findFirst().orElse("#888"));
            c.put("checkout", t.checkout() + extraTotal.getOrDefault(t.storeId(), 0.0));
            c.put("estimated", t.estimated());
            c.put("extrasMissing", extraMissing.getOrDefault(t.storeId(), 0));
            compare.add(c);
        }
        m.put("compare", compare);
        if (h.split() && totals.size() > 1) {
            var split = planner.bestSplit(totals, Math.max(300, 0.04 * plan.checkoutTotal()));
            if (split != null) {
                Map<String, Object> sp = new LinkedHashMap<>();
                sp.put("stores", split.stores().stream().map(x -> Map.of("store", x, "name", storeName(s, x))).toList());
                sp.put("checkout", split.checkout());
                sp.put("saving", split.saving());
                List<Object> groups = new ArrayList<>();
                for (String st : split.stores()) {
                    List<WeeklyPlanner.ListItem> items = split.items().stream().filter(it -> it.storeId().equals(st)).toList();
                    if (items.isEmpty()) continue;
                    Map<String, Object> g = new LinkedHashMap<>();
                    g.put("store", st);
                    g.put("name", storeName(s, st));
                    g.put("checkout", items.stream().filter(it -> !it.product().staple()).mapToDouble(it -> it.priced().checkoutCost()).sum());
                    g.put("aisles", aislesJson(items));
                    groups.add(g);
                }
                sp.put("groups", groups);
                m.put("split", sp);
            }
        }
        // saját tételek a választott boltban (két boltnál ott, ahol olcsóbb)
        List<Object> ex = new ArrayList<>();
        double extrasSum = 0;
        for (int i = 0; i < extras.size(); i++) {
            Map<String, Object> x = extras.get(i);
            Map<String, Object> em = new LinkedHashMap<>();
            em.put("n", i);
            em.put("name", x.get("name"));
            em.put("itemId", x.get("itemId"));
            String where = store;
            PriceEntry e = extraIn(a, x, store);
            if (m.get("split") instanceof Map<?, ?> sp) {
                for (Object so : Json.arr(sp.get("stores"))) {
                    String st = String.valueOf(Json.obj(so).get("store"));
                    PriceEntry o = extraIn(a, x, st);
                    if (o != null && (e == null || o.price() < e.price())) { e = o; where = st; }
                }
            }
            em.put("store", where);
            em.put("storeName", storeName(s, where));
            em.put("price", e == null ? null : e.price());
            em.put("product", e == null ? null : e.productName());
            em.put("similar", e != null && x.get("itemId") != null && !e.itemId().equals(x.get("itemId")));
            if (e != null) extrasSum += e.price();
            ex.add(em);
        }
        m.put("extras", ex);
        m.put("extrasTotal", extrasSum);
        String listKey = Integer.toHexString((store + String.join(",", newSlots) + h.people()).hashCode());
        m.put("listKey", listKey);
        m.put("checked", storage.checked(id, listKey));
        m.put("dinners", n);
        m.put("cookMode", h.cookMode());
        return m;
    }

    /**
     * Mi kerülhetne egy nap helyére (a „Csere” választója): a hét többi napja marad, a jelöltek a tervezés
     * mércéjével rangsorolva, mindegyiknél a hét új összegével.
     */
    private Object weekOptions(PriceRepository.State s, Household h, String id, List<String> slots,
                               List<String> excluded, int day) throws IOException {
        var a = s.analysis(h.cards());
        Map<String, Object> saved = storage.week(id);
        String savedStore = saved != null && saved.get("store") != null ? Json.str(saved.get("store")) : null;
        String store = savedStore != null && s.stores().stream().anyMatch(x -> x.id().equals(savedStore)) ? savedStore
                : h.autoStore() ? s.stores().get(0).id() : h.store();
        int cook = cookDay(slots, day);
        List<String> keep = new ArrayList<>();
        List<Integer> leftoverOf = new ArrayList<>();
        for (String x : slots) {
            boolean lo = x != null && x.startsWith("=");
            keep.add(lo ? null : x);
            leftoverOf.add(lo ? Integer.parseInt(x.substring(1)) : -1);
        }
        Set<String> ex = new TreeSet<>(excluded);
        String current = keep.get(cook);
        if (current != null) ex.add(current);
        Map<String, Boolean> ratings = storage.ratings(id);
        Set<String> liked = new TreeSet<>(), disliked = new TreeSet<>();
        ratings.forEach((r, l) -> (l ? liked : disliked).add(r));
        WeeklyPlanner planner = new WeeklyPlanner(repo().catalog(), a.recommender());
        var req = new WeeklyPlanner.Request(h.people(), slots.size(), h.budget(), h.diets(), h.equipment(),
                liked, disliked, keep, ex, 1, h.stores(), h.bulk(), leftoverOf);
        List<Object> out = new ArrayList<>();
        for (var o : planner.options(store, req, cook, 6)) {
            Map<String, Object> m = new LinkedHashMap<>(ApiMapper.suggestion(o.suggestion()));
            m.put("weekTotal", o.weekTotal());
            out.add(m);
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("day", cook);
        res.put("store", store);
        res.put("current", current);
        res.put("options", out);
        return res;
    }

    /** A nap főzős napja (maradéknapnál az, amelyiknek a maradékát esszük). */
    private static int cookDay(List<String> slots, int day) {
        String x = slots.get(day);
        return x != null && x.startsWith("=") ? Integer.parseInt(x.substring(1)) : day;
    }

    /** Ha egy nap főzős nappá (vagy más maradékává) válik, a rá hivatkozó maradéknapok új receptet kapnak. */
    private static void detachLeftovers(List<String> slots, int day) {
        for (int i = day + 1; i < slots.size(); i++) if (("=" + day).equals(slots.get(i))) slots.set(i, null);
    }

    /** Ahol ma a legjobb receptek vannak (a fejléc „ma itt a legjobb” jelzésével egyezően). */
    private static String bestStore(PriceRepository.Analysis a, List<String> allowed, Household h) {
        String best = allowed.get(0);
        double bestScore = -1;
        for (String st : allowed) {
            List<Suggestion> l = a.recommender().suggestionsFor(st, h.stores(), h.bulk());
            double sc = l.isEmpty() ? 0 : l.get(0).score();
            if (sc > bestScore) {
                bestScore = sc;
                best = st;
            }
        }
        return best;
    }

    private static List<Object> aislesJson(List<WeeklyPlanner.ListItem> list) {
        Map<String, List<WeeklyPlanner.ListItem>> by = new LinkedHashMap<>();
        for (var it : list) by.computeIfAbsent(it.aisle(), k -> new ArrayList<>()).add(it);
        List<Object> aisles = new ArrayList<>();
        for (var e : by.entrySet()) {
            Map<String, Object> am = new LinkedHashMap<>();
            am.put("aisle", e.getKey());
            List<Object> items = new ArrayList<>();
            for (var it : e.getValue()) {
                Map<String, Object> im = new LinkedHashMap<>(ApiMapper.ingredient(it.priced()));
                im.put("label", it.product().name());
                im.put("needed", Packaging.Qty.fmt(it.needed(), it.product().unit()));
                im.put("usedIn", it.usedIn());
                im.put("leftover", it.leftover() > 0.0001 ? Packaging.Qty.fmt(it.leftover(), it.product().unit()) : null);
                im.put("store", it.storeId());
                items.add(im);
            }
            am.put("items", items);
            aisles.add(am);
        }
        return aisles;
    }

    private Object recipes(PriceRepository.State s, Map<String, String> q, Prefs p) {
        String store = requireStore(s, q);
        var rec = s.analysis(p.cards()).recommender();
        Set<String> diets = csv(q.get("diets")), equipment = csv(q.get("equipment"));
        int servings = intParam(q, "servings", 0, 0, 40); // 0 = a recept alapadagja
        List<Object> out = new ArrayList<>();
        for (var r : repo().catalog().recipes()) {
            var meta = repo().catalog().meta(r.id());
            if (meta != null && (!meta.fits(diets) || !meta.cookableWith(equipment))) continue;
            Suggestion sug = rec.suggestion(r.id(), store, servings, p.stores(), p.bulk());
            Map<String, Object> m = new LinkedHashMap<>();
            // a listához az elkészítés nem kell (a receptlap külön kéri): a válasz így kb. harmadakkora
            Map<String, Object> rm = new LinkedHashMap<>(ApiMapper.recipe(r));
            rm.remove("steps");
            m.put("recipe", rm);
            m.put("ingredientNames", r.ingredients().stream().filter(i -> !i.pantry()).map(i -> i.label()).toList());
            if (sug != null) {
                m.put("totalCost", sug.totalCost());
                m.put("checkoutTotal", sug.checkoutTotal());
                m.put("dealCount", sug.dealCount());
                m.put("coverage", Math.round(sug.coverage() * 100) / 100.0);
                m.put("servings", sug.servings());
                m.put("score", Math.round(sug.score() * 1000) / 1000.0);
            }
            out.add(m);
        }
        return out;
    }

    private Object ingredients() {
        List<Object> out = new ArrayList<>();
        for (Catalog.Product p : repo().catalog().products().values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", p.key());
            m.put("name", p.name());
            m.put("unit", p.unit());
            m.put("qty", p.qty());
            m.put("tracked", p.tracked());
            out.add(m);
        }
        out.sort(Comparator.comparing(o -> String.valueOf(((Map<?, ?>) o).get("name")), java.text.Collator.getInstance(java.util.Locale.forLanguageTag("hu"))));
        return out;
    }

    private Object promos(PriceRepository.State s, Prefs p) {
        List<Object> list = new ArrayList<>();
        for (PromoMerger.PromoStatus ps : s.analysis(p.cards()).promoStatuses()) {
            Map<String, Object> m = new LinkedHashMap<>(ps.promo().toJson());
            m.put("storeName", storeName(s, ps.promo().storeId()));
            Catalog.Product prod = PromoMerger.resolveIngredient(repo().catalog(), ps.promo().ingredientKey());
            m.put("ingredientName", prod == null ? null : prod.name());
            m.put("status", ps.status().name());
            m.put("statusText", ps.status().label);
            m.put("matchedName", ps.matchedName());
            m.put("deletable", "kézi".equals(ps.promo().source()));
            list.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("promos", list);
        out.put("warnings", s.warnings().stream().filter(w -> w.startsWith("Akció")).toList());
        out.put("enabled", repo().promoStore() != null);
        return out;
    }

    private Object addPromo(String body, Prefs p) throws IOException {
        PromoStore store = requirePromoStore();
        Map<String, Object> in = Json.obj(Json.parse(body));
        List<String> skipped = new ArrayList<>();
        int added;
        if (in.containsKey("text")) {
            PromoStore.Loaded r = store.importText(String.valueOf(in.get("text")));
            added = r.promos().size();
            skipped.addAll(r.warnings());
        } else {
            Promo promo = Promo.fromJson(in, "kézi");
            if (!repo().catalog().stores().containsKey(promo.storeId())) {
                throw new IllegalArgumentException("Ismeretlen bolt: " + promo.storeId());
            }
            if (promo.ingredientKey() == null && promo.itemId() == null) {
                throw new IllegalArgumentException("Válaszd ki, melyik alapanyaghoz tartozik az akció");
            }
            store.add(promo);
            added = 1;
        }
        PriceRepository.State s = repo().reloadPromos();
        Map<String, Object> out = new LinkedHashMap<>(Json.obj(promos(s, p)));
        out.put("added", added);
        out.put("skipped", skipped);
        return out;
    }

    private Object deletePromo(Map<String, String> q, Prefs p) throws IOException {
        PromoStore store = requirePromoStore();
        String id = q.getOrDefault("id", "");
        if (!store.remove(id)) throw new NotFound("Nincs ilyen kézi akció: " + id);
        return promos(repo().reloadPromos(), p);
    }

    private PromoStore requirePromoStore() {
        if (repo().promoStore() == null) throw new IllegalArgumentException("Az akciók kezelése nincs bekapcsolva");
        return repo().promoStore();
    }

    // ---------- segédek ----------

    private static String readBody(HttpExchange ex, int limit) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            byte[] data = in.readNBytes(limit + 1);
            if (data.length > limit) {
                throw new IllegalArgumentException(limit >= MAX_BODY ? "Túl nagy kérés (legfeljebb 1 MB)" : "Túl nagy kérés (legfeljebb 64 KB)");
            }
            return new String(data, StandardCharsets.UTF_8);
        }
    }

    private static String storeName(PriceRepository.State s, String id) {
        return s.stores().stream().filter(x -> x.id().equals(id)).map(Store::name).findFirst().orElse(id);
    }

    private static String requireStore(PriceRepository.State s, Map<String, String> q) {
        String store = q.get("store");
        if (store == null || s.stores().stream().noneMatch(x -> x.id().equals(store))) {
            throw new IllegalArgumentException("Ismeretlen vagy hiányzó bolt: " + store);
        }
        return store;
    }

    private static long longParam(Map<String, String> q, String name, long def) {
        try {
            return Long.parseLong(q.getOrDefault(name, String.valueOf(def)));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static int intParam(Map<String, String> q, String name, int def, int min, int max) {
        try {
            return Math.max(min, Math.min(max, Integer.parseInt(q.getOrDefault(name, String.valueOf(def)))));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> m = new HashMap<>();
        if (raw == null) return m;
        for (String part : raw.split("&")) {
            int i = part.indexOf('=');
            if (i <= 0) continue;
            try {
                m.put(URLDecoder.decode(part.substring(0, i), StandardCharsets.UTF_8),
                        URLDecoder.decode(part.substring(i + 1), StandardCharsets.UTF_8));
            } catch (IllegalArgumentException ignored) {
                // hibás %-kódolás – a paramétert kihagyjuk
            }
        }
        return m;
    }

    private void staticFile(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (path.equals("/") || path.isEmpty()) path = "/index.html";
        if (path.equals("/admin") || path.equals("/admin/")) path = "/admin.html";
        if (path.contains("..") || path.contains("\\")) {
            send(ex, 400, "text/plain; charset=utf-8", "Hibás útvonal");
            return;
        }
        StaticEntry f = STATIC.get(path);
        if (f == null) {
            f = loadStatic(path);
            // a nem létező útvonalakat nem jegyezzük meg (különben véletlen URL-ekkel a memória teleírható)
            if (f != StaticEntry.MISSING) STATIC.put(path, f);
        }
        if (f == StaticEntry.MISSING) {
            send(ex, 404, "text/plain; charset=utf-8", "Nem található");
            return;
        }
        // a betűk nem változnak: egy évig a böngészőben maradhatnak; minden más ETag-gel újraellenőrizve
        // (változatlan fájlra 304, üres válasz – telefonon, sok felhasználónál ez a forgalom nagy része)
        ex.getResponseHeaders().set("Cache-Control", path.startsWith("/fonts/") ? "public, max-age=31536000, immutable" : "no-cache");
        ex.getResponseHeaders().set("ETag", f.etag());
        if (f.etag().equals(ex.getRequestHeaders().getFirst("If-None-Match"))) {
            ex.sendResponseHeaders(304, -1);
            ex.close();
            return;
        }
        String accept = ex.getRequestHeaders().getFirst("Accept-Encoding");
        if (f.gzip() != null && accept != null && accept.contains("gzip")) {
            ex.getResponseHeaders().set("Content-Type", contentType(path));
            ex.getResponseHeaders().set("Content-Encoding", "gzip");
            ex.getResponseHeaders().set("Vary", "Accept-Encoding");
            ex.sendResponseHeaders(200, f.gzip().length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(f.gzip());
            }
            return;
        }
        sendBytes(ex, 200, contentType(path), f.data(), false);
    }

    /** Egy statikus fájl tartalma, előre tömörített változata (szövegnél) és ETag-je (a JAR-ból egyszer olvassuk be). */
    private record StaticEntry(byte[] data, byte[] gzip, String etag) {
        static final StaticEntry MISSING = new StaticEntry(new byte[0], null, "");
    }

    private static final Map<String, StaticEntry> STATIC = new java.util.concurrent.ConcurrentHashMap<>();

    private static StaticEntry loadStatic(String path) {
        try (InputStream in = WebServer.class.getResourceAsStream("/static" + path)) {
            if (in == null) return StaticEntry.MISSING;
            byte[] data = in.readAllBytes();
            byte[] h = java.security.MessageDigest.getInstance("SHA-256").digest(data);
            String type = contentType(path);
            boolean textual = type.startsWith("text/") || type.contains("json") || type.contains("javascript") || type.contains("svg");
            return new StaticEntry(data, textual && data.length > 1024 ? gzip(data) : null,
                    "\"" + java.util.HexFormat.of().formatHex(h, 0, 12) + "\"");
        } catch (IOException | java.security.NoSuchAlgorithmException e) {
            return StaticEntry.MISSING;
        }
    }

    /** Terheléselosztóknak, konténer-orchestrátoroknak: 200, ha a szerver fut (az árak töltődhetnek még). */
    private void health(HttpExchange ex) throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("markets", markets.all().stream().map(r -> r.market().id()).toList());
        m.put("version", VERSION);
        m.put("uptimeSeconds", (System.currentTimeMillis() - startedAt) / 1000);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        send(ex, 200, "application/json; charset=utf-8", Json.write(m));
    }

    /** Az app verziója a JAR manifestjéből (build.sh írja bele), fejlesztés közben „dev”. */
    public static final String VERSION = java.util.Optional.ofNullable(WebServer.class.getPackage().getImplementationVersion()).orElse("dev");

    private static String contentType(String path) {
        if (path.endsWith(".html")) return "text/html; charset=utf-8";
        if (path.endsWith(".css")) return "text/css; charset=utf-8";
        if (path.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (path.endsWith(".json") || path.endsWith(".webmanifest")) return "application/manifest+json; charset=utf-8";
        if (path.endsWith(".svg")) return "image/svg+xml";
        if (path.endsWith(".webp")) return "image/webp";
        if (path.endsWith(".png")) return "image/png";
        if (path.endsWith(".ico")) return "image/x-icon";
        if (path.endsWith(".woff")) return "font/woff";
        if (path.endsWith(".woff2")) return "font/woff2";
        if (path.endsWith(".txt")) return "text/plain; charset=utf-8";
        return "application/octet-stream";
    }

    private static void send(HttpExchange ex, int code, String type, String body) throws IOException {
        sendBytes(ex, code, type, body.getBytes(StandardCharsets.UTF_8), true);
    }

    private static byte[] gzip(byte[] data) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(64, data.length / 4));
        try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
            gz.write(data);
        }
        return bos.toByteArray();
    }

    /** Válasz küldése; szöveges tartalmat gzip-pel tömörít, ha a böngésző kéri (telefonon sokat számít). */
    private static void sendBytes(HttpExchange ex, int code, String type, byte[] data, boolean compress) throws IOException {
        if (ex.getResponseCode() != -1) return; // már válaszoltunk (pl. hiba a küldés után) – ne dobjunk újabb kivételt
        ex.getResponseHeaders().set("Content-Type", type);
        String accept = ex.getRequestHeaders().getFirst("Accept-Encoding");
        boolean textual = type.startsWith("text/") || type.contains("json") || type.contains("javascript") || type.contains("svg");
        if (compress && textual && data.length > 1024 && accept != null && accept.contains("gzip")) {
            data = gzip(data);
            ex.getResponseHeaders().set("Content-Encoding", "gzip");
            ex.getResponseHeaders().set("Vary", "Accept-Encoding");
        }
        ex.sendResponseHeaders(code, data.length == 0 ? -1 : data.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(data);
        }
    }

    // ---------- közös védőréteg: kérésazonosító, biztonsági fejlécek, korlátok, napló, metrikák ----------

    private static final String RID = "sporolos.requestId";

    /** A kérés rövid azonosítója (a válasz X-Request-Id fejlécében és a naplóban is). */
    static String requestId(HttpExchange ex) {
        Object v = ex.getAttribute(RID);
        return v == null ? "-" : v.toString();
    }

    /**
     * Minden kérés ezen megy át: azonosítót és biztonsági fejléceket kap, mérjük, naplózzuk, és ha a kezelő
     * bármilyen hibával (akár Error-ral) elszáll, akkor is kap választ – a kapcsolat nem marad nyitva.
     */
    private com.sun.net.httpserver.HttpHandler guarded(com.sun.net.httpserver.HttpHandler h, boolean api) {
        return ex -> {
            long t0 = System.nanoTime();
            String rid = Long.toHexString(java.util.concurrent.ThreadLocalRandom.current().nextLong() & 0xffffffffffL);
            ex.setAttribute(RID, rid);
            var hd = ex.getResponseHeaders();
            hd.set("X-Request-Id", rid);
            hd.set("X-Content-Type-Options", "nosniff");
            hd.set("X-Frame-Options", "DENY");
            hd.set("Referrer-Policy", "same-origin");
            hd.set("Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=(), usb=(), interest-cohort=()");
            hd.set("Cross-Origin-Opener-Policy", "same-origin");
            hd.set("Cross-Origin-Resource-Policy", "same-origin");
            hd.set("Content-Security-Policy", CSP);
            if (trustProxy && "https".equalsIgnoreCase(ex.getRequestHeaders().getFirst("X-Forwarded-Proto"))) {
                hd.set("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
            }
            // a háztartás adatai (hét, lista, profil) ne kerüljenek közös gyorsítótárba (a service worker a sajátját kezeli)
            if (api) hd.set("Cache-Control", "no-store");
            metrics.started();
            try {
                h.handle(ex);
            } catch (Throwable e) {
                if (!(e instanceof IOException)) {
                    System.err.println("[hiba] " + rid + " " + ex.getRequestMethod() + " " + redact(ex.getRequestURI()) + ": " + e);
                    e.printStackTrace();
                }
                try {
                    if (ex.getResponseCode() == -1) sendError(ex, 500, "Belső hiba (" + rid + ")");
                } catch (Throwable ignored) {
                    // a kliens közben bonthatta a kapcsolatot
                }
                if (e instanceof Error err && !(e instanceof StackOverflowError)) throw err;
            } finally {
                long nanos = System.nanoTime() - t0;
                int code = ex.getResponseCode();
                metrics.finished(route(ex.getRequestURI().getPath(), api), code, nanos);
                if (accessLog && (api || code >= 400)) {
                    System.out.printf("%s %d %s %s %dms rid=%s%n", java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS),
                            code, ex.getRequestMethod(), redact(ex.getRequestURI()), nanos / 1_000_000, rid);
                }
                ex.close();
            }
        };
    }

    /** Ismert útvonalak (a metrikák címkéi korlátos számúak maradjanak, véletlen URL-ek ne hozzanak létre újat). */
    private static final Set<String> ROUTES = Set.of("/api/status", "/api/markets", "/api/deals", "/api/suggestions",
            "/api/recipe", "/api/item", "/api/search", "/api/ingredients", "/api/plan", "/api/recipes", "/api/offers",
            "/api/extras", "/api/promos", "/api/refresh", "/api/profile", "/api/rate", "/api/week", "/api/check");

    static String route(String path, boolean api) {
        if (!api) return path.equals("/healthz") || path.equals("/metrics") ? path : "static";
        if (path.startsWith("/api/admin/")) return "/api/admin";
        return ROUTES.contains(path) ? path : "/api/other";
    }

    /** Teszthez: {@link #redact} egy útvonal + lekérdezés szövegre. */
    public static String redactForTest(String pathAndQuery) {
        return redact(java.net.URI.create(pathAndQuery));
    }

    /** A profil-azonosító titok (aki ismeri, látja a háztartás listáját): a naplóban kitakarjuk. */
    static String redact(java.net.URI uri) {
        String q = uri.getRawQuery();
        String path = uri.getRawPath();
        if (q == null) return path;
        // csak a háztartáshoz kötött végpontokon titok az id (a recept- és termékazonosító nem az)
        if (!PROFILE_PATHS.contains(path)) return path + "?" + q;
        return path + "?" + q.replaceAll("(^|&)id=[^&]*", "$1id=***");
    }

    private static final Set<String> PROFILE_PATHS = Set.of("/api/week", "/api/profile", "/api/extras", "/api/rate", "/api/check");

    private void metricsEndpoint(HttpExchange ex) throws IOException {
        StringBuilder sb = new StringBuilder();
        metrics.write(sb, inFlight.availablePermits(), maxConcurrent, (System.currentTimeMillis() - startedAt) / 1000);
        for (Markets.Runtime r : markets.all()) {
            String id = r.market().id();
            PriceRepository.State s = r.repo().peek();
            sb.append("sporolos_prices_loaded{market=\"").append(id).append("\"} ").append(s == null ? 0 : 1).append('\n');
            if (s != null) {
                sb.append("sporolos_price_items{market=\"").append(id).append("\"} ").append(s.snapshot().entries().size()).append('\n');
                sb.append("sporolos_price_data_age_seconds{market=\"").append(id).append("\"} ")
                        .append(java.time.Duration.between(s.loadedAt(), java.time.Instant.now()).toSeconds()).append('\n');
                sb.append("sporolos_price_demo{market=\"").append(id).append("\"} ").append(s.snapshot().demo() ? 1 : 0).append('\n');
            }
        }
        if (storage instanceof hu.sporolos.store.PgStorage pg) {
            sb.append("sporolos_db_permits_available ").append(pg.availablePermits()).append('\n');
            sb.append("sporolos_db_idle_connections ").append(pg.idleConnections()).append('\n');
        }
        Runtime rt = Runtime.getRuntime();
        sb.append("sporolos_jvm_memory_used_bytes ").append(rt.totalMemory() - rt.freeMemory()).append('\n');
        sb.append("sporolos_jvm_memory_max_bytes ").append(rt.maxMemory()).append('\n');
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        send(ex, 200, "text/plain; version=0.0.4; charset=utf-8", sb.toString());
    }

    /** 404-es válaszhoz. */
    static final class NotFound extends RuntimeException {
        NotFound(String m) { super(m); }
    }
}
