package hu.sporolos.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import hu.sporolos.core.Json;
import hu.sporolos.core.PriceRepository;
import hu.sporolos.data.Catalog;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.GZIPOutputStream;

/**
 * Beépített JDK HTTP szerver: REST API a /api alatt, statikus fájlok (resources/static) minden máshol.
 *
 * Végpontok:
 *  GET  /api/status                                   – adatforrás, dátum, boltok
 *  GET  /api/suggestions?store=lidl&tag=vega&limit=12 – rangsorolt receptek egy boltra
 *  GET  /api/recipe?id=lecso&store=lidl&servings=6    – egy recept bevásárlólistával + ára minden láncban
 *  GET  /api/deals?store=lidl&limit=30&ingredients=true – a bolt kiemelt árai
 *  GET  /api/item?id=5998…                            – egy termék ára minden láncban
 *  GET  /api/search?q=tej&store=lidl&limit=30         – keresés a termékek között
 *  POST /api/refresh                                  – árak újratöltése
 */
public final class WebServer {

    private final PriceRepository repo;
    private HttpServer server;
    private ExecutorService executor;

    public WebServer(PriceRepository repo) {
        this.repo = repo;
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

    private void api(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            Map<String, String> q = query(ex.getRequestURI().getRawQuery());
            boolean post = "POST".equals(ex.getRequestMethod());
            if (path.equals("/api/refresh") != post) {
                send(ex, 405, "application/json; charset=utf-8",
                        Json.write(Map.of("error", path.equals("/api/refresh") ? "POST kérés kell" : "GET kérés kell")));
                return;
            }
            Object body = switch (path) {
                case "/api/status" -> status(repo.get());
                case "/api/deals" -> deals(repo.get(), q);
                case "/api/suggestions" -> suggestions(repo.get(), q);
                case "/api/recipe" -> recipe(repo.get(), q);
                case "/api/item" -> item(repo.get(), q);
                case "/api/search" -> search(repo.get(), q);
                case "/api/refresh" -> status(repo.reload());
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

    // ---------- végpontok ----------

    private static Map<String, Object> status(PriceRepository.State s) {
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
        List<Object> stores = new ArrayList<>();
        for (Store st : s.stores()) {
            Map<String, Object> sm = new LinkedHashMap<>();
            sm.put("id", st.id());
            sm.put("name", st.name());
            sm.put("color", st.color());
            sm.put("itemCount", s.itemCounts().getOrDefault(st.id(), 0));
            sm.put("dealCount", s.deals().dealCount(st.id()));
            List<Suggestion> sug = s.recommender().suggestionsFor(st.id());
            sm.put("recipeCount", sug.size());
            sm.put("topScore", sug.isEmpty() ? 0 : Math.round(sug.get(0).score() * 1000) / 1000.0);
            stores.add(sm);
        }
        m.put("stores", stores);
        return m;
    }

    private static Object deals(PriceRepository.State s, Map<String, String> q) {
        String store = requireStore(s, q);
        int limit = intParam(q, "limit", 30, 1, 200);
        boolean onlyIngredients = "true".equals(q.get("ingredients"));
        var rec = s.recommender();
        return s.deals().dealsForStore(store, onlyIngredients ? 100_000 : limit).stream()
                .filter(d -> !onlyIngredients || rec.ingredientFor(d.entry()) != null)
                .limit(limit)
                .map(d -> ApiMapper.deal(d, rec.ingredientFor(d.entry())))
                .toList();
    }

    private static Object suggestions(PriceRepository.State s, Map<String, String> q) {
        String store = requireStore(s, q);
        int limit = intParam(q, "limit", 12, 1, 200);
        String tag = q.getOrDefault("tag", "");
        return s.recommender().suggestionsFor(store).stream()
                .filter(x -> tag.isBlank() || x.recipe().tags().contains(tag))
                .limit(limit)
                .map(ApiMapper::suggestion).toList();
    }

    private static Object recipe(PriceRepository.State s, Map<String, String> q) {
        String store = requireStore(s, q);
        String id = q.getOrDefault("id", "");
        int servings = intParam(q, "servings", 0, 0, 40);
        var rec = s.recommender();
        Suggestion main = rec.suggestion(id, store, servings);
        if (main == null) throw new NotFound("Nincs ilyen recept: " + id);
        Map<String, Object> m = new LinkedHashMap<>(ApiMapper.suggestion(main));
        List<Object> stores = new ArrayList<>();
        for (Store st : s.stores()) {
            Suggestion other = rec.suggestion(id, st.id(), main.servings());
            Map<String, Object> b = ApiMapper.suggestionBrief(other);
            b.put("name", st.name());
            b.put("color", st.color());
            stores.add(b);
        }
        m.put("stores", stores);
        return m;
    }

    private static Object item(PriceRepository.State s, Map<String, String> q) {
        String id = q.getOrDefault("id", "");
        List<PriceEntry> offers = s.byItem().get(id);
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
            o.put("deal", ApiMapper.dealSummary(s.deals().dealFor(e)));
            list.add(o);
        });
        m.put("offers", list);
        return m;
    }

    private static Object search(PriceRepository.State s, Map<String, String> q) {
        String query = Catalog.normalize(q.getOrDefault("q", ""));
        if (query.length() < 2) throw new IllegalArgumentException("Legalább 2 karakteres keresőszó kell");
        String store = q.get("store");
        int limit = intParam(q, "limit", 30, 1, 100);
        List<String> tokens = Arrays.asList(query.split(" "));
        List<PriceEntry> hits = new ArrayList<>();
        for (List<PriceEntry> offers : s.byItem().values()) {
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
            o.put("deal", ApiMapper.dealSummary(s.deals().dealFor(e)));
            return o;
        }).toList();
    }

    // ---------- segédek ----------

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
    private static final class NotFound extends RuntimeException {
        NotFound(String m) { super(m); }
    }
}
