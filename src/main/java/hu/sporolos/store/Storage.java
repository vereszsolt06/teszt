package hu.sporolos.store;

import hu.sporolos.model.Models.PriceEntry;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Az app tartós adatai: háztartás-profilok (beállítások, „Nom vagy nem” értékelések, a mentett hét,
 * a kipipált listatételek), napi árelőzmények és a kézi akciók.
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

    /** Egy termék napi árai lánconként a [from, to) napokból: lánc → (nap → ár), időrendben. */
    Map<String, java.util.SortedMap<LocalDate, Double>> itemHistory(String itemId, LocalDate from, LocalDate toExclusive) throws IOException;

    // ---------- kézi akciók ----------

    List<Map<String, Object>> manualPromos() throws IOException;

    void saveManualPromos(List<Map<String, Object>> promos) throws IOException;

    // ---------- dokumentumok (pl. az adminban szerkesztett receptek és alapanyagok) ----------

    /** Egy JSON-dokumentum szövege, vagy null. */
    String document(String key) throws IOException;

    void saveDocument(String key, String json) throws IOException;

    void deleteDocument(String key) throws IOException;

    /**
     * Egy dokumentum atomi módosítása (olvasás → változtatás → írás): {@code change} a jelenlegi szövegből (vagy
     * null-ból) az újat adja, null esetén a dokumentum törlődik. Két egyidejű módosítás nem írja felül egymást –
     * PostgreSQL-lel több app-példány között sem. Ha {@code change} kivételt dob, nincs írás, és a kivétel továbbmegy.
     *
     * @return az új szöveg (vagy null)
     */
    String updateDocument(String key, java.util.function.UnaryOperator<String> change) throws IOException;

    /** A háztartáshoz tartozó dokumentumok kulcs-előtagjai („extras-” + azonosító): ezek is költöznek az azonosítóval. */
    List<String> HOUSEHOLD_DOCUMENTS = List.of("extras-", "watch-");

    /**
     * A háztartás minden adata (beállítások, értékelések, hét, pipák, saját tételek, figyelt termékek) átkerül egy új azonosítóra,
     * a régi megszűnik – a közös link „visszavonása”: aki a régi linket ismeri, többé nem éri el a háztartást.
     *
     * @return false, ha nincs ilyen (régi) háztartás
     */
    boolean moveHousehold(String fromId, String toId) throws IOException;

    /** Hány háztartás-profil van (az admin áttekintéséhez). */
    int householdCount() throws IOException;

    @Override
    void close();
}
