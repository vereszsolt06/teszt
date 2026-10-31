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
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Receptajánló: minden receptet "megvásárol" minden boltban, és azokat rangsorolja előre,
 * amelyekhez az adott boltban épp kiemelkedően jó áron kapni az alapanyagokat.
 *
 * <h3>Kiszerelés</h3>
 * Alapból <b>háztartási kiszerelésben</b> vásárol: a háztartási méretnél nagyobb csomagot (5 kg burgonya,
 * 30 tojás, 1,4 kg-os sajttömb) nem választ csak azért, mert egységárban olcsóbb. Ha egy nagy kiszerelés
 * a recept adagjára vetítve legalább 10%-kal (és 15 Ft-tal) olcsóbb, alternatívaként felajánlja.
 * Nagy kiszerelés módban fordítva: a legolcsóbb egységárat választja, és a háztartási a lehetőség.
 *
 * <h3>Választás</h3>
 * Hozzávalónként a felhasznált rész ára + a kényszerűen megvett maradék 35%-a számít, a ritkán kapható
 * (a lánc kevés boltjában lévő) termékek hátrébb kerülnek, és egy jó áron lévő terméket akkor is választ,
 * ha legfeljebb 20%-kal drágább a legolcsóbbnál.
 *
 * <h3>Pontszám (0..1)</h3>
 *  45% – mekkora részét adják a recept költségének jó áron lévő alapanyagok (a jó ár erősségével súlyozva),
 *  35% – mennyivel olcsóbb a recept itt, mint a többi (választott) bolt mediánjával,
 *  20% – mennyi alapanyagra van valós bolti ár.
 */
public final class RecipeRecommender {

    /** Ha egy jó áron lévő termék legfeljebb ennyiszer drágább a legolcsóbbnál, azt választjuk. */
    static final double DEAL_PREFERENCE = 1.20;
    static final double MIN_COVERAGE = 0.40;
    /** Ennyi elérhetőség alatt (a lánc boltjainak 30%-a) büntetjük a termék választását. */
    static final double AVAILABILITY_OK = 0.30;
    /** A nagy kiszerelés akkor érdemes alternatíva, ha legalább ennyivel olcsóbb a receptre vetítve. */
    static final double BULK_MIN_SAVING_RATIO = 0.10;
    static final double BULK_MIN_SAVING_FT = 15;

    private final Catalog catalog;
    private final DealAnalyzer deals;
    private final List<String> storeIds;
    /** lánc → alapanyag-kulcs → illeszkedő bolti termékek */
    private final Map<String, Map<String, List<PriceEntry>>> index = new HashMap<>();
    /** alapanyag-kulcs → becsült ár az alapanyag egységére (háztartási kiszerelések mediánja) */
    private final Map<String, Double> fallbackUnitPrice = new HashMap<>();
    /** bolti termék (vonalkód|lánc) → melyik recept-alapanyagnak felel meg */
    private final Map<String, String> ingredientOf = new HashMap<>();
    /** mód (false = háztartási, true = nagy kiszerelés) → recept → lánc → beárazott recept (alapadagra) */
    private final Map<Boolean, Map<String, Map<String, Suggestion>>> priced = new HashMap<>();
    /** rangsor-gyorsítótár: "lánc|választott láncok|mód" → rangsor */
    private final Map<String, List<Suggestion>> rankCache = new ConcurrentHashMap<>();

    public RecipeRecommender(Catalog catalog, List<PriceEntry> entries, DealAnalyzer deals, List<String> storeIds) {
        this(catalog, entries, deals, storeIds, Map.of());
    }

    /**
     * @param forcedIngredient vonalkód → alapanyag-kulcs: kézzel/fájlból felvitt akciók, amelyek
     *                         nem kategória szerint, hanem közvetlenül egy alapanyaghoz tartoznak
     */
    public RecipeRecommender(Catalog catalog, List<PriceEntry> entries, DealAnalyzer deals, List<String> storeIds,
                             Map<String, String> forcedIngredient) {
        this.catalog = catalog;
        this.deals = deals;
        this.storeIds = List.copyOf(storeIds);
        Map<String, List<Double>> householdUnitPrices = new HashMap<>();
        for (PriceEntry e : entries) {
            String forced = forcedIngredient.get(e.itemId());
            for (Catalog.Product p : catalog.products().values()) {
                boolean match = forced != null ? forced.equals(p.key()) : p.tracked() && catalog.matches(p, e);
                if (!match) continue;
                ingredientOf.putIfAbsent(e.itemId() + "|" + e.storeId(), p.name());
                index.computeIfAbsent(e.storeId(), k -> new HashMap<>())
                        .computeIfAbsent(p.key(), k -> new ArrayList<>()).add(e);
                Packaging.Pack pack = Packaging.of(p, e);
                if (pack != null && (pack.kind() == Packaging.Kind.WEIGHED || pack.content() <= p.householdMax())) {
                    householdUnitPrices.computeIfAbsent(p.key(), k -> new ArrayList<>()).add(pack.perUnit());
                }
            }
        }
        householdUnitPrices.forEach((k, v) -> fallbackUnitPrice.put(k, Stats.median(v)));

        for (boolean bulk : new boolean[]{false, true}) {
            Map<String, Map<String, Suggestion>> byRecipe = new HashMap<>();
            for (Recipe r : catalog.recipes()) {
                Map<String, Suggestion> perStore = new LinkedHashMap<>();
                for (String s : storeIds) perStore.put(s, price(r, s, 1.0, bulk));
                byRecipe.put(r.id(), perStore);
            }
            priced.put(bulk, byRecipe);
        }
    }

    // ---------- lekérdezések ----------

    /** A bolt rangsorolt receptjei (legjobb elöl), minden lánccal összevetve, háztartási kiszereléssel. */
    public List<Suggestion> suggestionsFor(String storeId) {
        return suggestionsFor(storeId, null, false);
    }

    /**
     * A bolt rangsorolt receptjei.
     *
     * @param allowed a felhasználó által választott láncok (ezekhez hasonlítunk); null = mind
     * @param bulk    nagy kiszerelések is jöhetnek
     */
    public List<Suggestion> suggestionsFor(String storeId, Collection<String> allowed, boolean bulk) {
        if (!storeIds.contains(storeId)) return List.of();
        Set<String> allow = normalizeAllowed(allowed);
        String key = storeId + "|" + String.join(",", allow) + "|" + bulk;
        return rankCache.computeIfAbsent(key, k -> {
            List<Suggestion> list = new ArrayList<>();
            for (Recipe r : catalog.recipes()) {
                Suggestion base = priced.get(bulk).get(r.id()).get(storeId);
                if (base.coverage() < MIN_COVERAGE) continue;
                list.add(score(base, othersCost(r.id(), storeId, 1.0, allow, bulk)));
            }
            list.sort(Comparator.comparingDouble(Suggestion::score).reversed());
            return List.copyOf(list);
        });
    }

    /** Egy recept egy boltban, tetszőleges adagszámra, minden lánccal összevetve, háztartási kiszereléssel. */
    public Suggestion suggestion(String recipeId, String storeId, int servings) {
        return suggestion(recipeId, storeId, servings, null, false);
    }

    /**
     * Egy recept egy boltban, tetszőleges adagszámra (a választott boltokhoz viszonyított pontszámmal).
     *
     * @return null, ha nincs ilyen recept vagy bolt
     */
    public Suggestion suggestion(String recipeId, String storeId, int servings, Collection<String> allowed, boolean bulk) {
        Recipe r = recipe(recipeId);
        if (r == null || !storeIds.contains(storeId)) return null;
        double factor = servings <= 0 ? 1.0 : servings / (double) r.servings();
        Suggestion base = factor == 1.0 ? priced.get(bulk).get(r.id()).get(storeId) : price(r, storeId, factor, bulk);
        return score(base, othersCost(r.id(), storeId, factor, normalizeAllowed(allowed), bulk));
    }

    /** Ugyanaz a recept minden láncban (alapadagra), háztartási kiszereléssel. */
    public Map<String, Suggestion> acrossStores(String recipeId) {
        return acrossStores(recipeId, false);
    }

    public Map<String, Suggestion> acrossStores(String recipeId, boolean bulk) {
        return priced.get(bulk).getOrDefault(recipeId, Map.of());
    }

    /** Ha a bolti termék valamelyik recept alapanyaga, annak neve; különben null. */
    public String ingredientFor(PriceEntry e) {
        return ingredientOf.get(e.itemId() + "|" + e.storeId());
    }

    private Recipe recipe(String id) {
        return catalog.recipes().stream().filter(x -> x.id().equals(id)).findFirst().orElse(null);
    }

    private Set<String> normalizeAllowed(Collection<String> allowed) {
        Set<String> s = new TreeSet<>();
        if (allowed != null) for (String a : allowed) if (storeIds.contains(a)) s.add(a);
        if (s.isEmpty()) s.addAll(storeIds);
        return s;
    }

    private double othersCost(String recipeId, String storeId, double factor, Set<String> allowed, boolean bulk) {
        List<Double> otherTotals = new ArrayList<>();
        for (Map.Entry<String, Suggestion> o : priced.get(bulk).get(recipeId).entrySet()) {
            if (!o.getKey().equals(storeId) && allowed.contains(o.getKey()) && o.getValue().coverage() >= MIN_COVERAGE) {
                otherTotals.add(o.getValue().totalCost() * factor);
            }
        }
        return otherTotals.isEmpty() ? Double.NaN : Stats.median(otherTotals);
    }

    // ---------- beárazás ----------

    /** Egy jelölt: bolti termék + kiszerelés + vásárlás. */
    private record Option(PriceEntry entry, Packaging.Pack pack, Packaging.Purchase buy, Deal deal) {}

    /** Egy recept beárazása egy boltban (pontszám nélkül); factor = adagszám-szorzó. */
    Suggestion price(Recipe r, String storeId, double factor, boolean bulkMode) {
        List<PricedIngredient> items = new ArrayList<>();
        double total = 0, checkout = 0, staples = 0, altTotal = 0, altCheckout = 0;
        int pricedCount = 0, real = 0, dealCount = 0;
        Map<String, List<PriceEntry>> storeIndex = index.getOrDefault(storeId, Map.of());
        for (Ingredient orig : r.ingredients()) {
            // a felületen mutatott mennyiség is az adagszámhoz igazodik ("2 db" 4 adagra → "1 db" 2 adagra)
            Ingredient ing = factor == 1.0 || orig.pantry() ? orig : new Ingredient(orig.productKey(), orig.label(),
                    Amounts.scale(orig.amount(), factor), orig.share(), orig.pantry(), orig.staple());
            if (ing.pantry()) {
                items.add(new PricedIngredient(ing, null, null, 0, false, 0, 0, 0, false, "", "", null));
                continue;
            }
            Catalog.Product p = catalog.products().get(ing.productKey());
            double needed = ing.share() * factor * p.qty();
            pricedCount++;
            PricedIngredient main = priceNeed(storeId, ing, p, needed, bulkMode, factor);
            if (!main.estimated()) {
                real++;
                if (main.deal() != null) dealCount++;
            }
            items.add(main);
            PricedIngredient alt = main.alternative() != null ? main.alternative() : main;
            total += main.cost();
            altTotal += alt.cost();
            if (ing.staple()) {
                staples += main.checkoutCost();
            } else {
                checkout += main.checkoutCost();
                altCheckout += alt.checkoutCost();
            }
        }
        double coverage = pricedCount == 0 ? 0 : real / (double) pricedCount;
        int servings = (int) Math.round(r.servings() * factor);
        return new Suggestion(r, storeId, items, total, total, checkout, staples, dealCount, coverage, 0, servings,
                altTotal, altCheckout, bulkMode);
    }

    /**
     * Egy alapanyag adott mennyiségének beárazása egy boltban (a recepten kívül is, pl. a heti
     * bevásárlólista összesített mennyiségeire).
     *
     * @param factor a minimum-megtakarítás küszöbének szorzója (adagszám-arány); 1 = egy recept
     */
    public PricedIngredient priceNeed(String storeId, Ingredient ing, Catalog.Product p, double needed,
                                      boolean bulkMode, double factor) {
        Map<String, List<PriceEntry>> storeIndex = index.getOrDefault(storeId, Map.of());
        List<Option> options = new ArrayList<>();
        for (PriceEntry e : storeIndex.getOrDefault(p.key(), List.of())) {
            Packaging.Pack pack = Packaging.of(p, e);
            if (pack == null) continue;
            options.add(new Option(e, pack, Packaging.buy(p, pack, needed), deals.dealFor(e)));
        }
        if (!options.isEmpty()) {
            List<Option> household = options.stream().filter(o -> !o.buy().bulk()).toList();
            Option cheapest = pick(options, false);
            Option home = household.isEmpty() ? cheapest : pick(household, true);
            Option bulkAlt = cheapest.buy().bulk()
                    && cheapest.buy().cost() <= home.buy().cost() * (1 - BULK_MIN_SAVING_RATIO)
                    && home.buy().cost() - cheapest.buy().cost() >= BULK_MIN_SAVING_FT * factor
                    ? cheapest : null;
            Option chosen = bulkMode && bulkAlt != null ? bulkAlt : home;
            Option other = bulkMode ? (bulkAlt != null ? home : null) : bulkAlt;
            return toPriced(ing, chosen, other == null ? null : toPriced(ing, other, null));
        }
        Double up = fallbackUnitPrice.get(p.key());
        double perUnit = up != null ? up : p.basePrice() / p.qty();
        double est = perUnit * needed;
        int packages = Math.max(1, (int) Math.ceil(needed / p.qty() - Packaging.PACKAGE_TOLERANCE));
        double pay = perUnit * p.qty() * packages;
        String text = packages + " × " + Packaging.Qty.fmt(p.qty(), p.unit()) + " (becsült)";
        return new PricedIngredient(ing, null, null, est, true, packages, packages * p.qty(), pay,
                false, text, "kb. " + Packaging.Money.ft(perUnit) + "/" + p.unit(), null);
    }

    /**
     * A legjobb jelölt. Háztartási módban a felhasznált rész + a maradék 35%-a számít,
     * nagy kiszerelés módban csak a felhasznált rész ára. Mindkettőt az elérhetőséggel súlyozzuk,
     * és a jó áron lévő terméket előnyben részesítjük.
     */
    private static Option pick(List<Option> options, boolean household) {
        Option best = null, bestDeal = null;
        double bestScore = Double.MAX_VALUE, bestDealScore = Double.MAX_VALUE;
        for (Option o : options) {
            double base = household ? o.buy().effective() : o.buy().cost();
            double s = base / availabilityWeight(o.entry());
            if (s < bestScore) { bestScore = s; best = o; }
            if (o.deal() != null && s < bestDealScore) { bestDealScore = s; bestDeal = o; }
        }
        return bestDeal != null && bestDealScore <= bestScore * DEAL_PREFERENCE ? bestDeal : best;
    }

    private static PricedIngredient toPriced(Ingredient ing, Option o, PricedIngredient alternative) {
        Packaging.Purchase b = o.buy();
        return new PricedIngredient(ing, o.entry(), o.deal(), b.cost(), false, b.packages(), b.amount(), b.pay(),
                b.bulk(), b.buyText(), o.pack().priceText(), alternative);
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
        return s.rescored(others, score);
    }

    /** Ritkán kapható termék (a lánc boltjainak kis részében) kevésbé jó választás: 0,5..1 közötti súly. */
    static double availabilityWeight(PriceEntry e) {
        return 0.5 + 0.5 * Math.min(1.0, e.availability() / AVAILABILITY_OK);
    }

    // ---------- kompatibilitás a régebbi tesztekkel / hívókkal ----------

    /** A szükséges mennyiség ára egy tételből (lásd {@link Packaging}). */
    public static Packaging.Purchase buy(Catalog.Product p, PriceEntry e, double needed) {
        Packaging.Pack pack = Packaging.of(p, e);
        if (pack == null) throw new IllegalArgumentException("Nem értelmezhető kiszerelés: " + e.productName());
        return Packaging.buy(p, pack, needed);
    }
}
