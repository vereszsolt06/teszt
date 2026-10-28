package hu.sporolos.web;

import hu.sporolos.model.Models.Deal;
import hu.sporolos.model.Models.PriceEntry;
import hu.sporolos.model.Models.PricedIngredient;
import hu.sporolos.model.Models.Recipe;
import hu.sporolos.model.Models.Suggestion;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Modell → JSON-barát Map átalakítás a REST válaszokhoz. */
final class ApiMapper {

    private ApiMapper() {}

    static Map<String, Object> entry(PriceEntry e) {
        if (e == null) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.itemId());
        m.put("name", e.productName());
        m.put("category", e.categoryName());
        m.put("store", e.storeId());
        m.put("price", e.price());
        m.put("maxPrice", e.maxPrice());
        m.put("unit", e.unit());
        m.put("quantity", e.quantity());
        m.put("unitPrice", e.unitPrice());
        m.put("chains", e.chainCount());
        m.put("shops", e.shopCount());
        m.put("totalShops", e.totalShops());
        m.put("availability", Math.round(e.availability() * 100) / 100.0);
        return m;
    }

    static Map<String, Object> deal(Deal d, String ingredient) {
        if (d == null) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ingredient", ingredient);
        m.put("item", entry(d.entry()));
        m.put("type", d.type().name());
        m.put("referencePrice", d.referencePrice());
        m.put("savingPercent", Math.round(d.savingPercent() * 1000) / 10.0);
        m.put("score", Math.round(d.score() * 1000) / 1000.0);
        m.put("reason", d.reason());
        return m;
    }

    static Map<String, Object> dealSummary(Deal d) {
        if (d == null) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", d.type().name());
        m.put("savingPercent", Math.round(d.savingPercent() * 1000) / 10.0);
        m.put("referencePrice", d.referencePrice());
        m.put("reason", d.reason());
        return m;
    }

    static Map<String, Object> recipe(Recipe r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id());
        m.put("name", r.name());
        m.put("emoji", r.emoji());
        m.put("minutes", r.minutes());
        m.put("servings", r.servings());
        m.put("difficulty", r.difficulty());
        m.put("tags", r.tags());
        m.put("steps", r.steps());
        return m;
    }

    static Map<String, Object> suggestion(Suggestion s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("recipe", recipe(s.recipe()));
        m.put("store", s.storeId());
        m.put("servings", s.servings());
        m.put("totalCost", s.totalCost());
        m.put("otherStoresCost", s.otherStoresCost());
        m.put("savings", s.savings());
        m.put("checkoutTotal", s.checkoutTotal());
        m.put("staplesTotal", s.staplesTotal());
        m.put("dealCount", s.dealCount());
        m.put("coverage", Math.round(s.coverage() * 100) / 100.0);
        m.put("score", Math.round(s.score() * 1000) / 1000.0);
        List<Object> items = new ArrayList<>();
        for (PricedIngredient pi : s.items()) {
            Map<String, Object> i = new LinkedHashMap<>();
            i.put("label", pi.ingredient().label());
            i.put("amount", pi.ingredient().amount());
            i.put("pantry", pi.ingredient().pantry());
            i.put("staple", pi.ingredient().staple());
            i.put("cost", pi.cost());
            i.put("estimated", pi.estimated());
            i.put("packages", pi.packages());
            i.put("buyAmount", Math.round(pi.buyAmount() * 1000) / 1000.0);
            i.put("checkoutCost", pi.checkoutCost());
            i.put("item", entry(pi.entry()));
            i.put("deal", dealSummary(pi.deal()));
            items.add(i);
        }
        m.put("ingredients", items);
        return m;
    }

    /** Rövid változat listákhoz (hozzávalók nélkül). */
    static Map<String, Object> suggestionBrief(Suggestion s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("store", s.storeId());
        m.put("totalCost", s.totalCost());
        m.put("checkoutTotal", s.checkoutTotal());
        m.put("dealCount", s.dealCount());
        m.put("coverage", Math.round(s.coverage() * 100) / 100.0);
        return m;
    }
}
