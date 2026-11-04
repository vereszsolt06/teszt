package hu.sporolos;

import hu.sporolos.core.DealAnalyzer;
import hu.sporolos.core.Json;
import hu.sporolos.core.Packaging;
import hu.sporolos.core.PromoMerger;
import hu.sporolos.core.PriceRepository;
import hu.sporolos.core.RecipeRecommender;
import hu.sporolos.core.WeeklyPlanner;
import hu.sporolos.data.ArfigyeloExcelProvider;
import hu.sporolos.data.Catalog;
import hu.sporolos.data.DemoPriceProvider;
import hu.sporolos.data.PriceHistory;
import hu.sporolos.data.PriceProvider;
import hu.sporolos.data.Promo;
import hu.sporolos.data.PromoStore;
import hu.sporolos.data.RecipeMeta;
import hu.sporolos.data.XlsxReader;
import hu.sporolos.model.Models.Deal;
import hu.sporolos.model.Models.DealType;
import hu.sporolos.model.Models.PriceEntry;
import hu.sporolos.model.Models.Suggestion;
import hu.sporolos.core.Amounts;
import hu.sporolos.store.FileStorage;
import hu.sporolos.store.PgStorage;
import hu.sporolos.store.Storage;
import hu.sporolos.web.WebServer;
import hu.sporolos.market.Market;
import hu.sporolos.market.MarketDef;
import hu.sporolos.market.Markets;
import hu.sporolos.market.NameRules;
import hu.sporolos.data.CijeneArchiveProvider;
import hu.sporolos.i18n.Lang;
import hu.sporolos.i18n.Translator;

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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
        System.setProperty("java.awt.headless", "true");
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
            PriceHistory h = new PriceHistory(new FileStorage(dir), 28);
            LocalDate today = LocalDate.of(2026, 10, 28);
            Files.createDirectories(dir.resolve("history"));
            for (int i = 1; i <= 4; i++) {
                Files.writeString(dir.resolve("history").resolve(today.minusDays(i) + ".tsv"), "c1\tlidl\t2000\n");
            }
            PriceEntry now = entry("c1", "Csirkemell", 18, "lidl", 1600, "kg", 1);
            h.record(today, List.of(now));
            eq(4, h.daysLoaded(), "4 korábbi nap");
            eq(2000.0, h.usualPrice("c1", "lidl"), "szokásos ár");
            check(Files.exists(dir.resolve("history").resolve(today + ".tsv")), "a mai nap elmentve");
            Deal d = new DealAnalyzer(List.of(now), h).dealFor(now);
            check(d != null && d.type() == DealType.HISTORY, "20%-kal a szokásos alatt");
        } finally {
            deleteTree(dir);
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

    static void testPackageWeightFromName() {
        eq(0.5, Packaging.packageWeightFromName("Pikok Pure Füstölt szalonna, ca. 500g"), "ca. 500g");
        eq(1.4, Packaging.packageWeightFromName("Pilos Trappista sajt ca1,4kg"), "1,4kg");
        eq(0.25, Packaging.packageWeightFromName("Kalifa lencse 25 dkg"), "dkg");
        check(Double.isNaN(Packaging.packageWeightFromName("Friss csirkecomb , kg")), "nincs tömeg");
        check(Double.isNaN(Packaging.packageWeightFromName("Tej 2,8% 1 l")), "liter nem tömeg");
    }

    static PriceEntry pe(String name, int cat, double price, String unit, double qty) {
        return new PriceEntry(name, name, cat, "kat", "x", price, price, unit, qty, price / qty, 1, 100, 100);
    }

    static void testPurchaseModel() {
        Catalog.Product tojas = CATALOG.products().get("tojas");
        // 6 tojás: a 10-es doboz jobb választás, mint a 30-as tálca, pedig darabra drágább
        var a = RecipeRecommender.buy(tojas, pe("Tojás 10 db", 14, 600, "db", 10), 6);
        var b = RecipeRecommender.buy(tojas, pe("Tojás 30 db", 14, 1650, "db", 30), 6);
        eq(1, a.packages(), "egy 10-es doboz");
        eq(600.0, a.pay(), "pénztári ár");
        eq(360.0, Math.round(a.cost() * 100) / 100.0, "receptre jutó rész");
        check(!a.bulk(), "a 10-es doboz háztartási méret");
        check(b.bulk(), "a 30-as tálca nagy kiszerelés");
        check(a.effective() < b.effective(), "a 10-es doboz a jobb választás");

        // mérlegelős áru: annyit fizetsz, amennyi kell
        Catalog.Product hagyma = CATALOG.products().get("voroshagyma");
        var c = RecipeRecommender.buy(hagyma, pe("Vöröshagyma lédig", 40, 250, "kg", 1), 0.25);
        eq(0, c.packages(), "mérlegelős");
        eq(62.5, c.pay(), "0,25 kg × 250 Ft");
        check(c.buyText().contains("2 db"), "darabszám is: " + c.buyText());

        // mérlegelősnél legalább egy darabot veszel: 1 dkg fokhagymához egy egész fejet
        Catalog.Product fok = CATALOG.products().get("fokhagyma");
        var f = RecipeRecommender.buy(fok, pe("Fokhagyma lédig", 39, 1500, "kg", 1), 0.01);
        eq(15.0, Math.round(f.cost() * 100) / 100.0, "a receptre jutó rész 1 dkg");
        eq(75.0, Math.round(f.pay() * 100) / 100.0, "de egy 5 dkg-os fejet fizetsz");

        // kilós árú, de a névben szereplő csomagtömegű termék: egész csomagot veszel
        Catalog.Product bacon = CATALOG.products().get("bacon");
        var d = RecipeRecommender.buy(bacon, pe("Füstölt szalonna, ca. 500g", 89, 2400, "kg", 1), 0.2);
        eq(1, d.packages(), "egy csomag");
        eq(1200.0, d.pay(), "0,5 kg × 2400 Ft");

        // csomagolt áru kilós egységgel, tömeg nélkül (pl. "Penny finomliszt"): 1 kg-os csomag, nem mérlegelős
        Catalog.Product liszt = CATALOG.products().get("liszt");
        var l = RecipeRecommender.buy(liszt, pe("Penny finomliszt", 52, 178, "kg", 1), 0.03);
        eq(1, l.packages(), "egy csomag liszt");
        eq(178.0, l.pay(), "a teljes csomagot fizeted, nem 3 dkg-ot");

        // darab ↔ kg: 0,15 kg paprika a "3 db"-os csomagból = 1 csomag, a receptre 1 db ára jut
        Catalog.Product paprika = CATALOG.products().get("paprika");
        var pp = RecipeRecommender.buy(paprika, pe("TV paprika 3 db", 35, 300, "db", 3), 0.15);
        eq(1, pp.packages(), "egy 3 db-os csomag");
        eq(100.0, Math.round(pp.cost() * 100) / 100.0, "1 db = 100 Ft jut a receptre");

        // kg ↔ darab: az 52 g-os zsemle 1 db; 3 zsemléhez 3-at veszel
        Catalog.Product zsemle = CATALOG.products().get("zsemle");
        var z = RecipeRecommender.buy(zsemle, pe("Zsemle 52 g", 94, 65, "kg", 0.052), 3);
        eq(3, z.packages(), "3 zsemle");
        eq(195.0, z.pay(), "3 × 65 Ft");

        // csomós zöldség: az ár egy csomóra szól, függetlenül attól, hogy a sor kg-ot vagy 5 db-ot ír
        Catalog.Product petr = CATALOG.products().get("petrezselyem");
        var cs = RecipeRecommender.buy(petr, pe("Petrezselyemgyökér csomós", 37, 689, "kg", 1), 0.1);
        eq(1, cs.packages(), "egy csomó");
        eq(689.0, cs.pay(), "egy csomó ára");
        eq("1 csomó", cs.buyText(), "szöveg");

        // 15% tűrés: 0,5 kg-hoz elég egy 0,45 kg-os csomag
        Catalog.Product pulyka = CATALOG.products().get("pulykamell");
        eq(1, RecipeRecommender.buy(pulyka, pe("Pulykamellfilé 450 g", 21, 1400, "kg", 0.45), 0.5).packages(),
                "nem veszünk két csomagot");

        // nagy kiszerelés: 5 kg burgonya 1,5 kg-hoz nagy, 2 kg háztartási; 20 adagnál (7,5 kg) az 5 kg sem nagy
        Catalog.Product krumpli = CATALOG.products().get("burgonya");
        check(RecipeRecommender.buy(krumpli, pe("Burgonya 5 kg", 41, 981, "kg", 5), 1.5).bulk(), "5 kg nagy");
        check(!RecipeRecommender.buy(krumpli, pe("Burgonya 2 kg", 41, 620, "kg", 2), 1.5).bulk(), "2 kg háztartási");
        check(!RecipeRecommender.buy(krumpli, pe("Burgonya 5 kg", 41, 981, "kg", 5), 7.5).bulk(), "sok adaghoz nem nagy");
    }

    static void testHouseholdDefaultWithBulkAlternative() {
        // egy boltban: 10-es tojás 600 Ft és 30-as tálca 1350 Ft (45 Ft/db), 1,5 kg krumplihoz lédig és 5 kg-os zsák
        List<PriceEntry> l = new ArrayList<>(List.of(
                new PriceEntry("t10", "Tojás 10 db", 14, "Tojás", "lidl", 600, 600, "db", 10, 60, 1, 100, 100),
                new PriceEntry("t30", "Tojás 30 db", 14, "Tojás", "lidl", 1350, 1350, "db", 30, 45, 1, 100, 100),
                new PriceEntry("k1", "Burgonya lédig", 41, "Burgonya", "lidl", 300, 300, "kg", 1, 300, 1, 100, 100),
                new PriceEntry("k5", "Burgonya 5 kg", 41, "Burgonya", "lidl", 900, 900, "kg", 5, 180, 1, 100, 100)));
        RecipeRecommender rec = new RecipeRecommender(CATALOG, l, new DealAnalyzer(l, null), List.of("lidl"));
        Suggestion home = rec.suggestion("rakott-krumpli", "lidl", 0, null, false);
        var egg = home.items().stream().filter(pi -> pi.ingredient().productKey().equals("tojas")).findFirst().orElseThrow();
        eq("t10", egg.entry().itemId(), "alapból a 10-es doboz");
        check(egg.alternative() != null && egg.alternative().entry().itemId().equals("t30"), "a tálca alternatívaként");
        check(egg.alternative().bulk(), "az alternatíva nagy kiszerelés");
        var potato = home.items().stream().filter(pi -> pi.ingredient().productKey().equals("burgonya")).findFirst().orElseThrow();
        eq("k1", potato.entry().itemId(), "alapból lédig krumpli");
        eq("k5", potato.alternative().entry().itemId(), "az 5 kg-os zsák alternatíva");
        check(home.altTotalCost() < home.totalCost(), "nagy kiszerelésekkel olcsóbb a recept");
        check(home.altCheckoutTotal() > home.checkoutTotal(), "de most többet fizetsz");

        Suggestion bulk = rec.suggestion("rakott-krumpli", "lidl", 0, null, true);
        var egg2 = bulk.items().stream().filter(pi -> pi.ingredient().productKey().equals("tojas")).findFirst().orElseThrow();
        eq("t30", egg2.entry().itemId(), "nagy kiszerelés módban a tálca");
        eq("t10", egg2.alternative().entry().itemId(), "és a doboz az alternatíva");
        eq(Math.round(home.altTotalCost()), Math.round(bulk.totalCost()), "a két mód összegei egymás tükrei");
    }

    static void testStoreFilterChangesComparison() {
        List<PriceEntry> base = new ArrayList<>(new DemoPriceProvider(CATALOG, LocalDate.of(2026, 1, 1)).load().entries());
        List<String> stores = List.of("aldi", "lidl", "penny", "auchan", "tesco", "spar");
        RecipeRecommender rec = new RecipeRecommender(CATALOG, base, new DealAnalyzer(base, null), stores);
        Suggestion all = rec.suggestion("lecso", "lidl", 0, null, false);
        Suggestion two = rec.suggestion("lecso", "lidl", 0, List.of("lidl", "spar"), false);
        Suggestion spar = rec.suggestion("lecso", "spar", 0, null, false);
        eq(Math.round(spar.totalCost()), Math.round(two.otherStoresCost()), "csak a Sparhoz hasonlít");
        check(all.otherStoresCost() != two.otherStoresCost() || Math.round(spar.totalCost()) == Math.round(all.otherStoresCost()),
                "a szűrés megváltoztatja a viszonyítást");
        check(!rec.suggestionsFor("lidl", List.of("lidl", "spar"), false).isEmpty(), "szűrt rangsor");
    }

    static void testAvailabilityLowersDeal() {
        PriceEntry common = new PriceEntry("a", "Kefir", 8, "Kefir", "spar", 100, 160, "kg", 0.2, 500, 1, 300, 375);
        PriceEntry rare = new PriceEntry("b", "Kefir2", 8, "Kefir", "spar", 100, 160, "kg", 0.2, 500, 1, 15, 375);
        DealAnalyzer a = new DealAnalyzer(List.of(common, rare), null);
        Deal dc = a.dealFor(common), dr = a.dealFor(rare);
        check(dc != null && dr != null, "mindkettő jó ár");
        check(dr.score() < dc.score() * 0.6, "a ritkán kapható pontszáma jóval kisebb");
        check(dr.reason().contains("%-ában kapható"), "az indoklás jelzi");
        eq(0.04, rare.availability(), "15/375");
    }

    static void testRefreshKeepsRealDataWhenSourceFails() throws Exception {
        PriceProvider.Snapshot real = new PriceProvider.Snapshot(LocalDate.of(2026, 10, 28),
                new DemoPriceProvider(CATALOG, LocalDate.of(2026, 10, 28)).load().entries(), "valós", false);
        int[] calls = {0};
        PriceProvider flaky = () -> {
            if (calls[0]++ == 0) return real;
            throw new IOException("nincs net");
        };
        PriceRepository repo = new PriceRepository(CATALOG,
                List.of(flaky, new DemoPriceProvider(CATALOG, LocalDate.now())), null, Duration.ofHours(1));
        repo.reload();
        PriceRepository.State after = repo.reload(); // a második betöltéskor a valós forrás hibázik
        eq("valós", after.snapshot().source(), "a valós adat megmaradt, nem váltott demóra");
        check(after.warnings().stream().anyMatch(w -> w.contains("nincs net")), "a hiba látszik a figyelmeztetésekben");
    }

    static void testDownloadCachesAndFallsBack() throws Exception {
        List<List<String>> rows = new ArrayList<>();
        rows.add(ARFIGYELO_HEADER);
        rows.add(row("111", "Csirkemell filé Ft/kg", "18", "Csirkemellfilé", "Lidl", "kg", "1", "1990,0000", "2190,0000", "1990,0000", "1", "50", "190"));
        byte[] xlsx = buildXlsx("Napi feltöltések - 2026-10-26", rows);
        var http = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        boolean[] up = {true};
        http.createContext("/napi.xlsx", ex -> {
            if (up[0]) {
                ex.sendResponseHeaders(200, xlsx.length);
                ex.getResponseBody().write(xlsx);
            } else {
                ex.sendResponseHeaders(503, -1);
            }
            ex.close();
        });
        http.start();
        Path dir = Files.createTempDirectory("letoltes");
        try {
            String url = "http://127.0.0.1:" + http.getAddress().getPort() + "/napi.xlsx";
            Path cache = dir.resolve("cache.xlsx");
            ArfigyeloExcelProvider p = new ArfigyeloExcelProvider(CATALOG, url, null, cache);
            PriceProvider.Snapshot s1 = p.load();
            check(s1.source().contains("friss letöltés"), "letöltésből: " + s1.source());
            check(Files.exists(cache), "elmentve a gyorsítótárba");
            up[0] = false;
            PriceProvider.Snapshot s2 = p.load();
            check(s2.source().contains("utoljára letöltött"), "a mentett példányra váltott: " + s2.source());
            eq(LocalDate.of(2026, 10, 26), s2.date(), "a mentett fájl dátuma");
        } finally {
            http.stop(0);
            try (var w = Files.walk(dir)) { w.sorted(java.util.Comparator.reverseOrder()).forEach(f -> f.toFile().delete()); }
        }
    }

    static void testPromoCsvParsing() {
        String csv = "bolt;termek;ar;egyseg;kiszereles;rendes_ar;tol;ig;alapanyag;vonalkod;kartya\n"
                + "Lidl;Csirkemellfilé tálcás;1499;kg;1;1999;2026.10.30.;2026.11.05.;csirkemell;;nem\n"
                + "SPAR;Tojás M 10 db;499;db;10;;2026-10-30;2026-11-05;tojas;;igen\n"
                + "Lidl;hibás sor;;kg;1;;;;csirkemell;;\n";
        List<String> warnings = new ArrayList<>();
        List<Promo> ps = parsePromos(csv, warnings);
        eq(2, ps.size(), "két érvényes sor");
        eq(1, warnings.size(), "egy hibás sor");
        Promo a = ps.get(0);
        eq("lidl", a.storeId(), "bolt normalizálva");
        eq(LocalDate.of(2026, 10, 30), a.validFrom(), "pontos dátum");
        eq("csirkemell", a.ingredientKey(), "alapanyag");
        check(!a.card(), "nem kártyás");
        check(ps.get(1).card() && ps.get(1).storeId().equals("spar"), "kártyás Spar akció");
    }

    /** A PromoStore csomagszintű parse metódusa reflexióval (a tesztek a hu.sporolos csomagban vannak). */
    static List<Promo> parsePromos(String text, List<String> warnings) {
        try {
            var m = PromoStore.class.getDeclaredMethod("parse", String.class, boolean.class, String.class, List.class);
            m.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<Promo> r = (List<Promo>) m.invoke(null, text, true, "teszt", warnings);
            return r;
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    static Promo promo(String id, String store, String name, String ingredient, String itemId, double price,
                       double regular, String unit, double qty, boolean card, LocalDate from, LocalDate to) {
        return new Promo(id, store, name, ingredient, itemId, price, regular, unit, qty, from, to, card, "teszt");
    }

    static void testPromoMergeRules() {
        LocalDate today = LocalDate.of(2026, 10, 31);
        List<PriceEntry> base = List.of(
                new PriceEntry("599", "Pilos vaj 250 g", 12, "Vaj", "lidl", 499, 499, "kg", 0.25, 1996, 1, 200, 200),
                new PriceEntry("cs1", "Húsfarm csirkemellfilé, kg", 18, "Csirkemellfilé", "lidl", 1999, 1999, "kg", 1, 1999, 1, 200, 200),
                new PriceEntry("cs2", "Csirkemell tokány, kg", 18, "Csirkemellfilé", "lidl", 1859, 1859, "kg", 1, 1859, 1, 200, 200));
        List<Promo> promos = List.of(
                promo("a", "lidl", "Pilos vaj", "vaj", "599", 399, 0, "kg", 0.25, false, null, null),          // felülírja
                promo("b", "lidl", "Pilos vaj", "vaj", "599", 520, 0, "kg", 0.25, false, null, null),          // drágább: már benne
                promo("c", "lidl", "Csirkemellfilé friss", "csirkemell", null, 1499, 1999, "kg", 1, false, null, null), // új tétel
                promo("d", "lidl", "Csirkemell tokány", "csirkemell", null, 1899, 0, "kg", 1, false, null, null),   // már benne (1859)
                promo("e", "lidl", "Tonhal", "tonhal", null, 599, 849, "kg", 0.16, true, null, null),         // kártyás, nincs kártya
                promo("f", "lidl", "Csirkecomb", "csirkecomb", null, 699, 0, "kg", 1, false, null, today.minusDays(1)), // lejárt
                promo("g", "lidl", "Csirkecomb", "csirkecomb", null, 699, 0, "kg", 1, false, today.plusDays(1), null),  // még nem
                promo("h", "aldi2", "x", "vaj", null, 1, 0, "db", 1, false, null, null));                        // ismeretlen bolt
        PromoMerger.Result r = PromoMerger.merge(CATALOG, base, promos, today, Set.of());
        Map<String, PromoMerger.Status> st = new HashMap<>();
        for (var x : r.statuses()) st.put(x.promo().id(), x.status());
        eq(PromoMerger.Status.ACTIVE, st.get("a"), "a: beépítve");
        eq(PromoMerger.Status.ALREADY_LISTED, st.get("b"), "b: már benne");
        eq(PromoMerger.Status.ACTIVE, st.get("c"), "c: új tétel");
        eq(PromoMerger.Status.ALREADY_LISTED, st.get("d"), "d: az Árfigyelőben olcsóbb");
        eq(PromoMerger.Status.CARD_OFF, st.get("e"), "e: kártya kell");
        eq(PromoMerger.Status.EXPIRED, st.get("f"), "f: lejárt");
        eq(PromoMerger.Status.NOT_STARTED, st.get("g"), "g: még nem kezdődött");
        eq(PromoMerger.Status.NO_MATCH, st.get("h"), "h: ismeretlen bolt");
        PriceEntry vaj = r.entries().stream().filter(e -> e.itemId().equals("599")).findFirst().orElseThrow();
        eq(399.0, vaj.price(), "a vaj akciós ára");
        eq(4, r.entries().size(), "3 eredeti + 1 új tétel");
        check(r.forcedIngredient().containsValue("csirkemell"), "az új tétel a csirkemellhez kötve");

        // kártyával az "e" is bekerül, és a tonhal (amit az Árfigyelő nem követ) árazhatóvá válik
        PromoMerger.Result withCard = PromoMerger.merge(CATALOG, base, promos, today, Set.of("lidl"));
        check(withCard.statuses().stream().anyMatch(x -> x.promo().id().equals("e") && x.status() == PromoMerger.Status.ACTIVE),
                "kártyával aktív");
        DealAnalyzer deals = new DealAnalyzer(withCard.entries(), null, withCard.promoInfo());
        PriceEntry tonhal = withCard.entries().stream().filter(e -> e.itemId().equals("promo:e")).findFirst().orElseThrow();
        Deal d = deals.dealFor(tonhal);
        check(d != null && d.type() == DealType.PROMO, "akció-jel");
        check(d.reason().contains("Hűségkártyás"), "kártyás akció indoklása: " + d.reason());
        check(tonhal.productName().contains("Lidl Plus"), "a név jelzi a kártyát: " + tonhal.productName());
        RecipeRecommender rec = new RecipeRecommender(CATALOG, withCard.entries(), deals, List.of("lidl"), withCard.forcedIngredient());
        Suggestion ts = rec.suggestion("tonhalas-teszta", "lidl", 0);
        check(ts.items().stream().anyMatch(pi -> pi.entry() != null && pi.entry().itemId().equals("promo:e")),
                "a tonhalas tészta az akciós tonhalat használja");
    }

    // ---------- teljes rendszer ----------

    // ---------- többnyelvűség ----------

    static void testLangNegotiation() {
        eq("en", Lang.normalize("en-GB"), "en-GB → en");
        eq("hu", Lang.normalize("HU"), "nagybetűs");
        eq("hu", Lang.normalize("xx"), "ismeretlen → magyar");
        eq("hu", Lang.fromAcceptLanguage(null), "fejléc nélkül magyar");
        eq("hu", Lang.fromAcceptLanguage("hu-HU,hu;q=0.9,en;q=0.8"), "magyar böngésző");
        eq("de", Lang.fromAcceptLanguage("de-DE,de;q=0.9,en;q=0.7"), "német böngésző → német");
        eq("en", Lang.fromAcceptLanguage("it-IT,it;q=0.9,en;q=0.7"), "olasz böngésző → az első támogatott (angol)");
        eq("en", Lang.fromAcceptLanguage("fr-FR"), "nem támogatott nyelv → angol");
        eq("hr", Lang.fromAcceptLanguage("hr-HR,hr;q=0.9,en;q=0.8"), "horvát böngésző");
    }

    static void testEveryLanguageCoversEveryRecipeAndIngredient() {
        check(Translator.forLang("hu") == null, "magyarra nincs fordító");
        for (String lang : List.of("en", "hr", "de")) {
            Translator tr = Translator.forLang(lang);
            check(tr != null, "van fordító: " + lang);
            for (var r : CATALOG.recipes()) {
                check(tr.knows(r.name()), lang + " receptnév: " + r.name());
                for (String step : r.steps()) check(tr.knows(step), lang + " lépés: " + r.id() + ": " + step);
            }
            for (var p : CATALOG.products().values()) check(tr.knows(p.name()), lang + " alapanyagnév: " + p.name());
        }
    }

    static void testCroatianGeneratedTexts() {
        Translator hr = Translator.forLang("hr");
        eq("1 glavica", hr.text("1 fej", true), "egy");
        eq("3 glavice", hr.text("3 fej", true), "2–4: néhány");
        eq("5 glavica", hr.text("5 fej", true), "5+: sok");
        eq("21 češanj", hr.text("21 gerezd", true), "21: egy");
        eq("12 češnjeva", hr.text("12 gerezd", true), "12: sok");
        eq("94 g", hr.text("9,4 dkg", true), "dkg → g");
        eq("1,5 dl", hr.text("1,5 dl", true), "a dl marad (Horvátországban is használják)");
        eq("12,49\u00a0€/kom", hr.text("12,49\u00a0€/db", true), "európai számformátum marad");
        eq("oko 425 g (≈ 4 kom)", hr.text("kb. 425 g (≈ 4 db)", true), "mérlegelős");
        eq("Crvena paprika, sol, papar", hr.text("Pirospaprika, só, bors", false), "fűszerlista");
        eq("Srijeda", hr.text("Szerda", true), "napnév");
    }

    static void testGermanGeneratedTexts() {
        Translator de = Translator.forLang("de");
        eq("2 Zehen", de.text("2 gerezd", true), "többes szám");
        eq("1 Dose", de.text("1 doboz", true), "egyes szám");
        eq("150 ml", de.text("1,5 dl", true), "dl → ml");
        eq("ca. 425 g (≈ 4 Stk.)", de.text("kb. 425 g (≈ 4 db)", true), "mérlegelős");
        eq("37% günstiger als bei den anderen 5 Ketten (1\u00a0079\u00a0Ft)", de.text("37%-kal olcsóbb, mint a többi 5 láncban (1\u00a0079\u00a0Ft)", true), "indoklás");
        eq("tej", de.searchToHungarian("milch"), "keresés németül");
        eq("Mittwoch", de.text("Szerda", true), "napnév");
    }

    static void testSearchAcrossLanguages() {
        eq("tej", Translator.forLang("en").searchToHungarian("milk"), "angol → magyar");
        eq("wc papir", Translator.forLang("en").searchToHungarian("toilet paper"), "kifejezés egyben");
        eq("tej", Translator.forLang("hr").searchToHungarian("mlijeko"), "horvát → magyar");
        eq("csirkemellfile", Translator.forLang("en").searchToHungarian("chicken breast fillet"), "alapanyagnév visszafelé");
        eq("trappista", Translator.forLang("en").searchToHungarian("trappista"), "ismeretlen szó marad");
    }

    static void testEnglishGeneratedTexts() {
        Translator en = Translator.forLang("en");
        eq("94 g", en.text("9,4 dkg", true), "dkg → g");
        eq("150 ml", en.text("1,5 dl", true), "dl → ml");
        eq("1.2 kg", en.text("120 dkg", true), "nagy dkg → kg");
        eq("2 heads", en.text("2 fej", true), "többes szám");
        eq("1 clove", en.text("1 gerezd", true), "egyes szám");
        eq("½ jar", en.text("fél üveg", true), "fél");
        eq("approx. 425 g (≈ 4 pcs)", en.text("kb. 425 g (≈ 4 db)", true), "mérlegelős");
        eq("1,099\u00a0Ft / 500 g · 2,198\u00a0Ft/kg", en.text("1\u00a0099\u00a0Ft / 500 g · 2\u00a0198\u00a0Ft/kg", true), "forint");
        eq("€12.49/pc", en.text("12,49\u00a0€/db", true), "euró elöl, tizedespont");
        eq("37% cheaper than at the other 5 chains (1,079\u00a0Ft)", en.text("37%-kal olcsóbb, mint a többi 5 láncban (1\u00a0079\u00a0Ft)", true), "akció-indoklás");
        eq("Paprika, salt, pepper", en.text("Pirospaprika, só, bors", false), "fűszerlista szavanként");
        eq("Sajátmárkás tej 1,5 l", en.text("Sajátmárkás tej 1,5 l", false), "terméknév változatlan");
        // a fa: azonosítók maradnak, a label mellé az eredeti kulcs kerül
        Map<String, Object> in = new HashMap<>(Map.of("id", "voroshagyma", "label", "Vöröshagyma", "tags", List.of("vega"), "amount", "2 fej"));
        Map<String, Object> out = Json.obj(en.apply(in));
        eq("voroshagyma", out.get("id"), "id változatlan");
        eq("Onions", out.get("label"), "label lefordítva");
        eq("Vöröshagyma", out.get("labelKey"), "a pipák kulcsa nyelvfüggetlen");
        eq(List.of("vega"), out.get("tags"), "szűrőkulcs változatlan");
        eq("Vöröshagyma", in.get("label"), "az eredeti objektum nem változik");
    }

    static void testJsonRejectsHostileInput() {
        String deep = "[".repeat(100_000) + "]".repeat(100_000);
        try {
            Json.parse(deep);
            check(false, "mély JSON-t elutasít");
        } catch (IllegalArgumentException e) {
            check(e.getMessage().contains("mélyen"), "mély JSON → IllegalArgumentException (nem StackOverflowError)");
        }
        String ok = "[".repeat(60) + "]".repeat(60);
        Json.parse(ok); // 60 szint még belefér
        for (String bad : List.of("\"lezáratlan", "{\"a\":\"x\\", "\"\\u12\"", "1e999", "[1,2", "{\"a\" 1}")) {
            try {
                Json.parse(bad);
                check(false, "hibás JSON elfogadva: " + bad);
            } catch (IllegalArgumentException e) {
                // jó: 400-as hiba lesz belőle, nem 500
            }
        }
    }

    static void testProductionHardening() throws Exception {
        Path dir = Files.createTempDirectory("sporolos");
        PriceRepository repo = new PriceRepository(CATALOG, List.of(new DemoPriceProvider(CATALOG, LocalDate.now())),
                new PriceHistory(new FileStorage(dir), 28), new PromoStore(dir.resolve("akciok"), List.of()), Duration.ofHours(1));
        WebServer server = new WebServer(repo, new FileStorage(dir), null, "titkos-jelszo").rateLimit(0, true);
        int port = server.start("127.0.0.1", 0);
        try {
            HttpClient c = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + port;
            // 1. mély / hibás / túl nagy törzs: 400, a szerver válaszol és tovább működik
            String deep = send(c, "POST", base + "/api/profile", "[".repeat(200_000), 400);
            check(deep.contains("error"), "mély JSON → 400");
            send(c, "POST", base + "/api/profile", "{\"people\":\"lezáratlan", 400);
            send(c, "POST", base + "/api/profile", "{\"x\":\"" + "a".repeat(70_000) + "\"}", 400);
            Map<String, Object> prof = Json.obj(Json.parse(send(c, "POST", base + "/api/profile", "{\"onboarded\":true}", 200)));
            check(prof.get("id") != null, "utána is működik");

            // 2. biztonsági fejlécek (statikus és API), API nem gyorsítótárazható
            HttpResponse<String> page = c.send(HttpRequest.newBuilder(URI.create(base + "/")).build(), HttpResponse.BodyHandlers.ofString());
            var h = page.headers();
            check(h.firstValue("Content-Security-Policy").orElse("").contains("frame-ancestors 'none'"), "CSP");
            eq("DENY", h.firstValue("X-Frame-Options").orElse(""), "X-Frame-Options");
            eq("nosniff", h.firstValue("X-Content-Type-Options").orElse(""), "nosniff");
            eq("same-origin", h.firstValue("Referrer-Policy").orElse(""), "Referrer-Policy");
            check(h.firstValue("X-Request-Id").orElse("").length() >= 6, "kérésazonosító");
            check(h.firstValue("Strict-Transport-Security").isEmpty(), "HTTP-n nincs HSTS");
            HttpResponse<String> api = c.send(HttpRequest.newBuilder(URI.create(base + "/api/markets"))
                    .header("X-Forwarded-Proto", "https").build(), HttpResponse.BodyHandlers.ofString());
            eq("no-store", api.headers().firstValue("Cache-Control").orElse(""), "API: no-store");
            check(api.headers().firstValue("Strict-Transport-Security").isPresent(), "HTTPS-proxy mögött HSTS");

            // 3. véletlen URL-ek nem töltik a memóriát; a statikus szöveg előre tömörítve jön
            for (int i = 0; i < 300; i++) get(c, base + "/nincs-ilyen-" + i + ".js", 404);
            check(WebServer.staticCacheSize() < 100, "a hiányzó útvonalak nincsenek a gyorsítótárban: " + WebServer.staticCacheSize());
            HttpResponse<byte[]> gz = c.send(HttpRequest.newBuilder(URI.create(base + "/app.js")).header("Accept-Encoding", "gzip").build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            eq("gzip", gz.headers().firstValue("Content-Encoding").orElse(""), "gzip");
            String js = new String(new java.util.zip.GZIPInputStream(new ByteArrayInputStream(gz.body())).readAllBytes(), StandardCharsets.UTF_8);
            check(js.contains("function ft("), "a tömörített app.js kibontva helyes");

            // 4. admin-belépés: IP-címenként percenként legfeljebb 10 próbálkozás (párhuzamosan is)
            List<java.util.concurrent.CompletableFuture<HttpResponse<String>>> tries = new ArrayList<>();
            for (int i = 0; i < 14; i++) {
                tries.add(c.sendAsync(HttpRequest.newBuilder(URI.create(base + "/api/admin/login"))
                        .POST(HttpRequest.BodyPublishers.ofString("{\"password\":\"rossz" + i + "\"}")).build(), HttpResponse.BodyHandlers.ofString()));
            }
            long limited = tries.stream().map(java.util.concurrent.CompletableFuture::join).filter(r -> r.statusCode() == 429).count();
            check(limited >= 4, "a 10. utáni próbálkozások 429-et kapnak: " + limited);

            // 5. metrikák
            check(get(c, base + "/metrics", 200).contains("sporolos_prices_loaded{market=\"hu\"} 0"), "még nincs ár betöltve");
            get(c, base + "/api/status", 200);
            String metrics = get(c, base + "/metrics", 200);
            check(metrics.contains("sporolos_http_requests_total{route=\"/api/profile\",status=\"2xx\"}"), "kérésszámláló");
            check(metrics.contains("sporolos_http_requests_total{route=\"/api/profile\",status=\"4xx\"}"), "hibaszámláló");
            check(metrics.contains("sporolos_http_rejected_total{reason=\"login_limit\"}"), "elutasítás oka");
            check(metrics.contains("sporolos_prices_loaded{market=\"hu\"} 1"), "árak betöltve");
            check(!metrics.contains("nincs-ilyen"), "véletlen URL nem lesz metrika-címke");
        } finally {
            server.stop();
        }
    }

    static void testBackpressure() throws Exception {
        Path dir = Files.createTempDirectory("sporolos");
        PriceRepository repo = new PriceRepository(CATALOG, List.of(new DemoPriceProvider(CATALOG, LocalDate.now())),
                new PriceHistory(new FileStorage(dir), 28), new PromoStore(dir.resolve("akciok"), List.of()), Duration.ofHours(1));
        WebServer server = new WebServer(repo, new FileStorage(dir)).maxConcurrent(1).queueWait(300);
        int port = server.start("127.0.0.1", 0);
        try {
            HttpClient c = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + port;
            // az egyetlen helyet egy lassan feltöltött kéréstörzs foglalja (a szerver a beolvasás alatt dolgozik rajta)
            var slow = new java.util.concurrent.Flow.Publisher<java.nio.ByteBuffer>() {
                @Override
                public void subscribe(java.util.concurrent.Flow.Subscriber<? super java.nio.ByteBuffer> s) {
                    s.onSubscribe(new java.util.concurrent.Flow.Subscription() {
                        int sent;
                        @Override public void request(long n) {
                            new Thread(() -> {
                                try {
                                    Thread.sleep(sent == 0 ? 0 : 900);
                                } catch (InterruptedException ignored) { }
                                if (sent++ == 0) s.onNext(java.nio.ByteBuffer.wrap("{\"onboarded\":".getBytes(StandardCharsets.UTF_8)));
                                else { s.onNext(java.nio.ByteBuffer.wrap("true}".getBytes(StandardCharsets.UTF_8))); s.onComplete(); }
                            }).start();
                        }
                        @Override public void cancel() { }
                    });
                }
            };
            var first = c.sendAsync(HttpRequest.newBuilder(URI.create(base + "/api/profile"))
                    .POST(HttpRequest.BodyPublishers.fromPublisher(slow)).build(), HttpResponse.BodyHandlers.ofString());
            Thread.sleep(250);
            HttpResponse<String> second = c.send(HttpRequest.newBuilder(URI.create(base + "/api/markets")).build(), HttpResponse.BodyHandlers.ofString());
            eq(503, second.statusCode(), "foglalt szervernél 503");
            check(second.headers().firstValue("Retry-After").isPresent(), "Retry-After");
            eq(200, first.join().statusCode(), "a lassú kérés is befejeződik");
            get(c, base + "/api/markets", 200); // a hely felszabadult
            get(c, base + "/app.js", 200);      // a statikus fájlokat nem korlátozza
        } finally {
            server.stop();
        }
    }

    static void testForwardedForUsesLastHop() {
        eq("203.0.113.9", WebServer.forwardedClient(List.of("1.2.3.4, 203.0.113.9")), "a kliens által küldött első elem nem számít");
        eq("203.0.113.9", WebServer.forwardedClient(List.of("203.0.113.9")), "egy elem");
        eq("198.51.100.7", WebServer.forwardedClient(List.of("hamis", "198.51.100.7 , ")), "több fejléc, záró vessző");
        eq(null, WebServer.forwardedClient(List.of()), "nincs fejléc");
    }

    static void testLogRedaction() {
        eq("/api/week?id=***&lang=en", WebServer.redactForTest("/api/week?id=abcdefghjk23456789mn&lang=en"), "profil-azonosító kitakarva");
        eq("/api/extras?id=***&n=3", WebServer.redactForTest("/api/extras?id=x&n=3"), "több paraméter");
        eq("/api/check?lang=hu&id=***", WebServer.redactForTest("/api/check?lang=hu&id=abc"), "nem az első paraméter");
        eq("/api/recipe?id=paprikas-csirke&store=aldi", WebServer.redactForTest("/api/recipe?id=paprikas-csirke&store=aldi"), "a receptazonosító nem titok");
        eq("/api/status", WebServer.redactForTest("/api/status"), "paraméter nélkül");
        eq("/api/search?q=tej&lang=hu", WebServer.redactForTest("/api/search?q=tej&lang=hu"), "más paraméter marad");
    }

    static void testRateLimit() throws Exception {
        Path dir = Files.createTempDirectory("sporolos");
        PriceRepository repo = new PriceRepository(CATALOG, List.of(new DemoPriceProvider(CATALOG, LocalDate.now())),
                new PriceHistory(new FileStorage(dir), 28), new PromoStore(dir.resolve("akciok"), List.of()), Duration.ofHours(1));
        WebServer server = new WebServer(repo, new FileStorage(dir)).rateLimit(5, false);
        int port = server.start("127.0.0.1", 0);
        try {
            HttpClient c = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + port;
            int ok = 0, limited = 0;
            String retry = null, body = null;
            for (int i = 0; i < 8; i++) {
                HttpResponse<String> r = c.send(HttpRequest.newBuilder(URI.create(base + "/api/markets?lang=en")).build(),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (r.statusCode() == 200) ok++;
                if (r.statusCode() == 429) { limited++; retry = r.headers().firstValue("Retry-After").orElse(null); body = r.body(); }
            }
            // percváltáskor egy újabb ablak indulhat: legalább 5 átmegy, és a többi korlátozott
            check(ok >= 5 && limited >= 1 && ok + limited == 8, "5 kérés/perc után 429 (" + ok + " ok, " + limited + " korlátozva)");
            check(retry != null && Integer.parseInt(retry) >= 1, "Retry-After fejléc");
            check(body.contains("Too many requests"), "angol üzenet");
            get(c, base + "/app.js", 200); // a statikus fájlokat nem korlátozza
            get(c, base + "/healthz", 200);
        } finally {
            server.stop();
        }
    }

    static void testWebServerLanguageAndCaching() throws Exception {
        Path dir = Files.createTempDirectory("sporolos");
        PriceRepository repo = new PriceRepository(CATALOG, List.of(new DemoPriceProvider(CATALOG, LocalDate.now())),
                new PriceHistory(new FileStorage(dir), 28), new PromoStore(dir.resolve("akciok"), List.of()), Duration.ofHours(1));
        WebServer server = new WebServer(repo, new FileStorage(dir));
        int port = server.start("127.0.0.1", 0);
        try {
            HttpClient c = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + port;
            Map<String, Object> hu = Json.obj(Json.parse(get(c, base + "/api/recipe?id=paprikas-csirke&store=lidl", 200)));
            eq("Paprikás csirke nokedlivel", Json.obj(hu.get("recipe")).get("name"), "alapból magyar");
            Map<String, Object> en = Json.obj(Json.parse(get(c, base + "/api/recipe?id=paprikas-csirke&store=lidl&lang=en", 200)));
            eq("Chicken paprikash with nokedli dumplings", Json.obj(en.get("recipe")).get("name"), "?lang=en → angol");
            HttpResponse<String> r = c.send(HttpRequest.newBuilder(URI.create(base + "/api/recipe?id=nincsilyen&store=lidl"))
                    .header("Accept-Language", "en-US,en;q=0.9").build(), HttpResponse.BodyHandlers.ofString());
            eq(404, r.statusCode(), "ismeretlen recept");
            check(r.body().contains("No such recipe: nincsilyen"), "angol böngészőnek angol hibaüzenet (Accept-Language)");
            // a magyar válasz nem szennyeződik az angol kérés után (a gyorsítótárazott elemzés magyar marad)
            String deals = get(c, base + "/api/deals?store=lidl", 200);
            check(!deals.contains("cheaper"), "a magyar akció-indoklás magyar maradt");

            Map<String, Object> health = Json.obj(Json.parse(get(c, base + "/healthz", 200)));
            eq(true, health.get("ok"), "állapot-végpont");
            eq(List.of("hu"), health.get("markets"), "piacok");
            check(health.containsKey("version") && health.containsKey("uptimeSeconds"), "verzió és üzemidő");
            HttpResponse<String> js = c.send(HttpRequest.newBuilder(URI.create(base + "/app.js")).build(), HttpResponse.BodyHandlers.ofString());
            String etag = js.headers().firstValue("ETag").orElse("");
            check(!etag.isEmpty(), "ETag a statikus fájlon");
            HttpResponse<String> again = c.send(HttpRequest.newBuilder(URI.create(base + "/app.js")).header("If-None-Match", etag).build(),
                    HttpResponse.BodyHandlers.ofString());
            eq(304, again.statusCode(), "változatlan fájlra 304");
            get(c, base + "/sw.js", 200);
            List<Object> milk = Json.arr(Json.parse(get(c, base + "/api/search?q=milk&lang=en", 200)));
            check(!milk.isEmpty(), "angolul is talál tejet („milk”)");
            Map<String, Object> hrRec = Json.obj(Json.parse(get(c, base + "/api/recipe?id=paprikas-csirke&store=lidl&lang=hr", 200)));
            eq("Piletina u paprika umaku s noklicama", Json.obj(hrRec.get("recipe")).get("name"), "?lang=hr → horvát");
            get(c, base + "/i18n.js", 200);
            for (String l : List.of("en", "hr", "de")) get(c, base + "/lang/" + l + ".js", 200);
        } finally {
            server.stop();
        }
    }

    static void testWebServerEndToEnd() throws Exception {
        Path dir = Files.createTempDirectory("sporolos");
        PriceRepository repo = new PriceRepository(CATALOG,
                List.of(new ArfigyeloExcelProvider(CATALOG, null, dir.resolve("nincs.xlsx"), null), // nem létezik → demóra vált
                        new DemoPriceProvider(CATALOG, LocalDate.now())),
                new PriceHistory(new FileStorage(dir), 28), new PromoStore(dir.resolve("akciok"), List.of()),
                Duration.ofHours(1));
        FileStorage storage = new FileStorage(dir);
        WebServer server = new WebServer(repo, storage);
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

            String firstId = String.valueOf(Json.obj(first.get("recipe")).get("id"));
            Map<String, Object> rec = Json.obj(Json.parse(get(c, base + "/api/recipe?id=" + firstId + "&store=lidl&servings=8", 200)));
            eq(8L, rec.get("servings"), "8 adagra számolva");
            eq(6, Json.arr(rec.get("stores")).size(), "ár mind a 6 láncban");
            check(Json.num(rec.get("checkoutTotal"), 0) > 0, "pénztári végösszeg");
            get(c, base + "/api/recipe?id=nincsilyen&store=lidl", 404);

            Object itemId = null;
            for (Object o : Json.arr(first.get("ingredients"))) {
                Object it = Json.obj(o).get("item");
                if (it != null) { itemId = Json.obj(it).get("id"); break; }
            }
            check(itemId != null, "van bolti termék a receptben");
            Map<String, Object> item = Json.obj(Json.parse(get(c, base + "/api/item?id=" + itemId, 200)));
            check(!Json.arr(item.get("offers")).isEmpty(), "a termék ajánlatai");
            get(c, base + "/api/item?id=nincsilyen", 404);

            List<Object> hits = Json.arr(Json.parse(get(c, base + "/api/search?q=tojas", 200)));
            check(!hits.isEmpty(), "a keresés talál tojást");
            get(c, base + "/api/search?q=x", 400);
            get(c, base + "/api/refresh", 405);

            // boltszűrő: a recept összehasonlítása csak a választott boltokat tartalmazza
            Map<String, Object> filtered = Json.obj(Json.parse(get(c, base + "/api/recipe?id=" + firstId + "&store=lidl&stores=lidl,spar", 200)));
            eq(2, Json.arr(filtered.get("stores")).size(), "csak a Lidl és a Spar");
            get(c, base + "/api/suggestions?store=lidl&bulk=1", 200);

            // akciók: felvitel, lista, törlés
            List<Object> ings = Json.arr(Json.parse(get(c, base + "/api/ingredients", 200)));
            check(ings.size() > 50, "alapanyag-lista");
            String body = "{\"store\":\"lidl\",\"name\":\"Akciós csirkemell\",\"ingredient\":\"csirkemell\",\"price\":999,\"unit\":\"kg\",\"quantity\":1}";
            HttpResponse<String> add = c.send(HttpRequest.newBuilder(URI.create(base + "/api/promos"))
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
            eq(200, add.statusCode(), "akció felvétele: " + add.body());
            List<Object> promos = Json.arr(Json.obj(Json.parse(add.body())).get("promos"));
            eq(1, promos.size(), "egy akció");
            Map<String, Object> pr = Json.obj(promos.get(0));
            eq("ACTIVE", pr.get("status"), "beépítve");
            List<Object> dl = Json.arr(Json.parse(get(c, base + "/api/deals?store=lidl&limit=200", 200)));
            check(dl.stream().anyMatch(o -> "PROMO".equals(Json.obj(o).get("type"))), "megjelenik a jó árak közt");
            HttpResponse<String> csvImport = c.send(HttpRequest.newBuilder(URI.create(base + "/api/promos"))
                    .POST(HttpRequest.BodyPublishers.ofString("{\"text\":\"bolt;termek;ar;egyseg;kiszereles;alapanyag\\nSpar;Tojás 10 db;399;db;10;tojas\"}")).build(),
                    HttpResponse.BodyHandlers.ofString());
            eq(200, csvImport.statusCode(), "CSV import: " + csvImport.body());
            eq(1L, Json.obj(Json.parse(csvImport.body())).get("added"), "egy sor importálva");
            HttpResponse<String> del = c.send(HttpRequest.newBuilder(URI.create(base + "/api/promos?id=" + pr.get("id")))
                    .DELETE().build(), HttpResponse.BodyHandlers.ofString());
            eq(200, del.statusCode(), "törlés");
            eq(1, Json.arr(Json.obj(Json.parse(del.body())).get("promos")).size(), "maradt a Spar-akció");
            HttpResponse<String> bad = c.send(HttpRequest.newBuilder(URI.create(base + "/api/promos"))
                    .POST(HttpRequest.BodyPublishers.ofString("{\"store\":\"lidl\",\"name\":\"x\"}")).build(),
                    HttpResponse.BodyHandlers.ofString());
            eq(400, bad.statusCode(), "ár nélkül hiba");
            get(c, base + "/api/deals?store=nincsilyen", 400);
            get(c, base + "/api/ismeretlen", 404);
            check(get(c, base + "/", 200).contains("Spórolós Konyha"), "főoldal");
            check(get(c, base + "/app.js", 200).contains("loadWeek"), "app.js");

            // heti terv, receptlista, képek
            Map<String, Object> plan = Json.obj(Json.parse(get(c, base + "/api/plan?store=lidl&people=3&dinners=4&budget=20000&diets=vega", 200)));
            List<Object> days = Json.arr(plan.get("days"));
            eq(4, days.size(), "4 vacsora");
            for (Object d : days) {
                Map<String, Object> r = Json.obj(Json.obj(Json.obj(d).get("suggestion")).get("recipe"));
                check(Json.arr(r.get("diets")).contains("vega"), "vega terv: " + r.get("id"));
            }
            check(!Json.arr(plan.get("aisles")).isEmpty(), "van bevásárlólista");
            String keepId = String.valueOf(Json.obj(Json.obj(Json.obj(days.get(0)).get("suggestion")).get("recipe")).get("id"));
            Map<String, Object> swapped = Json.obj(Json.parse(get(c, base + "/api/plan?store=lidl&people=3&dinners=2&keep=" + keepId + ",_", 200)));
            eq(keepId, String.valueOf(Json.obj(Json.obj(Json.obj(Json.arr(swapped.get("days")).get(0)).get("suggestion")).get("recipe")).get("id")), "a megtartott recept marad");
            get(c, base + "/api/plan?store=nincsilyen", 400);
            List<Object> all = Json.arr(Json.parse(get(c, base + "/api/recipes?store=lidl", 200)));
            eq(CATALOG.recipes().size(), all.size(), "minden recept a listában");
            List<Object> gf = Json.arr(Json.parse(get(c, base + "/api/recipes?store=lidl&diets=gluténmentes", 200)));
            check(gf.size() > 10 && gf.size() < all.size(), "gluténmentes szűrő: " + gf.size());
            // háztartás-profil: létrehozás, hét, csere, kipipálás, értékelés
            String prof = post(c, base + "/api/profile", "{\"store\":\"lidl\",\"people\":3,\"dinners\":4,\"budget\":20000,\"diets\":[\"vega\",\"rossz\"],\"onboarded\":true}", 200);
            Map<String, Object> pm = Json.obj(Json.parse(prof));
            String pid = String.valueOf(pm.get("id"));
            eq(List.of("vega"), Json.obj(pm.get("settings")).get("diets"), "ismeretlen diéta kiszűrve");
            Map<String, Object> wk = Json.obj(Json.parse(get(c, base + "/api/week?id=" + pid, 200)));
            List<Object> wdays = Json.arr(wk.get("days"));
            eq(4, wdays.size(), "4 vacsora a profil szerint");
            List<String> ids = recipeIds(wk);
            eq(ids, recipeIds(Json.obj(Json.parse(get(c, base + "/api/week?id=" + pid, 200)))), "a mentett hét megmarad");
            List<String> ids2 = recipeIds(Json.obj(Json.parse(get(c, base + "/api/week?id=" + pid + "&action=swap&day=1", 200))));
            eq(ids.get(0), ids2.get(0), "csere: a többi nap marad");
            check(!ids.get(1).equals(ids2.get(1)), "csere: a 2. nap más");
            Map<String, Object> set = Json.obj(Json.parse(get(c, base + "/api/week?id=" + pid + "&action=set&day=0&recipe=gombas-rizotto", 200)));
            eq("gombas-rizotto", recipeIds(set).get(0), "konkrét recept a hétre");
            String key = String.valueOf(set.get("listKey"));
            post(c, base + "/api/check?id=" + pid, "{\"key\":\"" + key + "\",\"item\":\"Rizs\",\"on\":true}", 200);
            eq(List.of("Rizs"), Json.obj(Json.parse(get(c, base + "/api/week?id=" + pid, 200))).get("checked"), "kipipálva marad");
            Map<String, Object> rated = Json.obj(Json.parse(post(c, base + "/api/rate?id=" + pid, "{\"recipe\":\"lecso\",\"liked\":false}", 200)));
            eq(List.of("lecso"), rated.get("disliked"), "nem kért recept");
            post(c, base + "/api/rate?id=" + pid, "{\"recipe\":\"nincsilyen\",\"liked\":true}", 400);
            get(c, base + "/api/profile?id=nincsilyen99", 404);
            get(c, base + "/api/week?id=../../etc", 400);
            send(c, "PUT", base + "/api/profile?id=" + pid, "{\"store\":\"aldi\",\"people\":2,\"dinners\":3,\"onboarded\":true}", 200);
            Map<String, Object> wk3 = Json.obj(Json.parse(get(c, base + "/api/week?id=" + pid, 200)));
            eq("aldi", String.valueOf(wk3.get("store")), "boltváltás után új hét");
            eq(3, Json.arr(wk3.get("days")).size(), "3 vacsora");
            // automatikus bolt: a hét abban a boltban, ahol a legolcsóbb; összevetés a többivel
            send(c, "PUT", base + "/api/profile?id=" + pid, "{\"people\":2,\"dinners\":5,\"onboarded\":true,\"split\":true}", 200);
            Map<String, Object> auto = Json.obj(Json.parse(get(c, base + "/api/week?id=" + pid, 200)));
            eq(Boolean.TRUE, auto.get("autoStore"), "automatikus bolt");
            List<Object> cmp = Json.arr(auto.get("compare"));
            check(cmp.size() >= 5, "összevetés a boltokkal");
            eq(auto.get("store"), Json.obj(cmp.get(0)).get("store"), "a legolcsóbb bolt van elöl, és azt választotta");
            check(Math.abs(Json.num(Json.obj(cmp.get(0)).get("checkout"), 0) - Json.num(auto.get("checkoutTotal"), 0)) < 1, "az összevetés és a terv összege egyezik");
            // maradék: kedd a hétfő maradéka, a hétfőn dupla adag
            Map<String, Object> rep = Json.obj(Json.parse(get(c, base + "/api/week?id=" + pid + "&action=repeat&day=0", 200)));
            List<Object> rdays = Json.arr(rep.get("days"));
            eq(0.0, Json.num(Json.obj(rdays.get(1)).get("leftoverOf"), -1), "kedd = hétfő maradéka");
            eq(4.0, Json.num(Json.obj(Json.obj(rdays.get(0)).get("suggestion")).get("servings"), 0), "hétfőn 2 napra főz");
            eq(0.0, Json.num(Json.obj(rdays.get(1)).get("shareOfList"), -1), "a maradéknapra nem kell vásárolni");
            Map<String, Object> fresh = Json.obj(Json.parse(get(c, base + "/api/week?id=" + pid + "&action=fresh&day=1", 200)));
            eq(-1.0, Json.num(Json.obj(Json.arr(fresh.get("days")).get(1)).get("leftoverOf"), 0), "kedden újra főzés");
            get(c, base + "/api/week?id=" + pid + "&action=repeat&day=4", 400);
            // kétnaponta főzés
            send(c, "PUT", base + "/api/profile?id=" + pid, "{\"people\":2,\"dinners\":5,\"onboarded\":true,\"cookMode\":\"every2\"}", 200);
            List<Object> e2 = Json.arr(Json.obj(Json.parse(get(c, base + "/api/week?id=" + pid, 200))).get("days"));
            long cooks = e2.stream().filter(d -> Json.num(Json.obj(d).get("leftoverOf"), -1) < 0).count();
            eq(3L, cooks, "5 vacsora, 3 főzés");
            // a recept mennyiségei az adagszámhoz igazodnak
            Map<String, Object> bk = Json.obj(Json.parse(get(c, base + "/api/recipe?id=bundas-kenyer&store=lidl&servings=2", 200)));
            var tomato = Json.arr(bk.get("ingredients")).stream().map(Json::obj).filter(i -> "Paradicsom".equals(i.get("label"))).findFirst().orElseThrow();
            eq("1 db", tomato.get("amount"), "4 adagra 2 db → 2 adagra 1 db");
            get(c, base + "/img/recipe/lecso", 404);
            // akciók a boltjaidban, saját tételek a listán
            List<Object> offers = Json.arr(Json.parse(get(c, base + "/api/offers?limit=20", 200)));
            check(!offers.isEmpty(), "van kiemelt ár");
            // a demó árak naponta mások (nem minden lánc tart mindent): a hét boltjában keresünk
            String weekStore = Json.str(Json.obj(Json.parse(get(c, base + "/api/week?id=" + pid, 200))).get("store"));
            Map<String, Object> hit = null;
            for (String q : List.of("tojas", "tej", "liszt", "rizs", "vaj")) {
                List<Object> found = Json.arr(Json.parse(get(c, base + "/api/search?q=" + q + "&limit=1&store=" + weekStore, 200)));
                if (!found.isEmpty()) { hit = Json.obj(found.get(0)); break; }
            }
            check(hit != null, "van keresési találat a hét boltjában");
            post(c, base + "/api/extras?id=" + pid, "{\"name\":\"Tojás\",\"itemId\":\"" + hit.get("id") + "\"}", 200);
            post(c, base + "/api/extras?id=" + pid, "{\"name\":\"Mosogatószer\"}", 200);
            post(c, base + "/api/extras?id=" + pid, "{\"name\":\"\"}", 400);
            Map<String, Object> wx = Json.obj(Json.parse(get(c, base + "/api/week?id=" + pid, 200)));
            List<Object> ex = Json.arr(wx.get("extras"));
            eq(2, ex.size(), "két saját tétel");
            check(Json.obj(ex.get(0)).get("price") != null, "a választott terméknek van ára (ugyanaz vagy hasonló termék)");
            eq(null, Json.obj(ex.get(1)).get("price"), "a szabad szöveges tételnek nincs ára");
            check(Json.num(wx.get("extrasTotal"), 0) > 0, "a saját tételek összege");
            get(c, base + "/api/extras?id=" + pid, 200);
            send(c, "DELETE", base + "/api/extras?id=" + pid + "&n=1", "", 200);
            eq(1, Json.arr(Json.obj(Json.parse(get(c, base + "/api/extras?id=" + pid, 200))).get("extras")).size(), "törölve");
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
            var plan = new WeeklyPlanner(CATALOG, st.recommender()).plan(store.id(),
                    req(2, 5, 15000, Set.of(), Set.of(), List.of(), Set.of(), 1));
            eq(5, plan.days().size(), store.id() + ": teljes heti terv");
            check(!plan.overBudget(), store.id() + ": belefér 15 000 Ft-ba (" + plan.checkoutTotal() + ")");
        }
        int priced = 0;
        for (var r : CATALOG.recipes()) if (st.recommender().suggestion(r.id(), "lidl", 0, null, false) != null) priced++;
        eq(CATALOG.recipes().size(), priced, "minden recept árazható a Lidlben");
    }

    // ---------- receptadatok, képek, heti terv ----------

    static void testRecipeMetaDiets() {
        for (var r : CATALOG.recipes()) {
            RecipeMeta m = CATALOG.meta(r.id());
            check(m != null, "van meta: " + r.id());
            check(m.kcal() > 50 && m.kcal() < 1600, r.id() + ": életszerű kalória " + m.kcal());
            check(m.needsOven() || m.needsStove() || r.steps().size() <= 3, r.id() + ": valamivel készül");
            if (r.tags().contains("vega")) check(m.diets().contains(RecipeMeta.VEGA), r.id() + ": vega címke ↔ húsmentes");
            if (r.tags().contains("húsos") || r.tags().contains("hal")) check(!m.diets().contains(RecipeMeta.VEGA), r.id() + ": húsos nem vega");
        }
        RecipeMeta paprikas = CATALOG.meta("paprikas-csirke");
        check(paprikas.diets().contains(RecipeMeta.GLUTEN_FREE) == false, "a nokedli nem gluténmentes");
        check(paprikas.cookableWith(Set.of(RecipeMeta.STOVE)), "tűzhelyen elkészül");
        check(!paprikas.cookableWith(Set.of(RecipeMeta.AIRFRYER)), "airfryerrel nem");
        check(CATALOG.recipes().size() >= 150, "legalább 150 recept: " + CATALOG.recipes().size());
    }

    static void testAmountScaling() {
        eq("1 db", Amounts.scale("2 db", 0.5), "fél adag");
        eq("4 szelet", Amounts.scale("8 szelet", 0.5), "szelet");
        eq("0,5 dl", Amounts.scale("1 dl", 0.5), "dl");
        eq("75 dkg", Amounts.scale("50 dkg", 1.5), "dkg");
        eq("1,5 kg", Amounts.scale("75 dkg", 2), "dkg → kg");
        eq("1,5 l", Amounts.scale("7,5 dl", 2), "dl → l");
        eq("fél fej", Amounts.scale("1 fej", 0.5), "fél fej");
        eq("1 vekni", Amounts.scale("fél vekni", 2), "fél → 1");
        eq("3 ek", Amounts.scale("másfél ek", 2), "másfél");
        eq("mellé", Amounts.scale("mellé", 2), "nem szám: változatlan");
        eq("2 db (salátának)", Amounts.scale("1 db (salátának)", 2), "megjegyzés marad");
        eq("8 szelet", Amounts.scale("8 szelet", 1), "1-es szorzó");
        // minden recept minden mennyisége értelmes marad fél és dupla adagra is
        for (var r : CATALOG.recipes()) for (var i : r.ingredients()) {
            String a = i.amount();
            if (a == null) continue;
            check(!Amounts.scale(a, 0.5).isBlank() && !Amounts.scale(a, 2).contains("NaN"), r.id() + ": " + a);
        }
    }

    static void testFileStorageRoundTrip() throws Exception {
        Path dir = Files.createTempDirectory("tarolo");
        try (FileStorage st = new FileStorage(dir)) {
            storageRoundTrip(st);
        } finally {
            deleteTree(dir);
        }
    }

    /** PostgreSQL: csak ha a PG_TEST_URL meg van adva (pl. postgres://postgres:jelszo@127.0.0.1:5432/teszt). */
    static void testPostgresStorage() throws Exception {
        String url = System.getenv("PG_TEST_URL");
        if (url == null || url.isBlank()) throw new Skip("PG_TEST_URL nincs megadva");
        try (PgStorage st = PgStorage.open(url, 1)) {
            storageRoundTrip(st);
            // párhuzamos használat a kapcsolatkészleten át
            var ex = java.util.concurrent.Executors.newFixedThreadPool(6);
            List<java.util.concurrent.Future<?>> fs = new ArrayList<>();
            String pid = "parhuzamos" + System.nanoTime() % 100000;
            for (int i = 0; i < 30; i++) {
                int n = i;
                fs.add(ex.submit(() -> {
                    st.rate(pid, "r" + (n % 7), n % 2 == 0);
                    return st.ratings(pid).size();
                }));
            }
            for (var f : fs) f.get();
            ex.shutdownNow();
            eq(7, st.ratings(pid).size(), "párhuzamos értékelések");
            // SQL-befecskendezés: a paraméter szövegként megy át
            st.saveProfile(pid, Map.of("store", "'); DROP TABLE household; --"));
            eq("'); DROP TABLE household; --", st.profile(pid).get("store"), "paraméterezett lekérdezés");
            // a séma második megnyitáskor is rendben (nem migrál újra)
            try (PgStorage again = PgStorage.open(url, 1)) {
                check(again.profile(pid) != null, "újranyitás");
            }
        }
    }

    static void storageRoundTrip(Storage st) throws Exception {
        String id = "teszt" + System.nanoTime() % 1_000_000;
        check(st.profile(id) == null, "új profil nincs");
        st.saveProfile(id, Map.of("store", "lidl", "people", 3));
        eq("lidl", st.profile(id).get("store"), "profil mentve");
        st.rate(id, "lecso", true);
        st.rate(id, "gulyasleves", false);
        st.rate(id, "lecso", false);
        st.rate(id, "bolognai", true);
        st.rate(id, "bolognai", null);
        eq(Map.of("lecso", false, "gulyasleves", false), st.ratings(id), "értékelések");
        st.clearRatings(id);
        check(st.ratings(id).isEmpty(), "értékelések törölve");
        st.saveWeek(id, Map.of("ids", List.of("a", "b"), "seed", 3));
        eq(List.of("a", "b"), st.week(id).get("ids"), "hét mentve");
        st.setChecked(id, "k1", "Tej", true);
        st.setChecked(id, "k1", "Kenyér", true);
        st.setChecked(id, "k1", "Tej", false);
        eq(Set.of("Kenyér"), st.checked(id, "k1"), "pipák");
        st.setChecked(id, "k2", "Rizs", true);
        check(st.checked(id, "k1").isEmpty(), "új lista: a régi pipák törlődnek");
        LocalDate d = LocalDate.of(2020, 1, 1).plusDays(System.nanoTime() % 2000);
        check(!st.hasPriceDay(d), "még nincs ilyen nap");
        st.savePriceDay(d, List.of(entry("x1", "Tej", 1, "lidl", 299, "l", 1), entry("x2", "Kenyér", 1, "aldi", 499.5, "kg", 1)));
        check(st.hasPriceDay(d), "nap elmentve");
        var pd = st.prices(d, d.plusDays(1));
        eq(1, pd.days(), "egy nap");
        eq(List.of(499.5), pd.prices().get("x2|aldi"), "ár visszaolvasva");
        st.saveManualPromos(List.of(Map.of("id", "p1", "store", "lidl", "name", "Csirke", "price", 999)));
        eq("Csirke", st.manualPromos().get(0).get("name"), "kézi akció");
        st.saveManualPromos(List.of());
        check(st.manualPromos().isEmpty(), "kézi akciók törölve");
    }

    static void testAdminApi() throws Exception {
        Path dir = Files.createTempDirectory("admin");
        FileStorage storage = new FileStorage(dir);
        hu.sporolos.data.CatalogSource src = new hu.sporolos.data.CatalogSource(storage);
        Catalog cat = src.load();
        PriceRepository repo = new PriceRepository(cat, List.of(new DemoPriceProvider(cat, LocalDate.now())),
                new PriceHistory(storage, 56), new PromoStore(dir.resolve("akciok"), List.of(), storage), Duration.ofHours(1));
        WebServer server = new WebServer(repo, storage, src, "titkos-jelszo");
        int port = server.start("127.0.0.1", 0);
        try {
            HttpClient c = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + port;
            get(c, base + "/api/admin/overview", 401);
            post(c, base + "/api/admin/login", "{\"password\":\"rossz\"}", 401);
            String tok = String.valueOf(Json.obj(Json.parse(post(c, base + "/api/admin/login", "{\"password\":\"titkos-jelszo\"}", 200))).get("token"));
            java.util.function.BiFunction<String, String, HttpRequest.Builder> req = (m, path) ->
                    HttpRequest.newBuilder(URI.create(base + path)).header("X-Admin-Token", tok).header("Content-Type", "application/json");
            java.util.function.Function<HttpRequest, HttpResponse<String>> run = r -> {
                try { return c.send(r, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)); } catch (Exception e) { throw new RuntimeException(e); }
            };
            var ov = run.apply(req.apply("GET", "/api/admin/overview").GET().build());
            eq(200, ov.statusCode(), "áttekintés belépve");
            eq(Boolean.FALSE, Json.obj(Json.parse(ov.body())).get("catalogModified"), "beépített katalógus");
            // akciót és árfrissítést csak admin vihet fel
            post(c, base + "/api/promos", "{\"store\":\"lidl\",\"ingredient\":\"vaj\",\"name\":\"Vaj\",\"price\":399,\"quantity\":0.25,\"unit\":\"kg\"}", 401);
            post(c, base + "/api/refresh", "", 401);
            eq(200, run.apply(req.apply("POST", "/api/promos").POST(HttpRequest.BodyPublishers.ofString(
                    "{\"store\":\"lidl\",\"ingredient\":\"vaj\",\"name\":\"Vaj\",\"price\":399,\"quantity\":0.25,\"unit\":\"kg\"}")).build()).statusCode(), "akció adminnal");

            // recept módosítása: a nyilvános API azonnal az új nevet adja, és a módosítás megmarad
            var lecso = run.apply(req.apply("GET", "/api/admin/recipe?id=lecso").GET().build());
            Map<String, Object> r = Json.obj(Json.parse(lecso.body()));
            r.put("name", "Lecsó (szerkesztve)");
            var saved = run.apply(req.apply("PUT", "/api/admin/recipe").PUT(HttpRequest.BodyPublishers.ofString(Json.write(r))).build());
            eq(200, saved.statusCode(), "recept mentve: " + saved.body());
            eq("Lecsó (szerkesztve)", Json.obj(Json.obj(Json.parse(get(c, base + "/api/recipe?id=lecso&store=lidl", 200))).get("recipe")).get("name"), "élőben frissült");
            check(storage.document(hu.sporolos.data.CatalogSource.RECIPES).contains("Lecsó (szerkesztve)"), "elmentve a tárolóba");
            eq("Lecsó (szerkesztve)", new hu.sporolos.data.CatalogSource(storage).load().recipe("lecso").name(), "újraindítás után is");
            // hibás recept: nem menti
            Map<String, Object> bad = new java.util.LinkedHashMap<>(r);
            bad.put("ingredients", List.of(Map.of("key", "nincsilyen", "amount", "1 db", "need", 1)));
            eq(400, run.apply(req.apply("PUT", "/api/admin/recipe").PUT(HttpRequest.BodyPublishers.ofString(Json.write(bad))).build()).statusCode(), "ismeretlen alapanyag");
            bad.put("ingredients", List.of(Map.of("key", "paradicsom", "amount", "1 db")));
            eq(400, run.apply(req.apply("PUT", "/api/admin/recipe").PUT(HttpRequest.BodyPublishers.ofString(Json.write(bad))).build()).statusCode(), "hiányzó mennyiség");
            // előnézet: a kiírt és a számolt mennyiség eltérése figyelmeztetés
            Map<String, Object> pv = new java.util.LinkedHashMap<>(r);
            pv.put("ingredients", List.of(Map.of("key", "paradicsom", "amount", "2 db", "need", 2.0)));
            var prev = Json.obj(Json.parse(run.apply(req.apply("POST", "/api/admin/recipe/preview").POST(HttpRequest.BodyPublishers.ofString(Json.write(pv))).build()).body()));
            check(!Json.arr(prev.get("issues")).isEmpty(), "2 db paradicsom ≠ 2 kg: figyelmeztet");
            check(Json.arr(prev.get("stores")).size() >= 5, "ár minden boltban");
            // új recept + törlés
            String nr = "{\"id\":\"admin-teszt\",\"name\":\"Admin teszt\",\"ingredients\":[{\"key\":\"tojas\",\"amount\":\"2 db\",\"need\":2}],\"steps\":[\"Megfőzöd.\"]}";
            eq(200, run.apply(req.apply("POST", "/api/admin/recipe").POST(HttpRequest.BodyPublishers.ofString(nr)).build()).statusCode(), "új recept");
            eq(400, run.apply(req.apply("POST", "/api/admin/recipe").POST(HttpRequest.BodyPublishers.ofString(nr)).build()).statusCode(), "duplikált azonosító");
            check(repo.catalog().recipe("admin-teszt") != null, "katalógusban");
            eq(200, run.apply(req.apply("DELETE", "/api/admin/recipe?id=admin-teszt").DELETE().build()).statusCode(), "törlés");
            check(repo.catalog().recipe("admin-teszt") == null, "törölve");
            // alapanyag: kizáró szó szűkíti a találatokat; használt alapanyag nem törölhető
            Map<String, Object> vaj = Json.obj(Json.obj(Json.parse(run.apply(req.apply("GET", "/api/admin/product?key=vaj").GET().build()).body())).get("product"));
            vaj.put("exclude", List.of("vaj"));
            var mm = Json.obj(Json.parse(run.apply(req.apply("POST", "/api/admin/product/matches").POST(HttpRequest.BodyPublishers.ofString(Json.write(vaj))).build()).body()));
            for (Object o : Json.arr(mm.get("matches"))) eq(0.0, Json.num(Json.obj(o).get("count"), -1), "a 'vaj' kizárva");
            eq(400, run.apply(req.apply("DELETE", "/api/admin/product?key=tojas").DELETE().build()).statusCode(), "használt alapanyag");
            check(Json.arr(Json.parse(run.apply(req.apply("GET", "/api/admin/products").GET().build()).body())).size() > 100, "alapanyaglista");
            check(!Json.arr(Json.parse(run.apply(req.apply("GET", "/api/admin/categories").GET().build()).body())).isEmpty(), "kategóriák");
            // visszaállítás
            eq(200, run.apply(req.apply("POST", "/api/admin/reset").POST(HttpRequest.BodyPublishers.noBody()).build()).statusCode(), "reset");
            eq("Lecsó kolbásszal", repo.catalog().recipe("lecso").name(), "visszaállt");
            check(storage.document(hu.sporolos.data.CatalogSource.RECIPES) == null, "a mentett változat törölve");
            check(get(c, base + "/admin", 200).contains("admin.js"), "admin oldal");
        } finally {
            server.stop();
            deleteTree(dir);
        }
    }

    static void testWeeklyPlanner() {
        List<PriceEntry> base = new DemoPriceProvider(CATALOG, LocalDate.of(2026, 1, 1)).load().entries();
        List<String> stores = List.of("aldi", "lidl", "penny", "auchan", "tesco", "spar");
        RecipeRecommender rec = new RecipeRecommender(CATALOG, base, new DealAnalyzer(base, null), stores);
        WeeklyPlanner wp = new WeeklyPlanner(CATALOG, rec);
        var plan = wp.plan("lidl", req(2, 5, 0, Set.of(), Set.of(), List.of(), Set.of(), 1));
        eq(5, plan.days().size(), "5 vacsora");
        java.util.Set<String> ids = new java.util.HashSet<>(), mains = new java.util.HashSet<>();
        int soups = 0;
        for (var d : plan.days()) {
            check(ids.add(d.suggestion().recipe().id()), "nincs ismétlés");
            check(mains.add(d.meta().mainKey()), "más-más fő alapanyag");
            check(!d.suggestion().recipe().tags().contains("édes"), "nem desszert vacsorára");
            eq(2, d.suggestion().servings(), "2 adag");
            if (d.suggestion().recipe().tags().contains("leves")) soups++;
        }
        check(soups <= 1, "legfeljebb egy leves");
        double sum = plan.items().stream().mapToDouble(i -> i.product().staple() ? 0 : i.priced().checkoutCost()).sum();
        check(Math.abs(sum - plan.checkoutTotal()) < 1, "a lista összege = végösszeg");
        double shares = plan.days().stream().mapToDouble(WeeklyPlanner.Day::shareOfList).sum();
        check(Math.abs(shares - plan.checkoutTotal()) < plan.checkoutTotal() * 0.02 + 2, "a napok részei kiadják a listát");
        // egy termék egyszer szerepel a listán, akkor is, ha több recept használja
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (var i : plan.items()) check(keys.add(i.product().key()), "egy sor termékenként: " + i.product().key());

        var vega = wp.plan("lidl", req(2, 5, 0, Set.of(RecipeMeta.VEGA, RecipeMeta.LACTOSE_FREE), Set.of(), List.of(), Set.of(), 1));
        for (var d : vega.days()) check(d.meta().fits(Set.of(RecipeMeta.VEGA, RecipeMeta.LACTOSE_FREE)), "diéta: " + d.suggestion().recipe().id());

        var noOven = wp.plan("lidl", req(2, 5, 0, Set.of(), Set.of(RecipeMeta.STOVE), List.of(), Set.of(), 1));
        for (var d : noOven.days()) check(!d.meta().needsOven(), "sütő nélkül: " + d.suggestion().recipe().id());

        String first = plan.days().get(0).suggestion().recipe().id(), second = plan.days().get(1).suggestion().recipe().id();
        java.util.List<String> keep = new java.util.ArrayList<>(java.util.Arrays.asList(first, null));
        var swapped = wp.plan("lidl", req(2, 2, 0, Set.of(), Set.of(), keep, Set.of(second), 1));
        eq(first, swapped.days().get(0).suggestion().recipe().id(), "a megtartott nap marad");
        check(!swapped.days().get(1).suggestion().recipe().id().equals(second), "a lecserélt nap más");

        var tight = wp.plan("lidl", req(2, 5, plan.checkoutTotal() * 0.8, Set.of(), Set.of(), List.of(), Set.of(), 1));
        check(tight.checkoutTotal() <= plan.checkoutTotal() * 0.8 + 1 || tight.overBudget(), "szűk keret: betartja vagy jelzi");
        var other = wp.plan("lidl", req(2, 5, 0, Set.of(), Set.of(), List.of(), Set.of(), 7));
        check(!other.days().stream().map(d -> d.suggestion().recipe().id()).toList()
                .equals(plan.days().stream().map(d -> d.suggestion().recipe().id()).toList()), "más mag, más hét");

        // maradék: kedd = hétfő, szerda = hétfő (3 napra főz), a többi nap új recept
        var lo = new WeeklyPlanner.Request(2, 5, 0, Set.of(), Set.of(), Set.of(), Set.of(), List.of(), Set.of(), 1,
                null, false, java.util.Arrays.asList(-1, 0, 0, -1, -1));
        var lp = wp.plan("lidl", lo);
        eq(5, lp.days().size(), "5 nap maradékkal");
        eq(6, lp.days().get(0).suggestion().servings(), "hétfőn 3 napra főz (6 adag)");
        eq(3, lp.days().get(0).coversDays(), "3 napra elég");
        eq(lp.days().get(0).suggestion().recipe().id(), lp.days().get(2).suggestion().recipe().id(), "szerdán a hétfői");
        eq(0.0, lp.days().get(1).shareOfList(), "maradéknapra nincs vásárlás");
        check(lp.days().get(0).meta().keepsWell(), "több napra csak eltartható étel");
        eq(3, WeeklyPlanner.cooked(lp).size(), "3 főzés");
        java.util.Set<String> distinct = new java.util.HashSet<>();
        for (var s0 : WeeklyPlanner.cooked(lp)) check(distinct.add(s0.recipe().id()), "a főzések különbözők");

        // ugyanaz a hét több boltban, és a két boltos bevásárlás sosem drágább
        List<WeeklyPlanner.StoreTotal> totals = new ArrayList<>();
        for (String st : stores) totals.add(wp.priceIn(st, WeeklyPlanner.cooked(plan), false));
        WeeklyPlanner.StoreTotal lidlTotal = totals.stream().filter(t -> t.storeId().equals("lidl")).findFirst().orElseThrow();
        check(Math.abs(lidlTotal.checkout() - plan.checkoutTotal()) < 1, "ugyanaz a bolt, ugyanaz az összeg");
        var split = wp.bestSplit(totals, 0);
        double single = totals.stream().mapToDouble(WeeklyPlanner.StoreTotal::checkout).min().orElseThrow();
        if (split != null) {
            check(split.checkout() <= single + 0.5, "két bolt nem drágább");
            eq(2, split.stores().size(), "két bolt");
            check(split.items().stream().allMatch(it -> split.stores().contains(it.storeId())), "minden tétel a két bolt egyikéből");
        }
        check(wp.bestSplit(totals, 1_000_000) == null, "kis megtakarításért nem bontja két boltra");
        // a rántott / ropogós ételek nem „eltarthatók”
        check(!CATALOG.meta("rantott-hus").keepsWell() && CATALOG.meta("gulyasleves").keepsWell(), "keepsWell");
    }

    static WeeklyPlanner.Request req(int people, int dinners, double budget, Set<String> diets, Set<String> eq,
                                     List<String> keep, Set<String> exclude, long seed) {
        return new WeeklyPlanner.Request(people, dinners, budget, diets, eq, Set.of(), Set.of(), keep, exclude, seed, null, false);
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


    // ---------- országok: pénznem, horvát árlisták ----------

    static void testMarketMoney() {
        String nb = " ";
        eq("5" + nb + "484" + nb + "Ft", Market.HU.money(5484), "forint: ezres tagolás");
        eq("-1" + nb + "200" + nb + "Ft", Market.HU.money(-1200), "negatív");
        MarketDef hr = MarketDef.resource("hr");
        Market m = hr.market();
        eq("EUR", m.currency(), "Horvátország: euró");
        eq("12,49" + nb + "€", m.money(m.toInternal(12.49)), "euró két tizedessel");
        eq("1" + nb + "234,50" + nb + "€", m.money(m.toInternal(1234.5)), "euró ezres tagolással");
        eq("0,39" + nb + "€", m.money(m.toInternal(0.39)), "centes ár");
        // a szövegek (pl. egységár) a kérés piacának pénznemével készülnek
        eq("2,50" + nb + "€", Market.with(m, () -> Packaging.Money.ft(m.toInternal(2.5))), "a szál piaca szerint");
        eq("1" + nb + "000" + nb + "Ft", Packaging.Money.ft(1000), "alapból forint");
    }

    static void testCroatianNameRules() {
        NameRules r = MarketDef.resource("hr").rules();
        Object[][] cases = {
                {"JAJA SVJ L 10/1 SL UZGOJ AGRO-KLASTER", "db", 10.0, "tojas"},
                {"MLIJEKO SVJ 1.5% M.M. 1 L  ABC", "l", 1.0, "tej"},
                {"MLIJEKO TR 2.8%MM 2L ČEP VINDIJA", "l", 2.0, "tej"},
                {"KOKOS MLIJEKO MANJ MAS 500 ml GO-TAN", "l", 0.5, "kokusztej"},
                {"VRHNJE ZA KUH 20% BRZO&FINO 3X200g", "kg", 0.6, "tejszin"},
                {"KISELO VRHNJE MILERAM 22% 400g DUKAT", "kg", 0.4, "tejfol"},
                {"PILEĆA PRSA BEZ KOŽE CCA 500G/2.5kg SP SV", "kg", 1.0, "csirkemell"},
                {"ZABATAK PILEĆI K PLUS", "kg", 1.0, "csirkecomb"},
                {"RAJČICA PASIRANA GARDEN GOOD 500g", "kg", 0.5, "passata"},
                {"RAJČICA", "kg", 1.0, "paradicsom"},
                {"KRUH BIJELI REZANI PAK 580 g  K PLUS", "kg", 0.58, "kenyer"},
                {"KUKURUZ ŠEĆERAC 285G", "kg", 0.285, "kukorica"},
                {"RIŽA DUGO ZRNO EXTRA 1  kg SCOTTI", "kg", 1.0, "rizs"},
                {"TJESTENINA SPAGHETTI MARODI 400g", "kg", 0.4, "spagetti"},
                {"BRAŠNO GLATKO T-550 1 kg PODRAVKA", "kg", 1.0, "liszt"},
                {"BANANA BIO", "kg", 1.0, "banan"},
                {"JABUKA GRANNY SMITH", "kg", 1.0, "alma"},
                {"Svježe mlijeko 3,2% m.m.", "l", 1.0, "tej"},
                {"Junetina mljevena", "kg", 0.5, "daralt_marha"},
                {"Miješano mljeveno meso", "kg", 0.5, "daralthus"},
                {"Ulje maslinovo extra djevičansko", "l", 0.5, "olivaolaj"},
                {"Luk crveni", "kg", 1.0, "voroshagyma"},
                // nem alapanyag: kész étel, felvágott, édesség, állateledel, kozmetikum
                {"PILEĆI FILETINO CLASSIC VINDIJA", "kg", 1.0, null},
                {"UMAK RAJČICA DATTER 330 g AGROMONTE", "kg", 0.33, null},
                {"ČOKOLADA FINESSE MLIJ RIŽA 200g", "kg", 0.2, null},
                {"JUHA OKUSA PILETINA 5/1 INDOMIE 350g", "kg", 0.35, null},
                {"WHISKAS PILETINA 100 g", "kg", 0.1, null},
                {"Pjena za kupanje kokos i mlijeko", "l", 0.5, null},
                {"Savijača jabuka, vanilija i karamela", "kg", 0.45, null},
                {"Krekeri sa sirom i češnjakom", "kg", 0.13, null},
                {"Goveđi gulaš", "kg", 0.3, null},
        };
        for (Object[] c : cases) {
            NameRules.Rule rule = r.classify((String) c[0], (String) c[1], (double) c[2]);
            eq(c[3], rule == null ? null : rule.key(), "illesztés: " + c[0]);
        }
        // a szabályok csak létező alapanyagra mutatnak
        for (NameRules.Rule rule : r.rules()) check(CATALOG.products().containsKey(rule.key()), "ismert alapanyag: " + rule.key());
    }

    /** Egy kis, valósághű horvát napi archívum (cijene.dev formátum). */
    static Path croatianArchive(Path dir) throws IOException {
        Path zip = dir.resolve("cijene-2026-11-03.zip");
        Object[][] products = {
                // név, egység, kiszerelés, vonalkód, ár (€)
                {"JAJA SVJ L 10/1 SL UZGOJ", "ko", "10.00 ko", "3857500091710", 3.69},
                {"MLIJEKO SVJ 1.5% M.M. 1 L  ABC", "ko", "1.00 l", "3850257062361", 1.45},
                {"PILEĆA PRSA BEZ KOŽE CCA 500G/2.5kg", "kg", "1.00 kg", "3850220115810", 8.09},
                {"KRUMPIR BIJELI", "kg", "1.00 kg", "", 0.99},
                {"LUK CRVENI", "kg", "1.00 kg", "", 1.29},
                {"RIŽA DUGO ZRNO 1 kg", "ko", "1.00 kg", "8001860259401", 1.99},
                {"KRUH BIJELI \"DOMAĆI\", REZANI 500 g", "ko", "", "3850334420800", 1.65},
                {"KAVA MLJEVENA 250 g", "ko", "0.25 kg", "3850000000149", 4.99},
                {"WHISKAS PILETINA 100 g", "ko", "0.10 kg", "3850000000170", 0.79},
        };
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(zip))) {
            for (String chain : List.of("konzum", "lidl", "dm")) {
                int stores = chain.equals("konzum") ? 4 : 2;
                StringBuilder st = new StringBuilder("store_id,type,address,city,zipcode\n");
                for (int i = 0; i < stores; i++) st.append(chain).append(i).append(",supermarket,Ulica ").append(i).append(",Zagreb,10000\n");
                StringBuilder pr = new StringBuilder("product_id,barcode,name,brand,category,unit,quantity\n");
                StringBuilder pc = new StringBuilder("store_id,product_id,price,unit_price,best_price_30,anchor_price,special_price,available,special_sale_type,anchor_price_date\n");
                for (int k = 0; k < products.length; k++) {
                    Object[] p = products[k];
                    String pid = chain.substring(0, 2) + k;
                    String name = ((String) p[0]).contains(",") || ((String) p[0]).contains("\"")
                            ? "\"" + ((String) p[0]).replace("\"", "\"\"") + "\"" : (String) p[0];
                    pr.append(pid).append(',').append(p[3]).append(',').append(name).append(",,HRANA,").append(p[1]).append(',').append(p[2]).append('\n');
                    double base = (double) p[4] * (chain.equals("lidl") ? 0.95 : 1.0);
                    for (int i = 0; i < stores; i++) {
                        double price = Math.round(base * (1 + i * 0.02) * 100) / 100.0;
                        // a Konzum csirkemellje akciós a boltok felében (az ár mezője ilyenkor üres)
                        boolean promo = chain.equals("konzum") && k == 2 && i % 2 == 0;
                        double sp = Math.round(price * 0.75 * 100) / 100.0;
                        String unitPrice = k == 6 ? String.valueOf(Math.round(price / 0.5 * 100) / 100.0) : "";
                        pc.append(chain).append(i).append(',').append(pid).append(',').append(promo ? "" : String.valueOf(price)).append(',')
                                .append(unitPrice).append(',').append(promo ? sp : price).append(',').append(p[4]).append(',')
                                .append(promo ? String.valueOf(sp) : "").append(",1,").append(promo ? "akcija" : "").append(",\n");
                    }
                }
                z.putNextEntry(new ZipEntry(chain + "/stores.csv"));
                z.write(st.toString().getBytes(StandardCharsets.UTF_8));
                z.putNextEntry(new ZipEntry(chain + "/products.csv"));
                z.write(pr.toString().getBytes(StandardCharsets.UTF_8));
                z.putNextEntry(new ZipEntry(chain + "/prices.csv"));
                z.write(pc.toString().getBytes(StandardCharsets.UTF_8));
            }
            z.putNextEntry(new ZipEntry("archive-info.txt"));
            z.write("teszt".getBytes(StandardCharsets.UTF_8));
        }
        return zip;
    }

    static void testCroatianArchive() throws Exception {
        Path dir = Files.createTempDirectory("sporolos-hr");
        try {
            MarketDef hr = MarketDef.resource("hr");
            Market m = hr.market();
            var provider = new CijeneArchiveProvider(hr, null, dir.resolve("hr"), croatianArchive(dir));
            PriceProvider.Snapshot snap = provider.load();
            eq(LocalDate.parse("2026-11-03"), snap.date(), "a dátum a fájlnévből");
            check(snap.entries().stream().noneMatch(e -> e.storeId().equals("dm")), "a drogéria (dm) kimarad");
            PriceEntry eggs = snap.entries().stream().filter(e -> e.storeId().equals("konzum") && e.productName().startsWith("Jaja")).findFirst().orElseThrow();
            eq("db", eggs.unit(), "tojás darabra");
            eq(10.0, eggs.quantity(), "10 tojás");
            eq(m.toInternal(3.69), eggs.price(), "a lánc legolcsóbb boltjának ára, belső egységben");
            eq(4, eggs.totalShops(), "a Konzum 4 boltja");
            eq(2, eggs.chainCount(), "két láncban kapható (ugyanaz a vonalkód)");
            check(eggs.categoryId() >= MarketDef.CATEGORY_BASE, "a tojás alapanyagként illeszkedik");
            PriceEntry chicken = snap.entries().stream().filter(e -> e.storeId().equals("konzum") && e.productName().startsWith("Pileća")).findFirst().orElseThrow();
            check(chicken.byWeight(), "a csirkemell mérlegelős (kg egység)");
            check(chicken.price() < chicken.maxPrice(), "akciós és rendes ár a boltok között");
            check(snap.nativePromos().containsKey(chicken.itemId() + "|konzum"), "a Konzum akciója a lánc saját akciója");
            PriceEntry bread = snap.entries().stream().filter(e -> e.productName().startsWith("Kruh")).findFirst().orElseThrow();
            eq("kg", bread.unit(), "a kenyér kiszerelése a névből (500 g)");
            eq(0.5, bread.quantity(), "500 g");
            check(bread.productName().contains("\"domaći\", rezani"), "idézőjeles, vesszős név is jól olvasódik: " + bread.productName());
            check(snap.entries().stream().filter(e -> e.productName().startsWith("Whiskas")).allMatch(e -> e.categoryId() == 0), "állateledel nem alapanyag");
            check(snap.entries().stream().filter(e -> e.productName().startsWith("Krumpir")).allMatch(e -> e.byWeight()), "lédig krumpli mérlegelős");

            // az app egésze horvát árakkal: ország a profilban, euró, horvát boltok
            Catalog hrCatalog = hr.deriveCatalog(resource("/data/products.json"), resource("/data/recipes.json"));
            PriceRepository huRepo = new PriceRepository(CATALOG, List.of(new DemoPriceProvider(CATALOG, LocalDate.now())), null, Duration.ofHours(1));
            Markets markets = new Markets(huRepo);
            markets.add(hr, new PriceRepository(m, hrCatalog, List.of(provider), null, null, Duration.ofHours(12)));
            FileStorage storage = new FileStorage(dir);
            WebServer server = new WebServer(markets, storage, null, null);
            int port = server.start("127.0.0.1", 0);
            try {
                HttpClient c = HttpClient.newHttpClient();
                String base = "http://127.0.0.1:" + port;
                // első kérés: a horvát árak a háttérben töltődnek → 503 „töltődik”
                HttpResponse<String> r = c.send(HttpRequest.newBuilder(URI.create(base + "/api/status?market=hr")).build(),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (r.statusCode() == 503) {
                    check(Boolean.TRUE.equals(Json.obj(Json.parse(r.body())).get("loading")), "töltődik jelzés");
                    for (int i = 0; i < 100 && r.statusCode() == 503; i++) {
                        Thread.sleep(100);
                        r = c.send(HttpRequest.newBuilder(URI.create(base + "/api/status?market=hr")).build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                    }
                }
                eq(200, r.statusCode(), "a horvát árak betöltődtek");
                Map<String, Object> st = Json.obj(Json.parse(r.body()));
                eq("EUR", Json.obj(st.get("market")).get("currency"), "euró");
                List<String> ids = Json.arr(st.get("stores")).stream().map(x -> Json.str(Json.obj(x).get("id"))).toList();
                check(ids.contains("konzum") && ids.contains("lidl") && !ids.contains("dm"), "horvát láncok: " + ids);
                eq(2, Json.arr(st.get("markets")).size(), "két ország");
                // magyar kérés közben is magyar marad
                Map<String, Object> huSt = Json.obj(Json.parse(get(c, base + "/api/status", 200)));
                eq("HUF", Json.obj(huSt.get("market")).get("currency"), "a magyar állapot forint");
                // profil horvát országgal
                Map<String, Object> prof = Json.obj(Json.parse(post(c, base + "/api/profile",
                        "{\"country\":\"hr\",\"people\":2,\"dinners\":3,\"budget\":20000,\"onboarded\":true,\"stores\":[\"konzum\",\"aldi\"]}", 200)));
                Map<String, Object> settings = Json.obj(prof.get("settings"));
                eq("hr", settings.get("country"), "ország mentve");
                eq(List.of("konzum"), Json.arr(settings.get("stores")), "csak horvát bolt maradhat");
                String pid = Json.str(prof.get("id"));
                Map<String, Object> w = Json.obj(Json.parse(get(c, base + "/api/week?id=" + pid, 200)));
                check(List.of("konzum", "lidl").contains(Json.str(w.get("store"))), "a hét horvát boltban: " + w.get("store"));
                eq(3, Json.arr(w.get("days")).size(), "3 vacsora");
                String text = w.toString();
                check(text.contains("€"), "euróban írt egységárak");
                // keresés magyarul a horvát árlistában
                List<Object> hits = Json.arr(Json.parse(get(c, base + "/api/search?q=k%C3%A1v%C3%A9&market=hr", 200)));
                check(!hits.isEmpty() && Json.str(Json.obj(hits.get(0)).get("name")).startsWith("Kava"), "kávé → kava");
                // angolul („coffee” → kávé → kava) és horvátul („kava” marad) is
                List<Object> en = Json.arr(Json.parse(get(c, base + "/api/search?q=coffee&market=hr&lang=en", 200)));
                check(!en.isEmpty() && Json.str(Json.obj(en.get(0)).get("name")).startsWith("Kava"), "coffee → kava");
                List<Object> hrHits = Json.arr(Json.parse(get(c, base + "/api/search?q=kava&market=hr&lang=hr", 200)));
                check(!hrHits.isEmpty() && Json.str(Json.obj(hrHits.get(0)).get("name")).startsWith("Kava"), "kava → kava");
                Map<String, Object> wHr = Json.obj(Json.parse(get(c, base + "/api/week?id=" + pid + "&lang=hr", 200)));
                check(wHr.toString().contains("€"), "horvátul is euró");
                // az akciók között ott a Konzum csirkemellje, euróban
                List<Object> offers = Json.arr(Json.parse(get(c, base + "/api/offers?market=hr&ingredients=false", 200)));
                check(offers.stream().anyMatch(o -> "PROMO".equals(Json.obj(o).get("type")) && Json.str(Json.obj(o).get("reason")).contains("€")), "horvát bolti akció euróban");
                // vissza Magyarországra: a hét magyar boltban
                send(c, "PUT", base + "/api/profile?id=" + pid, "{\"country\":\"hu\",\"people\":2,\"dinners\":3,\"onboarded\":true}", 200);
                Map<String, Object> w2 = Json.obj(Json.parse(get(c, base + "/api/week?id=" + pid, 200)));
                check(List.of("aldi", "lidl", "penny", "auchan", "tesco", "spar").contains(Json.str(w2.get("store"))), "magyar bolt");
                check(!w2.toString().contains("€"), "forintban");
            } finally {
                server.stop();
            }
        } finally {
            deleteTree(dir);
        }
    }

    static void testWeekOptionsDatesAndVariety() throws Exception {
        Path dir = Files.createTempDirectory("sporolos-opt");
        PriceRepository repo = new PriceRepository(CATALOG, List.of(new DemoPriceProvider(CATALOG, LocalDate.now())), null, Duration.ofHours(1));
        WebServer server = new WebServer(repo, new FileStorage(dir));
        int port = server.start("127.0.0.1", 0);
        try {
            HttpClient c = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + port;
            String pid = Json.str(Json.obj(Json.parse(post(c, base + "/api/profile", "{\"people\":2,\"dinners\":5,\"onboarded\":true}", 200))).get("id"));
            Map<String, Object> w = Json.obj(Json.parse(get(c, base + "/api/week?id=" + pid, 200)));
            List<Object> days = Json.arr(w.get("days"));
            eq(5, days.size(), "5 nap");
            // a hét a mai naptól (vasárnap a holnapi hétfőtől) indul, a napok neve a dátumhoz illik
            LocalDate start = LocalDate.parse(Json.str(w.get("start")));
            LocalDate today = LocalDate.now();
            eq(today.getDayOfWeek() == java.time.DayOfWeek.SUNDAY ? today.plusDays(1) : today, start, "a hét kezdete");
            for (int i = 0; i < days.size(); i++) {
                Map<String, Object> d = Json.obj(days.get(i));
                LocalDate date = LocalDate.parse(Json.str(d.get("date")));
                eq(start.plusDays(i), date, "egymást követő napok");
                eq(WeeklyPlanner.DAYS[date.getDayOfWeek().getValue() - 1], d.get("label"), "a nap neve a dátumból");
            }
            // változatosság: két vacsora nem épül ugyanarra a jellegzetes alapanyagra, és a krumpli legfeljebb kétszer
            List<String> ids = recipeIds(w);
            Set<String> hero = Set.of("karfiol", "brokkoli", "cukkini", "lencse", "csirkemell", "csirkecomb", "daralthus", "spenot", "gomba");
            Map<String, Integer> seen = new HashMap<>();
            int potato = 0;
            for (String id : ids) {
                var r = CATALOG.recipe(id);
                Set<String> keys = new java.util.HashSet<>();
                for (var ing : r.ingredients()) if (!ing.pantry()) keys.add(ing.productKey());
                for (String k : keys) if (hero.contains(k)) seen.merge(k, 1, Integer::sum);
                if (keys.contains("burgonya") || keys.contains("hasabburgonya")) potato++;
            }
            check(seen.values().stream().allMatch(n -> n <= 1), "jellegzetes alapanyag legfeljebb egyszer: " + seen);
            check(potato <= 2, "krumpli legfeljebb kétszer: " + potato);
            // csere-javaslatok: a többi nap marad, a jelöltek mások, mindegyiknél a hét új ára
            Map<String, Object> opts = Json.obj(Json.parse(get(c, base + "/api/week?id=" + pid + "&action=options&day=1", 200)));
            List<Object> list = Json.arr(opts.get("options"));
            check(!list.isEmpty() && list.size() <= 6, "1–6 javaslat");
            for (Object o : list) {
                String rid = Json.str(Json.obj(Json.obj(o).get("recipe")).get("id"));
                check(!ids.contains(rid), "a javaslat nincs már a héten: " + rid);
                check(Json.num(Json.obj(o).get("weekTotal"), 0) > 0, "a hét új ára");
            }
            String pick = Json.str(Json.obj(Json.obj(list.get(0)).get("recipe")).get("id"));
            Map<String, Object> w2 = Json.obj(Json.parse(get(c, base + "/api/week?id=" + pid + "&action=set&day=1&recipe=" + pick, 200)));
            List<String> ids2 = recipeIds(w2);
            eq(pick, ids2.get(1), "a választott recept került a keddre");
            eq(ids.get(0), ids2.get(0), "a többi nap marad");
            eq(Json.str(w.get("start")), Json.str(w2.get("start")), "a hét kezdete marad");
            check(Math.abs(Json.num(Json.obj(list.get(0)).get("weekTotal"), 0) - Json.num(w2.get("checkoutTotal"), 0)) < 400,
                    "a javaslat ára közel van a tényleges heti árhoz");
            // maradéknapra nincs csere-javaslat (ott az „inkább mást főzök” a művelet)
            get(c, base + "/api/week?id=" + pid + "&action=repeat&day=2", 200);
            Map<String, Object> lo = Json.obj(Json.parse(get(c, base + "/api/week?id=" + pid + "&action=options&day=3", 200)));
            eq(2.0, Json.num(lo.get("day"), -1), "maradéknapnál a főzős nap (szerda) javaslatai jönnek");
        } finally {
            server.stop();
            deleteTree(dir);
        }
    }

    static String resource(String path) throws IOException {
        try (var in = AllTests.class.getResourceAsStream(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static String post(HttpClient c, String url, String body, int expected) throws IOException, InterruptedException {
        return send(c, "POST", url, body, expected);
    }

    static String send(HttpClient c, String method, String url, String body, int expected) throws IOException, InterruptedException {
        HttpResponse<String> r = c.send(HttpRequest.newBuilder(URI.create(url))
                .method(method, HttpRequest.BodyPublishers.ofString(body)).header("Content-Type", "application/json").build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        eq(expected, r.statusCode(), "HTTP státusz: " + method + " " + url + " " + r.body());
        return r.body();
    }

    static List<String> recipeIds(Map<String, Object> plan) {
        return Json.arr(plan.get("days")).stream()
                .map(d -> String.valueOf(Json.obj(Json.obj(Json.obj(d).get("suggestion")).get("recipe")).get("id"))).toList();
    }

    static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var s = Files.walk(dir)) {
            s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
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
