# Spórolós Konyha 🏷️

[![Build és tesztek](https://github.com/vereszsolt06/teszt/actions/workflows/build.yml/badge.svg)](https://github.com/vereszsolt06/teszt/actions/workflows/build.yml)

Heti vacsoratervező a **GVH Árfigyelő** napi boltárai alapján, a [NomNom Planner](https://nomnomplanner.com/hu/) mintájára: egyszer megadod, hol vásárolsz, hányan esztek, mennyi a heti keret, mit nem esztek és mivel főzöl – az app összerak egy hetet valódi vacsorákból, fotóval, adagárral és tápértékkel, és egyetlen, bolti osztályok szerint rendezett bevásárlólistát ír hozzá. Bármelyik vacsora vagy az egész hét lecserélhető, a „Nom vagy nem” lapozóval megtaníthatod, mit szeretsz. A háztartás tagjai egy linkkel ugyanazt a hetet és listát látják, a kipipálás közös.

Megmaradt az eredeti ötlet is: a **Receptek** fülön az látszik, mit érdemes ma főzni abból, ami a boltodban épp kiemelkedően jó áron van, a bolti akciók felvihetők, a nem kedvelt láncok kizárhatók, és a kiszerelést (darab / kiló, háztartási vagy nagy csomag) gondosan kezeli.

Java 21, **külső függőség nélkül** (beépített JDK webszerver, saját JSON-, xlsx- és PostgreSQL-kliens), egyetlen JAR. Adatbázis nélkül fájlokba ment; `docker compose up`-pal PostgreSQL-lel fut.

![Heti terv](docs/het.png)

<img src="docs/het-mobil.png" alt="Heti terv telefonon" width="250"> <img src="docs/recept-mobil.png" alt="Receptlap telefonon" width="250"> <img src="docs/elso-inditas-mobil.png" alt="Első indítás: hol vásárolsz?" width="250">

<img src="docs/lista.png" alt="Bevásárlólista bolti osztályok szerint" width="640"> <img src="docs/nom-mobil.png" alt="Nom vagy nem" width="250">

<img src="docs/receptek.png" alt="Receptek: ma kifizetődő, most jó áron, összes recept" width="640"> <img src="docs/receptek-racs.png" alt="Az összes recept fotóval" width="640">

<img src="docs/recept.png" alt="Receptlap: fotó, adagár, makrók, hozzávalók a bolti termékkel" width="640">

> A képernyőképeket a GitHub Actions készíti a futó appról (`Képernyőképek` workflow, kézzel indítható): a fotók valódi, szabad licencű Wikimedia Commons-képek, szerzőjük és licencük a receptlapon látszik. Ahol színes kártya és emoji van, ott (még) nincs fotó. Lásd a [Képek](#7-képek-photoservice) részt.

## Indítás

Kell hozzá: **JDK 21 vagy újabb** (pl. [Adoptium Temurin](https://adoptium.net/)).

```bash
# Linux / macOS
./build.sh run

# Windows
build.cmd run
```

Ezután nyisd meg: **http://localhost:8080**

Induláskor az app letölti a napi Excelt (`https://cdnarfigyeloprodweu.azureedge.net/excel/arfigyelo_napi_termekadatok.xlsx`), és elmenti a `data/` mappába. 6 óránként a háttérben újratölti. Ha nincs internet, a legutóbb letöltött példányt használja; ha az sincs, demó árakra vált (ezt a fejléc és egy sárga sáv is jelzi). Egy már betöltött valós árállapotot egy sikertelen frissítés sosem cserél demóra.

**Telefonról:** indítsd `HOST=0.0.0.0 ./build.sh run`-nal, és a telefon böngészőjében nyisd meg a gép helyi IP-címét (pl. `http://192.168.1.20:8080`). A „Hozzáadás a kezdőképernyőhöz” menüponttal alkalmazásként is elmenthető.

**Dockerrel, PostgreSQL-lel:**

```bash
docker compose up -d        # app: http://localhost:8080, adatbázis: postgres:16 a „db-adatok” kötetben
docker compose logs -f app  # napló
docker compose down         # leállítás (az adatok megmaradnak)
```

A jelszót a `DB_PASSWORD` környezeti változóval (vagy egy `.env` fájllal) állíthatod. Docker nélkül is használható a PostgreSQL: `DATABASE_URL=postgres://felhasznalo:jelszo@localhost:5432/sporolos ./build.sh run`.

Csak fordítás: `./build.sh` → `target/sporolos-konyha.jar`, ami `java -jar target/sporolos-konyha.jar`-ral indítható. A GitHub Actions minden pushnál lefordítja és teszteli a projektet, a kész JAR-t pedig letölthető artifactként csatolja. IntelliJ / Eclipse alatt a `pom.xml`-lel Maven-projektként is megnyitható (`hu.sporolos.App` a főosztály).

### Beállítások

Parancssorból (`-Dport=9000`), környezeti változóval (`PORT=9000`) vagy egy `app.properties` fájllal a munkakönyvtárban:

| Kulcs | Környezeti változó | Alapérték | Mire jó |
|---|---|---|---|
| `port` | `PORT` | `8080` | webszerver portja |
| `host` | `HOST` | `127.0.0.1` | `0.0.0.0`-val a helyi hálózatról (pl. telefonról) is elérhető |
| `source` | `SOURCE` | `auto` | `auto` / `arfigyelo` (csak valós adat) / `demo` |
| `arfigyelo.file` | `ARFIGYELO_FILE` | – | kézzel letöltött Excel útvonala; ha létezik, nem tölt le |
| `arfigyelo.url` | `ARFIGYELO_URL` | GVH CDN | a napi Excel címe |
| `data.dir` | `DATA_DIR` | `data` | letöltött Excel és árelőzmények helye |
| `refresh.hours` | `REFRESH_HOURS` | `6` | ennyi óránként tölti újra az árakat |
| `promo.dir` | `PROMO_DIR` | `data/akciok` | a láncok saját akcióinak mappája (`*.json`, `*.csv`) |
| `promo.feeds` | `PROMO_FEEDS` | – | akció-feedek URL-jei vesszővel elválasztva (ugyanilyen JSON/CSV) |
| `database.url` | `DATABASE_URL` | – | `postgres://felh:jelszo@host:5432/db`; ha üres, a `data/` mappába ment |
| `photos` | `PHOTOS` | `on` | `off`: nem tölt le fotót a Wikimedia Commonsról |
| `photo.dir` | `PHOTO_DIR` | `data/kepek` | saját fotók: `{receptazonosító}.jpg` / `.png` / `.webp`, ezek mindig elsőbbséget kapnak |

## Mit tud

- **Első indítás (kb. egy perc):** bolt → hányan esztek, hány vacsora → heti keret → diéta (húsmentes, gluténmentes, laktózmentes) → konyha (tűzhely, sütő, airfryer). Utána rögtön kész a hét. Minden később a Beállításokban módosítható.
- **Hét:** „Ez a hét ≈ 6 894 Ft”, alatta a keret-sáv („keret 20 000 Ft · 13 106 Ft marad”), átlagos kalória és fehérje. A vacsorák fotós kártyákon: nap, név, adagár, adagszám, idő, kalória, fehérje, hangulat- és diéta-címkék. Minden kártyán **Csere** (a többi nap marad), felül **Új hetet kérek**. A hét mentve marad, amíg a beállításokat nem változtatod.
- **Receptlap:** nagy fotó (szerzővel és licenccel), adagár polccímkén, adagszám-állító, kalória / fehérje / szénhidrát / zsír, hozzávalók a konkrét bolti termékkel, árral és a „jó ár” okával, nagy kiszerelés alternatívaként („Ezt veszem”), elkészítés lépésenként, és hogy mennyibe kerülne a többi boltodban. Innen egy recept bármelyik napra betehető a hétre, vagy kedvencnek jelölhető.
- **Lista:** a hét összes hozzávalója egy listán, bolti osztályok szerint (zöldség, pékáru, hús, felvágott és sajt, tejtermék, száraz áru, konzerv, mélyhűtött), egész csomagokkal; soronként, melyik vacsorához kell és mennyi marad. Kipipálható (a háztartás minden eszközén látszik), megosztható / másolható. A „ha nincs otthon” kamraáru és a fűszerek külön.
- **Nom vagy nem:** kártyás lapozó (húzás, gombok, ← / →). A *Nom* receptek előnyt kapnak a tervben, a *Nem* receptek kimaradnak. Kedvencek listája.
- **Receptek:** „Ma kifizetődő” (az eredeti ötlet: ami ma a boltodban jó áron összevásárolható), „Most jó áron” termékek kereséssel, és mind a 170 recept fotóval, adagárral, kalóriával; szűrés fehérjedús / könnyed / laktató / diéta / gyors / airfryer / kedvencek szerint, rendezés ár, „ma a legjobb ár”, idő, fehérje, kalória szerint.
- **Bolt** a fejlécben: egy koppintással váltható; a „ma itt a legjobb” jelzés azt mutatja, hol a legkedvezőbbek ma a receptek.
- **Beállítások:** háztartás, keret, diéta, konyha, **boltjaim** (a nem kedvelt lánc kizárható), **kiszerelés** (háztartási vagy nagy), **hűségkártyák**, **bolti akciók** felvitele (kézzel vagy CSV/JSON), **közös háztartás** link, árfrissítés.
- **Közös háztartás:** a Beállításokban kapott linkkel (`…/#profil=…`) a párod vagy a családod ugyanazt a profilt használja: ugyanaz a hét, ugyanaz a lista, közös pipák.
- **Termékrészletek:** bármelyik termékre koppintva látod az árát minden láncban, hány boltjukban kapható, és mennyi a legdrágább boltjukban.

## Hogyan működik

### 1. Adatok – mit tartalmaz pontosan az Árfigyelő Excel?
A napi fájl egyetlen munkalap, soronként egy **termék × üzletlánc** páros (a 2026-10-28-i fájlban 25 443 sor). Minden árnál ott van, melyik üzletláncra vonatkozik. Konkrét boltot (címet) viszont nem tartalmaz: a lánc **legolcsóbb és legdrágább boltjának** ára szerepel benne, és hogy a lánc hány boltjában kapható. Oszlopok: vonalkód, név, hivatalos kategória (pl. 18 = Csirkemellfilé), lánc, egység (kg / l / db), kiszerelés, minimum és maximum ár, minimum és maximum egységár, hány láncban kapható, hány boltban kapható, a lánc összes boltja.

Amit ebből az app kihoz:

- **Élelmiszer-szűrés:** csak az 1–122. kategóriát tartja meg (5 564 sor), a drogériás tételeket (sampon, pelenka) eldobja. A Rossmann, a dm és a Müller kevés élelmiszert tart, ezért a boltválasztóban nem jelennek meg; a termékek láncok közötti összevetésében viszont szerepelnek.
- **Elérhetőség:** a „Hány boltban elérhető / Üzletlánc összes boltja” arányból. A diszkontláncok (Aldi, Lidl, Penny) szinte mindent minden boltban tartanak, egységes áron. A Sparnál viszont 317 élelmiszer a boltok kevesebb mint 20%-ában kapható (jellemzően csak az Interspar tartja). Az ilyen termékeket az app alapanyagnak és jó árnak is visszafogottabban ajánlja, és kiírja, hogy „csak a boltjaik X%-ában kapható”.
- **Láncon belüli árszórás:** a Spar 102 élelmiszerénél a legolcsóbb bolti ár legalább 12%-kal a legdrágább alatt van, sokszor feleannyi. Ilyen például a Don Francesco spagetti, ami 294 Ft a legolcsóbb és 699 Ft a legdrágább boltjukban; az Auchanban 715 Ft. Ezek jellemzően a lánc aktuális akciói, ezért ez *„Akciógyanús”* jelzést kap.
- **Adathibák kezelése:** az egységárat a csomagárból számolja, mert a fájl egységár-oszlopa néhány sorban hibás (pl. egy 26 g-os teánál a csomagár szerepel benne). Néhány termék rossz kategóriában van (pl. trappista sajt a natúr joghurtok közt); ezeket kizáró kulcsszavak szűrik ki.
- Az oszlopokat a **fejléc szövege alapján** keresi meg, így egy oszlopsorrend-változás nem töri el. Az xlsx-et folyamatosan (StAX) olvassa: kb. fél másodperc, néhány tíz MB memória.

### 2. Mi számít „kiemelkedően jó árnak”?
Minden bolti árra négy jelet néz, és a legerősebbet tartja meg (`DealAnalyzer`):

| Jel | Felirat | Mikor | Súly |
|---|---|---|---|
| CHAIN | *Itt a legolcsóbb* | ugyanaz a termék (vonalkód) ebben a láncban ≥ 8%-kal olcsóbb a többi lánc mediánjánál, és itt a legolcsóbb | teljes |
| HISTORY | *Olcsóbb a szokásosnál* | ≥ 8%-kal olcsóbb, mint az elmúlt napokban (helyi előzményekből, min. 3 nap kell) | teljes |
| SPREAD | *Akciógyanús* | a lánc legolcsóbb boltjában ≥ 12%-kal olcsóbb, mint a legdrágábban | 80% |
| CATEGORY | *Tartósan olcsó* | egységárban a kategória legolcsóbb 5%-ában van, és ≥ 30%-kal a medián alatt | 40% |

Ha a termék a lánc boltjainak kevesebb mint felében kapható, a pontszám arányosan csökken. Az Excel csak az adott napot tartalmazza, ezért az app **minden nap elmenti a láncok árait** (`data/history/ÉÉÉÉ-HH-NN.tsv`). Néhány nap futás után így azt is felismeri, ha valami *épp most* olcsóbb a szokásosnál.

### 3. Receptajánlás és bevásárlás
170 hétköznapi recept van beépítve (magyaros ételek mellett tészták, wokok, curryk, burgerek, tacók, saláták, airfryeres fogások) (`src/main/resources/data/recipes.json`): levesek, főzelékek, egytálételek, sült húsok, halak, saláták, desszertek. Minden hozzávaló abszolút mennyiséggel szerepel (kg / l / db). Az alapanyagok az Árfigyelő **kategóriáihoz** vannak kötve (`products.json`), pl. „darált hús” → 85-ös kategória, „spagetti” → 50-es kategória + „spagett” a névben.

A `RecipeRecommender` minden receptet „megvásárol” minden boltban.

#### Darab vagy kiló? (`Packaging`)
Az Árfigyelő ugyanazt a fajta árut háromféleképpen adja meg, és ezeket szigorúan külön kell kezelni:

| Fajta | Példa a fájlból | Mire szól az ár | Mit veszel |
|---|---|---|---|
| **mérlegelős** | „Paprika tv lédig”, „Csirkecomb Ft/kg”, „Húsmester csirkecomb egész kg vákuumcsomagolt” | 1 kg | annyit, amennyi kell, de legalább egy darabot (1 dkg fokhagymához egy egész fejet) |
| **csomagolt** | „Zöldborsó 450 g”, „Tojás 10 db”, „TV paprika 3 db”, „Karfiol db”, „Zsemle 52 g” | a csomag | egész csomagot; 15% tűréssel (0,5 kg-hoz elég egy 450 g-os) |
| **kilós árú csomag** | „Füstölt szalonna, ca. 500g”, „Trappista sajt ca1,4kg” | 1 kg | egész csomagot, a névben szereplő tömeggel |
| **csomós** | „Sárgarépa csomós”, „Csomós petrezselyemgyökér 5 db” | egy csomó | egész csomót (kb. 50 dkg), az egységtől függetlenül |

A darab és a kiló között a katalógus átlagos darabtömegeivel vált (paprika 15 dkg, uborka 40 dkg, hagyma 12 dkg, karfiol 1 kg, zsemle 5,5 dkg). Az alapanyagok egy része **csak csomagolva kapható** (liszt, tej, tejföl, kenyér, tészta stb.). Ezeknél egy kilós egységű, tömeg nélküli sor (pl. „Penny finomliszt”, 178 Ft) egy 1 kg-os csomag, nem 3 dkg lisztre lebontható kilóár.

#### Háztartási kiszerelés, nagy kiszerelés alternatívaként
Minden alapanyagnak van egy **háztartási felső határa** (`maxPack`: burgonya 2,5 kg, tojás 15 db, liszt 1 kg, trappista 60 dkg…). Az ennél nagyobb csomag (5 kg krumpli, 30 tojás, 1,4 kg-os sajttömb) csak akkor nem számít nagy kiszerelésnek, ha a recept (pl. 20 adag) ennyit kér.

- **Alapból** a háztartási kiszerelések közül választ: a felhasznált rész ára + a kényszerűen megvett maradék 35%-a számít. A recept ára ebből jön, így egy 10 kg-os zsák nem teszi olcsóbbá a receptet.
- Ha egy nagy kiszerelés a receptre vetítve **legalább 10%-kal (és 15 Ft-tal) olcsóbb**, a bevásárlólistán alternatívaként jelenik meg. Mellette ott van, mennyivel olcsóbb a recept, és mennyivel többet fizetsz ma. A recept fejlécében egy összesítő is jelzi, mennyi lenne nagy kiszerelésekkel.
- A **„Nagy kiszerelés is jöhet”** beállítással fordítva működik: a legolcsóbb egységárat választja, a háztartási pedig az alternatíva.
- A ritkán kapható csomagokat (pl. a Tesco 5 kg-os, 981 Ft-os zsákja csak 6/197 boltjukban van) ilyenkor is hátrébb sorolja.

#### Pontozás
**45%** mennyire épül jó árakra (a jel erősségével súlyozva), **35%** mennyivel olcsóbb itt, mint a többi választott bolt mediánjával, **20%** hány hozzávalóra van valós bolti ár.

### 4. A láncok saját akciói
#### Mennyire lehetséges az integráció?
Megvizsgáltam, mi van benne az Árfigyelőben, és mi nincs:

- Az Árfigyelő **weboldala** üzletenként tárolja az árakat, és jelöli az akciót, valamint a „törzsvásárlói” árat ([GVH Árfigyelő GYIK](https://arfigyelo.gvh.hu/docs/arfigyelo_GYIK.pdf)). A **napi Excel** viszont csak láncszintű minimum/maximum árat ad, akciójelölés és hűségkártyás ár nélkül. Ha egy akció a polcárat csökkenti, az a minimum árban többnyire benne van (ezt jelzi az *Akciógyanús* jel). Az **appos és hűségkártyás árak** (Lidl Plus, Tesco Clubcard, SPAR/PENNY app) és az **újságos ajánlatok érvényessége** viszont nincs benne.
- A láncok akcióit **nyilvános, dokumentált API nem** adja. Az akciós újság PDF vagy lapozható web, az ajánlatok a láncok weboldalain és webshopjaiban (Tesco, Auchan, Spar online) belső, dokumentálatlan végpontokon érhetők el. Ezek automatikus letöltése a felhasználási feltételekbe ütközhet, és bármikor eltörhet. Erről a fejlesztői gépről ráadásul nem is elérhetők, így nem lehetett volna megbízhatóan tesztelni.

**A döntés:** az app egy **akcióréteget** kapott, ami bármilyen forrásból fogad akciókat. A lánc-specifikus letöltők így később külön, cserélhetően hozzáadhatók, a többi kódhoz nem kell nyúlni:

1. **Kézzel** a felületen („Akciók” → Új akció): bolt, alapanyag, terméknév, ár, kiszerelés, rendes ár, érvényesség, kártyás-e.
2. **Beillesztve** CSV-ből vagy JSON-ból („Több akció beillesztése”), pl. egy akciós újságból kimásolt listát. Minta: [`docs/akciok-minta.csv`](docs/akciok-minta.csv).
3. **Fájlból:** minden `*.csv` és `*.json` a `data/akciok/` mappában (a kézi akciók a `sajat-akciok.json`-ba kerülnek).
4. **Feedből:** `PROMO_FEEDS=https://…` – bármilyen URL, ami ugyanezt a JSON-t vagy CSV-t adja. Ez lehet egy közösen karbantartott lista, vagy egy saját szkript kimenete, ami egy lánc oldaláról gyűjt. A legutóbbi sikeres letöltést elmenti, így net nélkül is megmarad.

CSV-fejléc: `bolt;termek;ar;egyseg;kiszereles;rendes_ar;tol;ig;alapanyag;vonalkod;kartya`. Az `alapanyag` lehet kulcs (`csirkemell`) vagy név („Csirkemellfilé”); a `vonalkod` az Árfigyelő „Termék azonosító” oszlopa.

#### Összefésülés az Árfigyelővel (`PromoMerger`)
- **Csak a ma érvényes** akció számít (`tol`–`ig`). A lejárt és a még el nem kezdődött akciókat az app mutatja, de nem számol velük.
- **Hűségkártyás/appos** akció csak akkor számít, ha a Beállításokban bejelölted, hogy van ilyen kártyád vagy appod. A termék neve mellett jelzi is (pl. „(Lidl Plus ár)”).
- Ha az akció **vonalkódhoz** tartozik, és az Árfigyelő ugyanabban a láncban már ennyiért vagy olcsóbban mutatja, akkor **már benne van**, és nem számolja kétszer. Ha drágábban mutatja, az akciós ár felülírja, és a régi ár lesz a viszonyítás.
- Ha **alapanyaghoz** tartozik, és a láncban van olyan Árfigyelő-tétel, aminek a neve tartalmazza az akciós termék szavait, és legfeljebb ennyibe kerül, szintén „már benne van”. Különben új tételként kerül be, és azonnal használható a receptekben. Ez az Árfigyelőben nem követett alapanyagokra (tonhal, gomba, passata…) is működik.
- Az akció új, **„Bolti akció”** jóár-jelet kap (legalább 0,6 pontszám, a megtakarítással arányosan több), és az indoklásban szerepel az érvényesség és a forrás.

### 5. Heti terv (`WeeklyPlanner`)
- **Jelöltek:** a bolt beárazott receptjei, desszertek és reggelik nélkül, a diétára és az eszközökre szűrve, a „Nem” és a lecserélt receptek nélkül.
- **Mohó válogatás közös kosárral:** minden napra azt a receptet választja, amelynél a legjobb az *ajánlási pontszám (jó árak) + „rendes vacsora” érték + kedvenc-bónusz + kis véletlen − a kosárhoz adott pénztári többletköltség*.
- **„Rendes vacsora”:** a NomNom valódi hétköznapi vacsorákat ad, nem a legolcsóbb köretet. Ezért előnyt kap a fehérjedús főétel és a népszerű konyha (olasz, mexikói, ázsiai, görög, spanyol, comfort), hátrébb kerül a könnyű leves, a főzelék és a 400 kcal alatti tál. Így 2 főre 5 vacsora jellemzően 5–9 ezer Ft, nem 3 ezer krumplis tészta. A többletköltség azt számolja, mennyivel nő a **közös** lista ára: ha hétfőn már vettél egy 2 kg-os zsák krumplit, a csütörtöki krumplis tészta krumplija ingyen van. Ezért a hét receptjei maguktól is egymásra épülnek.
- **Keret:** minden napra a hátralévő keret arányos részét nézi, és tartalékot hagy a többi napra. Ha a keret túl szűk, a legolcsóbb lehetőséget veszi, és megjegyzésben jelzi.
- **Változatosság:** egy fő alapanyag (pl. csirkecomb) egyszer, legfeljebb egy leves, egy tésztaétel és két halétel egy héten.
- A „Csere” a többi napot megtartja (`keep`), a lecserélt receptet kizárja (`exclude`); az „Új hét” más véletlen magot kap, és az előző hét receptjeit kihagyja. A háztartás hete a tárolóban marad (`/api/week`), amíg a terv szempontjából fontos beállítás (bolt, létszám, keret, diéta, konyha, boltok, kiszerelés, kártyák) nem változik.

### 6. Tápérték, diéták, eszközök (`RecipeMeta`)
Minden alapanyaghoz tápérték tartozik 100 g-ra (kcal, fehérje, szénhidrát, zsír, közelítő, tipikus értékek), plusz jelölés: hús, hal, glutén, laktóz. Ebből adagonként számol:
- **Diéták:** húsmentes, ha nincs benne hús/hal; gluténmentes, ha nincs glutént tartalmazó alapanyag (liszt, tészta, kenyér…); laktózmentes, ha nincs tejtermék. A kamrából vett fűszerek nem számítanak, és a „gluténmentes” csak az alapanyagokra vonatkozik (a kész termékek nyomokban tartalmazhatják) – **orvosi diétához mindig a csomagolást kell nézni**.
- **Eszközök:** a lépések szövegéből (sütő, tepsi, fokon… illetve főz, pirít, serpenyő…). Az airfryerben is elkészíthető receptek külön jelölést kaptak.
- **Hangulat:** *fehérjedús* (≥ 30 g fehérje vagy a kalória ≥ 30%-a), *könnyed* (≤ 450 kcal), *laktató* (≥ 750 kcal vagy kiadós egytál).
- Bő olajban sütésnél az olajnak csak 20%-át számolja (ennyi marad az ételben).

### 7. Képek (`PhotoService`)
A korábbi, programból rajzolt illusztrációk kikerültek. Helyettük **valódi ételfotók**, szabad licenccel:

1. Minden recepthez a `recipes.json` `photo` mezője ad 1–3 Wikipédia-cikket, sorrendben (pl. a csirkés paellához `en:Paella`, a lecsóhoz `en:Lecsó`, `hu:Lecsó`).
2. A szerver a háttérben (indításkor és 6 óránként, lassan, hogy ne terhelje a Wikimédiát) lekéri a cikk vezetőképét a Wikipédia REST API-jából.
3. Csak a **Wikimedia Commonson** lévő képet fogadja el, és csak szabad licenccel (CC0, CC BY, CC BY-SA, közkincs). A Wikipédiára helyben feltöltött, „fair use” képeket és a nem kereskedelmi (NC) licenceket kihagyja.
4. A képet 800 px szélesre kicsinyíti (JPEG), és a tárolóba menti a **szerzővel, a licenccel és a forrásoldal linkjével** együtt. A receptlap ki is írja ezeket („Fotó: Kiss Anna, CC BY-SA 4.0 – Wikimedia Commons”), ahogy a licenc kéri.
5. Ha egyik cikkhez sincs szabad kép, „nincs fotó” bejegyzés kerül a tárolóba, és egy hét múlva próbálja újra; ha nincs net, nem jegyez fel semmit, és egy óra múlva próbálja újra.

A GitHub Actions-ben futtatott próbán mind a 170 recepthez talált szabad licencű fotót. A fotók letöltése az első indulás után a háttérben kb. 5–10 percig tart; a Wikimedia korlátozza a gyors lekérdezést, ezért a szerver lassan, receptenként kb. egy másodperces szünettel halad, és a túlterhelés miatt elutasított kéréseket később újra próbálja.

Ahol nincs fotó, a kártya egy egyszerű színes felületet mutat a recept emojijával – rajz nincs. **Saját fotó:** tedd a `data/kepek/{receptazonosító}.jpg` fájlt a helyére (pl. `data/kepek/lecso.jpg`), az mindig elsőbbséget kap. A fotók a `/img/recipe/{id}` címen érhetők el, egy hétig gyorsítótárazhatók.

### 8. Tárolás: fájl vagy PostgreSQL (`store/`)
A tartós adatok egy `Storage` felület mögött vannak, két megvalósítással:

| | `FileStorage` (alap) | `PgStorage` (`DATABASE_URL`) |
|---|---|---|
| háztartás-profil (beállítások) | `data/profilok/{id}.json` | `household` (jsonb) |
| „Nom vagy nem” értékelések | a profil fájljában | `recipe_rating` |
| mentett hét | a profil fájljában | `saved_week` (jsonb) |
| kipipált listatételek | a profil fájljában | `checked_item` |
| napi árelőzmény | `data/history/ÉÉÉÉ-HH-NN.tsv` | `price_day`, `price_history` |
| receptfotók | `data/kepek-cache/` | `recipe_photo` (bytea) |
| kézi akciók | `data/akciok/sajat-akciok.json` | `manual_promo` |

A PostgreSQL-kapcsolathoz **nincs JDBC-driver**: a `PgClient` a PostgreSQL 3-as wire protokollját beszéli (SCRAM-SHA-256 / MD5 belépés, paraméterezett lekérdezések a kiterjesztett protokollal, így nincs SQL-befecskendezés), egy kis kapcsolatkészlettel. A séma induláskor magától létrejön / frissül (`schema_version` tábla, zárolással, hogy több példány se ütközzön). TLS nincs, mert a docker-compose belső hálózatára készült; távoli adatbázishoz SSH-alagút vagy helyi hálózat ajánlott.

**Fiók nincs** (ahogy a NomNomban sincs): a háztartást egy véletlen, 20 karakteres azonosító jelöli, amit a böngésző megjegyez. Aki ismeri, látja a háztartás hetét és listáját – ezért csak annak add oda a közös linket, akivel közösen vásároltok.

### REST API

| Végpont | Leírás |
|---|---|
| `GET /api/status` | adatforrás, dátum, demó/elavult jelzés, élelmiszerláncok |
| `GET /api/suggestions?store=lidl&tag=vega&limit=12` | rangsorolt receptek egy boltra, hozzávalókkal |
| `GET /api/recipe?id=lecso&store=lidl&servings=6` | egy recept bevásárlólistával (kiszerelés-alternatívákkal), és ára a választott láncokban |
| `GET /api/deals?store=lidl&limit=30&ingredients=true` | a bolt kiemelt árai (`ingredients=true`: csak recept-alapanyagok) |
| `GET /api/item?id=<vonalkód>` | egy termék ára minden láncban |
| `GET /api/search?q=tej&store=lidl&limit=30` | keresés a termékek között, egységár szerint |
| `GET /api/ingredients` | a recept-alapanyagok listája (az akció-űrlaphoz) |
| `GET /api/plan?store=lidl&people=2&dinners=5&budget=15000&diets=vega&equipment=tuzhely,suto&liked=…&disliked=…&keep=a,_,c&exclude=…&seed=3` | heti vacsoraterv közös, osztályonként rendezett bevásárlólistával |
| `GET /api/recipes?store=lidl&diets=gluténmentes` | az összes recept képpel, tápértékkel, adagonkénti árral |
| `POST /api/profile` | új háztartás-profil (törzs: beállítások) → `{id, settings, liked, disliked}` |
| `GET` / `PUT /api/profile?id=…` | a profil lekérése / beállítások mentése |
| `POST /api/rate?id=…` | `{"recipe": "lecso", "liked": true \| false \| null}` – Nom vagy nem |
| `DELETE /api/rate?id=…` | minden értékelés törlése |
| `GET /api/week?id=…[&action=new\|swap\|set&day=2&recipe=…]` | a háztartás heti terve (mentve marad); új hét, egy nap cseréje, konkrét recept egy napra |
| `POST /api/check?id=…` | `{"key", "item", "on"}` – listatétel kipipálása |
| `GET /img/recipe/{id}` | a recept fotója (Wikimedia Commons vagy saját), 404, ha nincs |
| `GET /api/promos` | a láncok saját akciói és állapotuk (beépítve / már benne / lejárt / kártya kell…) |
| `POST /api/promos` | új akció (JSON), vagy import: `{"text": "<CSV vagy JSON>"}` |
| `DELETE /api/promos?id=…` | kézi akció törlése |
| `POST /api/refresh` | árak újratöltése |

Minden boltfüggő végpont elfogadja: `stores=aldi,lidl` (a választott boltok), `bulk=1` (nagy kiszerelés mód), `cards=lidl,tesco` (hűségkártyák).

A válaszok gzip-pel tömörítve mennek, ha a böngésző kéri.

## Projektszerkezet

```
src/main/java/hu/sporolos/
  App.java                       belépési pont, beállítások
  core/  Json                    függőség nélküli JSON olvasó/író
         DealAnalyzer            „jó ár” felismerés (5 jel + elérhetőség)
         Packaging               mérlegelős / csomagolt / csomós, darab ↔ kg, nagy kiszerelés
         RecipeRecommender       receptek beárazása, háztartási és nagy kiszerelés, rangsorolás
         WeeklyPlanner           heti vacsoraterv közös kosárral, kerettel, diétával
         PromoMerger             a láncok akcióinak összefésülése az Árfigyelővel
         PriceRepository         forrásváltás, háttérfrissítés, elemzés kártyánként
  data/  ArfigyeloExcelProvider  napi Excel letöltése / beolvasása / gyorsítótár
         XlsxReader              folyamatos xlsx-olvasó (ZIP + StAX, XXE ellen védve)
         PriceHistory            napi árelőzmények (a tárolón át)
         PhotoService            receptfotók a Wikimedia Commonsról, licenccel
         DemoPriceProvider       tartalék demó árak
         Catalog                 alapanyagok, láncok, receptek, illesztés
         RecipeMeta              tápérték, diéták, eszközök, hangulat receptenként
         Promo, PromoStore       akciók: kézi, fájl, feed; CSV/JSON
  store/ Storage                 a tartós adatok felülete
         FileStorage             fájlos tárolás (alap)
         PgStorage, PgClient     PostgreSQL: séma, kapcsolatkészlet, saját wire-protokoll kliens
         Ids                     profil-azonosítók
  model/ Models                  rekordok (PriceEntry, Deal, Recipe, Suggestion…)
  web/   WebServer, ApiMapper    REST API + statikus fájlok
         Household               a háztartás beállításai, ellenőrzés, alapértékek
src/main/resources/
  data/products.json, recipes.json
  static/index.html, app.css, app.js, favicon.svg, manifest.webmanifest
src/test/java/hu/sporolos/AllTests.java
Dockerfile, docker-compose.yml   konténer: app + postgres:16
```

## Tesztek

```bash
./build.sh test        # Windows: build.cmd test
```

30 teszt fut le:
- JSON- és xlsx-olvasás, az Árfigyelő-fejléc felismerése, egységátváltás;
- mind a négy jó ár jel, valamint az elérhetőség hatása;
- a kiszerelés-modell: tojásdoboz vagy tálca, mérlegelős áru minimum egy darabbal, „ca. 500g”, csak csomagolt liszt, darab ↔ kg (paprika, zsemle), csomós zöldség, tűrés, a nagy kiszerelés határa;
- háztartási mód nagy kiszerelés alternatívával és fordítva, valamint a boltszűrő hatása;
- akciók: CSV-beolvasás, az összefésülés minden ága (felülír / már benne / új tétel / kártya kell / lejárt / még nem / ismeretlen bolt), és hogy egy akciós tonhal árazhatóvá teszi a tonhalas tésztát;
- az ajánló rangsorolása és adagköltsége;
- receptadatok (kalória, a vega címke és a hús egyezése, eszközök), minden receptnek van fotóforrása;
- fotók egy ál-Wikimédiával: a szabad licencű Commons-kép bekerül szerzővel és licenccel, 800 px-re kicsinyítve; a nem szabad helyi képet kihagyja; hálózati hibát nem jegyez fel „nincs fotónak”; a saját fotó elsőbbséget kap;
- tárolás oda-vissza (profil, értékelés, hét, pipák, árnap, fotó, kézi akció) fájlokkal, és – ha a `PG_TEST_URL` meg van adva – PostgreSQL-lel is, párhuzamos írással és SQL-befecskendezési próbával;
- heti terv: nincs ismétlés, változatos fő alapanyag, nincs desszert, a lista összege egyezik, egy termék egy sor, diéta- és eszközszűrő, csere a többi nap megtartásával, keret, más mag más hetet ad;
- a letöltés, a gyorsítótár és a visszaesés egy helyi HTTP-szerverrel;
- a hibás frissítés kezelése (a valós adat nem cserélődik demóra);
- végponttól végpontig teszt, ami elindítja a webszervert, és végighívja az összes API-t: boltszűrő, akciók felvitele / importja / törlése, fotó-kiszolgálás, háztartás-profil létrehozása, mentett hét, csere, konkrét recept egy napra, kipipálás, értékelés, boltváltás.

A GitHub Actions a teszteket egy PostgreSQL 16 szolgáltatással együtt futtatja, utána felépíti a Docker-képet, elindítja a `docker compose`-t, és ellenőrzi, hogy az app válaszol, a profil az adatbázisba kerül, és hány receptfotót talált a Wikimedia Commonson.

Ha az `ARFIGYELO_TEST_FILE` környezeti változó egy valódi napi Excelre mutat, a teljes folyamatot azon is lefuttatja: mind a hat láncban legalább 10 árazható recept és életszerű árak kellenek. A 2026-10-28-i fájllal, PostgreSQL 16-tal mind a 30 teszt sikeres, mind a 170 recept árazható, és mind a hat láncban kijön egy 5 vacsorás, 2 fős heti terv 15 000 Ft alatt.

## Feltételezések és döntések

Ezeket kérdezés nélkül döntöttem el; bármelyik könnyen módosítható.

- **A heti terv a főképernyő**, ahogy a NomNomban; a régi „recept dobáló” a Receptek fül „Ma kifizetődő” sávja lett.
- **Profil fiók nélkül**, véletlen azonosítóval, a szerveren (fájlban vagy adatbázisban) tárolva – így működik a közös háztartás és a több eszközös kipipálás. A NomNom a terveket a telefonon tartja; böngészős appnál a szerveres tárolás a természetes.
- **Heti terv alapértékei:** 2 fő, 5 vacsora (hétfő–péntek), 15 000 Ft keret, tűzhely és sütő van. Csak vacsorát tervez; desszert és reggeli nem kerül bele. A kamra-alapanyagok (olaj, liszt, cukor) a végösszegben nincsenek benne, külön sorban szerepelnek.
- **„Nom vagy nem”:** az olcsóbb receptek jönnek előbb (kis keveréssel), az édességek a pakli végén. A kedvencek előnyt kapnak, de nem kerülnek be minden áron: az ár és a változatosság továbbra is számít.

- **Böngészős app, nem natív mobilapp.** Egyszerűbb telepíteni (egy JAR), telefonon is jól működik, és kezdőképernyőre tehető.
- **Java, külső könyvtár nélkül.** Spring Boot helyett a JDK beépített HTTP szervere: nincs függőségletöltés, és a build bárhol működik, ahol van JDK 21.
- **Az adatforrás a GVH napi Excelje.** Az `arfigyelo.gvh.hu` oldal belső API-ját nem használom, mert nem nyilvános és dokumentálatlan.
- **A „bolt” üzletláncot jelent**, mert az Excel láncszintű (minimum/maximum) árakat ad. Az app a lánc legolcsóbb boltjának árát mutatja, ezért a te boltodban lehet drágább; ezt a „máshol akár … Ft” felirat és a termékrészletek is jelzik.
- **Kétféle összeg.** A recept ára a felhasznált mennyiségre jutó rész, így a boltok összevethetők. A bevásárlólista egész csomagokkal számol: ennyit fizetsz a pénztárnál.
- **Néhány alapanyagot az Árfigyelő nem követ** (tonhal, tortilla, passata, gomba, cukkini, spenót, kókusztej, lekvár, tarhonya, zabpehely stb.). Ezek becsült árral (*) szerepelnek, és nem számítanak jó árnak. A recepteket úgy válogattam, hogy főleg követett alapanyagokra épüljenek.
- **Kamraáru:** a fűszerek (só, bors, pirospaprika) „otthon vannak”, nem árazom őket. Az olaj, a liszt és a cukor árazott, de a bevásárlólistán külön, „ha nincs otthon” tételként szerepel.
- **A „Tartósan olcsó” jel kisebb súlyú**, mert a sajátmárkás termékek szinte mindig a kategória aljára esnek. Ettől még nem „épp most” akciósak.
- **Darabtömegek és háztartási határok** átlagos értékek (paprika 15 dkg, uborka 40 dkg; burgonya legfeljebb 2,5 kg, tojás 15 db). Ezek a `products.json`-ban állíthatók (`pieceWeight`, `bunchWeight`, `maxPack`, `packaged`).
- **Nagy kiszerelés küszöbe:** a receptre vetítve legalább 10% és 15 Ft megtakarítás kell, hogy alternatívaként megjelenjen; ennél kisebb különbségért nem éri meg többet fizetni.
- **A láncok akcióit nem gyűjti automatikusan** a láncok oldalairól (lásd a 4. pontot). Helyette egy általános akcióréteg fogad kézi, fájlos és feedes akciókat. A kártyás akciók alapból ki vannak kapcsolva, amíg nem jelölöd be, hogy van ilyen kártyád.
- **Az adagár kétféle:** a hét kártyáin a közös listából a vacsorára eső rész (a megmaradó csomagrésszel együtt, így a kártyák összege a lista végösszege), a receptlapon a ténylegesen felhasznált mennyiség ára.
- **Fotók csak szabad licenccel**, a szerző és a licenc feltüntetésével; ahol nincs, nincs rajz sem, csak egy egyszerű színes kártya.
- **Betűtípusok** a Google Fontsról töltődnek; offline a rendszer betűtípusára vált, a működést nem érinti.

## Ismert korlátok, továbbfejlesztési ötletek

- Az első napokban nincs előzmény, ezért az *Olcsóbb a szokásosnál* jel kb. 3 napi futás után jelenik meg.
- Bolt (nem lánc) szintű árakat az Excel nem tartalmaz; egy térképes boltválasztóhoz az Árfigyelő belső API-ja kellene.
- Néhány zöldséget a láncok következetlenül adnak meg (pl. „csomós petrezselyemgyökér” egyszer darabra, egyszer kilóra); ezeknél az ár közelítő.
- A kiszerelés-összesítő és a soronkénti csere csak a kiválasztott boltra vonatkozik; a „többi boltodban” összevetés az alapbeállítás szerinti (háztartási vagy nagy) kiszereléssel számol.
- Ha a felületet `HOST=0.0.0.0`-val a helyi hálózatra nyitod, a hálózaton bárki felvihet és törölhet kézi akciót.
- A tápértékek tipikus, kerekített értékekből számolt becslések; diétás vagy orvosi célra nem elég pontosak.
- A heti terv mohó algoritmus, nem garantáltan a legolcsóbb kombináció; cserébe gyors (≈ 0,1 s) és jól érthető.
- A Wikipédia-cikkek vezetőképe nem mindig maga az étel (pl. egy alapanyag vagy egy rokon étel); ilyenkor a `recipes.json` `photo` listáján érdemes jobb cikket megadni, vagy saját fotót tenni a `data/kepek` mappába.
- A közös háztartás linkje bárkinek hozzáférést ad, aki megkapja; jelszó / fiók nincs.
- A NomNom Planner ötletét és felépítését vettem át (lásd lent), a szövegeit, fotóit és márkáját nem.
- Ötletek: lánc-specifikus akció-letöltők külön feedként, saját recept felvétele a felületről, reggeli/ebéd tervezése is.

## Mit tanultunk a NomNom Plannerből?

A [nomnomplanner.com/hu](https://nomnomplanner.com/hu/), az [adatforrás-oldaluk](https://nomnomplanner.com/open-data/), az [adatvédelmi tájékoztatójuk](https://nomnomplanner.com/hu/privacy/) és a [blogjuk](https://nomnomplanner.com/hu/blog/heti-vacsora-ara/) alapján (2026. október):

- **Árak:** Magyarországon ők is a **GVH Árfigyelő** nyilvános napi adatát töltik le minden nap, és minden hozzávalót egy konkrét, névvel azonosított bolti termékhez kötnek – ugyanazt csinálja ez az app is. A Kifli árait a Kifli saját katalógusából veszik; ahol egy alapanyag a GVH-adatban nincs benne, ott a Kifli-árat használják minden boltra (ezt maguk is jelzik). Más országokban az Open Food Facts **Open Prices** adatbázisából (ODbL), statisztikai hivatalok és az Eurostat átlagáraiból számolnak. Akciók és hűségkártyás árak náluk sincsenek – nálunk felvihetők.
- **Receptek:** a nyílt adat oldaluk szerint a recepteket a **Wikibooks Cookbookból** adaptálták (CC BY-SA 4.0); több mint 1000 vacsorarecept. Ebben az appban 170 saját, magyar recept van, a magyar boltok Árfigyelő-kategóriáihoz kötve.
- **Fotók:** a forrásukat nem írják le. Itt a Wikimedia Commons szabad licencű képei jönnek, szerzővel és licenccel.
- **Tárolás:** fiók nincs; a tervek, beállítások és pipák a telefonon vannak. A szerveroldalon (Cloudflare) csak a receptértékeléseket, a visszajelzéseket és a várólistát tárolják, analitikára a PostHog EU-s szervereit, előfizetésre a RevenueCatet használják. Itt böngészős app van, ezért a háztartás adatai a szerveren vannak (fájlban vagy PostgreSQL-ben), de ugyanúgy fiók és személyes adat nélkül.
- **Felület, amit átvettünk:** kétperces beállítás (bolt, keret, létszám, diéta, konyha) → kész hét fotós vacsorakártyákkal, adagárral és a hét végösszegével a keret mellett → egy vacsora vagy az egész hét cseréje → makrók és hangulat-címkék → diéták és konyhai eszközök beépítve → osztályonként rendezett, megosztható lista → „Nom vagy nem” lapozás.

---
Az árak forrása: [GVH Árfigyelő](https://arfigyelo.gvh.hu). A boltban kiírt ár az irányadó.
