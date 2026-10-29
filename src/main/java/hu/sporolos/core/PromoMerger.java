package hu.sporolos.core;

import hu.sporolos.data.Catalog;
import hu.sporolos.data.Promo;
import hu.sporolos.data.Stats;
import hu.sporolos.model.Models.PriceEntry;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A láncok saját akcióinak összefésülése az Árfigyelő napi áraival.
 *
 * Szabályok:
 * <ul>
 *   <li>csak a ma érvényes akció számít; a hűségkártyás/appos akció csak akkor, ha a felhasználó jelezte,
 *       hogy van ilyen kártyája/appja;</li>
 *   <li>ha az akció egy vonalkódhoz tartozik, és az Árfigyelő ugyanabban a láncban már ennyiért vagy olcsóbban
 *       mutatja, az akció <b>már benne van</b> – nem számoljuk kétszer;</li>
 *   <li>ha drágábban mutatja, az akciós ár felülírja (a régi ár lesz a viszonyítás);</li>
 *   <li>alapanyaghoz kötött akciónál: ha a láncban van olyan Árfigyelő-tétel, aminek a neve tartalmazza az akciós
 *       termék nevének szavait, és legfeljebb ennyibe kerül, már benne van; különben új tételként kerül be.</li>
 * </ul>
 */
public final class PromoMerger {

    private PromoMerger() {}

    public enum Status {
        ACTIVE("beépítve"),
        ALREADY_LISTED("már benne van az Árfigyelő áraiban"),
        NOT_STARTED("még nem kezdődött el"),
        EXPIRED("lejárt"),
        CARD_OFF("hűségkártyás/appos ár – a beállításokban kapcsold be, ha van ilyened"),
        NO_MATCH("nem ismert bolt, termék vagy alapanyag");

        public final String label;

        Status(String label) {
            this.label = label;
        }
    }

    /** Egy beépített akció adatai a jóár-elemzéshez. */
    public record PromoInfo(Promo promo, double referencePrice) {}

    /** Egy akció állapota a felületnek. */
    public record PromoStatus(Promo promo, Status status, String matchedName) {}

    /**
     * @param entries          az Árfigyelő árai az akciókkal kiegészítve / felülírva
     * @param forcedIngredient új (szintetikus) tételek vonalkódja → alapanyag-kulcs
     * @param promoInfo        "vonalkód|lánc" → akció
     * @param statuses         minden akció állapota
     */
    public record Result(List<PriceEntry> entries, Map<String, String> forcedIngredient,
                         Map<String, PromoInfo> promoInfo, List<PromoStatus> statuses) {}

    public static Result merge(Catalog catalog, List<PriceEntry> base, List<Promo> promos,
                               LocalDate today, Set<String> cardStores) {
        List<PriceEntry> entries = new ArrayList<>(base);
        Map<String, Integer> pos = new HashMap<>();          // vonalkód|lánc → index
        Map<String, PriceEntry> anyStore = new HashMap<>();  // vonalkód → bármelyik láncbeli tétel
        for (int i = 0; i < entries.size(); i++) {
            PriceEntry e = entries.get(i);
            pos.put(e.itemId() + "|" + e.storeId(), i);
            anyStore.putIfAbsent(e.itemId(), e);
        }
        Map<String, String> forced = new LinkedHashMap<>();
        Map<String, PromoInfo> info = new HashMap<>();
        List<PromoStatus> statuses = new ArrayList<>();

        for (Promo p : promos) {
            if (!catalog.stores().containsKey(p.storeId())) {
                statuses.add(new PromoStatus(p, Status.NO_MATCH, null));
                continue;
            }
            if (p.validFrom() != null && today.isBefore(p.validFrom())) {
                statuses.add(new PromoStatus(p, Status.NOT_STARTED, null));
                continue;
            }
            if (p.validTo() != null && today.isAfter(p.validTo())) {
                statuses.add(new PromoStatus(p, Status.EXPIRED, null));
                continue;
            }
            if (p.card() && (cardStores == null || !cardStores.contains(p.storeId()))) {
                statuses.add(new PromoStatus(p, Status.CARD_OFF, null));
                continue;
            }
            String label = p.card() ? cardName(catalog, p.storeId()) + " ár" : null;

            // 1. vonalkód alapján
            if (p.itemId() != null) {
                Integer idx = pos.get(p.itemId() + "|" + p.storeId());
                if (idx != null) {
                    PriceEntry old = entries.get(idx);
                    if (old.price() <= p.price() + 0.5) {
                        statuses.add(new PromoStatus(p, Status.ALREADY_LISTED, old.productName()));
                        continue;
                    }
                    double ref = p.regularPrice() > 0 ? p.regularPrice() : old.price();
                    PriceEntry promoEntry = new PriceEntry(old.itemId(), withLabel(old.productName(), label),
                            old.categoryId(), old.categoryName(), old.storeId(), p.price(),
                            Math.max(old.maxPrice(), ref), old.unit(), old.quantity(), p.price() / old.quantity(),
                            old.chainCount(), old.shopCount(), old.totalShops());
                    entries.set(idx, promoEntry);
                    info.put(old.itemId() + "|" + old.storeId(), new PromoInfo(p, ref));
                    statuses.add(new PromoStatus(p, Status.ACTIVE, old.productName()));
                    continue;
                }
                PriceEntry other = anyStore.get(p.itemId());
                if (other != null) {
                    // a termék más láncban szerepel: ugyanazzal a kiszereléssel felvesszük ebbe a láncba is
                    PriceEntry promoEntry = new PriceEntry(other.itemId(), withLabel(other.productName(), label),
                            other.categoryId(), other.categoryName(), p.storeId(), p.price(),
                            Math.max(p.price(), p.regularPrice()), other.unit(), other.quantity(),
                            p.price() / other.quantity(), other.chainCount() + 1, 0, 0);
                    add(entries, pos, promoEntry);
                    info.put(other.itemId() + "|" + p.storeId(), new PromoInfo(p, p.regularPrice() > 0 ? p.regularPrice() : Double.NaN));
                    statuses.add(new PromoStatus(p, Status.ACTIVE, other.productName()));
                    continue;
                }
            }

            // 2. alapanyag alapján
            Catalog.Product prod = resolveIngredient(catalog, p.ingredientKey());
            if (prod == null) {
                statuses.add(new PromoStatus(p, Status.NO_MATCH, null));
                continue;
            }
            PriceEntry listed = findListed(catalog, prod, entries, p);
            if (listed != null) {
                statuses.add(new PromoStatus(p, Status.ALREADY_LISTED, listed.productName()));
                continue;
            }
            int catId = prod.categoryIds().isEmpty() ? 0 : prod.categoryIds().iterator().next();
            String itemId = "promo:" + p.id();
            PriceEntry synthetic = new PriceEntry(itemId, withLabel(p.name(), label), catId, prod.name(),
                    p.storeId(), p.price(), Math.max(p.price(), p.regularPrice()), p.unit(), p.quantity(),
                    p.price() / p.quantity(), 1, 0, 0);
            add(entries, pos, synthetic);
            forced.put(itemId, prod.key());
            double ref = p.regularPrice() > 0 ? p.regularPrice() : typicalPrice(catalog, prod, base, p);
            info.put(itemId + "|" + p.storeId(), new PromoInfo(p, ref));
            statuses.add(new PromoStatus(p, Status.ACTIVE, null));
        }
        return new Result(List.copyOf(entries), Map.copyOf(forced), Map.copyOf(info), List.copyOf(statuses));
    }

    /** Alapanyag a kulcsa ("csirkemell") vagy a neve ("Csirkemellfilé") alapján, ékezet- és kisbetű-függetlenül. */
    public static Catalog.Product resolveIngredient(Catalog catalog, String keyOrName) {
        if (keyOrName == null || keyOrName.isBlank()) return null;
        Catalog.Product direct = catalog.products().get(keyOrName.trim());
        if (direct != null) return direct;
        String n = Catalog.normalize(keyOrName);
        for (Catalog.Product p : catalog.products().values()) {
            if (Catalog.normalize(p.key()).equals(n.replace(' ', '_')) || Catalog.normalize(p.name()).equals(n)) return p;
        }
        return null;
    }

    private static void add(List<PriceEntry> entries, Map<String, Integer> pos, PriceEntry e) {
        pos.put(e.itemId() + "|" + e.storeId(), entries.size());
        entries.add(e);
    }

    private static String withLabel(String name, String label) {
        return label == null ? name : name + " (" + label + ")";
    }

    private static String cardName(Catalog catalog, String storeId) {
        var s = catalog.stores().get(storeId);
        return s != null && s.store().cardName() != null ? s.store().cardName() : "kártyás";
    }

    /**
     * Van-e már a láncban ugyanez a termék legfeljebb ennyiért: a név jelentős szavai (≥ 3 betű, nem szám,
     * nem mértékegység) mind szerepelnek az Árfigyelő-tétel nevében, és az ára (azonos kiszerelésre vetítve)
     * legfeljebb 1%-kal drágább az akciósnál.
     */
    static PriceEntry findListed(Catalog catalog, Catalog.Product prod, List<PriceEntry> entries, Promo p) {
        List<String> words = significantWords(p.name());
        if (words.isEmpty()) return null;
        double promoUnit = p.price() / p.quantity();
        for (PriceEntry e : entries) {
            if (!e.storeId().equals(p.storeId()) || e.itemId().startsWith("promo:")) continue;
            if (!(prod.tracked() && catalog.matches(prod, e))) continue;
            if (!sameUnitFamily(e.unit(), p.unit())) continue;
            String n = Catalog.normalize(e.productName());
            if (!words.stream().allMatch(n::contains)) continue;
            if (e.unitPrice() <= promoUnit * 1.01) return e;
        }
        return null;
    }

    private static boolean sameUnitFamily(String a, String b) {
        boolean am = a.equals("kg") || a.equals("l"), bm = b.equals("kg") || b.equals("l");
        return a.equals(b) || (am && bm);
    }

    private static final Set<String> STOP = Set.of("ft", "kg", "dkg", "gr", "ml", "db", "csomag", "friss", "akcios", "akcio");

    static List<String> significantWords(String name) {
        return Arrays.stream(Catalog.normalize(name).split(" "))
                .filter(w -> w.length() >= 3 && !w.chars().allMatch(Character::isDigit) && !STOP.contains(w)
                        && !w.matches("\\d+(g|kg|ml|l|db)"))
                .toList();
    }

    /** Ha nincs megadva rendes ár: az alapanyag szokásos ára ebben a láncban, ugyanerre a kiszerelésre. */
    private static double typicalPrice(Catalog catalog, Catalog.Product prod, List<PriceEntry> base, Promo p) {
        List<Double> unitPrices = new ArrayList<>();
        for (PriceEntry e : base) {
            if (!e.storeId().equals(p.storeId()) || !prod.tracked() || !catalog.matches(prod, e)) continue;
            if (!sameUnitFamily(e.unit(), p.unit())) continue;
            unitPrices.add(e.unitPrice());
        }
        if (unitPrices.size() < 2) return Double.NaN;
        return Stats.median(unitPrices) * p.quantity();
    }
}
