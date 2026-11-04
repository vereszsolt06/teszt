package hu.sporolos.market;

import java.util.function.Supplier;

/**
 * Egy ország (piac): pénznem, árforrás, boltok.
 *
 * <h3>Belső pénzegység</h3>
 * Minden számítás (receptár, heti terv, küszöbök – pl. „legalább 300 Ft-ot spórolj”) egységesen forint-egyenértékben
 * fut. A nem forintos piacok forrása a betöltéskor a {@link #rate() belső árfolyammal} vált át, a megjelenítés
 * ({@link #money(double)}) pedig vissza. Így a forintra hangolt heurisztikák minden országban ugyanúgy működnek,
 * a felhasználó pedig mindig a saját pénznemét látja. Az árfolyam csak a küszöbök és a becsült (a forrásban nem
 * szereplő) alapanyagok árát befolyásolja, a valós bolti árakat nem: azok oda-vissza pontosan váltódnak.
 *
 * @param id        rövid azonosító („hu”, „hr”)
 * @param name      ország neve magyarul
 * @param flag      zászló-emoji
 * @param currency  ISO pénznem („HUF”, „EUR”)
 * @param symbol    megjelenített jel („Ft”, „€”)
 * @param rate      1 egység pénznem hány belső (forint-egyenértékű) egység
 * @param decimals  tizedesjegyek a megjelenítésben (Ft: 0, €: 2)
 * @param source    az árforrás neve a felületen
 * @param sourceUrl az árforrás honlapja
 * @param beta      kísérleti (a felület jelzi)
 */
public record Market(String id, String name, String flag, String currency, String symbol, double rate,
                     int decimals, String source, String sourceUrl, boolean beta) {

    public static final Market HU = new Market("hu", "Magyarország", "🇭🇺", "HUF", "Ft", 1, 0,
            "GVH Árfigyelő", "https://arfigyelo.gvh.hu", false);

    private static final ThreadLocal<Market> CURRENT = new ThreadLocal<>();

    /** A kérés (vagy háttérszámítás) piaca; ha nincs beállítva, Magyarország. */
    public static Market current() {
        Market m = CURRENT.get();
        return m == null ? HU : m;
    }

    /** A megadott piaccal futtat (a pénzformázás és a szövegek ennek a pénznemével készülnek). */
    public static <T> T with(Market m, Supplier<T> body) {
        Market old = CURRENT.get();
        CURRENT.set(m);
        try {
            return body.get();
        } finally {
            if (old == null) CURRENT.remove();
            else CURRENT.set(old);
        }
    }

    /** A szál piacának beállítása egy kérés idejére; a végén {@link #clear()}. */
    public static void set(Market m) {
        CURRENT.set(m);
    }

    public static void clear() {
        CURRENT.remove();
    }

    /** Belső (forint-egyenértékű) összeg a piac pénznemében. */
    public double toLocal(double internal) {
        return internal / rate;
    }

    /** A piac pénznemében megadott összeg belső egységben. */
    public double toInternal(double local) {
        return local * rate;
    }

    /** Pénzformázás a piac pénznemében: „1 299 Ft”, „12,49 €”. A belső összeget váltja át. */
    public String money(double internal) {
        double v = toLocal(internal);
        if (decimals == 0) return group(Math.round(v)) + NBSP + symbol;
        long cents = Math.round(v * 100);
        long whole = Math.abs(cents) / 100;
        long frac = Math.abs(cents) % 100;
        return (cents < 0 ? "-" : "") + group(whole) + "," + (frac < 10 ? "0" : "") + frac + NBSP + symbol;
    }

    /** Nem törhető szóköz: a szám és a pénznem (és az ezres tagok) ne essenek külön sorba. */
    static final String NBSP = "\u00a0";

    /** Ezres tagolás nem törhető szóközzel („1 299”). */
    static String group(long r) {
        String s = String.valueOf(Math.abs(r));
        StringBuilder sb = new StringBuilder(r < 0 ? "-" : "");
        for (int i = 0; i < s.length(); i++) {
            if (i > 0 && (s.length() - i) % 3 == 0) sb.append('\u00a0');
            sb.append(s.charAt(i));
        }
        return sb.toString();
    }
}
