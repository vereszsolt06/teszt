package hu.sporolos.core;

import hu.sporolos.data.Catalog;
import hu.sporolos.data.Stats;
import hu.sporolos.model.Models.Deal;
import hu.sporolos.model.Models.DealType;
import hu.sporolos.model.Models.Ingredient;
import hu.sporolos.model.Models.PriceEntry;
import hu.sporolos.model.Models.PricedIngredient;
import hu.sporolos.model.Models.Recipe;
import hu.sporolos.model.Models.Suggestion;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Receptajánló: minden receptet "megvásárol" minden boltban, és azokat rangsorolja előre,
 * amelyekhez az adott boltban épp kiemelkedően jó áron kapni az alapanyagokat.
 *
 * Hozzávalónként a legkedvezőbb terméket választja: a receptre jutó költség alapján,
 * a ritkán kapható (a lánc kevés boltjában lévő) termékeket hátrébb sorolva, és egy jó áron
 * lévő terméket akkor is előnyben részesítve, ha legfeljebb 20%-kal drágább a legolcsóbbnál.
 *
 * Pontszám (0..1):
 *  45% – mekkora részét adják a recept költségének jó áron lévő alapanyagok (a jó ár erősségével súlyozva),
 *  35% – mennyivel olcsóbb a recept itt, mint a többi bolt mediánjával,
 *  20% – mennyi alapanyagra van valós bolti ár.
 */
public final class RecipeRecommender {

    /** Ha egy jó áron lévő termék adagja legfeljebb ennyiszer drágább a legolcsóbbnál, azt választjuk. */
    static final double DEAL_PREFERENCE = 1.20;
    static final double MIN_COVERAGE = 0.40;
    /** Ennyi elérhetőség alatt (a lánc boltjainak 30%-a) büntetjük a termék választását. */
    static final double AVAILABILITY_OK = 0.30;

    private static final Pattern BY_WEIGHT = Pattern.compile("(ledig|ft kg|pult|(^|\\s)[a-z]+ kg(\\s|$))");

    private final Catalog catalog;
    private final DealAnalyzer deals;
    private final List<String> storeIds;
    /** lánc → alapanyag-kulcs → illeszkedő bolti termékek */
    private final Map<String, Map<String, List<PriceEntry>>> index = new HashMap<>();
    /** alapanyag-kulcs → becsült egységár (minden lánc mediánja) a hiányzó tételekhez */
    private final Map<String, Double> fallbackUnitPrice = new HashMap<>();
    /** bolti termék (vonalkód|lánc) → melyik recept-alapanyagnak felel meg */
    private final Map<String, String> ingredientOf = new HashMap<>();
    /** recept → lánc → beárazott recept (alapadagra) */
    private final Map<String, Map<String, Suggestion>> priced = new HashMap<>();
    /** lánc → rangsorolt ajánlatok */
    private final Map<String, List<Suggestion>> ranked = new HashMap<>();

    public RecipeRecommender(Catalog catalog, List<PriceEntry> entries, DealAnalyzer deals, List<String> storeIds) {
        this.catalog = catalog;
        this.deals = deals;
        this.storeIds = List.copyOf(storeIds);
        Map<String, List<Double>> allUnitPrices = new HashMap<>();
        for (PriceEntry e : entries) {
            for (Catalog.Product p : catalog.products().values()) {
                if (p.tracked() && catalog.matches(p, e)) {
                    ingredientOf.putIfAbsent(e.itemId() + "|" + e.storeId(), p.name());
                    index.computeIfAbsent(e.storeId(), k -> new HashMap<>())
                            .computeIfAbsent(p.key(), k -> new ArrayList<>()).add(e);
                    double amount = amountInItemUnit(p, e, p.qty());
                    if (!Double.isNaN(amount) && amount > 0) {
                        // a katalógus-egységre vetített ár, hogy a különböző kiszerelések összevethetők legyenek
                        allUnitPrices.computeIfAbsent(p.key(), k -> new ArrayList<>()).add(e.unitPrice() * amount / p.qty());
                    }
                }
            }
        }
        allUnitPrices.forEach((k, v) -> fallbackUnitPrice.put(k, Stats.median(v)));

        for (Recipe r : catalog.recipes()) {
            Map<String, Suggestion> perStore = new LinkedHashMap<>();
            for (String s : storeIds) perStore.put(s, price(r, s, 1.0));
            priced.put(r.id(), perStore);
        }
        for (String s : storeIds) {
            List<Suggestion> list = new ArrayList<>();
            for (Recipe r : catalog.recipes()) {
                Suggestion base = priced.get(r.id()).get(s);
                if (base.coverage() < MIN_COVERAGE) continue;
                list.add(score(base, othersCost(r.id(), s, 1.0)));
            }
            list.sort(Comparator.comparingDouble(Suggestion::score).reversed());
            ranked.put(s, list);
        }
    }

    /** A bolt rangsorolt receptjei (legjobb elöl). */
    public List<Suggestion> suggestionsFor(String storeId) {
        return ranked.getOrDefault(storeId, List.of());
    }

    /**
     * Egy recept egy boltban, tetszőleges adagszámra (a többi bolthoz viszonyított pontszámmal).
     *
     * @return null, ha nincs ilyen recept vagy bolt
     */
    public Suggestion suggestion(String recipeId, String storeId, int servings) {
        Recipe r = catalog.recipes().stream().filter(x -> x.id().equals(recipeId)).findFirst().orElse(null);
        if (r == null || !storeIds.contains(storeId)) return null;
        double factor = servings <= 0 ? 1.0 : servings / (double) r.servings();
        Suggestion base = factor == 1.0 ? priced.get(r.id()).get(storeId) : price(r, storeId, factor);
        return score(base, othersCost(r.id(), storeId, factor));
    }

    /** Ugyanaz a recept minden láncban (alapadagra), a láncok sorrendjében. */
    public Map<String, Suggestion> acrossStores(String recipeId) {
        return priced.getOrDefault(recipeId, Map.of());
    }

    /** Ha a bolti termék valamelyik recept alapanyaga, annak neve; különben null. */
    public String ingredientFor(PriceEntry e) {
        return ingredientOf.get(e.itemId() + "|" + e.storeId());
    }

    private double othersCost(String recipeId, String storeId, double factor) {
        List<Double> otherTotals = new ArrayList<>();
        for (Map.Entry<String, Suggestion> o : priced.get(recipeId).entrySet()) {
            if (!o.getKey().equals(storeId) && o.getValue().coverage() >= MIN_COVERAGE) {
                otherTotals.add(o.getValue().totalCost() * factor);
            }
        }
        return otherTotals.isEmpty() ? Double.NaN : Stats.median(otherTotals);
    }

    /** Egy recept beárazása egy boltban (pontszám nélkül); factor = adagszám-szorzó. */
    Suggestion price(Recipe r, String storeId, double factor) {
        List<PricedIngredient> items = new ArrayList<>();
        double total = 0, checkout = 0;
        int priced = 0, real = 0, dealCount = 0;
        Map<String, List<PriceEntry>> storeIndex = index.getOrDefault(storeId, Map.of());
        for (Ingredient ing : r.ingredients()) {
            if (ing.pantry()) {
                items.add(new PricedIngredient(ing, null, null, 0, false, 0, 0, 0));
                continue;
            }
            Catalog.Product p = catalog.products().get(ing.productKey());
            double share = ing.share() * factor;
            double needed = share * p.qty();
            priced++;

            PriceEntry best = null, bestDealEntry = null;
            Purchase bestBuy = null, bestDealBuy = null;
            double bestScore = Double.MAX_VALUE, bestDealScore = Double.MAX_VALUE;
            for (PriceEntry e : storeIndex.getOrDefault(p.key(), List.of())) {
                Purchase buy = buy(p, e, share, needed);
                double s = buy.effective() / availabilityWeight(e);
                if (s < bestScore) { bestScore = s; best = e; bestBuy = buy; }
                if (deals.dealFor(e) != null && s < bestDealScore) { bestDealScore = s; bestDealEntry = e; bestDealBuy = buy; }
            }
            if (bestDealEntry != null && bestDealScore <= bestScore * DEAL_PREFERENCE) {
                best = bestDealEntry;
                bestBuy = bestDealBuy;
            }

            if (best != null) {
                Deal d = deals.dealFor(best);
                if (d != null) dealCount++;
                real++;
                total += bestBuy.cost();
                checkout += bestBuy.pay();
                items.add(new PricedIngredient(ing, best, d, bestBuy.cost(), false,
                        bestBuy.packages(), bestBuy.amount(), bestBuy.pay()));
            } else {
                Double up = fallbackUnitPrice.get(p.key());
                double est = up != null ? up * needed : p.basePrice() * share;
                int packages = Math.max(1, (int) Math.ceil(share - 0.08));
                double pay = up != null ? up * p.qty() * packages : p.basePrice() * packages;
                total += est;
                checkout += pay;
                items.add(new PricedIngredient(ing, null, null, est, true, packages, packages * p.qty(), pay));
            }
        }
        double coverage = priced == 0 ? 0 : real / (double) priced;
        int servings = (int) Math.round(r.servings() * factor);
        return new Suggestion(r, storeId, items, total, total, checkout, dealCount, coverage, 0, servings);
    }

    private Suggestion score(Suggestion s, double othersCost) {
        double realCost = 0, dealWeighted = 0;
        for (PricedIngredient pi : s.items()) {
            if (pi.entry() == null) continue;
            realCost += pi.cost();
            if (pi.deal() != null) {
                // a "tartósan olcsó" jel fele annyit ér, mint egy friss akció vagy láncok közötti árelőny
                double w = pi.deal().type() == DealType.CATEGORY ? 0.5 : 1.0;
                dealWeighted += pi.cost() * pi.deal().score() * w;
            }
        }
        double others = Double.isNaN(othersCost) ? s.totalCost() : othersCost;
        double dealPart = realCost > 0 ? Stats.clamp01(2.0 * dealWeighted / realCost) : 0;
        double savingPart = others > 0 ? Stats.clamp01((others - s.totalCost()) / others / 0.25) : 0;
        double score = 0.45 * dealPart + 0.35 * savingPart + 0.20 * s.coverage();
        return new Suggestion(s.recipe(), s.storeId(), s.items(), s.totalCost(), others, s.checkoutTotal(),
                s.dealCount(), s.coverage(), score, s.servings());
    }

    /** Ritkán kapható termék (a lánc boltjainak kis részében) kevésbé jó választás: 0,5..1 közötti súly. */
    static double availabilityWeight(PriceEntry e) {
        return 0.5 + 0.5 * Math.min(1.0, e.availability() / AVAILABILITY_OK);
    }

    /**
     * A szükséges mennyiség a bolti termék egységében (kg / l / db), vagy NaN, ha nem váltható át.
     * kg és l élelmiszernél jó közelítéssel felcserélhető; darab ↔ kg a katalógus darabtömegével.
     */
    static double amountInItemUnit(Catalog.Product p, PriceEntry e, double needed) {
        String a = p.unit(), b = e.unit();
        boolean aMass = a.equals("kg") || a.equals("l"), bMass = b.equals("kg") || b.equals("l");
        if (a.equals(b) || (aMass && bMass)) return needed;
        if (aMass && b.equals("db")) {
            double perUnit = isBunch(p, e) ? p.bunchWeight() / e.quantity() : p.pieceWeight();
            return perUnit > 0 ? needed / perUnit : Double.NaN;
        }
        if (a.equals("db") && bMass) {
            return p.pieceWeight() > 0 ? needed * p.pieceWeight() : Double.NaN;
        }
        return Double.NaN;
    }

    private static boolean isBunch(Catalog.Product p, PriceEntry e) {
        return p.bunchWeight() > 0 && Catalog.normalize(e.productName()).contains("csomo");
    }

    /**
     * Egy termék megvásárlása a recepthez.
     *
     * @param cost     a felhasznált mennyiség ára
     * @param packages hány csomag (0 = mérlegelős)
     * @param amount   összesen megvett mennyiség a termék egységében
     * @param pay      a pénztárnál fizetendő
     */
    record Purchase(double cost, int packages, double amount, double pay) {
        /**
         * Választási súly: a felhasznált rész ára + a kényszerűen megvett maradék 35%-a.
         * Így nem javasol 30 tojásos tálcát 6 tojáshoz csak azért, mert darabra 2 forinttal olcsóbb,
         * de egy jóval kedvezőbb egységárú nagyobb kiszerelést továbbra is választhat.
         */
        double effective() {
            return cost + 0.35 * Math.max(0, pay - cost);
        }
    }

    static Purchase buy(Catalog.Product p, PriceEntry e, double share, double needed) {
        double amount = amountInItemUnit(p, e, needed);
        boolean known = !Double.isNaN(amount) && e.unitPrice() > 0;
        double cost = known ? e.unitPrice() * amount : e.price() * share;

        if (e.unit().equals("kg") && Math.abs(e.quantity() - 1) < 1e-9) {
            // kilós árú termék: vagy mérlegelős, vagy a névben szereplő tömegű csomag (pl. "ca. 500g")
            if (soldByWeight(e)) return new Purchase(cost, 0, known ? amount : share, cost);
            double w = packageWeightFromName(e.productName());
            if (known && !Double.isNaN(w)) {
                int n = Math.max(1, (int) Math.ceil(amount / w - 0.08));
                return new Purchase(cost, n, n * w, n * w * e.unitPrice());
            }
        }
        double ratio = known ? amount / e.quantity() : share;
        // 8% tűrés: ha a recept épp egy csomagnyi kicsit többet kér, nem veszünk még egyet
        int n = Math.max(1, (int) Math.ceil(ratio - 0.08));
        return new Purchase(cost, n, n * e.quantity(), n * e.price());
    }

    /** A receptre jutó költség: egységár × szükséges mennyiség; ha nem váltható át, csomagár × hányad. */
    static double portionCost(Catalog.Product p, PriceEntry e, double share, double needed) {
        return buy(p, e, share, needed).cost();
    }

    /** Mérlegelős (lédig, Ft/kg, pultos) áru: annyit veszel, amennyi kell. */
    static boolean soldByWeight(PriceEntry e) {
        if (!(e.unit().equals("kg") && Math.abs(e.quantity() - 1) < 1e-9)) return false;
        String n = Catalog.normalize(e.productName());
        if (BY_WEIGHT.matcher(n).find()) return true;
        // kilós egységű, de a névben nincs csomagtömeg: Ft/kg-os, mérlegelős áru
        return Double.isNaN(packageWeightFromName(e.productName()));
    }

    private static final Pattern WEIGHT_IN_NAME =
            Pattern.compile("(\\d+(?:[.,]\\d+)?)\\s*(kg|dkg|g|gr)(?![a-z])", Pattern.CASE_INSENSITIVE);

    /** A terméknévben szereplő csomagtömeg kg-ban ("ca. 500g" → 0,5; "1,4kg" → 1,4), vagy NaN. */
    static double packageWeightFromName(String name) {
        java.util.regex.Matcher m = WEIGHT_IN_NAME.matcher(name == null ? "" : name);
        double found = Double.NaN;
        while (m.find()) {
            double v;
            try {
                v = Double.parseDouble(m.group(1).replace(',', '.'));
            } catch (NumberFormatException ex) {
                continue;
            }
            String u = m.group(2).toLowerCase();
            double kg = switch (u) {
                case "kg" -> v;
                case "dkg" -> v / 100;
                default -> v / 1000;
            };
            if (kg > 0.005 && kg <= 10) found = kg; // az utolsó értelmes tömeg számít
        }
        return found;
    }

    /** Hány csomagot kell megvenni (0 = mérlegelős). */
    static int packages(Catalog.Product p, PriceEntry e, double share, double needed) {
        return buy(p, e, share, needed).packages();
    }
}
