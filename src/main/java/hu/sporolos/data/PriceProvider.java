package hu.sporolos.data;

import hu.sporolos.model.Models.PriceEntry;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Árforrás: honnan jönnek az aktuális bolti árak. */
public interface PriceProvider {

    /**
     * Egy napi árállapot.
     *
     * @param demo         true, ha nem valós, hanem generált (demó) adat
     * @param nativePromos  a forrás saját akciójelölései ("vonalkód|lánc" → akció), pl. a horvát árlisták akciós ára
     */
    record Snapshot(LocalDate date, List<PriceEntry> entries, String source, boolean demo,
                    Map<String, hu.sporolos.core.PromoMerger.PromoInfo> nativePromos) {

        public Snapshot(LocalDate date, List<PriceEntry> entries, String source, boolean demo) {
            this(date, entries, source, demo, Map.of());
        }
    }

    /** Az aktuális árak betöltése. Hibánál kivételt dob, ilyenkor a tároló a következő forrásra vált. */
    Snapshot load() throws Exception;
}
