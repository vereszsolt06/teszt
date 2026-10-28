package hu.sporolos.data;

import hu.sporolos.model.Models.PriceEntry;

import java.time.LocalDate;
import java.util.List;

/** Árforrás: honnan jönnek az aktuális bolti árak. */
public interface PriceProvider {

    /**
     * Egy napi árállapot.
     *
     * @param demo true, ha nem valós, hanem generált (demó) adat
     */
    record Snapshot(LocalDate date, List<PriceEntry> entries, String source, boolean demo) {}

    /** Az aktuális árak betöltése. Hibánál kivételt dob, ilyenkor a tároló a következő forrásra vált. */
    Snapshot load() throws Exception;
}
