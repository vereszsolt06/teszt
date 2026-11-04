package hu.sporolos.market;

import hu.sporolos.core.Json;
import hu.sporolos.data.Catalog;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Egy nem magyar piac leírása (resources/markets/{id}.json): pénznem, láncok, az alapanyagok névalapú
 * illesztése és a keresés magyar → helyi szinonimái.
 */
public final class MarketDef {

    /** A névalapú illesztésnél az alapanyagok saját „kategóriát” kapnak: ettől az azonosítótól számozva. */
    public static final int CATEGORY_BASE = 1000;

    private final Market market;
    private final List<Map<String, Object>> stores;
    private final NameRules rules;
    private final Set<String> skipChains;
    private final String archiveList;
    private final Map<String, String> search;

    public MarketDef(String json) {
        Map<String, Object> m = Json.obj(Json.parse(json));
        market = new Market(Json.str(m.get("id")), Json.str(m.get("name")), Json.str(m.get("flag")),
                Json.str(m.get("currency")), Json.str(m.get("symbol")), Json.num(m.get("rate"), 1),
                (int) Json.num(m.get("decimals"), 2), Json.str(m.get("source")), Json.str(m.get("sourceUrl")),
                Boolean.TRUE.equals(m.get("beta")));
        List<Map<String, Object>> st = new ArrayList<>();
        for (Object o : Json.arr(m.get("stores"))) st.add(Json.obj(o));
        stores = List.copyOf(st);
        rules = new NameRules(m);
        Set<String> skip = new TreeSet<>();
        for (Object o : Json.arr(m.get("skipChains"))) skip.add(Json.str(o));
        skipChains = Set.copyOf(skip);
        archiveList = m.get("archiveList") == null ? null : Json.str(m.get("archiveList"));
        Map<String, String> s = new LinkedHashMap<>();
        if (m.get("search") != null) {
            for (Map.Entry<String, Object> e : Json.obj(m.get("search")).entrySet()) {
                s.put(Catalog.normalize(e.getKey()), Catalog.normalize(Json.str(e.getValue())));
            }
        }
        search = Map.copyOf(s);
    }

    public static MarketDef resource(String id) {
        try (InputStream in = MarketDef.class.getResourceAsStream("/markets/" + id + ".json")) {
            if (in == null) throw new IllegalArgumentException("Ismeretlen ország: " + id);
            return new MarketDef(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Nem sikerült betölteni: markets/" + id + ".json", e);
        }
    }

    public Market market() { return market; }
    public NameRules rules() { return rules; }
    public Set<String> skipChains() { return skipChains; }
    public String archiveList() { return archiveList; }
    /** Magyar keresőszó → helyi (normalizált), pl. „kave” → „kava”. */
    public Map<String, String> search() { return search; }

    public List<String> storeIds() {
        return stores.stream().map(s -> Json.str(s.get("id"))).toList();
    }

    /**
     * A piac katalógusa a közös (magyar) alapanyag- és receptlistából: a receptek, kiszerelések, tápértékek
     * ugyanazok, csak a boltok és az illesztés helyi. Amelyik alapanyaghoz nincs helyi szabály, az nem követett
     * (becsült árral szerepel, mint itthon a tonhal).
     */
    public Catalog deriveCatalog(String productsJson, String recipesJson) {
        Map<String, Object> root = new LinkedHashMap<>(Json.obj(Json.parse(productsJson)));
        Map<String, Integer> idx = new LinkedHashMap<>();
        for (NameRules.Rule r : rules.rules()) idx.put(r.key(), r.index());
        List<Object> products = new ArrayList<>();
        for (Object o : Json.arr(root.get("products"))) {
            Map<String, Object> p = new LinkedHashMap<>(Json.obj(o));
            Integer i = idx.get(Json.str(p.get("key")));
            p.put("categoryIds", i == null ? List.of() : List.of((double) (CATEGORY_BASE + i)));
            p.put("keywords", List.of());
            p.put("exclude", List.of());
            products.add(p);
        }
        root.put("products", products);
        List<Object> st = new ArrayList<>();
        for (Map<String, Object> s : stores) {
            Map<String, Object> x = new LinkedHashMap<>(s);
            x.putIfAbsent("factor", 1.0);
            st.add(x);
        }
        root.put("stores", st);
        return new Catalog(Json.write(root), recipesJson);
    }
}
