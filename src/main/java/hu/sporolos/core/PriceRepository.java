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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Az aktuális árállapot és a belőle számolt elemzések.
 *
 * A források listáját sorban próbálja (pl. Árfigyelő Excel → demó). {@code maxAge} után a háttérben
 * frissít, és addig a régi állapotot szolgálja ki, így egy lassú letöltés sem akasztja meg a felületet.
 * Ha a frissítés nem sikerül, vagy csak demó adatot adna valós adat helyett, a régi állapot marad.
 */
public final class PriceRepository {

    /** Egy betöltés eredménye – a webes réteg csak ezt olvassa. */
    public record State(Snapshot snapshot, DealAnalyzer deals, RecipeRecommender recommender,
                        List<Store> stores, Map<String, Integer> itemCounts, Map<String, List<PriceEntry>> byItem,
                        int historyDays, Instant loadedAt, List<String> warnings) {}

    /** A boltok listájából kihagyjuk, ahol alig van élelmiszer (pl. drogériák). */
    static final double MIN_FOOD_SHARE = 0.15;

    private final Catalog catalog;
    private final List<PriceProvider> providers;
    private final PriceHistory history;
    private final Duration maxAge;
    private final AtomicBoolean refreshing = new AtomicBoolean();
    private final ExecutorService background = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "arfrissites");
        t.setDaemon(true);
        return t;
    });
    private volatile State state;

    public PriceRepository(Catalog catalog, List<PriceProvider> providers, PriceHistory history, Duration maxAge) {
        this.catalog = catalog;
        this.providers = providers;
        this.history = history;
        this.maxAge = maxAge;
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
            state = new State(old.snapshot(), old.deals(), old.recommender(), old.stores(), old.itemCounts(),
                    old.byItem(), old.historyDays(), Instant.now(), List.copyOf(w));
            return state;
        }

        PriceHistory h = snap.demo() ? null : history;
        if (h != null) h.record(snap.date(), snap.entries());

        Map<String, Integer> counts = new HashMap<>();
        Map<String, List<PriceEntry>> byItem = new HashMap<>();
        for (PriceEntry e : snap.entries()) {
            counts.merge(e.storeId(), 1, Integer::sum);
            byItem.computeIfAbsent(e.itemId(), k -> new ArrayList<>()).add(e);
        }
        int max = counts.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        List<Store> stores = new ArrayList<>();
        for (Catalog.StoreInfo si : catalog.stores().values()) {
            if (counts.getOrDefault(si.store().id(), 0) >= max * MIN_FOOD_SHARE) stores.add(si.store());
        }
        List<String> ids = stores.stream().map(Store::id).toList();

        DealAnalyzer deals = new DealAnalyzer(snap.entries(), h);
        RecipeRecommender rec = new RecipeRecommender(catalog, snap.entries(), deals, ids);
        int days = h == null ? 0 : h.daysLoaded();
        state = new State(snap, deals, rec, List.copyOf(stores), Collections.unmodifiableMap(counts),
                Collections.unmodifiableMap(byItem), days, Instant.now(), List.copyOf(warnings));
        System.out.printf("[árforrás] %s – %s, %d tétel, %d bolt, %d nap előzmény%n",
                snap.source(), snap.date(), snap.entries().size(), stores.size(), days);
        return state;
    }
}
