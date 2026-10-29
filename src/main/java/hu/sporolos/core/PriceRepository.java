package hu.sporolos.core;

import hu.sporolos.data.Catalog;
import hu.sporolos.data.PriceHistory;
import hu.sporolos.data.PriceProvider;
import hu.sporolos.data.PriceProvider.Snapshot;
import hu.sporolos.data.Promo;
import hu.sporolos.data.PromoStore;
import hu.sporolos.model.Models.PriceEntry;
import hu.sporolos.model.Models.Store;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Az aktuális árállapot és a belőle számolt elemzések.
 *
 * A források listáját sorban próbálja (pl. Árfigyelő Excel → demó). {@code maxAge} után a háttérben
 * frissít, és addig a régi állapotot szolgálja ki. Ha a frissítés nem sikerül, vagy csak demó adatot adna
 * valós adat helyett, a régi állapot marad.
 *
 * A láncok saját akciói ({@link PromoStore}) az Árfigyelő-árakra rakódnak rá. Mivel a hűségkártyás/appos
 * akciók csak annak érvényesek, akinek van ilyen kártyája, az elemzés kártyakészletenként készül
 * (és gyorsítótárazódik).
 */
public final class PriceRepository {

    /** Egy elemzés adott hűségkártya-készlettel. */
    public record Analysis(List<PriceEntry> entries, DealAnalyzer deals, RecipeRecommender recommender,
                           Map<String, List<PriceEntry>> byItem, List<PromoMerger.PromoStatus> promoStatuses) {}

    /** Egy betöltés eredménye – a webes réteg csak ezt olvassa. */
    public static final class State {
        private final Snapshot snapshot;
        private final List<Store> stores;
        private final Map<String, Integer> itemCounts;
        private final int historyDays;
        private final Instant loadedAt;
        private final List<String> warnings;
        private final List<Promo> promos;
        private final java.util.function.Function<Set<String>, Analysis> builder;
        private final Map<String, Analysis> cache = new ConcurrentHashMap<>();

        State(Snapshot snapshot, List<Store> stores, Map<String, Integer> itemCounts, int historyDays,
              Instant loadedAt, List<String> warnings, List<Promo> promos,
              java.util.function.Function<Set<String>, Analysis> builder) {
            this.snapshot = snapshot;
            this.stores = stores;
            this.itemCounts = itemCounts;
            this.historyDays = historyDays;
            this.loadedAt = loadedAt;
            this.warnings = warnings;
            this.promos = promos;
            this.builder = builder;
        }

        public Snapshot snapshot() { return snapshot; }
        public List<Store> stores() { return stores; }
        public Map<String, Integer> itemCounts() { return itemCounts; }
        public int historyDays() { return historyDays; }
        public Instant loadedAt() { return loadedAt; }
        public List<String> warnings() { return warnings; }
        public List<Promo> promos() { return promos; }

        /** Elemzés a megadott hűségkártyákkal (láncazonosítók); null vagy üres = nincs kártya. */
        public Analysis analysis(Collection<String> cardStores) {
            Set<String> cards = new TreeSet<>();
            if (cardStores != null) cards.addAll(cardStores);
            return cache.computeIfAbsent(String.join(",", cards), k -> builder.apply(cards));
        }

        // kényelmi rövidítések kártya nélküli elemzéshez
        public DealAnalyzer deals() { return analysis(null).deals(); }
        public RecipeRecommender recommender() { return analysis(null).recommender(); }
        public Map<String, List<PriceEntry>> byItem() { return analysis(null).byItem(); }

        State withWarnings(List<String> w, Instant at) {
            State s = new State(snapshot, stores, itemCounts, historyDays, at, w, promos, builder);
            s.cache.putAll(cache);
            return s;
        }
    }

    /** A boltok listájából kihagyjuk, ahol alig van élelmiszer (pl. drogériák). */
    static final double MIN_FOOD_SHARE = 0.15;

    private final Catalog catalog;
    private final List<PriceProvider> providers;
    private final PriceHistory history;
    private final PromoStore promoStore;
    private final Duration maxAge;
    private final AtomicBoolean refreshing = new AtomicBoolean();
    private final ExecutorService background = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "arfrissites");
        t.setDaemon(true);
        return t;
    });
    private volatile State state;

    public PriceRepository(Catalog catalog, List<PriceProvider> providers, PriceHistory history, Duration maxAge) {
        this(catalog, providers, history, null, maxAge);
    }

    public PriceRepository(Catalog catalog, List<PriceProvider> providers, PriceHistory history,
                           PromoStore promoStore, Duration maxAge) {
        this.catalog = catalog;
        this.providers = providers;
        this.history = history;
        this.promoStore = promoStore;
        this.maxAge = maxAge;
    }

    public PromoStore promoStore() {
        return promoStore;
    }

    public Catalog catalog() {
        return catalog;
    }

    /** Az aktuális állapot; ha elavult, a háttérben frissítést indít. */
    public State get() {
        State s = state;
        if (s == null) return reload();
        if (Duration.between(s.loadedAt(), Instant.now()).compareTo(maxAge) > 0
                && refreshing.compareAndSet(false, true)) {
            background.submit(() -> {
                try {
                    reload();
                } catch (RuntimeException e) {
                    System.err.println("[árforrás] Háttérfrissítés sikertelen: " + e.getMessage());
                } finally {
                    refreshing.set(false);
                }
            });
        }
        return s;
    }

    /** Azonnali újratöltés (pl. a felület "Árak frissítése" gombja). */
    public synchronized State reload() {
        List<String> warnings = new ArrayList<>();
        Snapshot snap = null;
        for (PriceProvider p : providers) {
            try {
                snap = p.load();
                break;
            } catch (Exception e) {
                String msg = p.getClass().getSimpleName() + ": "
                        + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                warnings.add(msg);
                System.err.println("[árforrás] " + msg);
            }
        }
        State old = state;
        if (snap == null || (snap.demo() && old != null && !old.snapshot().demo())) {
            if (old == null) throw new IllegalStateException("Egyik árforrás sem működött: " + warnings);
            // valós adat helyett nem váltunk demóra: maradnak a korábbi árak, de jelezzük a hibát
            List<String> w = new ArrayList<>(old.warnings());
            w.add("Frissítés sikertelen, a korábban betöltött árak maradtak: " + String.join("; ", warnings));
            state = old.withWarnings(List.copyOf(w), Instant.now());
            return state;
        }
        PriceHistory h = snap.demo() ? null : history;
        if (h != null) h.record(snap.date(), snap.entries());
        state = build(snap, h, warnings);
        System.out.printf("[árforrás] %s – %s, %d tétel, %d bolt, %d nap előzmény, %d akció%n",
                snap.source(), snap.date(), snap.entries().size(), state.stores().size(), state.historyDays(),
                state.promos().size());
        return state;
    }

    /** Az akciók újraolvasása (pl. új kézi akció után) az árak újratöltése nélkül. */
    public synchronized State reloadPromos() {
        State old = state;
        if (old == null) return reload();
        List<String> warnings = new ArrayList<>(old.warnings());
        warnings.removeIf(w -> w.startsWith("Akció"));
        state = build(old.snapshot(), old.snapshot().demo() ? null : history, warnings);
        return state;
    }

    private State build(Snapshot snap, PriceHistory h, List<String> warnings) {
        List<String> w = new ArrayList<>(warnings);
        List<Promo> promos = List.of();
        if (promoStore != null) {
            PromoStore.Loaded loaded = promoStore.load();
            promos = loaded.promos();
            for (String x : loaded.warnings()) w.add("Akció: " + x);
        }

        Map<String, Integer> counts = new HashMap<>();
        for (PriceEntry e : snap.entries()) counts.merge(e.storeId(), 1, Integer::sum);
        int max = counts.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        List<Store> stores = new ArrayList<>();
        for (Catalog.StoreInfo si : catalog.stores().values()) {
            if (counts.getOrDefault(si.store().id(), 0) >= max * MIN_FOOD_SHARE) stores.add(si.store());
        }
        List<String> ids = stores.stream().map(Store::id).toList();
        int days = h == null ? 0 : h.daysLoaded();
        List<Promo> promoList = promos;
        LocalDate today = LocalDate.now();

        java.util.function.Function<Set<String>, Analysis> builder = cards -> {
            PromoMerger.Result merged = PromoMerger.merge(catalog, snap.entries(), promoList, today, cards);
            DealAnalyzer deals = new DealAnalyzer(merged.entries(), h, merged.promoInfo());
            RecipeRecommender rec = new RecipeRecommender(catalog, merged.entries(), deals, ids, merged.forcedIngredient());
            Map<String, List<PriceEntry>> byItem = new HashMap<>();
            for (PriceEntry e : merged.entries()) byItem.computeIfAbsent(e.itemId(), k -> new ArrayList<>()).add(e);
            return new Analysis(merged.entries(), deals, rec, Collections.unmodifiableMap(byItem), merged.statuses());
        };
        State s = new State(snap, List.copyOf(stores), Collections.unmodifiableMap(counts), days, Instant.now(),
                List.copyOf(w), promoList, builder);
        s.analysis(null); // az alapelemzés azonnal elkészül, hogy az első kérés ne várjon
        return s;
    }
}
