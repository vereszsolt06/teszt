package hu.sporolos.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import hu.sporolos.core.Json;
import hu.sporolos.core.Packaging;
import hu.sporolos.core.PriceRepository;
import hu.sporolos.core.PromoMerger;
import hu.sporolos.data.Catalog;
import hu.sporolos.data.Promo;
import hu.sporolos.data.PromoStore;
import hu.sporolos.core.WeeklyPlanner;
import hu.sporolos.store.Ids;
import hu.sporolos.store.Storage;
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

    private static final int MAX_BODY = 1_000_000;

    private final PriceRepository repo;
    private final Storage storage;
    private HttpServer server;
    private ExecutorService executor;

    public WebServer(PriceRepository repo) {
        this(repo, null);
    }

    public WebServer(PriceRepository repo, Storage storage) {
        this.repo = repo;
        this.storage = storage;
        ApiMapper.catalog = repo.catalog();
    }

    public int start(String host, int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.createContext("/api/", this::api);
        server.createContext("/", this::staticFile);
        executor = Executors.newFixedThreadPool(4);
        server.setExecutor(executor);
        server.start();
        return server.getAddress().getPort();
    }

    public void stop() {
        if (server != null) server.stop(0);
        if (executor != null) executor.shutdownNow();
    }

    /** A kérés közös beállításai. */
    private record Prefs(Set<String> stores, boolean bulk, Set<String> cards) {}

    private void api(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            Map<String, String> q = query(ex.getRequestURI().getRawQuery());
            boolean isRefresh = path.equals("/api/refresh");
            boolean isPromos = path.equals("/api/promos");
            boolean writes = path.equals("/api/rate") || path.equals("/api/check");
            boolean allowed = method.equals("GET") && !isRefresh && !writes
                    || method.equals("POST") && (isRefresh || isPromos || writes || path.equals("/api/profile"))
                    || method.equals("PUT") && path.equals("/api/profile")
                    || method.equals("DELETE") && (isPromos || path.equals("/api/rate"));
            if (!allowed) {
                send(ex, 405, "application/json; charset=utf-8", Json.write(Map.of("error", "Nem támogatott kérés: " + method)));
                return;
            }
            Object body = switch (path) {
                case "/api/status" -> status(repo.get(), prefs(q));
                case "/api/deals" -> deals(repo.get(), q, prefs(q));
                case "/api/suggestions" -> suggestions(repo.get(), q, prefs(q));
                case "/api/recipe" -> recipe(repo.get(), q, prefs(q));
                case "/api/item" -> item(repo.get(), q, prefs(q));
                case "/api/search" -> search(repo.get(), q, prefs(q));
                case "/api/ingredients" -> ingredients();
                case "/api/plan" -> plan(repo.get(), q, prefs(q));
                case "/api/recipes" -> recipes(repo.get(), q, prefs(q));
                case "/api/promos" -> switch (method) {
                    case "POST" -> addPromo(readBody(ex), prefs(q));
                    case "DELETE" -> deletePromo(q, prefs(q));
                    default -> promos(repo.get(), prefs(q));
                };
                case "/api/refresh" -> status(repo.reload(), prefs(q));
                case "/api/profile" -> switch (method) {
                    case "POST" -> createProfile(readBody(ex));
                    case "PUT" -> saveProfile(q, readBody(ex));
                    default -> profileJson(requireId(q));
                };
                case "/api/rate" -> method.equals("DELETE") ? clearRatings(q) : rate(q, readBody(ex));
                case "/api/week" -> week(repo.get(), q);
                case "/api/check" -> check(q, readBody(ex));
                default -> throw new NotFound("Ismeretlen végpont: " + path);
            };
            send(ex, 200, "application/json; charset=utf-8", Json.write(body));
        } catch (NotFound e) {
            send(ex, 404, "application/json; charset=utf-8", Json.write(Map.of("error", e.getMessage())));
        } catch (IllegalArgumentException e) {
            send(ex, 400, "application/json; charset=utf-8", Json.write(Map.of("error", e.getMessage())));
        } catch (Exception e) {
            e.printStackTrace();
            send(ex, 500, "application/json; charset=utf-8",
                    Json.write(Map.of("error", "Belső hiba: " + e.getClass().getSimpleName())));
        }
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
        return m;
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

    private static Object item(PriceRepository.State s, Map<String, String> q, Prefs p) {
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
        return m;
    }

    private static Object search(PriceRepository.State s, Map<String, String> q, Prefs p) {
        String query = Catalog.normalize(q.getOrDefault("q", ""));
        if (query.length() < 2) throw new IllegalArgumentException("Legalább 2 karakteres keresőszó kell");
        String store = q.get("store");
        var a = s.analysis(p.cards());
        int limit = intParam(q, "limit", 30, 1, 100);
        List<String> tokens = Arrays.asList(query.split(" "));
        List<PriceEntry> hits = new ArrayList<>();
        for (List<PriceEntry> offers : a.byItem().values()) {
            for (PriceEntry e : offers) {
                if (store != null && !store.isBlank() && !e.storeId().equals(store)) continue;
                String n = Catalog.normalize(e.productName() + " " + e.categoryName());
                if (tokens.stream().allMatch(n::contains)) hits.add(e);
            }
        }
        hits.sort(Comparator.comparingDouble(PriceEntry::unitPrice));
        return hits.stream().limit(limit).map(e -> {
            Map<String, Object> o = ApiMapper.entry(e);
            o.put("storeName", storeName(s, e.storeId()));
            o.put("deal", ApiMapper.dealSummary(a.deals().dealFor(e)));
            return o;
        }).toList();
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
        var plan = new WeeklyPlanner(repo.catalog(), a.recommender()).plan(store, req);
        return planJson(s, store, req.people(), plan);
    }

    private Map<String, Object> planJson(PriceRepository.State s, String store, int people, WeeklyPlanner.Plan plan) {
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
            dm.put("label", d.label());
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
        return repo.get().stores().stream().map(Store::id).toList();
    }

    private Object createProfile(String body) throws IOException {
        String id = Ids.newProfileId();
        Map<String, Object> in = body.isBlank() ? Map.of() : Json.obj(Json.parse(body));
        requireStorage().saveProfile(id, Household.from(in, storeIds()).toJson());
        return profileJson(id);
    }

    private Object saveProfile(Map<String, String> q, String body) throws IOException {
        String id = requireId(q);
        Household.load(requireStorage(), id, storeIds()); // létezik-e
        requireStorage().saveProfile(id, Household.from(Json.obj(Json.parse(body)), storeIds()).toJson());
        return profileJson(id);
    }

    private Map<String, Object> profileJson(String id) throws IOException {
        Household h = Household.load(requireStorage(), id, storeIds());
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
        Household.load(requireStorage(), id, storeIds());
        Map<String, Object> in = Json.obj(Json.parse(body));
        String recipe = in.get("recipe") == null ? null : Json.str(in.get("recipe"));
        if (recipe == null || repo.catalog().recipe(recipe) == null) throw new IllegalArgumentException("Ismeretlen recept: " + recipe);
        Object liked = in.get("liked");
        storage.rate(id, recipe, liked == null ? null : Boolean.TRUE.equals(liked));
        return profileJson(id);
    }

    private Object clearRatings(Map<String, String> q) throws IOException {
        String id = requireId(q);
        Household.load(requireStorage(), id, storeIds());
        storage.clearRatings(id);
        return profileJson(id);
    }

    private Object check(Map<String, String> q, String body) throws IOException {
        String id = requireId(q);
        Household.load(requireStorage(), id, storeIds());
        Map<String, Object> in = Json.obj(Json.parse(body));
        String key = Json.str(in.get("key")), item = Json.str(in.get("item"));
        if (key == null || item == null || key.length() > 200 || item.length() > 200) throw new IllegalArgumentException("Hibás tétel");
        storage.setChecked(id, key, item, Boolean.TRUE.equals(in.get("on")));
        return Map.of("checked", storage.checked(id, key));
    }

    private Object week(PriceRepository.State s, Map<String, String> q) throws IOException {
        String id = requireId(q);
        Household h = Household.load(requireStorage(), id, storeIds());
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
        if (saved != null && h.planKey().equals(saved.get("planKey"))) {
            slots.addAll(Household.strings(saved.get("ids")));
            excluded.addAll(Household.strings(saved.get("excluded")));
            seed = (long) Json.num(saved.get("seed"), 1);
            savedStore = saved.get("store") == null ? null : Json.str(saved.get("store"));
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
                if (recipe == null || repo.catalog().recipe(recipe) == null) throw new IllegalArgumentException("Ismeretlen recept");
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
        WeeklyPlanner planner = new WeeklyPlanner(repo.catalog(), a.recommender());
        var req = new WeeklyPlanner.Request(h.people(), n, h.budget(), h.diets(), h.equipment(),
                liked, disliked, keep, Set.copyOf(excluded), seed, h.stores(), h.bulk(), leftoverOf);
        var plan = planner.plan(planStore, req);

        // ugyanez a hét a többi boltban
        List<WeeklyPlanner.StoreTotal> totals = new ArrayList<>();
        List<Suggestion> cooked = WeeklyPlanner.cooked(plan);
        for (String st : allowed) totals.add(planner.priceIn(st, cooked, h.bulk()));
        String store = planStore;
        if (h.autoStore()) {
            // automatikus: ahol a hét a legolcsóbb (a becsült, ott nem kapható tételek büntetésével)
            WeeklyPlanner.StoreTotal cheapest = totals.stream()
                    .min(Comparator.comparingDouble(t -> t.checkout() + 400.0 * t.estimated())).orElse(null);
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
        storage.saveWeek(id, w);

        Map<String, Object> m = planJson(s, store, h.people(), plan);
        m.put("autoStore", h.autoStore());
        List<Object> compare = new ArrayList<>();
        // ugyanazzal a mércével, mint az automatikus választás (a nem kapható, becsült tételek büntetésével)
        totals.sort(Comparator.comparingDouble(t -> t.checkout() + 400.0 * t.estimated()));
        for (var t : totals) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("store", t.storeId());
            c.put("name", storeName(s, t.storeId()));
            c.put("color", s.stores().stream().filter(x -> x.id().equals(t.storeId())).map(Store::color).findFirst().orElse("#888"));
            c.put("checkout", t.checkout());
            c.put("estimated", t.estimated());
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
        String listKey = Integer.toHexString((store + String.join(",", newSlots) + h.people()).hashCode());
        m.put("listKey", listKey);
        m.put("checked", storage.checked(id, listKey));
        m.put("dinners", n);
        m.put("cookMode", h.cookMode());
        return m;
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
        for (var r : repo.catalog().recipes()) {
            var meta = repo.catalog().meta(r.id());
            if (meta != null && (!meta.fits(diets) || !meta.cookableWith(equipment))) continue;
            Suggestion sug = rec.suggestion(r.id(), store, servings, p.stores(), p.bulk());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("recipe", ApiMapper.recipe(r));
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
        for (Catalog.Product p : repo.catalog().products().values()) {
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
            Catalog.Product prod = PromoMerger.resolveIngredient(repo.catalog(), ps.promo().ingredientKey());
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
        out.put("enabled", repo.promoStore() != null);
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
            if (!repo.catalog().stores().containsKey(promo.storeId())) {
                throw new IllegalArgumentException("Ismeretlen bolt: " + promo.storeId());
            }
            if (promo.ingredientKey() == null && promo.itemId() == null) {
                throw new IllegalArgumentException("Válaszd ki, melyik alapanyaghoz tartozik az akció");
            }
            store.add(promo);
            added = 1;
        }
        PriceRepository.State s = repo.reloadPromos();
        Map<String, Object> out = new LinkedHashMap<>(Json.obj(promos(s, p)));
        out.put("added", added);
        out.put("skipped", skipped);
        return out;
    }

    private Object deletePromo(Map<String, String> q, Prefs p) throws IOException {
        PromoStore store = requirePromoStore();
        String id = q.getOrDefault("id", "");
        if (!store.remove(id)) throw new NotFound("Nincs ilyen kézi akció: " + id);
        return promos(repo.reloadPromos(), p);
    }

    private PromoStore requirePromoStore() {
        if (repo.promoStore() == null) throw new IllegalArgumentException("Az akciók kezelése nincs bekapcsolva");
        return repo.promoStore();
    }

    // ---------- segédek ----------

    private static String readBody(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            byte[] data = in.readNBytes(MAX_BODY + 1);
            if (data.length > MAX_BODY) throw new IllegalArgumentException("Túl nagy kérés (legfeljebb 1 MB)");
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
        if (path.contains("..") || path.contains("\\")) {
            send(ex, 400, "text/plain; charset=utf-8", "Hibás útvonal");
            return;
        }
        try (InputStream in = WebServer.class.getResourceAsStream("/static" + path)) {
            if (in == null) {
                send(ex, 404, "text/plain; charset=utf-8", "Nem található: " + path);
                return;
            }
            ex.getResponseHeaders().set("Cache-Control", "no-cache");
            sendBytes(ex, 200, contentType(path), in.readAllBytes());
        }
    }

    private static String contentType(String path) {
        if (path.endsWith(".html")) return "text/html; charset=utf-8";
        if (path.endsWith(".css")) return "text/css; charset=utf-8";
        if (path.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (path.endsWith(".json") || path.endsWith(".webmanifest")) return "application/manifest+json; charset=utf-8";
        if (path.endsWith(".svg")) return "image/svg+xml";
        if (path.endsWith(".png")) return "image/png";
        if (path.endsWith(".ico")) return "image/x-icon";
        return "application/octet-stream";
    }

    private static void send(HttpExchange ex, int code, String type, String body) throws IOException {
        sendBytes(ex, code, type, body.getBytes(StandardCharsets.UTF_8));
    }

    /** Válasz küldése; szöveges tartalmat gzip-pel tömörít, ha a böngésző kéri (telefonon sokat számít). */
    private static void sendBytes(HttpExchange ex, int code, String type, byte[] data) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        String accept = ex.getRequestHeaders().getFirst("Accept-Encoding");
        boolean textual = type.startsWith("text/") || type.contains("json") || type.contains("javascript") || type.contains("svg");
        if (textual && data.length > 1024 && accept != null && accept.contains("gzip")) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(data.length / 4);
            try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
                gz.write(data);
            }
            data = bos.toByteArray();
            ex.getResponseHeaders().set("Content-Encoding", "gzip");
            ex.getResponseHeaders().set("Vary", "Accept-Encoding");
        }
        ex.sendResponseHeaders(code, data.length == 0 ? -1 : data.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(data);
        }
    }

    /** 404-es válaszhoz. */
    static final class NotFound extends RuntimeException {
        NotFound(String m) { super(m); }
    }
}
