package hu.sporolos;

import hu.sporolos.core.PriceRepository;
import hu.sporolos.data.ArfigyeloExcelProvider;
import hu.sporolos.data.Catalog;
import hu.sporolos.data.CijeneArchiveProvider;
import hu.sporolos.market.MarketDef;
import hu.sporolos.market.Markets;
import hu.sporolos.data.CatalogSource;
import hu.sporolos.data.DemoPriceProvider;
import hu.sporolos.data.PriceHistory;
import hu.sporolos.data.PriceProvider;
import hu.sporolos.data.PromoStore;
import hu.sporolos.store.FileStorage;
import hu.sporolos.store.PgStorage;
import hu.sporolos.store.Storage;
import hu.sporolos.web.WebServer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Spórolós Konyha – belépési pont.
 *
 * Beállítások (fontossági sorrendben: parancssori -Dkulcs=érték, környezeti változó, app.properties):
 *   port            (PORT)            – alapértelmezés 8080
 *   host            (HOST)            – alapértelmezés 127.0.0.1 (csak helyi gépről érhető el)
 *   source          (SOURCE)          – auto | arfigyelo | demo  (auto: Árfigyelő, ha nem megy, demó)
 *   arfigyelo.url   (ARFIGYELO_URL)   – a napi Excel címe
 *   arfigyelo.file  (ARFIGYELO_FILE)  – helyi Excel; ha létezik, ezt használja letöltés helyett
 *   data.dir        (DATA_DIR)        – ide menti a letöltött Excelt és az árelőzményeket (alap: ./data)
 *   refresh.hours   (REFRESH_HOURS)   – ennyi óra után tölti újra az árakat (alap: 6)
 *   promo.dir       (PROMO_DIR)       – a láncok saját akcióinak mappája (*.json, *.csv; alap: data/akciok)
 *   promo.feeds     (PROMO_FEEDS)     – akció-feedek URL-jei vesszővel elválasztva (ugyanilyen JSON/CSV)
 *   admin.password  (ADMIN_PASSWORD)  – az admin felület (/admin) jelszava; ha üres, induláskor véletlen jelszó
 *   database.url    (DATABASE_URL)    – postgres://felh:jelszo@host:5432/db; ha üres, fájlokban tárol (data/)
 *   markets         (MARKETS)         – országok vesszővel: hu,hr (alap); csak „hu” esetén nincs horvát árforrás
 *   cijene.url      (CIJENE_URL)      – a horvát napi archívumok listája (alap: https://api.cijene.dev/v0/list)
 *   cijene.file     (CIJENE_FILE)     – kézzel letöltött horvát napi archívum (ZIP); ha meg van adva, nem tölt le
 *   rate.limit      (RATE_LIMIT)      – API-kérés / perc / IP-cím (alap 600; 0 = nincs korlát)
 *   trust.proxy     (TRUST_PROXY)     – reverse proxy mögött az X-Forwarded-For / -Proto használata (alap false)
 *   max.concurrent  (MAX_CONCURRENT)  – egyszerre feldolgozott API-kérések (alap 64; a többi vár, aztán 503)
 *   db.pool.size    (DB_POOL_SIZE)    – legfeljebb ennyi PostgreSQL-kapcsolat (alap 10)
 *   log.requests    (LOG_REQUESTS)    – kérésnapló a kimenetre, a profil-azonosító kitakarva (alap true)
 */
public final class App {

    public static void main(String[] args) throws Exception {
        // ékezetes konzolkimenet Windows/Linux alatt is
        System.setOut(new java.io.PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), true, "UTF-8"));
        System.setErr(new java.io.PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.err), true, "UTF-8"));
        Properties cfg = loadConfig();
        System.out.println("[app] Spórolós Konyha " + WebServer.VERSION + " · Java " + Runtime.version()
                + " · " + Runtime.getRuntime().availableProcessors() + " CPU · max. " + Runtime.getRuntime().maxMemory() / (1024 * 1024) + " MB memória");
        boolean portExplicit = isSet(cfg, "port", "PORT");
        int port = intCfg(cfg, "port", "PORT", 8080, 0, 65535);
        String host = get(cfg, "host", "HOST", "127.0.0.1");
        String source = oneOf(cfg, "source", "SOURCE", "auto", List.of("auto", "arfigyelo", "demo"));
        Path dataDir = Path.of(get(cfg, "data.dir", "DATA_DIR", "data"));
        String url = get(cfg, "arfigyelo.url", "ARFIGYELO_URL", ArfigyeloExcelProvider.DEFAULT_URL);
        String file = get(cfg, "arfigyelo.file", "ARFIGYELO_FILE", "");
        long refreshHours = intCfg(cfg, "refresh.hours", "REFRESH_HOURS", 6, 1, 24 * 7);
        int rateLimit = intCfg(cfg, "rate.limit", "RATE_LIMIT", 600, 0, 1_000_000);
        boolean trustProxy = boolCfg(cfg, "trust.proxy", "TRUST_PROXY", false);
        int maxConcurrent = intCfg(cfg, "max.concurrent", "MAX_CONCURRENT", 64, 1, 10_000);
        int dbPool = intCfg(cfg, "db.pool.size", "DB_POOL_SIZE", 10, 1, 500);
        boolean logRequests = boolCfg(cfg, "log.requests", "LOG_REQUESTS", true);
        List<String> marketIds = java.util.Arrays.stream(get(cfg, "markets", "MARKETS", "hu,hr").toLowerCase().split("[,\\s]+"))
                .filter(x -> !x.isBlank()).toList();
        for (String m : marketIds) {
            if (!List.of("hu", "hr").contains(m)) fail("MARKETS", m, "ismert országok: hu, hr");
        }

        String dbUrl = get(cfg, "database.url", "DATABASE_URL", "");
        Path promoDirEarly = Path.of(get(cfg, "promo.dir", "PROMO_DIR", dataDir.resolve("akciok").toString()));
        Storage storage;
        if (dbUrl.isBlank()) {
            storage = new FileStorage(dataDir, promoDirEarly.resolve(PromoStore.MANUAL_FILE));
        } else {
            System.out.println("[tárolás] Kapcsolódás a PostgreSQL-hez…");
            storage = PgStorage.open(dbUrl, 20, dbPool);
        }
        System.out.println("[tárolás] " + storage.describe());

        CatalogSource catalogSource = new CatalogSource(storage);
        Catalog catalog = catalogSource.load();
        if (catalogSource.modified()) System.out.println("[katalógus] Az adminban módosított receptek és alapanyagok betöltve");
        List<PriceProvider> providers = new ArrayList<>();
        if (!source.equals("demo")) {
            providers.add(new ArfigyeloExcelProvider(catalog, url,
                    file.isBlank() ? null : Path.of(file),
                    dataDir.resolve("arfigyelo_napi_termekadatok.xlsx")));
        }
        if (!source.equals("arfigyelo")) providers.add(new DemoPriceProvider(catalog, LocalDate.now()));

        String feeds = get(cfg, "promo.feeds", "PROMO_FEEDS", "");
        List<String> feedList = feeds.isBlank() ? List.of()
                : java.util.Arrays.stream(feeds.split("[,\\s]+")).filter(x -> !x.isBlank()).toList();
        Path promoDir = Path.of(get(cfg, "promo.dir", "PROMO_DIR", dataDir.resolve("akciok").toString()));
        PromoStore promos = new PromoStore(promoDir, feedList, storage);

        PriceRepository repo = new PriceRepository(catalog, providers,
                new PriceHistory(storage, 56), promos, Duration.ofHours(refreshHours));
        repo.reload();

        String adminPassword = get(cfg, "admin.password", "ADMIN_PASSWORD", "");
        boolean generated = adminPassword.isBlank();
        if (generated) adminPassword = hu.sporolos.store.Ids.newProfileId().substring(0, 12);
        else if (adminPassword.length() < 12 && !host.startsWith("127.") && !host.equals("localhost")) {
            System.err.println("[figyelem] Az ADMIN_PASSWORD rövid (" + adminPassword.length() + " karakter), és az app nem csak helyben érhető el – legalább 12 karakter ajánlott.");
        }
        Markets markets = new Markets(repo);
        if (marketIds.contains("hr")) {
            MarketDef hr = MarketDef.resource("hr");
            Catalog hrCatalog = hr.deriveCatalog(CatalogSource.pretty(catalogSource.products(), "products"),
                    CatalogSource.pretty(catalogSource.recipes(), "recipes"));
            String cijeneFile = get(cfg, "cijene.file", "CIJENE_FILE", "");
            List<PriceProvider> hrProviders = List.of(new CijeneArchiveProvider(hr,
                    get(cfg, "cijene.url", "CIJENE_URL", hr.archiveList()), dataDir.resolve("hr"),
                    cijeneFile.isBlank() ? null : Path.of(cijeneFile)));
            markets.add(hr, new PriceRepository(hr.market(), hrCatalog, hrProviders, null, null,
                    Duration.ofHours(Math.max(refreshHours, 12))));
            System.out.println("[árforrás] Horvátország: a cijene.dev napi archívuma, az első horvát kéréskor töltődik be");
        }
        WebServer server = new WebServer(markets, storage, catalogSource, adminPassword)
                .rateLimit(rateLimit, trustProxy)
                .maxConcurrent(maxConcurrent)
                .accessLog(logRequests);
        // ha a port foglalt (pl. egy korábban elindított példány), a következő szabadot veszi – de csak ha a portot
        // nem adták meg kifejezetten: élesben (Docker, terheléselosztó) a megadott portnak kell lennie, vagy semminek
        int actual = -1;
        int tries = portExplicit ? 1 : 20;
        for (int p = port; p < port + tries && actual < 0; p++) {
            try {
                actual = server.start(host, p);
                if (p != port) System.out.println("[web] A(z) " + port + "-es port foglalt, helyette: " + p);
            } catch (java.net.BindException e) {
                if (port == 0) throw e;
            }
        }
        if (actual < 0) {
            System.err.println(portExplicit ? "A(z) " + port + "-es port foglalt (PORT=" + port + ")."
                    : "A(z) " + port + "–" + (port + 19) + " portok mind foglaltak. Adj meg másikat: PORT=9000");
            System.exit(1);
        }
        // SIGTERM (docker stop, Kubernetes) és Ctrl+C: új kérést nem fogad, a futókat legfeljebb 5 mp-ig befejezi,
        // aztán lezárja az adatbázis-kapcsolatokat
        final Storage storageRef = storage;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("[app] Leállás: a futó kérések befejezése…");
            server.stop(5);
            try {
                storageRef.close();
            } catch (Exception e) {
                System.err.println("[app] A tároló lezárása nem sikerült: " + e.getMessage());
            }
            System.out.println("[app] Leállt.");
        }, "leallas"));
        System.out.println();
        System.out.println("  Spórolós Konyha fut:  http://" + (host.equals("0.0.0.0") ? "localhost" : host) + ":" + actual);
        System.out.println("  Admin felület:        http://" + (host.equals("0.0.0.0") ? "localhost" : host) + ":" + actual + "/admin");
        System.out.println("  Admin jelszó:         " + (generated ? adminPassword + "  (minden indításkor új; állandót az ADMIN_PASSWORD adja)" : "az ADMIN_PASSWORD szerint"));
        System.out.println("  Leállítás: Ctrl+C");
        System.out.println();
    }

    private static Properties loadConfig() throws IOException {
        Properties p = new Properties();
        Path f = Path.of("app.properties");
        if (Files.exists(f)) {
            try (InputStream in = Files.newInputStream(f)) {
                p.load(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        return p;
    }

    private static boolean isSet(Properties p, String key, String env) {
        String v = System.getProperty(key);
        if (v == null || v.isBlank()) v = System.getenv(env);
        if (v == null || v.isBlank()) v = p.getProperty(key);
        return v != null && !v.isBlank();
    }

    /** Érvénytelen beállítás: érthető üzenet, és kilépés (2) – ne egy kivétel-veremmel induljon félre az app. */
    private static void fail(String env, String value, String expected) {
        System.err.println("Hibás beállítás: " + env + "=" + value + " (" + expected + ")");
        System.exit(2);
    }

    static int intCfg(Properties p, String key, String env, int def, int min, int max) {
        String v = get(p, key, env, String.valueOf(def));
        try {
            int n = Integer.parseInt(v);
            if (n < min || n > max) fail(env, v, "egész szám " + min + " és " + max + " között");
            return n;
        } catch (NumberFormatException e) {
            fail(env, v, "egész szám " + min + " és " + max + " között");
            return def;
        }
    }

    static boolean boolCfg(Properties p, String key, String env, boolean def) {
        String v = get(p, key, env, String.valueOf(def)).toLowerCase();
        return switch (v) {
            case "true", "1", "yes", "igen", "on" -> true;
            case "false", "0", "no", "nem", "off" -> false;
            default -> {
                fail(env, v, "true vagy false");
                yield def;
            }
        };
    }

    static String oneOf(Properties p, String key, String env, String def, List<String> allowed) {
        String v = get(p, key, env, def).toLowerCase();
        if (!allowed.contains(v)) fail(env, v, "lehetséges értékek: " + String.join(", ", allowed));
        return v;
    }

    private static String get(Properties p, String key, String env, String def) {
        String v = System.getProperty(key);
        if (v == null || v.isBlank()) v = System.getenv(env);
        if (v == null || v.isBlank()) v = p.getProperty(key);
        return v == null || v.isBlank() ? def : v.trim();
    }
}
