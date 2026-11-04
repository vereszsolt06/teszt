package hu.sporolos.market;

import hu.sporolos.core.PriceRepository;
import hu.sporolos.data.CijeneArchiveProvider;
import hu.sporolos.data.PriceProvider;
import hu.sporolos.model.Models.Store;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A támogatott országok és árállapotuk. Magyarország mindig betöltődik induláskor; a többi ország csak akkor,
 * amikor először kérik (a horvát napi archívum ~140 MB, felesleges letölteni, ha senki nem használja).
 */
public final class Markets {

    /** Egy ország a futó appban. */
    public static final class Runtime {
        private final Market market;
        private final PriceRepository repo;
        private final MarketDef def; // null Magyarországnál

        Runtime(Market market, PriceRepository repo, MarketDef def) {
            this.market = market;
            this.repo = repo;
            this.def = def;
        }

        public Market market() { return market; }
        public PriceRepository repo() { return repo; }
        public MarketDef def() { return def; }

        /** A letöltés / feldolgozás állása (0..1), ha a forrás ismeri. */
        public double progress() {
            for (PriceProvider p : repo.providers()) if (p instanceof CijeneArchiveProvider c) return c.progress();
            return 0;
        }

        public String phase() {
            for (PriceProvider p : repo.providers()) if (p instanceof CijeneArchiveProvider c) return c.phase();
            return "";
        }

        /** A boltok azonosítói: betöltés után a ténylegesen szereplő láncok, előtte a leírás szerintiek. */
        public List<String> storeIds() {
            PriceRepository.State s = repo.peek();
            if (s != null) return s.stores().stream().map(Store::id).toList();
            if (def != null) return def.storeIds();
            return new ArrayList<>(repo.catalog().stores().keySet());
        }
    }

    /** A kért ország árai még töltődnek (a felület töltőképernyőt mutat és újrapróbálja). */
    public static final class Loading extends RuntimeException {
        private final Runtime runtime;

        public Loading(Runtime runtime, String message) {
            super(message);
            this.runtime = runtime;
        }

        public Runtime runtime() { return runtime; }
    }

    private final Map<String, Runtime> all = new LinkedHashMap<>();

    public Markets(PriceRepository hungary) {
        all.put(Market.HU.id(), new Runtime(Market.HU, hungary, null));
    }

    public void add(MarketDef def, PriceRepository repo) {
        all.put(def.market().id(), new Runtime(def.market(), repo, def));
    }

    public Runtime hungary() {
        return all.get(Market.HU.id());
    }

    /** Az ország, vagy Magyarország, ha az azonosító ismeretlen / üres. */
    public Runtime get(String id) {
        Runtime r = id == null ? null : all.get(id);
        return r == null ? hungary() : r;
    }

    public boolean has(String id) {
        return id != null && all.containsKey(id);
    }

    public Collection<Runtime> all() {
        return all.values();
    }

    /**
     * Az ország árállapota. Ha még nincs betöltve, a háttérben elindítja a betöltést, és {@link Loading}-ot dob
     * (a webes réteg ebből 503-as „töltődik” választ ad); hibánál a hiba szövegével.
     */
    public PriceRepository.State state(Runtime r) {
        PriceRepository.State s = r.repo().peek();
        if (s != null) return r.repo().get();
        if (r == hungary()) return r.repo().get();
        if (!r.repo().loading()) {
            String err = r.repo().lastError();
            if (err != null && !retryRequested(r)) throw new Loading(r, "Nem sikerült betölteni: " + err);
            r.repo().loadAsync();
        }
        throw new Loading(r, r.market().name() + " árai töltődnek…");
    }

    private final Map<String, Long> retryAt = new LinkedHashMap<>();

    /** Hiba után legfeljebb 15 másodpercenként próbálja újra (ne terhelje a forrást minden kérésnél). */
    private synchronized boolean retryRequested(Runtime r) {
        long now = System.currentTimeMillis();
        Long last = retryAt.get(r.market().id());
        if (last != null && now - last < 15_000) return false;
        retryAt.put(r.market().id(), now);
        return true;
    }

    /** Az admin módosította a közös alapanyag- és receptlistát: a többi ország levezetett katalógusa is frissül. */
    public void catalogChanged(String productsJson, String recipesJson) {
        for (Runtime r : all.values()) {
            if (r.def() == null) continue;
            r.repo().replaceCatalog(r.def().deriveCatalog(productsJson, recipesJson));
        }
    }
}
