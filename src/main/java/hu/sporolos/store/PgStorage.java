package hu.sporolos.store;

import hu.sporolos.core.Json;
import hu.sporolos.model.Models.PriceEntry;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * PostgreSQL-tárolás (pl. a docker-compose „db” szolgáltatása). Indításkor létrehozza / frissíti a sémát.
 * Kis kapcsolatkészletet tart; a megszakadt kapcsolatot eldobja, és újat nyit.
 */
public final class PgStorage implements Storage {

    /** A séma verziója; új táblánál / oszlopnál nő, és a {@link #MIGRATIONS} kap egy új elemet. */
    static final String[] MIGRATIONS = {
            // 1: alapséma
            """
            CREATE TABLE IF NOT EXISTS household (
              id          text PRIMARY KEY,
              settings    jsonb NOT NULL DEFAULT '{}'::jsonb,
              created_at  timestamptz NOT NULL DEFAULT now(),
              updated_at  timestamptz NOT NULL DEFAULT now()
            );
            CREATE TABLE IF NOT EXISTS recipe_rating (
              household_id text NOT NULL REFERENCES household(id) ON DELETE CASCADE,
              recipe_id    text NOT NULL,
              liked        boolean NOT NULL,
              rated_at     timestamptz NOT NULL DEFAULT now(),
              PRIMARY KEY (household_id, recipe_id)
            );
            CREATE TABLE IF NOT EXISTS saved_week (
              household_id text PRIMARY KEY REFERENCES household(id) ON DELETE CASCADE,
              week         jsonb NOT NULL,
              saved_at     timestamptz NOT NULL DEFAULT now()
            );
            CREATE TABLE IF NOT EXISTS checked_item (
              household_id text NOT NULL REFERENCES household(id) ON DELETE CASCADE,
              list_key     text NOT NULL,
              item         text NOT NULL,
              PRIMARY KEY (household_id, list_key, item)
            );
            CREATE TABLE IF NOT EXISTS price_day (
              day        date PRIMARY KEY,
              item_count integer NOT NULL,
              saved_at   timestamptz NOT NULL DEFAULT now()
            );
            CREATE TABLE IF NOT EXISTS price_history (
              day      date NOT NULL REFERENCES price_day(day) ON DELETE CASCADE,
              item_id  text NOT NULL,
              store_id text NOT NULL,
              price    numeric(12,2) NOT NULL,
              PRIMARY KEY (day, item_id, store_id)
            );
            CREATE INDEX IF NOT EXISTS price_history_item ON price_history (item_id, store_id);
            CREATE TABLE IF NOT EXISTS recipe_photo (
              recipe_id    text PRIMARY KEY,
              content_type text,
              image        bytea,
              title        text,
              page_url     text,
              author       text,
              license      text,
              license_url  text,
              fetched_at   timestamptz NOT NULL DEFAULT now()
            );
            CREATE TABLE IF NOT EXISTS manual_promo (
              id       text PRIMARY KEY,
              position integer NOT NULL,
              data     jsonb NOT NULL
            );
            """,
            // 2: a receptfotók kikerültek az appból
            "DROP TABLE IF EXISTS recipe_photo",
    };

    private final String url;
    private final Deque<PgClient> pool = new ArrayDeque<>();
    private static final int MAX_IDLE = 4;

    private PgStorage(String url) {
        this.url = url;
    }

    /** Kapcsolódik (indításkor a DB-konténer lassabb lehet: néhányszor újrapróbálja), és frissíti a sémát. */
    public static PgStorage open(String url, int attempts) throws IOException {
        PgStorage s = new PgStorage(url);
        IOException last = null;
        for (int i = 0; i < Math.max(1, attempts); i++) {
            try {
                s.migrate();
                return s;
            } catch (IOException e) {
                last = e;
                if (e instanceof PgClient.PgException) break;
                try {
                    Thread.sleep(1000L * Math.min(5, i + 1));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        throw last;
    }

    private void migrate() throws IOException {
        with(c -> {
            c.exec("CREATE TABLE IF NOT EXISTS schema_version (version integer PRIMARY KEY, applied_at timestamptz NOT NULL DEFAULT now())");
            // egyszerre csak egy példány migráljon
            c.exec("BEGIN");
            try {
                c.exec("LOCK TABLE schema_version IN EXCLUSIVE MODE");
                String v = c.query("SELECT coalesce(max(version), 0) FROM schema_version").rows().get(0)[0];
                for (int i = Integer.parseInt(v); i < MIGRATIONS.length; i++) {
                    c.exec(MIGRATIONS[i]);
                    c.query("INSERT INTO schema_version(version) VALUES ($1::int)", i + 1);
                }
                c.exec("COMMIT");
            } catch (IOException e) {
                rollback(c);
                throw e;
            }
            return null;
        });
    }

    @Override
    public String describe() {
        URI u = URI.create(url.replaceFirst("^postgresql:", "postgres:"));
        return "PostgreSQL (" + u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "") + u.getPath() + ")";
    }

    // ---------- kapcsolatkészlet ----------

    interface Work<T> {
        T run(PgClient c) throws IOException;
    }

    private <T> T with(Work<T> w) throws IOException {
        PgClient c;
        synchronized (pool) {
            c = pool.pollFirst();
        }
        if (c == null) c = PgClient.connect(url);
        try {
            T r = w.run(c);
            release(c);
            return r;
        } catch (PgClient.PgException e) {
            release(c);
            throw e;
        } catch (IOException | RuntimeException e) {
            c.close();
            throw e;
        }
    }

    private void release(PgClient c) {
        if (c.broken()) {
            c.close();
            return;
        }
        synchronized (pool) {
            if (pool.size() < MAX_IDLE) {
                pool.addFirst(c);
                return;
            }
        }
        c.close();
    }

    private static void rollback(PgClient c) {
        try {
            c.exec("ROLLBACK");
        } catch (IOException ignored) {
            // a kapcsolat úgyis eldobódik, ha hibás
        }
    }

    private <T> T tx(Work<T> w) throws IOException {
        return with(c -> {
            c.exec("BEGIN");
            try {
                T r = w.run(c);
                c.exec("COMMIT");
                return r;
            } catch (IOException | RuntimeException e) {
                rollback(c);
                throw e;
            }
        });
    }

    private static void ensureHousehold(PgClient c, String id) throws IOException {
        c.query("INSERT INTO household(id) VALUES ($1) ON CONFLICT (id) DO NOTHING", Ids.check(id));
    }

    // ---------- profil ----------

    @Override
    public Map<String, Object> profile(String id) throws IOException {
        Ids.check(id);
        return with(c -> {
            var rows = c.query("SELECT settings::text FROM household WHERE id = $1", id).rows();
            return rows.isEmpty() ? null : Json.obj(Json.parse(rows.get(0)[0]));
        });
    }

    @Override
    public void saveProfile(String id, Map<String, Object> settings) throws IOException {
        Ids.check(id);
        with(c -> c.query("""
                INSERT INTO household(id, settings) VALUES ($1, $2::jsonb)
                ON CONFLICT (id) DO UPDATE SET settings = excluded.settings, updated_at = now()""",
                id, Json.write(settings)));
    }

    @Override
    public Map<String, Boolean> ratings(String profileId) throws IOException {
        Ids.check(profileId);
        return with(c -> {
            Map<String, Boolean> out = new LinkedHashMap<>();
            for (String[] r : c.query("SELECT recipe_id, liked FROM recipe_rating WHERE household_id = $1 ORDER BY rated_at",
                    profileId).rows()) {
                out.put(r[0], "t".equals(r[1]));
            }
            return out;
        });
    }

    @Override
    public void rate(String profileId, String recipeId, Boolean liked) throws IOException {
        tx(c -> {
            ensureHousehold(c, profileId);
            if (liked == null) {
                c.query("DELETE FROM recipe_rating WHERE household_id = $1 AND recipe_id = $2", profileId, recipeId);
            } else {
                c.query("""
                        INSERT INTO recipe_rating(household_id, recipe_id, liked) VALUES ($1, $2, $3::boolean)
                        ON CONFLICT (household_id, recipe_id) DO UPDATE SET liked = excluded.liked, rated_at = now()""",
                        profileId, recipeId, liked.toString());
            }
            return null;
        });
    }

    @Override
    public void clearRatings(String profileId) throws IOException {
        with(c -> c.query("DELETE FROM recipe_rating WHERE household_id = $1", Ids.check(profileId)));
    }

    @Override
    public Map<String, Object> week(String profileId) throws IOException {
        Ids.check(profileId);
        return with(c -> {
            var rows = c.query("SELECT week::text FROM saved_week WHERE household_id = $1", profileId).rows();
            return rows.isEmpty() ? null : Json.obj(Json.parse(rows.get(0)[0]));
        });
    }

    @Override
    public void saveWeek(String profileId, Map<String, Object> week) throws IOException {
        tx(c -> {
            ensureHousehold(c, profileId);
            c.query("""
                    INSERT INTO saved_week(household_id, week) VALUES ($1, $2::jsonb)
                    ON CONFLICT (household_id) DO UPDATE SET week = excluded.week, saved_at = now()""",
                    profileId, Json.write(week));
            return null;
        });
    }

    @Override
    public Set<String> checked(String profileId, String listKey) throws IOException {
        Ids.check(profileId);
        return with(c -> {
            Set<String> out = new LinkedHashSet<>();
            for (String[] r : c.query("SELECT item FROM checked_item WHERE household_id = $1 AND list_key = $2",
                    profileId, listKey).rows()) {
                out.add(r[0]);
            }
            return out;
        });
    }

    @Override
    public void setChecked(String profileId, String listKey, String item, boolean on) throws IOException {
        tx(c -> {
            ensureHousehold(c, profileId);
            // a régi hetek pipái nem kellenek
            c.query("DELETE FROM checked_item WHERE household_id = $1 AND list_key <> $2", profileId, listKey);
            if (on) {
                c.query("INSERT INTO checked_item VALUES ($1, $2, $3) ON CONFLICT DO NOTHING", profileId, listKey, item);
            } else {
                c.query("DELETE FROM checked_item WHERE household_id = $1 AND list_key = $2 AND item = $3",
                        profileId, listKey, item);
            }
            return null;
        });
    }

    // ---------- árelőzmények ----------

    @Override
    public boolean hasPriceDay(LocalDate day) throws IOException {
        return with(c -> !c.query("SELECT 1 FROM price_day WHERE day = $1::date", day.toString()).rows().isEmpty());
    }

    @Override
    public void savePriceDay(LocalDate day, List<PriceEntry> entries) throws IOException {
        List<Object> rows = new ArrayList<>(entries.size());
        for (PriceEntry e : entries) rows.add(List.of(e.itemId(), e.storeId(), e.price()));
        tx(c -> {
            var ins = c.query("INSERT INTO price_day(day, item_count) VALUES ($1::date, $2::int) ON CONFLICT DO NOTHING",
                    day.toString(), entries.size());
            if (ins.updated() == 0) return null; // már elmentette egy másik példány
            c.query("""
                    INSERT INTO price_history(day, item_id, store_id, price)
                    SELECT $1::date, x->>0, x->>1, (x->>2)::numeric
                    FROM jsonb_array_elements($2::jsonb) AS x
                    ON CONFLICT DO NOTHING""", day.toString(), Json.write(rows));
            return null;
        });
    }

    @Override
    public PriceDays prices(LocalDate from, LocalDate toExclusive) throws IOException {
        return with(c -> {
            Map<String, List<Double>> out = new HashMap<>();
            for (String[] r : c.query("SELECT item_id, store_id, price::float8 FROM price_history WHERE day >= $1::date AND day < $2::date",
                    from.toString(), toExclusive.toString()).rows()) {
                out.computeIfAbsent(r[0] + "|" + r[1], k -> new ArrayList<>()).add(Double.parseDouble(r[2]));
            }
            int days = Integer.parseInt(c.query("SELECT count(*) FROM price_day WHERE day >= $1::date AND day < $2::date",
                    from.toString(), toExclusive.toString()).rows().get(0)[0]);
            return new PriceDays(out, days);
        });
    }

    // ---------- kézi akciók ----------

    @Override
    public List<Map<String, Object>> manualPromos() throws IOException {
        return with(c -> {
            List<Map<String, Object>> out = new ArrayList<>();
            for (String[] r : c.query("SELECT data::text FROM manual_promo ORDER BY position").rows()) {
                out.add(Json.obj(Json.parse(r[0])));
            }
            return out;
        });
    }

    @Override
    public void saveManualPromos(List<Map<String, Object>> promos) throws IOException {
        tx(c -> {
            c.exec("DELETE FROM manual_promo");
            for (int i = 0; i < promos.size(); i++) {
                Map<String, Object> m = promos.get(i);
                c.query("INSERT INTO manual_promo(id, position, data) VALUES ($1, $2::int, $3::jsonb)",
                        String.valueOf(m.getOrDefault("id", "p" + i)), i, Json.write(m));
            }
            return null;
        });
    }

    @Override
    public void close() {
        synchronized (pool) {
            for (PgClient c : pool) c.close();
            pool.clear();
        }
    }
}
