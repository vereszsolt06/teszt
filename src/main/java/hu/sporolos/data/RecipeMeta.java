package hu.sporolos.data;

import hu.sporolos.model.Models.Ingredient;
import hu.sporolos.model.Models.Recipe;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Egy recept származtatott adatai, a hozzávalókból és a leírásból számolva:
 * tápérték adagonként, diéták, szükséges konyhai eszközök, hangulat-címkék és a fő alapanyag
 * (a heti terv változatosságához).
 *
 * @param kcal        kalória / adag
 * @param protein     fehérje g / adag
 * @param carbs       szénhidrát g / adag
 * @param fat         zsír g / adag
 * @param diets       "vega", "gluténmentes", "laktózmentes" – amelyik teljesül
 * @param needsOven   kell hozzá sütő (vagy airfryer, ha airfryerOk)
 * @param needsStove  kell hozzá főzőlap
 * @param airfryerOk  a sütős rész airfryerben is elkészíthető
 * @param moods       "fehérjedús", "könnyed", "laktató" + a recept saját címkéi közül a "comfort"
 * @param mainKey     a fő alapanyag kulcsa (a hús/hal, ennek híján a legnagyobb tömegű hozzávaló)
 * @param keepsWell   másnap is jó (egytálétel, leves, főzelék, ragu…) – több napra főzhető;
 *                    a frissen sült, ropogós, rántott ételek és a saláták nem
 */
public record RecipeMeta(double kcal, double protein, double carbs, double fat, Set<String> diets,
                         boolean needsOven, boolean needsStove, boolean airfryerOk, List<String> moods,
                         String mainKey, boolean keepsWell) {

    public static final String VEGA = "vega", GLUTEN_FREE = "gluténmentes", LACTOSE_FREE = "laktózmentes";
    public static final String STOVE = "tuzhely", OVEN = "suto", AIRFRYER = "airfryer";

    /** Frissen az igazi: rántott, ropogós, sült tészta, saláta, pizza, palacsinta… */
    private static final Pattern FRESH_ONLY = Pattern.compile(
            "rántott|ropogós|lángos|tócsni|palacsint|saláta|burger|hot dog|fajitas|taco|quesadilla|omlett|rántotta|"
                    + "bundás|shakshuka|frittata|melegszendvics|camembert|csirkefalat|pizza|halfilé|lazac|gyros|souvlaki|"
                    + "wok|pirított|ramen|carbonara|pad thai|tortilla|burrito|hummusz|cordon|csirkeszárny|pogácsa|"
                    + "reggeli|körözött|parasztreggeli|hal |sült hal",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final Pattern OVEN_WORDS = Pattern.compile(
            "sütőben|sütőbe |sütőt|forró sütő|fokon|fokos|tepsi|sütőpapír|airfryer", Pattern.CASE_INSENSITIVE);
    private static final Pattern STOVE_WORDS = Pattern.compile(
            "főz|pirít|párol|serpenyő|forral|dinsztel|olajban|olajon|vajon|zsiradékon|lében|vízben|sütöd|sütsz|felolvasztod|megolvasztod|forró vízzel|melegíted",
            Pattern.CASE_INSENSITIVE);

    public static RecipeMeta of(Recipe r, Map<String, Catalog.Product> products, boolean airfryerOk) {
        double kcal = 0, p = 0, c = 0, f = 0;
        boolean meat = false, gluten = false, lactose = false;
        String mainMeat = null, heaviest = null;
        double heaviestGrams = 0;
        for (Ingredient i : r.ingredients()) {
            if (i.pantry()) continue;
            Catalog.Product prod = products.get(i.productKey());
            if (prod == null) continue;
            double grams = prod.grams(i.share() * prod.qty());
            if (prod.nutrition() != null && !Double.isNaN(grams)) {
                double k = grams / 100.0;
                // bő olajban sütésnél (≥ 1 dl olaj) az olajnak csak kb. 20%-a kerül az ételbe
                if (prod.key().endsWith("olaj") && grams >= 100) k *= 0.2;
                kcal += prod.nutrition()[0] * k;
                p += prod.nutrition()[1] * k;
                c += prod.nutrition()[2] * k;
                f += prod.nutrition()[3] * k;
            }
            Set<String> cont = prod.contains();
            if (cont.contains("hus") || cont.contains("hal")) {
                meat = true;
                if (mainMeat == null && !prod.staple()) mainMeat = prod.key();
            }
            gluten |= cont.contains("glutén");
            lactose |= cont.contains("laktóz");
            if (!prod.staple() && !Double.isNaN(grams) && grams > heaviestGrams) {
                heaviestGrams = grams;
                heaviest = prod.key();
            }
        }
        double n = Math.max(1, r.servings());
        Set<String> diets = new LinkedHashSet<>();
        if (!meat) diets.add(VEGA);
        if (!gluten) diets.add(GLUTEN_FREE);
        if (!lactose) diets.add(LACTOSE_FREE);

        // mondatonként: amelyik mondat sütőt/airfryert említ, az sütős lépés; a többi főzési ige tűzhelyes lépés
        boolean oven = false, stove = false;
        for (String step : r.steps()) {
            for (String sentence : step.split("[.;]")) {
                if (OVEN_WORDS.matcher(sentence).find()) oven = true;
                else if (STOVE_WORDS.matcher(sentence).find()) stove = true;
            }
        }

        double kcalPer = kcal / n, proteinPer = p / n;
        List<String> moods = new ArrayList<>();
        if (proteinPer >= 30 || (kcalPer > 0 && proteinPer * 4 / kcalPer >= 0.30)) moods.add("fehérjedús");
        boolean hearty = kcalPer >= 750 || r.tags().contains("comfort");
        if (kcalPer > 0 && kcalPer <= 450 && !hearty) moods.add("könnyed");
        if (hearty) moods.add("laktató");
        return new RecipeMeta(kcalPer, proteinPer, c / n, f / n, Set.copyOf(diets), oven, stove,
                airfryerOk, List.copyOf(moods), mainMeat != null ? mainMeat : heaviest,
                !FRESH_ONLY.matcher(r.name()).find() && !r.tags().contains("hal"));
    }

    /** Elkészíthető-e a megadott konyhai eszközökkel ("tuzhely", "suto", "airfryer"); üres halmaz = mindegy. */
    public boolean cookableWith(Set<String> equipment) {
        if (equipment == null || equipment.isEmpty()) return true;
        if (needsStove && !equipment.contains(STOVE)) return false;
        if (needsOven && !(equipment.contains(OVEN) || airfryerOk && equipment.contains(AIRFRYER))) return false;
        return true;
    }

    /** Megfelel-e a diétáknak (mindegyiknek). */
    public boolean fits(Set<String> requiredDiets) {
        return requiredDiets == null || diets.containsAll(requiredDiets);
    }
}
