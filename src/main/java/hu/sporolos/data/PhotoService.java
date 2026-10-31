package hu.sporolos.data;

import hu.sporolos.core.Json;
import hu.sporolos.store.Storage;
import hu.sporolos.store.Storage.Photo;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Valódi ételfotók a receptekhez, szabad licencű képekből (Wikimedia Commons).
 *
 * Minden recepthez a recipes.json "photo" mezője ad néhány Wikipédia-cikket ("en:Paella", "hu:Lecsó").
 * A szolgáltatás sorban megnézi a cikkek vezetőképét; az első olyan kép nyer, ami a Commonson van, és
 * a licence szabad (CC0, CC BY, CC BY-SA, közkincs). A nem szabad („fair use”) képeket kihagyja.
 * A képet 800 px szélesre kicsinyíti, és a tárolóba menti a szerzővel és a licenccel együtt – a felület
 * ki is írja őket, ahogy a licenc megköveteli.
 *
 * Saját fotó: a data/kepek/{receptazonosító}.jpg (vagy .png / .webp) mindig elsőbbséget kap.
 *
 * A letöltés a háttérben megy (indításkor egyszer végigmegy a recepteken, lassan, hogy ne terhelje
 * a Wikimédiát); ha nincs net, a felület egyszerű, fotó nélküli kártyát mutat.
 */
public final class PhotoService {

    /** HTTP-hívás – tesztben kicserélhető. */
    public interface Http {
        Response get(String url) throws IOException;

        record Response(int status, String contentType, byte[] body) {}
    }

    private static final Pattern COMMONS_FILE = Pattern.compile("/wikipedia/commons/(?:thumb/)?[0-9a-f]/[0-9a-f]{2}/([^/]+)");
    private static final Duration RETRY_AFTER = Duration.ofDays(7);
    private static final int WIDTH = 800;

    private final Catalog catalog;
    private final Storage storage;
    private final Path overrideDir;
    private final Http http;
    private final Map<String, Boolean> index = new ConcurrentHashMap<>();
    private final Map<String, Boolean> pending = new ConcurrentHashMap<>();
    private final ExecutorService worker;
    private volatile long delayMs = 400;
    /** Egymás utáni hálózati hibák: ha sok, egy órára abbahagyja a próbálkozást (nincs net / tiltott a Wikimedia). */
    private volatile int networkFailures;
    private volatile Instant offlineUntil = Instant.EPOCH;

    /** http == null: nincs letöltés (csak a saját és a már tárolt fotók). */
    public PhotoService(Catalog catalog, Storage storage, Path overrideDir, Http http) {
        this.catalog = catalog;
        this.storage = storage;
        this.overrideDir = overrideDir;
        this.http = http;
        this.worker = http == null ? null : Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "fotok");
            t.setDaemon(true);
            return t;
        });
        try {
            if (storage != null) index.putAll(storage.photoIndex());
        } catch (IOException | RuntimeException e) {
            System.err.println("[fotók] A tárolt fotók listája nem olvasható: " + e.getMessage());
        }
    }

    /** Valódi HTTP-kliens a Wikimédiához (a Wikimedia kéri, hogy a User-Agent azonosítsa az appot). */
    public static Http defaultHttp() {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
        return url -> {
            try {
                HttpResponse<byte[]> r = client.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                        .header("User-Agent", "SporolosKonyha/1.0 (https://github.com/vereszsolt06/teszt; receptfotok)")
                        .header("Accept", "application/json, image/*").GET().build(), HttpResponse.BodyHandlers.ofByteArray());
                return new Http.Response(r.statusCode(), r.headers().firstValue("content-type").orElse(""), r.body());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("megszakítva");
            }
        };
    }

    public void setDelayMs(long ms) {
        this.delayMs = ms;
    }

    /** Van-e megjeleníthető fotó (saját vagy letöltött). */
    public boolean has(String recipeId) {
        return override(recipeId) != null || Boolean.TRUE.equals(index.get(recipeId));
    }

    /** A fotó (bájtokkal), vagy null. Ha még nem próbáltuk, a háttérben elindítja a letöltést. */
    public Photo photo(String recipeId) {
        Path own = override(recipeId);
        if (own != null) {
            try {
                String n = own.getFileName().toString().toLowerCase(Locale.ROOT);
                String type = n.endsWith(".png") ? "image/png" : n.endsWith(".webp") ? "image/webp" : "image/jpeg";
                return new Photo(recipeId, type, Files.readAllBytes(own), "saját fotó", null, null, null, null, Instant.EPOCH);
            } catch (IOException e) {
                return null;
            }
        }
        if (storage == null) return null;
        try {
            Photo p = storage.photo(recipeId);
            if (p == null || !p.present() && p.fetchedAt().plus(RETRY_AFTER).isBefore(Instant.now())) schedule(recipeId);
            return p != null && p.present() ? p : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** A fotó forrása bájtok nélkül (a recept adatlapjához). */
    public Map<String, Object> credit(String recipeId) {
        Photo p = photo(recipeId);
        if (p == null) return null;
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("title", p.title());
        m.put("pageUrl", p.pageUrl());
        m.put("author", p.author());
        m.put("license", p.license());
        m.put("licenseUrl", p.licenseUrl());
        return m;
    }

    /** Indításkor és utána 6 óránként: a hiányzó fotók letöltése a háttérben. */
    public void startBackground() {
        if (worker == null) return;
        var timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "fotok-idozito");
            t.setDaemon(true);
            return t;
        });
        timer.scheduleAtFixedRate(this::prefetchAll, 5, 6 * 3600, java.util.concurrent.TimeUnit.SECONDS);
    }

    /** A még nem próbált (vagy rég sikertelen) receptek fotóit sorban letölti a háttérben. */
    public void prefetchAll() {
        if (worker == null || storage == null) return;
        for (var r : catalog.recipes()) {
            if (override(r.id()) != null || index.containsKey(r.id()) && index.get(r.id())) continue;
            if (index.containsKey(r.id())) {
                // sikertelen volt: csak egy hét múlva próbáljuk újra
                try {
                    Photo p = storage.photo(r.id());
                    if (p != null && p.fetchedAt().plus(RETRY_AFTER).isAfter(Instant.now())) continue;
                } catch (IOException | RuntimeException e) {
                    continue;
                }
            }
            schedule(r.id());
        }
    }

    private void schedule(String recipeId) {
        if (worker == null || pending.putIfAbsent(recipeId, true) != null) return;
        worker.submit(() -> {
            try {
                fetchNow(recipeId);
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                System.err.println("[fotók] " + recipeId + ": " + e.getMessage());
            } finally {
                pending.remove(recipeId);
            }
        });
    }

    /** Megkeresi és elmenti a recept fotóját (vagy a „nincs fotó” bejegyzést). Tesztből közvetlenül hívható. */
    public Photo fetchNow(String recipeId) {
        if (http == null || storage == null) return null;
        if (Instant.now().isBefore(offlineUntil)) return null;
        Photo found = null;
        boolean networkError = false;
        for (String src : catalog.photoSources(recipeId)) {
            try {
                found = fromArticle(recipeId, src);
                if (found != null) break;
            } catch (IOException e) {
                networkError = true;
                break; // nincs net: nem jegyezzük fel „nincs fotónak”, legközelebb újra próbálja
            } catch (RuntimeException e) {
                // hibás válasz: a következő cikk
            }
        }
        if (found == null && networkError) {
            if (++networkFailures >= 3) {
                offlineUntil = Instant.now().plus(Duration.ofHours(1));
                networkFailures = 0;
                System.err.println("[fotók] A Wikimedia nem érhető el; egy óra múlva próbálom újra.");
            }
            return null;
        }
        networkFailures = 0;
        Photo save = found != null ? found
                : new Photo(recipeId, null, null, null, null, null, null, null, Instant.now());
        try {
            storage.savePhoto(save);
            index.put(recipeId, save.present());
        } catch (IOException | RuntimeException e) {
            System.err.println("[fotók] Nem sikerült menteni (" + recipeId + "): " + e.getMessage());
        }
        return found;
    }

    private Photo fromArticle(String recipeId, String source) throws IOException {
        int colon = source.indexOf(':');
        String lang = colon > 0 ? source.substring(0, colon) : "en";
        String title = colon > 0 ? source.substring(colon + 1) : source;
        if (!lang.matches("[a-z]{2,3}")) return null;
        String summaryUrl = "https://" + lang + ".wikipedia.org/api/rest_v1/page/summary/"
                + enc(title.replace(' ', '_'));
        Http.Response s = http.get(summaryUrl);
        if (s.status() == 404) return null;
        if (s.status() != 200) throw new IOException("HTTP " + s.status());
        Map<String, Object> sum = Json.obj(Json.parse(new String(s.body(), StandardCharsets.UTF_8)));
        if ("disambiguation".equals(sum.get("type"))) return null;
        String img = imageUrl(sum);
        if (img == null) return null;
        Matcher m = COMMONS_FILE.matcher(img);
        if (!m.find()) return null; // helyi (nem szabad) kép a Wikipédián
        String file = java.net.URLDecoder.decode(m.group(1), StandardCharsets.UTF_8);
        if (file.toLowerCase(Locale.ROOT).endsWith(".svg")) return null;

        Http.Response info = http.get("https://commons.wikimedia.org/w/api.php?action=query&format=json"
                + "&prop=imageinfo&iiprop=url%7Cextmetadata%7Cmime&iiurlwidth=" + WIDTH + "&titles=" + enc("File:" + file));
        if (info.status() != 200) throw new IOException("HTTP " + info.status());
        Map<String, Object> pages = Json.obj(Json.obj(Json.obj(Json.parse(new String(info.body(), StandardCharsets.UTF_8))).get("query")).get("pages"));
        if (pages.isEmpty()) return null;
        Map<String, Object> page = Json.obj(pages.values().iterator().next());
        if (page.containsKey("missing")) return null;
        List<Object> ii = Json.arr(page.get("imageinfo"));
        if (ii.isEmpty()) return null;
        Map<String, Object> i0 = Json.obj(ii.get(0));
        Map<String, Object> meta = Json.obj(i0.getOrDefault("extmetadata", Map.of()));
        String license = metaValue(meta, "LicenseShortName");
        if (!freeLicense(license, metaValue(meta, "NonFree"))) return null;
        String url = i0.get("thumburl") != null ? Json.str(i0.get("thumburl")) : Json.str(i0.get("url"));
        Http.Response bin = http.get(url);
        if (bin.status() != 200 || bin.body().length < 2000) return null;

        byte[] bytes = bin.body();
        String type = bin.contentType().split(";")[0].trim();
        byte[] resized = resize(bytes);
        if (resized != null) {
            bytes = resized;
            type = "image/jpeg";
        } else if (!type.startsWith("image/")) {
            return null;
        }
        String pageUrl = i0.get("descriptionurl") != null ? Json.str(i0.get("descriptionurl"))
                : "https://commons.wikimedia.org/wiki/File:" + enc(file.replace(' ', '_'));
        String author = stripHtml(metaValue(meta, "Artist"));
        return new Photo(recipeId, type, bytes, sum.get("title") == null ? title : Json.str(sum.get("title")), pageUrl,
                author == null || author.isBlank() ? "ismeretlen szerző" : author,
                license, metaValue(meta, "LicenseUrl"), Instant.now());
    }

    private static String imageUrl(Map<String, Object> sum) {
        for (String k : new String[]{"thumbnail", "originalimage"}) {
            Object o = sum.get(k);
            if (o instanceof Map<?, ?> m && m.get("source") != null) return String.valueOf(m.get("source"));
        }
        return null;
    }

    static boolean freeLicense(String license, String nonFree) {
        if (license == null) return false;
        if (nonFree != null && !nonFree.isBlank() && !nonFree.equalsIgnoreCase("false")) return false;
        String l = license.toLowerCase(Locale.ROOT);
        if (l.contains("fair use") || l.contains("non-free") || l.contains("nc")) return false;
        return l.startsWith("cc") || l.contains("public domain") || l.startsWith("pd") || l.contains("gfdl");
    }

    private static String metaValue(Map<String, Object> meta, String key) {
        Object o = meta.get(key);
        if (o instanceof Map<?, ?> m && m.get("value") != null) return String.valueOf(m.get("value"));
        return null;
    }

    static String stripHtml(String s) {
        if (s == null) return null;
        String t = s.replaceAll("<[^>]*>", "").replace("&amp;", "&").replace("&quot;", "\"")
                .replace("&#039;", "'").replace("&nbsp;", " ").replaceAll("\\s+", " ").trim();
        return t.length() > 120 ? t.substring(0, 117) + "…" : t;
    }

    /** JPEG/PNG kicsinyítése 800 px szélesre, JPEG-be; ha nem olvasható (pl. WebP), null. */
    static byte[] resize(byte[] data) {
        try {
            BufferedImage src = ImageIO.read(new ByteArrayInputStream(data));
            if (src == null) return null;
            int w = Math.min(WIDTH, src.getWidth());
            int h = Math.max(1, Math.round(src.getHeight() * (w / (float) src.getWidth())));
            BufferedImage dst = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = dst.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setColor(java.awt.Color.WHITE);
            g.fillRect(0, 0, w, h);
            g.drawImage(src, 0, 0, w, h, null);
            g.dispose();
            ImageWriter wr = ImageIO.getImageWritersByFormatName("jpeg").next();
            ImageWriteParam p = wr.getDefaultWriteParam();
            p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            p.setCompressionQuality(0.82f);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (MemoryCacheImageOutputStream ios = new MemoryCacheImageOutputStream(out)) {
                wr.setOutput(ios);
                wr.write(null, new IIOImage(dst, null, null), p);
            } finally {
                wr.dispose();
            }
            return out.toByteArray();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private Path override(String recipeId) {
        if (overrideDir == null || !recipeId.matches("[a-z0-9-]{1,64}")) return null;
        for (String ext : new String[]{".jpg", ".jpeg", ".png", ".webp"}) {
            Path p = overrideDir.resolve(recipeId + ext);
            if (Files.isRegularFile(p)) return p;
        }
        return null;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    public void shutdown() {
        if (worker != null) worker.shutdownNow();
    }
}
