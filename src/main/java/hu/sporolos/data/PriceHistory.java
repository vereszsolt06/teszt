package hu.sporolos.data;

import hu.sporolos.model.Models.PriceEntry;

import hu.sporolos.store.Storage;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Helyi árelőzmények: minden nap elmenti a láncok minimum árait (fájlba vagy PostgreSQL-be, lásd Storage),
 * és ezekből kiszámolja, mennyi egy termék "szokásos" ára az elmúlt napokban.
 * Így derül ki, ha valami ÉPP MOST olcsóbb, mint általában – az Árfigyelő Excel ugyanis csak a mai napot tartalmazza.
 */
public final class PriceHistory {

    private final Storage storage;
    private final int maxDays;
    /** (termék|lánc) → előző napok árai */
    private Map<String, List<Double>> past = new HashMap<>();
    private int daysLoaded;

    /** storage == null: nincs előzmény (pl. tesztben). */
    public PriceHistory(Storage storage, int maxDays) {
        this.storage = storage;
        this.maxDays = maxDays;
    }

    /** A mai pillanatkép mentése (ha még nincs), majd a korábbi napok betöltése. */
    public synchronized void record(LocalDate date, List<PriceEntry> entries) {
        if (storage == null) return;
        try {
            if (!storage.hasPriceDay(date)) storage.savePriceDay(date, entries);
            Storage.PriceDays d = storage.prices(date.minusDays(maxDays), date);
            past = d.prices();
            daysLoaded = d.days();
        } catch (IOException | RuntimeException e) {
            System.err.println("[előzmények] Nem sikerült menteni/olvasni: " + e.getMessage());
        }
    }

    /** A termék szokásos (medián) ára ebben a láncban az előző napokban, vagy NaN, ha kevés az adat (< 3 nap). */
    public synchronized double usualPrice(String itemId, String storeId) {
        List<Double> l = past.get(itemId + "|" + storeId);
        if (l == null || l.size() < 3) return Double.NaN;
        return Stats.median(l);
    }

    public synchronized int daysLoaded() {
        return daysLoaded;
    }
}
