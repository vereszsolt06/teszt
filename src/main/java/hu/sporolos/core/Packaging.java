package hu.sporolos.core;

import hu.sporolos.data.Catalog;
import hu.sporolos.model.Models.PriceEntry;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Kiszerelések értelmezése: egy bolti tétel (Árfigyelő-sor) hogyan vásárolható meg egy recept-alapanyaghoz.
 *
 * Az Árfigyelő ugyanazt a fajta árut háromféleképpen adja meg, és ezeket nem szabad összekeverni:
 * <ul>
 *   <li><b>mérlegelős</b> (lédig, Ft/kg, pultos): az ár 1 kg-ra szól, annyit veszel, amennyi kell –
 *       de legalább egy darabot (egy fej fokhagymát nem lehet 1 dkg-onként venni);</li>
 *   <li><b>csomagolt</b>: az ár egy csomagra szól (450 g borsó, 10 db tojás, 3 db paprika, 1 db karfiol);
 *       egész csomagot kell venni;</li>
 *   <li><b>csomós</b> (csomós répa, petrezselyem): az ár egy csomóra szól, egységtől függetlenül.</li>
 * </ul>
 * A darab és a kiló között a katalógusban megadott darabtömeggel váltunk (1 paprika ≈ 15 dkg).
 * Minden mennyiséget az alapanyag saját egységére (kg / l / db) vetítünk, így a különböző kiszerelések összevethetők.
 */
public final class Packaging {

    private Packaging() {}

    /** Ha a recept legfeljebb ennyivel (15%) kér többet egész csomagoknál, nem veszünk még egyet. */
    public static final double PACKAGE_TOLERANCE = 0.15;

    public enum Kind { WEIGHED, PACKAGE, BUNCH }

    /**
     * Egy bolti tétel kiszerelése az alapanyag egységében.
     *
     * @param kind       mérlegelős / csomagolt / csomós
     * @param content    egy csomag tartalma az alapanyag egységében; mérlegelősnél 1 kg (vagy l) átváltva
     * @param price      egy csomag ára; mérlegelősnél a kilóár
     * @param minBuy     mérlegelősnél a legkisebb megvehető mennyiség (egy darab) az alapanyag egységében
     * @param packLabel  a csomag emberi leírása ("450 g", "3 db", "1 db", "csomó", "kb. 500 g")
     * @param priceText  az ár értelmezése ("599 Ft/kg", "549 Ft/db", "449 Ft / 450 g · 998 Ft/kg")
     */
    public record Pack(Kind kind, double content, double price, double minBuy, String packLabel, String priceText) {
        public double perUnit() {
            return price / content;
        }
    }

    /**
     * Egy vásárlás a recepthez.
     *
     * @param cost     a felhasznált mennyiség ára (a recept ára ebből áll össze)
     * @param packages hány csomag (0 = mérlegelős)
     * @param amount   összesen megvett mennyiség az alapanyag egységében
     * @param pay      a pénztárnál fizetendő
     * @param bulk     nagy (a háztartási méretnél nagyobb) kiszerelés
     * @param buyText  mit vegyél ("2 × 450 g", "kb. 25 dkg (≈ 2 db)", "1 db")
     */
    public record Purchase(double cost, int packages, double amount, double pay, boolean bulk, String buyText) {
        /**
         * Választási súly háztartási vásárláshoz: a felhasznált rész ára + a kényszerűen megvett maradék 35%-a.
         * Így nem javasol 30 tojásos tálcát 6 tojáshoz csak azért, mert darabra 2 forinttal olcsóbb.
         */
        public double effective() {
            return cost + 0.35 * Math.max(0, pay - cost);
        }
    }

    private static final Pattern BY_WEIGHT = Pattern.compile("(ledig|ft kg|pult|(^|\\s)[a-z]+ kg(\\s|$)|^kg\\s|\\skg$)");
    private static final Pattern WEIGHT_IN_NAME =
            Pattern.compile("(\\d+(?:[.,]\\d+)?)\\s*(kg|dkg|g|gr)(?![a-z])", Pattern.CASE_INSENSITIVE);

    /** A tétel kiszerelése az alapanyaghoz, vagy null, ha nem értelmezhető (pl. ismeretlen egység). */
    public static Pack of(Catalog.Product p, PriceEntry e) {
        String n = Catalog.normalize(e.productName());
        String unit = e.unit();
        boolean itemMass = unit.equals("kg") || unit.equals("l");
        if (!itemMass && !unit.equals("db")) return null;
        double qty = e.quantity() > 0 ? e.quantity() : 1;

        // 1. csomós zöldség: az ár egy csomóra szól
        if (p.bunchWeight() > 0 && n.contains("csomo")) {
            double content = kgTo(p, p.bunchWeight());
            if (Double.isNaN(content)) return null;
            return new Pack(Kind.BUNCH, content, e.price(), 0, "csomó",
                    Money.ft(e.price()) + " / csomó (kb. " + Qty.fmt(p.bunchWeight(), "kg") + ")");
        }

        boolean perKgRow = unit.equals("kg") && Math.abs(qty - 1) < 1e-9;
        double nameWeight = packageWeightFromName(e.productName());

        // 2. mérlegelős: kilós sor, a névben "lédig / Ft/kg / pult", vagy nincs benne csomagtömeg
        if (perKgRow && !p.packaged() && (BY_WEIGHT.matcher(n).find() || Double.isNaN(nameWeight))) {
            double content = kgTo(p, 1.0);
            if (Double.isNaN(content)) return null;
            double minBuy = p.pieceWeight() > 0 ? kgTo(p, p.pieceWeight()) : 0;
            return new Pack(Kind.WEIGHED, content, e.price(), minBuy, "mérlegelős",
                    Money.ft(e.price()) + "/kg");
        }

        // 3. kilós sor, de fix tömegű csomag ("ca. 500g", "1,4kg"): a csomag ára = tömeg × kilóár
        if (perKgRow && !Double.isNaN(nameWeight) && Math.abs(nameWeight - 1) > 1e-9) {
            double content = kgTo(p, nameWeight);
            if (Double.isNaN(content)) return null;
            double price = nameWeight * e.price();
            return new Pack(Kind.PACKAGE, content, price, 0, "kb. " + Qty.fmt(nameWeight, "kg"),
                    Money.ft(e.price()) + "/kg · kb. " + Qty.fmt(nameWeight, "kg") + "-os csomag ≈ " + Money.ft(price));
        }

        // 4. csomagolt: a sor ára egy csomagra szól
        double content;
        String label;
        if (itemMass) {
            content = p.unit().equals("db") ? massToPieces(p, qty) : (p.unit().equals("kg") || p.unit().equals("l") ? qty : Double.NaN);
            label = Qty.fmt(qty, unit);
        } else { // db
            content = p.unit().equals("db") ? qty : (p.pieceWeight() > 0 ? qty * p.pieceWeight() : p.qty());
            label = Qty.fmt(qty, "db");
        }
        if (Double.isNaN(content) || content <= 0) return null;
        String perUnitText;
        if (unit.equals("db")) {
            perUnitText = qty == 1 ? Money.ft(e.price()) + "/db" : Money.ft(e.price()) + " / " + label + " · " + Money.ft(e.price() / qty) + "/db";
        } else {
            perUnitText = Money.ft(e.price()) + " / " + label + " · " + Money.ft(e.price() / qty) + "/" + unit;
        }
        return new Pack(Kind.PACKAGE, content, e.price(), 0, label, perUnitText);
    }

    /** Vásárlás egy kiszerelésből a szükséges mennyiséghez (az alapanyag egységében). */
    public static Purchase buy(Catalog.Product p, Pack pack, double needed) {
        double perUnit = pack.perUnit();
        double cost = perUnit * needed;
        if (pack.kind() == Kind.WEIGHED) {
            double amount = Math.max(needed, pack.minBuy());
            return new Purchase(cost, 0, amount, amount * perUnit, false, weighedText(p, amount));
        }
        int n = Math.max(1, (int) Math.ceil(needed / pack.content() - PACKAGE_TOLERANCE));
        boolean bulk = pack.content() > Math.max(needed * 1.25, p.householdMax()) + 1e-9;
        String text = n == 1 ? pack.packLabel() : n + " × " + pack.packLabel();
        if (pack.kind() == Kind.BUNCH) text = n == 1 ? "1 csomó" : n + " csomó";
        return new Purchase(cost, n, n * pack.content(), n * pack.price(), bulk, text);
    }

    private static String weighedText(Catalog.Product p, double amount) {
        if (p.unit().equals("db")) {
            long pcs = Math.max(1, Math.round(amount));
            return pcs + " db" + (p.pieceWeight() > 0 ? " (≈ " + Qty.fmt(pcs * p.pieceWeight(), "kg") + ")" : "");
        }
        String base = "kb. " + Qty.fmt(amount, p.unit());
        if (p.unit().equals("kg") && p.pieceWeight() > 0) {
            long pcs = Math.max(1, Math.round(amount / p.pieceWeight()));
            base += " (≈ " + pcs + " db)";
        }
        return base;
    }

    /** kg → az alapanyag egysége (kg/l esetén ugyanaz, db esetén a darabtömeggel). */
    private static double kgTo(Catalog.Product p, double kg) {
        if (p.unit().equals("kg") || p.unit().equals("l")) return kg;
        if (p.unit().equals("db")) return p.pieceWeight() > 0 ? Math.max(1, Math.round(kg / p.pieceWeight())) : Double.NaN;
        return Double.NaN;
    }

    /** Csomagolt, tömegre megadott tétel darabszáma (pl. 52 g-os zsemle = 1 db). */
    private static double massToPieces(Catalog.Product p, double kg) {
        if (p.pieceWeight() <= 0) return Double.NaN;
        return Math.max(1, Math.round(kg / p.pieceWeight()));
    }

    /** A terméknévben szereplő csomagtömeg kg-ban ("ca. 500g" → 0,5; "1,4kg" → 1,4), vagy NaN. */
    public static double packageWeightFromName(String name) {
        Matcher m = WEIGHT_IN_NAME.matcher(name == null ? "" : name);
        double found = Double.NaN;
        while (m.find()) {
            double v;
            try {
                v = Double.parseDouble(m.group(1).replace(',', '.'));
            } catch (NumberFormatException ex) {
                continue;
            }
            double kg = switch (m.group(2).toLowerCase(Locale.ROOT)) {
                case "kg" -> v;
                case "dkg" -> v / 100;
                default -> v / 1000;
            };
            if (kg > 0.005 && kg <= 10) found = kg; // az utolsó értelmes tömeg számít
        }
        return found;
    }

    /** Pénzformázás ("1 299 Ft"). */
    public static final class Money {
        private Money() {}

        public static String ft(double v) {
            long r = Math.round(v);
            String s = String.valueOf(Math.abs(r));
            StringBuilder sb = new StringBuilder(r < 0 ? "-" : "");
            for (int i = 0; i < s.length(); i++) {
                if (i > 0 && (s.length() - i) % 3 == 0) sb.append(' ');
                sb.append(s.charAt(i));
            }
            return sb + " Ft";
        }
    }

    /** Mennyiségformázás magyarul ("450 g", "1,5 kg", "2 dl", "3 db"). */
    public static final class Qty {
        private Qty() {}

        public static String fmt(double amount, String unit) {
            switch (unit) {
                case "kg" -> {
                    if (amount < 1) return Math.max(1, Math.round(amount * 1000)) + " g";
                    return num(amount) + " kg";
                }
                case "l" -> {
                    if (amount < 1) {
                        long ml = Math.round(amount * 1000);
                        return ml % 100 == 0 ? (ml / 100) + " dl" : ml + " ml";
                    }
                    return num(amount) + " l";
                }
                default -> {
                    return num(amount) + " " + unit;
                }
            }
        }

        static String num(double v) {
            double r = Math.round(v * 100) / 100.0;
            if (r == Math.rint(r)) return String.valueOf((long) r);
            return String.valueOf(r).replace('.', ',');
        }
    }
}
