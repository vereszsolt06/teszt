# Spórolós Konyha 🏷️

[![Build és tesztek](https://github.com/vereszsolt06/teszt/actions/workflows/build.yml/badge.svg)](https://github.com/vereszsolt06/teszt/actions/workflows/build.yml)

Heti vacsoratervező a boltok napi árai alapján – Magyarországon a **GVH Árfigyelő**, Horvátországban (béta) a **láncok kötelező napi árlistái** szerint –, a [NomNom Planner](https://nomnomplanner.com/hu/) mintájára: egyszer megadod, hányan esztek, milyen gyakran főzöl, mennyi a heti keret, mit nem esztek, mivel főzöl és melyik boltokba jársz – az app összerak egy hetet valódi vacsorákból, adagárral és tápértékkel, abban a boltban, ahol a heted a legolcsóbb, és egyetlen, bolti osztályok szerint rendezett bevásárlólistát ír hozzá. Nem kell minden nap mást főzni: bármelyik vacsorából főzhetsz másnapra is, vagy kérheted, hogy kétnaponta főzz. Bármelyik vacsora vagy az egész hét lecserélhető, a „Nom vagy nem” lapozóval megtaníthatod, mit szeretsz. A háztartás tagjai egy linkkel ugyanazt a hetet és listát látják, a kipipálás közös.

Megmaradt az eredeti ötlet is: a **Receptek** fülön az látszik, mit érdemes ma főzni abból, ami a boltodban épp kiemelkedően jó áron van, a nem kedvelt láncok kizárhatók, és a kiszerelést (darab / kiló, háztartási vagy nagy csomag) gondosan kezeli. Az [ajanlatok.hu](https://www.ajanlatok.hu/), a SmartKosár és a Shoppy mintájára (lásd a [végén](#mit-vettünk-át-az-ajanlatokhu-tól-a-smartkosártól-és-a-shoppytól)) az **Akciók** fülön az összes boltod jó árai egy helyen kereshetők, a listára **saját tételek** (kávé, banán…) is felvehetők, és ezek is beleszámítanak abba, melyik bolt a legolcsóbb. A recepteket, az alapanyagokat, a bolti akciókat és az árfrissítést egy jelszóval védett **admin felületen** (`/admin`) lehet kezelni.

**Legutóbb:** **horvát nyelv** (felület és mind a 170 recept, háromalakú többes számmal), **keresés a saját nyelveden** („milk”, „mlijeko” → tej; horvát boltban „coffee” → kava), **telepíthető app** (PNG ikonok, telepítés gomb), **„kipipáltak elrejtése”** a boltban, **naptár-export** (a hét vacsorái `.ics`-ben), **kéréskorlát** IP-címenként.

**Ebben a körben** ([Világszerte](#világszerte-nyelvek-offline-skálázás)): az app **angolul is** használható – a felület, mind a 170 recept (név és elkészítés), az alapanyagok, a szerver generált mondatai (akció-indoklások, kiszerelések, megjegyzések) –, a nyelvet a böngészőből ismeri fel, és egy koppintással váltható; a háztartás tagjai **különböző nyelven ugyanazt a listát** pipálják. A bevásárlólista **térerő nélkül is működik** (service worker; a boltban tett pipák visszatéréskor felmennek), és a szerver bírja a forgalmat: virtuális szálak, ETag/304 a statikus fájlokra, `/healthz` a terheléselosztóknak és a Dockernek.

**Az előző körben** (lásd [Más országok](#más-országok-van-máshol-is-nyilvános-bolti-áradat) és [Ami élvezhetőbb lett](#ami-élvezhetőbb-lett)): megnéztem, mely országokban van a GVH Árfigyelőhöz hasonló nyilvános bolti áradat (kevesebb, mint amennyinek a NomNom országlistája alapján tűnik), és az app **országfüggetlen** lett: Horvátország a második ország, euróval, a horvát láncokkal. A felület is átalakult: csere-választó a hét ára szerint visszavonással, **főzés mód** lépésenként, a napok dátummal (a hét mától indul), változatosabb hetek, saját betűk, kártyák, animációk.

Java 21, **külső függőség nélkül** (beépített JDK webszerver, saját JSON-, xlsx-, CSV- és PostgreSQL-kliens), egyetlen JAR. Adatbázis nélkül fájlokba ment; `docker compose up`-pal PostgreSQL-lel fut.

![Heti terv](docs/het.png)

<img src="docs/het-mobil.png" alt="Heti terv telefonon, maradéknapokkal" width="250"> <img src="docs/recept-mobil.png" alt="Receptlap telefonon" width="250"> <img src="docs/fozes-mod-mobil.png" alt="Főzés mód: lépésenként, nagy betűkkel" width="250">

<img src="docs/csere.png" alt="Csere: mi legyen helyette, a hét árának változásával" width="640"> <img src="docs/orszag-mobil.png" alt="Első indítás: melyik ország" width="250"> <img src="docs/elso-inditas-mobil.png" alt="Első indítás: milyen gyakran főzöl?" width="250">

<img src="docs/lista.png" alt="Bevásárlólista saját tételekkel, két boltra bontva" width="640"> <img src="docs/nom-mobil.png" alt="Nom vagy nem" width="250">

<img src="docs/akciok.png" alt="Akciók: a boltjaid jó árai egy helyen, kereséssel" width="640"> <img src="docs/akcio-mobil.png" alt="Termékablak: ár minden láncban, felvétel a listára" width="250">

<img src="docs/recept.png" alt="Receptlap: amit a pénztárnál fizetsz, makrók, hozzávalók a bolti termékkel" width="640"> <img src="docs/receptek.png" alt="Receptek: ma kifizetődő, összes recept" width="640">

**Angolul, offline:** az első indítás a böngésző nyelvén szól (fent nyelvválasztó), a heti terv, a receptlap és a lista angolul; a lista térerő nélkül is megnyílik és pipálható.

<img src="docs/en-elso-inditas-mobil.png" alt="First run in English, with the language switch" width="250"> <img src="docs/en-het-mobil.png" alt="The week in English" width="250"> <img src="docs/en-recept-mobil.png" alt="Recipe sheet in English" width="250"> <img src="docs/offline-lista-mobil.png" alt="Shopping list offline, with ticks queued" width="250">

**Admin felület** (`/admin`): áttekintés, receptszerkesztő élő árelőnézettel, alapanyag-szerkesztő az illeszkedő bolti termékekkel.

<img src="docs/admin-attekintes.png" alt="Admin: áttekintés boltonként" width="420"> <img src="docs/admin-recept.png" alt="Admin: receptszerkesztő előnézettel" width="420"> <img src="docs/admin-alapanyag.png" alt="Admin: alapanyag és illeszkedő bolti termékek" width="420">

## Indítás

Kell hozzá: **JDK 21 vagy újabb** (pl. [Adoptium Temurin](https://adoptium.net/)).

```bash
# Linux / macOS
./build.sh run

# Windows
build.cmd run
```

Ezután nyisd meg: **http://localhost:8080**

**Horvát árak:** az első alkalommal, amikor valaki Horvátországot választja, az app letölti a [cijene.dev](https://cijene.dev) aznapi archívumát (kb. 140 MB, egy-két perc – a felület közben töltőképernyőt mutat), feldolgozza (kb. 10–20 s), és a `data/hr/` mappában tartja; naponta legfeljebb egyszer frissít. Ha a horvát országra nincs szükség: `MARKETS=hu`.

Induláskor az app letölti a napi Excelt (`https://cdnarfigyeloprodweu.azureedge.net/excel/arfigyelo_napi_termekadatok.xlsx`), és elmenti a `data/` mappába. 6 óránként a háttérben újratölti. Ha nincs internet, a legutóbb letöltött példányt használja; ha az sincs, demó árakra vált (ezt a fejléc és egy sárga sáv is jelzi). Egy már betöltött valós árállapotot egy sikertelen frissítés sosem cserél demóra.

**Telefonról:** indítsd `HOST=0.0.0.0 ./build.sh run`-nal, és a telefon böngészőjében nyisd meg a gép helyi IP-címét (pl. `http://192.168.1.20:8080`). A „Hozzáadás a kezdőképernyőhöz” menüponttal alkalmazásként is elmenthető.

**Dockerrel, PostgreSQL-lel:**

```bash
docker compose up -d        # app: http://localhost:8080, adatbázis: postgres:16 a „db-adatok” kötetben
docker compose logs -f app  # napló
docker compose down         # leállítás (az adatok megmaradnak)
```

A jelszót a `DB_PASSWORD` környezeti változóval (vagy egy `.env` fájllal) állíthatod, az admin jelszavát az `ADMIN_PASSWORD`-del (ha nem adod meg, a `docker compose logs app` kiírja a generáltat). Docker nélkül is használható a PostgreSQL: `DATABASE_URL=postgres://felhasznalo:jelszo@localhost:5432/sporolos ./build.sh run`.

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
| `admin.password` | `ADMIN_PASSWORD` | véletlen | az `/admin` felület jelszava; ha üres, induláskor generál egyet és kiírja a konzolra |
| `markets` | `MARKETS` | `hu,hr` | országok; `hu` esetén nincs horvát árforrás (és nincs országválasztó) |
| `cijene.url` | `CIJENE_URL` | `https://api.cijene.dev/v0/list` | a horvát napi archívumok listája |
| `cijene.file` | `CIJENE_FILE` | – | kézzel letöltött horvát napi archívum (ZIP); ha meg van adva, nem tölt le |
| `rate.limit` | `RATE_LIMIT` | `600` | API-kérés / perc / IP-cím (0 = nincs korlát); túllépéskor 429 `Retry-After`-rel |
| `trust.proxy` | `TRUST_PROXY` | `false` | reverse proxy / terheléselosztó mögött a kliens címe az `X-Forwarded-For`-ból, a HTTPS az `X-Forwarded-Proto`-ból (csak ha a proxy állítja be) |
| `max.concurrent` | `MAX_CONCURRENT` | `64` | egyszerre feldolgozott API-kérések; a többi legfeljebb 5 s-ig vár, aztán 503 + `Retry-After` |
| `db.pool.size` | `DB_POOL_SIZE` | `10` | legfeljebb ennyi PostgreSQL-kapcsolat példányonként (a PostgreSQL alapból 100-at enged, az összes példányra) |
| `log.requests` | `LOG_REQUESTS` | `true` | kérésnapló a kimenetre (idő, státusz, útvonal, ms, kérésazonosító; a profil-azonosító kitakarva) |
| – | `JAVA_OPTS` | – | Dockerben további JVM-kapcsolók (pl. `-XX:MaxRAMPercentage=60`) |

Hibás érték (pl. `PORT=abc`, `SOURCE=xyz`) esetén az app nem indul el, hanem megmondja, mi a baj (kilépési kód 2). Ha a `PORT` meg van adva, és foglalt, az app nem vált csendben másik portra.

## Éles üzem

Az app egyetlen JAR (vagy Docker-kép), külső függőség nélkül. Élesben így érdemes futtatni:

```
internet ──HTTPS──▶ reverse proxy (Caddy / nginx / felhős LB) ──HTTP──▶ app :8080 (1…n példány) ──▶ PostgreSQL
```

**Gyors telepítés egy szerverre** (a `deploy/` mappa: Caddy automatikus HTTPS-sel + app + PostgreSQL; a CI minden pushnál ellenőrzi a compose- és a Caddy-konfigurációt):

```bash
cd deploy && cp .env.example .env   # DOMAIN, DB_PASSWORD, ADMIN_PASSWORD
docker compose -f docker-compose.prod.yml up -d --build
```

**1. TLS a proxyn.** Az app maga nem kezel tanúsítványt; a proxy végzi a HTTPS-t, és az appnak `TRUST_PROXY=true`-t kell kapnia, hogy a kliens IP-címét (kéréskorlát) és a HTTPS-t (HSTS fejléc) a proxytól vegye. Az app az `X-Forwarded-For` **utolsó** elemét tekinti a kliensnek (azt írta a közvetlenül előtte álló proxy; az első elemet a kliens hamisíthatja), vagyis egy proxy-réteget feltételez – CDN + terheléselosztó láncnál a terheléselosztó cserélje le a fejlécet a valódi kliens címére. Caddyvel a kész konfiguráció a [`deploy/Caddyfile`](deploy/Caddyfile) (automatikus Let's Encrypt-tanúsítvány, a `/metrics` csak belső hálózatról, opcionálisan az admin is); nginx-szel ugyanez: `proxy_pass http://app:8080;` mellett `proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;` és `proxy_set_header X-Forwarded-Proto $scheme;`.

**2. Beállítások élesben:** `HOST=0.0.0.0`, `DATABASE_URL=postgres://…`, `ADMIN_PASSWORD` (legalább 12 karakter – rövidebbre az app figyelmeztet), `TRUST_PROXY=true`. A többi alapértéke éles üzemre van hangolva.

**3. Mit véd az app maga** (a proxy mögött is):
- biztonsági fejlécek minden válaszon: szigorú `Content-Security-Policy` (csak saját forrás, nincs inline szkript, keretbe nem tehető), `X-Frame-Options: DENY`, `X-Content-Type-Options`, `Referrer-Policy`, `Permissions-Policy`, `Cross-Origin-Opener-Policy`; HTTPS-proxy mögött `Strict-Transport-Security`; az API-válaszok `Cache-Control: no-store`;
- kéréskorlát IP-címenként (`RATE_LIMIT`), az admin-belépésre külön (10 próbálkozás / perc / IP), a lejárt admin-munkamenetek takarítása;
- terhelésszabályozás: egyszerre legfeljebb `MAX_CONCURRENT` API-kérés, PostgreSQL-ből legfeljebb `DB_POOL_SIZE` kapcsolat; túlterhelésnél 503 + `Retry-After` (a kliens újrapróbálhatja), nem omlik össze;
- bemenet: kéréstörzs legfeljebb 64 KB (admin: 1 MB), JSON legfeljebb 64 szint mélyen (a mélyebb `[[[[…` nem okozhat veremtúlcsordulást), hibás JSON-ra 400; minden hibára (akár `Error`-ra is) érkezik válasz, a kapcsolat nem marad nyitva;
- időkorlátok a lassan csöpögtetett kérések (slowloris) ellen: kérés beolvasása 30 s, válasz 120 s, üresjárat 30 s;
- a nem létező útvonalak nem kerülnek memóriába (véletlen URL-ekkel nem tölthető tele), a statikus fájlok egyszer tömörítve, ETag-gel.

**4. Leállás és frissítés.** SIGTERM-re (`docker stop`, Kubernetes) új kérést nem fogad, a futókat legfeljebb 5 s-ig befejezi, lezárja az adatbázis-kapcsolatokat. Az adatbázis-séma induláskor magától frissül (zárral: egyszerre csak egy példány migrál), így a gördülő frissítés (egyesével cserélt példányok) működik. A Docker-kép memóriahibánál kilép (`ExitOnOutOfMemoryError`), az orchestrátor újraindítja.

**5. Skálázás.** PostgreSQL-lel az app állapotmentes: több példány futhat a terheléselosztó mögött, a háztartások adatai közösek. Minden példány maga tölti az árakat (magyar: néhány MB; a horvát archívum feldolgozása 300–500 MB átmenetileg), és maga számolja a heti tervet (≈ 0,1 s / kérés). Kiinduló méret: 1 GB memória, 1–2 vCPU példányonként. Fájlos tárolással (`DATABASE_URL` nélkül) csak egy példány fusson.

**6. Megfigyelés.**
- `GET /healthz` – él-e (`{"ok":true,"version":…,"uptimeSeconds":…}`); a Docker `HEALTHCHECK` ezt hívja.
- `GET /metrics` – Prometheus-formátum: kérések útvonal és státuszosztály szerint, válaszidő-összeg és -darabszám, folyamatban lévő kérések, szabad helyek, elutasítások oka szerint (`rate_limit`, `login_limit`, `overload`, `db_busy`), betöltött árak és koruk országonként, demó mód, adatbázis-kapcsolatok, JVM-memória, verzió. Érdemes a proxyn belső hálózatra korlátozni.
- Napló (stdout): induláskor a verzió és az erőforrások; kérésenként egy sor (`2026-11-04T10:00:00Z 200 GET /api/week?id=***&lang=en 12ms rid=8fadf385b8`); hibánál a kérésazonosítóval (`rid`) együtt a verem. A válasz `X-Request-Id` fejléce ugyanez – egy felhasználói hibajelentés így visszakereshető.

**7. Mentés.** PostgreSQL-nél a `pg_dump` (pl. naponta: `docker compose exec -T db pg_dump -U sporolos sporolos | gzip > mentes-$(date +%F).sql.gz`); fájlos módban a `data/` mappa. Mentendő: a háztartások beállításai, értékelései, heti tervei, pipái, az árelőzmény, a kézi akciók és az adminban szerkesztett receptek. Személyes adat (név, e-mail) nincs; a háztartás-azonosító viszont titok (aki ismeri, látja a listát), ezért a napló kitakarja.

## Mit tud

- **Ország:** Magyarország (GVH Árfigyelő, forint) vagy Horvátország (béta: a láncok napi árlistái, euró – nyaraláshoz, vagy aki ott él). A receptek és a felület magyarul maradnak; az árak, a boltok, a keret és minden összeg az ország pénznemében. Horvát árlistában magyarul is lehet keresni („kávé” → *kava*). A Beállításokban bármikor váltható.
- **Első indítás (kb. egy perc):** melyik ország → hányan esztek, hány vacsora → milyen gyakran főzöl (minden nap / kétnaponta) → heti keret → diéta (húsmentes, gluténmentes, laktózmentes) → konyha (tűzhely, sütő, airfryer) → melyik boltokba jársz (és hogy két boltba is elmennél-e). Boltot nem kell választani: a hét abban lesz, ahol a legolcsóbb. Minden később módosítható.
- **Hét:** „Ez a heted ≈ 5 030 Ft”, alatta a keret-sáv, átlagos kalória, fehérje és adagár, és hogy **ugyanez a hét mennyi lenne a többi boltban** („Penny 5 030 · Lidl 5 308 +278 Ft…”) – egy koppintással átválthatsz. A hét **mától** indul (vasárnap a következő hétfőtől), a kártyákon nap és dátum, a mai kiemelve („ma · nov. 4.”). A vacsorák kártyákon: nagy étel-jel, név, adagár, adagszám, idő, kalória, fehérje, címkék.
- **Csere választóval:** a **Csere** nem vakon cserél, hanem 6 javaslatot mutat a napra (a többi nap marad), mindegyiknél, **mennyivel lesz drágább vagy olcsóbb a hét** („+120 Ft a héten”, „−80 Ft a héten”); van „Lepj meg” (véletlen csere) is. Csere után **Visszavonás**. Felül **Új hetet kérek**.
- **Maradék, több napra főzés:** bármelyik vacsoránál **„Holnapra is főzök”** – aznap dupla adag készül, másnap a maradékot eszed (nem kell főzni, nem kell vásárolni). A maradéknapon „Holnap is ez” (3 napra) vagy „Inkább mást főzök”. A **kétnaponta főzök** beállításnál a terv eleve így áll össze. Több napra csak olyat tesz, ami másnap is jó (egytálétel, leves, főzelék, pörkölt, rakott étel), rántott, ropogós ételt, salátát, halat nem.
- **Főzés mód:** a receptlapról lépésenként, nagy betűkkel, lapozva (gomb, nyíl, húzás); a képernyő közben nem alszik el (ahol a böngésző engedi), a hozzávalók egy koppintásra.
- **Receptlap:** elöl az, amit **a pénztárnál fizetsz** (egész csomagokkal) és az adagonkénti ár; alatta, mennyi fogy el belőle a receptben (a többi megmarad). Kalória / fehérje / szénhidrát / zsír, hozzávalók a konkrét bolti termékkel és **a mennyiség az adagszámhoz igazítva** (4 adagos receptnél 2 adagra „1 db paradicsom”, nem „2 db”), nagy kiszerelés alternatívaként, elkészítés, és mennyit fizetnél érte a többi boltban.
- **Lista:** haladásjelző („3 / 16 a kosárban”), és ha minden a kosárban, egy kis ünneplés. A hét összes hozzávalója egy listán (két bolt esetén boltonként szétbontva), bolti osztályok szerint (zöldség, pékáru, hús, felvágott és sajt, tejtermék, száraz áru, konzerv, mélyhűtött), egész csomagokkal; soronként, melyik vacsorához kell és mennyi marad. Kipipálható (a háztartás minden eszközén látszik), megosztható / másolható. A „ha nincs otthon” kamraáru és a fűszerek külön.
- **Nom vagy nem:** kártyás lapozó (húzás, gombok, ← / →). A *Nom* receptek előnyt kapnak a tervben, a *Nem* receptek kimaradnak. Kedvencek listája.
- **Akciók:** a boltjaid összes kiemelt ára egy rácsban, a legnagyobb megtakarítás elöl, kereséssel („sajt”, „csirke”) és boltszűrővel; alapból csak a receptekhez használható termékek, egy pipával minden élelmiszer. Kártyánként: ár, kiszerelés, egységár, mennyivel olcsóbb a többi láncnál, és miért jó ár.
- **Termékablak:** bármelyik termékre koppintva látod az árát minden láncban (hány boltjukban kapható, mennyivel drágább), az **elmúlt 8 hét árát** egy kis grafikonon („most a 8 hét legolcsóbb ára” / „szokásos ár” / „drágább a szokásosnál”), és egy gombbal **felteheted a listádra**.
- **Saját tételek a listán:** amire a vacsorákon kívül szükség van (kávé, banán, mosogatószer). Ha a javaslatok közül választod, az ára beleszámít a lista összegébe *és a boltválasztásba*; más láncban a legolcsóbb hasonló termékkel számol („hasonló: …”), mert a sajátmárkás termékek láncról láncra mások. Szabad szöveggel is felvehető, akkor ár nélkül.
- **Receptek:** „Ma kifizetődő” (az eredeti ötlet: ami ma a boltodban jó áron összevásárolható), és mind a 170 recept a háztartásod létszámára árazva („1 807 Ft · 2 adag”), kalóriával; szűrés fehérjedús / könnyed / laktató / diéta / gyors / airfryer / kedvencek szerint, rendezés ár, „ma a legjobb ár”, idő, fehérje, kalória szerint.
- **Bolt** a fejlécben: **automatikus** (ahol a heted a legolcsóbb) vagy egy rögzített lánc. **Két bolt:** ha bekapcsolod, és legalább ~300 Ft-ot (vagy a hét 4%-át) spórolsz vele, a lista két boltra bomlik – minden tétel ott, ahol olcsóbb.
- **Beállítások:** háztartás, keret, diéta, konyha, **boltjaim** (a nem kedvelt lánc kizárható), **kiszerelés** (háztartási vagy nagy), **hűségkártyák**, **közös háztartás** link. (A bolti akciók felvitele és az árfrissítés az admin felületre költözött.)
- **Admin felület (`/admin`):** receptek, alapanyagok, bolti akciók, árfrissítés, export – részletesen a [9. pontban](#9-admin-felület-admin).
- **Közös háztartás:** a Beállításokban kapott linkkel (`…/#profil=…`) a párod vagy a családod ugyanazt a profilt használja: ugyanaz a hét, ugyanaz a lista, közös pipák.

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

1. **Kézzel** az admin felületen („Bolti akciók” → Új akció): bolt, alapanyag, terméknév, ár, kiszerelés, rendes ár, érvényesség, kártyás-e.
2. **Beillesztve** CSV-ből vagy JSON-ból (admin → „Bolti akciók” → Beillesztés), pl. egy akciós újságból kimásolt listát. Minta: [`docs/akciok-minta.csv`](docs/akciok-minta.csv).
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
- **Javító kör:** a mohó választás nem látja előre, hogy egy később választott recept ugyanazt a csomagot (tojástálca, zacskó borsó) használná fel. Ezért utána napról napra kipróbálja minden jelölt cseréjét a többi nap mellett, és megtartja, ami a hét egészén javít (ugyanaz a mérce: Σ napok × recept értéke − 0,55 × pénztári összeg / egy vacsora kerete); a keret fölött a megtakarítás számít. Minden szabály marad (változatosság, diéta, eszközök, megtartott napok, többnapos főzésre csak eltartható étel). Mérés 1728 háztartás-beállításon (6 bolt × 1/2/4 fő × 3/5/7 vacsora × 4 diéta × keret nélkül / szűk kerettel × 4 mag): **adagár −2,3%** (640,8 → 626,0 Ft), **a keretet 6,5%-kal ritkábban lépi túl**, a jó-ár pontszám és a „rendes vacsora” érték változatlan, a különböző hetek száma szinte ugyanannyi; egy terv 9 helyett 13 ms. A véletlen receptenként állandó (a mag és a recept függvénye), így a mohó és a javító kör ugyanazt a célt követi.
- **„Rendes vacsora”:** a NomNom valódi hétköznapi vacsorákat ad, nem a legolcsóbb köretet. Ezért előnyt kap a fehérjedús főétel és a népszerű konyha (olasz, mexikói, ázsiai, görög, spanyol, comfort), hátrébb kerül a könnyű leves, a főzelék és a 400 kcal alatti tál. Így 2 főre 5 vacsora jellemzően 5–9 ezer Ft, nem 3 ezer krumplis tészta. A többletköltség azt számolja, mennyivel nő a **közös** lista ára: ha hétfőn már vettél egy 2 kg-os zsák krumplit, a csütörtöki krumplis tészta krumplija ingyen van. Ezért a hét receptjei maguktól is egymásra épülnek.
- **Keret:** minden napra a hátralévő keret arányos részét nézi, és tartalékot hagy a többi napra. Ha a keret túl szűk, a legolcsóbb lehetőséget veszi, és megjegyzésben jelzi.
- **Lefedettség:** amelyik receptnek több alapanyaga nincs az Árfigyelőben (becsült ár), az hátrébb kerül (−0,35 × a hiányzó arány), mert annak az ára bizonytalan.
- **Szigorú szűrők:** ha a diéta, az eszközök és a „Nem”-ek után kevesebb megfelelő recept marad, mint ahány vacsora kell, a hiányzó napokra az előző főzés maradéka jut (aznap többet főzöl), és a hét tetején egy megjegyzés jelzi. Korábban ilyenkor egyszerűen kevesebb nap jelent meg.
- **Változatosság:** egy jellegzetes alapanyag (csirkemell, karfiol, lencse, gomba…) egy héten csak egyszer, ugyanaz az ételtípus (fasírt, pörkölt, rakott, főzelék, pizza…) is csak egyszer, krumpli és rizs köretként legfeljebb kétszer – akkor is, ha a közös kosár miatt olcsóbb lenne még egy karfiolos vacsora (a korábbi változat két egymás utáni napra is karfiolt tett). Ezen felül: egy fő alapanyag (pl. csirkecomb) egyszer, legfeljebb egy leves, egy tésztaétel és két halétel egy héten.
- **Csere-javaslatok** (`action=options`): a többi napot megtartva ugyanazzal a mércével rangsorolja a jelölteket, mint a tervezés (véletlen nélkül), és mindegyikhez kiszámolja a hét új pénztári összegét.
- **A bolt nem ugrál:** automatikus boltnál egy csere után a mostani bolt marad, ha legfeljebb ~3%-kal (150 Ft-tal) drágább a legolcsóbbnál – korábban egy csere miatt az egész hét átköltözhetett egy másik boltba, és minden ár megváltozott.
- A „Csere” a többi napot megtartja (`keep`), a lecserélt receptet kizárja (`exclude`); az „Új hét” más véletlen magot kap, és az előző hét receptjeit kihagyja. A háztartás hete a tárolóban marad (`/api/week`), amíg a terv szempontjából fontos beállítás (bolt, létszám, keret, diéta, konyha, boltok, kiszerelés, kártyák) nem változik.

### 6. Tápérték, diéták, eszközök (`RecipeMeta`)
Minden alapanyaghoz tápérték tartozik 100 g-ra (kcal, fehérje, szénhidrát, zsír, közelítő, tipikus értékek), plusz jelölés: hús, hal, glutén, laktóz. Ebből adagonként számol:
- **Diéták:** húsmentes, ha nincs benne hús/hal; gluténmentes, ha nincs glutént tartalmazó alapanyag (liszt, tészta, kenyér…); laktózmentes, ha nincs tejtermék. A kamrából vett fűszerek nem számítanak, és a „gluténmentes” csak az alapanyagokra vonatkozik (a kész termékek nyomokban tartalmazhatják) – **orvosi diétához mindig a csomagolást kell nézni**.
- **Eszközök:** a lépések szövegéből (sütő, tepsi, fokon… illetve főz, pirít, serpenyő…). Az airfryerben is elkészíthető receptek külön jelölést kaptak.
- **Hangulat:** *fehérjedús* (≥ 30 g fehérje vagy a kalória ≥ 30%-a), *könnyed* (≤ 450 kcal), *laktató* (≥ 750 kcal vagy kiadós egytál).
- Bő olajban sütésnél az olajnak csak 20%-át számolja (ennyi marad az ételben).

### 7. Árak: mit mutat az app?
A korábbi változat a recept mellett a **felhasznált mennyiség** árát mutatta („8 szelet kenyér 180 Ft”), ami félrevezető: a boltban az egész vekni 449 Ft. Most mindenhol **az áll elöl, amit ténylegesen fizetsz**:

- **Receptlap:** soronként a megvett csomag ára (pl. 449 Ft a kenyér), mellette, hogy ebből mennyi fogy el a receptben, és hogy a többi megmarad. A fejlécben a pénztári összeg és az adagonkénti ár (pénztári összeg / adag).
- **Receptek, Nom vagy nem, Ma kifizetődő:** „1 807 Ft · 2 adag” – egész csomagokkal, a háztartásod létszámára.
- **Hét:** a hét ára a közös lista pénztári összege. A vacsorakártyán az adagár a közös listából rá eső rész: ha a hétfői rántott párizsihoz vett tojásból kedden is jut, a tálca ára megoszlik a kettő között. Így a kártyák összege mindig kiadja a hét árát.
- Az olaj, a liszt, a cukor és a fűszerek („kamraáru”) nincsenek benne az összegben, külön „ha nincs otthon” sorban szerepelnek.
- A mennyiségek az adagszámmal együtt változnak (`Amounts`): „2 db” → „1 db”, „1 dl” → „0,5 dl”, „50 dkg” → „75 dkg”, „75 dkg” → „1,5 kg”.

### 7.1 Melyik boltban? (automatikus bolt, két bolt)
- **Automatikus:** a hetet a boltjaid közül abban tervezi, ahol ma a legjobb a kínálat, majd ugyanazt a hetet beárazza mind a 6 láncban, **a saját tételekkel együtt**, és abba vált, ahol a legolcsóbb (ahol egy tétel nincs az Árfigyelőben, azt büntetéssel számolja; a sávban `*` jelzi, ha egy boltban valami hiányzik). A „Ugyanez a hét” sáv mutatja a többi bolt árát.
- **Két bolt:** minden boltpárra kiszámolja, mennyi lenne, ha minden tételt a kettő közül ott vennél, ahol olcsóbb; a legjobb párt ajánlja, ha legalább 300 Ft-ot vagy a hét 4%-át spórolja. Kettőnél több boltot nem ajánl, mert annyi utat nem ér meg.

### 8. Tárolás: fájl vagy PostgreSQL (`store/`)
A tartós adatok egy `Storage` felület mögött vannak, két megvalósítással:

| | `FileStorage` (alap) | `PgStorage` (`DATABASE_URL`) |
|---|---|---|
| háztartás-profil (beállítások) | `data/profilok/{id}.json` | `household` (jsonb) |
| „Nom vagy nem” értékelések | a profil fájljában | `recipe_rating` |
| mentett hét | a profil fájljában | `saved_week` (jsonb) |
| kipipált listatételek | a profil fájljában | `checked_item` |
| napi árelőzmény | `data/history/ÉÉÉÉ-HH-NN.tsv` | `price_day`, `price_history` |
| kézi akciók | `data/akciok/sajat-akciok.json` | `manual_promo` |
| saját listatételek, adminban szerkesztett katalógus | `data/dokumentumok/*.json` | `app_document` |
| horvát napi archívum (gyorsítótár) | `data/hr/cijene-ÉÉÉÉ-HH-NN.zip` | – (mindig fájl) |

A PostgreSQL-kapcsolathoz **nincs JDBC-driver**: a `PgClient` a PostgreSQL 3-as wire protokollját beszéli (SCRAM-SHA-256 / MD5 belépés, paraméterezett lekérdezések a kiterjesztett protokollal, így nincs SQL-befecskendezés), egy kis kapcsolatkészlettel. A séma induláskor magától létrejön / frissül (`schema_version` tábla, zárolással, hogy több példány se ütközzön). TLS nincs, mert a docker-compose belső hálózatára készült; távoli adatbázishoz SSH-alagút vagy helyi hálózat ajánlott.

**Fiók nincs** (ahogy a NomNomban sincs): a háztartást egy véletlen, 20 karakteres azonosító jelöli, amit a böngésző megjegyez. Aki ismeri, látja a háztartás hetét és listáját – ezért csak annak add oda a közös linket, akivel közösen vásároltok.

### REST API

| Végpont | Leírás |
|---|---|
| `GET /api/status` | adatforrás, dátum, demó/elavult jelzés, élelmiszerláncok, az ország (`market`: pénznem, árfolyam, forrás) és az országok listája (`markets`) |
| `GET /api/markets` | az országok és betöltöttségük (nem indít betöltést) |
| `GET /api/suggestions?store=lidl&tag=vega&limit=12` | rangsorolt receptek egy boltra, hozzávalókkal |
| `GET /api/recipe?id=lecso&store=lidl&servings=6` | egy recept bevásárlólistával (kiszerelés-alternatívákkal), és ára a választott láncokban |
| `GET /api/deals?store=lidl&limit=30&ingredients=true` | a bolt kiemelt árai (`ingredients=true`: csak recept-alapanyagok) |
| `GET /api/item?id=<vonalkód>` | egy termék ára minden láncban, és az elmúlt 8 hét ára (`history`: pontok, min, max, medián) |
| `GET /api/offers?q=sajt&store=lidl&ingredients=false&limit=60` | a boltjaid kiemelt árai együtt, megtakarítás szerint (Akciók fül) |
| `GET` / `POST` / `DELETE /api/extras?id=…[&n=2]` | a háztartás saját listatételei (`{"name", "itemId"}`) |
| `GET /api/search?q=tej&store=lidl&limit=30` | keresés a termékek között, egységár szerint |
| `GET /api/ingredients` | a recept-alapanyagok listája (az akció-űrlaphoz) |
| `GET /api/plan?store=lidl&people=2&dinners=5&budget=15000&diets=vega&equipment=tuzhely,suto&liked=…&disliked=…&keep=a,_,c&exclude=…&seed=3` | heti vacsoraterv közös, osztályonként rendezett bevásárlólistával |
| `GET /api/recipes?store=lidl&servings=2&diets=gluténmentes` | az összes recept tápértékkel, a megadott adagszámra árazva |
| `POST /api/profile` | új háztartás-profil (törzs: beállítások) → `{id, settings, liked, disliked}` |
| `GET` / `PUT /api/profile?id=…` | a profil lekérése / beállítások mentése |
| `POST /api/rate?id=…` | `{"recipe": "lecso", "liked": true \| false \| null}` – Nom vagy nem |
| `DELETE /api/rate?id=…` | minden értékelés törlése |
| `GET /api/week?id=…[&action=new\|swap\|set\|repeat\|fresh\|options&day=2&recipe=…]` | a háztartás heti terve (mentve marad, a háztartás országában); új hét, egy nap cseréje, konkrét recept egy napra, „holnapra is főzök”, „inkább mást főzök”, `options`: csere-javaslatok a hét új árával; a napoknál `date` és `index`, a hétnél `start`; automatikus boltnál a legolcsóbb bolt, `compare`: ugyanez a hét a többi boltban, `split`: két boltos lista |
| `POST /api/check?id=…` | `{"key", "item", "on"}` – listatétel kipipálása |
| `GET /api/promos` | a láncok saját akciói és állapotuk (beépítve / már benne / lejárt / kártya kell…) |
| `POST /api/promos` 🔒 | új akció (JSON), vagy import: `{"text": "<CSV vagy JSON>"}` |
| `DELETE /api/promos?id=…` 🔒 | kézi akció törlése |
| `POST /api/refresh` 🔒 | árak újratöltése |
| `POST /api/admin/login` | `{"password"}` → `{"token"}` (12 óráig érvényes) |
| `GET /api/admin/overview` 🔒 | forrás, dátum, előzmény napjai, háztartások, boltonként: tételek, jó árak, teljesen árazható receptek, hiányzó alapanyagok |
| `GET /api/admin/recipes`, `GET`/`PUT`/`POST`/`DELETE /api/admin/recipe?id=…` 🔒 | receptek listája figyelmeztetésekkel; egy recept lekérése / mentése / felvétele / törlése |
| `POST /api/admin/recipe/preview` 🔒 | mentés nélküli előnézet: ár minden láncban, soronként a bolti termék, figyelmeztetések |
| `GET /api/admin/products`, `GET`/`PUT`/`POST`/`DELETE /api/admin/product?key=…` 🔒 | alapanyagok illeszkedési számokkal; egy alapanyag (a használtat nem lehet törölni) |
| `POST /api/admin/product/matches`, `GET /api/admin/categories` 🔒 | élő illesztés-előnézet; az Árfigyelő kategóriái |
| `POST /api/admin/refresh`, `POST /api/admin/reset`, `GET /api/admin/export` 🔒 | árfrissítés; vissza a beépített katalógusra; katalógus letöltése JSON-ban |

Minden boltfüggő végpont elfogadja: `stores=aldi,lidl` (a választott boltok), `bulk=1` (nagy kiszerelés mód), `cards=lidl,tesco` (hűségkártyák), `market=hr` (ország; alap: `hu`). A háztartáshoz kötött végpontok (`week`, `extras`, `check`, `rate`, `profile`) a profil országát használják. Ha egy ország árai még töltődnek, a válasz **503** `{"loading": true, "progress": 0.4, "phase": "…", "market": {…}}` – a felület ilyenkor töltőképernyőt mutat és újrapróbál.

Az összegek a JSON-ban **belső, forint-egyenértékű egységben** jönnek (Horvátországban 1 € = 400 egység); a felület a `market.rate` szerint váltja vissza. Így a forintra hangolt küszöbök (pl. „legalább 300 Ft-ot spórolj két bolttal”) minden országban ugyanúgy működnek; a valós bolti árak oda-vissza pontosan váltódnak, az árfolyam csak a küszöböket és a becsült (a forrásban nem szereplő) alapanyagokat érinti.

🔒: `X-Admin-Token` fejléc kell (a belépéskor kapott token); nélküle 401.

A válaszok gzip-pel tömörítve mennek, ha a böngésző kéri.

### 9. Admin felület (`/admin`)
Böngészőben: **http://localhost:8080/admin**. A jelszó az `ADMIN_PASSWORD` környezeti változó (vagy `admin.password`); ha nincs megadva, az app minden indításkor generál egyet, és kiírja a konzolra:

```
  Admin felület:        http://localhost:8080/admin
  Admin jelszó:         k3j9…  (minden indításkor új; állandót az ADMIN_PASSWORD adja)
```

- **Áttekintés:** honnan és melyik napról vannak az árak, hány nap árelőzmény gyűlt össze, hány háztartás van, és boltonként: hány élelmiszer, hány jó ár, hány recept árazható teljesen, és **melyik alapanyag hiányzik** abból a láncból (hány receptet érint). Egy gombbal újratölthetők az árak.
- **Receptek:** kereshető táblázat (adag, idő, ár a legolcsóbb boltban, lefedettség, figyelmeztetések). A szerkesztőben minden mező állítható: név, emoji, idő, nehézség, adag, címkék, airfryer, hozzávalók (alapanyag a listából vagy „kamra”, a kiírt mennyiség és a számolt szükséglet), lépések. Az **Előnézet** mentés nélkül beárazza a receptet mind a 6 láncban, soronként a konkrét bolti termékkel, és figyelmeztet, ha a kiírt mennyiség („2 db”) és a szükséglet (0,8 kg) nem illik össze, vagy egy alapanyag sehol nincs az Árfigyelőben. Új recept felvehető, a meglévő törölhető.
- **Alapanyagok:** melyik alapanyag hány terméket talál lánconként (pirossal, ahol egyet sem). A szerkesztőben a kategóriák, kulcsszavak és kizáró szavak mellett **élőben látszik, melyik bolti termékek illeszkednek** – így egy rossz találat egy kizáró szóval kiszűrhető. Darabtömeg, csomótömeg, háztartási max., csak csomagolt, kamraáru, tápérték, hús/hal/glutén/laktóz jelölés. Amit recept használ, azt nem lehet törölni.
- **Bolti akciók:** a korábbi felhasználói „Akciók” párbeszéd ide költözött (kézi felvitel, CSV/JSON beillesztés, törlés), mert ezek minden háztartásra hatnak.
- **Adatok:** a szerkesztett katalógus letöltése JSON-ban (`products.json` + `recipes.json`, a repóba visszaírható), és visszaállás a beépítettre.

**Hogyan tárolja?** A szerkesztett katalógus a tárolóba kerül (`data/dokumentumok/` vagy a PostgreSQL `app_document` táblája), így újraindítás után is megmarad, és felülírja a JAR-ba épített `products.json`-t és `recipes.json`-t. Minden mentés előtt az egész katalógust újraépíti és ellenőrzi (ismeretlen alapanyag, üres név, rossz szám → 400, nem kerül be); ha egy mentett katalógus valamiért mégis hibás, induláskor a beépítettre esik vissza, és ezt naplózza. A változás azonnal él: az árak és az elemzés újraszámolódik, letöltés nélkül.

**Biztonság:** a jelszót állandó idejű összehasonlítással ellenőrzi, a token 12 óráig érvényes, csak a memóriában van (újraindításkor kilép). TLS nincs – helyi gépre és belső hálózatra készült; nyilvános elérésnél egy HTTPS-es fordított proxy (Caddy, nginx) kell elé.

## Projektszerkezet

```
src/main/java/hu/sporolos/
  App.java                       belépési pont, beállítások, országok
  market/ Market                 ország: pénznem, belső árfolyam, pénzformázás (a kérés szálán)
          Markets                az országok árállapota; a nem magyar csak az első kéréskor töltődik
          MarketDef, NameRules   ország-leírás (resources/markets/hr.json), névalapú alapanyag-illesztés
  core/  Json                    függőség nélküli JSON olvasó/író
         DealAnalyzer            „jó ár” felismerés (5 jel + elérhetőség)
         Packaging               mérlegelős / csomagolt / csomós, darab ↔ kg, nagy kiszerelés
         RecipeRecommender       receptek beárazása, háztartási és nagy kiszerelés, rangsorolás
         WeeklyPlanner           heti vacsoraterv közös kosárral, kerettel, diétával
         PromoMerger             a láncok akcióinak összefésülése az Árfigyelővel
         PriceRepository         forrásváltás, háttérfrissítés, elemzés kártyánként, katalóguscsere
  data/  ArfigyeloExcelProvider  napi Excel letöltése / beolvasása / gyorsítótár
         XlsxReader              folyamatos xlsx-olvasó (ZIP + StAX, XXE ellen védve)
         PriceHistory            napi árelőzmények (a tárolón át)
         DemoPriceProvider       tartalék demó árak
         Catalog                 alapanyagok, láncok, receptek, illesztés
         CatalogSource           beépített vagy adminban szerkesztett katalógus, ellenőrzés
         RecipeMeta              tápérték, diéták, eszközök, hangulat receptenként
         Promo, PromoStore       akciók: kézi, fájl, feed; CSV/JSON
         CijeneArchiveProvider   horvát árak: a cijene.dev napi ZIP-je, letöltés, CSV, lánconkénti összesítés
  store/ Storage                 a tartós adatok felülete
         FileStorage             fájlos tárolás (alap)
         PgStorage, PgClient     PostgreSQL: séma, kapcsolatkészlet, saját wire-protokoll kliens
         Ids                     profil-azonosítók
  model/ Models                  rekordok (PriceEntry, Deal, Recipe, Suggestion…)
  i18n/  Lang                    a kérés nyelve (?lang=, különben Accept-Language)
         Translator              a válaszok fordítása a határon (resources/i18n/{nyelv}.json)
  web/   WebServer, ApiMapper    REST API + statikus fájlok (ETag), /healthz
         AdminApi                admin végpontok: belépés, áttekintés, recept- és alapanyag-szerkesztés
         Household               a háztartás beállításai, ellenőrzés, alapértékek
         RateLimiter, Metrics    kéréskorlát IP-címenként; Prometheus-metrikák (/metrics)
src/main/resources/
  data/products.json, recipes.json
  markets/hr.json                Horvátország: láncok, illesztési szabályok, magyar → horvát keresőszavak
  i18n/en.json, hr.json, de.json  receptek, alapanyagok, kifejezések, mértékegységek, mondatminták, keresés
  static/index.html, app.css, app.js, favicon.svg, manifest.webmanifest
         i18n.js                 a felület nyelve: felismerés, t(), többes szám, nyelvválasztó
         lang/en.js, hr.js, de.js  a felület szótárai (csak a választott töltődik le)
         sw.js                   service worker: offline hét, lista és receptek
         admin.html, admin.css, admin.js
         fonts/                  Bricolage Grotesque, Atkinson Hyperlegible (SIL OFL, latin + latin-ext részhalmaz)
src/test/java/hu/sporolos/AllTests.java
Dockerfile, docker-compose.yml   konténer: app + postgres:16
```

## Tesztek

```bash
./build.sh test        # Windows: build.cmd test
```

49 teszt fut le:
- JSON- és xlsx-olvasás, az Árfigyelő-fejléc felismerése, egységátváltás;
- mind a négy jó ár jel, valamint az elérhetőség hatása;
- a kiszerelés-modell: tojásdoboz vagy tálca, mérlegelős áru minimum egy darabbal, „ca. 500g”, csak csomagolt liszt, darab ↔ kg (paprika, zsemle), csomós zöldség, tűrés, a nagy kiszerelés határa;
- háztartási mód nagy kiszerelés alternatívával és fordítva, valamint a boltszűrő hatása;
- akciók: CSV-beolvasás, az összefésülés minden ága (felülír / már benne / új tétel / kártya kell / lejárt / még nem / ismeretlen bolt), és hogy egy akciós tonhal árazhatóvá teszi a tonhalas tésztát;
- az ajánló rangsorolása és adagköltsége;
- receptadatok (kalória, a vega címke és a hús egyezése, eszközök, mi tartható el másnapig);
- a mennyiségek átszámolása más adagszámra (db, szelet, dl, dkg → kg, „fél”, „másfél”), mind a 170 recept minden sorára;
- heti terv maradékkal (3 napra főzés, a maradéknapra nincs vásárlás, csak eltartható étel), ugyanaz a hét több boltban, két boltos lista (sosem drágább, kis megtakarításért nem bont);
- tárolás oda-vissza (profil, értékelés, hét, pipák, árnap, kézi akció) fájlokkal, és – ha a `PG_TEST_URL` meg van adva – PostgreSQL-lel is, párhuzamos írással és SQL-befecskendezési próbával;
- heti terv javító köre: 6 boltban × 2 létszám × 3 mag × keret nélkül / kerettel átlagosan olcsóbb a mohó tervnél, nem lépi túl gyakrabban a keretet, nincs ismétlés vagy desszert, a megtartott napokhoz nem nyúl;
- heti terv: nincs ismétlés, változatos fő alapanyag, nincs desszert, a lista összege egyezik, egy termék egy sor, diéta- és eszközszűrő, csere a többi nap megtartásával, keret, más mag más hetet ad;
- a letöltés, a gyorsítótár és a visszaesés egy helyi HTTP-szerverrel;
- a hibás frissítés kezelése (a valós adat nem cserélődik demóra);
- országok: forint- és eurós pénzformázás (a kérés szála szerint); a horvát névillesztés 31 valódi (a láncok listáiból vett) terméknéven, köztük olyanokon, amelyek *nem* alapanyagok (felvágott, kész étel, csokoládé, állateledel, habfürdő); egy valósághű horvát napi archívum feldolgozása (darabos tojás, mérlegelős csirkemell, akciós ár üres árral, idézőjeles-vesszős név, a drogéria kihagyása, két láncban ugyanaz a vonalkód), majd a teljes app horvát árakkal: 503 „töltődik”, euró, horvát boltok, magyar keresőszó, horvát bolti akció, vissza magyarra;
- heti terv: a napok dátuma és neve, a hét kezdete, változatosság (jellegzetes alapanyag egyszer, krumpli legfeljebb kétszer), csere-javaslatok (nincsenek már a héten, a hét új ára közel a tényleges árhoz), választás után a többi nap és a kezdőnap marad, maradéknapnál a főzős nap javaslatai;
- admin: rossz jelszó 401, jó jelszó token; az akció- és a frissítés-végpont token nélkül 401; receptmódosítás mentése, tárolóba írása és azonnali érvényesülése; hibás recept 400; előnézet figyelmeztetéssel; új / ismételt / törölt recept; alapanyag kizáró szóval kevesebb találat; használt alapanyag nem törölhető; visszaállás a beépítettre; az `/admin` oldal kiszolgálása;
- éles üzem: ellenséges JSON (100 000 szint mély, lezáratlan szöveg, hibás escape, túl nagy szám) → 400, nem veremtúlcsordulás; túl nagy törzs; biztonsági fejlécek (CSP, X-Frame-Options, nosniff, Referrer-Policy, kérésazonosító), HSTS csak HTTPS-proxy mögött, API `no-store`; 300 véletlen URL után sem nő a statikus gyorsítótár; előre tömörített `app.js` kibontva helyes; admin-belépés: 14 párhuzamos próbálkozásból legalább 4 → 429; metrikák (útvonal, státusz, elutasítás oka, árak betöltve); terhelésszabályozás: egy lassan feltöltött kérés mellett a következő 503 + `Retry-After`, utána újra működik; a napló csak a háztartáshoz kötött végpontokon takarja ki az azonosítót; proxy mögött az `X-Forwarded-For` utolsó eleme a kliens (a hamisított első elem nem kerüli meg a korlátot);
- német: minden recept / lépés / alapanyag, mértékek (dl → ml), keresés („milch” → tej), napnév;
- horvát: minden recept / lépés / alapanyag, háromalakú többes szám (1, 3, 5, 12, 21), európai számformátum, a dl marad; keresés nyelvek között (angol → magyar, horvát → magyar, kifejezés egyben, alapanyagnév visszafelé, horvát boltban „coffee” → kava, „kava” marad); kéréskorlát (429, `Retry-After`, angol üzenet, a statikus fájlokat és a `/healthz`-t nem korlátozza);
- többnyelvűség: nyelvfelismerés (`?lang=`, `Accept-Language`), minden recept, lépés és alapanyag angol fordítása megvan, a generált mondatok (akció-indoklás, mérlegelős mennyiség, forint és euró, dkg → g, dl → ml, egyes/többes szám, fűszerlista), a terméknév változatlan, az azonosítók és a szűrőkulcsok maradnak, a pipák kulcsa nyelvfüggetlen; a webszerveren angol recept és hibaüzenet, a magyar válasz egy angol kérés után is magyar, `/healthz`, ETag és 304;
- végponttól végpontig teszt, ami elindítja a webszervert, és végighívja az összes API-t: boltszűrő, akciók felvitele / importja / törlése, háztartás-profil létrehozása, mentett hét, csere, konkrét recept egy napra, kipipálás, értékelés, boltváltás, automatikus bolt (a legolcsóbbat választja), „holnapra is főzök” / „inkább mást főzök”, kétnaponta főzés, a mennyiségek adagszámhoz igazítása, az Akciók végpont szűrése, saját tételek felvétele / törlése és beszámítása a boltválasztásba.

A GitHub Actions a teszteket egy PostgreSQL 16 szolgáltatással együtt futtatja, utána felépíti a Docker-képet, elindítja a `docker compose`-t, és ellenőrzi, hogy az app válaszol, és a profil az adatbázisba kerül.

Ha az `ARFIGYELO_TEST_FILE` környezeti változó egy valódi napi Excelre mutat, a teljes folyamatot azon is lefuttatja: mind a hat láncban legalább 10 árazható recept és életszerű árak kellenek. A 2026-10-28-i fájllal, PostgreSQL 16-tal mind a 35 teszt sikeres, mind a 170 recept árazható, és mind a hat láncban kijön egy 5 vacsorás, 2 fős heti terv 15 000 Ft alatt.

## Feltételezések és döntések

Ezeket kérdezés nélkül döntöttem el; bármelyik könnyen módosítható.

- **Több napra főzés:** a NomNom minden napra más vacsorát ad; itt a maradék beépített lehetőség (ezt a felhasználó kérte), a terv és a lista ehhez igazodik.
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
- **Képek egyelőre nincsenek** (kérésre kivettem a fotókat): a recepteket egy emoji jelöli. A fotós változat a git-előzményekben megvan (`PhotoService`, Wikimedia Commons, szabad licenccel), később visszahozható.
- **Kétnaponta főzés 5 vacsoránál:** hétfő+kedd, szerda+csütörtök, péntek – 3 főzés.
- **Automatikus bolt** az alapértelmezés; az első indításkor nem kell boltot választani.
- **Betűtípusok** a Google Fontsról töltődnek; offline a rendszer betűtípusára vált, a működést nem érinti.
- **Admin egyetlen jelszóval**, felhasználói fiókok nélkül: egy háztartási / kis csapatos apphoz ennyi elég, és nem kell hozzá adatbázisséma vagy jelszó-visszaállítás. Ha nincs megadva, véletlen jelszó, hogy alapból ne legyen nyitva.
- **Az akciók felvitele és az árfrissítés csak adminnak** megy (korábban bárki megtehette a felületről), mert minden háztartás árait érinti.
- **A szerkesztett katalógus a tárolóban** él, nem a JAR-ban: így Dockerben is megmarad, és az „Export” gombbal visszaírható a repóba.
- **A „Most jó áron” sáv a Receptek fülről az új Akciók fülre került**, és ott már az összes boltod együtt látszik – az ajanlatok.hu-hoz hasonlóan ez önálló böngészhető nézet lett.
- **A saját tételek** szabad szöveggel is felvehetők, de árat csak akkor kapnak, ha a javaslatok közül választod: egy „kávé” szövegből nem lehet biztosan tudni, melyik termék és kiszerelés kell.
- **Az árelőzmény 8 hetes**, mint az ajanlatok.hu ajánlatértékelésénél; régebbi adatot nem mutat.
- **Második ország: Horvátország**, mert ott a legjobb a nyilvános adat (minden lánc minden boltja, naponta, géppel olvasható, ingyen, regisztráció nélkül), és mert a magyar felhasználóknak is életszerű (nyaralás, apartman, önellátás). Szerbia a következő jelölt (lásd lent).
- **A cijene.dev archívumát** használom, nem a 29 lánc saját oldalait: egy jól karbantartott, nyílt forráskódú gyűjtő, egységes CSV-kkel; a láncok honlapjainak egyenkénti letöltése törékeny lenne. Az archívum nagy (~140 MB), ezért naponta legfeljebb egyszer és csak akkor töltődik, ha valaki Horvátországot használja.
- **Horvátországban a terméknév alapján illesztek** (`markets/hr.json`), mert a láncoknak nincs közös kategóriarendszerük. A szabályokat valódi terméknevekkel hangoltam: a cijene.dev nyílt termékadatbázisának 29 684 nevével és egy Konzum-bolt aznapi árlistájának soraival. A zöldség-gyümölcs és a pultos hús nevei lánconként eltérhetnek; ezért **béta**. Ami nem illeszkedik, becsült árral szerepel (mint itthon a tonhal).
- **A belső pénzegység forint-egyenérték** (1 € = 400 belső egység), a felület visszavált. Az árfolyam csak a küszöböket és a becsült árakat befolyásolja, a valós árakat nem.
- **Horvátországban a lánc legolcsóbb boltjának ára** számít (mint az Árfigyelőnél), az akciós ár (`special_price`), ha a lánc boltjainak legalább harmadában érvényes, a lánc saját akciójaként jelenik meg („Bolti akció, 27%-kal olcsóbb (rendesen 2,19 €)”). Árelőzmény ott nincs (a listák a 30 napos legalacsonyabb árat közlik, ezt nem mutatom külön).
- **A hét mától indul** (vasárnap a következő hétfőtől): így a lista arra szól, amit tényleg meg fogsz főzni. Egy hétnél régebbi mentett hét helyett új kezdődik.
- **A csere választóval megy**, nem vakon: a véletlen csere drágább hetet is adhatott (a régi változatban egy 268 Ft-os adag helyére 546 Ft-os került, és közben a bolt is átváltott).
- **A betűket az app maga szolgálja ki** (a Google Fonts helyett): offline is ugyanígy néz ki, és a böngésző nem szól át harmadik félhez.

## Ismert korlátok, továbbfejlesztési ötletek

- Az első napokban nincs előzmény, ezért az *Olcsóbb a szokásosnál* jel kb. 3 napi futás után jelenik meg.
- Bolt (nem lánc) szintű árakat az Excel nem tartalmaz; egy térképes boltválasztóhoz az Árfigyelő belső API-ja kellene.
- Néhány zöldséget a láncok következetlenül adnak meg (pl. „csomós petrezselyemgyökér” egyszer darabra, egyszer kilóra); ezeknél az ár közelítő.
- A kiszerelés-összesítő és a soronkénti csere csak a kiválasztott boltra vonatkozik; a „többi boltodban” összevetés az alapbeállítás szerinti (háztartási vagy nagy) kiszereléssel számol.
- Az admin felületnek nincs TLS-e; nyilvános hálózaton csak HTTPS-es proxy mögött használd.
- **Horvátország béta:** a névalapú illesztés valódi neveken hangolt, de a teljes, napi archívumon itt nem tudtam lefuttatni (a fejlesztői környezetből a cijene.dev nem érhető el); a formátumot a nyílt forráskódjukból és egy valódi árlistából vettem, a tesztek ilyen archívummal futnak. Az admin felület egyelőre a magyar adatokat kezeli.
- A horvát első betöltés egy-két perc (letöltés), utána naponta egyszer; egy szerényebb gépen a feldolgozás kb. 300–500 MB memóriát kér.
- A tápértékek tipikus, kerekített értékekből számolt becslések; diétás vagy orvosi célra nem elég pontosak.
- A heti terv mohó választás + egycserés javító kör (lásd 5. pont): nem garantáltan a legjobb kombináció (két nap egyidejű cseréjét nem próbálja), cserébe gyors (≈ 13 ms) és jól érthető.
- A közös háztartás linkje bárkinek hozzáférést ad, aki megkapja; jelszó / fiók nincs.
- A NomNom Planner ötletét és felépítését vettem át (lásd lent), a szövegeit, fotóit és márkáját nem.
- Az ár „hasonló termék” cseréje a saját tételeknél kategória + egység + kb. kiszerelés alapján megy; nagyon eltérő minőségű termékeket is párba állíthat.
- Az árelőzmény csak attól a naptól gyűlik, amikor az app először fut; a GVH régebbi napi fájljait nem tölti le.
- **Nyelvek:** magyar, angol, horvát, német. Az admin felület magyar maradt. A horvát és a német fordítás gépi minőségű, anyanyelvi átnézés jót tenne nekik. Az angol recept-mennyiségek a magyar mértékegységekből számolódnak (dkg → g); a „bögre/cup” típusú amerikai mértékek nincsenek.
- **Offline:** az első látogatás után működik (a service workernek egyszer be kell töltődnie); offline a csere és az új hét nem kérhető, a pipák viszont igen.
- Ötletek: lánc-specifikus akció-letöltők külön feedként, árriasztás (ha egy kedvenc termék olcsó lesz), reggeli/ebéd tervezése is.

## Világszerte: nyelvek, offline, skálázás

Az app eddig két országban tervezett, de csak magyarul beszélt – ez volt a legnagyobb akadály, hogy bárki más használja (egy horvát vagy egy Magyarországon élő külföldi, egy Horvátországban nyaraló német). A legtöbbet hozó lépések, sorrendben:

**1. Angol nyelv, végig.** Nem csak a gombok: mind a 170 recept neve és elkészítése, a 106 alapanyag, a fűszerlisták, a bolti osztályok, a napnevek, a diéta- és hangulatcímkék, az akciók indoklása („37% cheaper than at the other 5 chains”), a kiszerelések („approx. 425 g (≈ 4 pcs)”), a terv megjegyzései és a hibaüzenetek is.
- **A szerver magyarul számol, a határon fordít** (`i18n/Translator`). Az elemzések (akciók, rangsorok) gyorsítótárazva vannak; ha nyelvenként számolnánk, a memória és az idő is többszöröződne, és egy angol kérés „beszennyezhetné” a magyar választ. Ehelyett a kész JSON-fát fordítjuk: pontos egyezés (recept, lépés, alapanyag, kifejezés), vesszős fűszerlista szavanként, és a generált mondatokra sorban a minták (`resources/i18n/en.json`).
- **Helyi mértékek és számok:** angolul `dkg → g`, `dl → ml`, egyes/többes szám („1 clove”, „2 heads”, „½ jar”), tizedespont, ezres vessző, az euró elöl („€12.49”), a forint mögötte („9,477 Ft”). Dátum és nap a böngésző `Intl`-jével.
- **A terméknevek maradnak**, ahogy a bolt hívja őket (a polcon is így látod).
- **Közös lista, külön nyelv:** a pipák kulcsa a tétel eredeti neve (`labelKey`), így a párod magyarul, te angolul ugyanazt a listát pipáljátok.
- **Nyelvfelismerés:** a választott nyelv (eszközönként), különben a böngésző első támogatott nyelve, különben angol. Az API `?lang=` nélkül az `Accept-Language` fejlécet nézi.
- **Új nyelv:** egy `resources/i18n/{nyelv}.json` (a szerkezete az `en.json`-é), egy `static/lang/{nyelv}.js` szótár (`I18N.xx = {…}`, a kulcs a magyar szöveg), a nyelv kódja a `Lang.SUPPORTED`-ban és az `i18n.js` `LANGS`-ában, és a fájl a `sw.js` listájában. A felület csak a választott nyelv szótárát tölti le (az `i18n.js` mag 4,6 KB), így az újabb nyelvek nem növelik a letöltést. Ami hiányzik, magyarul jelenik meg – fokozatosan is fordítható. A teszt (`testEnglishCoversEveryRecipeAndIngredient`) jelzi, ha egy új recept fordítása hiányzik.

**2. Offline, a boltban is.** A sorok között gyakran nincs térerő – épp akkor, amikor a listát nézed. A service worker (`sw.js`) minden GET-et előbb a hálózatról kér (online mindig friss), a választ eltárolja, és ha nincs hálózat, a legutóbbit adja: a hét, a lista, a receptek offline is megnyílnak. A pipák offline azonnal látszanak, sorba állnak, és amint újra van hálózat, felmennek (egy tétel utolsó állapota számít); a fejléc alatt egy sáv jelzi az offline állapotot. A profil egy másolata az eszközön is megvan, hogy az app offline is elinduljon.

**Németül is.** Horvátország legnagyobb turistacsoportja németajkú, és az EU legnagyobb nyelvi piaca: a felület, mind a 170 recept („Paprikahuhn mit Nockerln”, „Topfenknödel”), az alapanyagok, a generált mondatok és a keresés („Milch” → tej, „Kaffee” → kava) németül is; dkg → g, dl → ml, „am Mittwoch”.

**Akadálymentesség.** Az axe-core átvizsgálás szerint mind a 8 nézet (első indítás, hét, receptlap, lista, akciók, receptek, Nom vagy nem, beállítások) világos és sötét módban is hibátlan: javítva a zöld címkék és gombok kontrasztja (sötét módban sötét szöveg a világos zöld / piros alapon), az adagár-kiemelés, a hét H1 címsora, és a figyelmeztető sávok feliratos régióban vannak.

**Horvátul is.** A horvát piac mellé horvát felület: mind a 170 recept, az alapanyagok és a generált mondatok. A horvát háromféle többes számot ismer (1 porcija · 2 porcije · 5 porcija; 1 glavica · 3 glavice · 5 glavica) – a kliensben az `Intl.PluralRules`, a szerveren a mértékegységek `[egy, néhány, sok]` alakja dönt; a napok tárgyesetben („za srijedu”). A számformátum európai marad (12,49 €), a dkg grammra vált, a dl marad. Nyelvenként egy `options` blokk szabja meg (`numbers`, `metric`).

**Keresés a saját nyelveden.** Minden nyelvi fájlnak van egy keresési szótára (`search`: helyi szó → magyar), és az alapanyagnevek visszafelé is működnek. A keresőszó útja: a felület nyelve → magyar → a piac nyelve. Így egy angol felhasználó a magyar boltban a „milk”-re tejet, a horvátban a „coffee”-ra *kava*-t talál; a horvát szó a horvát boltban változatlan marad.

**Használat közben:** a Receptek keresője a **hozzávalókban is** keres, több szóval („cukkini”, „csirke rizs” → amiben mindkettő van – mit főzzek abból, ami otthon van); a receptlapon **🔗 Megosztás** (link, amit a másik a saját nyelvén és boltja áraival lát); asztali gépen **🖨 Nyomtatás**: két hasábos, papírra szabott lista. A Receptek listája az elkészítés nélkül jön (a receptlap külön kéri): 129 KB helyett 85 KB. A lista tetején **„Kipipáltak elrejtése”** (a boltban csak az marad látható, ami még hiányzik; eszközönként megjegyzi); a hét tetején **📅 Naptárba**: a vacsorák `.ics` fájlban (18:30–19:30, adagár, recept-link), a Google / Apple / Outlook naptár megnyitáskor felveszi. **Telepíthető:** 192/512 px-es és maszkolható PNG ikon, Apple touch icon, gyorsindítás (Lista, Hét); ahol a böngésző engedi, a Beállításokban „Telepítés a kezdőképernyőre” gomb, iPhone-on útmutató.

**3. Bírja a forgalmat.**
- **Virtuális szálak** (Java 21) a 4 szálas fix pool helyett: a lassú mobilos kliensek nem foglalják le a szervert.
- **ETag + 304** a statikus fájlokra (a JAR-ból egyszer olvasva, memóriában), a betűk egy évig a böngészőben maradnak; gzip eddig is volt.
- **Kéréskorlát** IP-címenként (`RATE_LIMIT`, alapból 600 API-kérés / perc – egy háztartásnak NAT mögött is bőven elég, egy elszabadult szkriptet megfog); 429 + `Retry-After`, a felület nyelvén. Proxy mögött `TRUST_PROXY=true`.
- **`/healthz`** (`{"ok":true,"markets":[…]}`) a terheléselosztóknak, Kubernetesnek; a Dockerfile `HEALTHCHECK`-je is ezt hívja (curl nélkül).
- A szerver állapotmentes a PostgreSQL-lel (`DATABASE_URL`): több példány futhat egy terheléselosztó mögött; az árakat mindegyik maga tölti.

**Ami ezután a legtöbbet hozná** (a [Más országok](#más-országok-van-máshol-is-nyilvános-bolti-áradat) táblázat alapján): **Szerbia** a nyílt adat portál CSV-jéből, majd ahol nincs bolti áradat, ott az Open Prices / Eurostat-átlagárak jól jelölt becslésként – a NomNom is így csinálja.

## Mit tanultunk a NomNom Plannerből?

A [nomnomplanner.com/hu](https://nomnomplanner.com/hu/), az [adatforrás-oldaluk](https://nomnomplanner.com/open-data/), az [adatvédelmi tájékoztatójuk](https://nomnomplanner.com/hu/privacy/) és a [blogjuk](https://nomnomplanner.com/hu/blog/heti-vacsora-ara/) alapján (2026. október):

- **Árak:** Magyarországon ők is a **GVH Árfigyelő** nyilvános napi adatát töltik le minden nap, és minden hozzávalót egy konkrét, névvel azonosított bolti termékhez kötnek – ugyanazt csinálja ez az app is. A Kifli árait a Kifli saját katalógusából veszik; ahol egy alapanyag a GVH-adatban nincs benne, ott a Kifli-árat használják minden boltra (ezt maguk is jelzik). Más országokban az Open Food Facts **Open Prices** adatbázisából (ODbL), statisztikai hivatalok és az Eurostat átlagáraiból számolnak. Akciók és hűségkártyás árak náluk sincsenek – nálunk felvihetők.
- **Receptek:** a nyílt adat oldaluk szerint a recepteket a **Wikibooks Cookbookból** adaptálták (CC BY-SA 4.0); több mint 1000 vacsorarecept. Ebben az appban 170 saját, magyar recept van, a magyar boltok Árfigyelő-kategóriáihoz kötve.
- **Fotók:** a forrásukat nem írják le. Itt egyelőre nincs fotó.
- **Tárolás:** fiók nincs; a tervek, beállítások és pipák a telefonon vannak. A szerveroldalon (Cloudflare) csak a receptértékeléseket, a visszajelzéseket és a várólistát tárolják, analitikára a PostHog EU-s szervereit, előfizetésre a RevenueCatet használják. Itt böngészős app van, ezért a háztartás adatai a szerveren vannak (fájlban vagy PostgreSQL-ben), de ugyanúgy fiók és személyes adat nélkül.
- **Felület, amit átvettünk:** kétperces beállítás (bolt, keret, létszám, diéta, konyha) → kész hét vacsorakártyákkal, adagárral és a hét végösszegével a keret mellett → egy vacsora vagy az egész hét cseréje → makrók és hangulat-címkék → diéták és konyhai eszközök beépítve → osztályonként rendezett, megosztható lista → „Nom vagy nem” lapozás.

## Más országok: van máshol is nyilvános bolti áradat?

A NomNom országválasztójában 32 piac van (EU, Egyesült Királyság, USA, Kanada, Ausztrália), ezért úgy tűnhet, hogy sok országnak van a GVH Árfigyelőhöz hasonló nyilvános adata. **Nincs így:** a [NomNom](https://nomnomplanner.com/) saját GYIK-je szerint valódi bolti polcárat csak Magyarországon használnak; az USA-ban, Németországban, Franciaországban, Svédországban és Dániában országos átlagárakat (statisztikai adatokból), máshol becslést, „amíg helyi árak nem lesznek”. Az [Open Prices](https://prices.openfoodfacts.org) közösségi adatbázisából országonként csak néhány tucat sort vesznek át ([open-data oldaluk](https://nomnomplanner.com/open-data/): Németország 39, Franciaország 67, Svédország 33, USA 41, Dánia 0 sor).

A valódi, lánconkénti árak hivatalos forrásait 2026. novemberében így találtam:

| Ország | Mi van | Géppel olvasható, nyilvános? | Itt |
|---|---|---|---|
| 🇭🇺 Magyarország | GVH Árfigyelő – napi Excel, láncszintű legolcsóbb / legdrágább bolti ár | igen, ingyen | **használjuk** |
| 🇭🇷 Horvátország | 2025. május 2. óta ([NN 75/2025](https://narodne-novine.nn.hr/clanci/sluzbeni/2025_05_75_979.html)) a láncok **minden boltjuk** napi árlistáját géppel olvasható formában teszik közzé, akciós árral, 30 napos legalacsonyabb árral és a május 2-i „sidrena” árral; 2026. november 17-től minden kereskedőre (CSV vagy XML, munkanapokon reggel 8-ig – [mojafirma.hr](https://mojafirma.hr/clanci/strojno-citljiv-cjenik-csv-xml-2026)). Központi portál nincs, de a nyílt forráskódú [cijene.dev](https://cijene.dev) ([forrás](https://github.com/senko/cijene-api)) 29 lánc listáit gyűjti, és napi ZIP-archívumot ad regisztráció nélkül (`/v0/list`, 2025. június óta minden nap) | igen | **használjuk (béta)** |
| 🇷🇸 Szerbia | 2025. december 9. óta nemzeti platform 27 lánc árlistáival ([N1](https://n1info.rs/biznis/cene-trgovinski-lanci-onlajn/)); 2026. szeptember 1-től a nagy kereskedők boltonkénti, gépi feldolgozásra alkalmas árlistát tesznek közzé a honlapjukon **és CSV-ben a nyílt adat portálon** ([biznis.rs](https://biznis.rs/vesti/srbija/nova-pravila-za-objavljivanje-cena-trgovaca-od-1-septembra/)) | igen (új) | a következő jelölt |
| 🇸🇰 Szlovákia | [cenyslovensko.sk](https://spectator.sme.sk/business/c/slovakia-rolls-out-food-price-tracker) (Pénzügyminisztérium, 2025 nyara, az Árfigyelő mintájára – [economx](https://www.economx.hu/gazdasag/2025/07/07/arfigyelo-honlap-szlovakia-elelmiszerar-inflacio-812251/)): Billa, Fresh, Kaufland, Lidl, Terno, Tesco, naponta reggel 6-ig | letöltést / API-t nem találtam | nem |
| 🇷🇴 Románia | Monitorul Prețurilor (Versenyhivatal): ~30 000 termék, 1700+ bolt, a láncok naponta töltik fel ([financialintelligence.ro](https://financialintelligence.ro/consiliul-concurentei-aplicatia-monitorul-preturilor-a-fost-imbunatatita/)) | nyílt letöltés nem dokumentált | nem |
| 🇧🇬 Bulgária | kolkostruva.bg (Fogyasztóvédelmi Bizottság, 2025 – [BTA](https://www.bta.bg/en/news/977611-new-retail-price-platform-kolkostruva-bg-fully-operational)) | nem tudtam ellenőrizni | nem |
| 🇬🇷 Görögország | [e-katanalotis](https://www.gov.gr/en/ipiresies/polites-kai-kathemerinoteta/enemerose-katanaloton/e-katanalotes) (minisztériumi app a szupermarketek áraival) | nyílt letöltés nem dokumentált | nem |
| 🇦🇹 Ausztria | Statistik Austria [Preisradar](https://bmf.gv.at/presse/pressemeldungen/2026/jaenner-2026/inflation-preisradar.html) (2026): árváltozások, nem lánconkénti árak | – | nem |
| 🌍 Európán kívül | Izrael: a 2014-es élelmiszer-versenytörvény óta a nagy láncok a teljes árlistájukat az interneten teszik közzé ([Globes](https://en.globes.co.il/en/article-israeli-retailers-must-publish-prices-on-internet-1000983383)); Argentína: SEPA / Precios Claros, napi adatkészletek ([Perfil](https://www.perfil.com/noticias/economia/el-gobierno-difundira-12-millones-de-precios-diarios-de-supermercados-como-funciona-el-sistema.phtml)); Malajzia: [PriceCatcher](https://open.dosm.gov.my/data-catalogue/pricecatcher) nyílt adat | igen | nem (más nyelv, más konyha) |
| 🌍 mindenhol | [Open Prices](https://prices.openfoodfacts.org) (Open Food Facts, ODbL) – közösségi, vonalkódos árak | igen, de ritkás | nem |

**Ami ebből az appba került:** az app országfüggetlen lett (`market/`), Horvátország a második ország. Ugyanaz a 170 recept, ugyanazok a kiszerelések és tápértékek, csak a boltok, az árforrás, az alapanyag-illesztés és a pénznem helyi:

1. **Forrás:** a cijene.dev aznapi archívuma (`CijeneArchiveProvider`). Lánconként `stores.csv`, `products.csv`, `prices.csv`; a boltonkénti árakból lánconként ugyanazt állítjuk elő, mint amit az Árfigyelő ad: a legolcsóbb és a legdrágább bolt árát, hány boltban kapható, hány boltja van. A drogéria (dm) és a nagykereskedés (Metro) kimarad.
2. **Kiszerelés:** a horvát listák lánconként másképp írják („0.45 kg”, „500g”, „1.00 ko”, „10 kom”, „JAJA M 10/1”); a „kg” egységű, 1 kg-os sorok **mérlegelősek** (`byWeight`), a darabos sorok tömege a névből jön („KRUH 500 g” → 0,5 kg), ha semmi nincs, az ár / egységár adja.
3. **Illesztés:** a láncoknak nincs közös kategóriája, ezért a terméknévből (`NameRules`, szabályok: `markets/hr.json`): pl. a csirkemell „a név első szava *pileć…*, és van benne *prsa* vagy *file*, de nincs *pohan* (rántott), *dimlj* (füstölt), *narezak* (felvágott)…”. A szabályokat a cijene.dev nyílt termékadatbázisának **29 684 valódi nevén** hangoltam; minden alapanyag külön ellenőrizve (kész ételek, felvágottak, édességek, állateledel, kozmetikumok kizárva).
4. **Akció:** ahol a lánc boltjainak legalább harmadában akciós ár van, az a lánc saját akciója (a „Bolti akció” jel, a rendes árral).
5. **Pénz:** belül forint-egyenérték, a felület euróban mutat (12,49 €); a keret euróban adható meg (30–100 € javaslatokkal).
6. **Keresés magyarul:** „kávé” → *kava*, „tej” → *mlijeko*, „mosogatószer” → *deterdžent za suđe* (szinonimalista a `hr.json`-ban).
7. **Betöltés:** csak az első horvát kéréskor, a háttérben; addig a felület töltőképernyőt mutat a letöltés állásával. Hibánál „Újrapróbálom” vagy „Vissza a magyar árakhoz”.

Egy valósághű méretű próbán (15 lánc, 225 000 termék, 4,5 millió boltonkénti ár) a feldolgozás 10 s, a heti terv 0,1–0,2 s, a keresés 0,06 s volt, 512 MB memóriával.

## Ami élvezhetőbb lett

A felületet végigkattintottam telefonon és asztalin (Playwright), és ezeket javítottam:

- **Hibák:** a vacsorakártyáknak nem volt háttere (egy rossz CSS-szelektor miatt csak frissítés közben kaptak), a Receptek fejléce alá a fejléc stílusa került (fehér doboz), a dátum „október 28.-i” volt („28-i” a helyes), a beállítások diéta-chipjein üres színsáv volt, a nézetváltás nyitva hagyta a lapokat, a fejléc fül egérrel „beragadt”.
- **Bolt-ugrálás:** egy csere után a bolt nem vált át, ha a mostani csak kicsit drágább (lásd 5. pont).
- **Csere:** választó 6 javaslattal és a hét árának változásával, „Lepj meg”, **Visszavonás**.
- **Változatosság:** nincs két karfiolos, két fasírtos, két pörköltös vacsora egy héten, krumpli legfeljebb kétszer.
- **Napok:** a hét mától indul, a kártyákon dátum, a mai kiemelve, az elmúlt napok halványabbak.
- **Főzés mód** a receptlapról.
- **Kártyák:** nagy étel-jel színes háttéren (fotó helyett), finom megjelenés, csere után átforduló kártya.
- **Lista:** haladássáv, pipálás-animáció, a végén ünneplés.
- **Betűk** az appba csomagolva (offline is szép, nincs külső kérés), egységes ezres tagolás (5 484 Ft), sötét mód a kártyákon is.

## Mit vettünk át az ajanlatok.hu-tól, a SmartKosártól és a Shoppytól?

Mindhárom azt csinálja, mint ez az app: magyar élelmiszerárakat tesz összevethetővé. A nyilvános oldalaik, áruházi leírásaik és a róluk szóló cikkek alapján (2026. november):

| | Mit csinál | Honnan vannak az árak? |
|---|---|---|
| **[ajanlatok.hu](https://www.ajanlatok.hu/)** | akciósújság-gyűjtő 2020 óta (egy nemzetközi hálózat magyar oldala); láncok és üzletek szerint, helyhez kötve; minden ajánlatot az **elmúlt 8 hét** árengedményeihez és gyakoriságához mér; bevásárlólista (fiók nélkül 10 tétel), kedvencek, értesítés, mobilapp | a boltok **akciós újságjai és szórólapjai**, amelyeket saját közlésük szerint naponta gyűjtenek; az újságos (és nem a polci) ár |
| **SmartKosár** ([Trademagazin](https://trademagazin.hu/en/sporolas-a-smartkosarral), [Pénzcentrum](https://www.penzcentrum.hu/vasarlas/20180912/dobbenetes-osszegeket-sporolhatsz-egy-uj-magyar-talalmany-segitsegevel-1070094)) | **egész kosarat** hasonlít össze a nagy online élelmiszer-áruházak között, és megmutatja, hol a legolcsóbb az egész; 2018-ban a Start it @K&H inkubátorban indult | a láncok **webshopjainak** kínálata és árai (a gyűjtés módját nem írják le) – vagyis házhozszállításos online ár, nem bolti |
| **Shoppy** ([Trademagazin](https://trademagazin.hu/en/mostantol-egyszerubb-a-legkedvezobb-arat-es-a-hazait-is-megtalalni), [App Store](https://apps.apple.com/by/app/shoppy/id6450847442)) | ingyenes ár- és termék-összehasonlító oldal és app: keresés, vonalkód-olvasás, bevásárlólista, kedvencek, akciók; a **Magyar Termék Nonprofit Kft.**-vel együttműködve kiemeli a magyar termékeket | a forrását nem nevezi meg; a „kereskedőktől független” összehasonlítás a láncok nyilvános (webes) árain alapulhat |

**Az árak eredete – a lényeg:** ezek az oldalak vagy az akciós újságot (ajanlatok.hu), vagy a webshopok árait (SmartKosár) használják; egyik sem ad nyilvános API-t, és egyik sem a bolti polcárat. A **bolti, láncszintű polcár egyetlen nyilvános, ingyenes és jogilag tiszta forrása ma a GVH Árfigyelő** napi Excele – ezért ez az app továbbra is erre épül, a láncok újságos akciói pedig a (most már admin által kezelt) akciórétegen jöhetnek be.

**Amit átvettünk:**
- **Böngészhető „Most jó áron” nézet minden boltra** (ajanlatok.hu, Shoppy): az új **Akciók** fül, kereséssel és boltszűrővel; nem kell előbb receptet választani.
- **Ajánlat a saját előzményéhez mérve** (ajanlatok.hu, 8 hét): a termékablakban az elmúlt 8 hét ára grafikonnal, mediánnal és ítélettel; a „Olcsóbb a szokásosnál” jel is ebből jön.
- **Egész kosár összevetése** (SmartKosár): a hét ára már eddig is minden boltban látszott; most a **saját tételek is** beleszámítanak, így a „hol a legolcsóbb” a valódi heti bevásárlásra igaz, nem csak a vacsorákra.
- **Bevásárlólista szabad tételekkel és termékből** (mindhárom): saját tételek a listán, a termékablakból egy gombbal.
- **Karbantartható adat** (mindhárom mögött szerkesztőség áll): az admin felület, ahol a receptek, az alapanyag-illesztés és az akciók kódolás nélkül javíthatók, és látszik, mi hiányzik melyik láncból.

Amit **nem** vettünk át: fiókot és helyalapú értesítést (fiók nélküli app maradt), vonalkód-olvasást (az Árfigyelő Excelben van vonalkód, de a böngészős kamera-olvasás külön munka), és a webshopok árait (más ár, mint a boltban, és csak dokumentálatlan végpontokon érhető el).

---
Az árak forrása: [GVH Árfigyelő](https://arfigyelo.gvh.hu). A boltban kiírt ár az irányadó.
