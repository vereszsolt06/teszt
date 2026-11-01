package hu.sporolos.data;

import hu.sporolos.core.Json;
import hu.sporolos.store.Storage;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Honnan jön a receptek és az alapanyagok listája: a beépített products.json / recipes.json, vagy
 * – ha az adminban módosították – a tárolóban (fájl vagy PostgreSQL) elmentett változat.
 *
 * Minden módosítás előtt egy teljes Catalog épül a módosított adatokból: ha hibás (pl. ismeretlen
 * alapanyag, hiányzó mennyiség), a mentés elmarad, és a hiba visszamegy az adminnak.
 */
public final class CatalogSource {

    public static final String PRODUCTS = "catalog-products";
    public static final String RECIPES = "catalog-recipes";

    private final Storage storage;
    private String productsJson;
    private String recipesJson;
    private boolean modified;

    public CatalogSource(Storage storage) {
        this.storage = storage;
    }

    /** Betöltés: a mentett változat, ha van és érvényes, különben a beépített. */
    public synchronized Catalog load() {
        String p = null, r = null;
        try {
            if (storage != null) {
                p = storage.document(PRODUCTS);
                r = storage.document(RECIPES);
            }
        } catch (IOException | RuntimeException e) {
            System.err.println("[katalógus] A mentett receptek nem olvashatók: " + e.getMessage());
        }
        try {
            productsJson = p != null ? p : Catalog.readResource("/data/products.json");
            recipesJson = r != null ? r : Catalog.readResource("/data/recipes.json");
            modified = p != null || r != null;
            return new Catalog(productsJson, recipesJson);
        } catch (IOException e) {
            throw new IllegalStateException("A beépített adatok nem olvashatók", e);
        } catch (RuntimeException e) {
            if (p == null && r == null) throw e;
            System.err.println("[katalógus] A mentett változat hibás, a beépítettet használom: " + e.getMessage());
            modified = false;
            return Catalog.loadDefault();
        }
    }

    public synchronized boolean modified() {
        return modified;
    }

    /** Az alapanyagok JSON-gyökere (másolat, szabadon módosítható). */
    public synchronized Map<String, Object> products() {
        return deepCopy(Json.obj(Json.parse(productsJson)));
    }

    public synchronized Map<String, Object> recipes() {
        return deepCopy(Json.obj(Json.parse(recipesJson)));
    }

    /**
     * Új adatok érvényesítése és mentése.
     *
     * @return az új katalógus
     * @throws IllegalArgumentException ha az adatokból nem építhető katalógus
     */
    public synchronized Catalog apply(Map<String, Object> productsRoot, Map<String, Object> recipesRoot) throws IOException {
        String p = pretty(productsRoot, "products");
        String r = pretty(recipesRoot, "recipes");
        Catalog c;
        try {
            c = new Catalog(p, r);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(e.getMessage() == null ? e.toString() : e.getMessage());
        }
        if (storage == null) throw new IOException("Nincs tároló a módosítások mentéséhez");
        storage.saveDocument(PRODUCTS, p);
        storage.saveDocument(RECIPES, r);
        productsJson = p;
        recipesJson = r;
        modified = true;
        return c;
    }

    /** Vissza a beépített receptekhez és alapanyagokhoz. */
    public synchronized Catalog reset() throws IOException {
        if (storage != null) {
            storage.deleteDocument(PRODUCTS);
            storage.deleteDocument(RECIPES);
        }
        return load();
    }

    /** Ugyanaz a formátum, mint a beépített fájlokban: egy elem soronként, így diffelhető és kézzel is szerkeszthető. */
    public static String pretty(Map<String, Object> root, String listKey) {
        StringBuilder sb = new StringBuilder("{\n");
        int i = 0;
        for (Map.Entry<String, Object> e : root.entrySet()) {
            sb.append("  ").append(Json.write(e.getKey())).append(": ");
            if (e.getKey().equals(listKey)) {
                List<Object> list = Json.arr(e.getValue());
                sb.append("[\n");
                for (int k = 0; k < list.size(); k++) {
                    sb.append(Json.write(list.get(k))).append(k < list.size() - 1 ? ",\n" : "\n");
                }
                sb.append("  ]");
            } else {
                sb.append(Json.write(e.getValue()));
            }
            sb.append(++i < root.size() ? ",\n" : "\n");
        }
        return sb.append("}\n").toString();
    }

    @SuppressWarnings("unchecked")
    static <T> T deepCopy(T o) {
        if (o instanceof Map<?, ?> m) {
            Map<String, Object> c = new LinkedHashMap<>();
            m.forEach((k, v) -> c.put(String.valueOf(k), deepCopy(v)));
            return (T) c;
        }
        if (o instanceof List<?> l) {
            List<Object> c = new ArrayList<>();
            for (Object x : l) c.add(deepCopy(x));
            return (T) c;
        }
        return o;
    }
}
