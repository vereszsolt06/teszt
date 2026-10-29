package hu.sporolos.data;

import hu.sporolos.core.Json;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Egy lánc saját akciója, ami nem (vagy nem így) szerepel az Árfigyelő napi fájljában:
 * akciós újság, a lánc appja, hűségkártyás ár (Lidl Plus, Clubcard…).
 *
 * Egy akció vagy egy konkrét termékhez (vonalkód) tartozik, vagy egy recept-alapanyaghoz.
 *
 * @param id           egyedi azonosító
 * @param storeId      lánc ("lidl", "spar"…)
 * @param name         a termék neve, ahogy az újságban szerepel
 * @param ingredientKey recept-alapanyag kulcsa (products.json), vagy null
 * @param itemId       vonalkód (Árfigyelő "Termék azonosító"), vagy null
 * @param price        akciós ár (Ft) a megadott kiszerelésre
 * @param regularPrice rendes ár (Ft), vagy 0, ha nem ismert
 * @param unit         kg / l / db
 * @param quantity     kiszerelés az egységben (pl. 0,5 = 500 g); mérlegelős (Ft/kg) árunál 1
 * @param validFrom    első érvényes nap, vagy null
 * @param validTo      utolsó érvényes nap, vagy null
 * @param card         csak hűségkártyával / appal érvényes
 * @param source       honnan jött ("kézi", "fájl: lidl.csv", "feed: …")
 */
public record Promo(String id, String storeId, String name, String ingredientKey, String itemId,
                    double price, double regularPrice, String unit, double quantity,
                    LocalDate validFrom, LocalDate validTo, boolean card, String source) {

    public boolean activeOn(LocalDate day) {
        return (validFrom == null || !day.isBefore(validFrom)) && (validTo == null || !day.isAfter(validTo));
    }

    public Promo withId(String newId, String newSource) {
        return new Promo(newId, storeId, name, ingredientKey, itemId, price, regularPrice, unit, quantity,
                validFrom, validTo, card, newSource);
    }

    /** JSON objektumból (a hiányzó mezők alapértéket kapnak). Hibás adatnál IllegalArgumentException. */
    public static Promo fromJson(Map<String, Object> m, String source) {
        String store = str(m, "store", "bolt");
        String name = str(m, "name", "termek");
        double price = Json.num(first(m, "price", "ar"), Double.NaN);
        if (store == null || store.isBlank()) throw new IllegalArgumentException("Hiányzik a bolt");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Hiányzik a termék neve");
        if (Double.isNaN(price) || price <= 0) throw new IllegalArgumentException("Hibás vagy hiányzó ár: " + name);
        String unit = normalizeUnit(str(m, "unit", "egyseg"));
        double qty = Json.num(first(m, "quantity", "kiszereles"), 1);
        if (unit.equals("g")) { unit = "kg"; qty /= 1000; }
        if (unit.equals("ml")) { unit = "l"; qty /= 1000; }
        if (unit.equals("dkg")) { unit = "kg"; qty /= 100; }
        if (unit.equals("dl")) { unit = "l"; qty /= 10; }
        if (qty <= 0) throw new IllegalArgumentException("Hibás kiszerelés: " + name);
        Object card = first(m, "card", "kartya");
        boolean isCard = Boolean.TRUE.equals(card) || (card instanceof String c
                && (c.equalsIgnoreCase("igen") || c.equalsIgnoreCase("true") || c.equals("1")));
        return new Promo(str(m, "id"), Catalog.normalize(store).replace(' ', '-'), name.trim(),
                blankToNull(str(m, "ingredient", "alapanyag")), blankToNull(str(m, "itemId", "vonalkod")),
                price, Json.num(first(m, "regularPrice", "rendes_ar"), 0), unit, qty,
                date(str(m, "validFrom", "tol")), date(str(m, "validTo", "ig")), isCard,
                source);
    }

    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("store", storeId);
        m.put("name", name);
        m.put("ingredient", ingredientKey);
        m.put("itemId", itemId);
        m.put("price", price);
        m.put("regularPrice", regularPrice);
        m.put("unit", unit);
        m.put("quantity", quantity);
        m.put("validFrom", validFrom == null ? null : validFrom.toString());
        m.put("validTo", validTo == null ? null : validTo.toString());
        m.put("card", card);
        m.put("source", source);
        return m;
    }

    static String normalizeUnit(String u) {
        if (u == null || u.isBlank()) return "db";
        String n = Catalog.normalize(u).replace(" ", "");
        return switch (n) {
            case "kg", "kilo", "kilogramm", "ftkg" -> "kg";
            case "g", "gr", "gramm" -> "g";
            case "dkg" -> "dkg";
            case "l", "liter" -> "l";
            case "dl" -> "dl";
            case "ml" -> "ml";
            default -> "db";
        };
    }

    private static LocalDate date(String s) {
        if (s == null || s.isBlank()) return null;
        String t = s.trim().replace('.', '-').replaceAll("-+$", "").replace(' ', '-').replaceAll("-+", "-");
        try {
            return LocalDate.parse(t);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Hibás dátum (ÉÉÉÉ-HH-NN kell): " + s);
        }
    }

    private static Object first(Map<String, Object> m, String... keys) {
        for (String k : keys) if (m.get(k) != null) return m.get(k);
        return null;
    }

    private static String str(Map<String, Object> m, String... keys) {
        Object o = first(m, keys);
        return o == null ? null : String.valueOf(o);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
