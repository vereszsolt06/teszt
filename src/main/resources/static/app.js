// Spórolós Konyha – kliensoldal. Nincs keretrendszer: fetch + DOM, minden szöveg textContent-tel kerül ki.
"use strict";

// a statikus HTML a felület nyelvén (a szótár az i18n.js-ből jön)
translateDom(document.body);

const $ = (sel, root = document) => root.querySelector(sel);
const $$ = (sel, root = document) => Array.from(root.querySelectorAll(sel));

const state = {
  status: null,
  profile: null,      // {id, settings, liked, disliked, storage}
  week: null,         // /api/week válasz
  view: "het",
  recipes: null,      // /api/recipes (a kiválasztott boltra)
  recipesKey: "",
  deck: [],
  filter: "",
  sheet: null,        // a nyitott receptlap: {id, servings, data, swapped, day}
  ingredients: null,
  weekBusy: 0,
  prevDays: [],       // az előző kirajzolás receptjei (az új kártyák animálásához)
};

const DEAL_LABEL = {
  CHAIN: t("Itt a legolcsóbb"),
  HISTORY: t("Olcsóbb a szokásosnál"),
  SPREAD: t("Akciógyanús"),
  CATEGORY: t("Tartósan olcsó"),
  PROMO: t("Bolti akció"),
};
const DIETS = [["vega", t("Húsmentes")], ["gluténmentes", t("Gluténmentes")], ["laktózmentes", t("Laktózmentes")]];
const EQUIPMENT = [["tuzhely", t("Tűzhely")], ["suto", t("Sütő")], ["airfryer", t("Airfryer")]];
const DAY_TO = { Hétfő: "hétfőre", Kedd: "keddre", Szerda: "szerdára", Csütörtök: "csütörtökre", Péntek: "péntekre", Szombat: "szombatra", Vasárnap: "vasárnapra" };
const IN_STORE = {
  aldi: "az Aldiban", lidl: "a Lidlben", penny: "a Pennyben", auchan: "az Auchanban",
  tesco: "a Tescóban", spar: "a Sparban",
  // Horvátország
  konzum: "a Konzumban", kaufland: "a Kauflandban", plodine: "a Plodinében", studenac: "a Studenacban",
  tommy: "a Tommyban", eurospin: "az Eurospinben", ktc: "a KTC-ben", ribola: "a Ribolában", ntl: "az NTL-ben",
  zabac: "a Žabacban", trgocentar: "a Trgocentarban", vrutak: "a Vrutakban", roto: "a Rotóban", boso: "a Bosóban",
  brodokomerc: "a Brodokomercben", lorenco: "a Lorencóban", trgovina_krk: "a Trgovina Krkben",
};
const HU_MARKET = { id: "hu", name: t("Magyarország"), flag: "🇭🇺", currency: "HUF", symbol: "Ft", rate: 1, decimals: 0, source: "GVH Árfigyelő", sourceUrl: "https://arfigyelo.gvh.hu" };
/** Keret-javaslatok az ország pénznemében (az első indításnál). */
const BUDGETS = { HUF: [8000, 12000, 15000, 20000, 30000], EUR: [30, 40, 50, 70, 100] };
const DEFAULT_BUDGET = { HUF: 15000, EUR: 50 };

// ---------- formázás, DOM ----------

const nf = new Intl.NumberFormat(LOCALE, { maximumFractionDigits: 0 });
const NB = "\u00a0";
/** Ezres tagolás: magyarul nem törhető szóközzel (5 484), angolul vesszővel (5,484). */
const group = (n) => String(n).replace(/\B(?=(\d{3})+(?!\d))/g, LANG === "en" ? "," : NB);
const DEC = LANG === "en" ? "." : ",";

/** A megjelenített ország (pénznem, árforrás); az árak belül forint-egyenértékben jönnek. */
const market = () => state.status?.market || state.marketHint || HU_MARKET;
/** Az ország, ahol a háztartás vásárol (a kérésekhez). */
const country = () => state.profile?.settings?.country || ob.draft?.country || "hu";

/** Pénz a kiválasztott ország pénznemében: 5 484 Ft, 12,49 € (angolul 5,484 Ft, €12.49). */
function ft(v) {
  const m = market();
  const x = (Number(v) || 0) / (m.rate || 1);
  const sign = x < 0 ? "−" : "";
  let num;
  if (!m.decimals) num = group(Math.round(Math.abs(x)));
  else {
    const c = Math.round(Math.abs(x) * 100);
    num = group(Math.floor(c / 100)) + (c % 100 ? `${DEC}${String(c % 100).padStart(2, "0")}` : "");
  }
  // angolul az euró elöl áll (€12.49), a forint marad mögötte
  if (LANG === "en" && m.symbol === "€") return `${sign}€${num}`;
  return `${sign}${num}${NB}${m.symbol}`;
}
/** Belső összeg ↔ a felhasználó pénzneme (a keret mezőihez). */
const toLocalMoney = (internal) => (Number(internal) || 0) / (market().rate || 1);
const toInternalMoney = (local) => Math.round((Number(local) || 0) * (market().rate || 1));
const cap = (s) => s.charAt(0).toUpperCase() + s.slice(1);
/** „október 28.” → „október 28-i” (a magyar dátum toldaléka pont nélkül); angolul „28 October”. */
function dateAdj(d) {
  if (LANG !== "hu") return d.toLocaleDateString(LOCALE, { month: "long", day: "numeric" });
  return d.toLocaleDateString("hu-HU", { month: "long", day: "numeric" }).replace(/\.$/, "") + "-i";
}
const shortDate = (iso) => new Date(iso + "T00:00:00").toLocaleDateString(LOCALE, { month: "short", day: "numeric" });
/** Mértékegység a felület nyelvén (db → pc). */
const unitName = (u) => (u === "db" ? t("db") : u);

function qty(amount, unit) {
  const comma = (n) => String(n).replace(".", DEC);
  if (unit === "kg" && amount < 1) return `${Math.round(amount * 1000)} g`;
  if (unit === "l" && amount < 1) return !["en", "de"].includes(LANG) ? `${comma(Math.round(amount * 100) / 10)} dl` : `${Math.round(amount * 1000)} ml`;
  return `${comma(Math.round(amount * 100) / 100)} ${unitName(unit)}`;
}

function el(tag, cls, text, attrs) {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (text != null) e.textContent = text;
  if (attrs) for (const [k, v] of Object.entries(attrs)) e.setAttribute(k, v);
  return e;
}

function local(key, value) {
  try {
    if (value === undefined) return localStorage.getItem(key);
    if (value === null) localStorage.removeItem(key);
    else localStorage.setItem(key, value);
  } catch (e) {
    return null;
  }
  return null;
}

/**
 * API-hívás. A nem magyar országoknál hozzáteszi a „market” paramétert. Ha az ország árai még töltődnek
 * (503 + loading), töltőképernyőt mutat, és addig próbálja újra, amíg kész nem lesz – a hívó ebből semmit nem lát.
 */
async function api(path, opts, forCountry) {
  const c = forCountry || country();
  if (path.startsWith("/api/") && !path.startsWith("/api/admin") && c !== "hu" && !/[?&]market=/.test(path)) {
    path += (path.includes("?") ? "&" : "?") + "market=" + encodeURIComponent(c);
  }
  // a szerver a megjelenített szövegeket (receptek, indoklások) a felület nyelvén küldi
  if (path.startsWith("/api/") && !path.startsWith("/api/admin")) path += (path.includes("?") ? "&" : "?") + "lang=" + LANG;
  if (!opts || !opts.method || opts.method === "GET") seenGets.add(path);
  for (let attempt = 0; ; attempt++) {
    let res;
    try {
      res = await fetch(path, opts);
    } catch (e) {
      throw offlineError();
    }
    let body = null;
    try { body = await res.json(); } catch (e) { /* nem JSON */ }
    if (res.status === 503 && body?.offline) throw offlineError();
    if (res.status === 503 && body && "loading" in body && (!opts || !opts.method || opts.method === "GET")) {
      await waitForMarket(body);
      continue;
    }
    hideLoader();
    if (!res.ok) {
      const err = new Error(body?.error || t("Hiba ({status})", { status: res.status }));
      err.status = res.status;
      throw err;
    }
    return body;
  }
}

/** A betöltött GET-kérések: amikor a service worker először átveszi az oldalt, ezeket újra lekérjük, hogy offline is meglegyenek. */
const seenGets = new Set();

/** Nincs hálózat (és a service worker sem tudott korábbi választ adni). */
function offlineError() {
  const err = new Error(t("Nincs internet – ehhez most kapcsolat kell."));
  err.offline = true;
  return err;
}

// ---------- egy ország árainak betöltése (pl. az első horvát kéréskor) ----------

let loaderRetry = null;
function waitForMarket(info) {
  const m = info.market || {};
  const box = $("#loader");
  box.hidden = false;
  $("#loader-flag").textContent = m.flag || "🛒";
  const failed = info.failed;
  const cname = m.name || t("Az ország");
  $("#loader-title").textContent = failed ? t("{name} árai most nem tölthetők be", { name: cname }) : t("{name} árai töltődnek…", { name: cname });
  $("#loader-text").textContent = failed
    ? t("{error} Lehet, hogy nincs internet, vagy a forrás ({source}) épp nem érhető el.", { error: info.error || "", source: m.source || "" })
    : t("Forrás: {source}. Az első alkalommal ez egy-két perc (a napi árlista nagy), utána naponta egyszer frissül.", { source: m.source || "" });
  $("#loader-phase").textContent = failed ? "" : (info.phase || t("Indul…"));
  $("#loader-bar").style.width = `${Math.round((info.progress || 0) * 100)}%`;
  $("#loader-actions").hidden = !failed;
  $(".loader-meter").hidden = !!failed;
  return new Promise((resolve) => {
    if (failed) { loaderRetry = resolve; return; }
    setTimeout(resolve, 1500);
  });
}
function hideLoader() {
  const box = $("#loader");
  if (box && !box.hidden) box.hidden = true;
}
const jsonOpts = (method, data) => ({ method, headers: { "Content-Type": "application/json" }, body: JSON.stringify(data) });

let toastTimer;
/** Rövid üzenet alul; opcionálisan egy gombbal (pl. „Visszavonás”). */
function toast(msg, action) {
  const box = $("#toast");
  $("#toast-text").textContent = msg;
  const b = $("#toast-action");
  b.hidden = !action;
  if (action) {
    b.textContent = action.label;
    b.onclick = () => { box.hidden = true; action.run(); };
  }
  box.hidden = false;
  box.classList.remove("pop");
  void box.offsetWidth; // az animáció újraindítása
  box.classList.add("pop");
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { box.hidden = true; }, action ? 6500 : 3600);
}

function openDialog(dlg) {
  if (typeof dlg.showModal === "function") { if (!dlg.open) dlg.showModal(); }
  else dlg.setAttribute("open", "");
}

const settings = () => state.profile?.settings || {};
const storeInfo = (id) => state.status?.stores.find((s) => s.id === id);
const storeName = (id) => storeInfo(id)?.name || id;
const inStore = (id) => (LANG === "hu" ? IN_STORE[id] || `itt: ${storeName(id)}` : t("itt: {store}", { store: storeName(id) }));
/** „szerdára” / „for Wednesday”; a nap neve a szervertől már a felület nyelvén jön. */
const dayTo = (label) => (LANG === "hu" ? DAY_TO[label] || "erre a napra" : DAY_FOR[LANG]?.[label] || t("erre: {day}", { day: label }));
/** A recept- és árnézetek boltja: a beállított, automatikus módban a hét boltja (ahol a hét a legolcsóbb). */
const currentStore = () => settings().store || state.week?.store || bestStore()?.id;

function bestStore() {
  const s = state.status?.stores || [];
  const mine = settings().stores?.length ? s.filter((x) => settings().stores.includes(x.id)) : s;
  return (mine.length ? mine : s).reduce((a, b) => (!a || b.topScore > a.topScore ? b : a), null);
}

/** A beállítások query-paraméterként a boltfüggő kérésekhez. */
function prefQuery() {
  const s = settings();
  const p = new URLSearchParams();
  if (s.stores?.length) p.set("stores", s.stores.join(","));
  if (s.bulk) p.set("bulk", "1");
  if (s.cards?.length) p.set("cards", s.cards.join(","));
  const q = p.toString();
  return q ? "&" + q : "";
}

function filterQuery() {
  const s = settings();
  const q = new URLSearchParams();
  if (s.diets?.length) q.set("diets", s.diets.join(","));
  if (s.equipment?.length && s.equipment.length < 3) q.set("equipment", s.equipment.join(","));
  return q.toString();
}

// ---------- receptjel ----------

/** A recept kis jele: az emoji egy színes körben (fotó nincs). */
function icon(r, cls) {
  let h = 0;
  for (const c of r.id) h = (h * 31 + c.charCodeAt(0)) % 360;
  const e = el("span", `ricon ${cls || ""}`, r.emoji || "🍽️", { "aria-hidden": "true" });
  e.style.setProperty("--h", h);
  return e;
}

/** Ennyit fizetsz a pénztárnál a recepthez (egész csomagokkal), a kamraáru nélkül. */
const payText = (checkout, servings) => `${ft(checkout)} · ${t("{n} adag", { n: servings })}`;

function macroLine(r) {
  if (!r.nutrition) return "";
  return t("{kcal} kcal · {protein} g fehérje", { kcal: r.nutrition.kcal, protein: r.nutrition.protein });
}

function badges(r, max) {
  const wrap = el("span", "badges");
  const list = [...(r.moods || []), ...(r.diets || [])].slice(0, max || 9);
  for (const b of list) wrap.appendChild(el("span", "badge" + (DIETS.some(([d]) => d === b) ? " diet" : ""), t(b)));
  return wrap;
}

// ---------- profil (fiók nélkül: véletlen azonosító, megosztható) ----------

async function loadProfile() {
  const fromLink = new URLSearchParams(location.hash.slice(1)).get("profil");
  if (fromLink) {
    local("sporolos.profile", fromLink);
    history.replaceState(null, "", "#het");
    toast(t("Csatlakoztál a háztartás közös tervéhez."));
  }
  const id = local("sporolos.profile");
  if (!id) return null;
  try {
    state.profile = await api(`/api/profile?id=${encodeURIComponent(id)}`);
    return state.profile;
  } catch (e) {
    // offline: a legutóbb látott profil az eszközön (a hét és a lista a service worker gyorsítótárából jön)
    if (e.offline) {
      try {
        const saved = JSON.parse(local("sporolos.profileData") || "null");
        if (saved && saved.id === id) return (state.profile = saved);
      } catch (err) { /* sérült másolat */ }
    }
    if (e.status === 404 || e.status === 400) {
      local("sporolos.profile", null);
      return null;
    }
    throw e;
  }
}

let saveTimer;
/** Beállítás módosítása; a hét és a receptlista újraszámolódik. */
function updateSettings(patch, immediate) {
  state.profile.settings = { ...settings(), ...patch };
  clearTimeout(saveTimer);
  const run = async () => {
    try {
      state.profile = await api(`/api/profile?id=${state.profile.id}`, jsonOpts("PUT", state.profile.settings));
      state.recipes = null;
      renderStorePill();
      await loadWeek();
      if (state.view === "receptek") renderBrowse();
      if (state.view === "nom") loadDeck();
    } catch (e) { toast(e.message); }
  };
  if (immediate) return run();
  saveTimer = setTimeout(run, 450);
  return null;
}

// ---------- státusz, bolt ----------

async function loadStatus() {
  state.status = await api(`/api/status${prefQuery().replace(/^&/, "?")}`);
  const s = state.status;
  const m = market();
  const d = new Date(s.date + "T00:00:00");
  const dateText = d.toLocaleDateString(LOCALE, { month: "long", day: "numeric" });
  const foot = $("#foot-text");
  foot.replaceChildren();
  if (m.id === "hu") {
    foot.append(t("Árak: "), el("a", null, t("GVH Árfigyelő"), { href: "https://arfigyelo.gvh.hu", target: "_blank", rel: "noopener" }),
      t(" napi adatai lánconként (a lánc legolcsóbb boltjának ára; a te boltodban lehet drágább) és a felvitt bolti akciók."));
  } else {
    foot.append(`${m.flag} ${m.name}${m.beta ? t(" (béta)") : ""}${t(" – árak: ")}`, el("a", null, "cijene.dev", { href: m.sourceUrl, target: "_blank", rel: "noopener" }),
      t(": a láncok törvény által előírt napi árlistái (NN 75/2025), lánconként a legolcsóbb bolt ára; a te boltodban lehet drágább. Az akciós árakat a láncok maguk jelölik."));
  }
  $("#foot-source").textContent = t("Most: {source}, {date}, {n} ár.", { source: s.source, date: dateText, n: nf.format(s.itemCount) });
  const banner = $("#banner");
  if (s.demo) {
    banner.textContent = t("Demó árakat látsz: a napi árlista most nem volt elérhető. Később magától újrapróbálja.");
    banner.hidden = false;
  } else if (s.stale) {
    banner.textContent = t("Az árak {date} állapotot mutatnak.", { date: dateAdj(d) });
    banner.hidden = false;
  } else {
    banner.hidden = true;
  }
  document.documentElement.dataset.market = m.id;
}

function renderStorePill() {
  const id = currentStore();
  const s = storeInfo(id);
  const pill = $("#store-pill");
  pill.style.setProperty("--c", s?.color || "var(--line)");
  const auto = !settings().store;
  pill.querySelector(".pill-text").textContent = s ? s.name : t("Bolt");
  pill.classList.toggle("auto", auto);
  pill.setAttribute("aria-label", t("Bolt: {store}{auto} – módosítás", { store: s ? s.name : t("nincs"), auto: auto ? t(" (automatikus)") : "" }));
}

function storeCards(container, selected, onPick, opts = {}) {
  container.replaceChildren();
  const best = bestStore();
  if (opts.auto) {
    const b = el("button", "store-card auto", null, { type: "button", "aria-pressed": String(selected == null) });
    b.append(el("span", "sc-name", t("Automatikus")), el("span", "sc-meta", t("ahol a heted a legolcsóbb")));
    b.addEventListener("click", () => onPick(null));
    container.appendChild(b);
  }
  for (const s of state.status.stores) {
    if (opts.only && !opts.only.includes(s.id)) continue;
    const b = el("button", "store-card", null, { type: "button", "aria-pressed": String(s.id === selected) });
    b.style.setProperty("--c", s.color);
    const wk = state.week?.compare?.find((c) => c.store === s.id);
    b.append(el("span", "sc-name", s.name), el("span", "sc-meta", wk ? t("a heted itt {price}", { price: ft(wk.checkout) }) : t("{n} jó ár ma", { n: s.dealCount })));
    if (best && s.id === best.id && !wk) b.appendChild(el("span", "sc-best", t("ma itt a legjobb")));
    if (wk && state.week.compare[0].store === s.id) b.appendChild(el("span", "sc-best", t("itt a legolcsóbb")));
    b.addEventListener("click", () => onPick(s.id));
    container.appendChild(b);
  }
}

function openStoreSheet() {
  const mine = settings().stores?.length ? settings().stores : null;
  storeCards($("#store-grid"), settings().store || null, (id) => {
    $("#store-sheet").close();
    updateSettings({ store: id }, true);
  }, { auto: true, only: mine });
  const split = $("#split-toggle");
  split.checked = !!settings().split;
  openDialog($("#store-sheet"));
}

// ---------- nézetek ----------

const VIEWS = ["het", "lista", "akciok", "receptek", "nom"];

function route() {
  const h = location.hash.slice(1);
  const p = new URLSearchParams(h);
  if (p.get("recept")) {
    openRecipe(p.get("recept"), {});
    return;
  }
  setView(VIEWS.includes(h) ? h : "het");
}

function setView(v) {
  state.view = v;
  // nézetváltáskor a nyitott lapok bezárulnak (az első indítás kérdései kivételével)
  for (const d of $$("dialog[open]")) if (d.id !== "onboard") d.close();
  for (const sec of $$(".view")) sec.hidden = sec.dataset.view !== v;
  const navView = v === "nom" ? "receptek" : v;
  for (const a of $$("a[data-view]")) {
    if (a.dataset.view === navView) a.setAttribute("aria-current", "page");
    else a.removeAttribute("aria-current");
  }
  if (v === "het" || v === "lista") {
    if (!state.week) loadWeek().catch((e) => toast(e.message));
    else (v === "het" ? renderWeek : renderList)();
  }
  if (v === "nom") loadDeck().catch((e) => toast(e.message));
  if (v === "akciok") renderOffers().catch((e) => toast(e.message));
  if (v === "receptek") renderBrowse().catch((e) => toast(e.message));
  window.scrollTo(0, 0);
}

// ---------- heti terv ----------

async function loadWeek(action, day, recipe) {
  if (!state.profile) return;
  const my = ++state.weekBusy;
  const q = new URLSearchParams({ id: state.profile.id });
  if (action) q.set("action", action);
  if (day != null) q.set("day", day);
  if (recipe) q.set("recipe", recipe);
  if (!state.week) $("#dinners").replaceChildren(...skeletonCards(settings().dinners || 5));
  const w = await api(`/api/week?${q}`);
  if (my !== state.weekBusy) return;
  state.week = w;
  renderStorePill();
  renderWeek();
  renderList();
}

function skeletonCards(n) {
  return Array.from({ length: n }, () => el("li", "dinner skeleton", null, { "aria-hidden": "true" }));
}

function renderWeek() {
  const w = state.week;
  if (!w) return;
  const people = w.people;
  $("#week-total").textContent = `≈ ${ft(w.checkoutTotal)}`;
  const cooks = w.days.filter((d) => d.leftoverOf < 0).length;
  $("#week-meta").textContent = `${t("{n} vacsora", { n: w.days.length })} · ${t("{n} fő", { n: people })}${cooks < w.days.length ? ` · ${t("{n}× főzöl", { n: cooks })}` : ""} · ${w.storeName}${w.autoStore ? t(", mert itt a legolcsóbb") : ""}`;
  const facts = $("#week-facts");
  facts.replaceChildren();
  const fact = (text, title) => facts.appendChild(el("span", "fact", text, title ? { title } : null));
  if (w.avgKcal) fact(t("~{kcal} kcal / adag", { kcal: w.avgKcal }), t("Egy vacsora-adag átlagosan"));
  if (w.avgProtein) fact(t("{g} g fehérje", { g: w.avgProtein }), t("Egy vacsora-adag átlagosan"));
  if (w.days.length) fact(t("≈ {price} / adag", { price: ft(w.checkoutTotal / Math.max(1, w.days.length * people)) }), t("A hét ára adagonként"));
  const meter = $("#week-meter");
  if (w.budget > 0) {
    meter.hidden = false;
    meter.classList.toggle("over", w.overBudget);
    meter.firstElementChild.style.width = `${Math.min(100, (w.checkoutTotal / w.budget) * 100)}%`;
    $("#week-meter-text").textContent = w.overBudget
      ? t("{budget} a keret · túllépés: {over}", { budget: ft(w.budget), over: ft(w.checkoutTotal - w.budget) })
      : t("{budget} a keret · {left} marad", { budget: ft(w.budget), left: ft(w.budget - w.checkoutTotal) });
  } else {
    meter.hidden = true;
    $("#week-meter-text").textContent = t("nincs megadva heti keret");
  }
  const notes = $("#week-notes");
  notes.replaceChildren(...w.notes.map((n) => el("p", "note-box", n)));
  if (w.split) {
    const n = el("p", "note-box good");
    n.append(t("Ha két boltba is elmész ({stores}), {saving} marad a zsebedben: {total}. ", { stores: w.split.stores.map((x) => x.name).join(" + "), saving: ft(w.split.saving), total: ft(w.split.checkout) }));
    const a = el("a", null, t("A lista boltonként →"), { href: "#lista" });
    n.appendChild(a);
    notes.appendChild(n);
  }
  renderCompare(w);

  const ol = $("#dinners");
  ol.replaceChildren();
  if (!w.days.length) {
    ol.appendChild(el("li", "empty", t("Ezekkel a beállításokkal most nem áll össze terv. Próbáld kevesebb szűrővel vagy másik bolttal.")));
  }
  const prev = state.prevDays;
  w.days.forEach((d, i) => {
    const card = dinnerCard(d, i, people);
    const id = (d.leftoverOf >= 0 ? "=" : "") + d.suggestion.recipe.id;
    if (prev.length && prev[i] !== id) card.classList.add("changed");
    else if (!prev.length) { card.classList.add("enter"); card.style.setProperty("--i", i); }
    ol.appendChild(card);
  });
  state.prevDays = w.days.map((d) => (d.leftoverOf >= 0 ? "=" : "") + d.suggestion.recipe.id);
  $("#week-fine").textContent = (w.staplesTotal > 0 ? t("Az összegben nincs benne {price} kamraáru (olaj, liszt, cukor…), ha venni kell. ", { price: ft(w.staplesTotal) }) : "")
    + t("A vacsoránkénti ár a közös bevásárlólistából rá eső rész: a kártyák összege kiadja a hét árát, a megkezdett csomagok a többi vacsorához jutnak.");
}

/** Ugyanez a hét a többi boltban – egy sor gomb a hét alatt (keskeny kijelzőn görgethető). */
function renderCompare(w) {
  const box = $("#week-compare");
  box.replaceChildren();
  if (!w.compare || w.compare.length < 2) return;
  box.appendChild(el("span", "cmp-label", t("Ugyanez a hét:")));
  const row = el("div", "cmp-row");
  const best = w.compare[0];
  for (const c of w.compare) {
    const b = el("button", "cmp" + (c.store === w.store ? " current" : ""), null, { type: "button", "aria-pressed": String(c.store === w.store) });
    b.style.setProperty("--c", c.color);
    const miss = (c.estimated || 0) + (c.extrasMissing || 0);
    b.append(el("span", "cmp-name", c.name), el("b", null, ft(c.checkout) + (miss ? "*" : "")));
    if (c !== best && c.checkout > best.checkout) b.appendChild(el("span", "cmp-diff", `+${ft(c.checkout - best.checkout)}`));
    b.title = miss ? t("{n} tétel ott nem kapható (vagy nincs a forrásban) – a sorrendben ezt is figyelembe vettük", { n: miss }) : t("A hét {store} árain", { store: c.name });
    b.addEventListener("click", () => {
      if (c.store !== w.store) updateSettings({ store: c.store }, true);
    });
    row.appendChild(b);
  }
  box.appendChild(row);
}

const DAY_ADJ = { Hétfő: "hétfői", Kedd: "keddi", Szerda: "szerdai", Csütörtök: "csütörtöki", Péntek: "pénteki", Szombat: "szombati", Vasárnap: "vasárnapi" };

function dinnerCard(d, i, people) {
  const s = d.suggestion, r = s.recipe;
  const leftover = d.leftoverOf >= 0;
  const today = state.week.today;
  const li = el("li", "dinner" + (leftover ? " leftover" : ""));
  if (d.date && today) {
    if (d.date === today) li.classList.add("today");
    else if (d.date < today) li.classList.add("past");
  }
  const open = el("button", "dinner-open", null, { type: "button", "aria-label": t("{day}: {name} – recept megnyitása", { day: d.label, name: r.name }) });
  const hero = el("span", "dinner-hero");
  let h = 0;
  for (const c of r.id) h = (h * 31 + c.charCodeAt(0)) % 360;
  hero.style.setProperty("--h", h);
  const when = el("span", "dinner-when");
  when.append(el("span", "day-chip", d.label));
  if (d.date) {
    const rel = d.date === today ? t("ma") : (today && d.date === addDays(today, 1)) ? t("holnap") : null;
    when.append(el("span", "day-date", rel ? `${rel} · ${shortDate(d.date)}` : shortDate(d.date)));
  }
  hero.append(when, el("span", "hero-emoji", r.emoji || "🍽️", { "aria-hidden": "true" }));
  open.appendChild(hero);
  const body = el("span", "dinner-body");
  if (leftover) {
    const from = state.week.days.find((x) => x.index === d.leftoverOf) || state.week.days[d.leftoverOf];
    const kicker = !from ? t("Maradék") : LANG === "hu" ? `Maradék a ${DAY_ADJ[from.label] || from.label.toLowerCase()} főzésből` : t("Maradék: {day}", { day: from.label });
    body.append(el("span", "dinner-kicker", kicker),
      el("span", "dinner-name", r.name),
      el("span", "dinner-line", t("Nem kell főzni, nem kell vásárolni – csak melegíteni.")));
  } else {
    body.append(el("span", "dinner-name", r.name));
    const line = el("span", "dinner-line");
    const cover = d.coversDays > 1 ? ` · ${t("{n} adag", { n: s.servings })} ${t("({n} napra)", { n: d.coversDays })}` : ` · ${t("{n} adag", { n: s.servings })}`;
    line.append(el("b", "per-serving", `≈ ${ft(d.shareOfList / s.servings)}`), document.createTextNode(`${t(" / adag")}${cover} · ${t("{n} perc", { n: r.minutes })}`));
    body.append(line, el("span", "dinner-sub", macroLine(r)), badges(r, 3));
  }
  open.appendChild(body);
  const src = leftover ? d.leftoverOf : i;
  open.addEventListener("click", () => openRecipe(r.id, { servings: s.servings, day: src }));
  li.appendChild(open);

  const acts = el("div", "dinner-acts");
  const next = state.week.days[i + 1];
  if (leftover) {
    acts.appendChild(actBtn(t("Inkább mást főzök"), () => runWeek("fresh", i)));
  } else {
    acts.appendChild(actBtn(t("Csere"), () => openSwap(i), '<path d="M4 9h13l-3-3M20 15H7l3 3"/>'));
  }
  if (next && !(next.leftoverOf >= 0 && next.leftoverOf === src)) {
    acts.appendChild(actBtn(leftover ? t("Holnap is ez") : t("Holnapra is főzök"), () => runWeek("repeat", i), '<path d="M12 5v14M5 12h14"/>'));
  }
  li.appendChild(acts);
  return li;
}

function addDays(iso, n) {
  const d = new Date(iso + "T12:00:00");
  d.setDate(d.getDate() + n);
  return d.toISOString().slice(0, 10);
}

function actBtn(label, onClick, path) {
  const b = el("button", "act-btn", null, { type: "button" });
  if (path) b.innerHTML = `<svg viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">${path}</svg>`;
  b.appendChild(el("span", null, label));
  b.addEventListener("click", onClick);
  return b;
}

/** Egy heti művelet (csere, maradék…) a kártya rövid „dolgozom” állapotával. */
function runWeek(action, day, recipe) {
  $$(".dinner")[day]?.classList.add("swapping");
  return loadWeek(action, day, recipe).catch((e) => toast(e.message));
}

// ---------- csere: mi legyen helyette ----------

async function openSwap(day) {
  const w = state.week;
  const d = w.days[day];
  const cur = d.suggestion.recipe;
  $("#swap-title").textContent = t("Mi legyen helyette {day}?", { day: dayTo(d.label) });
  $("#swap-hint").textContent = t("Most: {name}. A többi nap marad; mellettük látod, mennyivel változik a hét ára.", { name: cur.name });
  const ul = $("#swap-list");
  ul.replaceChildren(...Array.from({ length: 4 }, () => el("li", "swap-skel", null, { "aria-hidden": "true" })));
  $("#swap-random").onclick = () => {
    $("#swap-sheet").close();
    runWeek("swap", day).then(() => undoToast(day, cur));
  };
  openDialog($("#swap-sheet"));
  let res;
  try {
    res = await api(`/api/week?id=${state.profile.id}&action=options&day=${day}`);
  } catch (e) {
    ul.replaceChildren(el("li", "empty", e.message));
    return;
  }
  ul.replaceChildren();
  if (!res.options.length) ul.appendChild(el("li", "empty", t("Most nincs más recept, ami a beállításaidnak és a hét többi napjának megfelel. Próbáld a „Lepj meg” gombot.")));
  for (const o of res.options) {
    const r = o.recipe;
    const li = el("li");
    const b = el("button", "swap-opt", null, { type: "button" });
    const delta = o.weekTotal - w.checkoutTotal;
    const dTxt = Math.abs(toLocalMoney(delta)) < (market().decimals ? 0.05 : 15) ? t("ugyanannyi") : t("{delta} a héten", { delta: `${delta > 0 ? "+" : "−"}${ft(Math.abs(delta))}` });
    const info = el("span", "swap-info");
    info.append(el("span", "swap-name", r.name),
      el("span", "swap-sub", `${t("{n} perc", { n: r.minutes })} · ${macroLine(r)}`), badges(r, 3));
    b.append(icon(r), info, el("span", "swap-delta" + (delta < -1 ? " cheaper" : delta > 1 ? " dearer" : ""), dTxt));
    b.addEventListener("click", () => {
      $("#swap-sheet").close();
      runWeek("set", day, r.id).then(() => undoToast(day, cur));
    });
    li.appendChild(b);
    ul.appendChild(li);
  }
}

/** „X került a helyére” – visszavonással. */
function undoToast(day, prev) {
  const now = state.week?.days[day]?.suggestion.recipe;
  if (!now || now.id === prev.id) return;
  toast(t("{name} került a helyére.", { name: now.name }), { label: t("Visszavonás"), run: () => runWeek("set", day, prev.id) });
}

// ---------- bevásárlólista ----------

function renderList() {
  const w = state.week;
  if (!w) return;
  const split = w.split;
  const forWhom = t("{dinners} vacsora, {people} főre", { dinners: w.days.length, people: w.people, n: w.people });
  $("#list-sub").textContent = split ? `${split.stores.map((x) => x.name).join(" + ")} · ${forWhom}` : `${w.storeName} · ${forWhom}`;
  $("#list-total").textContent = ft((split ? split.checkout : w.checkoutTotal) + (w.extrasTotal || 0));
  const checked = new Set(w.checked || []);
  const root = $("#aisles");
  root.replaceChildren();
  const groups = split ? split.groups : [{ name: null, aisles: w.aisles }];
  let total = 0, done = 0;
  for (const g of groups) {
    if (g.name) {
      const h = el("h2", "store-head");
      h.append(el("span", null, g.name), el("b", null, ft(g.checkout)));
      root.appendChild(h);
    }
    const staples = [];
    for (const a of g.aisles) {
      const items = a.items.filter((i) => !i.staple);
      staples.push(...a.items.filter((i) => i.staple));
      if (!items.length) continue;
      root.appendChild(aisleSection(a.aisle, items, checked));
      total += items.length;
      done += items.filter((i) => checked.has(itemKey(i))).length;
    }
    if (staples.length) root.appendChild(aisleSection(t("Ha nincs otthon"), staples, checked));
  }
  if (w.pantry.length) root.appendChild(el("p", "pantry", t("Fűszerek a kamrából: {list}.", { list: w.pantry.join(", ") })));
  renderExtras(w, checked);
  updateProgress();
  $("#list-share").textContent = navigator.share ? t("Megosztás") : t("Lista másolása");
}

/** A lista-tétel kulcsa a közös pipákhoz: az eredeti (magyar) név, hogy minden nyelven ugyanaz legyen. */
const itemKey = (i) => i.labelKey || i.label;

function aisleSection(title, items, checked) {
  const sec = el("section", "aisle");
  sec.appendChild(el("h2", null, title));
  const ul = el("ul", "buy");
  for (const i of items) {
    const li = el("li");
    const key = itemKey(i);
    const id = `chk-${key}`.replace(/[^\w-]/g, "_");
    const cb = el("input", null, null, { type: "checkbox", id });
    cb.checked = checked.has(key);
    li.classList.toggle("done", cb.checked);
    cb.addEventListener("change", () => toggleCheck(key, cb.checked, li));
    const label = el("label", null, null, { for: id });
    label.append(el("span", "buy-name", i.item ? i.item.name : t("{name} (becsült ár)", { name: i.label })),
      el("span", "buy-qty", `${i.buyText}${i.item ? ` · ${i.priceText}` : ""}`),
      el("span", "buy-use", `${i.usedIn.join(", ")}${i.leftover ? ` · ${t("marad {amount}", { amount: i.leftover })}` : ""}`));
    const price = el("span", "buy-price", ft(i.checkoutCost));
    li.append(cb, label, price);
    if (i.deal) li.appendChild(el("span", i.deal.type === "PROMO" ? "buy-deal promo" : "buy-deal", DEAL_LABEL[i.deal.type]));
    ul.appendChild(li);
  }
  sec.appendChild(ul);
  return sec;
}

async function shareList() {
  const w = state.week;
  if (!w) return;
  const groups = w.split ? w.split.groups : [{ name: w.storeName, aisles: w.aisles }];
  const lines = [t("Bevásárlólista ({people} fő, {dinners} vacsora)", { people: w.people, dinners: w.days.length })];
  for (const g of groups) {
    lines.push("", `== ${g.name} ==`);
    for (const a of g.aisles) {
      lines.push(a.aisle.toUpperCase());
      for (const i of a.items) lines.push(`☐ ${i.item ? i.item.name : i.label} – ${i.buyText} – ${ft(i.checkoutCost)}${i.staple ? t(" (ha nincs otthon)") : ""}`);
    }
  }
  if (w.extras?.length) {
    lines.push("", t("SAJÁT TÉTELEK"));
    for (const x of w.extras) lines.push(`☐ ${x.name}${x.price != null ? ` – ${ft(x.price)} (${x.storeName})` : ""}`);
  }
  lines.push("", t("Összesen kb. {price}", { price: ft((w.split ? w.split.checkout : w.checkoutTotal) + (w.extrasTotal || 0)) }), "", t("Vacsorák:"),
    ...w.days.map((d) => `${d.label}: ${d.leftoverOf >= 0 ? t("maradék – ") : ""}${d.suggestion.recipe.name}`));
  const text = lines.join("\n");
  try {
    if (navigator.share) {
      await navigator.share({ title: t("Bevásárlólista"), text });
      return;
    }
    await navigator.clipboard.writeText(text);
    toast(t("A lista a vágólapra került."));
  } catch (e) {
    if (e && e.name === "AbortError") return;
    toast(t("A lista másolása nem sikerült ebben a böngészőben."));
  }
}

// ---------- naptár (.ics) ----------

/** iCalendar-szöveg: vessző, pontosvessző, sortörés escape-elve. */
const icsText = (s) => String(s).replace(/\\/g, "\\\\").replace(/[,;]/g, (c) => "\\" + c).replace(/\n/g, "\\n");

/**
 * A hét vacsorái naptárfájlként: minden nap egy 18:30–19:30-as esemény (helyi idő), benne az adagár és a recept
 * linkje. A naptár-alkalmazások (Google, Apple, Outlook) megnyitáskor felveszik; nincs fiók, nincs szinkron.
 */
function exportIcs() {
  const w = state.week;
  if (!w?.days.length) return;
  const stamp = new Date().toISOString().replace(/[-:]/g, "").replace(/\.\d+Z$/, "Z");
  const lines = ["BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Sporolos Konyha//HU", "CALSCALE:GREGORIAN"];
  w.days.forEach((d, i) => {
    if (!d.date) return;
    const r = d.suggestion.recipe;
    const day = d.date.replace(/-/g, "");
    const leftover = d.leftoverOf >= 0;
    const url = `${location.origin}${location.pathname}#recept=${encodeURIComponent(r.id)}`;
    const desc = leftover ? t("Nem kell főzni, nem kell vásárolni – csak melegíteni.")
      : `${t("≈ {price} / adag", { price: ft(d.shareOfList / d.suggestion.servings) })} · ${t("{n} perc", { n: r.minutes })}\n${t("Recept: {url}", { url })}`;
    lines.push("BEGIN:VEVENT", `UID:${w.listKey}-${i}-${r.id}@sporolos`, `DTSTAMP:${stamp}`,
      `DTSTART:${day}T183000`, `DTEND:${day}T193000`,
      `SUMMARY:${icsText(`${r.emoji || "🍽️"} ${leftover ? t("Maradék: {name}", { name: r.name }) : r.name}`)}`,
      `DESCRIPTION:${icsText(desc)}`, `URL:${url}`, "END:VEVENT");
  });
  lines.push("END:VCALENDAR");
  const blob = new Blob([lines.join("\r\n") + "\r\n"], { type: "text/calendar;charset=utf-8" });
  const a = el("a", null, null, { href: URL.createObjectURL(blob), download: `${t("vacsorak")}-${w.days[0].date || "het"}.ics` });
  document.body.appendChild(a);
  a.click();
  setTimeout(() => { URL.revokeObjectURL(a.href); a.remove(); }, 1000);
  toast(t("A naptárfájl letöltve – nyisd meg, és a vacsorák bekerülnek a naptáradba."));
}

// ---------- telepítés (PWA) ----------

let installPrompt = null;
window.addEventListener("beforeinstallprompt", (e) => { e.preventDefault(); installPrompt = e; });
window.addEventListener("appinstalled", () => { installPrompt = null; });
const isStandalone = () => matchMedia("(display-mode: standalone)").matches || navigator.standalone === true;
const isIos = () => /iphone|ipad|ipod/i.test(navigator.userAgent);

// ---------- receptlap ----------

async function openRecipe(id, opts) {
  const servings = opts.servings || settings().people || 0;
  state.sheet = { id, servings, day: opts.day ?? null, swapped: new Set(), data: null };
  const dlg = $("#recipe-sheet");
  const body = $("#rs-body");
  body.replaceChildren(el("p", "empty", t("Recept betöltése…")));
  openDialog(dlg);
  await loadSheet();
}

async function loadSheet() {
  const sh = state.sheet;
  try {
    sh.data = await api(`/api/recipe?id=${encodeURIComponent(sh.id)}&store=${currentStore()}&servings=${sh.servings}${prefQuery()}`);
  } catch (e) {
    $("#rs-body").replaceChildren(el("p", "empty", e.message));
    return;
  }
  renderSheet();
}

function sheetItems(s) {
  return s.ingredients.map((i) => (state.sheet.swapped.has(i.label) && i.alternative ? { ...i.alternative, swappedFrom: i } : i));
}

function renderSheet() {
  const sh = state.sheet, s = sh.data, r = s.recipe;
  const items = sheetItems(s);
  let cost = 0, checkout = 0;
  for (const i of items) if (!i.pantry) { cost += i.cost; if (!i.staple) checkout += i.checkoutCost; }
  const body = $("#rs-body");
  body.replaceChildren();

  const top = el("div", "rs-top");
  top.appendChild(icon(r, "big"));
  const close = el("button", "rs-close", "✕", { type: "button", "aria-label": t("Bezárás") });
  close.addEventListener("click", () => $("#recipe-sheet").close());
  top.appendChild(close);
  body.appendChild(top);

  const head = el("div", "rs-head");
  const left = el("div");
  left.append(el("h2", "rs-title", r.name, { id: "rs-title" }),
    el("p", "sub", `${t("{n} perc", { n: r.minutes })} · ${r.difficulty} · ${(r.equipment || []).join(", ")}`),
    badges(r));
  const right = el("div", "rs-side");
  const tag = el("div", "price-tag", null, { role: "img", "aria-label": t("A pénztárnál kb. {price}", { price: ft(checkout) }) });
  tag.append(el("small", null, t("{where} fizetsz", { where: inStore(s.store) })), el("span", "now", ft(checkout)),
    el("small", null, `${t("{n} adag", { n: s.servings })} · ${t("≈ {price} / adag", { price: ft(checkout / s.servings) })}`));
  right.append(tag, servingsControl(s.servings));
  head.append(left, right);
  body.appendChild(head);
  if (checkout - cost >= 50) {
    body.appendChild(el("p", "rs-note", t("Ebből a recepthez kb. {price} értékű áru fogy el; a többi (pl. a megkezdett csomagok) megmarad – a heti tervben ezeket a többi vacsora használja fel.", { price: ft(cost) })));
  }

  if (r.nutrition) {
    const m = el("dl", "macros");
    for (const [k, label, unit] of [["kcal", t("kalória"), "kcal"], ["protein", t("fehérje"), "g"], ["carbs", t("szénhidrát"), "g"], ["fat", t("zsír"), "g"]]) {
      const d = el("div");
      d.append(el("dt", null, label), el("dd", null, `${r.nutrition[k]} ${unit}`));
      m.appendChild(d);
    }
    body.appendChild(m);
    body.appendChild(el("p", "fineprint", t("Egy adagra, tipikus tápértékekből becsülve.")));
  }

  // hét-műveletek
  const acts = el("div", "rs-actions");
  if (sh.day != null && state.week?.days[sh.day]?.suggestion.recipe.id === r.id) {
    const swap = el("button", "ghost", t("Másikat kérek {day}", { day: dayTo(state.week.days[sh.day].label) }), { type: "button" });
    swap.addEventListener("click", () => {
      $("#recipe-sheet").close();
      openSwap(sh.day);
    });
    acts.appendChild(swap);
  } else if (state.week?.days.length) {
    const sel = el("select", null, null, { "aria-label": t("Melyik napra?") });
    state.week.days.forEach((d, i) => sel.appendChild(el("option", null, t("{day} (most: {name})", { day: d.label, name: d.suggestion.recipe.name }), { value: String(i) })));
    const add = el("button", "primary", t("Betesszük a hétre"), { type: "button" });
    add.addEventListener("click", async () => {
      $("#recipe-sheet").close();
      await loadWeek("set", sel.value, r.id).catch((e) => toast(e.message));
      toast(`${r.name} → ${state.week.days[Number(sel.value)].label}`);
      if (state.view !== "het") location.hash = "het";
    });
    acts.append(sel, add);
  }
  const shareBtn = el("button", "ghost", t("🔗 Megosztás"), { type: "button" });
  shareBtn.addEventListener("click", () => shareRecipe(r));
  acts.appendChild(shareBtn);
  const cookBtn = el("button", "ghost", t("👩‍🍳 Főzés mód"), { type: "button" });
  cookBtn.addEventListener("click", () => openCook(r, items));
  acts.appendChild(cookBtn);
  const liked = state.profile?.liked.includes(r.id);
  const heart = el("button", "ghost", liked ? t("♥ Kedvenc") : t("♡ Kedvencekhez"), { type: "button", "aria-pressed": String(!!liked) });
  heart.addEventListener("click", async () => {
    await rate(r.id, liked ? null : true);
    renderSheet();
  });
  acts.appendChild(heart);
  body.appendChild(acts);

  const cols = el("div", "rs-cols");
  cols.append(ingredientsBox(items, s), stepsBox(r));
  body.appendChild(cols);
  body.appendChild(compareBox(s, checkout));
}

/** Egy recept linkje (a megnyitó a saját nyelvén és boltja áraival látja). */
async function shareRecipe(r) {
  const url = `${location.origin}${location.pathname}#recept=${encodeURIComponent(r.id)}`;
  try {
    if (navigator.share) { await navigator.share({ title: r.name, text: `${r.emoji || "🍽️"} ${r.name}`, url }); return; }
    await navigator.clipboard.writeText(url);
    toast(t("A recept linkje a vágólapra került."));
  } catch (e) {
    if (e && e.name !== "AbortError") toast(url);
  }
}

// ---------- főzés mód ----------

const cook = { recipe: null, step: 0, lock: null };

async function openCook(r, items) {
  cook.recipe = r;
  cook.step = 0;
  $("#cook-title").textContent = r.name;
  const ings = $("#cook-ings");
  ings.replaceChildren(...items.map((i) => {
    const li = el("li");
    li.append(el("b", null, i.label), document.createTextNode(i.amount ? ` – ${i.amount}` : ""));
    return li;
  }));
  renderCook();
  openDialog($("#cook"));
  // a képernyő ne aludjon el főzés közben (ahol a böngésző engedi)
  try { if (navigator.wakeLock) cook.lock = await navigator.wakeLock.request("screen"); } catch (e) { cook.lock = null; }
}

function renderCook() {
  const steps = cook.recipe.steps;
  const n = steps.length;
  const i = cook.step;
  $("#cook-kicker").textContent = t("Főzés mód · {i}. lépés / {n}", { i: i + 1, n });
  $("#cook-dots").replaceChildren(...steps.map((_, k) => el("span", k < i ? "done" : k === i ? "on" : null)));
  const stage = $("#cook-stage");
  stage.replaceChildren(el("span", "cook-num", String(i + 1)), el("p", "cook-step", steps[i]));
  stage.classList.remove("slide");
  void stage.offsetWidth;
  stage.classList.add("slide");
  $("#cook-prev").disabled = i === 0;
  $("#cook-next").textContent = i === n - 1 ? t("Kész! 🎉") : t("Következő →");
}

function cookMove(d) {
  const n = cook.recipe.steps.length;
  if (cook.step + d >= n) {
    $("#cook").close();
    toast(t("Jó étvágyat! 🍽️"));
    return;
  }
  cook.step = Math.max(0, cook.step + d);
  renderCook();
}

function servingsControl(n) {
  const wrap = el("div", "stepper", null, { role: "group", "aria-label": t("Adagszám") });
  const minus = el("button", "step", "−", { type: "button", "aria-label": t("Kevesebb adag") });
  const plus = el("button", "step", "+", { type: "button", "aria-label": t("Több adag") });
  minus.disabled = n <= 1;
  plus.disabled = n >= 20;
  const change = (d) => {
    state.sheet.servings = Math.max(1, Math.min(20, n + d));
    loadSheet();
  };
  minus.addEventListener("click", () => change(-1));
  plus.addEventListener("click", () => change(+1));
  wrap.append(minus, el("span", "step-value", t("{n} adag", { n })), plus);
  return wrap;
}

function ingredientsBox(items, s) {
  const box = el("section", "rs-ing");
  box.appendChild(el("h3", null, t("Mi kell hozzá {where}", { where: inStore(s.store) })));
  const ul = el("ul", "ing");
  for (const i of items) {
    const li = el("li");
    if (i.pantry) li.classList.add("pantry");
    const label = el("span");
    label.append(el("span", "ing-name", i.label), document.createTextNode(" "), el("span", "ing-amount", i.amount || ""));
    li.append(label, el("span", "ing-cost", i.pantry ? t("kamrából") : ft(i.checkoutCost)));
    if (i.item) {
      const p = el("span", "ing-product");
      const link = el("button", "linkish", i.item.name, { type: "button" });
      link.addEventListener("click", () => openItem(i.item.id));
      const used = i.checkoutCost - i.cost >= 20 ? t(" · ebből a recepthez ≈ {price}, a többi megmarad", { price: ft(i.cost) }) : "";
      p.append(link, el("span", "muted", `${t(" · vegyél: {what}", { what: i.buyText })}${i.bulk ? t(" (nagy kiszerelés)") : ""}${i.staple ? t(" – ha nincs otthon") : ""}${used}`));
      if (i.deal) p.appendChild(el("span", i.deal.type === "PROMO" ? "ing-deal promo" : "ing-deal",
        `${DEAL_LABEL[i.deal.type]}${i.deal.savingPercent > 0 ? ` −${Math.round(i.deal.savingPercent)}%` : ""}`, { title: i.deal.reason }));
      li.appendChild(p);
      const other = i.swappedFrom ? i.swappedFrom : i.alternative;
      if (other) li.appendChild(altRow(i.swappedFrom || i, i, other));
    } else if (i.estimated) {
      li.appendChild(el("span", "ing-product muted", t("Az Árfigyelő nem követi, vagy itt nincs – becsült ár.")));
    }
    ul.appendChild(li);
  }
  box.appendChild(ul);
  return box;
}

function altRow(orig, current, other) {
  const row = el("div", "alt");
  const costDiff = other.cost - current.cost;
  const payDiff = other.checkoutCost - current.checkoutCost;
  const what = other.bulk ? t("Nagy kiszerelésben olcsóbb") : t("Kisebb kiszerelésben");
  row.appendChild(el("span", null, t("{what}: {name} ({buy}). A receptre {cost}, ma {pay}.", { what, name: other.item ? other.item.name : "", buy: other.buyText,
    cost: costDiff < 0 ? "−" + ft(-costDiff) : "+" + ft(costDiff), pay: payDiff >= 0 ? "+" + ft(payDiff) : "−" + ft(-payDiff) })));
  const btn = el("button", "ghost small", t("Ezt veszem"), { type: "button" });
  btn.addEventListener("click", () => {
    if (state.sheet.swapped.has(orig.label)) state.sheet.swapped.delete(orig.label);
    else state.sheet.swapped.add(orig.label);
    renderSheet();
  });
  row.appendChild(btn);
  return row;
}

function stepsBox(r) {
  const box = el("section", "rs-steps");
  box.appendChild(el("h3", null, t("Elkészítés")));
  const ol = el("ol");
  for (const step of r.steps) ol.appendChild(el("li", null, step));
  box.appendChild(ol);
  return box;
}

function compareBox(s, pay) {
  const box = el("section", "rs-compare");
  box.appendChild(el("h3", null, t("Mennyit fizetnél érte máshol?")));
  const rows = s.stores.filter((x) => x.coverage >= 0.4)
    .map((x) => ({ ...x, pay: x.store === s.store ? pay : x.checkoutTotal }))
    .sort((a, b) => a.pay - b.pay);
  const max = Math.max(...rows.map((x) => x.pay), 1);
  const ul = el("ul", "bars");
  for (const x of rows) {
    const li = el("li", x.store === s.store ? "current" : null);
    const bar = el("span", "bar");
    bar.style.setProperty("--w", `${Math.max(6, (x.pay / max) * 100)}%`);
    bar.style.setProperty("--c", x.color);
    li.append(el("span", "bar-name", x.name), bar, el("span", "bar-value", ft(x.pay)));
    ul.appendChild(li);
  }
  box.appendChild(ul);
  box.appendChild(el("p", "fineprint", t("Egész csomagokkal, a kamraáru nélkül. A bolt a fejlécben váltható.")));
  return box;
}

// ---------- Nom vagy nem ----------

async function rate(recipeId, liked) {
  try {
    state.profile = await api(`/api/rate?id=${state.profile.id}`, jsonOpts("POST", { recipe: recipeId, liked }));
  } catch (e) { toast(e.message); }
}

async function allRecipes() {
  const key = `${currentStore()}|${settings().people}|${filterQuery()}|${prefQuery()}`;
  if (state.recipes && state.recipesKey === key) return state.recipes;
  const list = await api(`/api/recipes?store=${currentStore()}&servings=${settings().people || 0}&${filterQuery()}${prefQuery()}`);
  state.recipes = list;
  state.recipesKey = key;
  return list;
}

const perServing = (x) => (x.checkoutTotal != null ? x.checkoutTotal / (x.servings || x.recipe.servings) : null);
const isSweet = (r) => r.tags.some((x) => x === "édes" || x === "sütemény" || x === "reggeli");

async function loadDeck() {
  const list = await allRecipes();
  const rated = new Set([...state.profile.liked, ...state.profile.disliked]);
  // vacsorák elöl, kicsit keverve; az édességek és reggelik a pakli végén
  state.deck = list.filter((x) => !rated.has(x.recipe.id) && x.checkoutTotal != null)
    .map((x) => ({ x, k: Math.random() + (isSweet(x.recipe) ? 10 : 0) }))
    .sort((a, b) => a.k - b.k).map((o) => o.x);
  renderDeck();
  renderLiked();
}

const nomCount = () => t("{yes} nom · {no} nem · még {n} recept", { yes: state.profile.liked.length, no: state.profile.disliked.length, n: state.deck.length });

function renderDeck() {
  const deck = $("#nom-deck");
  deck.replaceChildren();
  const top = state.deck.slice(0, 3);
  $("#nom-count").textContent = nomCount();
  for (const b of ["#nom-no", "#nom-yes", "#nom-later"]) $(b).disabled = !top.length;
  if (!top.length) {
    deck.appendChild(el("p", "empty", t("Minden receptet értékeltél ezekkel a szűrőkkel. A heti terv már ezek alapján válogat.")));
    return;
  }
  top.slice().reverse().forEach((x, idx) => {
    const depth = top.length - 1 - idx;
    const r = x.recipe;
    const card = el("article", "nom-card", null, { "aria-label": r.name });
    card.style.setProperty("--depth", depth);
    const body = el("div", "nom-body");
    const ings = r.ingredients ? r.ingredients : [];
    body.append(icon(r, "big"), el("h2", null, r.name), el("p", "sub", `${t("{n} perc", { n: r.minutes })} · ${r.difficulty} · ${macroLine(r)}`), badges(r, 5),
      el("p", "nom-ings", (x.ingredientNames || []).join(" · ")),
      el("span", "nom-price", payText(x.checkoutTotal, x.servings || r.servings)));
    card.appendChild(body);
    card.append(el("span", "stamp yes", t("NOM")), el("span", "stamp no", t("NEM")));
    if (depth === 0) dragCard(card);
    deck.appendChild(card);
  });
}

async function decide(liked) {
  const x = state.deck[0];
  if (!x) return;
  const card = $("#nom-deck .nom-card:last-of-type");
  const reduce = matchMedia("(prefers-reduced-motion: reduce)").matches;
  if (card && !reduce) {
    const dir = liked === true ? 1 : liked === false ? -1 : 0;
    card.style.transition = "transform 220ms ease-in, opacity 220ms ease-in";
    card.style.transform = dir ? `translateX(${dir * 120}%) rotate(${dir * 18}deg)` : "translateY(30px) scale(0.92)";
    card.style.opacity = "0";
  }
  if (liked === null) state.deck.push(state.deck.shift());
  else state.deck.shift();
  setTimeout(renderDeck, reduce ? 0 : 200);
  if (liked !== null) {
    await rate(x.recipe.id, liked);
    renderLiked();
    $("#nom-count").textContent = nomCount();
  }
}

function dragCard(card) {
  let x0 = null, dx = 0;
  card.addEventListener("pointerdown", (e) => {
    x0 = e.clientX;
    dx = 0;
    card.setPointerCapture(e.pointerId);
    card.style.transition = "none";
  });
  card.addEventListener("pointermove", (e) => {
    if (x0 == null) return;
    dx = e.clientX - x0;
    card.style.transform = `translateX(${dx}px) rotate(${dx / 18}deg)`;
    card.classList.toggle("lean-yes", dx > 40);
    card.classList.toggle("lean-no", dx < -40);
  });
  const end = () => {
    if (x0 == null) return;
    x0 = null;
    if (Math.abs(dx) > 100) return decide(dx > 0);
    card.style.transition = "transform 180ms ease-out";
    card.style.transform = "";
    card.classList.remove("lean-yes", "lean-no");
    if (Math.abs(dx) < 5) openRecipe(state.deck[0].recipe.id, {});
  };
  card.addEventListener("pointerup", end);
  card.addEventListener("pointercancel", () => { x0 = null; card.style.transform = ""; });
}

function renderLiked() {
  const ul = $("#liked");
  ul.replaceChildren();
  const byId = new Map((state.recipes || []).map((x) => [x.recipe.id, x]));
  const liked = state.profile.liked.map((id) => byId.get(id)).filter(Boolean);
  $("#nom-reset").hidden = !state.profile.liked.length && !state.profile.disliked.length;
  if (!liked.length) {
    ul.appendChild(el("li", "empty", t("Még nincs kedvenced. Amit a „Nom” gombbal jelölsz, itt gyűlik, és a heti terv előnyben részesíti.")));
    return;
  }
  for (const x of liked) {
    const li = el("li");
    const b = el("button", "liked-open", null, { type: "button" });
    b.append(icon(x.recipe), el("span", null, x.recipe.name));
    b.addEventListener("click", () => openRecipe(x.recipe.id, {}));
    const rm = el("button", "ghost small", t("Mégsem"), { type: "button", "aria-label": t("{name} törlése a kedvencek közül", { name: x.recipe.name }) });
    rm.addEventListener("click", async () => { await rate(x.recipe.id, null); loadDeck(); });
    li.append(b, rm);
    ul.appendChild(li);
  }
}

// ---------- receptek, jó árak ----------

async function renderBrowse() {
  const store = currentStore();
  $("#today-store").textContent = inStore(store);
  const [list] = await Promise.all([allRecipes(), renderToday(store)]);
  const q = $("#browse-q").value.trim();
  const f = state.filter;
  const liked = new Set(state.profile.liked);
  const norm = (s) => s.toLowerCase().normalize("NFD").replace(/[̀-ͯ]/g, "");
  // több szó: mind szerepeljen a névben, a címkékben vagy a hozzávalókban („csirke rizs”, „cukkini”)
  const words = norm(q).split(/\s+/).filter(Boolean);
  const rows = list.filter((x) => {
    const r = x.recipe;
    if (words.length) {
      const hay = norm(`${r.name} ${r.tags.map((g) => `${g} ${t(g)}`).join(" ")} ${(x.ingredientNames || []).join(" ")}`);
      if (!words.every((w) => hay.includes(w))) return false;
    }
    if (!f) return true;
    if (f === "kedvenc") return liked.has(r.id);
    if (f === "airfryer") return (r.equipment || []).some((e) => e.includes("airfryer"));
    if (f === "gyors") return r.minutes <= 30;
    return (r.diets || []).includes(f) || (r.moods || []).includes(f);
  });
  const sort = $("#browse-sort").value;
  const key = {
    price: (x) => perServing(x) ?? 1e9,
    score: (x) => -(x.score ?? 0),
    time: (x) => x.recipe.minutes,
    kcal: (x) => x.recipe.nutrition?.kcal ?? 1e9,
    protein: (x) => -(x.recipe.nutrition?.protein ?? 0),
  }[sort];
  rows.sort(key ? (a, b) => key(a) - key(b) : (a, b) => a.recipe.name.localeCompare(b.recipe.name, LANG));
  $("#browse-count").textContent = `${t("{n} recept", { n: rows.length })} · ${t("adagárak {where}", { where: inStore(store) })}${filterQuery() ? t(" · a diétád és a konyhád szerint szűrve") : ""}`;
  const grid = $("#browse-grid");
  grid.replaceChildren();
  if (!rows.length) grid.appendChild(el("li", "empty", t("Nincs ilyen recept ezekkel a szűrőkkel.")));
  const frag = document.createDocumentFragment();
  for (const x of rows) frag.appendChild(recipeCard(x, liked.has(x.recipe.id)));
  grid.appendChild(frag);
}

function recipeCard(x, isLiked) {
  const r = x.recipe;
  const li = el("li", "card");
  const b = el("button", "card-open", null, { type: "button" });
  const body = el("span", "card-body");
  const head = el("span", "card-head");
  head.append(icon(r), el("span", "card-name", r.name));
  body.append(head, el("span", "card-sub", `${t("{n} perc", { n: r.minutes })} · ${macroLine(r)}`), badges(r, 3));
  b.appendChild(body);
  b.addEventListener("click", () => openRecipe(r.id, {}));
  const foot = el("div", "card-foot");
  foot.appendChild(el("span", "card-price", x.checkoutTotal != null ? payText(x.checkoutTotal, x.servings || r.servings) : t("itt nem kapható minden")));
  const heart = el("button", "heart", isLiked ? "♥" : "♡", { type: "button", "aria-pressed": String(isLiked), "aria-label": t("{name}: kedvenc", { name: r.name }) });
  heart.addEventListener("click", async () => {
    const on = heart.getAttribute("aria-pressed") !== "true";
    heart.setAttribute("aria-pressed", String(on));
    heart.textContent = on ? "♥" : "♡";
    await rate(r.id, on ? true : null);
  });
  foot.appendChild(heart);
  li.append(b, foot);
  return li;
}

async function renderToday(store) {
  // vacsorának való receptek (reggeli, édesség nélkül), a diétád és a konyhád szerint
  const byId = new Map((await allRecipes()).map((x) => [x.recipe.id, x]));
  const ok = byId;
  const sugs = (await api(`/api/suggestions?store=${store}&limit=60${prefQuery()}`))
    .filter((s) => ok.has(s.recipe.id) && !isSweet(s.recipe)).slice(0, 10);
  const ul = $("#today");
  ul.replaceChildren();
  for (const s of sugs) {
    const li = el("li");
    const b = el("button", "mini", null, { type: "button" });
    const deals = s.ingredients.filter((i) => i.deal).map((i) => i.label.toLowerCase()).slice(0, 3);
    const head = el("span", "card-head");
    head.append(icon(s.recipe), el("span", "mini-name", s.recipe.name));
    const mine = byId.get(s.recipe.id);
    b.append(head, el("span", "mini-sub", mine ? payText(mine.checkoutTotal, mine.servings) : payText(s.checkoutTotal, s.servings)),
      el("span", "mini-deals", deals.length ? t("jó áron: {list}", { list: deals.join(", ") }) : ""));
    b.addEventListener("click", () => openRecipe(s.recipe.id, {}));
    li.appendChild(b);
    ul.appendChild(li);
  }
}

// ---------- akciók (a boltjaid kiemelt árai) ----------

const offerState = { store: "", all: false };

async function renderOffers() {
  $("#offers-sub").textContent = market().id === "hu"
    ? t("A boltjaid kiemelt árai a GVH Árfigyelő mai adataiból és a felvitt bolti akciókból: a legnagyobb megtakarítás elöl. Egy termékre koppintva látod az árát minden láncban, és felteheted a listádra.")
    : t("A boltjaid kiemelt árai a láncok mai árlistáiból – az akciós árakat a láncok maguk jelölik: a legnagyobb megtakarítás elöl. Magyarul is kereshetsz (kávé, sajt, csirke). Egy termékre koppintva látod az árát minden láncban.");
  const chips = $("#offer-stores");
  if (!chips.childElementCount) {
    const mine = state.status.stores.filter((x) => !settings().stores?.length || settings().stores.includes(x.id));
    for (const [id, name, color] of [["", t("Minden boltom"), null], ...mine.map((x) => [x.id, x.name, x.color])]) {
      const b = el("button", color ? "store-chip" : null, name, { type: "button", "aria-pressed": String(offerState.store === id) });
      if (color) b.style.setProperty("--c", color);
      b.addEventListener("click", () => {
        offerState.store = id;
        for (const x of $$("button", chips)) x.setAttribute("aria-pressed", String(x === b));
        renderOffers().catch((e) => toast(e.message));
      });
      chips.appendChild(b);
    }
  }
  const q = $("#offer-q").value.trim();
  const params = new URLSearchParams({ limit: "80", ingredients: String(!$("#offer-all").checked) });
  if (offerState.store) params.set("store", offerState.store);
  if (q.length >= 2) params.set("q", q);
  const list = await api(`/api/offers?${params}${prefQuery()}`);
  const ul = $("#offers");
  ul.replaceChildren();
  if (!list.length) ul.appendChild(el("li", "empty", q ? t("Nincs ilyen kiemelt ár. Próbáld a „nem csak a receptek alapanyagai” kapcsolóval.") : t("Most nincs kiemelt ár ebben a nézetben.")));
  for (const d of list) {
    const it = d.item;
    const li = el("li", "offer" + (d.type === "PROMO" ? " promo" : ""));
    const b = el("button", null, null, { type: "button" });
    const top = el("span", "offer-top");
    const st = el("span", "offer-store", d.storeName);
    st.style.setProperty("--c", storeInfo(it.store)?.color || "var(--line)");
    top.append(st, el("span", "offer-kind", DEAL_LABEL[d.type]));
    const prices = el("span", "offer-prices");
    if (d.type !== "CATEGORY" && d.referencePrice > it.price) prices.appendChild(el("s", null, ft(d.referencePrice)));
    prices.appendChild(el("b", null, ft(it.price)));
    if (d.savingPercent >= 1) prices.appendChild(el("span", "offer-save", `−${Math.round(d.savingPercent)}%`));
    b.append(top, el("span", "offer-name", it.name),
      el("span", "offer-unit", `${qty(it.quantity, it.unit)} · ${ft(it.unitPrice)}/${unitName(it.unit)}${d.ingredient ? t(" · receptekhez: {what}", { what: d.ingredient.toLowerCase() }) : ""}`),
      prices, el("span", "offer-why", d.reason));
    b.addEventListener("click", () => openItem(it.id));
    li.appendChild(b);
    ul.appendChild(li);
  }
}

async function openItem(id) {
  let data;
  try {
    data = await api(`/api/item?id=${encodeURIComponent(id)}${prefQuery()}`);
  } catch (e) {
    toast(e.message);
    return;
  }
  $("#item-title").textContent = data.name;
  $("#item-cat").textContent = `${data.category} · ${data.offers.length > 1 ? t("{n} láncban kapható", { n: data.offers.length }) : t("csak egy láncban kapható")}`;
  const ul = $("#item-offers");
  ul.replaceChildren();
  const min = Math.min(...data.offers.map((o) => o.price));
  for (const o of data.offers) {
    const li = el("li", o.store === currentStore() ? "current" : null);
    if (!o.mine) li.classList.add("not-mine");
    const left = el("div");
    left.appendChild(el("b", null, o.storeName));
    const shops = o.totalShops ? t("{n} / {total} boltjukban", { n: o.shops, total: o.totalShops }) : (o.shops ? t("{n} boltjukban", { n: o.shops }) : t("saját akció"));
    left.appendChild(el("span", "offer-meta", o.maxPrice > o.price ? `${shops} · ${t("máshol akár {price}", { price: ft(o.maxPrice) })}` : shops));
    if (o.deal) left.appendChild(el("span", "ing-deal", DEAL_LABEL[o.deal.type]));
    const right = el("div", "offer-price");
    right.appendChild(el("b", null, ft(o.price)));
    if (o.price > min) right.appendChild(el("span", "offer-diff", `+${ft(o.price - min)}`));
    right.appendChild(el("span", "offer-meta", `${qty(o.quantity, o.unit)} · ${ft(o.unitPrice)}/${unitName(o.unit)}`));
    li.append(left, right);
    ul.appendChild(li);
  }
  renderHistory(data.history || []);
  const add = $("#item-add");
  add.onclick = async () => {
    await addExtra(data.name, data.id);
    $("#item-dialog").close();
  };
  openDialog($("#item-dialog"));
}

/** Az elmúlt hetek ára lánconként (ha van már előzmény): kis vonaldiagram és összegzés. */
function renderHistory(hist) {
  const box = $("#item-history");
  box.replaceChildren();
  if (!hist.length) {
    if (market().id === "hu") box.appendChild(el("p", "fineprint", t("Árelőzmény még nincs: az app minden nap elmenti az árakat, néhány nap múlva itt látszik, olcsóbb-e most a szokásosnál.")));
    return;
  }
  box.appendChild(el("h3", "hist-title", t("Az elmúlt hetekben")));
  for (const h of hist) {
    const row = el("div", "hist-row");
    const pts = h.points.map(([, v]) => v);
    const min = Math.min(...pts), max = Math.max(...pts);
    const w = 160, hgt = 34;
    const path = pts.map((v, i) => `${i ? "L" : "M"}${(i / Math.max(1, pts.length - 1)) * w},${max === min ? hgt / 2 : hgt - ((v - min) / (max - min)) * (hgt - 4) - 2}`).join(" ");
    const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
    svg.setAttribute("viewBox", `0 0 ${w} ${hgt}`);
    svg.setAttribute("class", "spark");
    svg.setAttribute("aria-hidden", "true");
    const pe = document.createElementNS("http://www.w3.org/2000/svg", "path");
    pe.setAttribute("d", path);
    svg.appendChild(pe);
    const now = pts[pts.length - 1];
    const verdict = now <= min + 0.5 ? t("a legalacsonyabb ár") : now < h.median - 0.5 ? t("a szokásosnál olcsóbb") : now > h.median + 0.5 ? t("drágább a szokásosnál") : t("a szokásos ár");
    const txt = el("span", "hist-txt");
    txt.append(el("b", null, h.storeName), el("span", null, t(" {n} nap: {min} – {max}, most {verdict}", { n: h.days, min: ft(min), max: ft(max), verdict })));
    row.append(txt, svg);
    box.appendChild(row);
  }
}

// ---------- első indítás ----------

const ob = { step: 0, draft: null };

/** Az első indításnál kiválasztott ország adatai (a státusz országlistájából). */
const obMarket = () => (state.status?.markets || []).find((m) => m.id === ob.draft?.country) || HU_MARKET;

const COUNTRY_SUB = {
  hu: t("GVH Árfigyelő · Aldi, Lidl, Penny, Auchan, Tesco, Spar · forint"),
  hr: t("a láncok kötelező napi árlistái · Konzum, Lidl, Spar, Kaufland, Plodine, Studenac… · euró – nyaraláshoz is"),
};

const OB_STEPS = [
  {
    id: "country",
    title: t("Hol vásárolsz?"),
    hint: t("Az ország boltjainak napi árai alapján tervezünk. A felület nyelve független az országtól – fent bármikor átállíthatod."),
    skip: () => (state.status?.markets || []).length < 2,
    render(box) {
      const list = el("div", "choice-list");
      for (const m of state.status.markets) {
        const b = el("button", "choice country", null, { type: "button", "aria-pressed": String(ob.draft.country === m.id) });
        const txt = el("span", "choice-text");
        txt.append(el("b", null, `${m.name}${m.beta ? t(" · béta") : ""}`), el("span", null, COUNTRY_SUB[m.id] || m.source));
        b.append(el("span", "flag", m.flag, { "aria-hidden": "true" }), txt);
        b.addEventListener("click", () => { ob.draft.country = m.id; renderOb(); });
        list.appendChild(b);
      }
      box.appendChild(list);
    },
  },
  {
    title: t("Hányan esztek?"),
    hint: t("Ennyi adaggal számolunk, és ennyi vacsorát tervezünk a hétre."),
    render(box) {
      box.append(stepperField(t("Fő"), "people", 1, 12, (v) => t("{n} fő", { n: v })), stepperField(t("Vacsora a héten"), "dinners", 1, 7, (v) => t("{n} vacsora", { n: v })));
    },
  },
  {
    title: t("Milyen gyakran főzöl?"),
    hint: t("Nem kell minden nap mást főzni: ha kétnaponta főzöl, egyszerre két napra készül az étel, a maradék másnapra. A héten bármelyik napnál átállíthatod."),
    render(box) {
      const row = el("div", "choice-list");
      for (const [v, title, sub] of [["daily", t("Minden nap főzök"), t("minden vacsora más")], ["every2", t("Kétnaponta főzök"), t("egyszer főzöl, kétszer eszel – a maradék másnapra")]]) {
        const b = el("button", "choice", null, { type: "button", "aria-pressed": String(ob.draft.cookMode === v) });
        b.append(el("b", null, title), el("span", null, sub));
        b.addEventListener("click", () => { ob.draft.cookMode = v; renderOb(); });
        row.appendChild(b);
      }
      box.appendChild(row);
    },
  },
  {
    title: t("Mennyi a heti keret?"),
    hint: t("A vacsorák bevásárlására. A terv ebbe próbál beleférni – és ha lehet, maradjon is belőle."),
    render(box) {
      const chips = el("div", "chips-row");
      const cur = market().currency;
      for (const local of [...(BUDGETS[cur] || BUDGETS.HUF), 0]) {
        const v = toInternalMoney(local);
        const b = el("button", null, v ? ft(v) : t("nincs keret"), { type: "button", "aria-pressed": String(ob.draft.budget === v) });
        b.addEventListener("click", () => { ob.draft.budget = v; renderOb(); });
        chips.appendChild(b);
      }
      const dec = market().decimals;
      const input = el("input", null, null, { type: "number", min: "0", step: dec ? "5" : "500", inputmode: "numeric", "aria-label": t("Saját összeg ({symbol})", { symbol: market().symbol }) });
      input.value = ob.draft.budget ? Math.round(toLocalMoney(ob.draft.budget)) : "";
      input.addEventListener("input", () => { ob.draft.budget = Math.max(0, toInternalMoney(Number(input.value) || 0)); });
      const money = el("label", "money");
      money.append(el("span", null, t("vagy saját összeg:")), input, el("span", null, market().symbol));
      box.append(chips, money);
    },
  },
  {
    title: t("Van, amit nem esztek?"),
    hint: t("Csak olyan vacsorák jönnek, amik minden bejelöltnek megfelelnek. Ha nincs ilyen, lépj tovább."),
    render(box) {
      box.appendChild(toggleChips(DIETS, "diets"));
    },
  },
  {
    title: t("Mivel főzöl?"),
    hint: t("Csak olyan receptet kapsz, ami ezekkel elkészíthető. Airfryerrel a sütős receptek egy része is megy."),
    render(box) {
      box.appendChild(toggleChips(EQUIPMENT, "equipment", true));
    },
  },
  {
    title: t("Melyik boltokba jársz?"),
    hint: t("Nem kell most egy boltot választanod: a heted abban a boltban lesz, ahol a legolcsóbb, és mindig látod, mennyi lenne a többiben. Amelyik láncba nem mennél, vedd ki."),
    render(box) {
      const all = state.status.stores.map((x) => [x.id, x.name, x.color]);
      const row = el("div", "chips-row big");
      for (const [v, label, color] of all) {
        const b = el("button", "store-chip", label, { type: "button", "aria-pressed": String(ob.draft.stores.includes(v)) });
        b.style.setProperty("--c", color);
        b.addEventListener("click", () => {
          const on = !ob.draft.stores.includes(v);
          if (!on && ob.draft.stores.length === 1) { toast(t("Legalább egy bolt maradjon.")); return; }
          ob.draft.stores = on ? [...ob.draft.stores, v] : ob.draft.stores.filter((x) => x !== v);
          b.setAttribute("aria-pressed", String(on));
        });
        row.appendChild(b);
      }
      const split = el("label", "check-line");
      const cb = el("input", null, null, { type: "checkbox" });
      cb.checked = ob.draft.split;
      cb.addEventListener("change", () => { ob.draft.split = cb.checked; });
      split.append(cb, el("span", null, t("Két boltba is elmegyek, ha legalább pár száz forintot spórolok vele")));
      box.append(row, split);
    },
  },
];

function stepperField(label, key, min, max, fmt) {
  const wrap = el("div", "field");
  wrap.appendChild(el("span", "field-label", label));
  const st = el("div", "stepper big");
  const minus = el("button", "step", "−", { type: "button", "aria-label": t("{label}: kevesebb", { label }) });
  const val = el("span", "step-value", fmt(ob.draft[key]));
  const plus = el("button", "step", "+", { type: "button", "aria-label": t("{label}: több", { label }) });
  const set = (d) => {
    ob.draft[key] = Math.max(min, Math.min(max, ob.draft[key] + d));
    val.textContent = fmt(ob.draft[key]);
    minus.disabled = ob.draft[key] <= min;
    plus.disabled = ob.draft[key] >= max;
  };
  minus.addEventListener("click", () => set(-1));
  plus.addEventListener("click", () => set(1));
  set(0);
  st.append(minus, val, plus);
  wrap.appendChild(st);
  return wrap;
}

function toggleChips(options, key, atLeastOne) {
  const row = el("div", "chips-row big");
  for (const [v, label] of options) {
    const b = el("button", null, label, { type: "button", "aria-pressed": String(ob.draft[key].includes(v)) });
    b.addEventListener("click", () => {
      const on = !ob.draft[key].includes(v);
      if (!on && atLeastOne && ob.draft[key].length === 1) { toast(t("Legalább egy maradjon.")); return; }
      ob.draft[key] = on ? [...ob.draft[key], v] : ob.draft[key].filter((x) => x !== v);
      b.setAttribute("aria-pressed", String(on));
    });
    row.appendChild(b);
  }
  return row;
}

function startOnboarding() {
  ob.step = 0;
  ob.draft = { country: market().id, store: null, people: 2, dinners: 5, budget: toInternalMoney(DEFAULT_BUDGET[market().currency] || 15000),
    diets: [], equipment: ["tuzhely", "suto"], cookMode: "daily", stores: state.status.stores.map((x) => x.id), split: false };
  while (OB_STEPS[ob.step].skip?.()) ob.step++;
  renderOb();
  const dlg = $("#onboard");
  dlg.addEventListener("cancel", (e) => e.preventDefault());
  openDialog(dlg);
  // a megnyitáskor a böngésző az első gombra (a nyelvválasztóra) tenné a fókuszt – az kiválasztásnak látszana
  $("#ob-next").focus();
}

const obSteps = () => OB_STEPS.filter((x) => !x.skip?.());

function renderOb() {
  const st = OB_STEPS[ob.step];
  const box = $("#ob-step");
  box.replaceChildren();
  const visible = obSteps();
  const pos = visible.indexOf(st);
  if (pos === 0) {
    box.appendChild(langSwitch());
    box.appendChild(el("p", "ob-kicker", t("Mi legyen ma vacsora – megint? Pár kérdés, és kész a heted.")));
  }
  box.append(el("h1", "ob-title", st.title, { id: "ob-title" }), el("p", "sub", st.hint));
  st.render(box);
  box.classList.remove("slide");
  void box.offsetWidth;
  box.classList.add("slide");
  $("#ob-progress").replaceChildren(...visible.map((_, i) => el("span", i <= pos ? "on" : null)));
  $("#ob-back").hidden = pos === 0;
  $("#ob-next").textContent = pos === visible.length - 1 ? t("Kérem a hetet") : t("Tovább");
}

function obMove(dir) {
  let i = ob.step + dir;
  while (i > 0 && i < OB_STEPS.length - 1 && OB_STEPS[i].skip?.()) i += dir;
  ob.step = Math.max(0, Math.min(OB_STEPS.length - 1, i));
  renderOb();
}

async function obNext() {
  const st = OB_STEPS[ob.step];
  if (st.ok && !st.ok()) return;
  if (st.id === "country" && market().id !== ob.draft.country) {
    // az ország árai (első alkalommal letöltés – a töltőképernyő mutatja), aztán az ottani boltok és pénznem
    $("#ob-next").disabled = true;
    try {
      state.marketHint = obMarket();
      state.status = await api("/api/status", undefined, ob.draft.country);
      ob.draft.stores = state.status.stores.map((x) => x.id);
      ob.draft.budget = toInternalMoney(DEFAULT_BUDGET[market().currency] || 15000);
      ob.draft.cards = [];
    } catch (e) {
      toast(e.message);
      return;
    } finally {
      $("#ob-next").disabled = false;
    }
  }
  if (OB_STEPS.indexOf(st) < OB_STEPS.length - 1) {
    obMove(1);
    return;
  }
  $("#ob-next").disabled = true;
  try {
    const all = state.status.stores.length;
    state.profile = await api("/api/profile", jsonOpts("POST", { ...ob.draft, stores: ob.draft.stores.length === all ? [] : ob.draft.stores, onboarded: true }));
    local("sporolos.profile", state.profile.id);
    $("#onboard").close();
    await afterProfile();
  } catch (e) {
    toast(e.message);
  } finally {
    $("#ob-next").disabled = false;
  }
}

// ---------- beállítások ----------

function openSettings() {
  const s = settings();
  const root = $("#settings-body");
  root.replaceChildren();
  const group = (title, hint) => {
    const fs = el("fieldset", "setting");
    fs.appendChild(el("legend", null, title));
    if (hint) fs.appendChild(el("p", "hint", hint));
    root.appendChild(fs);
    return fs;
  };

  const markets = state.status.markets || [];
  if (markets.length > 1) {
    const ctry = group(t("Ország"), t("Melyik ország boltjainak árai alapján tervezzünk. Váltáskor új hét készül az ottani boltokkal (a kedvenceid maradnak)."));
    const list = el("div", "choice-list two");
    for (const m of markets) {
      const b = el("button", "choice country", null, { type: "button", "aria-pressed": String(market().id === m.id) });
      const txt = el("span", "choice-text");
      txt.append(el("b", null, `${m.name}${m.beta ? t(" · béta") : ""}`), el("span", null, COUNTRY_SUB[m.id] || m.source));
      b.append(el("span", "flag", m.flag, { "aria-hidden": "true" }), txt);
      b.addEventListener("click", () => { if (m.id !== market().id) switchCountry(m); });
      list.appendChild(b);
    }
    ctry.appendChild(list);
  }

  const lang = group(t("Nyelv"), t("A felület és a receptek nyelve ezen az eszközön. A háztartás többi tagja a saját nyelvén láthatja ugyanazt a hetet és listát."));
  lang.appendChild(langSwitch());

  const house = group(t("A háztartásod"), t("Ezekből készül a heti terv."));
  const row = el("div", "fields");
  row.append(
    settingStepper(t("Fő"), s.people, 1, 12, (v) => t("{n} fő", { n: v }), (v) => updateSettings({ people: v })),
    settingStepper(t("Vacsora a héten"), s.dinners, 1, 7, (v) => `${v}`, (v) => updateSettings({ dinners: v })));
  const money = el("label", "field");
  money.appendChild(el("span", "field-label", t("Heti keret")));
  const m = el("span", "money");
  const input = el("input", null, null, { type: "number", min: "0", step: market().decimals ? "5" : "500", inputmode: "numeric", placeholder: t("nincs") });
  input.value = s.budget ? Math.round(toLocalMoney(s.budget)) : "";
  input.addEventListener("input", () => updateSettings({ budget: Math.max(0, toInternalMoney(Number(input.value) || 0)) }));
  m.append(input, el("span", null, market().symbol));
  money.appendChild(m);
  row.appendChild(money);
  house.appendChild(row);

  const cook = group(t("Főzés"), t("Kétnaponta főzésnél egyszerre két napra készül az étel; a héten bármelyik napot átállíthatod."));
  for (const [v, title] of [["daily", t("Minden nap főzök")], ["every2", t("Kétnaponta főzök (a maradék másnapra)")]]) {
    const lab = el("label", "radio");
    const r = el("input", null, null, { type: "radio", name: "cook" });
    r.checked = (s.cookMode || "daily") === v;
    r.addEventListener("change", () => updateSettings({ cookMode: v }, true));
    lab.append(r, el("span", null, title));
    cook.appendChild(lab);
  }

  group(t("Diéta")).appendChild(checkChips(DIETS, s.diets, (v) => updateSettings({ diets: v })));
  group(t("Konyha"), t("Csak ezekkel elkészíthető recepteket kapsz.")).appendChild(checkChips(EQUIPMENT, s.equipment, (v) => {
    if (!v.length) { toast(t("Legalább egy eszköz maradjon.")); openSettings(); return; }
    updateSettings({ equipment: v });
  }));

  const stores = group(t("Boltjaim"), t("Ezek közül választjuk ki, hol a legolcsóbb a heted (vagy a fejlécben rögzíthetsz egyet), és ezekkel vetjük össze az árakat. A nem kedvelt láncot kiveheted."));
  const all = state.status.stores;
  const mine = s.stores?.length ? s.stores : all.map((x) => x.id);
  stores.appendChild(checkChips(all.map((x) => [x.id, x.name, x.color]), mine, (v) => {
    if (!v.length) { toast(t("Legalább egy bolt maradjon.")); openSettings(); return; }
    const patch = { stores: v.length === all.length ? [] : v };
    if (settings().store && !v.includes(settings().store)) patch.store = null;
    updateSettings(patch, true).then(() => loadStatus());
  }));
  const splitLab = el("label", "check-line");
  const splitCb = el("input", null, null, { type: "checkbox" });
  splitCb.checked = !!s.split;
  splitCb.addEventListener("change", () => updateSettings({ split: splitCb.checked }, true));
  splitLab.append(splitCb, el("span", null, t("Két boltba is elmegyek, ha megéri (a lista boltonként szétválik)")));
  stores.appendChild(splitLab);

  const pack = group(t("Kiszerelés"));
  for (const [v, title, hint] of [[false, t("Háztartási kiszerelés"), t("Annyit veszel, amennyi kell, vagy a legkisebb szokásos csomagot; a nagyobbat alternatívaként mutatjuk.")],
    [true, t("Nagy kiszerelés is jöhet, ha olcsóbb"), t("Például 5 kg krumpli vagy 30 tojás, ha a receptre vetítve legalább 10%-kal olcsóbb.")]]) {
    const lab = el("label", "radio");
    const r = el("input", null, null, { type: "radio", name: "pack" });
    r.checked = !!s.bulk === v;
    r.addEventListener("change", () => updateSettings({ bulk: v }, true));
    const txt = el("span");
    txt.append(el("b", null, title), el("br"), el("span", "hint", hint));
    lab.append(r, txt);
    pack.appendChild(lab);
  }

  const cards = group(t("Hűségkártyáim és appjaim"), t("A kártyás akciók csak akkor számítanak, ha van ilyened."));
  cards.appendChild(checkChips(all.filter((x) => x.cardName).map((x) => [x.id, x.cardName, x.color]), s.cards || [], (v) => updateSettings({ cards: v }, true)));
  if (market().id === "hu") cards.appendChild(el("p", "hint", t("Az akciós újságok és appok árait az admin felületen lehet felvinni (/admin).")));

  if (!isStandalone() && (installPrompt || isIos())) {
    const app = group(t("Alkalmazás"), t("Telepítsd a telefonodra: ikonként indul, teljes képernyőn, és a lista offline is megy."));
    if (installPrompt) {
      const b = el("button", "primary small", t("Telepítés a kezdőképernyőre"), { type: "button" });
      b.addEventListener("click", async () => {
        const p = installPrompt;
        installPrompt = null;
        try { await p.prompt(); } catch (e) { /* a böngésző elutasította */ }
        app.remove();
      });
      app.appendChild(b);
    } else {
      app.appendChild(el("p", "hint", t("iPhone-on: Safariban Megosztás → Főképernyőhöz adás.")));
    }
  }

  const share = group(t("Közös háztartás"), t("Ezzel a linkkel a párod vagy a családod ugyanazt a hetet és ugyanazt a bevásárlólistát látja, a kipipálás is közös. Csak annak add oda, akivel közösen vásároltok."));
  const link = `${location.origin}${location.pathname}#profil=${state.profile.id}`;
  const linkBox = el("input", "link-box", null, { type: "text", readonly: "", value: link, "aria-label": t("Megosztási link") });
  const copy = el("button", "ghost small", t("Link másolása"), { type: "button" });
  copy.addEventListener("click", async () => {
    try { await navigator.clipboard.writeText(link); toast(t("A link a vágólapra került.")); }
    catch (e) { linkBox.select(); }
  });
  const restart = el("button", "ghost small", t("Újrakezdés új profillal"), { type: "button" });
  restart.addEventListener("click", () => {
    local("sporolos.profile", null);
    $("#settings").close();
    state.profile = null;
    state.week = null;
    startOnboarding();
  });
  const sr = el("div", "share-row");
  sr.append(linkBox, copy);
  share.append(sr, restart);

  const data = group(t("Adatok"));
  const st = state.status;
  data.appendChild(el("p", "hint", t("Árak: {source} ({date}), {n} ár{history}. Tárolás: {storage}.", { source: st.source, date: st.date, n: nf.format(st.itemCount),
    history: st.historyDays ? t(", {n} nap előzmény", { n: st.historyDays }) : "", storage: state.profile.storage })));
  data.appendChild(el("p", "hint", market().id === "hu"
    ? t("Az árak 6 óránként maguktól frissülnek. A receptek, alapanyagok és bolti akciók az admin felületen (/admin) kezelhetők.")
    : t("A horvát árlisták naponta egyszer frissülnek (a láncok minden reggel közzéteszik őket). A receptek és az alapanyagok közösek, az illesztés a terméknevek alapján történik – béta.")));
  openDialog($("#settings"));
}

/** Ország váltása: új profilbeállítás (az ottani boltokkal), az árak betöltése, új hét. */
async function switchCountry(m) {
  $("#settings").close();
  try {
    state.marketHint = m;
    state.profile = await api(`/api/profile?id=${state.profile.id}`, jsonOpts("PUT",
      { ...settings(), country: m.id, store: null, stores: [], cards: [], budget: toInternalMoney(DEFAULT_BUDGET[m.currency] || 15000) }), m.id);
    state.week = null;
    state.recipes = null;
    state.prevDays = [];
    $("#offer-stores").replaceChildren();
    $("#dinners").replaceChildren(...skeletonCards(settings().dinners || 5));
    await loadStatus();
    renderStorePill();
    await loadWeek();
    if (state.view !== "het" && state.view !== "lista") setView(state.view);
    toast(t("{flag} {name}: mostantól az ottani árakkal tervezünk.", { flag: m.flag, name: m.name }));
  } catch (e) {
    toast(e.message);
  }
}

function settingStepper(label, value, min, max, fmt, onChange) {
  const wrap = el("div", "field");
  wrap.appendChild(el("span", "field-label", label));
  const st = el("div", "stepper");
  let v = value;
  const minus = el("button", "step", "−", { type: "button", "aria-label": t("{label}: kevesebb", { label }) });
  const val = el("span", "step-value", fmt(v));
  const plus = el("button", "step", "+", { type: "button", "aria-label": t("{label}: több", { label }) });
  const set = (d) => {
    v = Math.max(min, Math.min(max, v + d));
    val.textContent = fmt(v);
    minus.disabled = v <= min;
    plus.disabled = v >= max;
    if (d) onChange(v);
  };
  minus.addEventListener("click", () => set(-1));
  plus.addEventListener("click", () => set(1));
  set(0);
  st.append(minus, val, plus);
  wrap.appendChild(st);
  return wrap;
}

function checkChips(options, selected, onChange) {
  const wrap = el("div", "chips");
  for (const [v, label, color] of options) {
    const lab = el("label", "chip");
    if (color) lab.style.setProperty("--c", color);
    const cb = el("input", null, null, { type: "checkbox", value: v });
    cb.checked = selected.includes(v);
    cb.addEventListener("change", () => onChange($$("input:checked", wrap).map((x) => x.value)));
    lab.append(cb, el("span", null, label));
    wrap.appendChild(lab);
  }
  return wrap;
}

// ---------- saját tételek ----------

async function addExtra(name, itemId) {
  try {
    await api(`/api/extras?id=${state.profile.id}`, jsonOpts("POST", { name, itemId: itemId || null }));
    toast(t("„{name}” a listán.", { name }));
    await loadWeek();
  } catch (e) {
    toast(e.message);
  }
}

async function removeExtra(n) {
  try {
    await api(`/api/extras?id=${state.profile.id}&n=${n}`, { method: "DELETE" });
    await loadWeek();
  } catch (e) {
    toast(e.message);
  }
}

function renderExtras(w, checked) {
  const ul = $("#extras");
  ul.replaceChildren();
  for (const x of w.extras || []) {
    const li = el("li");
    const key = `extra:${x.name}`;
    const id = `chk-x-${x.n}`;
    const cb = el("input", null, null, { type: "checkbox", id });
    cb.checked = checked.has(key);
    li.classList.toggle("done", cb.checked);
    cb.addEventListener("change", () => toggleCheck(key, cb.checked, li));
    const label = el("label", null, null, { for: id });
    label.append(el("span", "buy-name", x.name),
      el("span", "buy-qty", x.price != null ? `${x.similar ? t("hasonló: ") : ""}${x.product} · ${x.storeName}` : x.itemId ? t("itt nincs hasonló termék ({store})", { store: x.storeName }) : t("ár nélkül")));
    const right = el("span", "buy-price", x.price != null ? ft(x.price) : "–");
    const del = el("button", "icon-del", "✕", { type: "button", "aria-label": t("{name} törlése", { name: x.name }) });
    del.addEventListener("click", () => removeExtra(x.n));
    li.append(cb, label, right, del);
    ul.appendChild(li);
  }
}

let suggestTimer;
function bindExtras() {
  const q = $("#extra-q"), box = $("#extra-suggest");
  const close = () => { box.hidden = true; box.replaceChildren(); };
  q.addEventListener("input", () => {
    clearTimeout(suggestTimer);
    const v = q.value.trim();
    if (v.length < 2) return close();
    suggestTimer = setTimeout(async () => {
      try {
        const hits = await api(`/api/search?q=${encodeURIComponent(v)}&store=${currentStore()}&limit=8${prefQuery()}`);
        box.replaceChildren();
        for (const h of hits) {
          const li = el("li");
          const b = el("button", null, null, { type: "button" });
          b.append(el("span", null, h.name), el("b", null, ft(h.price)));
          b.addEventListener("click", () => { close(); q.value = ""; addExtra(h.name, h.id); });
          li.appendChild(b);
          box.appendChild(li);
        }
        box.hidden = !hits.length;
      } catch (e) { close(); }
    }, 220);
  });
  q.addEventListener("blur", () => setTimeout(close, 200));
  $("#extra-form").addEventListener("submit", (e) => {
    e.preventDefault();
    const v = q.value.trim();
    if (!v) return;
    close();
    q.value = "";
    addExtra(v, null);
  });
}

// Offline pipák: a boltban gyakran nincs térerő. A pipa azonnal látszik, a szerverre pedig akkor megy fel,
// amikor újra van hálózat (a sorrend megmarad, egy tétel utolsó állapota számít).
const PENDING = "sporolos.pendingChecks";
const pendingChecks = () => { try { return JSON.parse(local(PENDING) || "[]"); } catch (e) { return []; } };

function queueCheck(c) {
  const list = pendingChecks().filter((x) => !(x.key === c.key && x.item === c.item && x.id === c.id));
  list.push(c);
  local(PENDING, JSON.stringify(list));
}

async function flushChecks() {
  const list = pendingChecks();
  if (!list.length || !navigator.onLine) return;
  local(PENDING, null);
  for (let i = 0; i < list.length; i++) {
    const c = list[i];
    try {
      const r = await api(`/api/check?id=${c.id}`, jsonOpts("POST", { key: c.key, item: c.item, on: c.on }));
      if (state.week && state.profile?.id === c.id && state.week.listKey === c.key) state.week.checked = r.checked;
    } catch (e) {
      if (e.offline) { for (const x of list.slice(i)) queueCheck(x); return; }
      // a lista közben megváltozott (új hét) – ezt a pipát elengedjük
    }
  }
  if (state.view === "lista") renderList();
}

async function toggleCheck(key, on, li) {
  li.classList.toggle("done", on);
  if (on) {
    li.classList.remove("just-done");
    void li.offsetWidth;
    li.classList.add("just-done");
    // „kipipáltak elrejtése” módban az animáció után tűnik el
    setTimeout(() => li.classList.remove("just-done"), 900);
    if (navigator.vibrate) try { navigator.vibrate(12); } catch (e) { /* nem támogatott */ }
  }
  const c = { id: state.profile.id, key: state.week.listKey, item: key, on };
  try {
    const r = await api(`/api/check?id=${c.id}`, jsonOpts("POST", { key: c.key, item: c.item, on: c.on }));
    state.week.checked = r.checked;
  } catch (e) {
    if (!e.offline) { toast(e.message); return; }
    queueCheck(c);
    const set = new Set(state.week.checked || []);
    if (on) set.add(key); else set.delete(key);
    state.week.checked = [...set];
  }
  updateProgress();
}

function listItems(w) {
  return (w.split ? w.split.groups.flatMap((g) => g.aisles) : w.aisles).flatMap((a) => a.items).filter((x) => !x.staple);
}

let celebrated = "";
function updateProgress() {
  const w = state.week;
  const checked = new Set(w.checked || []);
  const items = listItems(w).map(itemKey).concat((w.extras || []).map((x) => `extra:${x.name}`));
  const done = items.filter((k) => checked.has(k)).length;
  const box = $("#list-progress");
  box.replaceChildren(el("span", "progress-text", done === items.length && items.length ? t("Minden a kosárban 🎉") : t("{done} / {n} a kosárban", { done, n: items.length })));
  const bar = el("span", "progress-bar");
  const fill = el("span");
  fill.style.width = `${items.length ? (done / items.length) * 100 : 0}%`;
  bar.appendChild(fill);
  box.appendChild(bar);
  if (items.length && done === items.length && celebrated !== w.listKey && state.view === "lista") {
    celebrated = w.listKey;
    confetti();
    toast(t("Kész a bevásárlás! Jó főzést 👩‍🍳"));
  }
  if (done < items.length && celebrated === w.listKey) celebrated = "";
}

/** Egy kis ünneplés (csak ha a rendszer nem kér kevesebb mozgást). */
function confetti() {
  if (matchMedia("(prefers-reduced-motion: reduce)").matches) return;
  const box = $("#confetti");
  box.replaceChildren();
  const colors = ["var(--tag)", "var(--leaf)", "var(--red)", "#4c7bd9", "#f08c2e"];
  for (let i = 0; i < 70; i++) {
    const p = el("i");
    p.style.left = `${Math.random() * 100}%`;
    p.style.background = colors[i % colors.length];
    p.style.setProperty("--dx", `${(Math.random() - 0.5) * 220}px`);
    p.style.setProperty("--rot", `${Math.random() * 720 - 360}deg`);
    p.style.animationDelay = `${Math.random() * 0.35}s`;
    p.style.animationDuration = `${1.6 + Math.random() * 1.2}s`;
    box.appendChild(p);
  }
  setTimeout(() => box.replaceChildren(), 3400);
}

// ---------- indulás ----------

function bind() {
  window.addEventListener("hashchange", route);
  $("#store-pill").addEventListener("click", openStoreSheet);
  $("#split-toggle").addEventListener("change", (e) => {
    $("#store-sheet").close();
    updateSettings({ split: e.target.checked }, true);
  });
  $("#open-settings").addEventListener("click", () => state.profile && openSettings());
  $("#week-new").addEventListener("click", () => {
    $("#dinners").classList.add("refreshing");
    loadWeek("new").catch((e) => toast(e.message)).finally(() => $("#dinners").classList.remove("refreshing"));
  });
  $("#list-share").addEventListener("click", shareList);
  $("#week-ics").addEventListener("click", exportIcs);
  $("#list-print").addEventListener("click", () => window.print());
  const hide = $("#list-hide-done");
  const applyHide = () => $('section[data-view="lista"]').classList.toggle("hide-done", hide.checked);
  hide.checked = local("sporolos.hideDone") === "1";
  applyHide();
  hide.addEventListener("change", () => { local("sporolos.hideDone", hide.checked ? "1" : null); applyHide(); });
  $("#nom-yes").addEventListener("click", () => decide(true));
  $("#nom-no").addEventListener("click", () => decide(false));
  $("#nom-later").addEventListener("click", () => decide(null));
  $("#nom-reset").addEventListener("click", async () => {
    try {
      state.profile = await api(`/api/rate?id=${state.profile.id}`, { method: "DELETE" });
      await loadDeck();
      toast(t("Az értékeléseid törölve."));
    } catch (e) { toast(e.message); }
  });
  document.addEventListener("keydown", (e) => {
    if (state.view !== "nom" || document.querySelector("dialog[open]") || e.target.closest("input, textarea, select")) return;
    if (e.key === "ArrowRight") decide(true);
    if (e.key === "ArrowLeft") decide(false);
  });
  let t1, t2;
  $("#browse-q").addEventListener("input", () => { clearTimeout(t1); t1 = setTimeout(() => renderBrowse().catch((e) => toast(e.message)), 150); });
  $("#offer-q").addEventListener("input", () => { clearTimeout(t2); t2 = setTimeout(() => renderOffers().catch((e) => toast(e.message)), 250); });
  $("#offer-all").addEventListener("change", () => renderOffers().catch((e) => toast(e.message)));
  bindExtras();
  $("#browse-sort").addEventListener("change", () => renderBrowse().catch((e) => toast(e.message)));
  $("#browse-filters").addEventListener("click", (e) => {
    const b = e.target.closest("button[data-f]");
    if (!b) return;
    state.filter = b.dataset.f;
    for (const x of $$("#browse-filters button")) x.setAttribute("aria-pressed", String(x === b));
    renderBrowse().catch((err) => toast(err.message));
  });
  $("#ob-next").addEventListener("click", obNext);
  $("#cook-next").addEventListener("click", () => cookMove(1));
  $("#cook-prev").addEventListener("click", () => cookMove(-1));
  $("#cook-close").addEventListener("click", () => $("#cook").close());
  $("#cook").addEventListener("close", () => { try { cook.lock?.release(); } catch (e) { /* már elengedve */ } cook.lock = null; });
  $("#cook").addEventListener("keydown", (e) => {
    if (e.key === "ArrowRight") cookMove(1);
    if (e.key === "ArrowLeft") cookMove(-1);
  });
  let cx = null;
  $("#cook-stage").addEventListener("pointerdown", (e) => { cx = e.clientX; });
  $("#cook-stage").addEventListener("pointerup", (e) => {
    if (cx == null) return;
    const dx = e.clientX - cx;
    cx = null;
    if (Math.abs(dx) > 60) cookMove(dx < 0 ? 1 : -1);
  });
  $("#loader-retry").addEventListener("click", () => {
    $("#loader-actions").hidden = true;
    $("#loader-phase").textContent = t("Újrapróbálom…");
    const r = loaderRetry;
    loaderRetry = null;
    if (r) r();
  });
  $("#loader-home").addEventListener("click", async () => {
    if (state.profile) {
      try {
        await fetch(`/api/profile?id=${state.profile.id}`, jsonOpts("PUT", { ...settings(), country: "hu", store: null, stores: [], cards: [] }));
      } catch (e) { /* a hálózati hibát az újratöltés úgyis jelzi */ }
    }
    location.reload();
  });
  $("#ob-back").addEventListener("click", () => obMove(-1));
  for (const dlg of $$("dialog:not(#onboard)")) {
    dlg.addEventListener("click", (e) => { if (e.target === e.currentTarget) e.currentTarget.close(); });
  }
  $("#recipe-sheet").addEventListener("close", () => {
    if (new URLSearchParams(location.hash.slice(1)).get("recept")) history.replaceState(null, "", `#${state.view}`);
  });
}

async function afterProfile() {
  local("sporolos.profileData", JSON.stringify(state.profile));
  await loadStatus();
  renderStorePill();
  const h = location.hash.slice(1);
  if (!VIEWS.includes(h) && !h.startsWith("recept=")) history.replaceState(null, "", "#het");
  route();
  if (state.view !== "het" && state.view !== "lista") loadWeek().catch((e) => toast(e.message));
}

/**
 * Váratlan hiba (programhiba, kezeletlen Promise): a felhasználó egy rövid üzenetet kap ahelyett, hogy egy némán
 * megakadt oldalt látna – legfeljebb 10 másodpercenként egyet, és csak a saját szkriptjeink hibáira (a böngésző-
 * bővítményekéire nem). A részletek a konzolban maradnak.
 */
let lastCrashToast = 0;
function reportUnexpected(err, source) {
  if (source && !String(source).startsWith(location.origin)) return;
  if (err && err.offline) return; // az offline állapotot külön jelezzük
  const now = Date.now();
  if (now - lastCrashToast < 10000) return;
  lastCrashToast = now;
  try { toast(t("Valami nem sikerült. Ha nem működik rendesen, frissítsd az oldalt.")); } catch (e) { /* a toast sem elérhető */ }
}
window.addEventListener("error", (e) => reportUnexpected(e.error, e.filename));
window.addEventListener("unhandledrejection", (e) => reportUnexpected(e.reason, location.origin));

/** Offline jelzés a fejléc alatt; újra online állapotban a sorban álló pipák felmennek. */
function bindConnectivity() {
  const show = () => {
    const b = $("#offline");
    if (b) b.hidden = navigator.onLine;
  };
  window.addEventListener("offline", () => { show(); toast(t("Offline vagy – a legutóbb betöltött hetet és listát látod.")); });
  window.addEventListener("online", () => { show(); toast(t("Újra online.")); flushChecks(); });
  show();
  if ("serviceWorker" in navigator && location.protocol !== "file:") {
    // az első látogatáskor a service worker csak az első kérések után indul: amit addig betöltöttünk, azt még egyszer lekérjük
    const hadController = !!navigator.serviceWorker.controller;
    navigator.serviceWorker.addEventListener("controllerchange", () => {
      if (hadController) return;
      for (const path of seenGets) fetch(path).catch(() => { /* offline */ });
    });
    navigator.serviceWorker.register("/sw.js").catch(() => { /* nem baj: offline mód nélkül is működik */ });
  }
}

async function init() {
  bind();
  bindConnectivity();
  try {
    state.status = await api("/api/status");
    const p = await loadProfile();
    if (!p) {
      await loadStatus();
      startOnboarding();
      return;
    }
    await afterProfile();
    flushChecks();
  } catch (e) {
    $("#banner").textContent = t("Az adatok nem tölthetők be: {error}", { error: e.message });
    $("#banner").hidden = false;
  }
}

init();
