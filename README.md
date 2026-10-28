# Spórolós Konyha 🏷️

Böngészős receptajánló, ami a **GVH Árfigyelő** napi árai alapján megmondja: *ha ma ebben a boltban vásárolsz, mit érdemes főzni?* Kiválasztod a boltot, és az app olyan recepteket „dobál”, amelyekhez ott épp kiemelkedően jó áron kapod az alapanyagokat. Minden hozzávalónál látod, melyik konkrét terméket vedd meg, mennyibe kerül, és miért jó az ára.

Java 21-ben írva, **külső függőség nélkül** (beépített JDK webszerver, saját JSON- és xlsx-olvasó), így egyetlen JAR-ként fut.

![Asztali nézet](docs/kepernyokep.png)

<img src="docs/kepernyokep-mobil.png" alt="Mobil nézet" width="300">

## Indítás

Kell hozzá: **JDK 21 vagy újabb** (pl. [Adoptium Temurin](https://adoptium.net/)).

```bash
# Linux / macOS
./build.sh run

# Windows
build.cmd run
```

Ezután nyisd meg: **http://localhost:8080**

Induláskor az app letölti a napi Excelt (`https://cdnarfigyeloprodweu.azureedge.net/excel/arfigyelo_napi_termekadatok.xlsx`), és elmenti a `data/` mappába. Ha nincs internet, a legutóbb letöltött példányt használja; ha az sincs, demó árakra vált (ezt a fejléc mindig kiírja).

Csak fordítás: `./build.sh` → `target/sporolos-konyha.jar`, ami utána `java -jar target/sporolos-konyha.jar`-ral indítható. IntelliJ / Eclipse alatt a `pom.xml`-lel Maven-projektként is megnyitható (`hu.sporolos.App` a főosztály).

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

## Hogyan működik

### 1. Adatok
A GVH Árfigyelő napi Excelje soronként egy *termék × üzletlánc* párost tartalmaz: vonalkód, név, hivatalos kategória (pl. 18 = Csirkemellfilé), kiszerelés, a lánc legolcsóbb és legdrágább boltjának ára, egységár, és hogy hány láncban/boltban kapható. Az app ebből csak az élelmiszer-kategóriákat tartja meg (1–122), a drogériás tételeket (sampon, pelenka stb.) eldobja. Az oszlopokat a **fejléc szövege alapján** keresi meg, így egy oszlopsorrend-változás nem töri el.

### 2. Mi számít „kiemelkedően jó árnak”?
Minden bolti árra négy jelet néz, és a legerősebbet tartja meg (`DealAnalyzer`):

| Jel | Felirat | Mikor | Súly |
|---|---|---|---|
| CHAIN | *Itt a legolcsóbb* | ugyanaz a termék (vonalkód) ebben a láncban ≥ 8%-kal olcsóbb a többi lánc mediánjánál, és itt a legolcsóbb | teljes |
| HISTORY | *Olcsóbb a szokásosnál* | ≥ 8%-kal olcsóbb, mint az elmúlt napokban (helyi előzményekből, min. 3 nap kell) | teljes |
| SPREAD | *Akciógyanús* | a lánc legolcsóbb boltjában ≥ 12%-kal olcsóbb, mint a legdrágábban | 80% |
| CATEGORY | *Tartósan olcsó* | egységárban a kategória legolcsóbb 5%-ában van, és ≥ 30%-kal a medián alatt | 40% |

Az Excel csak az adott napot tartalmazza, ezért az app **minden nap elmenti a láncok árait** (`data/history/ÉÉÉÉ-HH-NN.tsv`). Néhány nap futás után így azt is felismeri, ha valami *épp most* olcsóbb a szokásosnál.

### 3. Receptajánlás
26 hétköznapi (főleg magyaros) recept van beépítve (`src/main/resources/data/recipes.json`). Az alapanyagok az Árfigyelő **kategóriáihoz** vannak kötve (`products.json`), pl. „darált hús” → 85-ös kategória, „spagetti” → 50-es kategória + „spagetti” a névben. A `RecipeRecommender` minden receptet „megvásárol” minden boltban:

- hozzávalónként a **legolcsóbb adagot** választja (egységár × szükséges mennyiség), de ha van jó áron lévő termék, ami legfeljebb 20%-kal drágább, azt veszi;
- kiszámolja a recept költségét ebben a boltban és a többi bolt mediánjával;
- pontoz: **45%** mennyire épül jó árakra (súlyozva a jel erősségével), **35%** mennyivel olcsóbb itt, mint máshol, **20%** hány hozzávalóra van valós bolti ár.

A felület elsőre a legjobb receptet mutatja, a **„Másik receptet kérek”** gomb a legjobb 8 közül húz súlyozott véletlennel, ismétlés nélkül. A bolt választható, a „ma itt a legjobb” címke a legmagasabb pontszámú boltot jelöli.

### REST API

| Végpont | Leírás |
|---|---|
| `GET /api/status` | adatforrás, dátum, élelmiszerláncok, jó árak száma |
| `GET /api/suggestions?store=lidl&tag=vega&limit=12` | rangsorolt receptek egy boltra (tagek: `gyors`, `olcsó`, `vega`, `magyaros`, `húsos`, …) |
| `GET /api/deals?store=lidl&limit=30&ingredients=true` | a bolt kiemelt árai (`ingredients=true`: csak recept-alapanyagok) |
| `POST /api/refresh` | árak újratöltése |

## Projektszerkezet

```
src/main/java/hu/sporolos/
  App.java                     belépési pont, beállítások
  core/  Json                  függőség nélküli JSON olvasó/író
         DealAnalyzer          „jó ár” felismerés (4 jel)
         RecipeRecommender     receptek beárazása és rangsorolása
         PriceRepository       forrásváltás, gyorsítótár, időzített frissítés
  data/  ArfigyeloExcelProvider  napi Excel letöltése / beolvasása
         XlsxReader            xlsx olvasó (ZIP + XML, XXE ellen védve)
         PriceHistory          napi árelőzmények
         DemoPriceProvider     tartalék demó árak
         Catalog               alapanyagok, láncok, receptek
  model/ Models                rekordok (PriceEntry, Deal, Recipe, Suggestion…)
  web/   WebServer, ApiMapper  REST API + statikus fájlok
src/main/resources/
  data/products.json, recipes.json
  static/index.html, app.css, app.js, favicon.svg
src/test/java/hu/sporolos/AllTests.java
```

## Tesztek

```bash
./build.sh test        # Windows: build.cmd test
```

16 teszt: JSON, xlsx-olvasás, az Árfigyelő-fejléc felismerése és egységátváltás, mind a négy jó ár jel, az ajánló rangsorolása és adagköltség-számítása, valamint egy végponttól végpontig teszt, ami elindítja a webszervert és végighívja az API-t. Ha az `ARFIGYELO_TEST_FILE` környezeti változó egy valódi napi Excelre mutat, a teljes folyamatot azon is lefuttatja (a 2026-10-28-i fájllal mind a 16 teszt sikeres: 5564 élelmiszerár, 6 lánc).

## Feltételezések és döntések

Ezeket kérdezés nélkül döntöttem el; bármelyik könnyen módosítható.

- **Böngészős app, nem mobilapp.** Egyszerűbb telepíteni (egy JAR), telefonon is jól néz ki, és `HOST=0.0.0.0`-val a telefon a helyi hálózaton keresztül el is éri.
- **Java, külső könyvtár nélkül.** Spring Boot helyett a JDK beépített HTTP szervere; így nincs függőségletöltés, és a build bárhol működik, ahol van JDK 21.
- **Az adatforrás a GVH napi Excelje** (amit megadtál). Az `arfigyelo.gvh.hu` oldal belső API-ját nem használom, mert nem nyilvános és dokumentálatlan.
- **„Bolt” = üzletlánc.** Az Excel láncszintű (min./max.) árakat ad, nem boltonként. A „minimum ár” a lánc legolcsóbb boltjára vonatkozik, ezért a te boltodban lehet drágább; ezt a „máshol akár … Ft” felirat jelzi.
- **Csak élelmiszerláncok jelennek meg** (Aldi, Lidl, Penny, Auchan, Tesco, Spar). A Rossmann, a dm és a Müller kevés élelmiszert tart, ezért kimaradnak (a küszöb: a legnagyobb lánc kínálatának 15%-a).
- **A recept költsége a felhasznált mennyiségre jutó rész**, nem a teljes csomag ára (pl. 6 tojás egy 10-es dobozból). Így összevethetők a boltok, de a pénztárnál a teljes csomagot fizeted.
- **Néhány alapanyagot az Árfigyelő nem követ** (tonhal, tortilla, passata, gomba, cukkini, spenót, kókusztej, lekvár stb.). Ezek becsült árral (*) szerepelnek, és nem számítanak jó árnak.
- **Egységeltérésnél közelítek:** ha a bolt darabra árazza, amit a recept kilóban kér (pl. „TV paprika 3 db”), a csomagár × recept-hányad becslést használom. A kg és az l felcserélhetőnek számít (sűrűség ≈ 1).
- **Kamraáru (só, bors, fűszerek) „otthon van”**, nem árazom.
- **A „Tartósan olcsó” jel kisebb súlyú**, mert a sajátmárkás termékek szinte mindig a kategória aljára esnek. Ettől még nem „épp most” akciósak. Az igazi „épp most” jelek a láncok közti árkülönbség, a láncon belüli árszórás és pár nap után a saját előzmények.
- **Adagok:** a receptek többsége 4 adagos, a reggeli és gyors receptek 2 adagosak; a felület mindig kiírja.
- **Betűtípusok** a Google Fontsról töltődnek; offline a rendszer betűtípusára vált, a működést nem érinti.

## Ismert korlátok, továbbfejlesztési ötletek

- Az első napokban nincs előzmény, ezért a *Olcsóbb a szokásosnál* jel kb. 3 napi futás után jelenik meg.
- Bolt (nem lánc) szintű árakat az Excel nem tartalmaz; egy térképes boltválasztóhoz az Árfigyelő belső API-ja kellene.
- Több recept, adagszám-állítás, bevásárlólista-export, kedvenc receptek.

---
Az árak forrása: [GVH Árfigyelő](https://arfigyelo.gvh.hu). A boltban kiírt ár az irányadó.
