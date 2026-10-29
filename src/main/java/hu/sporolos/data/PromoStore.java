package hu.sporolos.data;

import hu.sporolos.core.Json;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * A láncok saját akciói, három forrásból:
 * <ol>
 *   <li><b>kézi</b>: a felületen felvitt akciók ({@code <dir>/sajat-akciok.json}),</li>
 *   <li><b>fájlok</b>: minden {@code *.json} és {@code *.csv} a {@code <dir>} mappában
 *       (pl. egy akciós újságból kimásolt lista),</li>
 *   <li><b>feedek</b>: tetszőleges URL-ek, amelyek ugyanilyen JSON-t vagy CSV-t adnak
 *       (pl. egy közösen karbantartott lista vagy egy saját letöltő szkript kimenete);
 *       a legutóbbi sikeres letöltést elmenti, így net nélkül is megmarad.</li>
 * </ol>
 *
 * JSON: {@code [{"store":"lidl","name":"Csirkemellfilé","ingredient":"csirkemell","price":1499,
 * "unit":"kg","quantity":1,"regularPrice":1999,"validFrom":"2026-10-30","validTo":"2026-11-05","card":false}]}
 *
 * CSV (pontosvessző vagy vessző, első sor fejléc):
 * {@code bolt;termek;ar;egyseg;kiszereles;rendes_ar;tol;ig;alapanyag;vonalkod;kartya}
 */
public final class PromoStore {

    public static final String MANUAL_FILE = "sajat-akciok.json";
    private static final long MAX_BODY = 2_000_000;

    private final Path dir;
    private final List<String> feeds;

    public PromoStore(Path dir, List<String> feeds) {
        this.dir = dir;
        this.feeds = feeds == null ? List.of() : List.copyOf(feeds);
    }

    /** Minden akció a fájlokból és a feedekből. A hibás fájlokat/sorokat kihagyja, és a figyelmeztetésekbe írja. */
    public synchronized Loaded load() {
        List<Promo> out = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (dir != null && Files.isDirectory(dir)) {
            List<Path> files;
            try (Stream<Path> s = Files.list(dir)) {
                files = s.filter(Files::isRegularFile)
                        .filter(f -> f.toString().endsWith(".json") || f.toString().endsWith(".csv"))
                        .sorted().toList();
            } catch (IOException e) {
                files = List.of();
                warnings.add("Akciók mappája nem olvasható: " + e.getMessage());
            }
            for (Path f : files) {
                String name = f.getFileName().toString();
                String source = name.equals(MANUAL_FILE) ? "kézi" : "fájl: " + name;
                try {
                    out.addAll(parse(Files.readString(f, StandardCharsets.UTF_8), name.endsWith(".csv"), source, warnings));
                } catch (IOException | RuntimeException e) {
                    warnings.add(name + ": " + e.getMessage());
                }
            }
        }
        for (int i = 0; i < feeds.size(); i++) {
            String url = feeds.get(i);
            Path cache = dir == null ? null : dir.resolve("feeds").resolve("feed-" + Integer.toHexString(url.hashCode()) + ".cache");
            String body = null;
            try {
                body = download(url);
                if (cache != null) {
                    Files.createDirectories(cache.getParent());
                    Files.writeString(cache, body, StandardCharsets.UTF_8);
                }
            } catch (Exception e) {
                warnings.add("Akció-feed nem érhető el (" + url + "): " + e.getMessage());
                try {
                    if (cache != null && Files.exists(cache)) body = Files.readString(cache, StandardCharsets.UTF_8);
                } catch (IOException ignored) {
                    // nincs mentett példány
                }
            }
            if (body != null) {
                boolean csv = !body.stripLeading().startsWith("[") && !body.stripLeading().startsWith("{");
                out.addAll(parse(body, csv, "feed: " + url, warnings));
            }
        }
        // azonosító nélküli (fájlból jött) akciók: stabil azonosító a tartalomból
        List<Promo> withIds = new ArrayList<>();
        Map<String, Integer> seen = new HashMap<>();
        for (Promo p : out) {
            String id = p.id();
            if (id == null || id.isBlank()) {
                String base = Integer.toHexString((p.source() + p.storeId() + p.name() + p.price() + p.validFrom()).hashCode());
                int n = seen.merge(base, 1, Integer::sum);
                id = n == 1 ? base : base + "-" + n;
                p = p.withId(id, p.source());
            }
            withIds.add(p);
        }
        return new Loaded(List.copyOf(withIds), List.copyOf(warnings));
    }

    /** A betöltött akciók és a betöltés közbeni hibák. */
    public record Loaded(List<Promo> promos, List<String> warnings) {}

    /** Új kézi akció felvétele. */
    public synchronized Promo add(Promo p) throws IOException {
        List<Map<String, Object>> list = readManual();
        Promo withId = p.withId(UUID.randomUUID().toString().substring(0, 8), "kézi");
        list.add(withId.toJson());
        writeManual(list);
        return withId;
    }

    /** Kézi akció törlése; a fájlból / feedből jött akciókat csak a forrásukban lehet törölni. */
    public synchronized boolean remove(String id) throws IOException {
        List<Map<String, Object>> list = readManual();
        boolean removed = list.removeIf(m -> id.equals(String.valueOf(m.get("id"))));
        if (removed) writeManual(list);
        return removed;
    }

    /**
     * Akciók importja szövegből (JSON vagy CSV) a kézi listába.
     *
     * @return hány akció került be, és a kihagyott sorok
     */
    public synchronized Loaded importText(String text) throws IOException {
        List<String> warnings = new ArrayList<>();
        boolean csv = !text.stripLeading().startsWith("[") && !text.stripLeading().startsWith("{");
        List<Promo> parsed = parse(text, csv, "kézi", warnings);
        List<Map<String, Object>> list = readManual();
        List<Promo> added = new ArrayList<>();
        for (Promo p : parsed) {
            Promo withId = p.withId(UUID.randomUUID().toString().substring(0, 8), "kézi");
            list.add(withId.toJson());
            added.add(withId);
        }
        if (!added.isEmpty()) writeManual(list);
        return new Loaded(added, warnings);
    }

    // ---------- feldolgozás ----------

    static List<Promo> parse(String text, boolean csv, String source, List<String> warnings) {
        List<Promo> out = new ArrayList<>();
        if (csv) {
            List<Map<String, Object>> rows = csvRows(text);
            for (int i = 0; i < rows.size(); i++) {
                try {
                    out.add(Promo.fromJson(rows.get(i), source));
                } catch (IllegalArgumentException e) {
                    warnings.add(source + ", " + (i + 2) + ". sor: " + e.getMessage());
                }
            }
        } else {
            Object root = Json.parse(text);
            List<Object> arr = root instanceof Map<?, ?> m ? Json.arr(Json.obj(m).get("promos")) : Json.arr(root);
            for (int i = 0; i < arr.size(); i++) {
                try {
                    out.add(Promo.fromJson(Json.obj(arr.get(i)), source));
                } catch (IllegalArgumentException e) {
                    warnings.add(source + ", " + (i + 1) + ". elem: " + e.getMessage());
                }
            }
        }
        return out;
    }

    /** Egyszerű CSV: pontosvessző vagy vessző, idézőjelek nélkül; a fejléc mezőnevei ékezet nélkül, kisbetűvel. */
    static List<Map<String, Object>> csvRows(String text) {
        String[] lines = text.replace("﻿", "").split("\\R");
        List<Map<String, Object>> out = new ArrayList<>();
        if (lines.length == 0) return out;
        String sep = lines[0].contains(";") ? ";" : ",";
        String[] header = lines[0].split(sep, -1);
        for (int h = 0; h < header.length; h++) header[h] = Catalog.normalize(header[h]).replace(' ', '_');
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] c = line.split(sep, -1);
            Map<String, Object> m = new LinkedHashMap<>();
            for (int h = 0; h < header.length && h < c.length; h++) {
                String v = c[h].trim();
                if (!v.isEmpty()) m.put(header[h], v);
            }
            out.add(m);
        }
        return out;
    }

    private List<Map<String, Object>> readManual() throws IOException {
        if (dir == null) throw new IOException("Nincs beállítva az akciók mappája");
        Path f = dir.resolve(MANUAL_FILE);
        List<Map<String, Object>> list = new ArrayList<>();
        if (Files.exists(f)) {
            for (Object o : Json.arr(Json.parse(Files.readString(f, StandardCharsets.UTF_8)))) list.add(Json.obj(o));
        }
        return list;
    }

    private void writeManual(List<Map<String, Object>> list) throws IOException {
        Files.createDirectories(dir);
        Path f = dir.resolve(MANUAL_FILE);
        Path tmp = dir.resolve(MANUAL_FILE + ".tmp");
        StringBuilder sb = new StringBuilder("[\n");
        for (int i = 0; i < list.size(); i++) {
            sb.append("  ").append(Json.write(list.get(i))).append(i < list.size() - 1 ? ",\n" : "\n");
        }
        sb.append("]\n");
        Files.writeString(tmp, sb, StandardCharsets.UTF_8);
        Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
    }

    private static String download(String url) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
        HttpResponse<byte[]> res = client.send(HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30)).header("User-Agent", "SporolosKonyha/1.0").GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        if (res.statusCode() != 200) throw new IOException("HTTP " + res.statusCode());
        if (res.body().length > MAX_BODY) throw new IOException("túl nagy válasz");
        return new String(res.body(), StandardCharsets.UTF_8);
    }
}
