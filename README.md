# Spórolós Konyha 🏷️

[![Build és tesztek](https://github.com/vereszsolt06/teszt/actions/workflows/build.yml/badge.svg)](https://github.com/vereszsolt06/teszt/actions/workflows/build.yml)

Böngészős receptajánló, ami a **GVH Árfigyelő** napi árai alapján megmondja: *ha ma ebben a boltban vásárolsz, mit érdemes főzni?* Kiválasztod a boltot, és az app olyan recepteket „dobál”, amelyekhez ott épp kiemelkedően jó áron kapod az alapanyagokat. Minden hozzávalónál látod, melyik konkrét terméket vedd meg, mennyibe kerül, és miért jó az ára. Van hozzá kipipálható bevásárlólista is, és egy összevetés arról, mennyibe kerülne ugyanez a többi láncban.

Java 21-ben írva, **külső függőség nélkül** (beépített JDK webszerver, saját JSON- és xlsx-olvasó), így egyetlen JAR-ként fut.

![Asztali nézet](docs/kepernyokep.png)

<img src="docs/kepernyokep-mobil.png" alt="Mobil nézet" width="300"> <img src="docs/termek-osszehasonlitas.png" alt="Egy termék ára a láncokban" width="480">

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

## Mit tud

- **Boltválasztó** a hat élelmiszerlánccal; a „ma itt a legjobb” címke azt a boltot jelöli, ahol ma a legjobb receptet lehet összevásárolni.
- **Receptdobáló:** elsőre a legjobb recept, a „Másik receptet kérek” gomb a legjobb 8 közül húz súlyozott véletlennel, ismétlés nélkül. Szűrők: gyors, olcsó, húsmentes, magyaros, leves, főzelék, húsos, hal, édes.
- **Hozzávalók:** minden hozzávalónál a választott konkrét termék, a receptre jutó ár, és ha épp jó áron van, annak oka.
- **Bevásárlólista:** egész csomagokkal (pl. „2 × 450 g”), mérlegelős áruknál a szükséges tömeggel, kipipálható tételekkel, megosztással vagy vágólapra másolással. Az olaj, a liszt és a cukor külön, „ha nincs otthon” tételként szerepel.
- **Adagszám** állítása 1–20 adag között, minden ár újraszámolva.
- **„Mennyibe kerülne máshol?”**: a recept ára mind a hat láncban; egy koppintással átváltasz a másik boltra ugyanazzal a recepttel.
- **Termékrészletek:** bármelyik termékre koppintva látod az árát minden láncban, hány boltjukban kapható, és mennyi a legdrágább boltjukban.
- **Keresés** a kiválasztott bolt termékei között, egységár szerint rendezve.
- **Összes recept** böngészése ajánlás, ár (adagonként) vagy elkészítési idő szerint.
- **Megosztható link:** a cím tartalmazza a boltot, a receptet és az adagszámot (pl. `#bolt=tesco&recept=gulyasleves&adag=8`).

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
55 hétköznapi, főleg magyaros recept van beépítve (`src/main/resources/data/recipes.json`): levesek, főzelékek, egytálételek, sült húsok, halak, saláták, desszertek. Minden hozzávaló abszolút mennyiséggel szerepel (kg / l / db). Az alapanyagok az Árfigyelő **kategóriáihoz** vannak kötve (`products.json`), pl. „darált hús” → 85-ös kategória, „spagetti” → 50-es kategória + „spagett” a névben.

A `RecipeRecommender` minden receptet „megvásárol” minden boltban:

- **Egységátváltás:** kg ↔ l (élelmiszernél ≈ azonos), darab ↔ kg a darabtömeggel (1 paprika ≈ 0,15 kg, 1 uborka ≈ 0,4 kg), csomós zöldségnél a csomag tömegével.
- **Csomagok:** mérlegelős áru (lédig, Ft/kg, pultos) esetén annyit veszel, amennyi kell. A kilós árú, de fix tömegű csomagoknál a névből olvassa ki a tömeget („ca. 500g”). Egyébként egész csomagokat vesz, 15% tűréssel: 0,5 kg pulykamellhez elég egy 450 g-os tálca.
- **Választás:** hozzávalónként a felhasznált rész ára számít, plusz a kényszerűen megvett maradék 35%-a. Így nem javasol 30-as tojástálcát 6 tojáshoz csak azért, mert darabra 2 forinttal olcsóbb. A ritkán kapható termékek hátrébb kerülnek. Ha van jó áron lévő termék, ami legfeljebb 20%-kal drágább a legolcsóbbnál, azt választja.
- **Pontozás:** **45%** mennyire épül jó árakra (a jel erősségével súlyozva), **35%** mennyivel olcsóbb itt, mint a többi bolt mediánjával, **20%** hány hozzávalóra van valós bolti ár.

### REST API

| Végpont | Leírás |
|---|---|
| `GET /api/status` | adatforrás, dátum, demó/elavult jelzés, élelmiszerláncok |
| `GET /api/suggestions?store=lidl&tag=vega&limit=12` | rangsorolt receptek egy boltra, hozzávalókkal |
| `GET /api/recipe?id=lecso&store=lidl&servings=6` | egy recept bevásárlólistával, és ára mind a hat láncban |
| `GET /api/deals?store=lidl&limit=30&ingredients=true` | a bolt kiemelt árai (`ingredients=true`: csak recept-alapanyagok) |
| `GET /api/item?id=<vonalkód>` | egy termék ára minden láncban |
| `GET /api/search?q=tej&store=lidl&limit=30` | keresés a termékek között, egységár szerint |
| `POST /api/refresh` | árak újratöltése |

A válaszok gzip-pel tömörítve mennek, ha a böngésző kéri.

## Projektszerkezet

```
src/main/java/hu/sporolos/
  App.java                       belépési pont, beállítások
  core/  Json                    függőség nélküli JSON olvasó/író
         DealAnalyzer            „jó ár” felismerés (4 jel + elérhetőség)
         RecipeRecommender       receptek beárazása, csomagok, rangsorolás
         PriceRepository         forrásváltás, háttérfrissítés, hibatűrés
  data/  ArfigyeloExcelProvider  napi Excel letöltése / beolvasása / gyorsítótár
         XlsxReader              folyamatos xlsx-olvasó (ZIP + StAX, XXE ellen védve)
         PriceHistory            napi árelőzmények
         DemoPriceProvider       tartalék demó árak
         Catalog                 alapanyagok, láncok, receptek, illesztés
  model/ Models                  rekordok (PriceEntry, Deal, Recipe, Suggestion…)
  web/   WebServer, ApiMapper    REST API + statikus fájlok
src/main/resources/
  data/products.json, recipes.json
  static/index.html, app.css, app.js, favicon.svg, manifest.webmanifest
src/test/java/hu/sporolos/AllTests.java
```

## Tesztek

```bash
./build.sh test        # Windows: build.cmd test
```

21 teszt fut le:
- JSON- és xlsx-olvasás, az Árfigyelő-fejléc felismerése, egységátváltás;
- mind a négy jó ár jel, valamint az elérhetőség hatása;
- a csomagmodell: tojástálca vagy doboz, mérlegelős áru, „ca. 500g”, darab ↔ kg, tűrés;
- az ajánló rangsorolása és adagköltsége;
- a letöltés, a gyorsítótár és a visszaesés egy helyi HTTP-szerverrel;
- a hibás frissítés kezelése (a valós adat nem cserélődik demóra);
- végponttól végpontig teszt, ami elindítja a webszervert, és végighívja az összes API-t.

Ha az `ARFIGYELO_TEST_FILE` környezeti változó egy valódi napi Excelre mutat, a teljes folyamatot azon is lefuttatja: mind a hat láncban legalább 10 árazható recept és életszerű árak kellenek. A 2026-10-28-i fájllal mind a 21 teszt sikeres, és mind a hat láncban mind az 55 recept árazható.

## Feltételezések és döntések

Ezeket kérdezés nélkül döntöttem el; bármelyik könnyen módosítható.

- **Böngészős app, nem natív mobilapp.** Egyszerűbb telepíteni (egy JAR), telefonon is jól működik, és kezdőképernyőre tehető.
- **Java, külső könyvtár nélkül.** Spring Boot helyett a JDK beépített HTTP szervere: nincs függőségletöltés, és a build bárhol működik, ahol van JDK 21.
- **Az adatforrás a GVH napi Excelje.** Az `arfigyelo.gvh.hu` oldal belső API-ját nem használom, mert nem nyilvános és dokumentálatlan.
- **A „bolt” üzletláncot jelent**, mert az Excel láncszintű (minimum/maximum) árakat ad. Az app a lánc legolcsóbb boltjának árát mutatja, ezért a te boltodban lehet drágább; ezt a „máshol akár … Ft” felirat és a termékrészletek is jelzik.
- **Kétféle összeg.** A recept ára a felhasznált mennyiségre jutó rész, így a boltok összevethetők. A bevásárlólista egész csomagokkal számol: ennyit fizetsz a pénztárnál.
- **Néhány alapanyagot az Árfigyelő nem követ** (tonhal, tortilla, passata, gomba, cukkini, spenót, kókusztej, lekvár, tarhonya, zabpehely stb.). Ezek becsült árral (*) szerepelnek, és nem számítanak jó árnak. A recepteket úgy válogattam, hogy főleg követett alapanyagokra épüljenek.
- **Kamraáru:** a fűszerek (só, bors, pirospaprika) „otthon vannak”, nem árazom őket. Az olaj, a liszt és a cukor árazott, de a bevásárlólistán külön, „ha nincs otthon” tételként szerepel.
- **A „Tartósan olcsó” jel kisebb súlyú**, mert a sajátmárkás termékek szinte mindig a kategória aljára esnek. Ettől még nem „épp most” akciósak.
- **Darabtömegek** átlagos értékek (paprika 15 dkg, uborka 40 dkg, hagyma 12 dkg, karfiol 1 kg); a `products.json`-ban állíthatók.
- **Betűtípusok** a Google Fontsról töltődnek; offline a rendszer betűtípusára vált, a működést nem érinti.

## Ismert korlátok, továbbfejlesztési ötletek

- Az első napokban nincs előzmény, ezért az *Olcsóbb a szokásosnál* jel kb. 3 napi futás után jelenik meg.
- Bolt (nem lánc) szintű árakat az Excel nem tartalmaz; egy térképes boltválasztóhoz az Árfigyelő belső API-ja kellene.
- Néhány zöldséget a láncok következetlenül adnak meg (pl. „csomós petrezselyemgyökér” egyszer darabra, egyszer kilóra); ezeknél az ár közelítő.
- Ötletek: heti menütervező egy bevásárlással, kedvenc receptek, saját recept felvétele a felületről.

---
Az árak forrása: [GVH Árfigyelő](https://arfigyelo.gvh.hu). A boltban kiírt ár az irányadó.
