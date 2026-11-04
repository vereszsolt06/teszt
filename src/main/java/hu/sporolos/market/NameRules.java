package hu.sporolos.market;

import hu.sporolos.core.Json;
import hu.sporolos.data.Catalog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Bolti termék → recept-alapanyag illesztés a terméknév alapján (ott, ahol – mint Horvátországban – a láncoknak
 * nincs közös kategóriarendszerük).
 *
 * <h3>Szabályok</h3>
 * Egy alapanyaghoz minták tartoznak ({@code any}); egy minta szóközzel elválasztott darabokból áll, és akkor
 * illeszkedik, ha a (kisbetűs, ékezet nélküli) névben <i>mindegyik</i> darab megvan, bármilyen sorrendben.
 * A darabok jelölései: {@code ^x} – egy szó így kezdődik, {@code x$} – egy szó így végződik ({@code ^x$} – pontosan ez
 * a szó), {@code <x} – a név első szava így kezdődik (a {@code prefixFillers} előtagokat – „bio”, „svježe”… –
 * átugorva). A {@code not} minták közül bármelyik illeszkedése kizár, ahogy a közös {@code notAny} lista is
 * (állateledel, kozmetikum…). Egység ({@code unit}) és kiszerelés ({@code qty}) is szűrhet.
 * Az első illeszkedő alapanyag nyer, ezért a szűkebbek állnak elöl (pl. a paradicsompüré a paradicsom előtt).
 */
public final class NameRules {

    record Token(String core, boolean wordStart, boolean wordEnd, boolean namePrefix) {
        static Token parse(String t) {
            boolean prefix = t.startsWith("<");
            if (prefix) t = t.substring(1);
            boolean start = t.startsWith("^");
            if (start) t = t.substring(1);
            boolean end = t.endsWith("$");
            if (end) t = t.substring(0, t.length() - 1);
            return new Token(Catalog.normalize(t), start, end, prefix);
        }
    }

    record Phrase(List<Token> tokens) {
        static Phrase parse(String p) {
            List<Token> t = new ArrayList<>();
            for (String s : p.trim().split("\\s+")) {
                if (s.isBlank()) continue;
                Token tok = Token.parse(s);
                if (!tok.core().isEmpty()) t.add(tok);
            }
            return new Phrase(List.copyOf(t));
        }
    }

    /** Egy alapanyag szabálya. */
    public record Rule(String key, int index, List<Phrase> any, List<Phrase> not, Set<String> units,
                       double qtyMin, double qtyMax) {}

    private final List<Rule> rules = new ArrayList<>();
    private final List<Phrase> globalNot = new ArrayList<>();
    private final List<String> fillers = new ArrayList<>();

    public NameRules(Map<String, Object> root) {
        for (Object o : Json.arr(root.get("notAny"))) globalNot.add(Phrase.parse(Json.str(o)));
        for (Object o : Json.arr(root.get("prefixFillers"))) fillers.add(Catalog.normalize(Json.str(o)));
        Map<String, Object> match = root.get("match") == null ? Map.of() : Json.obj(root.get("match"));
        int i = 0;
        for (Map.Entry<String, Object> e : new LinkedHashMap<>(match).entrySet()) {
            Map<String, Object> r = Json.obj(e.getValue());
            List<Phrase> any = new ArrayList<>(), not = new ArrayList<>();
            for (Object p : Json.arr(r.get("any"))) any.add(Phrase.parse(Json.str(p)));
            for (Object p : Json.arr(r.get("not"))) not.add(Phrase.parse(Json.str(p)));
            Set<String> units = new TreeSet<>();
            for (Object u : Json.arr(r.get("unit"))) units.add(Json.str(u));
            List<Object> q = Json.arr(r.get("qty"));
            double min = q.size() == 2 ? Json.num(q.get(0), 0) : 0;
            double max = q.size() == 2 ? Json.num(q.get(1), Double.MAX_VALUE) : Double.MAX_VALUE;
            rules.add(new Rule(e.getKey(), i++, List.copyOf(any), List.copyOf(not), units, min, max));
        }
    }

    public List<Rule> rules() {
        return rules;
    }

    /** Melyik alapanyag (szabály) illeszkedik a termékre, vagy null. */
    public Rule classify(String productName, String unit, double qty) {
        String n = " " + Catalog.normalize(productName) + " ";
        List<String> head = headWords(n);
        for (Phrase p : globalNot) if (matches(n, head, p)) return null;
        for (Rule r : rules) {
            if (!r.units().isEmpty() && !r.units().contains(unit)) continue;
            if (qty > 0 && (qty < r.qtyMin() - 1e-9 || qty > r.qtyMax() + 1e-9)) continue;
            boolean ok = false;
            for (Phrase p : r.any()) if (matches(n, head, p)) { ok = true; break; }
            if (!ok) continue;
            boolean excluded = false;
            for (Phrase p : r.not()) if (matches(n, head, p)) { excluded = true; break; }
            if (!excluded) return r;
        }
        return null;
    }

    /** A név első szavai: az első „valódi” szó, előtte az átugorható előtagokkal. */
    private List<String> headWords(String padded) {
        List<String> out = new ArrayList<>();
        for (String w : padded.trim().split(" ")) {
            if (w.isEmpty()) continue;
            out.add(w);
            if (!isFiller(w) || out.size() >= 4) break;
        }
        return out;
    }

    private boolean isFiller(String w) {
        for (String f : fillers) {
            if (w.equals(f) || f.length() >= 4 && w.startsWith(f)) return true;
        }
        return false;
    }

    private static boolean matches(String padded, List<String> head, Phrase p) {
        if (p.tokens().isEmpty()) return false;
        for (Token t : p.tokens()) if (!has(padded, head, t)) return false;
        return true;
    }

    private static boolean has(String padded, List<String> head, Token t) {
        if (t.namePrefix()) {
            for (String w : head) {
                if (t.wordEnd() ? w.equals(t.core()) : w.startsWith(t.core())) return true;
            }
            return false;
        }
        String needle = (t.wordStart() ? " " : "") + t.core() + (t.wordEnd() ? " " : "");
        return padded.contains(needle);
    }
}
