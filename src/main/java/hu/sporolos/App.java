package hu.sporolos;

import hu.sporolos.core.PriceRepository;
import hu.sporolos.data.ArfigyeloExcelProvider;
import hu.sporolos.data.Catalog;
import hu.sporolos.data.DemoPriceProvider;
import hu.sporolos.data.PriceHistory;
import hu.sporolos.data.PriceProvider;
import hu.sporolos.data.PhotoService;
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
 *   database.url    (DATABASE_URL)    – postgres://felh:jelszo@host:5432/db; ha üres, fájlokban tárol (data/)
 *   photos          (PHOTOS)          – on | off: receptfotók letöltése a Wikimedia Commonsról (alap: on)
 *   photo.dir       (PHOTO_DIR)       – saját receptfotók mappája ({receptazonosító}.jpg; alap: data/kepek)
 */
public final class App {

    public static void main(String[] args) throws Exception {
        // ékezetes konzolkimenet Windows/Linux alatt is
        System.setOut(new java.io.PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), true, "UTF-8"));
        System.setErr(new java.io.PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.err), true, "UTF-8"));
        Properties cfg = loadConfig();
        int port = Integer.parseInt(get(cfg, "port", "PORT", "8080"));
        String host = get(cfg, "host", "HOST", "127.0.0.1");
        String source = get(cfg, "source", "SOURCE", "auto").toLowerCase();
        Path dataDir = Path.of(get(cfg, "data.dir", "DATA_DIR", "data"));
        String url = get(cfg, "arfigyelo.url", "ARFIGYELO_URL", ArfigyeloExcelProvider.DEFAULT_URL);
        String file = get(cfg, "arfigyelo.file", "ARFIGYELO_FILE", "");
        long refreshHours = Long.parseLong(get(cfg, "refresh.hours", "REFRESH_HOURS", "6"));

        System.setProperty("java.awt.headless", "true");
        String dbUrl = get(cfg, "database.url", "DATABASE_URL", "");
        Path promoDirEarly = Path.of(get(cfg, "promo.dir", "PROMO_DIR", dataDir.resolve("akciok").toString()));
        Storage storage;
        if (dbUrl.isBlank()) {
            storage = new FileStorage(dataDir, promoDirEarly.resolve(PromoStore.MANUAL_FILE));
        } else {
            System.out.println("[tárolás] Kapcsolódás a PostgreSQL-hez…");
            storage = PgStorage.open(dbUrl, 20);
        }
        System.out.println("[tárolás] " + storage.describe());

        Catalog catalog = Catalog.loadDefault();
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
                new PriceHistory(storage, 28), promos, Duration.ofHours(refreshHours));
        repo.reload();

        boolean photosOn = !get(cfg, "photos", "PHOTOS", "on").equalsIgnoreCase("off");
        PhotoService photos = new PhotoService(catalog, storage,
                Path.of(get(cfg, "photo.dir", "PHOTO_DIR", dataDir.resolve("kepek").toString())),
                photosOn ? PhotoService.defaultHttp() : null);
        photos.startBackground();

        WebServer server = new WebServer(repo, storage, photos);
        int actual = server.start(host, port);
        System.out.println();
        System.out.println("  Spórolós Konyha fut:  http://" + (host.equals("0.0.0.0") ? "localhost" : host) + ":" + actual);
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

    private static String get(Properties p, String key, String env, String def) {
        String v = System.getProperty(key);
        if (v == null || v.isBlank()) v = System.getenv(env);
        if (v == null || v.isBlank()) v = p.getProperty(key);
        return v == null || v.isBlank() ? def : v.trim();
    }
}
