package hu.sporolos.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import hu.sporolos.core.Json;
import hu.sporolos.core.PriceRepository;
import hu.sporolos.model.Models.Store;
import hu.sporolos.model.Models.Suggestion;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Beépített JDK HTTP szerver: REST API a /api alatt, statikus fájlok (resources/static) minden máshol.
 *
 * Végpontok:
 *  GET  /api/status                      – adatforrás, dátum, boltok
 *  GET  /api/deals?store=lidl&limit=30&ingredients=true – a bolt kiemelt ajánlatai
 *                                        (ingredients=true: csak recept-alapanyagok)
 *  GET  /api/suggestions?store=lidl&tag=vega&limit=12 – rangsorolt receptek
 *  POST /api/refresh                     – árak újratöltése
 */
public final class WebServer {

    private final PriceRepository repo;
    private HttpServer server;

    public WebServer(PriceRepository repo) {
        this.repo = repo;
    }

    public int start(String host, int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.createContext("/api/", this::api);
        server.createContext("/", this::staticFile);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
        return server.getAddress().getPort();
    }

    public void stop() {
        if (server != null) server.stop(0);
    }

    private void api(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            Map<String, String> q = query(ex.getRequestURI().getRawQuery());
            Object body = switch (path) {
                case "/api/status" -> status(repo.get());
                case "/api/deals" -> deals(repo.get(), q);
                case "/api/suggestions" -> suggestions(repo.get(), q);
                case "/api/refresh" -> {
                    if (!"POST".equals(ex.getRequestMethod())) {
                        send(ex, 405, "application/json", Json.write(Map.of("error", "POST kell")));
                        yield null;
                    }
                    yield status(repo.reload());
                }
                default -> {
                    send(ex, 404, "application/json", Json.write(Map.of("error", "Ismeretlen végpont: " + path)));
                    yield null;
                }
            };
            if (body != null) send(ex, 200, "application/json; charset=utf-8", Json.write(body));
        } catch (IllegalArgumentException e) {
            send(ex, 400, "application/json; charset=utf-8", Json.write(Map.of("error", e.getMessage())));
        } catch (Exception e) {
            e.printStackTrace();
            send(ex, 500, "application/json; charset=utf-8", Json.write(Map.of("error", String.valueOf(e.getMessage()))));
        }
    }

    private static Map<String, Object> status(PriceRepository.State s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("source", s.snapshot().source());
        m.put("date", s.snapshot().date().toString());
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
        return s.deals().dealsForStore(store, onlyIngredients ? 5000 : limit).stream()
                .filter(d -> !onlyIngredients || rec.ingredientFor(d.entry()) != null)
                .limit(limit)
                .map(d -> ApiMapper.deal(d, rec.ingredientFor(d.entry())))
                .toList();
    }

    private static Object suggestions(PriceRepository.State s, Map<String, String> q) {
        String store = requireStore(s, q);
        int limit = intParam(q, "limit", 12, 1, 100);
        String tag = q.getOrDefault("tag", "");
        return s.recommender().suggestionsFor(store).stream()
                .filter(x -> tag.isBlank() || x.recipe().tags().contains(tag))
                .limit(limit)
                .map(ApiMapper::suggestion).toList();
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
            m.put(URLDecoder.decode(part.substring(0, i), StandardCharsets.UTF_8),
                    URLDecoder.decode(part.substring(i + 1), StandardCharsets.UTF_8));
        }
        return m;
    }

    private void staticFile(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (path.equals("/") || path.isEmpty()) path = "/index.html";
        if (path.contains("..")) {
            send(ex, 400, "text/plain", "Hibás útvonal");
            return;
        }
        try (InputStream in = WebServer.class.getResourceAsStream("/static" + path)) {
            if (in == null) {
                send(ex, 404, "text/plain; charset=utf-8", "Nem található: " + path);
                return;
            }
            byte[] data = in.readAllBytes();
            ex.getResponseHeaders().set("Content-Type", contentType(path));
            ex.getResponseHeaders().set("Cache-Control", "no-cache");
            ex.sendResponseHeaders(200, data.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(data);
            }
        }
    }

    private static String contentType(String path) {
        if (path.endsWith(".html")) return "text/html; charset=utf-8";
        if (path.endsWith(".css")) return "text/css; charset=utf-8";
        if (path.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (path.endsWith(".svg")) return "image/svg+xml";
        if (path.endsWith(".png")) return "image/png";
        if (path.endsWith(".ico")) return "image/x-icon";
        return "application/octet-stream";
    }

    private static void send(HttpExchange ex, int code, String type, String body) throws IOException {
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(code, data.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(data);
        }
    }
}
