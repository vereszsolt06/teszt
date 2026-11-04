package hu.sporolos.web;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Egyszerű kéréskorlátozó nyilvános üzemhez: kulcsonként (kliens IP-címe) legfeljebb {@code perMinute} API-kérés
 * egy perces ablakban. Az app egy oldalbetöltéskor ~10 kérést küld, egy hét összerakása, pipálás pár továbbit; az
 * alapérték (600/perc) bőven elég egy háztartásnak egy NAT mögött is, de egy elszabadult szkriptet megfog.
 * Memóriát a lejárt ablakok takarításával tart kordában.
 */
public final class RateLimiter {

    private record Window(long minute, AtomicInteger count) {}

    private final int perMinute;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private volatile long lastSweep;

    /** {@code perMinute <= 0}: nincs korlát. */
    public RateLimiter(int perMinute) {
        this.perMinute = perMinute;
    }

    public boolean enabled() { return perMinute > 0; }

    /** Belefér-e még ez a kérés a kulcs perces keretébe (és ha igen, beszámítja). */
    public boolean allow(String key) {
        return allow(key, System.currentTimeMillis());
    }

    boolean allow(String key, long nowMillis) {
        if (perMinute <= 0) return true;
        long minute = nowMillis / 60_000;
        Window w = windows.compute(key, (k, old) -> old == null || old.minute() != minute ? new Window(minute, new AtomicInteger()) : old);
        boolean ok = w.count().incrementAndGet() <= perMinute;
        if (minute != lastSweep) sweep(minute);
        return ok;
    }

    /** Hány másodperc múlva nyílik új ablak (a Retry-After fejléchez). */
    public static long retryAfterSeconds(long nowMillis) {
        return Math.max(1, 60 - (nowMillis / 1000) % 60);
    }

    private void sweep(long minute) {
        lastSweep = minute;
        for (Iterator<Window> it = windows.values().iterator(); it.hasNext(); ) {
            if (it.next().minute() < minute) it.remove();
        }
    }

    int trackedKeys() { return windows.size(); }
}
