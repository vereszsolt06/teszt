package hu.sporolos.data;

import hu.sporolos.model.Models.PriceEntry;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A GVH Árfigyelő napi termékadat-Excelje (arfigyelo_napi_termekadatok.xlsx).
 *
 * Forrás sorrend:
 *  1. ha a {@code localFile} létezik → azt olvassa,
 *  2. különben letölti a {@code url}-ről, és elmenti a {@code cacheFile}-ba,
 *  3. ha a letöltés nem sikerül, a legutóbb elmentett {@code cacheFile}-t használja.
 *
 * Az oszlopokat a fejléc szövege alapján keresi meg, így az oszlopsorrend változása nem töri el.
 */
public final class ArfigyeloExcelProvider implements PriceProvider {

    public static final String DEFAULT_URL =
            "https://cdnarfigyeloprodweu.azureedge.net/excel/arfigyelo_napi_termekadatok.xlsx";

    private final Catalog catalog;
    private final String url;
    private final Path localFile;
    private final Path cacheFile;

    public ArfigyeloExcelProvider(Catalog catalog, String url, Path localFile, Path cacheFile) {
        this.catalog = catalog;
        this.url = url;
        this.localFile = localFile;
        this.cacheFile = cacheFile;
    }

    @Override
    public Snapshot load() throws Exception {
        if (localFile != null && Files.isRegularFile(localFile)) {
            try (InputStream in = Files.newInputStream(localFile)) {
                return parse(in, "GVH Árfigyelő – helyi fájl (" + localFile.getFileName() + ")");
            }
        }
        if (url != null && !url.isBlank()) {
            try {
                byte[] data = download(url);
                Snapshot s = parse(new ByteArrayInputStream(data), "GVH Árfigyelő – friss letöltés");
                if (cacheFile != null) saveCache(data);
                return s;
            } catch (Exception e) {
                if (cacheFile == null || !Files.isRegularFile(cacheFile)) throw e;
                System.err.println("[árforrás] Letöltés sikertelen (" + e.getMessage() + "), a mentett példányt használom.");
            }
        }
        if (cacheFile != null && Files.isRegularFile(cacheFile)) {
            try (InputStream in = Files.newInputStream(cacheFile)) {
                return parse(in, "GVH Árfigyelő – utoljára letöltött példány");
            }
        }
        throw new IOException("Nincs elérhető Árfigyelő Excel (se helyi fájl, se letöltés)");
    }

    private void saveCache(byte[] data) {
        try {
            Files.createDirectories(cacheFile.toAbsolutePath().getParent());
            Path tmp = cacheFile.resolveSibling(cacheFile.getFileName() + ".tmp");
            Files.write(tmp, data);
            Files.move(tmp, cacheFile, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            System.err.println("[árforrás] A letöltött fájl mentése nem sikerült: " + e.getMessage());
        }
    }

    private static byte[] download(String url) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("User-Agent", "SporolosKonyha/1.0")
                .GET().build();
        HttpResponse<byte[]> res = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (res.statusCode() != 200) throw new IOException("HTTP " + res.statusCode() + " – " + url);
        return res.body();
    }

    Snapshot parse(InputStream in, String sourceName) throws IOException {
        RowConverter conv = new RowConverter();
        String sheetName = XlsxReader.streamFirstSheet(in, conv::accept);
        return new Snapshot(dateFromSheetName(sheetName), conv.finish(), sourceName, false);
    }

    /** "Napi feltöltések - 2026-10-28" → 2026-10-28; ha nincs benne dátum, a mai nap. */
    static LocalDate dateFromSheetName(String name) {
        Matcher m = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})").matcher(name == null ? "" : name);
        return m.find() ? LocalDate.parse(m.group(1)) : LocalDate.now();
    }

    /** Fejléc szerinti oszlopkeresés + soronkénti konverzió (memóriában lévő sorokra, pl. tesztekhez). */
    List<PriceEntry> toEntries(List<List<String>> rows) throws IOException {
        RowConverter conv = new RowConverter();
        for (int i = 0; i < rows.size(); i++) conv.accept(i, rows.get(i));
        return conv.finish();
    }

    /**
     * Soronként érkező cellákból PriceEntry-ket készít. Az első 15 sorban keresi a fejlécet,
     * utána minden sort átalakít; csak az élelmiszer-kategóriákat tartja meg.
     */
    final class RowConverter {
        private Columns cols;
        private int skipped;
        private final List<PriceEntry> out = new ArrayList<>();

        void accept(int index, List<String> row) {
            if (cols == null) {
                if (index < 15) cols = Columns.detect(row);
                return;
            }
            Function<Integer, String> cell = c -> c >= 0 && c < row.size() ? row.get(c).trim() : "";
            int catId = (int) num(cell.apply(cols.categoryId()), -1);
            if (!catalog.isFoodCategory(catId)) return;
            String storeId = catalog.resolveStore(cell.apply(cols.chain()));
            double price = num(cell.apply(cols.minPrice()), Double.NaN);
            String name = cell.apply(cols.name()).replaceAll("\\s+", " ").trim();
            if (storeId == null || Double.isNaN(price) || price <= 0 || name.isEmpty()) {
                skipped++;
                return;
            }

            double maxPrice = num(cell.apply(cols.maxPrice()), price);
            String unit = cell.apply(cols.unit()).toLowerCase(java.util.Locale.ROOT);
            double qty = num(cell.apply(cols.quantity()), 1);
            // g → kg, ml → l, hogy az egységárak összevethetők legyenek
            if (unit.equals("g")) { unit = "kg"; qty /= 1000; }
            if (unit.equals("ml")) { unit = "l"; qty /= 1000; }
            if (unit.isEmpty()) unit = "db";
            if (qty <= 0) qty = 1;
            // Az egységárat magunk számoljuk: a fájl "Minimum egységár" oszlopa néhány sorban hibás
            // (pl. 26 g-os teánál a csomagárat tartalmazza egységár helyett).
            double unitPrice = price / qty;
            String id = cell.apply(cols.itemId());
            if (id.isEmpty()) id = Catalog.normalize(name) + "|" + qty + unit;

            out.add(new PriceEntry(id, name, catId, cell.apply(cols.categoryName()), storeId,
                    price, Math.max(price, maxPrice), unit, qty, unitPrice,
                    (int) num(cell.apply(cols.chainCount()), 1), (int) num(cell.apply(cols.shopCount()), 0),
                    (int) num(cell.apply(cols.totalShops()), 0)));
        }

        List<PriceEntry> finish() throws IOException {
            if (cols == null) {
                throw new IOException("Nem található az Árfigyelő fejléc (Termék név, Üzletlánc név, Minimum ár…)");
            }
            if (out.isEmpty()) throw new IOException("Az Excel nem tartalmazott értelmezhető élelmiszer-árat");
            if (skipped > 0) System.err.println("[árforrás] " + skipped + " hiányos sor kihagyva");
            return out;
        }
    }

    /** "1 299,0000" / "399.5" → szám. */
    static double num(String s, double def) {
        if (s == null || s.isBlank()) return def;
        try {
            return Double.parseDouble(s.replace(" ", "").replace(" ", "").replace(",", "."));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** Oszlopindexek a fejlécből. */
    record Columns(int itemId, int name, int categoryId, int categoryName, int chain, int unit, int quantity,
                   int minPrice, int maxPrice, int minUnitPrice, int chainCount, int shopCount, int totalShops) {

        static Columns detect(List<String> header) {
            List<String> h = header.stream().map(Catalog::normalize).toList();
            Map<String, Integer> found = new java.util.HashMap<>();
            for (int i = 0; i < h.size(); i++) {
                String c = h.get(i);
                if (c.contains("termek azonosito")) found.putIfAbsent("id", i);
                else if (c.contains("termek nev") || c.equals("termek") || c.contains("megnevezes")) found.putIfAbsent("name", i);
                else if (c.contains("kategoria azonosito")) found.putIfAbsent("catId", i);
                else if (c.contains("kategoria")) found.putIfAbsent("catName", i);
                else if (c.contains("uzletlanc nev") || c.equals("uzletlanc") || c.equals("lanc")) found.putIfAbsent("chain", i);
                else if (c.equals("egyseg")) found.putIfAbsent("unit", i);
                else if (c.contains("kiszereles")) found.putIfAbsent("qty", i);
                else if (c.contains("minimum egysegar")) found.putIfAbsent("minUnit", i);
                else if (c.contains("minimum ar")) found.putIfAbsent("min", i);
                else if (c.contains("maximum ar")) found.putIfAbsent("max", i);
                else if (c.contains("hany uzletlancban")) found.putIfAbsent("chains", i);
                else if (c.contains("hany boltban")) found.putIfAbsent("shops", i);
                else if (c.contains("osszes boltja") || c.contains("osszes bolt")) found.putIfAbsent("total", i);
            }
            if (!found.containsKey("name") || !found.containsKey("chain") || !found.containsKey("min")) return null;
            Function<String, Integer> g = k -> found.getOrDefault(k, -1);
            return new Columns(g.apply("id"), g.apply("name"), g.apply("catId"), g.apply("catName"), g.apply("chain"),
                    g.apply("unit"), g.apply("qty"), g.apply("min"), g.apply("max"), g.apply("minUnit"),
                    g.apply("chains"), g.apply("shops"), g.apply("total"));
        }
    }
}
