package hu.sporolos.model;

import java.util.List;

/**
 * Az alkalmazás adatmodelljei egy helyen (Java rekordok).
 */
public final class Models {

    private Models() {}

    /** Egy üzletlánc. */
    public record Store(String id, String name, String color) {}

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
     */
    public record PriceEntry(String itemId, String productName, int categoryId, String categoryName,
                             String storeId, double price, double maxPrice, String unit,
                             double quantity, double unitPrice, int chainCount, int shopCount) {}

    /**
     * Miért jó az ár. Típusok:
     *  CHAIN    – ugyanez a termék más láncokban drágább,
     *  HISTORY  – olcsóbb, mint az elmúlt napok szokásos ára (helyi előzményekből),
     *  SPREAD   – a lánc egyes boltjaiban jóval olcsóbb, mint a legdrágábbakban (jellemzően akció),
     *  CATEGORY – egységárban a kategória legolcsóbbjai között van.
     */
    public enum DealType { CHAIN, HISTORY, SPREAD, CATEGORY }

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

    /** Recept hozzávaló. share = a katalógus-kiszerelés hányad része kell; pantry = kamraáru, nem árazzuk. */
    public record Ingredient(String productKey, String label, String amount, double share, boolean pantry) {}

    /** Egy recept. */
    public record Recipe(String id, String name, String emoji, int minutes, int servings,
                         String difficulty, List<String> tags, List<Ingredient> ingredients,
                         List<String> steps) {}

    /**
     * Egy hozzávaló árazva egy adott boltban.
     *
     * @param entry     a választott bolti termék, vagy null, ha a boltban nincs / az Árfigyelő nem követi
     * @param deal      ha a választott termék épp jó áron van
     * @param cost      a receptre jutó költség (Ft)
     * @param estimated true, ha a költség becsült (nincs bolti adat)
     */
    public record PricedIngredient(Ingredient ingredient, PriceEntry entry, Deal deal, double cost, boolean estimated) {}

    /**
     * Ajánlott recept egy boltra.
     *
     * @param totalCost        a recept költsége ebben a boltban
     * @param otherStoresCost  ugyanez a recept a többi bolt mediánjával számolva
     * @param coverage         a nem-kamraáru hozzávalók hányad részére van valós bolti ár (0..1)
     */
    public record Suggestion(Recipe recipe, String storeId, List<PricedIngredient> items,
                             double totalCost, double otherStoresCost, int dealCount,
                             double coverage, double score) {
        public double savings() {
            return Math.max(0, otherStoresCost - totalCost);
        }
    }
}
