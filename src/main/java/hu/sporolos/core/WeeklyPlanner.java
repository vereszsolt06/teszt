package hu.sporolos.core;

import hu.sporolos.data.Catalog;
import hu.sporolos.data.RecipeMeta;
import hu.sporolos.model.Models.Ingredient;
import hu.sporolos.model.Models.PricedIngredient;
import hu.sporolos.model.Models.Recipe;
import hu.sporolos.model.Models.Suggestion;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Heti vacsoraterv egy boltra, keretre, létszámra, diétára és konyhai felszerelésre.
 *
 * <h3>Hogyan választ?</h3>
 * A boltban árazható receptekből (a receptajánló pontszámával) mohó módon tölti fel a napokat.
 * Minden lépésben azt a receptet veszi, amelyik a legjobb "pontszám − költség" arányt adja,
 * ahol a költség a <b>közös bevásárlólista növekménye</b>: ha két recept ugyanazt a csomagot használja
 * (pl. egy tálca tojás, egy zacskó borsó), a második olcsóbb, így a terv magától is a maradékokat
 * hasznosító párosításokat részesíti előnyben.
 *
 * Változatosság: egy fő alapanyag (pl. csirkemell) legfeljebb egyszer, leves és tészta legfeljebb 1-1,
 * hal legfeljebb 2, édes/reggeli/sütemény nem kerül a vacsorák közé. A kedvelt receptek előnyt kapnak,
 * a nem kedveltek kimaradnak. A keretet tartja: ha egy recept túllépné (a hátralévő napokra is
 * hagyva a legolcsóbb lehetőség árát), nem választja.
 */
public final class WeeklyPlanner {

    public static final String[] DAYS = {"Hétfő", "Kedd", "Szerda", "Csütörtök", "Péntek", "Szombat", "Vasárnap"};
    private static final Set<String> NOT_DINNER = Set.of("édes", "sütemény", "reggeli");

    /**
     * A terv paraméterei.
     *
     * @param people   hány főre (adag / vacsora)
     * @param dinners  hány vacsora (1–7)
     * @param budget   heti keret Ft-ban (0 = nincs)
     * @param diets    kötelező diéták (RecipeMeta.VEGA…)
     * @param equipment konyhai eszközök (üres = mindegy)
     * @param liked    kedvelt receptek
     * @param disliked nem kedvelt receptek (kimaradnak)
     * @param keep     megtartandó receptek napok szerint (null = szabad hely)
     * @param exclude  ezeket ne válassza (pl. a lecserélt receptek)
     * @param seed     véletlen mag ("új hét" gomb)
     */
    public record Request(int people, int dinners, double budget, Set<String> diets, Set<String> equipment,
                          Set<String> liked, Set<String> disliked, List<String> keep, Set<String> exclude, long seed,
                          Collection<String> allowedStores, boolean bulk) {}

    /** Egy nap. */
    public record Day(String label, Suggestion suggestion, RecipeMeta meta, double shareOfList, boolean kept) {}

    /** A közös bevásárlólista egy sora. */
    public record ListItem(String aisle, Catalog.Product product, PricedIngredient priced, double needed,
                           List<String> usedIn, double leftover) {}

    /** A teljes terv. */
    public record Plan(List<Day> days, List<ListItem> items, double checkoutTotal, double staplesTotal,
                       double budget, boolean overBudget, List<String> pantry, double avgKcal, double avgProtein,
                       int candidateCount, List<String> notes) {}

    private final Catalog catalog;
    private final RecipeRecommender rec;

    public WeeklyPlanner(Catalog catalog, RecipeRecommender rec) {
        this.catalog = catalog;
        this.rec = rec;
    }

    public Plan plan(String storeId, Request q) {
        int dinners = Math.max(1, Math.min(7, q.dinners()));
        int people = Math.max(1, Math.min(12, q.people()));
        Random rnd = new Random(q.seed());

        // jelöltek, a létszámra árazva
        Map<String, Suggestion> cand = new LinkedHashMap<>();
        for (Suggestion base : rec.suggestionsFor(storeId, q.allowedStores(), q.bulk())) {
            Recipe r = base.recipe();
            RecipeMeta m = catalog.meta(r.id());
            if (r.tags().stream().anyMatch(NOT_DINNER::contains)) continue;
            if (q.disliked().contains(r.id()) || q.exclude().contains(r.id())) continue;
            if (m == null || !m.fits(q.diets()) || !m.cookableWith(q.equipment())) continue;
            cand.put(r.id(), rec.suggestion(r.id(), storeId, people, q.allowedStores(), q.bulk()));
        }

        Suggestion[] slots = new Suggestion[dinners];
        boolean[] kept = new boolean[dinners];
        List<String> notes = new ArrayList<>();
        for (int i = 0; i < dinners && q.keep() != null && i < q.keep().size(); i++) {
            String id = q.keep().get(i);
            if (id == null || id.isBlank()) continue;
            Suggestion s = rec.suggestion(id, storeId, people, q.allowedStores(), q.bulk());
            if (s != null) {
                slots[i] = s;
                kept[i] = true;
            }
        }

        double budget = q.budget() > 0 ? q.budget() : Double.MAX_VALUE;
        Map<String, Double> payCache = new HashMap<>();
        for (int i = 0; i < dinners; i++) {
            if (slots[i] != null) continue;
            List<Suggestion> chosen = filled(slots);
            Map<String, Double> curNeed = needs(chosen);
            double curCheckout = 0;
            for (Map.Entry<String, Double> e : curNeed.entrySet()) curCheckout += pay(storeId, e.getKey(), e.getValue(), q.bulk(), payCache);
            int remainingAfter = (int) java.util.Arrays.stream(slots).filter(x -> x == null).count() - 1;
            // a hátralévő napokra legalább ennyi kell (a közös csomagok miatt a legolcsóbb recept ~70%-a)
            double reservePerDinner = 0.7 * cand.values().stream().mapToDouble(Suggestion::checkoutTotal).min().orElse(0);
            double perDinnerBudget = q.budget() > 0 ? q.budget() / dinners : 1250.0 * people;

            Suggestion best = null;
            double bestValue = -Double.MAX_VALUE;
            for (Suggestion s : cand.values()) {
                if (!varietyOk(s, chosen)) continue;
                double marginal = 0;
                for (Map.Entry<String, Double> e : needs(List.of(s)).entrySet()) {
                    double cur = curNeed.getOrDefault(e.getKey(), 0.0);
                    marginal += pay(storeId, e.getKey(), cur + e.getValue(), q.bulk(), payCache)
                            - (cur > 0 ? pay(storeId, e.getKey(), cur, q.bulk(), payCache) : 0);
                }
                if (curCheckout + marginal + remainingAfter * reservePerDinner > budget) continue;
                double value = s.score()
                        + (q.liked().contains(s.recipe().id()) ? 0.35 : 0)
                        + rnd.nextDouble() * 0.18
                        - 0.55 * marginal / Math.max(1, perDinnerBudget);
                if (value > bestValue) {
                    bestValue = value;
                    best = s;
                }
            }
            if (best == null) {
                // keretbe nem fér semmi a változatossági szabályokkal: lazítunk, és a legolcsóbbat vesszük
                best = cand.values().stream()
                        .min(java.util.Comparator.comparingDouble(Suggestion::checkoutTotal)).orElse(null);
                if (best != null && notes.stream().noneMatch(n -> n.startsWith("A keret"))) {
                    notes.add("A keret szűkös: néhány napra a legolcsóbb receptet tettük, a változatossági szabályokat lazítva.");
                }
            }
            if (best == null) break;
            slots[i] = best;
            cand.remove(best.recipe().id());
        }

        List<Suggestion> chosen = filled(slots);
        Basket b = basket(storeId, chosen, q.bulk());
        List<Day> days = new ArrayList<>();
        double sumKcal = 0, sumProtein = 0;
        for (int i = 0; i < dinners; i++) {
            if (slots[i] == null) continue;
            RecipeMeta m = catalog.meta(slots[i].recipe().id());
            days.add(new Day(DAYS[i], slots[i], m, b.shareOf.getOrDefault(slots[i].recipe().id(), 0.0), kept[i]));
            if (m != null) {
                sumKcal += m.kcal();
                sumProtein += m.protein();
            }
        }
        if (days.size() < dinners) notes.add("A szűrőknek csak " + days.size() + " recept felelt meg ebben a boltban.");
        boolean over = q.budget() > 0 && b.checkout > q.budget();
        if (over) notes.add("A terv " + Packaging.Money.ft(b.checkout - q.budget()) + "-tal túllépi a keretet.");
        return new Plan(days, b.items, b.checkout, b.staples, q.budget(), over, b.pantry,
                days.isEmpty() ? 0 : sumKcal / days.size(), days.isEmpty() ? 0 : sumProtein / days.size(),
                cand.size() + chosen.size(), notes);
    }

    /** A receptek nem kamraáru alapanyag-szükséglete összesítve (az alapanyag egységében). */
    private Map<String, Double> needs(List<Suggestion> recipes) {
        Map<String, Double> need = new HashMap<>();
        for (Suggestion s : recipes) {
            double factor = s.servings() / (double) s.recipe().servings();
            for (Ingredient i : s.recipe().ingredients()) {
                if (i.pantry() || i.staple()) continue;
                Catalog.Product p = catalog.products().get(i.productKey());
                need.merge(p.key(), i.share() * factor * p.qty(), Double::sum);
            }
        }
        return need;
    }

    /** Egy alapanyag adott mennyiségének pénztári ára (gyorsítótárral). */
    private double pay(String storeId, String key, double amount, boolean bulk, Map<String, Double> cache) {
        String ck = key + "|" + Math.round(amount * 1000);
        Double v = cache.get(ck);
        if (v != null) return v;
        Catalog.Product p = catalog.products().get(key);
        Ingredient ing = new Ingredient(p.key(), p.name(), "", amount / p.qty(), false, p.staple());
        double pay = rec.priceNeed(storeId, ing, p, amount, bulk, 1).checkoutCost();
        cache.put(ck, pay);
        return pay;
    }

    private static List<Suggestion> filled(Suggestion[] slots) {
        List<Suggestion> l = new ArrayList<>();
        for (Suggestion s : slots) if (s != null) l.add(s);
        return l;
    }

    /** Változatosság: fő alapanyag egyszer, leves/tészta egyszer, hal legfeljebb kétszer, ugyanaz a recept egyszer. */
    private boolean varietyOk(Suggestion s, List<Suggestion> chosen) {
        RecipeMeta m = catalog.meta(s.recipe().id());
        int soups = 0, pastas = 0, fish = 0;
        for (Suggestion c : chosen) {
            if (c.recipe().id().equals(s.recipe().id())) return false;
            RecipeMeta cm = catalog.meta(c.recipe().id());
            if (m != null && cm != null && m.mainKey() != null && m.mainKey().equals(cm.mainKey())) return false;
            if (c.recipe().tags().contains("leves")) soups++;
            if (c.recipe().tags().contains("tészta")) pastas++;
            if (c.recipe().tags().contains("hal")) fish++;
        }
        List<String> t = s.recipe().tags();
        return !(t.contains("leves") && soups >= 1) && !(t.contains("tészta") && pastas >= 1) && !(t.contains("hal") && fish >= 2);
    }

    /** A közös bevásárlólista a kiválasztott receptekhez. */
    private record Basket(double checkout, double staples, List<ListItem> items, Map<String, Double> shareOf, List<String> pantry) {}

    private Basket basket(String storeId, List<Suggestion> recipes, boolean bulk) {
        Map<String, Double> need = new LinkedHashMap<>();
        Map<String, List<String>> usedIn = new HashMap<>();
        Map<String, Map<String, Double>> needBy = new HashMap<>();
        Set<String> pantry = new LinkedHashSet<>();
        for (Suggestion s : recipes) {
            double factor = s.servings() / (double) s.recipe().servings();
            for (Ingredient i : s.recipe().ingredients()) {
                if (i.pantry()) {
                    for (String part : i.label().split(",")) pantry.add(part.trim().toLowerCase(java.util.Locale.ROOT));
                    continue;
                }
                Catalog.Product p = catalog.products().get(i.productKey());
                double n = i.share() * factor * p.qty();
                need.merge(p.key(), n, Double::sum);
                usedIn.computeIfAbsent(p.key(), k -> new ArrayList<>()).add(s.recipe().name());
                needBy.computeIfAbsent(p.key(), k -> new HashMap<>()).merge(s.recipe().id(), n, Double::sum);
            }
        }
        double checkout = 0, staples = 0;
        List<ListItem> items = new ArrayList<>();
        Map<String, Double> shareOf = new HashMap<>();
        for (Map.Entry<String, Double> e : need.entrySet()) {
            Catalog.Product p = catalog.products().get(e.getKey());
            Ingredient ing = new Ingredient(p.key(), p.name(), Packaging.Qty.fmt(e.getValue(), p.unit()),
                    e.getValue() / p.qty(), false, p.staple());
            PricedIngredient pi = rec.priceNeed(storeId, ing, p, e.getValue(), bulk, Math.max(1, usedIn.get(p.key()).size()));
            double leftover = Math.max(0, pi.buyAmount() - e.getValue());
            items.add(new ListItem(p.aisle(), p, pi, e.getValue(), List.copyOf(new LinkedHashSet<>(usedIn.get(p.key()))), leftover));
            if (p.staple()) staples += pi.checkoutCost();
            else checkout += pi.checkoutCost();
            // a pénztári összeg szétosztása a receptek között a felhasznált mennyiség arányában
            for (Map.Entry<String, Double> by : needBy.get(p.key()).entrySet()) {
                if (!p.staple()) shareOf.merge(by.getKey(), pi.checkoutCost() * by.getValue() / e.getValue(), Double::sum);
            }
        }
        List<String> aisles = catalog.aisles();
        items.sort(java.util.Comparator.comparingInt((ListItem it) -> {
            int idx = aisles.indexOf(it.aisle());
            return idx < 0 ? 99 : idx;
        }).thenComparing(it -> it.product().name()));
        return new Basket(checkout, staples, items, shareOf, new ArrayList<>(pantry));
    }

    /** A terv csoportosítva bolti sorok szerint (a felülethez). */
    public static Map<String, List<ListItem>> byAisle(Plan p) {
        Map<String, List<ListItem>> m = new LinkedHashMap<>();
        for (ListItem it : p.items()) m.computeIfAbsent(it.aisle(), k -> new ArrayList<>()).add(it);
        return m;
    }
}
