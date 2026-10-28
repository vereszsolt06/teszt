package hu.sporolos.core;

import hu.sporolos.data.PriceHistory;
import hu.sporolos.data.Stats;
import hu.sporolos.model.Models.Deal;
import hu.sporolos.model.Models.DealType;
import hu.sporolos.model.Models.PriceEntry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Eldönti, mely árak "kiemelkedően jók". Négy jelet néz, és a legerősebbet tartja meg:
 * <ol>
 *   <li>CHAIN – ugyanaz a termék (vonalkód) itt olcsóbb, mint a többi lánc mediánja, és itt a legolcsóbb;</li>
 *   <li>HISTORY – olcsóbb, mint a saját szokásos ára az elmúlt napokban (helyi előzmények);</li>
 *   <li>SPREAD – a lánc legolcsóbb boltjában jóval kevesebb, mint a legdrágábban (jellemzően helyi akció);</li>
 *   <li>CATEGORY – egységárban a kategória legolcsóbb 5%-ában van, és messze a medián alatt
 *       (ez "tartósan olcsó" jel, ezért kisebb súllyal számít).</li>
 * </ol>
 */
public final class DealAnalyzer {

    // küszöbök: ennyi megtakarítás alatt nem tekintjük "kiemelkedőnek"
    static final double CHAIN_MIN = 0.08, HISTORY_MIN = 0.08, SPREAD_MIN = 0.12, CATEGORY_MIN = 0.30;

    private final Map<String, Deal> dealsByKey = new HashMap<>();

    public DealAnalyzer(List<PriceEntry> entries, PriceHistory history) {
        Map<String, List<PriceEntry>> byItem = new HashMap<>();
        Map<String, List<Double>> byCategoryUnit = new HashMap<>();
        for (PriceEntry e : entries) {
            byItem.computeIfAbsent(e.itemId(), k -> new ArrayList<>()).add(e);
            byCategoryUnit.computeIfAbsent(e.categoryId() + "|" + e.unit(), k -> new ArrayList<>()).add(e.unitPrice());
        }
        Map<String, Double> categoryMedian = new HashMap<>();
        for (Map.Entry<String, List<Double>> c : byCategoryUnit.entrySet()) {
            c.getValue().sort(Comparator.naturalOrder());
            categoryMedian.put(c.getKey(), Stats.median(c.getValue()));
        }

        for (PriceEntry e : entries) {
            List<Deal> candidates = new ArrayList<>();

            // 1. másik láncokhoz képest
            List<Double> others = new ArrayList<>();
            for (PriceEntry o : byItem.get(e.itemId())) {
                if (!o.storeId().equals(e.storeId())) others.add(o.price());
            }
            if (!others.isEmpty()) {
                double ref = Stats.median(others);
                double min = others.stream().mapToDouble(Double::doubleValue).min().orElse(ref);
                double s = (ref - e.price()) / ref;
                if (s >= CHAIN_MIN && e.price() <= min) {
                    String where = others.size() == 1 ? "a másik láncban" : "a többi " + others.size() + " láncban";
                    candidates.add(new Deal(e, DealType.CHAIN, ref, s, Stats.clamp01(s / 0.35),
                            pct(s) + "-kal olcsóbb, mint " + where + " (" + ft(ref) + ")"));
                }
            }

            // 2. saját előzményekhez képest
            if (history != null) {
                double usual = history.usualPrice(e.itemId(), e.storeId());
                if (!Double.isNaN(usual) && usual > 0) {
                    double s = (usual - e.price()) / usual;
                    if (s >= HISTORY_MIN) {
                        candidates.add(new Deal(e, DealType.HISTORY, usual, s, Stats.clamp01(s / 0.30),
                                pct(s) + "-kal olcsóbb a szokásos áránál (" + ft(usual) + ")"));
                    }
                }
            }

            // 3. láncon belüli árszórás
            if (e.maxPrice() > e.price() && e.shopCount() >= 2) {
                double s = (e.maxPrice() - e.price()) / e.maxPrice();
                if (s >= SPREAD_MIN) {
                    candidates.add(new Deal(e, DealType.SPREAD, e.maxPrice(), s, 0.8 * Stats.clamp01(s / 0.40),
                            "egyes boltjaikban " + pct(s) + "-kal olcsóbb (máshol akár " + ft(e.maxPrice()) + ")"));
                }
            }

            // 4. kategória egységárához képest
            String cu = e.categoryId() + "|" + e.unit();
            List<Double> sorted = byCategoryUnit.get(cu);
            if (sorted.size() >= 6) {
                double med = categoryMedian.get(cu);
                double s = (med - e.unitPrice()) / med;
                if (s >= CATEGORY_MIN && Stats.percentileRank(sorted, e.unitPrice()) <= 0.05) {
                    candidates.add(new Deal(e, DealType.CATEGORY, med * e.quantity(), s, 0.4 * Stats.clamp01(s / 0.70),
                            "egységárban a legolcsóbbak közt: " + ft(e.unitPrice()) + "/" + e.unit()
                                    + " (átlag " + ft(med) + "/" + e.unit() + ")"));
                }
            }

            candidates.stream().max(Comparator.comparingDouble(Deal::score))
                    .ifPresent(d -> dealsByKey.put(key(e), d));
        }
    }

    /** Az adott bolti termék jó ár-e (null, ha nem). */
    public Deal dealFor(PriceEntry e) {
        return dealsByKey.get(key(e));
    }

    /** Egy lánc kiemelt ajánlatai pontszám szerint, azonos nevűek nélkül. */
    public List<Deal> dealsForStore(String storeId, int limit) {
        List<Deal> l = new ArrayList<>(dealsByKey.values().stream()
                .filter(d -> d.entry().storeId().equals(storeId)).toList());
        l.sort(Comparator.comparingDouble(Deal::score).reversed()
                .thenComparing(d -> d.entry().productName()));
        Set<String> seen = new HashSet<>();
        List<Deal> out = new ArrayList<>();
        for (Deal d : l) {
            if (seen.add(d.entry().productName().toLowerCase())) out.add(d);
            if (out.size() >= limit) break;
        }
        return out;
    }

    public int dealCount(String storeId) {
        return (int) dealsByKey.values().stream().filter(d -> d.entry().storeId().equals(storeId)).count();
    }

    private static String key(PriceEntry e) {
        return e.itemId() + "|" + e.storeId();
    }

    static String pct(double s) {
        return Math.round(s * 100) + "%";
    }

    static String ft(double v) {
        long r = Math.round(v);
        String s = String.valueOf(r);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            if (i > 0 && (s.length() - i) % 3 == 0) sb.append(' ');
            sb.append(s.charAt(i));
        }
        return sb + " Ft";
    }
}
