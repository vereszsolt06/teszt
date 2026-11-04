package hu.sporolos.web;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Egyszerű, függőség nélküli metrikák Prometheus-szöveges formátumban (/metrics): kérések útvonal és státusz
 * szerint, válaszidő-összeg és -darabszám (átlaghoz), folyamatban lévő kérések, elutasítások oka szerint.
 * Az útvonal-címkék korlátos halmazból jönnek ({@link WebServer#route}), így a címkék száma nem nőhet a végtelenségig.
 */
final class Metrics {

    private final Map<String, LongAdder> requests = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> durationMicros = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> durationCount = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> rejected = new ConcurrentHashMap<>();
    private final AtomicLong inProgress = new AtomicLong();

    void started() {
        inProgress.incrementAndGet();
    }

    void finished(String route, int status, long nanos) {
        inProgress.decrementAndGet();
        String cls = status < 0 ? "none" : (status / 100) + "xx";
        requests.computeIfAbsent(route + "|" + cls, k -> new LongAdder()).increment();
        durationMicros.computeIfAbsent(route, k -> new LongAdder()).add(nanos / 1000);
        durationCount.computeIfAbsent(route, k -> new LongAdder()).increment();
    }

    void rejected(String reason) {
        rejected.computeIfAbsent(reason, k -> new LongAdder()).increment();
    }

    long requests(String route, String statusClass) {
        LongAdder a = requests.get(route + "|" + statusClass);
        return a == null ? 0 : a.sum();
    }

    long rejectedCount(String reason) {
        LongAdder a = rejected.get(reason);
        return a == null ? 0 : a.sum();
    }

    void write(StringBuilder sb, int freeSlots, int maxConcurrent, long uptimeSeconds) {
        sb.append("# HELP sporolos_http_requests_total HTTP-kérések útvonal és státuszosztály szerint\n");
        sb.append("# TYPE sporolos_http_requests_total counter\n");
        for (var e : new TreeMap<>(requests).entrySet()) {
            String[] k = e.getKey().split("\\|");
            sb.append("sporolos_http_requests_total{route=\"").append(k[0]).append("\",status=\"").append(k[1]).append("\"} ")
                    .append(e.getValue().sum()).append('\n');
        }
        sb.append("# TYPE sporolos_http_request_duration_seconds summary\n");
        for (var e : new TreeMap<>(durationMicros).entrySet()) {
            sb.append("sporolos_http_request_duration_seconds_sum{route=\"").append(e.getKey()).append("\"} ")
                    .append(e.getValue().sum() / 1_000_000.0).append('\n');
            sb.append("sporolos_http_request_duration_seconds_count{route=\"").append(e.getKey()).append("\"} ")
                    .append(durationCount.get(e.getKey()).sum()).append('\n');
        }
        sb.append("# TYPE sporolos_http_rejected_total counter\n");
        for (var e : new TreeMap<>(rejected).entrySet()) {
            sb.append("sporolos_http_rejected_total{reason=\"").append(e.getKey()).append("\"} ").append(e.getValue().sum()).append('\n');
        }
        sb.append("# TYPE sporolos_http_in_progress gauge\n");
        sb.append("sporolos_http_in_progress ").append(inProgress.get()).append('\n');
        sb.append("sporolos_api_slots_free ").append(freeSlots).append('\n');
        sb.append("sporolos_api_slots_max ").append(maxConcurrent).append('\n');
        sb.append("sporolos_uptime_seconds ").append(uptimeSeconds).append('\n');
        sb.append("sporolos_info{version=\"").append(WebServer.VERSION).append("\"} 1\n");
    }
}
