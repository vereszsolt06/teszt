package hu.sporolos.core;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A receptben szereplő emberi mennyiségek ("8 szelet", "1,5 dl", "fél vekni", "50 dkg") átszámolása
 * más adagszámra. Ha a recept 4 adagos, de 2 adagot főzöl, a „2 db paradicsom” 1 db lesz – a felületen
 * mutatott mennyiség és a megvett mennyiség így mindig egyezik.
 */
public final class Amounts {

    private static final Pattern LEADING = Pattern.compile(
            "^\\s*(\\d+(?:[.,]\\d+)?(?:\\s*/\\s*\\d+)?|másfél|fél|negyed|egy|két|kettő|három|négy)(?=\\s|$)\\s*(.*)$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    /** Mértékegységek, amiknél tizedes is értelmes; a többi (db, fej, szelet…) fél darabra kerekít. */
    private static final Set<String> CONTINUOUS = Set.of("g", "dkg", "kg", "ml", "dl", "l", "cl");

    private Amounts() {}

    /** A mennyiség szorozva; ha nem értelmezhető (pl. „mellé”, „néhány szelet”), változatlan. */
    public static String scale(String amount, double factor) {
        if (amount == null || amount.isBlank() || Math.abs(factor - 1) < 1e-9) return amount;
        Matcher m = LEADING.matcher(amount);
        if (!m.matches()) return amount;
        double n = parse(m.group(1));
        if (Double.isNaN(n)) return amount;
        String rest = m.group(2).trim();
        String unit = rest.split("[\\s,(]")[0].toLowerCase(Locale.ROOT);
        double v = n * factor;
        if (unit.equals("dkg") && v >= 100) return fmt(Math.round(v) / 100.0, true) + " kg" + rest.substring(3);
        if (unit.equals("dl") && v >= 10) return fmt(v / 10, true) + " l" + rest.substring(2);
        String num = CONTINUOUS.contains(unit) ? fmt(v, v < 10) : halves(v);
        return rest.isEmpty() ? num : num + " " + rest;
    }

    static double parse(String s) {
        String t = s.toLowerCase(Locale.ROOT).replace(" ", "");
        switch (t) {
            case "fél": return 0.5;
            case "másfél": return 1.5;
            case "negyed": return 0.25;
            case "egy": return 1;
            case "két", "kettő": return 2;
            case "három": return 3;
            case "négy": return 4;
            default: break;
        }
        try {
            if (t.contains("/")) {
                String[] p = t.split("/");
                return Double.parseDouble(p[0].replace(',', '.')) / Double.parseDouble(p[1]);
            }
            return Double.parseDouble(t.replace(',', '.'));
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    /** Darabos mennyiség: fél darabra kerekítve ("fél", "1", "1,5", "2"), legalább fél. */
    private static String halves(double v) {
        double h = Math.max(0.5, Math.round(v * 2) / 2.0);
        if (h == 0.5) return "fél";
        return fmt(h, true);
    }

    private static String fmt(double v, boolean oneDecimal) {
        double r = oneDecimal ? Math.round(v * 10) / 10.0 : Math.round(v);
        if (r == Math.rint(r)) return String.valueOf((long) r);
        return String.valueOf(r).replace('.', ',');
    }
}
