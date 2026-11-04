package hu.sporolos.data;

import hu.sporolos.core.Json;
import hu.sporolos.core.PromoMerger;
import hu.sporolos.market.Market;
import hu.sporolos.market.MarketDef;
import hu.sporolos.market.NameRules;
import hu.sporolos.model.Models.PriceEntry;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Horvát árak a láncok kötelező napi árlistáiból (NN 75/2025), a cijene.dev nyilvános napi ZIP-archívumán
 * keresztül (https://api.cijene.dev/v0/list → {@code /v0/archive/ÉÉÉÉ-HH-NN.zip}, regisztráció nélkül).
 *
 * <p>Az archívumban lánconként három CSV van: {@code stores.csv} (boltok), {@code products.csv} (termékek: név,
 * márka, egység, kiszerelés, vonalkód) és {@code prices.csv} (boltonkénti ár, akciós ár, 30 napos legalacsonyabb ár,
 * „sidrena” – 2025. május 2-i – ár, elérhetőség). Ebből lánconként és termékenként ugyanazt állítjuk elő, amit a
 * GVH Árfigyelő ad: a lánc legolcsóbb és legdrágább boltjának árát, hány boltban kapható és hány boltja van.
 * Ahol a boltok jelentős részében akciós ár van, az a lánc saját akciójaként („Bolti akció”) jelenik meg.
 *
 * <p>A napi archívum nagy (~140 MB), ezért naponta legfeljebb egyszer tölti le, és csak akkor, ha valaki a
 * horvát árakat kéri; a letöltött fájlt a {@code data/hr/} mappában tartja (net nélkül is megmarad).
 * {@code CIJENE_FILE}-lal egy kézzel letöltött archívum is megadható.
 */
public final class CijeneArchiveProvider implements PriceProvider {

    private final MarketDef def;
    private final String listUrl;
    private final Path cacheDir;
    private final Path localFile;
    private final AtomicLong done = new AtomicLong();
    private final AtomicLong total = new AtomicLong();
    private volatile String phase = "";

    public CijeneArchiveProvider(MarketDef def, String listUrl, Path cacheDir, Path localFile) {
        this.def = def;
        this.listUrl = listUrl;
        this.cacheDir = cacheDir;
        this.localFile = localFile;
    }

    /** A letöltés / feldolgozás állása (0..1), a felület töltőképernyőjéhez. */
    public double progress() {
        long t = total.get();
        return t <= 0 ? 0 : Math.min(1, done.get() / (double) t);
    }

    public String phase() {
        return phase;
    }

    @Override
    public Snapshot load() throws Exception {
        Path zip;
        if (localFile != null) {
            if (!Files.exists(localFile)) throw new IOException("Nincs ilyen fájl: " + localFile);
            zip = localFile;
        } else {
            zip = fetch();
        }
        return parse(zip);
    }

    // ---------- letöltés ----------

    private Path fetch() throws IOException, InterruptedException {
        Files.createDirectories(cacheDir);
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
        String date, url;
        long size;
        try {
            phase = "A legfrissebb horvát árlista keresése…";
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(listUrl))
                    .timeout(Duration.ofSeconds(30)).header("User-Agent", "SporolosKonyha/1.0").GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (r.statusCode() != 200) throw new IOException("HTTP " + r.statusCode());
            List<Object> archives = Json.arr(Json.obj(Json.parse(r.body())).get("archives"));
            if (archives.isEmpty()) throw new IOException("Az archívumlista üres");
            Map<String, Object> latest = null;
            for (Object o : archives) {
                Map<String, Object> a = Json.obj(o);
                if (latest == null || Json.str(a.get("date")).compareTo(Json.str(latest.get("date"))) > 0) latest = a;
            }
            date = Json.str(latest.get("date"));
            url = Json.str(latest.get("url"));
            size = (long) Json.num(latest.get("size"), 0);
            if (!date.matches("\\d{4}-\\d{2}-\\d{2}")) throw new IOException("Hibás dátum a listában: " + date);
        } catch (IOException e) {
            Path cached = newestCached();
            if (cached != null) {
                System.err.println("[árforrás] A cijene.dev nem érhető el (" + e.getMessage() + "), a legutóbbi letöltött archívum marad: " + cached.getFileName());
                return cached;
            }
            throw new IOException("A cijene.dev archívumlistája nem érhető el: " + e.getMessage(), e);
        }
        Path target = cacheDir.resolve("cijene-" + date + ".zip");
        if (Files.exists(target) && (size <= 0 || Files.size(target) == size)) return target;

        phase = "A horvát árlista letöltése (" + date + ")…";
        Path tmp = cacheDir.resolve("cijene-" + date + ".zip.part");
        HttpResponse<InputStream> r = http.send(HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(30)).header("User-Agent", "SporolosKonyha/1.0").GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());
        if (r.statusCode() != 200) {
            r.body().close();
            Path cached = newestCached();
            if (cached != null) return cached;
            throw new IOException("A napi archívum letöltése nem sikerült: HTTP " + r.statusCode());
        }
        total.set(size > 0 ? size : r.headers().firstValueAsLong("Content-Length").orElse(0));
        done.set(0);
        try (InputStream in = r.body(); OutputStream out = Files.newOutputStream(tmp)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                done.addAndGet(n);
            }
        }
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        // a régebbi archívumok törlése (a mostani marad, net nélkül ez szolgál)
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(cacheDir, "cijene-*.zip")) {
            for (Path p : ds) if (!p.equals(target)) Files.deleteIfExists(p);
        }
        return target;
    }

    private Path newestCached() throws IOException {
        if (!Files.isDirectory(cacheDir)) return null;
        Path best = null;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(cacheDir, "cijene-*.zip")) {
            for (Path p : ds) if (best == null || p.getFileName().toString().compareTo(best.getFileName().toString()) > 0) best = p;
        }
        return best;
    }

    // ---------- feldolgozás ----------

    /** Egy termék összesítése egy láncban, a boltjai árai alapján. */
    private static final class Agg {
        int stores, specials;
        double min = Double.MAX_VALUE, max = 0, regularMax = 0, specialMin = Double.MAX_VALUE, anchor = 0;
    }

    private record Product(String id, String barcode, String name, String category, String unit, double qty,
                           boolean byWeight) {}

    private static final Pattern DATE_IN_NAME = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})");

    Snapshot parse(Path zipPath) throws IOException {
        LocalDate date = LocalDate.now();
        Matcher dm = DATE_IN_NAME.matcher(zipPath.getFileName().toString());
        if (dm.find()) date = LocalDate.parse(dm.group(1));

        List<PriceEntry> entries = new ArrayList<>();
        Map<String, PromoMerger.PromoInfo> promos = new HashMap<>();
        Map<String, Integer> chainsByBarcode = new HashMap<>();
        int chains = 0;
        try (ZipFile zip = new ZipFile(zipPath.toFile(), StandardCharsets.UTF_8)) {
            Map<String, Map<String, ZipEntry>> byChain = new TreeMap<>();
            Enumeration<? extends ZipEntry> en = zip.entries();
            long bytes = 0;
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory()) continue;
                String name = e.getName().replace('\\', '/');
                int slash = name.lastIndexOf('/');
                if (slash <= 0) continue;
                String file = name.substring(slash + 1);
                String dir = name.substring(0, slash);
                String chain = dir.substring(dir.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT).replace('-', '_');
                if (def.skipChains().contains(chain)) continue;
                byChain.computeIfAbsent(chain, k -> new HashMap<>()).put(file, e);
                if (file.equals("prices.csv")) bytes += Math.max(0, e.getSize());
            }
            phase = "A horvát árak feldolgozása…";
            total.set(bytes);
            done.set(0);
            List<PriceEntry> raw = new ArrayList<>();
            for (Map.Entry<String, Map<String, ZipEntry>> c : byChain.entrySet()) {
                Map<String, ZipEntry> files = c.getValue();
                if (!files.containsKey("products.csv") || !files.containsKey("prices.csv")) continue;
                int n = readChain(zip, c.getKey(), files, raw, promos);
                if (n > 0) chains++;
            }
            for (PriceEntry e : raw) chainsByBarcode.merge(e.itemId(), 1, Integer::sum);
            for (PriceEntry e : raw) {
                int cc = chainsByBarcode.getOrDefault(e.itemId(), 1);
                entries.add(cc == e.chainCount() ? e : new PriceEntry(e.itemId(), e.productName(), e.categoryId(),
                        e.categoryName(), e.storeId(), e.price(), e.maxPrice(), e.unit(), e.quantity(), e.unitPrice(),
                        cc, e.shopCount(), e.totalShops(), e.byWeight()));
            }
        }
        if (entries.isEmpty()) throw new IOException("Az archívumban nincs feldolgozható ár: " + zipPath.getFileName());
        phase = "";
        String src = "cijene.dev – a horvát láncok napi árlistái (" + chains + " lánc)";
        return new Snapshot(date, entries, src, false, promos);
    }

    private int readChain(ZipFile zip, String chain, Map<String, ZipEntry> files, List<PriceEntry> out,
                          Map<String, PromoMerger.PromoInfo> promos) throws IOException {
        Market m = def.market();
        // boltok száma
        int totalStores = 0;
        if (files.containsKey("stores.csv")) {
            try (BufferedReader r = reader(zip, files.get("stores.csv"))) {
                Csv csv = new Csv(r);
                if (csv.header() != null) while (csv.next() != null) totalStores++;
            }
        }
        // termékek
        Map<String, Product> products = new HashMap<>();
        try (BufferedReader r = reader(zip, files.get("products.csv"))) {
            Csv csv = new Csv(r);
            Map<String, Integer> h = csv.header();
            if (h == null) return 0;
            String[] row;
            while ((row = csv.next()) != null) {
                String id = csv.get(row, h, "product_id");
                String name = csv.get(row, h, "name");
                if (id == null || id.isBlank() || name == null || name.isBlank()) continue;
                Product p = product(id, csv.get(row, h, "barcode"), name.trim(), csv.get(row, h, "category"),
                        csv.get(row, h, "unit"), csv.get(row, h, "quantity"), chain);
                if (p != null) products.put(id, p);
            }
        }
        // árak boltonként → lánconként összesítve
        Map<String, Agg> aggs = new HashMap<>();
        Map<String, Double> unitPrices = new HashMap<>();
        try (BufferedReader r = reader(zip, files.get("prices.csv"))) {
            Csv csv = new Csv(r);
            Map<String, Integer> h = csv.header();
            if (h == null) return 0;
            int iPid = h.getOrDefault("product_id", -1), iPrice = h.getOrDefault("price", -1),
                    iSpecial = h.getOrDefault("special_price", -1), iAvail = h.getOrDefault("available", -1),
                    iAnchor = h.getOrDefault("anchor_price", -1), iUnit = h.getOrDefault("unit_price", -1);
            if (iPid < 0 || iPrice < 0) return 0;
            String[] row;
            while ((row = csv.next()) != null) {
                done.addAndGet(csv.lastLineLength());
                if (iPid >= row.length) continue;
                String pid = row[iPid];
                if (!products.containsKey(pid)) continue;
                if (iAvail >= 0 && iAvail < row.length && "0".equals(row[iAvail].trim())) continue;
                double price = num(row, iPrice), special = num(row, iSpecial);
                double effective = special > 0 && (price <= 0 || special < price) ? special : price;
                if (effective <= 0) continue;
                Agg a = aggs.computeIfAbsent(pid, k -> new Agg());
                a.stores++;
                a.min = Math.min(a.min, effective);
                a.max = Math.max(a.max, effective);
                if (special > 0 && price > special + 0.005) {
                    a.specials++;
                    a.specialMin = Math.min(a.specialMin, special);
                    a.regularMax = Math.max(a.regularMax, price);
                } else if (special > 0) {
                    a.specials++;
                    a.specialMin = Math.min(a.specialMin, special);
                }
                double anchor = num(row, iAnchor);
                if (anchor > 0) a.anchor = Math.max(a.anchor, anchor);
                double up = num(row, iUnit);
                if (up > 0) unitPrices.merge(pid, up, Math::min);
            }
        }
        int added = 0;
        NameRules rules = def.rules();
        for (Map.Entry<String, Agg> e : aggs.entrySet()) {
            Product p = products.get(e.getKey());
            Agg a = e.getValue();
            Product q = p;
            if (q.qty() <= 0) {
                // kiszerelés a bolti egységárból (ár / egységár), ha a lista nem adta meg
                Double up = unitPrices.get(p.id());
                if (up == null || up <= 0) continue;
                double qty = a.min / up;
                if (!(qty > 0.005 && qty < 50)) continue;
                q = new Product(p.id(), p.barcode(), p.name(), p.category(), p.unit().equals("db") ? "kg" : p.unit(), round3(qty), false);
            }
            NameRules.Rule rule = rules.classify(q.name(), q.unit(), q.qty());
            int cat = rule == null ? 0 : MarketDef.CATEGORY_BASE + rule.index();
            String catName = rule == null ? (q.category() == null || q.category().isBlank() ? "Egyéb" : pretty(q.category())) : ingredientName(rule.key());
            double price = m.toInternal(a.min), max = m.toInternal(a.max);
            String itemId = q.barcode();
            PriceEntry pe = new PriceEntry(itemId, pretty(q.name()), cat, catName, chain, price, max, q.unit(), q.qty(),
                    price / q.qty(), 1, a.stores, Math.max(totalStores, a.stores), q.byWeight());
            out.add(pe);
            added++;
            // a lánc saját akciója: a boltjai legalább harmadában akciós ár van
            if (a.specials > 0 && a.specials * 3 >= a.stores) {
                double regular = Math.max(a.regularMax, a.anchor);
                if (regular > a.specialMin + 0.005) {
                    Promo promo = new Promo("hr-" + chain + "-" + p.id(), chain, pe.productName(), null, itemId,
                            m.toInternal(a.specialMin), m.toInternal(regular), q.unit(), q.qty(), null, null, false,
                            "a lánc napi árlistája");
                    promos.put(itemId + "|" + chain, new PromoMerger.PromoInfo(promo, m.toInternal(regular)));
                }
            }
        }
        return added;
    }

    private static final Map<String, String> INGREDIENT_NAMES = new HashMap<>();

    /** Az alapanyag magyar neve (a katalógusból), a felületen ez lesz a termék „kategóriája”. */
    private static String ingredientName(String key) {
        if (INGREDIENT_NAMES.isEmpty()) {
            synchronized (INGREDIENT_NAMES) {
                if (INGREDIENT_NAMES.isEmpty()) {
                    for (Catalog.Product p : Catalog.loadDefault().products().values()) INGREDIENT_NAMES.put(p.key(), p.name());
                }
            }
        }
        return INGREDIENT_NAMES.getOrDefault(key, key);
    }

    private static BufferedReader reader(ZipFile zip, ZipEntry e) throws IOException {
        return new BufferedReader(new InputStreamReader(zip.getInputStream(e), StandardCharsets.UTF_8), 1 << 16);
    }

    private static double num(String[] row, int i) {
        if (i < 0 || i >= row.length) return 0;
        String s = row[i].trim();
        if (s.isEmpty()) return 0;
        try {
            return Double.parseDouble(s.replace(',', '.'));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static double round3(double v) {
        return Math.round(v * 1000) / 1000.0;
    }

    // ---------- kiszerelés ----------

    private static final Pattern QTY = Pattern.compile("(\\d+(?:[.,]\\d+)?)\\s*([a-zčćžšđ.]*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern WEIGHT_IN_NAME = Pattern.compile("(\\d+(?:[.,]\\d+)?)\\s*(kg|g|gr|dag|dkg|l|lit|ml|dl|cl)(?![a-zčćžšđ])", Pattern.CASE_INSENSITIVE);
    private static final Pattern PIECES_IN_NAME = Pattern.compile("(?<![\\d.,])(\\d{1,2})\\s*/\\s*1(?![\\d])|(\\d{1,2})\\s*(?:kom|kos|x)\\b", Pattern.CASE_INSENSITIVE);

    /**
     * A termék egysége (kg / l / db) és kiszerelése a lista „unit” és „quantity” mezőiből. A horvát listák ezt
     * lánconként másképp írják: „0.45 kg”, „500g”, „1.00 ko”, „10 kom”, vagy csak egy szám a „kg”/„kom” egység
     * mellé. A „kg” egységű, 1 kg-os sorok mérlegelősek (az ár kilóra szól).
     */
    static Product product(String id, String barcode, String name, String category, String unitRaw, String qtyRaw, String chain) {
        String unitCol = unitToken(unitRaw);
        double qty = 0;
        String unit = null;
        if (qtyRaw != null && !qtyRaw.isBlank()) {
            Matcher m = QTY.matcher(qtyRaw.trim());
            if (m.find()) {
                double v = parse(m.group(1));
                String u = unitToken(m.group(2));
                if (u.isEmpty()) u = unitCol;
                double[] conv = convert(v, u);
                if (conv != null) {
                    qty = conv[0];
                    unit = conv[1] == 0 ? "kg" : conv[1] == 1 ? "l" : "db";
                }
            }
        }
        if (unit == null) {
            double[] c = convert(1, unitCol);
            unit = c == null ? "db" : c[1] == 0 ? "kg" : c[1] == 1 ? "l" : "db";
        }
        boolean byWeight = false;
        String unitColNorm = unitCol;
        if (unit.equals("kg") && (unitColNorm.equals("kg")) && Math.abs(qty - 1) < 1e-9) byWeight = true;
        // darabos sor, de a névben tömeg áll (pl. „KRUH 500 g”, 1 ko): a tömeg a pontosabb kiszerelés
        if (unit.equals("db") && qty <= 1.0001) {
            Matcher w = WEIGHT_IN_NAME.matcher(name);
            double found = 0;
            String fu = null;
            while (w.find()) {
                double[] c = convert(parse(w.group(1)), unitToken(w.group(2)));
                if (c != null && c[0] > 0.005 && c[0] <= 25) {
                    found = c[0];
                    fu = c[1] == 0 ? "kg" : "l";
                }
            }
            if (fu != null) {
                unit = fu;
                qty = found;
            } else {
                Matcher pm = PIECES_IN_NAME.matcher(name);
                if (pm.find()) {
                    String g = pm.group(1) != null ? pm.group(1) : pm.group(2);
                    int pcs = Integer.parseInt(g);
                    if (pcs >= 2 && pcs <= 60) qty = pcs;
                }
                if (qty <= 0) qty = 1;
            }
        }
        if (qty <= 0 && unit.equals("db")) qty = 1;
        if (qty > 0) qty = round3(qty);
        String bc = barcode == null ? "" : barcode.replaceAll("[^0-9A-Za-z:_-]", "");
        if (bc.isEmpty() || !bc.matches("\\d{8,14}")) bc = chain + ":" + id;
        return new Product(id, bc, name, category, unit, qty, byWeight);
    }

    /** Egység-jelölés egységesítve: kg, g, dag, l, ml, dl, cl, db (darab), vagy üres. */
    static String unitToken(String raw) {
        if (raw == null) return "";
        String u = raw.toLowerCase(Locale.ROOT).replace(".", "").trim();
        return switch (u) {
            case "kg", "kgr", "kilogram" -> "kg";
            case "g", "gr", "gram", "grama" -> "g";
            case "dag", "dkg" -> "dag";
            case "l", "lit", "litra", "lt", "ltr" -> "l";
            case "ml" -> "ml";
            case "dl" -> "dl";
            case "cl" -> "cl";
            case "kom", "ko", "kos", "komad", "kut", "kutija", "pak", "pakiranje", "pc", "pcs", "par", "boca", "lim", "vre", "vrecica", "kom." -> "db";
            default -> u.startsWith("kom") ? "db" : "";
        };
    }

    /** [mennyiség, egység: 0 = kg, 1 = l, 2 = db], vagy null, ha ismeretlen az egység. */
    private static double[] convert(double v, String u) {
        return switch (u) {
            case "kg" -> new double[]{v, 0};
            case "g" -> new double[]{v / 1000, 0};
            case "dag" -> new double[]{v / 100, 0};
            case "l" -> new double[]{v, 1};
            case "ml" -> new double[]{v / 1000, 1};
            case "dl" -> new double[]{v / 10, 1};
            case "cl" -> new double[]{v / 100, 1};
            case "db" -> new double[]{v, 2};
            default -> null;
        };
    }

    private static double parse(String s) {
        try {
            return Double.parseDouble(s.replace(',', '.'));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** A csupa nagybetűs listanevek mondatszerű írása („MLIJEKO SVJ 1.5% 1 L” → „Mlijeko svj 1.5% 1 l”). */
    static String pretty(String s) {
        if (s == null) return "";
        String t = s.trim().replaceAll("\\s+", " ");
        long upper = t.chars().filter(Character::isUpperCase).count();
        long lower = t.chars().filter(Character::isLowerCase).count();
        if (upper <= lower * 2 || t.length() < 3) return t;
        String l = t.toLowerCase(Locale.forLanguageTag("hr"));
        // a mértékegységek maradnak kisbetűsek, az első betű nagy
        return Character.toUpperCase(l.charAt(0)) + l.substring(1);
    }

    // ---------- CSV ----------

    /** Egyszerű, gyors CSV-olvasó (vessző, idézőjel, idézőjelen belüli vessző és sortörés). */
    static final class Csv {
        private final BufferedReader r;
        private Map<String, Integer> header;
        private int lastLen;

        Csv(BufferedReader r) {
            this.r = r;
        }

        Map<String, Integer> header() throws IOException {
            if (header != null) return header;
            String[] h = next();
            if (h == null) return null;
            header = new HashMap<>();
            for (int i = 0; i < h.length; i++) {
                String k = h[i].trim().toLowerCase(Locale.ROOT);
                if (i == 0 && k.startsWith("﻿")) k = k.substring(1);
                header.put(k, i);
            }
            return header;
        }

        int lastLineLength() {
            return lastLen;
        }

        String get(String[] row, Map<String, Integer> h, String col) {
            Integer i = h.get(col);
            return i == null || i >= row.length ? null : row[i];
        }

        String[] next() throws IOException {
            String line = r.readLine();
            if (line == null) return null;
            lastLen = line.length() + 1;
            if (line.indexOf('"') < 0) return line.split(",", -1);
            List<String> out = new ArrayList<>();
            StringBuilder cur = new StringBuilder();
            boolean quoted = false;
            while (true) {
                for (int i = 0; i < line.length(); i++) {
                    char c = line.charAt(i);
                    if (quoted) {
                        if (c == '"') {
                            if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                                cur.append('"');
                                i++;
                            } else {
                                quoted = false;
                            }
                        } else {
                            cur.append(c);
                        }
                    } else if (c == '"') {
                        quoted = true;
                    } else if (c == ',') {
                        out.add(cur.toString());
                        cur.setLength(0);
                    } else {
                        cur.append(c);
                    }
                }
                if (!quoted) break;
                String more = r.readLine();
                if (more == null) break;
                lastLen += more.length() + 1;
                cur.append('\n');
                line = more;
            }
            out.add(cur.toString());
            return out.toArray(new String[0]);
        }
    }
}
