package hu.sporolos.core;

import hu.sporolos.data.Catalog;
import hu.sporolos.data.PriceHistory;
import hu.sporolos.data.PriceProvider;
import hu.sporolos.data.PriceProvider.Snapshot;
import hu.sporolos.model.Models.PriceEntry;
import hu.sporolos.model.Models.Store;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Az aktuális árállapot és a belőle számolt elemzések. A források listáját sorban próbálja
 * (pl. Árfigyelő Excel → demó), és {@code maxAge} után magától frissít.
 */
public final class PriceRepository {

    /** Egy betöltés eredménye – a webes réteg csak ezt olvassa. */
    public record State(Snapshot snapshot, DealAnalyzer deals, RecipeRecommender recommender,
                        List<Store> stores, Map<String, Integer> itemCounts, int historyDays,
                        Instant loadedAt, List<String> warnings) {}

    /** A boltok listájából kihagyjuk, ahol alig van élelmiszer (pl. drogériák). */
    static final double MIN_FOOD_SHARE = 0.15;

    private final Catalog catalog;
    private final List<PriceProvider> providers;
    private final PriceHistory history;
    private final Duration maxAge;
    private volatile State state;

    public PriceRepository(Catalog catalog, List<PriceProvider> providers, PriceHistory history, Duration maxAge) {
        this.catalog = catalog;
        this.providers = providers;
        this.history = history;
        this.maxAge = maxAge;
    }

    public State get() {
        State s = state;
        if (s == null || Duration.between(s.loadedAt(), Instant.now()).compareTo(maxAge) > 0) {
            s = reload();
        }
        return s;
    }

    public synchronized State reload() {
        List<String> warnings = new ArrayList<>();
        Snapshot snap = null;
        for (PriceProvider p : providers) {
            try {
                snap = p.load();
                break;
            } catch (Exception e) {
                String msg = p.getClass().getSimpleName() + ": " + e.getMessage();
                warnings.add(msg);
                System.err.println("[árforrás] " + msg);
            }
        }
        if (snap == null) throw new IllegalStateException("Egyik árforrás sem működött: " + warnings);

        if (history != null && !snap.source().startsWith("Demó")) history.record(snap.date(), snap.entries());

        Map<String, Integer> counts = new HashMap<>();
        for (PriceEntry e : snap.entries()) counts.merge(e.storeId(), 1, Integer::sum);
        int max = counts.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        List<Store> stores = new ArrayList<>();
        for (Catalog.StoreInfo si : catalog.stores().values()) {
            if (counts.getOrDefault(si.store().id(), 0) >= max * MIN_FOOD_SHARE) stores.add(si.store());
        }
        List<String> ids = stores.stream().map(Store::id).toList();

        PriceHistory h = snap.source().startsWith("Demó") ? null : history;
        DealAnalyzer deals = new DealAnalyzer(snap.entries(), h);
        RecipeRecommender rec = new RecipeRecommender(catalog, snap.entries(), deals, ids);
        int days = h == null ? 0 : h.daysLoaded();
        state = new State(snap, deals, rec, stores, counts, days, Instant.now(), warnings);
        System.out.printf("[árforrás] %s – %s, %d tétel, %d bolt, %d nap előzmény%n",
                snap.source(), snap.date(), snap.entries().size(), stores.size(), days);
        return state;
    }
}
