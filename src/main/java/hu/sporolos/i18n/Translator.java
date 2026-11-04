package hu.sporolos.i18n;

import hu.sporolos.core.Json;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Az API-válaszok fordítása a határon. A szerver minden szöveget magyarul állít elő (így a gyorsítótárazott
 * elemzések, akció-indoklások nyelvfüggetlenek); kiküldés előtt ez az osztály a JSON-fa megjelenített szövegeit
 * cseréli le a {@code resources/i18n/{nyelv}.json} alapján:
 * <ol>
 *   <li>pontos egyezés: receptnév, lépés, alapanyagnév, kifejezés (bolti osztály, napnév, megjegyzés…);</li>
 *   <li>vesszős fűszerlista szavanként („Pirospaprika, só, bors” → „Paprika, salt, pepper”);</li>
 *   <li>a generált mondatokban ({@link #PATTERN_KEYS}) sorban a minták, a mértékegységek (egyes/többes szám),
 *       a tizedesvessző és a pénzformátum („1 299 Ft” → „1,299 Ft”, „12,49 €” → „€12.49”).</li>
 * </ol>
 * Az azonosítók, a szűrőkulcsok és a felhasználó saját szövegei ({@link #SKIP_KEYS}) változatlanok; ahol egy
 * „label” megváltozik, mellé kerül az eredeti „labelKey”-ként, mert a közös bevásárlólista pipái ezzel azonosítják a
 * tételt – így egy háztartás tagjai különböző nyelven is ugyanazt a listát pipálják. Ami nincs a szótárban, magyar marad.
 */
public final class Translator {

    /** Ezekhez a kulcsokhoz tartozó értékeket (és ami alattuk van) nem fordítjuk. */
    static final Set<String> SKIP_KEYS = Set.of("id", "key", "labelKey", "store", "itemId", "country", "cookMode", "listKey",
            "color", "date", "today", "loadedAt", "tags", "moods", "diets", "unit", "type", "url", "sourceUrl", "flag",
            "emoji", "currency", "symbol", "checked", "liked", "disliked", "extras", "settings", "storage", "recipe_id",
            "lang", "languages");
    /** Generált mondatok és mennyiségek: itt a minták és a számformátum is fut. */
    static final Set<String> PATTERN_KEYS = Set.of("amount", "buyText", "priceText", "reason", "notes", "error", "phase",
            "source", "text", "warnings", "packLabel", "label", "pantry", "leftover", "needed");

    private static final Map<String, Translator> CACHE = new ConcurrentHashMap<>();

    private final String lang;
    private final Map<String, String> exact = new HashMap<>();
    private final Map<String, String> words = new HashMap<>();
    private final List<Map.Entry<Pattern, String>> patterns = new ArrayList<>();
    private final Map<String, String[]> units = new HashMap<>();
    private final Pattern unitPattern;

    private static final Pattern DECIMAL = Pattern.compile("(\\d),(\\d)");
    /** A magyar konyhai mértékegységek, amiket külföldön nem használnak: dkg → g, dl → ml. */
    private static final Pattern DKG_DL = Pattern.compile("(\\d+(?:,\\d+)?)\\s*(dkg|dl)(?![\\p{L}])", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern MONEY = Pattern.compile("([-−]?)(\\d{1,3}(?: \\d{3})*)(?:\\.(\\d{2}))? (Ft|€)");

    Translator(String lang, String dictJson, String recipesJson, String productsJson) {
        this.lang = lang;
        Map<String, Object> d = Json.obj(Json.parse(dictJson));
        Map<String, Object> recipes = d.get("recipes") == null ? Map.of() : Json.obj(d.get("recipes"));
        if (recipesJson != null) {
            for (Object o : Json.arr(Json.obj(Json.parse(recipesJson)).get("recipes"))) {
                Map<String, Object> r = Json.obj(o);
                Object t = recipes.get(Json.str(r.get("id")));
                if (t == null) continue;
                Map<String, Object> tr = Json.obj(t);
                if (tr.get("name") != null) exact.put(Json.str(r.get("name")), Json.str(tr.get("name")));
                List<Object> hu = Json.arr(r.get("steps")), en = tr.get("steps") == null ? List.of() : Json.arr(tr.get("steps"));
                for (int i = 0; i < Math.min(hu.size(), en.size()); i++) exact.put(Json.str(hu.get(i)), Json.str(en.get(i)));
            }
        }
        Map<String, Object> ingredients = d.get("ingredients") == null ? Map.of() : Json.obj(d.get("ingredients"));
        if (productsJson != null) {
            for (Object o : Json.arr(Json.obj(Json.parse(productsJson)).get("products"))) {
                Map<String, Object> p = Json.obj(o);
                Object t = ingredients.get(Json.str(p.get("key")));
                if (t != null) exact.put(Json.str(p.get("name")), Json.str(t));
            }
        }
        if (d.get("phrases") != null) for (var e : Json.obj(d.get("phrases")).entrySet()) exact.put(e.getKey(), Json.str(e.getValue()));
        if (d.get("words") != null) {
            for (var e : Json.obj(d.get("words")).entrySet()) words.put(e.getKey().toLowerCase(Locale.ROOT), Json.str(e.getValue()));
        }
        if (d.get("patterns") != null) {
            for (Object o : Json.arr(d.get("patterns"))) {
                List<Object> p = Json.arr(o);
                patterns.add(Map.entry(Pattern.compile(Json.str(p.get(0)), Pattern.UNICODE_CHARACTER_CLASS), Json.str(p.get(1))));
            }
        }
        if (d.get("units") != null) {
            for (var e : Json.obj(d.get("units")).entrySet()) {
                List<Object> f = Json.arr(e.getValue());
                units.put(e.getKey(), new String[] {Json.str(f.get(0)), Json.str(f.get(f.size() - 1))});
            }
        }
        List<String> keys = new ArrayList<>(units.keySet());
        keys.sort(Comparator.comparingInt(String::length).reversed());
        StringBuilder alt = new StringBuilder();
        for (String k : keys) alt.append(alt.isEmpty() ? "" : "|").append(Pattern.quote(k));
        unitPattern = keys.isEmpty() ? null : Pattern.compile(
                "(\\d+(?:\\.\\d+)?|½|1½|a few)\\s+(" + alt + ")(?![\\p{L}])|/(" + alt + ")(?![\\p{L}])",
                Pattern.UNICODE_CHARACTER_CLASS);
    }

    /** A nyelv fordítója (magyarra null: nincs mit fordítani). */
    public static Translator forLang(String lang) {
        String l = Lang.normalize(lang);
        if (l.equals(Lang.HU)) return null;
        return CACHE.computeIfAbsent(l, Translator::load);
    }

    private static Translator load(String lang) {
        return new Translator(lang, resource("/i18n/" + lang + ".json"), resource("/data/recipes.json"), resource("/data/products.json"));
    }

    private static String resource(String path) {
        try (InputStream in = Translator.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("Hiányzó erőforrás: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Nem olvasható: " + path, e);
        }
    }

    public String lang() { return lang; }

    /** Van-e pontos fordítás erre a szövegre (receptnév, lépés, alapanyag, kifejezés)? */
    public boolean knows(String s) { return exact.containsKey(s); }

    /** A JSON-fa fordított másolata (az eredeti, esetleg gyorsítótárazott objektum nem változik). */
    public Object apply(Object json) {
        return walk(json, null);
    }

    @SuppressWarnings("unchecked")
    private Object walk(Object o, String key) {
        if (o instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                String k = String.valueOf(e.getKey());
                Object v = e.getValue();
                if (SKIP_KEYS.contains(k)) {
                    out.put(k, v);
                    continue;
                }
                Object t = walk(v, k);
                out.put(k, t);
                if (k.equals("label") && v instanceof String s && !s.equals(t) && !m.containsKey("labelKey")) out.put("labelKey", s);
            }
            return out;
        }
        if (o instanceof List<?> l) {
            List<Object> out = new ArrayList<>(l.size());
            for (Object x : l) out.add(walk(x, key));
            return out;
        }
        if (o instanceof String s) return text(s, key != null && PATTERN_KEYS.contains(key));
        return o;
    }

    /** Egy szöveg fordítása; {@code generated}: mondat vagy mennyiség, amire a minták is futnak. */
    public String text(String s, boolean generated) {
        if (s == null || s.isEmpty()) return s;
        String t = exact.get(s);
        if (t != null) return t;
        String w = wordList(s);
        if (w != null) return w;
        if (!generated) return s;
        String r = metric(s);
        for (Map.Entry<Pattern, String> p : patterns) r = p.getKey().matcher(r).replaceAll(p.getValue());
        r = DECIMAL.matcher(r).replaceAll("$1.$2");
        if (unitPattern != null) r = units(r);
        r = money(r);
        return r;
    }

    /** „Pirospaprika, só, bors” → „Paprika, salt, pepper”, ha minden tagja ismert (különben null). */
    private String wordList(String s) {
        if (words.isEmpty()) return null;
        String[] parts = s.split(",\\s*");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            String w = words.get(part.trim().toLowerCase(Locale.ROOT));
            if (w == null) return null;
            sb.append(sb.isEmpty() ? "" : ", ").append(w);
        }
        String out = sb.toString();
        if (!s.isEmpty() && Character.isUpperCase(s.charAt(0)) && !out.isEmpty()) {
            out = Character.toUpperCase(out.charAt(0)) + out.substring(1);
        }
        return out;
    }

    private String units(String s) {
        Matcher m = unitPattern.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String rep;
            if (m.group(3) != null) {
                rep = "/" + units.get(m.group(3))[0];
            } else {
                String n = m.group(1);
                boolean one = n.equals("1") || n.equals("½");
                rep = n + " " + units.get(m.group(2))[one ? 0 : 1];
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** „9,4 dkg” → „94 g”, „1,5 dl” → „150 ml”. */
    static String metric(String s) {
        Matcher m = DKG_DL.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            double v = Double.parseDouble(m.group(1).replace(',', '.'));
            boolean dkg = m.group(2).equals("dkg");
            long x = Math.round(v * (dkg ? 10 : 100));
            String rep = dkg ? (x >= 1000 && x % 100 == 0 ? (x / 1000.0 + "").replace(".0", "") + " kg" : x + " g")
                    : (x >= 1000 && x % 100 == 0 ? (x / 1000.0 + "").replace(".0", "") + " l" : x + " ml");
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** „1 299 Ft” → „1,299 Ft”; „12.49 €” (a tizedesvessző már pont) → „€12.49”. */
    static String money(String s) {
        Matcher m = MONEY.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String whole = m.group(2).replace(' ', ',');
            String cents = m.group(3) == null ? "" : "." + m.group(3);
            String rep = m.group(4).equals("€") ? m.group(1) + "€" + whole + cents : m.group(1) + whole + cents + " Ft";
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
