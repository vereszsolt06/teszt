package hu.sporolos.store;

import hu.sporolos.core.Json;
import hu.sporolos.model.Models.PriceEntry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Fájlos tárolás a data/ mappában – nem kell hozzá semmi telepítés.
 *
 * <pre>
 * data/profilok/{id}.json        egy háztartás: beállítások, értékelések, hét, kipipált tételek
 * data/history/ÉÉÉÉ-HH-NN.tsv    napi árak (termék, lánc, ár)
 * data/kepek-cache/{id}.json|img receptfotók és forrásuk
 * data/akciok/sajat-akciok.json  kézi akciók
 * </pre>
 */
public final class FileStorage implements Storage {

    private final Path root;
    private final Path promoFile;

    public FileStorage(Path root) {
        this(root, root.resolve("akciok").resolve("sajat-akciok.json"));
    }

    public FileStorage(Path root, Path manualPromoFile) {
        this.root = root;
        this.promoFile = manualPromoFile;
    }

    @Override
    public String describe() {
        return "fájlok (" + root + ")";
    }

    // ---------- profil ----------

    private Path profileFile(String id) {
        return root.resolve("profilok").resolve(Ids.check(id) + ".json");
    }

    private Map<String, Object> doc(String id) throws IOException {
        Path f = profileFile(id);
        if (!Files.exists(f)) return null;
        return Json.obj(Json.parse(Files.readString(f, StandardCharsets.UTF_8)));
    }

    private Map<String, Object> docOrNew(String id) throws IOException {
        Map<String, Object> d = doc(id);
        return d == null ? new LinkedHashMap<>() : new LinkedHashMap<>(d);
    }

    private void writeDoc(String id, Map<String, Object> d) throws IOException {
        write(profileFile(id), Json.write(d));
    }

    @Override
    public synchronized Map<String, Object> profile(String id) throws IOException {
        Map<String, Object> d = doc(id);
        return d == null ? null : Json.obj(d.getOrDefault("settings", Map.of()));
    }

    @Override
    public synchronized void saveProfile(String id, Map<String, Object> settings) throws IOException {
        Map<String, Object> d = docOrNew(id);
        d.put("settings", settings);
        d.put("updated", Instant.now().toString());
        writeDoc(id, d);
    }

    @Override
    public synchronized Map<String, Boolean> ratings(String profileId) throws IOException {
        Map<String, Object> d = doc(profileId);
        Map<String, Boolean> out = new LinkedHashMap<>();
        if (d != null) Json.obj(d.getOrDefault("ratings", Map.of())).forEach((k, v) -> out.put(k, Boolean.TRUE.equals(v)));
        return out;
    }

    @Override
    public synchronized void rate(String profileId, String recipeId, Boolean liked) throws IOException {
        Map<String, Object> d = docOrNew(profileId);
        Map<String, Object> r = new LinkedHashMap<>(Json.obj(d.getOrDefault("ratings", Map.of())));
        if (liked == null) r.remove(recipeId); else r.put(recipeId, liked);
        d.put("ratings", r);
        writeDoc(profileId, d);
    }

    @Override
    public synchronized void clearRatings(String profileId) throws IOException {
        Map<String, Object> d = docOrNew(profileId);
        d.put("ratings", Map.of());
        writeDoc(profileId, d);
    }

    @Override
    public synchronized Map<String, Object> week(String profileId) throws IOException {
        Map<String, Object> d = doc(profileId);
        return d == null || d.get("week") == null ? null : Json.obj(d.get("week"));
    }

    @Override
    public synchronized void saveWeek(String profileId, Map<String, Object> week) throws IOException {
        Map<String, Object> d = docOrNew(profileId);
        d.put("week", week);
        writeDoc(profileId, d);
    }

    @Override
    public synchronized Set<String> checked(String profileId, String listKey) throws IOException {
        Map<String, Object> d = doc(profileId);
        Set<String> out = new LinkedHashSet<>();
        if (d != null) {
            Object l = Json.obj(d.getOrDefault("checked", Map.of())).get(listKey);
            if (l != null) for (Object o : Json.arr(l)) out.add(Json.str(o));
        }
        return out;
    }

    @Override
    public synchronized void setChecked(String profileId, String listKey, String item, boolean on) throws IOException {
        Map<String, Object> d = docOrNew(profileId);
        // csak az aktuális lista pipái maradnak meg, a régi heteké nem kell
        Map<String, Object> all = new LinkedHashMap<>();
        Set<String> set = new LinkedHashSet<>();
        Object prev = Json.obj(d.getOrDefault("checked", Map.of())).get(listKey);
        if (prev != null) for (Object o : Json.arr(prev)) set.add(Json.str(o));
        if (on) set.add(item); else set.remove(item);
        all.put(listKey, new ArrayList<>(set));
        d.put("checked", all);
        writeDoc(profileId, d);
    }

    // ---------- árelőzmények ----------

    private Path historyDir() {
        return root.resolve("history");
    }

    @Override
    public synchronized boolean hasPriceDay(LocalDate day) {
        return Files.exists(historyDir().resolve(day + ".tsv"));
    }

    @Override
    public synchronized void savePriceDay(LocalDate day, List<PriceEntry> entries) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (PriceEntry e : entries) {
            sb.append(e.itemId()).append('\t').append(e.storeId()).append('\t').append(e.price()).append('\n');
        }
        write(historyDir().resolve(day + ".tsv"), sb.toString());
    }

    @Override
    public synchronized PriceDays prices(LocalDate from, LocalDate toExclusive) throws IOException {
        Map<String, List<Double>> past = new HashMap<>();
        int days = 0;
        if (!Files.isDirectory(historyDir())) return new PriceDays(past, 0);
        List<Path> files;
        try (Stream<Path> s = Files.list(historyDir())) {
            files = s.filter(p -> p.getFileName().toString().endsWith(".tsv")).filter(p -> {
                try {
                    LocalDate d = LocalDate.parse(p.getFileName().toString().replace(".tsv", ""));
                    return !d.isBefore(from) && d.isBefore(toExclusive);
                } catch (RuntimeException ex) {
                    return false;
                }
            }).toList();
        }
        for (Path f : files) {
            days++;
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                String[] c = line.split("\t");
                if (c.length < 3) continue;
                try {
                    past.computeIfAbsent(c[0] + "|" + c[1], k -> new ArrayList<>()).add(Double.parseDouble(c[2]));
                } catch (NumberFormatException ignored) {
                    // sérült sor
                }
            }
        }
        return new PriceDays(past, days);
    }

    // ---------- fotók ----------

    private Path photoDir() {
        return root.resolve("kepek-cache");
    }

    @Override
    public synchronized Photo photo(String recipeId) throws IOException {
        Path meta = photoDir().resolve(Ids.check(recipeId) + ".json");
        if (!Files.exists(meta)) return null;
        Map<String, Object> m = Json.obj(Json.parse(Files.readString(meta, StandardCharsets.UTF_8)));
        Path img = photoDir().resolve(recipeId + ".img");
        byte[] bytes = Files.exists(img) ? Files.readAllBytes(img) : null;
        return new Photo(recipeId, str(m, "contentType"), bytes, str(m, "title"), str(m, "pageUrl"),
                str(m, "author"), str(m, "license"), str(m, "licenseUrl"),
                m.get("fetchedAt") == null ? Instant.EPOCH : Instant.parse(Json.str(m.get("fetchedAt"))));
    }

    @Override
    public synchronized void savePhoto(Photo p) throws IOException {
        String id = Ids.check(p.recipeId());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("contentType", p.contentType());
        m.put("title", p.title());
        m.put("pageUrl", p.pageUrl());
        m.put("author", p.author());
        m.put("license", p.license());
        m.put("licenseUrl", p.licenseUrl());
        m.put("fetchedAt", p.fetchedAt().toString());
        Path img = photoDir().resolve(id + ".img");
        if (p.present()) {
            Files.createDirectories(photoDir());
            Path tmp = photoDir().resolve(id + ".img.tmp");
            Files.write(tmp, p.bytes());
            Files.move(tmp, img, StandardCopyOption.REPLACE_EXISTING);
        } else {
            Files.deleteIfExists(img);
        }
        write(photoDir().resolve(id + ".json"), Json.write(m));
    }

    @Override
    public synchronized Map<String, Boolean> photoIndex() throws IOException {
        Map<String, Boolean> out = new HashMap<>();
        if (!Files.isDirectory(photoDir())) return out;
        try (Stream<Path> s = Files.list(photoDir())) {
            for (Path p : s.toList()) {
                String n = p.getFileName().toString();
                if (n.endsWith(".json")) {
                    String id = n.substring(0, n.length() - 5);
                    out.put(id, Files.exists(photoDir().resolve(id + ".img")));
                }
            }
        }
        return out;
    }

    // ---------- kézi akciók ----------

    @Override
    public synchronized List<Map<String, Object>> manualPromos() throws IOException {
        List<Map<String, Object>> list = new ArrayList<>();
        if (promoFile != null && Files.exists(promoFile)) {
            for (Object o : Json.arr(Json.parse(Files.readString(promoFile, StandardCharsets.UTF_8)))) list.add(Json.obj(o));
        }
        return list;
    }

    @Override
    public synchronized void saveManualPromos(List<Map<String, Object>> list) throws IOException {
        if (promoFile == null) throw new IOException("Nincs beállítva az akciók mappája");
        StringBuilder sb = new StringBuilder("[\n");
        for (int i = 0; i < list.size(); i++) {
            sb.append("  ").append(Json.write(list.get(i))).append(i < list.size() - 1 ? ",\n" : "\n");
        }
        sb.append("]\n");
        write(promoFile, sb.toString());
    }

    // ---------- segédek ----------

    private static void write(Path f, String content) throws IOException {
        Files.createDirectories(f.getParent());
        Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
    }

    private static String str(Map<String, Object> m, String k) {
        return m.get(k) == null ? null : Json.str(m.get(k));
    }

    @Override
    public void close() {
        // nincs nyitott erőforrás
    }
}
