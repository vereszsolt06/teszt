package hu.sporolos.web;

import hu.sporolos.core.Json;
import hu.sporolos.data.RecipeMeta;
import hu.sporolos.store.Ids;
import hu.sporolos.store.Storage;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Egy háztartás beállításai (a NomNom „egyszer beállítod, két perc” lépései: bolt, keret, létszám, diéta,
 * konyha), ellenőrizve és alapértékekkel. Fiók nincs: a profilt egy véletlen azonosító jelöli, amit
 * a böngésző megjegyez, és egy linkkel megosztható a háztartás többi tagjával.
 */
record Household(String store, int people, int dinners, int budget, Set<String> diets, Set<String> equipment,
                 Set<String> stores, boolean bulk, Set<String> cards, boolean onboarded, String cookMode, boolean split,
                 String country) {

    /** Minden nap főz / kétnaponta főz (a maradék másnapra). */
    static final String DAILY = "daily", EVERY_OTHER = "every2";

    static final Set<String> DIETS = Set.of(RecipeMeta.VEGA, RecipeMeta.GLUTEN_FREE, RecipeMeta.LACTOSE_FREE);
    static final Set<String> EQUIPMENT = Set.of(RecipeMeta.STOVE, RecipeMeta.OVEN, RecipeMeta.AIRFRYER);

    static Household defaults() {
        return new Household(null, 2, 5, 15000, Set.of(), Set.of(RecipeMeta.STOVE, RecipeMeta.OVEN),
                Set.of(), false, Set.of(), false, DAILY, false, "hu");
    }

    /**
     * JSON-ból, minden mezőt ellenőrizve; az ismeretlen / hibás értékek helyett alapérték. A boltok az ország
     * láncai közül valók (a keret belső, forint-egyenértékű egységben van, lásd {@link hu.sporolos.market.Market}).
     */
    static Household from(Map<String, Object> m, hu.sporolos.market.Markets markets) {
        String country = m == null || m.get("country") == null ? "hu" : Json.str(m.get("country"));
        if (!markets.has(country)) country = "hu";
        return from(m, markets.get(country).storeIds(), country);
    }

    /** Csak magyar boltokkal (tesztekhez és a régi hívókhoz). */
    static Household from(Map<String, Object> m, Collection<String> knownStores) {
        return from(m, knownStores, "hu");
    }

    static Household from(Map<String, Object> m, Collection<String> knownStores, String country) {
        Household d = defaults();
        if (m == null) return new Household(d.store(), d.people(), d.dinners(), d.budget(), d.diets(), d.equipment(),
                d.stores(), d.bulk(), d.cards(), d.onboarded(), d.cookMode(), d.split(), country);
        String store = m.get("store") == null ? null : Json.str(m.get("store"));
        if (store != null && !knownStores.contains(store)) store = null;
        Set<String> equipment = subset(m.get("equipment"), EQUIPMENT);
        return new Household(store,
                clamp(m.get("people"), d.people(), 1, 12),
                clamp(m.get("dinners"), d.dinners(), 1, 7),
                clamp(m.get("budget"), d.budget(), 0, 1_000_000),
                subset(m.get("diets"), DIETS),
                m.containsKey("equipment") && !equipment.isEmpty() ? equipment : d.equipment(),
                subset(m.get("stores"), Set.copyOf(knownStores)),
                Boolean.TRUE.equals(m.get("bulk")),
                subset(m.get("cards"), Set.copyOf(knownStores)),
                Boolean.TRUE.equals(m.get("onboarded")),
                EVERY_OTHER.equals(m.get("cookMode")) ? EVERY_OTHER : DAILY,
                Boolean.TRUE.equals(m.get("split")), country);
    }

    Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("store", store);
        m.put("people", people);
        m.put("dinners", dinners);
        m.put("budget", budget);
        m.put("diets", new ArrayList<>(diets));
        m.put("equipment", new ArrayList<>(equipment));
        m.put("stores", new ArrayList<>(stores));
        m.put("bulk", bulk);
        m.put("cards", new ArrayList<>(cards));
        m.put("onboarded", onboarded);
        m.put("cookMode", cookMode);
        m.put("split", split);
        m.put("country", country);
        return m;
    }

    /** A bolt null: automatikus – ahol a hét a legolcsóbb. */
    boolean autoStore() {
        return store == null;
    }

    /** A hét újratervezését kívánó beállítások „ujjlenyomata”: ha ez változik, új hetet kell tervezni. */
    String planKey() {
        return (country.equals("hu") ? "" : country + "|") + store + "|" + people + "|" + dinners + "|" + budget + "|" + diets
                + "|" + equipment + "|" + stores + "|" + bulk + "|" + cards + "|" + cookMode;
    }

    static Household load(Storage storage, String id, hu.sporolos.market.Markets markets) throws IOException {
        Map<String, Object> m = storage.profile(Ids.check(id));
        if (m == null) throw new WebServer.NotFound("Nincs ilyen háztartás-profil");
        return from(m, markets);
    }

    private static int clamp(Object o, int def, int min, int max) {
        double v = Json.num(o, def);
        if (Double.isNaN(v)) return def;
        return (int) Math.max(min, Math.min(max, Math.round(v)));
    }

    private static Set<String> subset(Object o, Set<String> allowed) {
        Set<String> out = new TreeSet<>();
        if (o == null) return out;
        try {
            for (Object x : Json.arr(o)) {
                String v = Json.str(x);
                if (allowed.contains(v)) out.add(v);
            }
        } catch (RuntimeException ignored) {
            // nem lista – üres
        }
        return out;
    }

    static List<String> strings(Object o) {
        List<String> out = new ArrayList<>();
        if (o == null) return out;
        for (Object x : Json.arr(o)) out.add(x == null ? null : Json.str(x));
        return out;
    }
}
