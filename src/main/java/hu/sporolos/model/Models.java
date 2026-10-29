package hu.sporolos.model;

import java.util.List;

/**
 * Az alkalmazás adatmodelljei egy helyen (Java rekordok).
 */
public final class Models {

    private Models() {}

    /** Egy üzletlánc. */
    /**
     * Egy üzletlánc.
     *
     * @param cardName a lánc hűségprogramjának / appjának neve (pl. "Lidl Plus"), vagy null
     */
    public record Store(String id, String name, String color, String cardName) {}

    /**
     * Egy termék ára egy üzletláncban – a GVH Árfigyelő napi Excel egy sora.
     *
     * @param itemId          termékazonosító (vonalkód); ezzel hasonlítjuk ugyanazt a terméket a láncok között
     * @param productName     terméknév
     * @param categoryId      Árfigyelő kategória-azonosító
     * @param categoryName    kategórianév
     * @param storeId         üzletlánc azonosító
     * @param price           minimum ár a láncon belül (Ft) – ennyiért kapható a legolcsóbb boltjukban
     * @param maxPrice        maximum ár a láncon belül (Ft)
     * @param unit            egység: kg, l vagy db
     * @param quantity        kiszerelés az egységben (pl. 0.5 = fél kg)
     * @param unitPrice       minimum egységár (Ft / egység)
     * @param chainCount      hány üzletláncban kapható
     * @param shopCount       hány boltban kapható ebben a láncban
     * @param totalShops      a lánc összes boltjának száma (0, ha ismeretlen)
     */
    public record PriceEntry(String itemId, String productName, int categoryId, String categoryName,
                             String storeId, double price, double maxPrice, String unit,
                             double quantity, double unitPrice, int chainCount, int shopCount,
                             int totalShops) {

        /** A lánc boltjainak hányad részében kapható (0..1); ismeretlen bolt­számnál 1. */
        public double availability() {
            if (totalShops <= 0 || shopCount <= 0) return 1.0;
            return Math.min(1.0, shopCount / (double) totalShops);
        }
    }

    /**
     * Miért jó az ár. Típusok:
     *  CHAIN    – ugyanez a termék más láncokban drágább,
     *  HISTORY  – olcsóbb, mint az elmúlt napok szokásos ára (helyi előzményekből),
     *  SPREAD   – a lánc egyes boltjaiban jóval olcsóbb, mint a legdrágábbakban (jellemzően akció),
     *  CATEGORY – egységárban a kategória legolcsóbbjai között van,
     *  PROMO    – a lánc saját akciója (akciós újság, app, hűségkártya), amit a felhasználó vagy egy feed adott meg.
     */
    public enum DealType { CHAIN, HISTORY, SPREAD, CATEGORY, PROMO }

    /**
     * Egy kiemelkedően jó ár.
     *
     * @param referencePrice amihez képest olcsó (Ft, ugyanarra a kiszerelésre vetítve)
     * @param savingPercent  0..1 megtakarítás a viszonyítási árhoz képest
     * @param score          0..1 rangsoroló pontszám
     * @param reason         ember által olvasható indoklás
     */
    public record Deal(PriceEntry entry, DealType type, double referencePrice, double savingPercent,
                       double score, String reason) {}

    /**
     * Recept hozzávaló.
     *
     * @param share  a katalógus-kiszerelés hányad része kell
     * @param pantry fűszer / kamraáru, nem árazzuk (só, bors, pirospaprika)
     * @param staple alapvető kamraáru (olaj, liszt, cukor): árazzuk, de a bevásárlólistán "ha nincs otthon" tétel
     */
    public record Ingredient(String productKey, String label, String amount, double share, boolean pantry,
                             boolean staple) {}

    /** Egy recept. */
    public record Recipe(String id, String name, String emoji, int minutes, int servings,
                         String difficulty, List<String> tags, List<Ingredient> ingredients,
                         List<String> steps) {}

    /**
     * Egy hozzávaló árazva egy adott boltban.
     *
     * @param entry        a választott bolti termék, vagy null, ha a boltban nincs / az Árfigyelő nem követi
     * @param deal         ha a választott termék épp jó áron van
     * @param cost         a receptre jutó költség (Ft) – a felhasznált mennyiség ára
     * @param estimated    true, ha a költség becsült (nincs bolti adat)
     * @param packages     hány csomagot kell venni (0 = mérlegelős áru, annyit veszel, amennyi kell)
     * @param buyAmount    összesen mennyit veszel az alapanyag egységében (kg / l / db)
     * @param checkoutCost amennyit a pénztárnál fizetsz érte (egész csomagokkal)
     * @param bulk         a választott tétel nagy (a háztartási méretnél nagyobb) kiszerelés
     * @param buyText      mit vegyél, emberi nyelven ("2 × 450 g", "kb. 25 dkg (≈ 2 db)")
     * @param priceText    mire szól az ár ("599 Ft/kg", "549 Ft/db", "449 Ft / 450 g · 998 Ft/kg")
     * @param alternative  a másik lehetőség: háztartási módban a nagy kiszerelés (ha jóval olcsóbb),
     *                     nagy kiszerelés módban a háztartási; null, ha nincs érdemi alternatíva
     */
    public record PricedIngredient(Ingredient ingredient, PriceEntry entry, Deal deal, double cost,
                                   boolean estimated, int packages, double buyAmount, double checkoutCost,
                                   boolean bulk, String buyText, String priceText, PricedIngredient alternative) {}

    /**
     * Egy recept beárazva egy boltban.
     *
     * @param totalCost        a felhasznált mennyiségek ára ebben a boltban
     * @param otherStoresCost  ugyanez a többi (választott) bolt mediánjával számolva
     * @param checkoutTotal    a bevásárlás összege egész csomagokkal, az alap kamraáruk nélkül
     * @param staplesTotal     az alap kamraáruk (olaj, liszt, cukor) ára egész csomagokkal, ha nincs otthon
     * @param coverage         a nem-kamraáru hozzávalók hányad részére van valós bolti ár (0..1)
     * @param servings         hány adagra van számolva
     * @param altTotalCost     a recept ára, ha minden alternatívát választod (háztartási módban: nagy kiszerelésekkel)
     * @param altCheckoutTotal a pénztári összeg ugyanekkor
     * @param bulkMode         nagy kiszerelés módban számolva
     */
    public record Suggestion(Recipe recipe, String storeId, List<PricedIngredient> items,
                             double totalCost, double otherStoresCost, double checkoutTotal, double staplesTotal,
                             int dealCount, double coverage, double score, int servings,
                             double altTotalCost, double altCheckoutTotal, boolean bulkMode) {
        public double savings() {
            return Math.max(0, otherStoresCost - totalCost);
        }

        Suggestion withScore(double others, double newScore) {
            return new Suggestion(recipe, storeId, items, totalCost, others, checkoutTotal, staplesTotal,
                    dealCount, coverage, newScore, servings, altTotalCost, altCheckoutTotal, bulkMode);
        }

        public Suggestion rescored(double others, double newScore) {
            return withScore(others, newScore);
        }
    }
}
