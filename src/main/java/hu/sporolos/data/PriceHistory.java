package hu.sporolos.data;

import hu.sporolos.model.Models.PriceEntry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Helyi árelőzmények: minden nap elmenti a láncok minimum árait (data/history/ÉÉÉÉ-HH-NN.tsv),
 * és ezekből kiszámolja, mennyi egy termék "szokásos" ára az elmúlt napokban.
 * Így derül ki, ha valami ÉPP MOST olcsóbb, mint általában – az Árfigyelő Excel ugyanis csak a mai napot tartalmazza.
 */
public final class PriceHistory {

    private final Path dir;
    private final int maxDays;
    /** (termék|lánc) → előző napok árai */
    private final Map<String, List<Double>> past = new HashMap<>();
    private int daysLoaded;

    public PriceHistory(Path dir, int maxDays) {
        this.dir = dir;
        this.maxDays = maxDays;
    }

    /** A mai pillanatkép mentése (ha még nincs), majd a korábbi napok betöltése. */
    public synchronized void record(LocalDate date, List<PriceEntry> entries) {
        if (dir == null) return;
        try {
            Files.createDirectories(dir);
            Path today = dir.resolve(date + ".tsv");
            if (!Files.exists(today)) {
                StringBuilder sb = new StringBuilder();
                for (PriceEntry e : entries) {
                    sb.append(e.itemId()).append('\t').append(e.storeId()).append('\t').append(e.price()).append('\n');
                }
                Files.writeString(today, sb, StandardCharsets.UTF_8);
            }
            loadPast(date);
        } catch (IOException e) {
            System.err.println("[előzmények] Nem sikerült menteni/olvasni: " + e.getMessage());
        }
    }

    private void loadPast(LocalDate today) throws IOException {
        past.clear();
        daysLoaded = 0;
        List<Path> files;
        try (Stream<Path> s = Files.list(dir)) {
            files = s.filter(p -> p.getFileName().toString().endsWith(".tsv"))
                    .filter(p -> {
                        String d = p.getFileName().toString().replace(".tsv", "");
                        try {
                            LocalDate ld = LocalDate.parse(d);
                            return ld.isBefore(today) && !ld.isBefore(today.minusDays(maxDays));
                        } catch (RuntimeException ex) {
                            return false;
                        }
                    }).toList();
        }
        for (Path f : files) {
            daysLoaded++;
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                String[] c = line.split("\t");
                if (c.length < 3) continue;
                try {
                    past.computeIfAbsent(c[0] + "|" + c[1], k -> new ArrayList<>()).add(Double.parseDouble(c[2]));
                } catch (NumberFormatException ignored) {
                    // sérült sor – kihagyjuk
                }
            }
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
