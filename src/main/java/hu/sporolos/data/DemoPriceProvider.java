package hu.sporolos.data;

import hu.sporolos.model.Models.PriceEntry;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Tartalék adatforrás, ha az Árfigyelő Excel nem érhető el (pl. nincs internet).
 * A katalógus Árfigyelő által követett alapanyagaiból 2 "márkát" generál láncokként,
 * naponta változó árakkal és alkalmi akciókkal. Ugyanazon a napon determinisztikus.
 */
public final class DemoPriceProvider implements PriceProvider {

    private static final List<String> DEMO_STORES = List.of("aldi", "lidl", "penny", "auchan", "tesco", "spar");

    private final Catalog catalog;
    private final LocalDate date;

    public DemoPriceProvider(Catalog catalog, LocalDate date) {
        this.catalog = catalog;
        this.date = date;
    }

    @Override
    public Snapshot load() {
        List<PriceEntry> out = new ArrayList<>();
        for (String storeId : DEMO_STORES) {
            Catalog.StoreInfo s = catalog.stores().get(storeId);
            double factor = s == null ? 1.0 : s.factor();
            for (Catalog.Product p : catalog.products().values()) {
                if (!p.tracked()) continue;
                int catId = p.categoryIds().iterator().next();
                for (int variant = 0; variant < 2; variant++) {
                    Random rnd = new Random(date.toEpochDay() * 1_000_003L + storeId.hashCode() * 7919L
                            + p.key().hashCode() * 31L + variant);
                    if (rnd.nextDouble() < 0.08) continue; // nem minden lánc tart mindent
                    double regular = p.basePrice() * factor * (variant == 0 ? 1.0 : 1.25)
                            * (1 + (rnd.nextDouble() - 0.5) * 0.14);
                    double price = rnd.nextDouble() < 0.13 ? regular * (1 - (0.18 + rnd.nextDouble() * 0.27)) : regular;
                    price = shelfPrice(price);
                    String brand = variant == 0 ? "Sajátmárkás" : "Prémium";
                    out.add(new PriceEntry("demo-" + p.key() + "-" + variant,
                            brand + " " + p.name().toLowerCase() + " " + formatQty(p.qty(), p.unit()),
                            catId, p.name(), storeId, price, shelfPrice(regular), p.unit(), p.qty(),
                            price / p.qty(), DEMO_STORES.size(), 50, 50));
                }
            }
        }
        return new Snapshot(date, out, "Demó árak (az Árfigyelő nem volt elérhető)", true);
    }

    static String formatQty(double qty, String unit) {
        if (unit.equals("kg") && qty < 1) return Math.round(qty * 1000) + " g";
        if (unit.equals("l") && qty < 1) return Math.round(qty * 1000) + " ml";
        return (qty == Math.rint(qty) ? String.valueOf((long) qty) : String.valueOf(qty)) + " " + unit;
    }

    /** Bolti árvégződés: 100 Ft fölött 9-re végződő tízesre kerekít (pl. 1287 → 1289). */
    static double shelfPrice(double p) {
        if (p < 100) return Math.round(p);
        return Math.round(p / 10.0) * 10 - 1;
    }
}
