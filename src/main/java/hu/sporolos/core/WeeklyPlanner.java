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
     * @param leftoverOf napok szerint: melyik korábbi nap főztjének maradékát esszük (null / -1 = aznap főzünk)
     */
    public record Request(int people, int dinners, double budget, Set<String> diets, Set<String> equipment,
                          Set<String> liked, Set<String> disliked, List<String> keep, Set<String> exclude, long seed,
                          Collection<String> allowedStores, boolean bulk, List<Integer> leftoverOf) {

        /** Maradéknapok nélkül (minden nap főzés). */
        public Request(int people, int dinners, double budget, Set<String> diets, Set<String> equipment,
                       Set<String> liked, Set<String> disliked, List<String> keep, Set<String> exclude, long seed,
                       Collection<String> allowedStores, boolean bulk) {
            this(people, dinners, budget, diets, equipment, liked, disliked, keep, exclude, seed, allowedStores, bulk, List.of());
        }
    }

    /** Egy nap. */
    public record Day(String label, Suggestion suggestion, RecipeMeta meta, double shareOfList, boolean kept,
                      int leftoverOf, int coversDays) {}

    /** A közös bevásárlólista egy sora. */
    public record ListItem(String aisle, Catalog.Product product, PricedIngredient priced, double needed,
                           List<String> usedIn, double leftover, String storeId) {}

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

        // maradéknapok: lo[i] = melyik nap főztjét esszük az i. napon (-1 = aznap főzünk)
        int[] lo = new int[dinners];
        java.util.Arrays.fill(lo, -1);
        for (int i = 1; i < dinners && q.leftoverOf() != null && i < q.leftoverOf().size(); i++) {
            Integer k = q.leftoverOf().get(i);
            if (k == null || k < 0 || k >= i) continue;
            lo[i] = lo[k] >= 0 ? lo[k] : k;
        }
        int[] mult = new int[dinners];
        for (int i = 0; i < dinners; i++) if (lo[i] < 0) mult[i]++; else mult[lo[i]]++;

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
            if (id == null || id.isBlank() || lo[i] >= 0) continue;
            Suggestion s = rec.suggestion(id, storeId, people * mult[i], q.allowedStores(), q.bulk());
            if (s != null) {
                slots[i] = s;
                kept[i] = true;
                cand.remove(id);
            }
        }

        double budget = q.budget() > 0 ? q.budget() : Double.MAX_VALUE;
        Map<String, Double> payCache = new HashMap<>();
        int cookingDays = (int) java.util.Arrays.stream(lo).filter(x -> x < 0).count();
        for (int i = 0; i < dinners; i++) {
            if (slots[i] != null || lo[i] >= 0) continue;
            List<Suggestion> chosen = filled(slots);
            Map<String, Double> curNeed = needs(chosen);
            double curCheckout = 0;
            for (Map.Entry<String, Double> e : curNeed.entrySet()) curCheckout += pay(storeId, e.getKey(), e.getValue(), q.bulk(), payCache);
            int remainingAfter = 0;
            for (int j = i + 1; j < dinners; j++) if (slots[j] == null && lo[j] < 0) remainingAfter++;
            // a hátralévő napokra legalább ennyi kell (a közös csomagok miatt a legolcsóbb recept ~70%-a)
            double reservePerDinner = 0.7 * cand.values().stream().mapToDouble(Suggestion::checkoutTotal).min().orElse(0);
            double perDinnerBudget = (q.budget() > 0 ? q.budget() / dinners : 1250.0 * people) * mult[i];

            Suggestion best = null;
            double bestValue = -Double.MAX_VALUE;
            for (int pass = 0; pass < 2 && best == null; pass++) {
                for (Suggestion s : cand.values()) {
                    if (!varietyOk(s, chosen)) continue;
                    RecipeMeta m = catalog.meta(s.recipe().id());
                    // több napra csak olyat főzünk, ami másnap is jó (első körben); ha nincs ilyen, bármit
                    if (pass == 0 && mult[i] > 1 && m != null && !m.keepsWell()) continue;
                    double marginal = 0;
                    for (Map.Entry<String, Double> e : needs(List.of(s)).entrySet()) {
                        double cur = curNeed.getOrDefault(e.getKey(), 0.0);
                        double add = e.getValue() * mult[i];
                        marginal += pay(storeId, e.getKey(), cur + add, q.bulk(), payCache)
                                - (cur > 0 ? pay(storeId, e.getKey(), cur, q.bulk(), payCache) : 0);
                    }
                    if (curCheckout + marginal + remainingAfter * reservePerDinner > budget) continue;
                    double value = s.score()
                            + appeal(s.recipe(), m)
                            // ahol becsült (az Árfigyelőben nem szereplő) alapanyag van, ott az ár is bizonytalan
                            - 0.35 * (1 - s.coverage())
                            + (q.liked().contains(s.recipe().id()) ? 0.35 : 0)
                            + rnd.nextDouble() * 0.18
                            - 0.55 * marginal / Math.max(1, perDinnerBudget);
                    if (value > bestValue) {
                        bestValue = value;
                        best = s;
                    }
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
            cand.remove(best.recipe().id());
            slots[i] = mult[i] == 1 ? best
                    : rec.suggestion(best.recipe().id(), storeId, people * mult[i], q.allowedStores(), q.bulk());
        }

        // ha a szűrőknek kevés recept felel meg, a hiányzó napokra az előző főzés maradéka jut (többet főzünk)
        boolean filledWithLeftovers = false;
        for (int i = 0; i < dinners; i++) {
            if (lo[i] >= 0 || slots[i] != null) continue;
            int k = -1;
            for (int j = i - 1; j >= 0 && k < 0; j--) {
                int root = lo[j] >= 0 ? lo[j] : j;
                if (slots[root] != null) k = root;
            }
            if (k < 0) continue;
            lo[i] = k;
            mult[k]++;
            mult[i] = 0;
            slots[k] = rec.suggestion(slots[k].recipe().id(), storeId, people * mult[k], q.allowedStores(), q.bulk());
            filledWithLeftovers = true;
        }
        if (filledWithLeftovers) {
            notes.add("A beállításaidnak most kevés recept felel meg, ezért néhány napra a korábbi főzés maradéka jut (aznap többet főzöl).");
        }

        List<Suggestion> chosen = filled(slots);
        Basket b = basket(storeId, chosen, q.bulk());
        List<Day> days = new ArrayList<>();
        double sumKcal = 0, sumProtein = 0;
        for (int i = 0; i < dinners; i++) {
            int src = lo[i] >= 0 ? lo[i] : i;
            if (slots[src] == null) continue;
            RecipeMeta m = catalog.meta(slots[src].recipe().id());
            double share = lo[i] >= 0 ? 0 : b.shareOf.getOrDefault(slots[i].recipe().id(), 0.0);
            days.add(new Day(DAYS[i], slots[src], m, share, kept[src], lo[i], mult[src]));
            if (m != null) {
                sumKcal += m.kcal();
                sumProtein += m.protein();
            }
        }
        if (days.size() < dinners) notes.add("A szűrőknek csak " + chosen.size() + " recept felelt meg ebben a boltban.");
        boolean over = q.budget() > 0 && b.checkout > q.budget();
        if (over) notes.add("A terv túllépi a keretet (+" + Packaging.Money.ft(b.checkout - q.budget()) + ").");
        return new Plan(days, b.items, b.checkout, b.staples, q.budget(), over, b.pantry,
                days.isEmpty() ? 0 : sumKcal / days.size(), days.isEmpty() ? 0 : sumProtein / days.size(),
                cand.size() + chosen.size(), notes);
    }

    /**
     * Csere-javaslat egy napra.
     *
     * @param suggestion a recept a nap adagszámára árazva
     * @param weekTotal  a hét pénztári összege, ha ez kerül arra a napra (a többi nap marad)
     */
    public record Option(Suggestion suggestion, double weekTotal, double value) {}

    /**
     * Mi kerülhetne egy nap helyére: a többi napot (a {@code q.keep()} szerint) megtartva a legjobb jelöltek
     * – ugyanazzal a mércével, mint a tervezés (jó árak, „rendes vacsora”, kedvencek, a közös kosár többletköltsége),
     * csak véletlen nélkül. A lecserélendő recept a {@code q.exclude()}-ban legyen.
     */
    public List<Option> options(String storeId, Request q, int day, int limit) {
        int dinners = Math.max(1, Math.min(7, q.dinners()));
        int people = Math.max(1, Math.min(12, q.people()));
        if (day < 0 || day >= dinners) return List.of();
        int[] lo = new int[dinners];
        java.util.Arrays.fill(lo, -1);
        for (int i = 1; i < dinners && q.leftoverOf() != null && i < q.leftoverOf().size(); i++) {
            Integer k = q.leftoverOf().get(i);
            if (k == null || k < 0 || k >= i) continue;
            lo[i] = lo[k] >= 0 ? lo[k] : k;
        }
        int[] mult = new int[dinners];
        for (int i = 0; i < dinners; i++) if (lo[i] < 0) mult[i]++; else mult[lo[i]]++;
        if (lo[day] >= 0) return List.of();
        List<Suggestion> chosen = new ArrayList<>();
        Set<String> kept = new java.util.HashSet<>();
        for (int i = 0; i < dinners && q.keep() != null && i < q.keep().size(); i++) {
            String id = q.keep().get(i);
            if (i == day || id == null || lo[i] >= 0) continue;
            Suggestion s = rec.suggestion(id, storeId, people * mult[i], q.allowedStores(), q.bulk());
            if (s != null) {
                chosen.add(s);
                kept.add(id);
            }
        }
        Map<String, Double> payCache = new HashMap<>();
        Map<String, Double> curNeed = needs(chosen);
        double base = 0;
        for (Map.Entry<String, Double> e : curNeed.entrySet()) base += pay(storeId, e.getKey(), e.getValue(), q.bulk(), payCache);
        double perDinnerBudget = (q.budget() > 0 ? q.budget() / dinners : 1250.0 * people) * mult[day];
        List<Option> out = new ArrayList<>();
        for (Suggestion base0 : rec.suggestionsFor(storeId, q.allowedStores(), q.bulk())) {
            Recipe r = base0.recipe();
            RecipeMeta m = catalog.meta(r.id());
            if (r.tags().stream().anyMatch(NOT_DINNER::contains)) continue;
            if (kept.contains(r.id()) || q.disliked().contains(r.id()) || q.exclude().contains(r.id())) continue;
            if (m == null || !m.fits(q.diets()) || !m.cookableWith(q.equipment())) continue;
            if (mult[day] > 1 && !m.keepsWell()) continue;
            Suggestion s = rec.suggestion(r.id(), storeId, people * mult[day], q.allowedStores(), q.bulk());
            if (s == null || !varietyOk(s, chosen)) continue;
            double marginal = 0;
            for (Map.Entry<String, Double> e : needs(List.of(s)).entrySet()) {
                double cur = curNeed.getOrDefault(e.getKey(), 0.0);
                marginal += pay(storeId, e.getKey(), cur + e.getValue(), q.bulk(), payCache)
                        - (cur > 0 ? pay(storeId, e.getKey(), cur, q.bulk(), payCache) : 0);
            }
            double value = s.score() + appeal(r, m) - 0.35 * (1 - s.coverage())
                    + (q.liked().contains(r.id()) ? 0.35 : 0) - 0.55 * marginal / Math.max(1, perDinnerBudget);
            out.add(new Option(s, base + marginal, value));
        }
        out.sort(java.util.Comparator.comparingDouble(Option::value).reversed());
        return out.size() > limit ? List.copyOf(out.subList(0, limit)) : out;
    }

    /** A terv főzős napjainak receptjei (a maradéknapok nélkül), a bevásárláshoz. */
    public static List<Suggestion> cooked(Plan p) {
        List<Suggestion> out = new ArrayList<>();
        for (Day d : p.days()) if (d.leftoverOf() < 0) out.add(d.suggestion());
        return out;
    }

    /** Ugyanannak a hétnek a pénztári ára egy másik boltban. */
    public record StoreTotal(String storeId, double checkout, double staples, int estimated, List<ListItem> items) {}

    public StoreTotal priceIn(String storeId, List<Suggestion> cooked, boolean bulk) {
        Basket b = basket(storeId, cooked, bulk);
        int est = (int) b.items.stream().filter(it -> it.priced().estimated()).count();
        return new StoreTotal(storeId, b.checkout, b.staples, est, b.items);
    }

    /**
     * Két boltos bevásárlás: minden tétel abban a boltban, ahol olcsóbb (a két bolt a legjobb pár a
     * megadottak közül). Csak akkor ad eredményt, ha legalább {@code minSaving} Ft-ot spórol az egy
     * boltos legjobbhoz képest.
     */
    public record Split(List<String> stores, double checkout, double saving, List<ListItem> items) {}

    public Split bestSplit(List<StoreTotal> totals, double minSaving) {
        StoreTotal single = totals.stream().min(java.util.Comparator.comparingDouble(StoreTotal::checkout)).orElse(null);
        if (single == null) return null;
        Split best = null;
        for (int a = 0; a < totals.size(); a++) {
            for (int c = a + 1; c < totals.size(); c++) {
                StoreTotal x = totals.get(a), y = totals.get(c);
                Map<String, ListItem> byKey = new HashMap<>();
                for (ListItem it : y.items()) byKey.put(it.product().key(), it);
                List<ListItem> items = new ArrayList<>();
                double sum = 0;
                for (ListItem ix : x.items()) {
                    ListItem iy = byKey.get(ix.product().key());
                    ListItem pick = ix;
                    if (iy != null) {
                        boolean xEst = ix.priced().estimated(), yEst = iy.priced().estimated();
                        if (xEst && !yEst || xEst == yEst && iy.priced().checkoutCost() < ix.priced().checkoutCost()) pick = iy;
                    }
                    items.add(pick);
                    if (!pick.product().staple()) sum += pick.priced().checkoutCost();
                }
                if (best == null || sum < best.checkout()) {
                    best = new Split(List.of(x.storeId(), y.storeId()), sum, single.checkout() - sum, items);
                }
            }
        }
        if (best == null || best.saving() < minSaving) return null;
        // ha minden tétel egy boltba esett, az nem két boltos bevásárlás
        long stores = best.items().stream().filter(it -> !it.product().staple()).map(ListItem::storeId).distinct().count();
        return stores < 2 ? null : best;
    }

    private static final Set<String> CROWD_PLEASERS = Set.of("olasz", "mexikói", "ázsiai", "görög", "spanyol", "comfort");

    /**
     * Mennyire „rendes vacsora” egy recept (a NomNom is valódi, laktató hétköznapi vacsorákat ad, nem a
     * legolcsóbb köretet): fehérjedús főétel és népszerű konyhák előnyben, könnyű levesek, főzelékek és
     * a nagyon kis kalóriájú tálak hátrébb. Nagyjából −0,3 … +0,4.
     */
    static double appeal(Recipe r, RecipeMeta m) {
        double a = 0;
        List<String> t = r.tags();
        if (m != null) {
            if (m.moods().contains("fehérjedús")) a += 0.2;
            else if (m.protein() >= 20) a += 0.1;
            if (m.kcal() < 400) a -= 0.15;
            if (m.moods().contains("laktató")) a += 0.05;
        }
        if (t.stream().anyMatch(CROWD_PLEASERS::contains)) a += 0.15;
        if (t.contains("leves") && (m == null || !m.moods().contains("laktató"))) a -= 0.15;
        if (t.contains("főzelék")) a -= 0.08;
        if (t.contains("reggeli")) a -= 0.2;
        return a;
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

    /**
     * A recept „főszereplő” alapanyagai: a fő alapanyag, és azok a jellegzetes zöldségek, hüvelyesek, húsok,
     * amelyekről az étel szól (két karfiolos vagy két lencsés vacsora egy héten már egyhangú, akkor is, ha
     * közösen olcsóbb).
     */
    private static final Set<String> HERO = Set.of("karfiol", "brokkoli", "cukkini", "padlizsan", "sutotok", "kaposzta",
            "kelkaposzta", "spenot", "gomba", "lencse", "voros_bab", "feher_bab", "csicseri", "zoldbab", "borso", "cekla",
            "edesburgonya", "tofu", "lazac", "halfile", "tonhal", "harcsa", "ponty", "csirkemell", "csirkecomb",
            "csirkeszarny", "egesz_csirke", "pulykamell", "daralthus", "daralt_marha", "marhalabszar", "sertes_karaj",
            "sertes_comb", "sertes_tarja", "sertes_oldalas", "virsli", "kolbasz", "parizsi", "tyuk");

    private Set<String> featured(Recipe r) {
        Set<String> out = new java.util.HashSet<>();
        RecipeMeta m = catalog.meta(r.id());
        if (m != null && m.mainKey() != null) out.add(m.mainKey());
        for (Ingredient i : r.ingredients()) if (!i.pantry() && HERO.contains(i.productKey())) out.add(i.productKey());
        return out;
    }

    /**
     * Változatosság: fő alapanyag és jellegzetes zöldség/hüvelyes/hús egyszer, leves/tészta egyszer, hal legfeljebb
     * kétszer, ugyanaz a recept egyszer.
     */
    private boolean varietyOk(Suggestion s, List<Suggestion> chosen) {
        RecipeMeta m = catalog.meta(s.recipe().id());
        Set<String> feat = featured(s.recipe());
        int soups = 0, pastas = 0, fish = 0;
        for (Suggestion c : chosen) {
            if (c.recipe().id().equals(s.recipe().id())) return false;
            RecipeMeta cm = catalog.meta(c.recipe().id());
            if (m != null && cm != null && m.mainKey() != null && m.mainKey().equals(cm.mainKey())) return false;
            for (String f : featured(c.recipe())) if (feat.contains(f)) return false;
            if (c.recipe().tags().contains("leves")) soups++;
            if (c.recipe().tags().contains("tészta")) pastas++;
            if (c.recipe().tags().contains("hal")) fish++;
        }
        List<String> t = s.recipe().tags();
        if (t.contains("leves") && soups >= 1 || t.contains("tészta") && pastas >= 1 || t.contains("hal") && fish >= 2) return false;
        // ugyanaz az ételtípus se legyen kétszer (fasírt és karfiolfasírt, két pörkölt, két rakott étel…)
        Set<String> dish = dishWords(s.recipe());
        if (!dish.isEmpty()) {
            for (Suggestion c : chosen) for (String w : dishWords(c.recipe())) if (dish.contains(w)) return false;
        }
        // a köret se legyen minden nap ugyanaz: krumpli, rizs legfeljebb kétszer (6–7 vacsoránál háromszor)
        int maxSide = chosen.size() >= 5 ? 3 : 2;
        for (Set<String> side : SIDES) {
            if (!usesAny(s.recipe(), side)) continue;
            long n = chosen.stream().filter(c -> usesAny(c.recipe(), side)).count();
            if (n >= maxSide) return false;
        }
        return true;
    }

    /** Ételtípusok, amelyekből egy héten egy elég (a receptnévben, ékezet nélkül keresve). */
    private static final List<String> DISH_WORDS = List.of("fasirt", "porkolt", "paprikas", "rakott", "fozelek", "gulyas",
            "lecso", "rantott", "toltott", "pizza", "salata", "burger", "curry", "chili", "rizotto", "wok", "taco", "tortilla",
            "quesadilla", "lasagne", "palacsinta", "pure", "nokedli", "gyros", "ragu");

    private static Set<String> dishWords(Recipe r) {
        String n = Catalog.normalize(r.name());
        Set<String> out = new java.util.HashSet<>();
        for (String w : DISH_WORDS) if (n.contains(w)) out.add(w);
        return out;
    }

    /** Köret-csoportok a változatossághoz. */
    private static final List<Set<String>> SIDES = List.of(
            Set.of("burgonya", "hasabburgonya"), Set.of("rizs"));

    private static boolean usesAny(Recipe r, Set<String> keys) {
        for (Ingredient i : r.ingredients()) if (!i.pantry() && keys.contains(i.productKey())) return true;
        return false;
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
            items.add(new ListItem(p.aisle(), p, pi, e.getValue(), List.copyOf(new LinkedHashSet<>(usedIn.get(p.key()))), leftover, storeId));
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
