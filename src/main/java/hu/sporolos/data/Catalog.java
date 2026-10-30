package hu.sporolos.data;

import hu.sporolos.core.Json;
import hu.sporolos.model.Models.Ingredient;
import hu.sporolos.model.Models.PriceEntry;
import hu.sporolos.model.Models.Recipe;
import hu.sporolos.model.Models.Store;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A recept-alapanyag katalógus, az üzletláncok és a receptek (resources/data/*.json),
 * valamint az Árfigyelő-termékek alapanyagokhoz rendelése (kategória + opcionális kulcsszó).
 */
public final class Catalog {

    /**
     * Recept-alapanyag. Ha categoryIds üres, az Árfigyelő nem követi, ilyenkor basePrice a becslés.
     *
     * @param keywords        ha nem üres, a bolti termék nevének tartalmaznia kell valamelyiket
     * @param excludeKeywords ha a bolti termék neve ezek valamelyikét tartalmazza, nem illeszkedik
     * @param pieceWeight     egy darab tömege kg-ban (darabos ↔ kilós termékek átváltásához; 0 = ismeretlen)
     * @param bunchWeight     egy "csomós" (1 db-os) kiszerelés tömege kg-ban (pl. csomós répa); 0 = nincs
     * @param staple          alapvető kamraáru (olaj, liszt, cukor), ami többnyire otthon van
     * @param packaged        csak csomagolva kapható (sosem mérlegelős)
     * @param maxPack         a háztartási kiszerelés felső határa az alapanyag egységében; 0 = qty × 1,5
     * @param nutrition       [kcal, fehérje g, szénhidrát g, zsír g] 100 g-ra (folyadéknál 100 ml-re), vagy null
     * @param aisle           bolti sor a bevásárlólistához ("Zöldség, gyümölcs", "Hús, hal"…)
     * @param contains        "hus", "hal", "glutén", "laktóz" – a diétaszűrőkhöz
     */
    public record Product(String key, String name, double qty, String unit, double basePrice,
                          Set<Integer> categoryIds, List<String> keywords, List<String> excludeKeywords,
                          double pieceWeight, double bunchWeight, boolean staple, boolean packaged,
                          double maxPack, double[] nutrition, String aisle, Set<String> contains) {

        /** Egy mennyiség (az alapanyag egységében) grammban; db-nál a darabtömeggel, ismeretlennél NaN. */
        public double grams(double amount) {
            return switch (unit) {
                case "kg", "l" -> amount * 1000;
                case "db" -> pieceWeight > 0 ? amount * pieceWeight * 1000 : Double.NaN;
                default -> Double.NaN;
            };
        }
        public boolean tracked() {
            return !categoryIds.isEmpty();
        }

        /** A háztartási kiszerelés felső határa. */
        public double householdMax() {
            return maxPack > 0 ? maxPack : qty * 1.5;
        }
    }

    private static double[] nutrition(Object o) {
        List<Object> l = Json.arr(o);
        if (l.size() != 4) return null;
        double[] n = new double[4];
        for (int i = 0; i < 4; i++) n[i] = Json.num(l.get(i), 0);
        return n;
    }

    /** Lánc a demó-szorzóval együtt. */
    public record StoreInfo(Store store, double factor) {}

    private final Map<String, Product> products = new LinkedHashMap<>();
    private final Map<String, StoreInfo> stores = new LinkedHashMap<>();
    private final List<Recipe> recipes = new ArrayList<>();
    private final int foodCategoryMaxId;
    private final List<String> aisles = new ArrayList<>();
    private final Map<String, RecipeMeta> meta = new LinkedHashMap<>();

    public static Catalog loadDefault() {
        try {
            return new Catalog(readResource("/data/products.json"), readResource("/data/recipes.json"));
        } catch (IOException e) {
            throw new IllegalStateException("Nem sikerült betölteni a beépített adatokat", e);
        }
    }

    public Catalog(String productsJson, String recipesJson) {
        Map<String, Object> root = Json.obj(Json.parse(productsJson));
        foodCategoryMaxId = (int) Json.num(root.get("foodCategoryMaxId"), 122);
        for (Object a : Json.arr(root.get("aisles"))) aisles.add(Json.str(a));
        for (Object o : Json.arr(root.get("stores"))) {
            Map<String, Object> s = Json.obj(o);
            Store st = new Store(Json.str(s.get("id")), Json.str(s.get("name")), Json.str(s.get("color")),
                    Json.str(s.get("card")));
            stores.put(st.id(), new StoreInfo(st, Json.num(s.get("factor"), 1.0)));
        }
        for (Object o : Json.arr(root.get("products"))) {
            Map<String, Object> p = Json.obj(o);
            List<String> kws = Json.arr(p.get("keywords")).stream().map(k -> normalize(Json.str(k))).toList();
            List<String> excl = Json.arr(p.get("exclude")).stream().map(k -> normalize(Json.str(k))).toList();
            Set<Integer> cats = Json.arr(p.get("categoryIds")).stream()
                    .map(c -> (int) Json.num(c, -1)).collect(Collectors.toUnmodifiableSet());
            Product prod = new Product(Json.str(p.get("key")), Json.str(p.get("name")),
                    Json.num(p.get("qty"), 1), Json.str(p.get("unit")), Json.num(p.get("basePrice"), 0),
                    cats, kws, excl, Json.num(p.get("pieceWeight"), 0), Json.num(p.get("bunchWeight"), 0),
                    Boolean.TRUE.equals(p.get("staple")), Boolean.TRUE.equals(p.get("packaged")),
                    Json.num(p.get("maxPack"), 0), nutrition(p.get("nutrition")),
                    p.get("aisle") == null ? "Egyéb" : Json.str(p.get("aisle")),
                    Json.arr(p.get("contains")).stream().map(Json::str).collect(Collectors.toUnmodifiableSet()));
            if (products.containsKey(prod.key())) throw new IllegalStateException("Duplikált alapanyag: " + prod.key());
            products.put(prod.key(), prod);
        }

        Map<String, Object> rroot = Json.obj(Json.parse(recipesJson));
        for (Object o : Json.arr(rroot.get("recipes"))) {
            Map<String, Object> r = Json.obj(o);
            List<Ingredient> ings = new ArrayList<>();
            for (Object io : Json.arr(r.get("ingredients"))) {
                Map<String, Object> i = Json.obj(io);
                String key = Json.str(i.get("key"));
                boolean pantry = Boolean.TRUE.equals(i.get("pantry"));
                Product prod = products.get(key);
                if (!pantry && prod == null) {
                    throw new IllegalStateException("A(z) '" + r.get("id") + "' recept ismeretlen alapanyagot használ: " + key);
                }
                String label = i.get("label") != null ? Json.str(i.get("label")) : prod.name();
                // a mennyiség megadható abszolút értékben ("need", az alapanyag egységében: kg / l / db)
                // vagy a katalógus-kiszerelés hányadaként ("share")
                double share = i.containsKey("need") && prod != null
                        ? Json.num(i.get("need"), 0) / prod.qty()
                        : Json.num(i.get("share"), 0);
                if (!pantry && share <= 0) {
                    throw new IllegalStateException("A(z) '" + r.get("id") + "' recept " + key + " hozzávalójának nincs mennyisége");
                }
                ings.add(new Ingredient(key, label, Json.str(i.get("amount")), share, pantry,
                        prod != null && prod.staple()));
            }
            List<String> tags = Json.arr(r.get("tags")).stream().map(Json::str).toList();
            List<String> steps = Json.arr(r.get("steps")).stream().map(Json::str).toList();
            if (recipes.stream().anyMatch(x -> x.id().equals(Json.str(r.get("id"))))) {
                throw new IllegalStateException("Duplikált recept-azonosító: " + r.get("id"));
            }
            Recipe recipe = new Recipe(Json.str(r.get("id")), Json.str(r.get("name")), Json.str(r.get("emoji")),
                    (int) Json.num(r.get("minutes"), 30), (int) Json.num(r.get("servings"), 4),
                    Json.str(r.get("difficulty")), tags, ings, steps);
            recipes.add(recipe);
            meta.put(recipe.id(), RecipeMeta.of(recipe, products, Boolean.TRUE.equals(r.get("airfryer"))));
        }
    }

    public Map<String, Product> products() { return products; }
    public List<String> aisles() { return aisles; }
    public RecipeMeta meta(String recipeId) { return meta.get(recipeId); }
    public Map<String, StoreInfo> stores() { return stores; }
    public List<Recipe> recipes() { return recipes; }

    public boolean isFoodCategory(int categoryId) {
        return categoryId > 0 && categoryId <= foodCategoryMaxId;
    }

    /** Illeszkedik-e egy bolti termék a recept-alapanyagra (kategória + ha van, kulcsszó a névben). */
    public boolean matches(Product p, PriceEntry e) {
        if (!p.categoryIds().contains(e.categoryId())) return false;
        if (p.keywords().isEmpty() && p.excludeKeywords().isEmpty()) return true;
        String n = " " + normalize(e.productName()) + " ";
        for (String kw : p.excludeKeywords()) if (n.contains(kw)) return false;
        if (p.keywords().isEmpty()) return true;
        for (String kw : p.keywords()) if (n.contains(kw)) return true;
        return false;
    }

    /**
     * Üzletlánc azonosító a névből ("SPAR" → "spar"); ismeretlen láncot felvesz,
     * hogy új láncok megjelenése se törje el az appot.
     */
    public synchronized String resolveStore(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String n = normalize(raw).trim();
        for (StoreInfo s : stores.values()) {
            if (n.equals(s.store().id()) || n.equals(normalize(s.store().name()).trim())) return s.store().id();
        }
        for (StoreInfo s : stores.values()) {
            if (n.contains(s.store().id())) return s.store().id();
        }
        String id = n.replace(' ', '-');
        String[] palette = {"#5b6b5f", "#7a4e8c", "#a0522d", "#2f6f73", "#8a6d1d"};
        stores.put(id, new StoreInfo(new Store(id, raw.trim(), palette[Math.floorMod(id.hashCode(), palette.length)], null), 1.0));
        return id;
    }

    /** Kisbetűsít, ékezetet töröl, a nem betű/szám karaktereket szóközre cseréli. */
    public static String normalize(String s) {
        if (s == null) return "";
        return Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replaceAll("[^a-z0-9]+", " ")
                .trim();
    }

    static String readResource(String path) throws IOException {
        try (InputStream in = Catalog.class.getResourceAsStream(path)) {
            if (in == null) throw new IOException("Hiányzó erőforrás: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
