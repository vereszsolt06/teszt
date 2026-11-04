package hu.sporolos.web;

import hu.sporolos.core.Json;
import hu.sporolos.core.PriceRepository;
import hu.sporolos.core.RecipeRecommender;
import hu.sporolos.data.Catalog;
import hu.sporolos.data.CatalogSource;
import hu.sporolos.data.RecipeMeta;
import hu.sporolos.model.Models.PriceEntry;
import hu.sporolos.model.Models.PricedIngredient;
import hu.sporolos.model.Models.Recipe;
import hu.sporolos.model.Models.Store;
import hu.sporolos.model.Models.Suggestion;
import hu.sporolos.store.Storage;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Admin felület API-ja (/api/admin/…): receptek és alapanyagok szerkesztése, az árforrás állapota,
 * adatminőség (hol becsült az ár, hol nem egyezik a mennyiség), árfrissítés, export / visszaállítás.
 *
 * Belépés: POST /api/admin/login {"password": …} → token; minden más kérés az X-Admin-Token fejlécet várja.
 * A jelszót az ADMIN_PASSWORD adja; ha nincs megadva, induláskor véletlen jelszó készül (a konzolra írva).
 */
final class AdminApi {

    /** 401-es válaszhoz. */
    static final class Unauthorized extends RuntimeException {
        Unauthorized() { super("Admin belépés szükséges"); }
    }

    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9-]{1,59}");
    private static final long TOKEN_TTL_SECONDS = 12 * 3600;

    private final PriceRepository repo;
    private final Storage storage;
    private final CatalogSource source;
    private final byte[] passwordHash;
    private final Map<String, Instant> tokens = new ConcurrentHashMap<>();
    private final SecureRandom rnd = new SecureRandom();

    private Runnable afterCatalogChange = () -> { };

    /** A közös katalógus változásakor (pl. a többi ország levezetett katalógusának frissítéséhez). */
    void onCatalogChange(Runnable r) {
        afterCatalogChange = r == null ? () -> { } : r;
    }

    private void replaceCatalog(hu.sporolos.data.Catalog c) {
        repo.replaceCatalog(c);
        afterCatalogChange.run();
    }

    AdminApi(PriceRepository repo, Storage storage, CatalogSource source, String password) {
        this.repo = repo;
        this.storage = storage;
        this.source = source;
        this.passwordHash = sha(password);
    }

    // ---------- belépés ----------

    Object login(String body) throws InterruptedException {
        String pw = Json.str(Json.obj(Json.parse(body)).get("password"));
        if (pw == null || !MessageDigest.isEqual(sha(pw), passwordHash)) {
            Thread.sleep(600); // lassítja a találgatást
            throw new Unauthorized();
        }
        byte[] t = new byte[24];
        rnd.nextBytes(t);
        String token = HexFormat.of().formatHex(t);
        tokens.put(token, Instant.now().plusSeconds(TOKEN_TTL_SECONDS));
        return Map.of("token", token);
    }

    boolean authorized(String token) {
        if (token == null) return false;
        Instant exp = tokens.get(token);
        if (exp == null) return false;
        if (exp.isBefore(Instant.now())) {
            tokens.remove(token);
            return false;
        }
        return true;
    }

    void require(String token) {
        if (!authorized(token)) throw new Unauthorized();
    }

    private static byte[] sha(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------- végpontok ----------

    Object handle(String path, String method, Map<String, String> q, String body) throws IOException {
        return switch (path) {
            case "/api/admin/overview" -> overview();
            case "/api/admin/recipes" -> recipes();
            case "/api/admin/recipe" -> switch (method) {
                case "GET" -> recipe(q.get("id"));
                case "PUT" -> saveRecipe(body, false);
                case "POST" -> saveRecipe(body, true);
                case "DELETE" -> deleteRecipe(q.get("id"));
                default -> throw new IllegalArgumentException("Nem támogatott: " + method);
            };
            case "/api/admin/recipe/preview" -> previewRecipe(body);
            case "/api/admin/products" -> products();
            case "/api/admin/product" -> switch (method) {
                case "GET" -> product(q.get("key"));
                case "PUT" -> saveProduct(body, false);
                case "POST" -> saveProduct(body, true);
                case "DELETE" -> deleteProduct(q.get("key"));
                default -> throw new IllegalArgumentException("Nem támogatott: " + method);
            };
            case "/api/admin/product/matches" -> productMatches(body);
            case "/api/admin/categories" -> categories();
            case "/api/admin/refresh" -> { repo.reload(); yield overview(); }
            case "/api/admin/reset" -> { replaceCatalog(source.reset()); yield overview(); }
            case "/api/admin/export" -> Map.of(
                    "products", CatalogSource.pretty(source.products(), "products"),
                    "recipes", CatalogSource.pretty(source.recipes(), "recipes"));
            default -> throw new WebServer.NotFound("Ismeretlen admin végpont: " + path);
        };
    }

    private Object overview() throws IOException {
        PriceRepository.State s = repo.get();
        Catalog cat = repo.catalog();
        RecipeRecommender rec = s.recommender();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("source", s.snapshot().source());
        m.put("demo", s.snapshot().demo());
        m.put("date", s.snapshot().date().toString());
        m.put("loadedAt", s.loadedAt().toString());
        m.put("itemCount", s.snapshot().entries().size());
        m.put("historyDays", s.historyDays());
        m.put("warnings", s.warnings());
        m.put("storage", storage == null ? "nincs" : storage.describe());
        m.put("households", storage == null ? 0 : storage.householdCount());
        m.put("catalogModified", source.modified());
        m.put("recipeCount", cat.recipes().size());
        m.put("productCount", cat.products().size());
        m.put("promoCount", s.promos().size());

        // boltonként: hány recept árazható teljesen, és mely alapanyagok becsültek
        List<Object> stores = new ArrayList<>();
        for (Store st : s.stores()) {
            Map<String, Object> sm = new LinkedHashMap<>();
            sm.put("id", st.id());
            sm.put("name", st.name());
            sm.put("color", st.color());
            sm.put("items", s.itemCounts().getOrDefault(st.id(), 0));
            sm.put("deals", s.deals().dealCount(st.id()));
            int full = 0;
            Map<String, Integer> missing = new TreeMap<>();
            for (Recipe r : cat.recipes()) {
                Suggestion sug = rec.acrossStores(r.id()).get(st.id());
                if (sug == null) continue;
                if (sug.coverage() >= 0.999) full++;
                for (PricedIngredient pi : sug.items()) {
                    if (pi.estimated() && !pi.ingredient().pantry()) missing.merge(pi.ingredient().label(), 1, Integer::sum);
                }
            }
            sm.put("fullyPriced", full);
            sm.put("missing", missing);
            stores.add(sm);
        }
        m.put("stores", stores);
        int issues = 0;
        for (Recipe r : cat.recipes()) issues += recipeIssues(cat, r).size();
        m.put("recipeIssues", issues);
        return m;
    }

    // ---------- receptek ----------

    private Object recipes() {
        Catalog cat = repo.catalog();
        RecipeRecommender rec = repo.get().recommender();
        List<Object> out = new ArrayList<>();
        for (Recipe r : cat.recipes()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.id());
            m.put("name", r.name());
            m.put("emoji", r.emoji());
            m.put("tags", r.tags());
            m.put("minutes", r.minutes());
            m.put("servings", r.servings());
            RecipeMeta meta = cat.meta(r.id());
            if (meta != null) {
                m.put("kcal", Math.round(meta.kcal()));
                m.put("diets", meta.diets());
            }
            double min = Double.MAX_VALUE, cov = 1;
            for (Suggestion sug : rec.acrossStores(r.id()).values()) {
                min = Math.min(min, sug.checkoutTotal());
                cov = Math.min(cov, sug.coverage());
            }
            m.put("cheapestCheckout", min == Double.MAX_VALUE ? null : min);
            m.put("minCoverage", Math.round(cov * 100) / 100.0);
            m.put("issues", recipeIssues(cat, r));
            out.add(m);
        }
        return out;
    }

    private Object recipe(String id) {
        Map<String, Object> r = findById(Json.arr(source.recipes().get("recipes")), "id", id);
        if (r == null) throw new WebServer.NotFound("Nincs ilyen recept: " + id);
        return r;
    }

    private Object saveRecipe(String body, boolean isNew) throws IOException {
        Map<String, Object> r = cleanRecipe(Json.obj(Json.parse(body)));
        Map<String, Object> root = source.recipes();
        List<Object> list = new ArrayList<>(Json.arr(root.get("recipes")));
        int idx = indexOf(list, "id", Json.str(r.get("id")));
        if (isNew && idx >= 0) throw new IllegalArgumentException("Már van ilyen azonosítójú recept: " + r.get("id"));
        if (!isNew && idx < 0) throw new WebServer.NotFound("Nincs ilyen recept: " + r.get("id"));
        if (isNew) list.add(r); else list.set(idx, r);
        root.put("recipes", list);
        Catalog c = source.apply(source.products(), root);
        replaceCatalog(c);
        return Map.of("saved", r.get("id"), "issues", recipeIssues(c, c.recipe(Json.str(r.get("id")))));
    }

    private Object deleteRecipe(String id) throws IOException {
        Map<String, Object> root = source.recipes();
        List<Object> list = new ArrayList<>(Json.arr(root.get("recipes")));
        int idx = indexOf(list, "id", id);
        if (idx < 0) throw new WebServer.NotFound("Nincs ilyen recept: " + id);
        list.remove(idx);
        root.put("recipes", list);
        replaceCatalog(source.apply(source.products(), root));
        return Map.of("deleted", id);
    }

    /** Mentés nélkül: a recept ára minden boltban, tápértéke és a figyelmeztetések. */
    private Object previewRecipe(String body) {
        Map<String, Object> r = cleanRecipe(Json.obj(Json.parse(body)));
        Map<String, Object> root = source.recipes();
        List<Object> list = new ArrayList<>(Json.arr(root.get("recipes")));
        int idx = indexOf(list, "id", Json.str(r.get("id")));
        if (idx >= 0) list.set(idx, r); else list.add(r);
        root.put("recipes", List.of(r)); // csak ezt az egyet árazzuk
        Catalog tmp;
        try {
            tmp = new Catalog(CatalogSource.pretty(source.products(), "products"), CatalogSource.pretty(root, "recipes"));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(e.getMessage());
        }
        PriceRepository.State s = repo.get();
        PriceRepository.Analysis a = s.analysis(null);
        List<String> ids = s.stores().stream().map(Store::id).toList();
        RecipeRecommender rec = new RecipeRecommender(tmp, a.entries(), a.deals(), ids);
        String id = Json.str(r.get("id"));
        List<Object> stores = new ArrayList<>();
        for (Store st : s.stores()) {
            Suggestion sug = rec.acrossStores(id).get(st.id());
            if (sug == null) continue;
            Map<String, Object> sm = new LinkedHashMap<>();
            sm.put("store", st.id());
            sm.put("name", st.name());
            sm.put("checkout", sug.checkoutTotal());
            sm.put("used", sug.totalCost());
            sm.put("coverage", Math.round(sug.coverage() * 100) / 100.0);
            List<Object> lines = new ArrayList<>();
            for (PricedIngredient pi : sug.items()) {
                if (pi.ingredient().pantry()) continue;
                Map<String, Object> lm = new LinkedHashMap<>();
                lm.put("label", pi.ingredient().label());
                lm.put("amount", pi.ingredient().amount());
                lm.put("product", pi.entry() == null ? null : pi.entry().productName());
                lm.put("buy", pi.buyText());
                lm.put("pay", pi.checkoutCost());
                lm.put("estimated", pi.estimated());
                lines.add(lm);
            }
            sm.put("lines", lines);
            stores.add(sm);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        RecipeMeta meta = tmp.meta(id);
        if (meta != null) {
            out.put("kcal", Math.round(meta.kcal()));
            out.put("protein", Math.round(meta.protein()));
            out.put("diets", meta.diets());
            out.put("moods", meta.moods());
            out.put("keepsWell", meta.keepsWell());
            out.put("oven", meta.needsOven());
            out.put("stove", meta.needsStove());
        }
        out.put("stores", stores);
        out.put("issues", recipeIssues(tmp, tmp.recipe(id)));
        return out;
    }

    /** A recept beküldött mezőinek ellenőrzése és rendbetétele (az ismeretlen mezők kimaradnak). */
    private static Map<String, Object> cleanRecipe(Map<String, Object> in) {
        String id = Json.str(in.get("id"));
        if (id == null || !ID.matcher(id).matches()) throw new IllegalArgumentException("Az azonosító kisbetű, szám és kötőjel lehet (pl. rakott-krumpli)");
        String name = trimmed(in.get("name"));
        if (name == null) throw new IllegalArgumentException("Hiányzik a recept neve");
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", id);
        r.put("name", name);
        r.put("emoji", trimmed(in.get("emoji")) == null ? "🍽️" : trimmed(in.get("emoji")));
        r.put("minutes", (int) Math.max(1, Math.min(600, Json.num(in.get("minutes"), 30))));
        r.put("servings", (int) Math.max(1, Math.min(20, Json.num(in.get("servings"), 4))));
        String diff = trimmed(in.get("difficulty"));
        r.put("difficulty", diff == null ? "könnyű" : diff);
        List<String> tags = new ArrayList<>();
        for (Object t : Json.arr(in.getOrDefault("tags", List.of()))) {
            String v = trimmed(t);
            if (v != null && !tags.contains(v.toLowerCase())) tags.add(v.toLowerCase());
        }
        r.put("tags", tags);
        List<Object> ings = new ArrayList<>();
        for (Object o : Json.arr(in.getOrDefault("ingredients", List.of()))) {
            Map<String, Object> i = Json.obj(o);
            Map<String, Object> c = new LinkedHashMap<>();
            String key = trimmed(i.get("key"));
            if (key == null) continue;
            c.put("key", key);
            if (Boolean.TRUE.equals(i.get("pantry"))) {
                String label = trimmed(i.get("label"));
                c.put("label", label == null ? "fűszerek" : label);
                c.put("pantry", true);
            } else {
                String label = trimmed(i.get("label"));
                if (label != null) c.put("label", label);
                String amount = trimmed(i.get("amount"));
                if (amount != null) c.put("amount", amount);
                double need = Json.num(i.get("need"), 0);
                if (!(need > 0)) throw new IllegalArgumentException("A(z) " + key + " hozzávalónál hiányzik a mennyiség (need)");
                c.put("need", need);
            }
            ings.add(c);
        }
        if (ings.isEmpty()) throw new IllegalArgumentException("Legalább egy hozzávaló kell");
        r.put("ingredients", ings);
        List<String> steps = new ArrayList<>();
        for (Object st : Json.arr(in.getOrDefault("steps", List.of()))) {
            String v = trimmed(st);
            if (v != null) steps.add(v);
        }
        if (steps.isEmpty()) throw new IllegalArgumentException("Legalább egy elkészítési lépés kell");
        r.put("steps", steps);
        if (Boolean.TRUE.equals(in.get("airfryer"))) r.put("airfryer", true);
        return r;
    }

    // ---------- alapanyagok ----------

    private Object products() {
        Catalog cat = repo.catalog();
        PriceRepository.State s = repo.get();
        PriceRepository.Analysis a = s.analysis(null);
        Map<String, Integer> used = new LinkedHashMap<>();
        for (Recipe r : cat.recipes()) for (var i : r.ingredients()) if (!i.pantry()) used.merge(i.productKey(), 1, Integer::sum);
        // egyszer végigmegyünk a termékeken, és megszámoljuk alapanyagonként és boltonként a találatokat
        Map<String, Map<String, Integer>> hits = new LinkedHashMap<>();
        for (PriceEntry e : a.entries()) {
            for (Catalog.Product p : cat.products().values()) {
                if (p.tracked() && cat.matches(p, e)) hits.computeIfAbsent(p.key(), k -> new TreeMap<>()).merge(e.storeId(), 1, Integer::sum);
            }
        }
        List<Object> out = new ArrayList<>();
        for (Catalog.Product p : cat.products().values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", p.key());
            m.put("name", p.name());
            m.put("unit", p.unit());
            m.put("qty", p.qty());
            m.put("aisle", p.aisle());
            m.put("tracked", p.tracked());
            m.put("recipes", used.getOrDefault(p.key(), 0));
            Map<String, Integer> h = hits.getOrDefault(p.key(), Map.of());
            m.put("matches", h);
            List<String> none = new ArrayList<>();
            if (p.tracked()) for (Store st : s.stores()) if (!h.containsKey(st.id())) none.add(st.name());
            m.put("missingIn", none);
            out.add(m);
        }
        return out;
    }

    private Object product(String key) {
        Map<String, Object> p = findById(Json.arr(source.products().get("products")), "key", key);
        if (p == null) throw new WebServer.NotFound("Nincs ilyen alapanyag: " + key);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("product", p);
        out.put("aisles", repo.catalog().aisles());
        out.put("matches", matchesFor(p));
        return out;
    }

    /** Mentés nélkül: mely bolti termékek illeszkednek a (módosított) alapanyagra. */
    private Object productMatches(String body) {
        return Map.of("matches", matchesFor(cleanProduct(Json.obj(Json.parse(body)))));
    }

    private List<Object> matchesFor(Map<String, Object> productJson) {
        Map<String, Object> root = source.products();
        root.put("products", List.of(productJson));
        Catalog tmp;
        try {
            tmp = new Catalog(CatalogSource.pretty(root, "products"), "{\"recipes\": []}");
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(e.getMessage());
        }
        Catalog.Product p = tmp.products().values().iterator().next();
        PriceRepository.State s = repo.get();
        Map<String, List<PriceEntry>> byStore = new LinkedHashMap<>();
        for (Store st : s.stores()) byStore.put(st.id(), new ArrayList<>());
        if (p.tracked()) {
            for (PriceEntry e : s.analysis(null).entries()) {
                if (byStore.containsKey(e.storeId()) && tmp.matches(p, e)) byStore.get(e.storeId()).add(e);
            }
        }
        List<Object> out = new ArrayList<>();
        for (Store st : s.stores()) {
            List<PriceEntry> l = byStore.get(st.id());
            l.sort(Comparator.comparingDouble(PriceEntry::unitPrice));
            Map<String, Object> sm = new LinkedHashMap<>();
            sm.put("store", st.id());
            sm.put("name", st.name());
            sm.put("count", l.size());
            List<Object> items = new ArrayList<>();
            for (PriceEntry e : l.subList(0, Math.min(12, l.size()))) {
                Map<String, Object> im = new LinkedHashMap<>();
                im.put("name", e.productName());
                im.put("category", e.categoryName());
                im.put("price", e.price());
                im.put("quantity", e.quantity());
                im.put("unit", e.unit());
                im.put("unitPrice", e.unitPrice());
                items.add(im);
            }
            sm.put("items", items);
            out.add(sm);
        }
        return out;
    }

    private Object saveProduct(String body, boolean isNew) throws IOException {
        Map<String, Object> p = cleanProduct(Json.obj(Json.parse(body)));
        Map<String, Object> root = source.products();
        List<Object> list = new ArrayList<>(Json.arr(root.get("products")));
        int idx = indexOf(list, "key", Json.str(p.get("key")));
        if (isNew && idx >= 0) throw new IllegalArgumentException("Már van ilyen kulcsú alapanyag: " + p.get("key"));
        if (!isNew && idx < 0) throw new WebServer.NotFound("Nincs ilyen alapanyag: " + p.get("key"));
        if (isNew) list.add(p); else list.set(idx, p);
        root.put("products", list);
        replaceCatalog(source.apply(root, source.recipes()));
        return Map.of("saved", p.get("key"));
    }

    private Object deleteProduct(String key) throws IOException {
        Catalog cat = repo.catalog();
        List<String> users = new ArrayList<>();
        for (Recipe r : cat.recipes()) for (var i : r.ingredients()) if (key.equals(i.productKey()) && !i.pantry()) users.add(r.name());
        if (!users.isEmpty()) throw new IllegalArgumentException("Ezek a receptek használják, előbb azokból vedd ki: " + String.join(", ", users));
        Map<String, Object> root = source.products();
        List<Object> list = new ArrayList<>(Json.arr(root.get("products")));
        int idx = indexOf(list, "key", key);
        if (idx < 0) throw new WebServer.NotFound("Nincs ilyen alapanyag: " + key);
        list.remove(idx);
        root.put("products", list);
        replaceCatalog(source.apply(root, source.recipes()));
        return Map.of("deleted", key);
    }

    private static Map<String, Object> cleanProduct(Map<String, Object> in) {
        String key = Json.str(in.get("key"));
        if (key == null || !ID.matcher(key).matches()) throw new IllegalArgumentException("A kulcs kisbetű, szám és kötőjel lehet (pl. csirkemell)");
        String name = trimmed(in.get("name"));
        if (name == null) throw new IllegalArgumentException("Hiányzik az alapanyag neve");
        String unit = Json.str(in.get("unit"));
        if (!List.of("kg", "l", "db").contains(unit)) throw new IllegalArgumentException("Az egység kg, l vagy db lehet");
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("key", key);
        p.put("name", name);
        double qty = Json.num(in.get("qty"), 1);
        p.put("qty", qty > 0 ? qty : 1);
        p.put("unit", unit);
        p.put("basePrice", Math.max(0, Json.num(in.get("basePrice"), 0)));
        List<Object> cats = new ArrayList<>();
        for (Object c : Json.arr(in.getOrDefault("categoryIds", List.of()))) {
            double v = Json.num(c, -1);
            if (v > 0) cats.add((int) v);
        }
        p.put("categoryIds", cats);
        for (String k : List.of("keywords", "exclude")) {
            List<Object> words = new ArrayList<>();
            for (Object w : Json.arr(in.getOrDefault(k, List.of()))) {
                String v = trimmed(w);
                if (v != null) words.add(v);
            }
            if (!words.isEmpty()) p.put(k, words);
        }
        for (String k : List.of("pieceWeight", "bunchWeight", "maxPack")) {
            double v = Json.num(in.get(k), 0);
            if (v > 0) p.put(k, v);
        }
        for (String k : List.of("staple", "packaged")) if (Boolean.TRUE.equals(in.get(k))) p.put(k, true);
        List<Object> n = Json.arr(in.getOrDefault("nutrition", List.of()));
        if (n.size() == 4) {
            List<Object> nn = new ArrayList<>();
            for (Object x : n) nn.add(Math.max(0, Json.num(x, 0)));
            p.put("nutrition", nn);
        }
        String aisle = trimmed(in.get("aisle"));
        if (aisle != null) p.put("aisle", aisle);
        List<Object> contains = new ArrayList<>();
        for (Object c : Json.arr(in.getOrDefault("contains", List.of()))) {
            String v = Json.str(c);
            if (List.of("hus", "hal", "glutén", "laktóz").contains(v)) contains.add(v);
        }
        if (!contains.isEmpty()) p.put("contains", contains);
        return p;
    }

    /** Az Árfigyelő élelmiszer-kategóriái a mai adatból (azonosító → név, hány termék). */
    private Object categories() {
        Map<Integer, Map<String, Object>> m = new TreeMap<>();
        for (PriceEntry e : repo.get().analysis(null).entries()) {
            if (e.categoryId() <= 0) continue;
            m.computeIfAbsent(e.categoryId(), k -> {
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("id", k);
                c.put("name", e.categoryName());
                c.put("count", 0);
                return c;
            }).merge("count", 1, (x, y) -> (int) x + (int) y);
        }
        return new ArrayList<>(m.values());
    }

    // ---------- adatminőség ----------

    private static final Pattern AMOUNT = Pattern.compile("^\\s*(\\d+(?:[.,]\\d+)?|fél|másfél|negyed)\\s*(\\S*)");

    /** Figyelmeztetések egy receptre: ismeretlen alapanyag, a kiírt és a számolt mennyiség eltérése, árazhatóság. */
    static List<String> recipeIssues(Catalog cat, Recipe r) {
        List<String> out = new ArrayList<>();
        if (r == null) return out;
        for (var i : r.ingredients()) {
            if (i.pantry()) continue;
            Catalog.Product p = cat.products().get(i.productKey());
            if (p == null) continue;
            double need = i.share() * p.qty();
            String a = i.amount();
            if (a == null) continue;
            Matcher m = AMOUNT.matcher(a);
            if (!m.find()) continue;
            double n = switch (m.group(1)) {
                case "fél" -> 0.5;
                case "másfél" -> 1.5;
                case "negyed" -> 0.25;
                default -> Double.parseDouble(m.group(1).replace(',', '.'));
            };
            String u = m.group(2).toLowerCase().replace(",", "");
            Double expected = null;
            if (p.unit().equals("kg")) {
                if (u.equals("dkg")) expected = n / 100;
                else if (u.equals("g")) expected = n / 1000;
                else if (u.equals("kg")) expected = n;
                else if ((u.equals("db") || u.equals("fej")) && p.pieceWeight() > 0) expected = n * p.pieceWeight();
            } else if (p.unit().equals("l")) {
                if (u.equals("dl")) expected = n / 10;
                else if (u.equals("ml")) expected = n / 1000;
                else if (u.equals("l")) expected = n;
            } else if (p.unit().equals("db") && (u.equals("db") || u.equals("szelet") || u.equals("gerezd"))) {
                expected = n;
            }
            if (expected == null || expected <= 0) continue;
            double ratio = need / expected;
            if (ratio < 0.6 || ratio > 1.67) {
                out.add(i.label() + ": a kiírt „" + a + "” és a számolt " + hu.sporolos.core.Packaging.Qty.fmt(need, p.unit())
                        + " nem egyezik");
            }
        }
        return out;
    }

    // ---------- segédek ----------

    private static String trimmed(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }

    private static int indexOf(List<Object> list, String field, String value) {
        for (int i = 0; i < list.size(); i++) {
            if (value != null && value.equals(Json.str(Json.obj(list.get(i)).get(field)))) return i;
        }
        return -1;
    }

    private static Map<String, Object> findById(List<Object> list, String field, String value) {
        int i = indexOf(list, field, value);
        return i < 0 ? null : Json.obj(list.get(i));
    }
}
