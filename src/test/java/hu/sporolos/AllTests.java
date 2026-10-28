package hu.sporolos;

import hu.sporolos.core.DealAnalyzer;
import hu.sporolos.core.Json;
import hu.sporolos.core.PriceRepository;
import hu.sporolos.core.RecipeRecommender;
import hu.sporolos.data.ArfigyeloExcelProvider;
import hu.sporolos.data.Catalog;
import hu.sporolos.data.DemoPriceProvider;
import hu.sporolos.data.PriceHistory;
import hu.sporolos.data.PriceProvider;
import hu.sporolos.data.XlsxReader;
import hu.sporolos.model.Models.Deal;
import hu.sporolos.model.Models.DealType;
import hu.sporolos.model.Models.PriceEntry;
import hu.sporolos.model.Models.Suggestion;
import hu.sporolos.web.WebServer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Függőség nélküli tesztfuttató: minden "test" kezdetű metódust lefuttat, és összesít.
 * Futtatás: ./build.sh test  (Windows: build.cmd test)
 *
 * Ha az ARFIGYELO_TEST_FILE környezeti változó egy valódi napi Excelre mutat,
 * a teljes folyamatot azon is lefuttatja.
 */
public final class AllTests {

    private static final Catalog CATALOG = Catalog.loadDefault();

    public static void main(String[] args) throws Exception {
        int ok = 0, fail = 0, skip = 0;
        for (Method m : AllTests.class.getDeclaredMethods()) {
            if (!m.getName().startsWith("test")) continue;
            try {
                m.invoke(null);
                System.out.println("  ✓ " + m.getName());
                ok++;
            } catch (java.lang.reflect.InvocationTargetException e) {
                if (e.getCause() instanceof Skip s) {
                    System.out.println("  – " + m.getName() + " (kihagyva: " + s.getMessage() + ")");
                    skip++;
                } else {
                    System.out.println("  ✗ " + m.getName() + ": " + e.getCause());
                    e.getCause().printStackTrace(System.out);
                    fail++;
                }
            }
        }
        System.out.printf("%nEredmény: %d sikeres, %d hibás, %d kihagyva%n", ok, fail, skip);
        System.exit(fail > 0 ? 1 : 0);
    }

    // ---------- JSON ----------

    static void testJsonRoundTrip() {
        String src = "{\"a\":1,\"b\":[true,null,\"x\\\"y\"],\"c\":{\"d\":2.5,\"é\":\"ő\\n\"}}";
        Object parsed = Json.parse(src);
        Map<String, Object> m = Json.obj(parsed);
        eq(1L, m.get("a"), "egész szám");
        eq(2.5, Json.obj(m.get("c")).get("d"), "tört szám");
        eq("x\"y", Json.arr(m.get("b")).get(2), "escape-elt idézőjel");
        eq(src, Json.write(parsed), "visszaírás");
        eq("[1,2.5,\"\\u0001\"]", Json.write(List.of(1, 2.5, "\u0001")), "vezérlőkarakter");
    }

    static void testJsonRejectsGarbage() {
        expectThrows(() -> Json.parse("{\"a\":1} x"), "felesleges karakter");
        expectThrows(() -> Json.parse("[1,2"), "lezáratlan tömb");
    }

    // ---------- katalógus ----------

    static void testCatalogLoadsRecipesAndProducts() {
        check(CATALOG.recipes().size() >= 20, "legalább 20 recept van");
        check(CATALOG.products().get("csirkemell").categoryIds().contains(18), "csirkemell → 18-as kategória");
        check(!CATALOG.products().get("tonhal").tracked(), "a tonhalat az Árfigyelő nem követi");
        // a konstruktor kivételt dob, ha egy recept ismeretlen alapanyagra hivatkozik – ide eljutni = minden rendben
    }

    static void testCatalogStoreResolution() {
        eq("spar", CATALOG.resolveStore("Spar"), "Spar");
        eq("muller", CATALOG.resolveStore("Müller"), "ékezetes név");
        eq("dm", CATALOG.resolveStore("dm"), "dm");
        String id = CATALOG.resolveStore("Új Lánc Kft");
        eq("uj-lanc-kft", id, "ismeretlen lánc felvétele");
        check(CATALOG.stores().containsKey(id), "felvett lánc a listában");
    }

    static void testKeywordMatching() {
        Catalog.Product spag = CATALOG.products().get("spagetti");
        check(CATALOG.matches(spag, entry("1", "Combino Spagetti durum, 1kg", 50, "lidl", 629, "kg", 1)), "spagetti illeszkedik");
        check(!CATALOG.matches(spag, entry("2", "Combino Fusilli durum 500g", 50, "lidl", 325, "kg", 0.5)), "fusilli nem spagetti");
        check(!CATALOG.matches(spag, entry("3", "Spagetti szósz", 999, "lidl", 325, "kg", 0.5)), "más kategória nem illeszkedik");
    }

    // ---------- xlsx ----------

    static void testXlsxReader() throws Exception {
        byte[] xlsx = buildXlsx("Napi feltöltések - 2026-10-28", List.of(
                List.of("Termék név", "Ár"),
                List.of("Tej", "399,0000")));
        XlsxReader.Sheet sheet = XlsxReader.readFirstSheet(new ByteArrayInputStream(xlsx));
        eq("Napi feltöltések - 2026-10-28", sheet.name(), "munkalap neve");
        eq(2, sheet.rows().size(), "sorok száma");
        eq("Tej", sheet.rows().get(1).get(0), "megosztott szöveg");
        eq("399,0000", sheet.rows().get(1).get(1), "szám szövegként");
    }

    static void testArfigyeloExcelParsing() throws Exception {
        List<List<String>> rows = new ArrayList<>();
        rows.add(ARFIGYELO_HEADER);
        rows.add(row("111", "Csirkemell filé Ft/kg", "18", "Csirkemellfilé", "Lidl", "kg", "1", "1990,0000", "2190,0000", "1990,0000", "1", "50", "190"));
        rows.add(row("222", "Pilos vaj 250g", "12", "Vaj", "Lidl", "g", "250", "499,0000", "499,0000", "1,9960", "1", "190", "190"));
        rows.add(row("333", "Sampon", "141", "Sampon", "dm", "l", "0,25", "999,0000", "999,0000", "3996,0000", "1", "200", "260"));
        byte[] xlsx = buildXlsx("Napi feltöltések - 2026-10-27", rows);
        Path f = Files.createTempFile("arfigyelo", ".xlsx");
        Files.write(f, xlsx);
        try {
            PriceProvider.Snapshot s = new ArfigyeloExcelProvider(CATALOG, null, f, null).load();
            eq(LocalDate.of(2026, 10, 27), s.date(), "dátum a munkalap nevéből");
            eq(2, s.entries().size(), "a drogériás tétel (sampon) kiszűrve");
            PriceEntry vaj = s.entries().get(1);
            eq("kg", vaj.unit(), "g → kg");
            eq(0.25, vaj.quantity(), "250 g → 0,25 kg");
            eq(1990.0, s.entries().get(0).price(), "vesszős tizedes");
            eq(2190.0, s.entries().get(0).maxPrice(), "maximum ár");
        } finally {
            Files.deleteIfExists(f);
        }
    }

    static void testExcelWithoutHeaderFails() throws Exception {
        byte[] xlsx = buildXlsx("x", List.of(List.of("valami", "más"), List.of("1", "2")));
        Path f = Files.createTempFile("rossz", ".xlsx");
        Files.write(f, xlsx);
        try {
            expectThrows(() -> new ArfigyeloExcelProvider(CATALOG, null, f, null).load(), "fejléc nélkül hiba");
        } finally {
            Files.deleteIfExists(f);
        }
    }

    // ---------- jó árak ----------

    static void testChainDeal() {
        List<PriceEntry> l = List.of(
                entry("tej1", "Tej 1l", 1, "lidl", 300, "l", 1),
                entry("tej1", "Tej 1l", 1, "spar", 400, "l", 1),
                entry("tej1", "Tej 1l", 1, "tesco", 420, "l", 1));
        DealAnalyzer a = new DealAnalyzer(l, null);
        Deal d = a.dealFor(l.get(0));
        check(d != null, "a Lidl ára jó ár");
        eq(DealType.CHAIN, d.type(), "típus");
        eq(410.0, d.referencePrice(), "viszonyítás a többi lánc mediánja");
        check(a.dealFor(l.get(1)) == null, "a Spar ára nem jó ár");
    }

    static void testSmallDifferenceIsNotADeal() {
        List<PriceEntry> l = List.of(
                entry("x", "Liszt 1kg", 52, "lidl", 280, "kg", 1),
                entry("x", "Liszt 1kg", 52, "spar", 290, "kg", 1));
        check(new DealAnalyzer(l, null).dealFor(l.get(0)) == null, "3% különbség nem kiemelkedő");
    }

    static void testSpreadDeal() {
        PriceEntry e = new PriceEntry("k", "Kefir", 8, "Kefir", "spar", 109, 159, "kg", 0.2, 545, 1, 40, 60);
        Deal d = new DealAnalyzer(List.of(e), null).dealFor(e);
        check(d != null && d.type() == DealType.SPREAD, "láncon belüli árszórás = akciógyanús");
    }

    static void testHistoryDeal() throws Exception {
        Path dir = Files.createTempDirectory("hist");
        try {
            PriceHistory h = new PriceHistory(dir, 28);
            LocalDate today = LocalDate.of(2026, 10, 28);
            for (int i = 1; i <= 4; i++) {
                Files.writeString(dir.resolve(today.minusDays(i) + ".tsv"), "c1\tlidl\t2000\n");
            }
            PriceEntry now = entry("c1", "Csirkemell", 18, "lidl", 1600, "kg", 1);
            h.record(today, List.of(now));
            eq(4, h.daysLoaded(), "4 korábbi nap");
            eq(2000.0, h.usualPrice("c1", "lidl"), "szokásos ár");
            check(Files.exists(dir.resolve(today + ".tsv")), "a mai nap elmentve");
            Deal d = new DealAnalyzer(List.of(now), h).dealFor(now);
            check(d != null && d.type() == DealType.HISTORY, "20%-kal a szokásos alatt");
        } finally {
            try (var s = Files.list(dir)) { s.forEach(p -> p.toFile().delete()); }
            Files.deleteIfExists(dir);
        }
    }

    // ---------- receptajánló ----------

    static void testRecommenderPrefersDeals() {
        // a demó adatokba a Lidlnél nagyon olcsó tojást és tejfölt teszünk: a tojásos/tejfölös receptek előre kerülnek
        List<PriceEntry> base = new ArrayList<>(new DemoPriceProvider(CATALOG, LocalDate.of(2026, 1, 1)).load().entries());
        base.add(new PriceEntry("olcso-tojas", "Akciós tojás 10 db", 14, "Tojás", "lidl", 300, 300, "db", 10, 30, 1, 10, 10));
        base.add(new PriceEntry("olcso-tojas", "Akciós tojás 10 db", 14, "Tojás", "spar", 1100, 1100, "db", 10, 110, 1, 10, 10));
        base.add(new PriceEntry("olcso-tojas", "Akciós tojás 10 db", 14, "Tojás", "tesco", 1150, 1150, "db", 10, 115, 1, 10, 10));
        List<String> stores = List.of("aldi", "lidl", "penny", "auchan", "tesco", "spar");
        DealAnalyzer deals = new DealAnalyzer(base, null);
        RecipeRecommender rec = new RecipeRecommender(CATALOG, base, deals, stores);
        List<Suggestion> lidl = rec.suggestionsFor("lidl");
        check(!lidl.isEmpty(), "van ajánlat");
        for (int i = 1; i < lidl.size(); i++) {
            check(lidl.get(i - 1).score() >= lidl.get(i).score(), "pontszám szerint rendezett");
        }
        // a tojásra épülő rántotta a Lidlben az akciós tojást használja, és jobb pontot kap, mint a Sparban
        Suggestion lidlRantotta = lidl.stream().filter(x -> x.recipe().id().equals("rantotta")).findFirst()
                .orElseThrow(() -> new AssertionError("a rántotta hiányzik a Lidl-ajánlatokból"));
        boolean usesCheapEgg = lidlRantotta.items().stream()
                .anyMatch(pi -> pi.entry() != null && pi.entry().itemId().equals("olcso-tojas"));
        check(usesCheapEgg, "a Lidl-rántotta az akciós tojást használja");
        check(lidlRantotta.dealCount() >= 1, "van benne jó ár");
        Suggestion sparRantotta = rec.suggestion("rantotta", "spar", 0);
        check(lidlRantotta.score() > sparRantotta.score(), "a Lidlben jobb pontot kap, mint a Sparban");
        int lidlRank = lidl.indexOf(lidlRantotta);
        int sparRank = rec.suggestionsFor("spar").stream().map(x -> x.recipe().id()).toList().indexOf("rantotta");
        check(lidlRank < sparRank, "a Lidl-listán előrébb van (" + lidlRank + " vs " + sparRank + ")");
    }

    static void testPortionCostUsesUnitPrice() {
        // 6 tojás 10-es dobozból, 30 Ft/db → 180 Ft
        List<PriceEntry> l = List.of(
                new PriceEntry("t", "Tojás 10 db", 14, "Tojás", "lidl", 300, 300, "db", 10, 30, 1, 1, 1),
                new PriceEntry("v", "Vaj 250 g", 12, "Vaj", "lidl", 500, 500, "kg", 0.25, 2000, 1, 1, 1),
                new PriceEntry("k", "Kenyér 1 kg", 46, "Kenyér", "lidl", 600, 600, "kg", 1, 600, 1, 1, 1));
        RecipeRecommender rec = new RecipeRecommender(CATALOG, l, new DealAnalyzer(l, null), List.of("lidl"));
        // a "rantotta" receptben 6 tojás van (share 0.6 a 10 db-ból)
        Suggestion s = rec.suggestionsFor("lidl").stream().filter(x -> x.recipe().id().equals("rantotta")).findFirst()
                .orElseThrow(() -> new AssertionError("a rántotta nem került be (lefedettség?)"));
        double eggCost = s.items().stream().filter(pi -> pi.entry() != null && pi.entry().itemId().equals("t"))
                .mapToDouble(pi -> pi.cost()).sum();
        eq(180.0, Math.round(eggCost * 100) / 100.0, "6 db × 30 Ft");
        double butter = s.items().stream().filter(pi -> pi.entry() != null && pi.entry().itemId().equals("v"))
                .mapToDouble(pi -> pi.cost()).sum();
        eq(40.0, Math.round(butter * 100) / 100.0, "2 dkg vaj × 2000 Ft/kg");
        check(s.items().stream().anyMatch(pi -> pi.estimated()), "a hiányzó alapanyagok becsültek");
    }

    // ---------- teljes rendszer ----------

    static void testWebServerEndToEnd() throws Exception {
        Path dir = Files.createTempDirectory("sporolos");
        PriceRepository repo = new PriceRepository(CATALOG,
                List.of(new ArfigyeloExcelProvider(CATALOG, null, dir.resolve("nincs.xlsx"), null), // nem létezik → demóra vált
                        new DemoPriceProvider(CATALOG, LocalDate.now())),
                new PriceHistory(dir.resolve("history"), 28), Duration.ofHours(1));
        WebServer server = new WebServer(repo);
        int port = server.start("127.0.0.1", 0);
        try {
            HttpClient c = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + port;
            Map<String, Object> status = Json.obj(Json.parse(get(c, base + "/api/status", 200)));
            check(String.valueOf(status.get("source")).startsWith("Demó"), "demó forrásra váltott");
            check(!Json.arr(status.get("warnings")).isEmpty(), "a hiba a figyelmeztetések közt");
            List<Object> stores = Json.arr(status.get("stores"));
            eq(6, stores.size(), "6 élelmiszerlánc");

            String sugs = get(c, base + "/api/suggestions?store=lidl&limit=5", 200);
            List<Object> sl = Json.arr(Json.parse(sugs));
            check(!sl.isEmpty() && sl.size() <= 5, "1–5 ajánlat");
            Map<String, Object> first = Json.obj(sl.get(0));
            check(Json.obj(first.get("recipe")).get("name") != null, "receptnév");
            check(!Json.arr(first.get("ingredients")).isEmpty(), "hozzávalók");

            List<Object> vega = Json.arr(Json.parse(get(c, base + "/api/suggestions?store=lidl&tag=vega", 200)));
            for (Object o : vega) {
                check(Json.arr(Json.obj(Json.obj(o).get("recipe")).get("tags")).contains("vega"), "csak vega");
            }

            get(c, base + "/api/deals?store=spar&ingredients=true", 200);
            get(c, base + "/api/deals?store=nincsilyen", 400);
            get(c, base + "/api/ismeretlen", 404);
            check(get(c, base + "/", 200).contains("Spórolós Konyha"), "főoldal");
            check(get(c, base + "/app.js", 200).contains("nextRecipe"), "app.js");
            get(c, base + "/../pom.xml", 400);
        } finally {
            server.stop();
            try (var s = Files.walk(dir)) {
                s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    /** Valódi Árfigyelő Excelen (ha meg van adva): a teljes folyamat lefut, és értelmes eredményt ad. */
    static void testRealArfigyeloFile() throws Exception {
        String f = System.getenv("ARFIGYELO_TEST_FILE");
        if (f == null || !Files.exists(Path.of(f))) throw new Skip("ARFIGYELO_TEST_FILE nincs megadva");
        PriceProvider.Snapshot s = new ArfigyeloExcelProvider(CATALOG, null, Path.of(f), null).load();
        check(s.entries().size() > 1000, "sok élelmiszer-tétel: " + s.entries().size());
        PriceRepository repo = new PriceRepository(CATALOG, List.of(() -> s), null, Duration.ofHours(1));
        PriceRepository.State st = repo.reload();
        check(st.stores().size() >= 5, "legalább 5 élelmiszerlánc");
        for (var store : st.stores()) {
            List<Suggestion> l = st.recommender().suggestionsFor(store.id());
            check(l.size() >= 10, store.id() + ": legalább 10 árazható recept (" + l.size() + ")");
            Suggestion top = l.get(0);
            check(top.totalCost() > 50 && top.totalCost() < 20000, store.id() + ": életszerű receptár " + top.totalCost());
            check(st.deals().dealCount(store.id()) > 0, store.id() + ": van jó ár");
        }
    }

    // ---------- segédek ----------

    static final List<String> ARFIGYELO_HEADER = List.of("Termék azonosító", "Termék név", "Kategória azonosító",
            "Kategória név", "Üzletlánc név", "Egység", "Kiszerelés", "Minimum ár", "Maximum ár",
            "Minimum egységár", "Hány üzletláncban elérhető", "Hány boltban elérhető", "Üzletlánc összes boltja");

    static List<String> row(String... c) {
        return List.of(c);
    }

    static PriceEntry entry(String id, String name, int cat, String store, double price, String unit, double qty) {
        return new PriceEntry(id, name, cat, "kat", store, price, price, unit, qty, price / qty, 1, 10, 10);
    }

    static String get(HttpClient c, String url, int expected) throws IOException, InterruptedException {
        HttpResponse<String> r = c.send(HttpRequest.newBuilder(URI.create(url)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        eq(expected, r.statusCode(), "HTTP státusz: " + url);
        return r.body();
    }

    /** Minimális xlsx előállítása tesztekhez (megosztott szövegekkel, mint az Excel). */
    static byte[] buildXlsx(String sheetName, List<List<String>> rows) throws IOException {
        List<String> shared = new ArrayList<>();
        StringBuilder sheet = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>");
        for (int r = 0; r < rows.size(); r++) {
            sheet.append("<row r=\"").append(r + 1).append("\">");
            for (int c = 0; c < rows.get(r).size(); c++) {
                String ref = (char) ('A' + c) + String.valueOf(r + 1);
                shared.add(rows.get(r).get(c));
                sheet.append("<c r=\"").append(ref).append("\" t=\"s\"><v>").append(shared.size() - 1).append("</v></c>");
            }
            sheet.append("</row>");
        }
        sheet.append("</sheetData></worksheet>");
        StringBuilder ss = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">");
        for (String s : shared) ss.append("<si><t>").append(s.replace("&", "&amp;").replace("<", "&lt;")).append("</t></si>");
        ss.append("</sst>");
        String wb = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" "
                + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>"
                + "<sheet name=\"" + sheetName + "\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>";
        String rels = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>"
                + "</Relationships>";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bos)) {
            put(z, "xl/workbook.xml", wb);
            put(z, "xl/_rels/workbook.xml.rels", rels);
            put(z, "xl/sharedStrings.xml", ss.toString());
            put(z, "xl/worksheets/sheet1.xml", sheet.toString());
        }
        return bos.toByteArray();
    }

    private static void put(ZipOutputStream z, String name, String content) throws IOException {
        z.putNextEntry(new ZipEntry(name));
        z.write(content.getBytes(StandardCharsets.UTF_8));
        z.closeEntry();
    }

    static void check(boolean cond, String msg) {
        if (!cond) throw new AssertionError(msg);
    }

    static void eq(Object expected, Object actual, String msg) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(msg + " – várt: " + expected + ", kapott: " + actual);
        }
    }

    interface ThrowingRunnable { void run() throws Exception; }

    static void expectThrows(ThrowingRunnable r, String msg) {
        try {
            r.run();
        } catch (Exception e) {
            return;
        }
        throw new AssertionError("Kivételt vártunk: " + msg);
    }

    static final class Skip extends RuntimeException {
        Skip(String m) { super(m); }
    }
}
