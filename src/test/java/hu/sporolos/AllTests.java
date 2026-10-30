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
import hu.sporolos.web.RecipeArt;
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

    static void testWebServerEndToEnd() throws Exception {
        Path dir = Files.createTempDirectory("sporolos");
        PriceRepository repo = new PriceRepository(CATALOG,
                List.of(new ArfigyeloExcelProvider(CATALOG, null, dir.resolve("nincs.xlsx"), null), // nem létezik → demóra vált
                        new DemoPriceProvider(CATALOG, LocalDate.now())),
                new PriceHistory(dir.resolve("history"), 28), new PromoStore(dir.resolve("akciok"), List.of()),
                Duration.ofHours(1));
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

            List<Object> hits = Json.arr(Json.parse(get(c, base + "/api/search?q=tojas&store=lidl", 200)));
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
            check(get(c, base + "/app.js", 200).contains("nextRecipe"), "app.js");
            check(get(c, base + "/views.js", 200).contains("loadPlan"), "views.js");

            // heti terv, receptlista, képek
            Map<String, Object> plan = Json.obj(Json.parse(get(c, base + "/api/plan?store=lidl&people=3&dinners=4&budget=20000&diets=vega", 200)));
            List<Object> days = Json.arr(plan.get("days"));
            eq(4, days.size(), "4 vacsora");
            for (Object d : days) {
                Map<String, Object> r = Json.obj(Json.obj(Json.obj(d).get("suggestion")).get("recipe"));
                check(Json.arr(r.get("diets")).contains("vega"), "vega terv: " + r.get("id"));
                check(String.valueOf(r.get("image")).startsWith("/img/recipe/"), "van kép");
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
            String svg = get(c, base + "/img/recipe/" + firstId + ".svg", 200);
            check(svg.startsWith("<svg") && svg.endsWith("</svg>"), "SVG kép");
            get(c, base + "/img/recipe/nincsilyen.svg", 404);
            get(c, base + "/img/recipe/..%2Fpom.xml", 400);
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

    static void testRecipeArtForAll() {
        RecipeArt art = new RecipeArt(CATALOG);
        java.util.Set<String> distinct = new java.util.HashSet<>();
        for (var r : CATALOG.recipes()) {
            String svg = art.svg(r.id());
            check(svg != null && svg.startsWith("<svg") && svg.endsWith("</svg>"), "SVG: " + r.id());
            check(!svg.contains("NaN") && !svg.contains("<script"), "tiszta SVG: " + r.id());
            distinct.add(svg);
        }
        eq(CATALOG.recipes().size(), distinct.size(), "minden kép más");
        check(art.svg("nincsilyen") == null, "ismeretlen recept: nincs kép");
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
