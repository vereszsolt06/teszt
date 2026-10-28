package hu.sporolos.data;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/** Egyszerű statisztikai segédfüggvények. */
public final class Stats {

    private Stats() {}

    public static double median(Collection<Double> values) {
        if (values.isEmpty()) return Double.NaN;
        List<Double> l = new ArrayList<>(values);
        Collections.sort(l);
        int n = l.size();
        return n % 2 == 1 ? l.get(n / 2) : (l.get(n / 2 - 1) + l.get(n / 2)) / 2.0;
    }

    /** Hányadik percentilisben van x a listában (0 = legolcsóbb, 1 = legdrágább). */
    public static double percentileRank(List<Double> sorted, double x) {
        if (sorted.size() <= 1) return 0.5;
        int below = 0;
        for (double v : sorted) if (v < x) below++;
        return below / (double) (sorted.size() - 1);
    }

    public static double clamp01(double v) {
        return Math.max(0, Math.min(1, v));
    }
}
