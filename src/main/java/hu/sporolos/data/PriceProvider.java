package hu.sporolos.data;

import hu.sporolos.model.Models.PriceEntry;

import java.time.LocalDate;
import java.util.List;

/** Árforrás: honnan jönnek az aktuális bolti árak. */
public interface PriceProvider {

    /** Egy napi árállapot. */
    record Snapshot(LocalDate date, List<PriceEntry> entries, String source) {}

    /** Az aktuális árak betöltése. Hibánál kivételt dob, ilyenkor a tároló a következő forrásra vált. */
    Snapshot load() throws Exception;
}
