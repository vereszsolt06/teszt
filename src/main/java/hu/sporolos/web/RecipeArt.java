package hu.sporolos.web;

import hu.sporolos.data.Catalog;
import hu.sporolos.data.RecipeMeta;
import hu.sporolos.model.Models.Ingredient;
import hu.sporolos.model.Models.Recipe;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Receptképek generálása: felülnézetes, lapos ("flat lay") illusztráció SVG-ben, a recept hozzávalóiból.
 *
 * Az edény a recept fajtájából jön (leves → mély tányér, tészta → lapos tányér, sült → tepsi,
 * egytál → serpenyő, saláta → tál, édes → desszerttányér), rajta az alapanyagok stilizált formái
 * (tojás, paradicsomkarika, krumplikocka, csirkefalat, tésztaszálak, rizsszemek, brokkolirózsa…).
 * A szórás a recept azonosítójából képzett maggal determinisztikus, így minden recept képe egyedi,
 * de mindig ugyanaz. Fotót nem használ, külső szolgáltatás nélkül, azonnal készül.
 */
public final class RecipeArt {

    private static final String INK = "#1d2a23";
    private static final double W = 480, H = 360, CX = 212, CY = 182;
    private final Catalog catalog;
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public RecipeArt(Catalog catalog) {
        this.catalog = catalog;
    }

    /** A recept képe SVG-ként, vagy null, ha nincs ilyen recept. */
    public String svg(String recipeId) {
        Recipe r = catalog.recipes().stream().filter(x -> x.id().equals(recipeId)).findFirst().orElse(null);
        if (r == null) return null;
        return cache.computeIfAbsent(recipeId, k -> draw(r));
    }

    private enum Vessel { BOWL, PLATE, TRAY, PAN, SALAD, DESSERT, BOARD }

    private synchronized String draw(Recipe r) {
        Random rnd = new Random(r.id().hashCode() * 31L + 7);
        RecipeMeta meta = catalog.meta(r.id());
        List<String> keys = new ArrayList<>();
        for (Ingredient i : r.ingredients()) if (!i.pantry()) keys.add(i.productKey());
        String pantry = r.ingredients().stream().filter(Ingredient::pantry).map(i -> i.label().toLowerCase(Locale.ROOT))
                .reduce("", (a, b) -> a + " " + b);
        Vessel v = vessel(r, meta, keys);
        String id = r.id();
        hero = id.contains("burger") ? "burger"
                : id.contains("rantott") || id.equals("cordon-bleu") || id.equals("nuggets") ? "schnitzel"
                : id.contains("fasirt") ? "patty"
                : id.equals("tocsni") || id.equals("langos") ? "pancake" : null;
        if (id.contains("pizza") || id.equals("margherita")) {
            StringBuilder p = new StringBuilder(8000);
            p.append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 480 360\" role=\"img\" aria-label=\"")
                    .append(esc(r.name())).append(" illusztráció\">");
            background(p, r, rnd);
            pizza(p, keys, rnd);
            return p.append("</svg>").toString();
        }

        StringBuilder sb = new StringBuilder(8000);
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 480 360\" role=\"img\" aria-label=\"")
                .append(esc(r.name())).append(" illusztráció\">");
        background(sb, r, rnd);
        switch (v) {
            case BOWL -> bowl(sb, r, keys, pantry, rnd);
            case TRAY -> tray(sb, keys, pantry, rnd);
            case PAN -> pan(sb, keys, pantry, rnd);
            case SALAD -> salad(sb, keys, rnd);
            case DESSERT -> dessert(sb, r, keys, rnd);
            case BOARD -> board(sb, keys, rnd);
            default -> plate(sb, r, keys, pantry, rnd);
        }
        sb.append("</svg>");
        return sb.toString();
    }

    private static Vessel vessel(Recipe r, RecipeMeta m, List<String> keys) {
        List<String> t = r.tags();
        if (t.contains("leves") || r.name().toLowerCase(Locale.ROOT).contains("főzelék") || t.contains("főzelék")
                || r.id().contains("curry") || r.id().contains("dal") || r.id().contains("chili")) return Vessel.BOWL;
        if (t.contains("édes") || t.contains("sütemény")) return Vessel.DESSERT;
        if (m != null && !m.needsOven() && !m.needsStove()) return keys.contains("kenyer") || keys.contains("turo") ? Vessel.BOARD : Vessel.SALAD;
        if (r.id().startsWith("rakott") || r.id().contains("lasagne") || r.id().contains("muszaka") || r.id().contains("gratin")
                || r.id().contains("pizza") || r.id().contains("margherita") || r.id().contains("tepsis")
                || (m != null && m.needsOven() && !m.needsStove() && !t.contains("édes"))) return Vessel.TRAY;
        if (t.contains("tészta") || keys.contains("spagetti") || keys.contains("penne") && !t.contains("leves")) return Vessel.PLATE;
        if (r.id().contains("paella") || r.id().contains("lecso") || r.id().contains("shakshuka") || r.id().contains("frittata")
                || r.id().contains("rantotta") || r.id().contains("wok") || r.id().contains("piritott") || r.id().contains("reggeli"))
            return Vessel.PAN;
        return Vessel.PLATE;
    }

    // ---------- háttér ----------

    private static final String[][] PALETTES = {
            {"#f3e3c7", "#e9d3ad"}, {"#dfe9da", "#cbdcc4"}, {"#f2d9cf", "#e6c3b6"}, {"#dbe4ee", "#c5d3e2"},
            {"#efe6d2", "#e0d3b6"}, {"#e7dff0", "#d6cae6"}, {"#f6e6b8", "#ebd394"}, {"#d9ece6", "#c2ddd4"}};

    private void background(StringBuilder sb, Recipe r, Random rnd) {
        String[] pal = PALETTES[Math.floorMod(r.id().hashCode(), PALETTES.length)];
        sb.append(rect(0, 0, W, H, pal[0], null, 0));
        // asztalterítő-csíkok
        for (int i = -4; i < 14; i++) {
            double x = i * 48;
            sb.append(String.format(Locale.ROOT, "<path d=\"M%.0f 0 L%.0f 360\" stroke=\"%s\" stroke-width=\"14\" opacity=\".55\"/>",
                    x, x - 120, pal[1]));
        }
        // szalvéta és villa
        sb.append(String.format(Locale.ROOT,
                "<g transform=\"rotate(-6 430 250)\"><rect x=\"392\" y=\"150\" width=\"74\" height=\"190\" rx=\"6\" fill=\"#fbfcf9\" stroke=\"%s\" stroke-width=\"2.5\"/>"
                        + "<path d=\"M392 176h74\" stroke=\"%s\" stroke-width=\"2\" opacity=\".25\"/></g>", INK, INK));
        sb.append(String.format(Locale.ROOT,
                "<g transform=\"rotate(-6 430 250)\" fill=\"none\" stroke=\"%s\" stroke-width=\"3\" stroke-linecap=\"round\">"
                        + "<path d=\"M429 190v34M419 190v18q0 10 10 12q10-2 10-12v-18M429 224v96\"/></g>", INK));
    }

    // ---------- edények ----------

    private void shadowCircle(StringBuilder sb, double r) {
        sb.append(String.format(Locale.ROOT, "<ellipse cx=\"%.0f\" cy=\"%.0f\" rx=\"%.0f\" ry=\"%.0f\" fill=\"%s\" opacity=\".14\"/>",
                CX + 8, CY + 10, r, r, INK));
    }

    private void plate(StringBuilder sb, Recipe r, List<String> keys, String pantry, Random rnd) {
        shadowCircle(sb, 140);
        sb.append(circle(CX, CY, 140, "#fbfcf9", INK, 2.5)).append(circle(CX, CY, 108, "#f1f3ee", INK, 1.2));
        sauce(sb, keys, pantry, 92, rnd);
        fill(sb, keys, 96, rnd, true);
        garnish(sb, 96, rnd, pantry);
    }

    private void bowl(StringBuilder sb, Recipe r, List<String> keys, String pantry, Random rnd) {
        shadowCircle(sb, 136);
        sb.append(circle(CX, CY, 136, "#fbfcf9", INK, 2.5));
        sb.append(circle(CX, CY, 112, soupColor(keys, pantry, r), INK, 2));
        sb.append(String.format(Locale.ROOT, "<ellipse cx=\"%.0f\" cy=\"%.0f\" rx=\"60\" ry=\"22\" fill=\"#fff\" opacity=\".18\" transform=\"rotate(-25 %.0f %.0f)\"/>",
                CX - 40, CY - 50, CX - 40, CY - 50));
        fill(sb, keys, 88, rnd, false);
        if (keys.contains("tejfol") && !r.tags().contains("édes")) sb.append(swirl(CX + 18, CY + 14, 22, "#fffaf0"));
        garnish(sb, 90, rnd, pantry);
        steam(sb, CX, CY - 150);
    }

    private void tray(StringBuilder sb, List<String> keys, String pantry, Random rnd) {
        sb.append(String.format(Locale.ROOT, "<rect x=\"%.0f\" y=\"%.0f\" width=\"330\" height=\"236\" rx=\"22\" fill=\"%s\" opacity=\".14\"/>",
                CX - 157, CY - 108, INK));
        sb.append(String.format(Locale.ROOT, "<rect x=\"%.0f\" y=\"%.0f\" width=\"330\" height=\"236\" rx=\"22\" fill=\"#cfd6d9\" stroke=\"%s\" stroke-width=\"2.5\"/>",
                CX - 165, CY - 118, INK));
        boolean cheesy = keys.stream().anyMatch(k -> k.equals("gouda") || k.equals("mozzarella") || k.equals("cheddar") || k.equals("trappista"));
        String base = cheesy ? "#f2c86b" : keys.contains("tejfol") || keys.contains("tejszin") ? "#f4e6c4" : "#e3a35f";
        sb.append(String.format(Locale.ROOT, "<rect x=\"%.0f\" y=\"%.0f\" width=\"296\" height=\"202\" rx=\"14\" fill=\"%s\" stroke=\"%s\" stroke-width=\"1.5\"/>",
                CX - 148, CY - 101, base, INK));
        // pirult foltok
        for (int i = 0; i < 14; i++) {
            double x = CX - 130 + rnd.nextDouble() * 260, y = CY - 85 + rnd.nextDouble() * 170;
            sb.append(String.format(Locale.ROOT, "<ellipse cx=\"%.0f\" cy=\"%.0f\" rx=\"%.0f\" ry=\"%.0f\" fill=\"#b9692b\" opacity=\".35\"/>",
                    x, y, 6 + rnd.nextDouble() * 12, 4 + rnd.nextDouble() * 8));
        }
        scatterRect(sb, keys, rnd, CX - 135, CY - 88, 270, 176);
        garnishRect(sb, rnd, CX - 135, CY - 88, 270, 176, pantry);
        steam(sb, CX, CY - 150);
    }

    private void pan(StringBuilder sb, List<String> keys, String pantry, Random rnd) {
        sb.append(String.format(Locale.ROOT, "<rect x=\"%.0f\" y=\"%.0f\" width=\"150\" height=\"24\" rx=\"12\" fill=\"%s\" transform=\"rotate(-28 %.0f %.0f)\"/>",
                CX - 290, CY + 20, INK, CX - 140, CY));
        shadowCircle(sb, 136);
        sb.append(circle(CX, CY, 136, "#3b4643", INK, 2.5)).append(circle(CX, CY, 118, "#2c3532", null, 0));
        sauce(sb, keys, pantry, 104, rnd);
        fill(sb, keys, 100, rnd, false);
        garnish(sb, 100, rnd, pantry);
        steam(sb, CX, CY - 150);
    }

    private void salad(StringBuilder sb, List<String> keys, Random rnd) {
        shadowCircle(sb, 136);
        sb.append(circle(CX, CY, 136, "#e9f1ee", INK, 2.5)).append(circle(CX, CY, 116, "#ffffff", INK, 1));
        for (int i = 0; i < 9; i++) {
            double a = rnd.nextDouble() * Math.PI * 2, d = rnd.nextDouble() * 70;
            sb.append(leaf(CX + Math.cos(a) * d, CY + Math.sin(a) * d, 26 + rnd.nextDouble() * 10, rnd.nextDouble() * 360,
                    rnd.nextBoolean() ? "#7cbf5a" : "#5fa44a"));
        }
        fill(sb, keys, 92, rnd, false);
        garnish(sb, 96, rnd, "");
    }

    private void dessert(StringBuilder sb, Recipe r, List<String> keys, Random rnd) {
        shadowCircle(sb, 138);
        sb.append(circle(CX, CY, 138, "#fbfcf9", INK, 2.5));
        for (int i = 0; i < 24; i++) {
            double a = i * Math.PI * 2 / 24;
            sb.append(circle(CX + Math.cos(a) * 124, CY + Math.sin(a) * 124, 5, "#e8ece4", null, 0));
        }
        String id = r.id();
        if (keys.contains("penne") || keys.contains("spagetti") || keys.contains("zsemle") && keys.contains("mak")) {
            // édes tészta / mákos guba: tésztaágy, rajta mák vagy dió
            if (keys.contains("zsemle")) {
                for (int i = 0; i < 12; i++) {
                    double a = rnd.nextDouble() * Math.PI * 2, d = Math.sqrt(rnd.nextDouble()) * 80;
                    sb.append(circle(CX + Math.cos(a) * d, CY + Math.sin(a) * d, 14, "#e9c27a", INK, 1.4));
                }
            } else {
                noodles(sb, rnd, 96, "#efc75a", true);
            }
            dust(sb, rnd, keys.contains("mak") ? "#2b2b33" : "#8a5a2b");
            dust(sb, rnd, "#fbfcf9");
            return;
        }
        if (id.contains("gomboc")) {
            for (int i = 0; i < 5; i++) {
                double a = i * Math.PI * 2 / 5, d = i == 4 ? 0 : 50;
                double x = i == 4 ? CX : CX + Math.cos(a) * d, y = i == 4 ? CY : CY + Math.sin(a) * d;
                sb.append(circle(x, y, 28, "#d9a253", INK, 2));
                for (int j = 0; j < 10; j++) sb.append(circle(x - 18 + rnd.nextDouble() * 36, y - 18 + rnd.nextDouble() * 36, 2, "#a8692c", null, 0));
            }
            sb.append(swirl(CX + 60, CY + 60, 20, "#fffaf0"));
            return;
        }
        if (keys.contains("leveles_teszta") || id.contains("bukta") || id.contains("pogacsa") || id.contains("csiga")) {
            for (int i = 0; i < 5; i++) {
                double a = i * Math.PI * 2 / 5 + rnd.nextDouble() * .3, d = i == 4 ? 0 : 58;
                double x = i == 4 ? CX : CX + Math.cos(a) * d, y = i == 4 ? CY : CY + Math.sin(a) * d;
                sb.append(pastry(x, y, 34, id.contains("csiga") || keys.contains("kakao")));
            }
        } else if (id.contains("palacsinta")) {
            for (int i = 0; i < 3; i++) {
                double y = CY - 40 + i * 40;
                sb.append(String.format(Locale.ROOT, "<rect x=\"%.0f\" y=\"%.0f\" width=\"190\" height=\"36\" rx=\"18\" fill=\"#e9b866\" stroke=\"%s\" stroke-width=\"2\"/>"
                        + "<path d=\"M%.0f %.0f h150\" stroke=\"#c98a3a\" stroke-width=\"3\" stroke-linecap=\"round\" opacity=\".6\"/>",
                        CX - 95, y - 18, INK, CX - 75, y));
            }
            if (keys.contains("kakao")) dust(sb, rnd, "#5b3a22");
        } else if (id.contains("pite") || id.contains("kenyer") || id.contains("morzsa") || id.contains("felfujt") || id.contains("guba")) {
            sb.append(String.format(Locale.ROOT, "<path d=\"M%.0f %.0f L%.0f %.0f A120 120 0 0 1 %.0f %.0f Z\" fill=\"#e7b56a\" stroke=\"%s\" stroke-width=\"2.5\"/>",
                    CX - 20, CY + 50, CX - 100, CY - 50, CX + 70, CY - 80, INK));
            sb.append(String.format(Locale.ROOT, "<path d=\"M%.0f %.0f A120 120 0 0 1 %.0f %.0f\" fill=\"none\" stroke=\"#b8782f\" stroke-width=\"10\" stroke-linecap=\"round\"/>",
                    CX - 100, CY - 50, CX + 70, CY - 80));
            if (keys.contains("mak")) dust(sb, rnd, INK);
        } else {
            // pohárkrém / túrós desszert / gyümölcs: krém halom
            sb.append(circle(CX, CY, 86, keys.contains("kakao") ? "#a9744f" : "#fff6e6", INK, 2));
            sb.append(swirl(CX, CY, 40, keys.contains("kakao") ? "#c08d68" : "#ffffff"));
        }
        fill(sb, keys.stream().filter(k -> k.equals("eper") || k.equals("banan") || k.equals("alma") || k.equals("korte")
                || k.equals("narancs") || k.equals("dio") || k.equals("lekvar") || k.equals("mak")).toList(), 112, rnd, false);
    }

    private void board(StringBuilder sb, List<String> keys, Random rnd) {
        sb.append(String.format(Locale.ROOT, "<rect x=\"%.0f\" y=\"%.0f\" width=\"340\" height=\"220\" rx=\"26\" fill=\"%s\" opacity=\".14\"/>", CX - 162, CY - 100, INK));
        sb.append(String.format(Locale.ROOT, "<rect x=\"%.0f\" y=\"%.0f\" width=\"340\" height=\"220\" rx=\"26\" fill=\"#d9a46a\" stroke=\"%s\" stroke-width=\"2.5\"/>", CX - 170, CY - 110, INK));
        for (int i = 0; i < 6; i++) {
            double y = CY - 90 + i * 36;
            sb.append(String.format(Locale.ROOT, "<path d=\"M%.0f %.0f q80 %.0f 300 0\" stroke=\"#c48d54\" stroke-width=\"2\" fill=\"none\"/>",
                    CX - 150, y, rnd.nextDouble() * 10 - 5));
        }
        scatterRect(sb, keys, rnd, CX - 150, CY - 92, 300, 184);
    }

    // ---------- tartalom ----------

    private static String soupColor(List<String> keys, String pantry, Recipe r) {
        if (keys.contains("passata") || keys.contains("paradicsom") && r.id().contains("paradicsom")) return "#d9573c";
        if (pantry.contains("pirospaprika") || r.id().contains("gulyas") || r.id().contains("halaszle") || r.id().contains("bab")) return "#d9772f";
        if (keys.contains("sutotok")) return "#f0a03c";
        if (keys.contains("brokkoli") || keys.contains("spenot") || keys.contains("borso") || keys.contains("zoldbab")) return "#9cc46a";
        if (keys.contains("lencse") || pantry.contains("curry")) return "#e3a63d";
        if (keys.contains("tejfol") || keys.contains("tejszin") || keys.contains("tej") || keys.contains("kokusztej")) return "#f3e7cf";
        return "#f0cf6b";
    }

    private void sauce(StringBuilder sb, List<String> keys, String pantry, double r, Random rnd) {
        String color = null;
        if (keys.contains("passata")) color = "#d24a33";
        else if (pantry.contains("pirospaprika") && (keys.contains("tejfol") || keys.contains("tejszin"))) color = "#e8964a";
        else if (pantry.contains("pirospaprika")) color = "#cf5d2e";
        else if (keys.contains("tejszin") || keys.contains("tejfol")) color = "#f5ebd3";
        else if (keys.contains("kokusztej") || pantry.contains("curry")) color = "#e7b042";
        if (color == null) return;
        sb.append(blob(CX, CY, r * 0.82, color, rnd, 0.92));
    }

    private static final double SCALE = 1.35;
    private static final java.util.Set<String> HERO_SKIP = java.util.Set.of(
            "csirkemell", "csirkecomb", "pulykamell", "sertes_karaj", "sertes_comb", "sertes_tarja", "daralthus", "daralt_marha",
            "halfile", "ponty", "parizsi", "trappista", "karfiol", "lencse", "zsemle", "tojas", "liszt", "burgonya");

    /** A recept "főszereplője", ha külön rajzoljuk: rántott szelet, fasírt, tócsni, burger. */
    private String hero;

    /** A tányér tartalma: alap (rizs, tészta) középre, darabos hozzávalók körben, kicsit nagyítva. */
    private void fill(StringBuilder sb, List<String> keys, double radius, Random rnd, boolean base) {
        sb.append(String.format(Locale.ROOT, "<g transform=\"translate(%.1f %.1f) scale(%.2f) translate(%.1f %.1f)\">", CX, CY, SCALE, -CX, -CY));
        fillInner(sb, keys, radius / SCALE, rnd);
        sb.append("</g>");
    }

    private void fillInner(StringBuilder sb, List<String> keys, double radius, Random rnd) {
        List<double[]> used = new ArrayList<>();
        int total = 0;
        if (hero != null) {
            int n = hero.equals("burger") ? 1 : hero.equals("patty") || hero.equals("pancake") ? 4 : 2;
            for (int i = 0; i < n; i++) {
                double a = i * Math.PI * 2 / n + rnd.nextDouble() * .4, d = n == 1 ? 0 : radius * 0.38;
                double x = CX + Math.cos(a) * d - (n == 1 ? 10 : 0), y = CY + Math.sin(a) * d;
                sb.append(heroGlyph(hero, x, y, rnd));
                used.add(new double[]{x, y});
                used.add(new double[]{x + 14, y});
                used.add(new double[]{x - 14, y});
            }
            if (hero.equals("burger") && keys.contains("hasabburgonya")) {
                for (int i = 0; i < 9; i++) sb.append(chunk(CX + 46 + rnd.nextDouble() * 20, CY - 40 + i * 10, 34, 7, 70 + rnd.nextDouble() * 40, "#f3c95a"));
                return;
            }
            if (keys.contains("burgonya") && !hero.equals("pancake")) {
                for (int i = 0; i < 6; i++) {
                    double[] p = place(rnd, radius, used, 22);
                    used.add(p);
                    sb.append(chunk(p[0], p[1], 15, 13, rnd.nextDouble() * 360, "#f1d27a"));
                }
            }
        }
        for (String k : keys) {
            int count = countFor(k);
            if (count == 0) continue;
            if (hero != null && HERO_SKIP.contains(k)) continue;
            if (k.equals("rizs") || k.equals("kuszkusz")) { grains(sb, rnd, radius * 0.62, k.equals("rizs") ? "#fbf7ec" : "#f0d898"); continue; }
            if (k.equals("spagetti") || k.equals("penne") || k.equals("rizsteszta") || k.equals("tarhonya") || k.equals("lasagne")) {
                noodles(sb, rnd, radius * 0.7, k.equals("rizsteszta") ? "#f7efd8" : "#efc75a", k.equals("penne")); continue;
            }
            for (int i = 0; i < count && total < 30; i++) {
                double[] p = place(rnd, radius, used, 15);
                used.add(p);
                total++;
                sb.append(glyph(k, p[0], p[1], rnd));
            }
        }
    }

    private void scatterRect(StringBuilder sb, List<String> keys, Random rnd, double x0, double y0, double w, double h) {
        int total = 0;
        for (String k : keys) {
            int count = Math.min(countFor(k), 5);
            if (k.equals("rizs") || k.equals("spagetti") || k.equals("penne") || k.equals("lasagne")) continue;
            for (int i = 0; i < count && total < 24; i++, total++) {
                sb.append(glyph(k, x0 + 18 + rnd.nextDouble() * (w - 36), y0 + 18 + rnd.nextDouble() * (h - 36), rnd));
            }
        }
    }

    private static int countFor(String k) {
        return switch (k) {
            case "liszt", "retesliszt", "cukor", "porcukor", "napraforgoolaj", "olivaolaj", "zsir", "vaj", "margarin",
                 "tej", "tejszin", "habtejszin", "kokusztej", "passata", "kakao", "almale", "tejfol", "joghurt", "kefir",
                 "fokhagyma", "lekvar", "pesto", "zabpehely", "keksz", "leveles_teszta", "mak", "dio" -> 0;
            case "borso", "kukorica", "voros_bab", "feher_bab", "csicseri", "lencse", "mogyoro", "olajbogyo" -> 7;
            case "rizs", "kuszkusz", "spagetti", "penne", "rizsteszta", "tarhonya", "lasagne" -> 1;
            case "tojas" -> 3;
            default -> 5;
        };
    }

    private static double[] place(Random rnd, double radius, List<double[]> used, double minDist) {
        double[] best = null;
        for (int tries = 0; tries < 30; tries++) {
            double a = rnd.nextDouble() * Math.PI * 2, d = Math.sqrt(rnd.nextDouble()) * radius;
            double[] p = {CX + Math.cos(a) * d, CY + Math.sin(a) * d};
            boolean ok = true;
            for (double[] u : used) if (Math.hypot(u[0] - p[0], u[1] - p[1]) < minDist) { ok = false; break; }
            if (ok) return p;
            best = p;
        }
        return best;
    }

    /** Egy hozzávaló stilizált formája. */
    private static String glyph(String k, double x, double y, Random rnd) {
        double rot = rnd.nextDouble() * 360;
        return switch (k) {
            case "tojas" -> circle(x, y, 17, "#ffffff", INK, 1.8) + circle(x + 2, y - 1, 8, "#f6b81f", null, 0);
            case "paradicsom" -> circle(x, y, 14, "#e2442f", INK, 1.8) + star(x, y, 5, "#ffd2c2");
            case "paprika" -> chunk(x, y, 18, 10, rot, rnd.nextBoolean() ? "#e8452c" : "#4f9d3a");
            case "uborka" -> circle(x, y, 13, "#8cc06b", INK, 1.6) + circle(x, y, 8, "#d8edc5", null, 0);
            case "csemegeuborka" -> chunk(x, y, 20, 8, rot, "#6f8f3a");
            case "burgonya", "hasabburgonya", "edesburgonya" -> k.equals("hasabburgonya")
                    ? chunk(x, y, 28, 8, rot, "#f3c95a")
                    : chunk(x, y, 15, 13, rot, k.equals("edesburgonya") ? "#f08a3c" : "#f1d27a");
            case "voroshagyma" -> ring(x, y, 12, "#b56aa3") + ring(x, y, 7, "#cf8fc0");
            case "sargarepa" -> circle(x, y, 9, "#f28a1e", INK, 1.5) + circle(x, y, 3, "#f9b25e", null, 0);
            case "petrezselyem", "zsemle" -> k.equals("zsemle") ? circle(x, y, 20, "#e2a75c", INK, 1.8)
                    + String.format(Locale.ROOT, "<path d=\"M%.0f %.0f q8 6 16 0\" stroke=\"%s\" stroke-width=\"1.6\" fill=\"none\"/>", x - 8, y - 2, INK)
                    : chunk(x, y, 14, 7, rot, "#f3ead0");
            case "brokkoli" -> cloud(x, y, 13, "#3f8f3a");
            case "karfiol" -> cloud(x, y, 14, "#f2ead2");
            case "gomba" -> mushroom(x, y, 13);
            case "spenot", "kelkaposzta", "kaposzta", "zoldbab", "zoldsegkeverek" -> k.equals("zoldbab")
                    ? chunk(x, y, 24, 5, rot, "#5ea142")
                    : k.equals("zoldsegkeverek") ? circle(x, y, 6, "#f28a1e", INK, 1) + circle(x + 9, y + 4, 5, "#7ab648", INK, 1)
                    : leaf(x, y, 18, rot, k.equals("kaposzta") ? "#cfe3a8" : "#4f9440");
            case "borso" -> circle(x, y, 5, "#77b84a", INK, 1.2);
            case "kukorica" -> rect(x - 4, y - 4, 8, 8, "#f6cf3a", INK, 1);
            case "voros_bab" -> bean(x, y, rot, "#8c2b2b");
            case "feher_bab", "csicseri" -> k.equals("csicseri") ? circle(x, y, 6, "#e7c587", INK, 1.2) : bean(x, y, rot, "#f1e9d8");
            case "lencse" -> circle(x, y, 4, "#d57a32", null, 0);
            case "mogyoro", "dio" -> bean(x, y, rot, "#b9864f");
            case "olajbogyo" -> ring(x, y, 5, "#3a3a2a");
            case "csirkemell", "csirkecomb", "csirkeszarny", "egesz_csirke", "tyuk", "pulykamell" -> meat(x, y, rot, "#e7b27b", "#c8823f");
            case "daralthus", "daralt_marha" -> crumbs(x, y, rnd, "#8a4b2b");
            case "marhalabszar", "sertes_karaj", "sertes_comb", "sertes_tarja", "sertes_oldalas" -> meat(x, y, rot, "#a5603a", "#7c3f22");
            case "virsli", "kolbasz", "parizsi", "szalami" -> k.equals("virsli") ? chunk(x, y, 30, 9, rot, "#e88a6a")
                    : circle(x, y, 11, k.equals("parizsi") ? "#f0a6a0" : "#b4382b", INK, 1.6) + circle(x - 3, y + 2, 2, "#f6d6c8", null, 0);
            case "bacon", "sonka" -> chunk(x, y, 20, 9, rot, k.equals("bacon") ? "#d76a5b" : "#f2a9a0");
            case "halfile", "harcsa", "ponty", "tonhal" -> fish(x, y, rot, "#f3ece0");
            case "lazac" -> fish(x, y, rot, "#f39a6e");
            case "trappista", "gouda", "cheddar", "feta", "mozzarella", "camembert", "turo", "tofu" -> switch (k) {
                case "mozzarella" -> circle(x, y, 13, "#ffffff", INK, 1.6);
                case "feta", "turo", "tofu" -> rect(x - 8, y - 8, 16, 16, k.equals("tofu") ? "#f1e6c8" : "#fbfaf4", INK, 1.4);
                case "camembert" -> circle(x, y, 22, "#f8f2df", INK, 2);
                default -> cheese(x, y, rot, k.equals("cheddar") ? "#f2a13a" : "#f6cf55");
            };
            case "kenyer" -> slice(x, y, rot);
            case "tortilla" -> circle(x, y, 26, "#efd49a", INK, 1.8) + circle(x - 6, y + 4, 3, "#c99a4e", null, 0) + circle(x + 8, y - 6, 2, "#c99a4e", null, 0);
            case "citrom" -> wedge(x, y, rot, "#f6d43a");
            case "alma", "korte" -> circle(x, y, 14, k.equals("alma") ? "#d93b2b" : "#b9c84a", INK, 1.6)
                    + String.format(Locale.ROOT, "<path d=\"M%.0f %.0f v-8\" stroke=\"%s\" stroke-width=\"2\"/>", x, y - 12, INK);
            case "banan" -> String.format(Locale.ROOT, "<path d=\"M%.0f %.0f a26 26 0 0 0 40 0\" stroke=\"#f3cf3a\" stroke-width=\"11\" fill=\"none\" stroke-linecap=\"round\" transform=\"rotate(%.0f %.0f %.0f)\"/>",
                    x - 20, y, rot, x, y);
            case "narancs" -> circle(x, y, 14, "#f39a24", INK, 1.6) + star(x, y, 6, "#fbd08a");
            case "eper" -> String.format(Locale.ROOT, "<path d=\"M%.0f %.0f q-12 -14 0 -16 q12 2 0 16z\" fill=\"#e2343c\" stroke=\"%s\" stroke-width=\"1.4\"/>", x, y + 8, INK)
                    + String.format(Locale.ROOT, "<path d=\"M%.0f %.0f l4 -4 l4 4\" stroke=\"#3f8f3a\" stroke-width=\"2\" fill=\"none\"/>", x - 4, y - 7);
            case "padlizsan" -> circle(x, y, 13, "#6b3b7a", INK, 1.6) + circle(x, y, 9, "#e9e0c8", null, 0);
            case "cukkini" -> circle(x, y, 12, "#4f8f3a", INK, 1.6) + circle(x, y, 8, "#e6f0c8", null, 0);
            case "sutotok" -> chunk(x, y, 16, 14, rot, "#f0a03c");
            case "cekla" -> chunk(x, y, 14, 14, rot, "#9b2343");
            default -> circle(x, y, 8, "#d9c7a0", INK, 1.2);
        };
    }

    private void pizza(StringBuilder sb, List<String> keys, Random rnd) {
        shadowCircle(sb, 142);
        sb.append(circle(CX, CY, 142, "#d9a46a", INK, 2.5));
        sb.append(circle(CX, CY, 136, "#e2a75c", INK, 1.5)).append(circle(CX, CY, 116, "#d24a33", null, 0));
        for (int i = 0; i < 9; i++) {
            double a = rnd.nextDouble() * Math.PI * 2, d = Math.sqrt(rnd.nextDouble()) * 80;
            sb.append(blob(CX + Math.cos(a) * d, CY + Math.sin(a) * d, 22, "#fbf3d9", rnd, 0.95));
        }
        for (int i = 0; i < 4; i++) {
            double a = i * Math.PI / 4;
            sb.append(String.format(Locale.ROOT, "<path d=\"M%.1f %.1f L%.1f %.1f\" stroke=\"%s\" stroke-width=\"1.5\" opacity=\".4\"/>",
                    CX - Math.cos(a) * 136, CY - Math.sin(a) * 136, CX + Math.cos(a) * 136, CY + Math.sin(a) * 136, INK));
        }
        List<String> top = keys.stream().filter(k -> !k.equals("liszt") && !k.equals("mozzarella") && !k.equals("passata")).toList();
        sb.append(String.format(Locale.ROOT, "<g transform=\"translate(%.1f %.1f) scale(1.2) translate(%.1f %.1f)\">", CX, CY, -CX, -CY));
        List<double[]> used = new ArrayList<>();
        for (String k : top) {
            for (int i = 0; i < Math.min(countFor(k), 5); i++) {
                double[] p = place(rnd, 86, used, 14);
                used.add(p);
                sb.append(glyph(k, p[0], p[1], rnd));
            }
        }
        sb.append("</g>");
        for (int i = 0; i < 10; i++) {
            double a = rnd.nextDouble() * Math.PI * 2, d = Math.sqrt(rnd.nextDouble()) * 100;
            sb.append(leaf(CX + Math.cos(a) * d, CY + Math.sin(a) * d, 6, rnd.nextDouble() * 360, "#3f8f3a"));
        }
    }

    private static String heroGlyph(String hero, double x, double y, Random rnd) {
        return switch (hero) {
            case "schnitzel" -> {
                StringBuilder g = new StringBuilder(String.format(Locale.ROOT,
                        "<path d=\"M%.1f %.1f q14 -20 40 -10 q22 10 12 30 q-12 20 -40 12 q-24 -8 -12 -32z\" fill=\"#e3a548\" stroke=\"%s\" stroke-width=\"2\"/>",
                        x - 26, y - 4, INK));
                for (int i = 0; i < 9; i++) g.append(circle(x - 18 + rnd.nextDouble() * 40, y - 10 + rnd.nextDouble() * 24, 1.6, "#b8782f", null, 0));
                yield g.toString();
            }
            case "patty" -> circle(x, y, 18, "#8a4b2b", INK, 2)
                    + String.format(Locale.ROOT, "<path d=\"M%.1f %.1f l20 0 M%.1f %.1f l20 0\" stroke=\"#5e2f18\" stroke-width=\"3\" stroke-linecap=\"round\"/>", x - 10, y - 5, x - 10, y + 5);
            case "pancake" -> blob(x, y, 24, "#e6b35a", rnd, 1) + circle(x + 4, y - 3, 3, "#c98a3a", null, 0) + circle(x - 7, y + 6, 2.5, "#c98a3a", null, 0);
            default -> { // burger felülről: zsemle szezámmal, kilógó sajt és saláta
                StringBuilder g = new StringBuilder();
                g.append(leaf(x, y + 30, 32, 0, "#7cbf5a"));
                g.append(String.format(Locale.ROOT, "<path d=\"M%.1f %.1f l64 -6 l-8 40z\" fill=\"#f6b73a\" stroke=\"%s\" stroke-width=\"1.6\"/>", x - 30, y - 10, INK));
                g.append(circle(x, y, 44, "#d9893a", INK, 2.2)).append(circle(x - 10, y - 12, 26, "#e9a75a", null, 0));
                for (int i = 0; i < 14; i++) {
                    double a = rnd.nextDouble() * Math.PI * 2, d = rnd.nextDouble() * 32;
                    g.append(String.format(Locale.ROOT, "<ellipse cx=\"%.1f\" cy=\"%.1f\" rx=\"2.6\" ry=\"1.4\" fill=\"#fff6dc\" transform=\"rotate(%.0f %.1f %.1f)\"/>",
                            x + Math.cos(a) * d, y + Math.sin(a) * d, rnd.nextDouble() * 180, x + Math.cos(a) * d, y + Math.sin(a) * d));
                }
                yield g.toString();
            }
        };
    }

    // ---------- díszítés ----------

    private void garnish(StringBuilder sb, double radius, Random rnd, String pantry) {
        String color = pantry.contains("kapor") || pantry.contains("petrezselyem") || pantry.contains("bazsalikom")
                || pantry.contains("snidling") || pantry.contains("újhagyma") ? "#3f8f3a" : null;
        if (color == null) return;
        for (int i = 0; i < 12; i++) {
            double a = rnd.nextDouble() * Math.PI * 2, d = Math.sqrt(rnd.nextDouble()) * radius;
            sb.append(circle(CX + Math.cos(a) * d, CY + Math.sin(a) * d, 2.2, color, null, 0));
        }
    }

    private void garnishRect(StringBuilder sb, Random rnd, double x0, double y0, double w, double h, String pantry) {
        if (!(pantry.contains("bazsalikom") || pantry.contains("oregánó") || pantry.contains("petrezselyem"))) return;
        for (int i = 0; i < 14; i++) sb.append(circle(x0 + rnd.nextDouble() * w, y0 + rnd.nextDouble() * h, 2.4, "#3f8f3a", null, 0));
    }

    private static void steam(StringBuilder sb, double x, double y) {
        for (int i = -1; i <= 1; i++) {
            sb.append(String.format(Locale.ROOT, "<path d=\"M%.0f %.0f q-10 -12 0 -24 q10 -12 0 -24\" stroke=\"#fbfcf9\" stroke-width=\"4\" fill=\"none\" stroke-linecap=\"round\" opacity=\".85\"/>",
                    x + i * 34, y + 20));
        }
    }

    private void grains(StringBuilder sb, Random rnd, double r, String color) {
        sb.append(blob(CX, CY, r, color, rnd, 0.9));
        for (int i = 0; i < 70; i++) {
            double a = rnd.nextDouble() * Math.PI * 2, d = Math.sqrt(rnd.nextDouble()) * r * 0.92;
            sb.append(String.format(Locale.ROOT, "<ellipse cx=\"%.1f\" cy=\"%.1f\" rx=\"4\" ry=\"1.8\" fill=\"#ffffff\" stroke=\"#d9cfb4\" stroke-width=\".6\" transform=\"rotate(%.0f %.1f %.1f)\"/>",
                    CX + Math.cos(a) * d, CY + Math.sin(a) * d, rnd.nextDouble() * 180, CX + Math.cos(a) * d, CY + Math.sin(a) * d));
        }
    }

    private void noodles(StringBuilder sb, Random rnd, double r, String color, boolean shortPasta) {
        if (shortPasta) {
            for (int i = 0; i < 26; i++) {
                double a = rnd.nextDouble() * Math.PI * 2, d = Math.sqrt(rnd.nextDouble()) * r;
                sb.append(chunk(CX + Math.cos(a) * d, CY + Math.sin(a) * d, 22, 9, rnd.nextDouble() * 360, color));
            }
            return;
        }
        for (int i = 0; i < 16; i++) {
            double a = rnd.nextDouble() * Math.PI * 2;
            double x1 = CX + Math.cos(a) * r * 0.9, y1 = CY + Math.sin(a) * r * 0.9;
            double x2 = CX - Math.cos(a + .6) * r * 0.9, y2 = CY - Math.sin(a + .6) * r * 0.9;
            sb.append(String.format(Locale.ROOT, "<path d=\"M%.0f %.0f Q%.0f %.0f %.0f %.0f\" stroke=\"%s\" stroke-width=\"5\" fill=\"none\" stroke-linecap=\"round\"/>",
                    x1, y1, CX + (rnd.nextDouble() - .5) * 80, CY + (rnd.nextDouble() - .5) * 80, x2, y2, color));
            sb.append(String.format(Locale.ROOT, "<path d=\"M%.0f %.0f Q%.0f %.0f %.0f %.0f\" stroke=\"#c99a2e\" stroke-width=\"1\" fill=\"none\" opacity=\".5\"/>",
                    x1, y1 + 2, CX, CY, x2, y2 + 2));
        }
    }

    private static void dust(StringBuilder sb, Random rnd, String color) {
        for (int i = 0; i < 60; i++) {
            double a = rnd.nextDouble() * Math.PI * 2, d = Math.sqrt(rnd.nextDouble()) * 90;
            sb.append(circle(CX + Math.cos(a) * d, CY + Math.sin(a) * d, 1.6, color, null, 0));
        }
    }

    // ---------- alakzatok ----------

    private static String circle(double x, double y, double r, String fill, String stroke, double sw) {
        return String.format(Locale.ROOT, "<circle cx=\"%.1f\" cy=\"%.1f\" r=\"%.1f\" fill=\"%s\"%s/>", x, y, r, fill,
                stroke == null ? "" : String.format(Locale.ROOT, " stroke=\"%s\" stroke-width=\"%.1f\"", stroke, sw));
    }

    private static String rect(double x, double y, double w, double h, String fill, String stroke, double sw) {
        return String.format(Locale.ROOT, "<rect x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" rx=\"2\" fill=\"%s\"%s/>", x, y, w, h, fill,
                stroke == null ? "" : String.format(Locale.ROOT, " stroke=\"%s\" stroke-width=\"%.1f\"", stroke, sw));
    }

    private static String ring(double x, double y, double r, String color) {
        return String.format(Locale.ROOT, "<circle cx=\"%.1f\" cy=\"%.1f\" r=\"%.1f\" fill=\"none\" stroke=\"%s\" stroke-width=\"3\"/>", x, y, r, color);
    }

    private static String chunk(double x, double y, double w, double h, double rot, String color) {
        return String.format(Locale.ROOT, "<rect x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" rx=\"%.1f\" fill=\"%s\" stroke=\"%s\" stroke-width=\"1.6\" transform=\"rotate(%.0f %.1f %.1f)\"/>",
                x - w / 2, y - h / 2, w, h, Math.min(w, h) / 2.6, color, INK, rot, x, y);
    }

    private static String meat(double x, double y, double rot, String color, String sear) {
        return String.format(Locale.ROOT, "<g transform=\"rotate(%.0f %.1f %.1f)\"><path d=\"M%.1f %.1f q10 -14 26 -4 q8 12 -4 22 q-16 8 -24 -4 q-6 -8 2 -14z\" fill=\"%s\" stroke=\"%s\" stroke-width=\"1.8\"/>"
                        + "<path d=\"M%.1f %.1f l14 6 M%.1f %.1f l12 5\" stroke=\"%s\" stroke-width=\"2.4\" stroke-linecap=\"round\"/></g>",
                rot, x, y, x - 13, y, color, INK, x - 8, y - 4, x - 6, y + 4, sear);
    }

    private static String crumbs(double x, double y, Random rnd, String color) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5; i++) sb.append(circle(x + rnd.nextDouble() * 18 - 9, y + rnd.nextDouble() * 18 - 9, 3.5 + rnd.nextDouble() * 2, color, INK, 0.8));
        return sb.toString();
    }

    private static String fish(double x, double y, double rot, String color) {
        return String.format(Locale.ROOT, "<g transform=\"rotate(%.0f %.1f %.1f)\"><rect x=\"%.1f\" y=\"%.1f\" width=\"44\" height=\"22\" rx=\"9\" fill=\"%s\" stroke=\"%s\" stroke-width=\"1.8\"/>"
                        + "<path d=\"M%.1f %.1f q4 11 0 22 M%.1f %.1f q4 11 0 22 M%.1f %.1f q4 11 0 22\" stroke=\"#ffffff\" stroke-width=\"1.6\" fill=\"none\" opacity=\".8\"/></g>",
                rot, x, y, x - 22, y - 11, color, INK, x - 10, y - 11, x, y - 11, x + 10, y - 11);
    }

    private static String cheese(double x, double y, double rot, String color) {
        return String.format(Locale.ROOT, "<g transform=\"rotate(%.0f %.1f %.1f)\"><path d=\"M%.1f %.1f l26 -12 l0 22z\" fill=\"%s\" stroke=\"%s\" stroke-width=\"1.6\" stroke-linejoin=\"round\"/>%s</g>",
                rot, x, y, x - 12, y, color, INK, circle(x + 6, y - 1, 2.4, "#e3b33a", null, 0));
    }

    private static String cloud(double x, double y, double r, String color) {
        return circle(x - r * .5, y, r * .65, color, INK, 1.4) + circle(x + r * .5, y, r * .65, color, INK, 1.4)
                + circle(x, y - r * .5, r * .7, color, INK, 1.4)
                + String.format(Locale.ROOT, "<path d=\"M%.1f %.1f v%.1f\" stroke=\"#8fbf6a\" stroke-width=\"4\" stroke-linecap=\"round\"/>", x, y + r * .3, r * .7);
    }

    private static String mushroom(double x, double y, double r) {
        return String.format(Locale.ROOT, "<path d=\"M%.1f %.1f a%.1f %.1f 0 0 1 %.1f 0z\" fill=\"#c7a07a\" stroke=\"%s\" stroke-width=\"1.6\"/>"
                        + "<rect x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" rx=\"2\" fill=\"#efe3cf\" stroke=\"%s\" stroke-width=\"1.4\"/>",
                x - r, y, r, r, 2 * r, INK, x - r * .3, y, r * .6, r * .8, INK);
    }

    private static String leaf(double x, double y, double s, double rot, String color) {
        return String.format(Locale.ROOT, "<path d=\"M%.1f %.1f q%.1f %.1f %.1f 0 q%.1f %.1f %.1f 0z\" fill=\"%s\" stroke=\"%s\" stroke-width=\"1.4\" transform=\"rotate(%.0f %.1f %.1f)\"/>",
                x - s, y, s, -s * .8, 2 * s, -s, s * .8, -2 * s, color, INK, rot, x, y);
    }

    private static String bean(double x, double y, double rot, String color) {
        return String.format(Locale.ROOT, "<ellipse cx=\"%.1f\" cy=\"%.1f\" rx=\"7\" ry=\"4.5\" fill=\"%s\" stroke=\"%s\" stroke-width=\"1\" transform=\"rotate(%.0f %.1f %.1f)\"/>",
                x, y, color, INK, rot, x, y);
    }

    private static String slice(double x, double y, double rot) {
        return String.format(Locale.ROOT, "<g transform=\"rotate(%.0f %.1f %.1f)\"><path d=\"M%.1f %.1f h32 v-22 q0 -12 -16 -12 q-16 0 -16 12z\" fill=\"#f1d39a\" stroke=\"#b8782f\" stroke-width=\"4\" stroke-linejoin=\"round\"/></g>",
                rot, x, y, x - 16, y + 14);
    }

    private static String wedge(double x, double y, double rot, String color) {
        return String.format(Locale.ROOT, "<path d=\"M%.1f %.1f a16 16 0 0 1 32 0z\" fill=\"%s\" stroke=\"%s\" stroke-width=\"1.6\" transform=\"rotate(%.0f %.1f %.1f)\"/>",
                x - 16, y, color, INK, rot, x, y);
    }

    private static String star(double x, double y, double r, String color) {
        StringBuilder d = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            double a = i * Math.PI / 3;
            d.append(String.format(Locale.ROOT, "M%.1f %.1f L%.1f %.1f ", x, y, x + Math.cos(a) * r, y + Math.sin(a) * r));
        }
        return String.format(Locale.ROOT, "<path d=\"%s\" stroke=\"%s\" stroke-width=\"1.5\"/>", d, color);
    }

    private static String swirl(double x, double y, double r, String color) {
        return String.format(Locale.ROOT, "<path d=\"M%.1f %.1f a%.1f %.1f 0 1 1 %.1f 0 a%.1f %.1f 0 1 1 %.1f 0\" fill=\"%s\" stroke=\"%s\" stroke-width=\"1.4\"/>",
                x - r, y, r, r * .7, 2 * r, r * .6, r * .45, -r * 1.2, color, INK);
    }

    private static String pastry(double x, double y, double r, boolean cocoa) {
        StringBuilder sb = new StringBuilder(circle(x, y, r, "#e6ad5f", INK, 2));
        sb.append(String.format(Locale.ROOT, "<path d=\"M%.1f %.1f a%.1f %.1f 0 1 1 %.1f 0 a%.1f %.1f 0 1 1 %.1f 0\" fill=\"none\" stroke=\"%s\" stroke-width=\"3\"/>",
                x - r * .7, y, r * .7, r * .7, r * 1.4, r * .5, r * .5, -r, cocoa ? "#6b3f22" : "#b8782f"));
        return sb.toString();
    }

    /** Lágy szélű, kissé szabálytalan folt (mártás, rizságy): zárt, másodfokú görbe a pontok felezőin át. */
    private static String blob(double x, double y, double r, String color, Random rnd, double opacity) {
        int n = 9;
        double[][] p = new double[n][2];
        for (int i = 0; i < n; i++) {
            double a = i * Math.PI * 2 / n, rr = r * (0.88 + rnd.nextDouble() * 0.2);
            p[i][0] = x + Math.cos(a) * rr;
            p[i][1] = y + Math.sin(a) * rr;
        }
        StringBuilder d = new StringBuilder();
        for (int i = 0; i <= n; i++) {
            double[] a = p[i % n], b = p[(i + 1) % n];
            double mx = (a[0] + b[0]) / 2, my = (a[1] + b[1]) / 2;
            if (i == 0) d.append(String.format(Locale.ROOT, "M%.1f %.1f ", mx, my));
            else d.append(String.format(Locale.ROOT, "Q%.1f %.1f %.1f %.1f ", a[0], a[1], mx, my));
        }
        return String.format(Locale.ROOT, "<path d=\"%sZ\" fill=\"%s\" opacity=\"%.2f\"/>", d, color, opacity);
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;");
    }
}
