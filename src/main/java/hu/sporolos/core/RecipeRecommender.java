package hu.sporolos.core;

import hu.sporolos.data.Catalog;
import hu.sporolos.data.Stats;
import hu.sporolos.model.Models.Deal;
import hu.sporolos.model.Models.Ingredient;
import hu.sporolos.model.Models.PriceEntry;
import hu.sporolos.model.Models.PricedIngredient;
import hu.sporolos.model.Models.Recipe;
import hu.sporolos.model.Models.Suggestion;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Receptajánló: minden receptet "megvásárol" minden boltban, és azokat rangsorolja előre,
 * amelyekhez az adott boltban épp kiemelkedően jó áron kapni az alapanyagokat.
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

    private final Catalog catalog;
    private final DealAnalyzer deals;
    /** lánc → alapanyag-kulcs → illeszkedő bolti termékek */
    private final Map<String, Map<String, List<PriceEntry>>> index = new HashMap<>();
    /** alapanyag-kulcs → becsült adagár (minden lánc mediánja) a hiányzó tételekhez */
    private final Map<String, Double> fallbackUnitPrice = new HashMap<>();
    /** bolti termék (vonalkód|lánc) → melyik recept-alapanyagnak felel meg */
    private final Map<String, String> ingredientOf = new HashMap<>();
    /** lánc → rangsorolt ajánlatok */
    private final Map<String, List<Suggestion>> ranked = new HashMap<>();

    public RecipeRecommender(Catalog catalog, List<PriceEntry> entries, DealAnalyzer deals, List<String> storeIds) {
        this.catalog = catalog;
        this.deals = deals;
        Map<String, List<Double>> allUnitPrices = new HashMap<>();
        for (PriceEntry e : entries) {
            for (Catalog.Product p : catalog.products().values()) {
                if (p.tracked() && catalog.matches(p, e)) {
                    ingredientOf.putIfAbsent(e.itemId() + "|" + e.storeId(), p.name());
                    index.computeIfAbsent(e.storeId(), k -> new HashMap<>())
                            .computeIfAbsent(p.key(), k -> new ArrayList<>()).add(e);
                    if (compatible(p.unit(), e.unit())) {
                        allUnitPrices.computeIfAbsent(p.key(), k -> new ArrayList<>()).add(e.unitPrice());
                    }
                }
            }
        }
        allUnitPrices.forEach((k, v) -> fallbackUnitPrice.put(k, Stats.median(v)));

        // minden recept minden boltban
        Map<String, Map<String, Suggestion>> raw = new HashMap<>(); // recept → lánc → ár
        for (Recipe r : catalog.recipes()) {
            Map<String, Suggestion> perStore = new HashMap<>();
            for (String s : storeIds) perStore.put(s, price(r, s));
            raw.put(r.id(), perStore);
        }
        // pontozás a többi bolthoz viszonyítva
        for (String s : storeIds) {
            List<Suggestion> list = new ArrayList<>();
            for (Recipe r : catalog.recipes()) {
                Suggestion base = raw.get(r.id()).get(s);
                if (base.coverage() < MIN_COVERAGE) continue;
                List<Double> otherTotals = new ArrayList<>();
                for (String o : storeIds) {
                    Suggestion os = raw.get(r.id()).get(o);
                    if (!o.equals(s) && os.coverage() >= MIN_COVERAGE) otherTotals.add(os.totalCost());
                }
                double others = otherTotals.isEmpty() ? base.totalCost() : Stats.median(otherTotals);
                list.add(score(base, others));
            }
            list.sort(Comparator.comparingDouble(Suggestion::score).reversed());
            ranked.put(s, list);
        }
    }

    /** A bolt rangsorolt receptjei (legjobb elöl). */
    public List<Suggestion> suggestionsFor(String storeId) {
        return ranked.getOrDefault(storeId, List.of());
    }

    /** Ha a bolti termék valamelyik recept alapanyaga, annak neve; különben null. */
    public String ingredientFor(PriceEntry e) {
        return ingredientOf.get(e.itemId() + "|" + e.storeId());
    }

    /** Egy recept beárazása egy boltban (pontszám nélkül). */
    Suggestion price(Recipe r, String storeId) {
        List<PricedIngredient> items = new ArrayList<>();
        double total = 0;
        int priced = 0, real = 0, dealCount = 0;
        Map<String, List<PriceEntry>> storeIndex = index.getOrDefault(storeId, Map.of());
        for (Ingredient ing : r.ingredients()) {
            if (ing.pantry()) {
                items.add(new PricedIngredient(ing, null, null, 0, false));
                continue;
            }
            Catalog.Product p = catalog.products().get(ing.productKey());
            double needed = ing.share() * p.qty();
            priced++;

            PriceEntry best = null, bestDealEntry = null;
            double bestCost = Double.MAX_VALUE, bestDealCost = Double.MAX_VALUE;
            for (PriceEntry e : storeIndex.getOrDefault(p.key(), List.of())) {
                double c = portionCost(p, e, ing.share(), needed);
                if (c < bestCost) { bestCost = c; best = e; }
                if (deals.dealFor(e) != null && c < bestDealCost) { bestDealCost = c; bestDealEntry = e; }
            }
            if (bestDealEntry != null && bestDealCost <= bestCost * DEAL_PREFERENCE) {
                best = bestDealEntry;
                bestCost = bestDealCost;
            }

            if (best != null) {
                Deal d = deals.dealFor(best);
                if (d != null) dealCount++;
                real++;
                total += bestCost;
                items.add(new PricedIngredient(ing, best, d, bestCost, false));
            } else {
                Double up = fallbackUnitPrice.get(p.key());
                double est = up != null ? up * needed : p.basePrice() * ing.share();
                total += est;
                items.add(new PricedIngredient(ing, null, null, est, true));
            }
        }
        double coverage = priced == 0 ? 0 : real / (double) priced;
        return new Suggestion(r, storeId, items, total, total, dealCount, coverage, 0);
    }

    private Suggestion score(Suggestion s, double othersCost) {
        double realCost = 0, dealWeighted = 0;
        for (PricedIngredient pi : s.items()) {
            if (pi.entry() == null) continue;
            realCost += pi.cost();
            if (pi.deal() != null) dealWeighted += pi.cost() * pi.deal().score();
        }
        double dealPart = realCost > 0 ? Stats.clamp01(2.0 * dealWeighted / realCost) : 0;
        double savingPart = othersCost > 0 ? Stats.clamp01((othersCost - s.totalCost()) / othersCost / 0.25) : 0;
        double score = 0.45 * dealPart + 0.35 * savingPart + 0.20 * s.coverage();
        return new Suggestion(s.recipe(), s.storeId(), s.items(), s.totalCost(), othersCost,
                s.dealCount(), s.coverage(), score);
    }

    /**
     * A receptre jutó költség: ha az egységek összevethetők, egységár × szükséges mennyiség;
     * különben a bolti ár × recept-hányad (feltételezve, hogy a kiszerelés hasonló).
     */
    static double portionCost(Catalog.Product p, PriceEntry e, double share, double needed) {
        if (compatible(p.unit(), e.unit()) && e.unitPrice() > 0) return e.unitPrice() * needed;
        return e.price() * share;
    }

    /** kg és l élelmiszernél jó közelítéssel felcserélhető (sűrűség ≈ 1). */
    static boolean compatible(String a, String b) {
        if (a.equals(b)) return true;
        return (a.equals("kg") || a.equals("l")) && (b.equals("kg") || b.equals("l"));
    }
}
