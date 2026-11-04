// Spórolós Konyha – a felület nyelve. Az app.js előtt töltődik be; a választott nyelv szótárát (lang/{nyelv}.js)
// szinkron betölti, így az app.js indulásakor már megvan. A statikus HTML-t az app.js fordítja le induláskor.
// A kulcs a magyar szöveg ({name} helyőrzőkkel); ami nincs a szótárban, magyarul marad. A tömb értéke
// egyes/többes szám ([egy, több]) a {n} szerint. A receptek, alapanyagok és a szerver generált mondatai a szervertől
// jönnek lefordítva (hu.sporolos.i18n.Translator, resources/i18n/en.json) – az api() minden kéréshez hozzáteszi a nyelvet.
"use strict";

/** A választható nyelvek: kód, saját nevén, zászló. */
const LANGS = [["hu", "Magyar", "🇭🇺"], ["en", "English", "🇬🇧"], ["hr", "Hrvatski", "🇭🇷"], ["de", "Deutsch", "🇩🇪"]];

/**
 * A felület nyelve: amit a felhasználó választott (ezen az eszközön), különben a böngésző első támogatott nyelve,
 * különben angol – aki se magyarul, se angolul nem kér, angolul ért többet.
 */
const LANG = (() => {
  const ok = (l) => LANGS.some(([c]) => c === l);
  try {
    const saved = localStorage.getItem("sporolos.lang");
    if (ok(saved)) return saved;
  } catch (e) { /* privát mód */ }
  for (const l of navigator.languages || [navigator.language || ""]) {
    const base = String(l).toLowerCase().split("-")[0];
    if (ok(base)) return base;
  }
  return "en";
})();
const LOCALE = { hu: "hu-HU", en: "en-GB", hr: "hr-HR", de: "de-DE" }[LANG];
const PLURAL = new Intl.PluralRules(LOCALE);

/** „szerdára” horvátul: a nap neve tárgyesetben („za srijedu”); angolul a „for {day}” minta elég. */
const DAY_FOR = {
  hr: { Ponedjeljak: "za ponedjeljak", Utorak: "za utorak", Srijeda: "za srijedu", Četvrtak: "za četvrtak",
    Petak: "za petak", Subota: "za subotu", Nedjelja: "za nedjelju" },
  de: { Montag: "am Montag", Dienstag: "am Dienstag", Mittwoch: "am Mittwoch", Donnerstag: "am Donnerstag",
    Freitag: "am Freitag", Samstag: "am Samstag", Sonntag: "am Sonntag" },
};

/** A szótárak nyelvenként külön fájlban (lang/{nyelv}.js): mindenki csak a sajátját tölti le. */
const I18N = {};
if (LANG !== "hu") document.write(`<script src="lang/${LANG}.js"><\/script>`);

/**
 * Fordítás: t("{n} adag", { n: 2 }) → „2 servings”. A tömbös érték a {n} szerinti alak: [egy, több] (angol),
 * vagy [egy, néhány, sok] (horvát: 1 porcija, 2 porcije, 5 porcija) – a böngésző Intl.PluralRules-a dönt.
 */
function t(hu, vars) {
  let s = LANG === "hu" ? hu : (I18N[LANG] || {})[hu] ?? hu;
  if (Array.isArray(s)) {
    const cat = vars && vars.n != null ? PLURAL.select(Number(vars.n)) : "other";
    s = s.length === 2 ? s[cat === "one" ? 0 : 1] : s[cat === "one" ? 0 : cat === "few" ? 1 : 2];
  }
  if (!vars) return s;
  return s.replace(/\{(\w+)\}/g, (m, k) => (k in vars ? String(vars[k]) : m));
}

/** A nyelvválasztó (az első indításnál és a Beállításokban): váltáskor az oldal újratöltődik. */
function langSwitch() {
  const row = document.createElement("div");
  row.className = "lang-switch";
  row.setAttribute("role", "group");
  row.setAttribute("aria-label", "Nyelv / Language");
  for (const [code, name, flag] of LANGS) {
    const b = document.createElement("button");
    b.type = "button";
    b.lang = code;
    b.setAttribute("aria-pressed", String(code === LANG));
    b.textContent = `${flag} ${name}`;
    b.addEventListener("click", () => {
      if (code === LANG) return;
      try { localStorage.setItem("sporolos.lang", code); } catch (e) { /* privát mód: csak erre a betöltésre */ }
      location.reload();
    });
    row.appendChild(b);
  }
  return row;
}

/** A statikus HTML szövegei és feliratai a felület nyelvén (a magyar az alap). */
function translateDom(root) {
  document.documentElement.lang = LANG;
  if (LANG === "hu") return;
  document.title = t(document.title);
  const desc = document.querySelector('meta[name="description"]');
  if (desc) desc.content = t(desc.content);
  const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
  for (let n = walker.nextNode(); n; n = walker.nextNode()) {
    const raw = n.nodeValue;
    const key = raw.trim();
    if (!key || n.parentElement?.closest("script, style")) continue;
    const tr = t(key);
    if (tr !== key) n.nodeValue = raw.replace(key, tr);
  }
  for (const e of root.querySelectorAll("[placeholder], [aria-label], [title]")) {
    for (const a of ["placeholder", "aria-label", "title"]) {
      const v = e.getAttribute(a);
      if (v) e.setAttribute(a, t(v));
    }
  }
}

