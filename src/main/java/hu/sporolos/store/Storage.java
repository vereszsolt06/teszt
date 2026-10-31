package hu.sporolos.store;

import hu.sporolos.model.Models.PriceEntry;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Az app tartós adatai: háztartás-profilok (beállítások, „Nom vagy nem” értékelések, a mentett hét,
 * a kipipált listatételek), napi árelőzmények, a receptfotók gyorsítótára és a kézi akciók.
 *
 * Két megvalósítás van: {@link FileStorage} (JSON/TSV fájlok a data/ mappában – alapértelmezés)
 * és {@link PgStorage} (PostgreSQL, pl. a docker-compose-ban). A DATABASE_URL dönt.
 */
public interface Storage extends AutoCloseable {

    /** "fájlok (data/)" vagy "PostgreSQL (host/db)" – a státuszban jelenik meg. */
    String describe();

    // ---------- háztartás-profil ----------

    /** A profil beállításai (JSON-objektum), vagy null, ha nincs ilyen profil. */
    Map<String, Object> profile(String id) throws IOException;

    void saveProfile(String id, Map<String, Object> settings) throws IOException;

    /** receptazonosító → true (nom) / false (nem). */
    Map<String, Boolean> ratings(String profileId) throws IOException;

    /** liked == null: értékelés törlése. */
    void rate(String profileId, String recipeId, Boolean liked) throws IOException;

    void clearRatings(String profileId) throws IOException;

    /** A mentett hét (bolt, receptek, mag, kizártak…), vagy null. */
    Map<String, Object> week(String profileId) throws IOException;

    void saveWeek(String profileId, Map<String, Object> week) throws IOException;

    /** A kipipált tételek egy bevásárlólistán (listKey: a hét azonosítója). */
    Set<String> checked(String profileId, String listKey) throws IOException;

    void setChecked(String profileId, String listKey, String item, boolean on) throws IOException;

    // ---------- árelőzmények ----------

    boolean hasPriceDay(LocalDate day) throws IOException;

    void savePriceDay(LocalDate day, List<PriceEntry> entries) throws IOException;

    /** (termékazonosító|lánc) → árak a [from, to) napokból; valamint hány nap volt. */
    PriceDays prices(LocalDate from, LocalDate toExclusive) throws IOException;

    record PriceDays(Map<String, List<Double>> prices, int days) {}

    // ---------- receptfotók ----------

    /** A tárolt fotó (vagy a "nincs fotó" bejegyzés), vagy null, ha még nem próbáltuk. */
    Photo photo(String recipeId) throws IOException;

    void savePhoto(Photo photo) throws IOException;

    /** A tárolt fotók állapota bájtok nélkül: receptazonosító → van-e kép. */
    Map<String, Boolean> photoIndex() throws IOException;

    /**
     * Egy receptfotó a forrásával és licencével. bytes == null: megpróbáltuk, de nincs szabad licencű kép
     * (fetchedAt után egy héttel újra próbáljuk).
     */
    record Photo(String recipeId, String contentType, byte[] bytes, String title, String pageUrl,
                 String author, String license, String licenseUrl, Instant fetchedAt) {
        public boolean present() {
            return bytes != null && bytes.length > 0;
        }
    }

    // ---------- kézi akciók ----------

    List<Map<String, Object>> manualPromos() throws IOException;

    void saveManualPromos(List<Map<String, Object>> promos) throws IOException;

    @Override
    void close();
}
