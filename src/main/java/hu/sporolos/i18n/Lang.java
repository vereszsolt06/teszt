package hu.sporolos.i18n;

import java.util.List;
import java.util.Locale;

/**
 * A kérés nyelve (szálanként, mint a {@link hu.sporolos.market.Market}). A szerver minden szöveget magyarul állít elő
 * (a gyorsítótárazott elemzések így nyelvfüggetlenek), a válasz a határon fordítódik: {@link Translator}.
 */
public final class Lang {

    public static final String HU = "hu", EN = "en";
    /** A támogatott nyelvek, a felület nyelvválasztójának sorrendjében. */
    public static final List<String> SUPPORTED = List.of(HU, EN);

    private static final ThreadLocal<String> CURRENT = ThreadLocal.withInitial(() -> HU);

    private Lang() {}

    public static String current() { return CURRENT.get(); }

    public static boolean english() { return EN.equals(CURRENT.get()); }

    public static void set(String lang) { CURRENT.set(normalize(lang)); }

    public static void clear() { CURRENT.remove(); }

    /** „en-GB”, „EN”, „en_US” → „en”; ismeretlen vagy üres → magyar. */
    public static String normalize(String lang) {
        if (lang == null || lang.isBlank()) return HU;
        String l = lang.trim().toLowerCase(Locale.ROOT);
        int cut = l.indexOf('-') >= 0 ? l.indexOf('-') : l.indexOf('_');
        if (cut > 0) l = l.substring(0, cut);
        return SUPPORTED.contains(l) ? l : HU;
    }

    /**
     * A böngésző Accept-Language fejlécéből: az első támogatott nyelv a preferencia sorrendjében
     * (a q-értékeket a böngészők csökkenő sorrendben küldik). Ha egyik sem támogatott, angol – a magyar
     * felhasználó böngészője úgyis magyart kér elöl, mindenki más pedig angolul ért többet.
     */
    public static String fromAcceptLanguage(String header) {
        if (header == null || header.isBlank()) return HU;
        for (String part : header.split(",")) {
            String tag = part.split(";")[0].trim().toLowerCase(Locale.ROOT);
            if (tag.isEmpty() || tag.equals("*")) continue;
            String base = tag.split("[-_]")[0];
            if (SUPPORTED.contains(base)) return base;
        }
        return EN;
    }
}
