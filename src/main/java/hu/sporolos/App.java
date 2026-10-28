package hu.sporolos;

import hu.sporolos.core.PriceRepository;
import hu.sporolos.data.ArfigyeloExcelProvider;
import hu.sporolos.data.Catalog;
import hu.sporolos.data.DemoPriceProvider;
import hu.sporolos.data.PriceHistory;
import hu.sporolos.data.PriceProvider;
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

        Catalog catalog = Catalog.loadDefault();
        List<PriceProvider> providers = new ArrayList<>();
        if (!source.equals("demo")) {
            providers.add(new ArfigyeloExcelProvider(catalog, url,
                    file.isBlank() ? null : Path.of(file),
                    dataDir.resolve("arfigyelo_napi_termekadatok.xlsx")));
        }
        if (!source.equals("arfigyelo")) providers.add(new DemoPriceProvider(catalog, LocalDate.now()));

        PriceRepository repo = new PriceRepository(catalog, providers,
                new PriceHistory(dataDir.resolve("history"), 28), Duration.ofHours(refreshHours));
        repo.reload();

        WebServer server = new WebServer(repo);
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
