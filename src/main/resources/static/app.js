// Spórolós Konyha – kliensoldal. Nincs keretrendszer: fetch + DOM, minden szöveg textContent-tel kerül ki.
"use strict";

const $ = (sel, root = document) => root.querySelector(sel);
const $$ = (sel, root = document) => Array.from(root.querySelectorAll(sel));

const state = {
  status: null,
  store: null,
  tag: "",
  suggestions: [],   // a bolt rangsorolt receptjei (szűrővel)
  shown: new Set(),  // a "Másik receptet kérek" már mutatott receptjei
  current: null,     // a kiválasztott recept részletei (/api/recipe)
  swapped: new Set(),// a hozzávalók, amelyeknél a felhasználó az alternatív kiszerelést választotta
  servings: 0,       // 0 = a recept alapadagja
  showAll: false,
  sort: "score",
  requestId: 0,      // elavult válaszok eldobásához
  dealLimit: 12,     // ennyi jó árat mutatunk elsőre (telefonon ne legyen végtelen a lista)
  prefs: { stores: [], bulk: false, cards: [] }, // stores: üres = minden bolt
  ingredients: null, // az akció-űrlap alapanyag-listája
  view: "ma",        // ma | terv | nom | receptek (views.js)
};

const DEAL_LABEL = {
  CHAIN: "Itt a legolcsóbb",
  HISTORY: "Olcsóbb a szokásosnál",
  SPREAD: "Akciógyanús",
  CATEGORY: "Tartósan olcsó",
  PROMO: "Bolti akció",
};

// ---------- formázás ----------

const nf = new Intl.NumberFormat("hu-HU", { maximumFractionDigits: 0 });
const ft = (v) => nf.format(Math.round(v)).replace(/\s/g, " ") + " Ft";

function qty(amount, unit) {
  const comma = (n) => String(n).replace(".", ",");
  if (unit === "kg" && amount < 1) return `${Math.round(amount * 1000)} g`;
  if (unit === "l" && amount < 1) return `${comma(Math.round(amount * 100) / 10)} dl`;
  return `${comma(Math.round(amount * 100) / 100)} ${unit}`;
}

function storeName(id) {
  return state.status?.stores.find((s) => s.id === id)?.name || id;
}

/** "a Lidlben", "az Aldiban", "a Tescóban" – a láncnevek ragozása kézzel, mert szabállyal nem megy. */
const IN_STORE = {
  aldi: "az Aldiban", lidl: "a Lidlben", penny: "a Pennyben", auchan: "az Auchanban",
  tesco: "a Tescóban", spar: "a Sparban", rossmann: "a Rossmannban", muller: "a Müllerben", dm: "a dm-ben",
};
function inStore(id) {
  return IN_STORE[id] || `itt: ${storeName(id)} –`;
}

const cap = (t) => t.charAt(0).toUpperCase() + t.slice(1);

function el(tag, cls, text, attrs) {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (text != null) e.textContent = text;
  if (attrs) for (const [k, v] of Object.entries(attrs)) e.setAttribute(k, v);
  return e;
}

// ---------- tárolás, beállítások, állapot az URL-ben ----------

function store(key, value) {
  try {
    if (value === undefined) return localStorage.getItem(key);
    if (value === null) localStorage.removeItem(key);
    else localStorage.setItem(key, value);
  } catch (e) {
    return null;
  }
  return null;
}

function loadPrefs() {
  try {
    const p = JSON.parse(store("sporolos.prefs") || "null");
    if (p && typeof p === "object") {
      state.prefs = {
        stores: Array.isArray(p.stores) ? p.stores : [],
        bulk: !!p.bulk,
        cards: Array.isArray(p.cards) ? p.cards : [],
      };
    }
  } catch (e) { /* alapbeállítások */ }
}

function savePrefs() {
  store("sporolos.prefs", JSON.stringify(state.prefs));
}

/** A beállítások query-paraméterként minden kéréshez. */
function prefQuery() {
  const p = new URLSearchParams();
  if (state.prefs.stores.length) p.set("stores", state.prefs.stores.join(","));
  if (state.prefs.bulk) p.set("bulk", "1");
  if (state.prefs.cards.length) p.set("cards", state.prefs.cards.join(","));
  const s = p.toString();
  return s ? "&" + s : "";
}

/** A felhasználó boltjai (a státusz sorrendjében). */
function myStores() {
  const all = state.status?.stores || [];
  if (!state.prefs.stores.length) return all;
  const mine = all.filter((s) => state.prefs.stores.includes(s.id));
  return mine.length ? mine : all;
}

function readHash() {
  const p = new URLSearchParams(location.hash.slice(1));
  return { store: p.get("bolt"), recipe: p.get("recept"), servings: Number(p.get("adag")) || 0, tag: p.get("cimke") || "", view: p.get("nezet") || "" };
}

function writeHash() {
  const p = new URLSearchParams();
  if (state.view && state.view !== "ma") p.set("nezet", state.view);
  if (state.store) p.set("bolt", state.store);
  if (state.current && state.view === "ma") p.set("recept", state.current.recipe.id);
  if (state.servings) p.set("adag", String(state.servings));
  if (state.tag) p.set("cimke", state.tag);
  const h = "#" + p.toString();
  if (location.hash !== h) history.replaceState(null, "", h);
}

// ---------- hálózat ----------

async function api(path, opts) {
  const sep = path.includes("?") ? "" : "?";
  const res = await fetch(path + sep + prefQuery(), opts);
  const body = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(body.error || `A szerver ${res.status} hibával válaszolt.`);
  return body;
}

function toast(msg) {
  const t = $("#toast");
  t.textContent = msg;
  t.hidden = false;
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => { t.hidden = true; }, 3200);
}

function showError(msg) {
  const pick = $("#pick");
  pick.replaceChildren(el("p", "empty", msg + " Indítsd újra az alkalmazást, vagy próbáld az „Árak frissítése” gombot."));
}

// ---------- betöltés ----------

async function loadStatus() {
  state.status = await api("/api/status");
  const s = state.status;
  const d = new Date(s.date + "T00:00:00");
  const dateText = d.toLocaleDateString("hu-HU", { month: "long", day: "numeric" });
  let text = `${s.source} · ${dateText} · ${nf.format(s.itemCount)} élelmiszerár`;
  if (s.activePromos > 0) text += ` · ${s.activePromos} saját akció`;
  if (s.historyDays > 0) text += ` · ${s.historyDays} nap előzmény`;
  $("#source").textContent = text;

  const banner = $("#banner");
  if (s.demo) {
    banner.textContent = "Demó árakat látsz: az Árfigyelő napi fájlja most nem volt elérhető. Ellenőrizd az internetkapcsolatot, majd nyomd meg az „Árak frissítése” gombot.";
    banner.hidden = false;
  } else if (s.stale) {
    banner.textContent = `Az árak ${dateText}-i állapotot mutatnak. Frissebb adatért nyomd meg az „Árak frissítése” gombot.`;
    banner.hidden = false;
  } else {
    banner.hidden = true;
  }

  const ids = myStores().map((x) => x.id);
  if (!state.store || !ids.includes(state.store)) {
    const saved = store("sporolos.store");
    state.store = ids.includes(saved) ? saved : bestStore()?.id;
  }
  renderStores();
}

function bestStore() {
  return myStores().reduce((a, b) => (!a || b.topScore > a.topScore ? b : a), null);
}

/** A bolt receptjei és jó árai; utána a kért (vagy a legjobb) recept. */
async function loadStore(preferredRecipe) {
  if (!state.store) return;
  const tag = encodeURIComponent(state.tag);
  const [sugs] = await Promise.all([
    api(`/api/suggestions?store=${state.store}&tag=${tag}&limit=200`),
    loadDeals(),
  ]);
  state.suggestions = sugs;
  state.shown = new Set();
  if (preferredRecipe && sugs.some((s) => s.recipe.id === preferredRecipe)) {
    await showRecipe(preferredRecipe, state.servings > 0);
  } else {
    await nextRecipe(true);
  }
}

async function loadDeals() {
  const q = $("#search").value.trim();
  if (q.length >= 2) return runSearch(q);
  const only = $("#onlyIngredients").checked;
  const deals = await api(`/api/deals?store=${state.store}&limit=${state.dealLimit + 1}&ingredients=${only}`);
  renderDeals(deals.slice(0, state.dealLimit));
  $("#more-deals").hidden = deals.length <= state.dealLimit;
}

async function showRecipe(id, keepServings) {
  const myId = ++state.requestId;
  if (!keepServings) state.servings = 0;
  const servings = state.servings ? `&servings=${state.servings}` : "";
  const detail = await api(`/api/recipe?id=${encodeURIComponent(id)}&store=${state.store}${servings}`);
  if (myId !== state.requestId) return; // közben másik receptet kértek
  if (state.current?.recipe.id !== id || state.current?.store !== detail.store) state.swapped = new Set();
  state.current = detail;
  state.shown.add(id);
  renderPick(!keepServings);
  renderMore();
  writeHash();
}

/** Mindent újratölt (beállítás-változás után), a kiválasztott receptet megtartva. */
async function reloadAll() {
  const keep = state.current?.recipe.id;
  await loadStatus();
  await loadStore(keep);
  if (typeof onStoreChanged === "function") onStoreChanged();
}

// ---------- receptválasztás ----------

/** Súlyozott véletlen a legjobb 8 közül, ismétlés nélkül – így "dobál" recepteket, de mindig jókat. */
async function nextRecipe(first) {
  const pool = state.suggestions.slice(0, 8);
  if (pool.length === 0) {
    state.current = null;
    renderPick();
    renderMore();
    writeHash();
    return;
  }
  let candidates = pool.filter((s) => !state.shown.has(s.recipe.id));
  if (candidates.length === 0) {
    state.shown = new Set(state.current ? [state.current.recipe.id] : []);
    candidates = pool.filter((s) => !state.shown.has(s.recipe.id));
    if (candidates.length === 0) candidates = pool;
  }
  let chosen = candidates[0];
  if (!first) {
    const weights = candidates.map((s) => Math.pow(s.score + 0.05, 2));
    let r = Math.random() * weights.reduce((a, b) => a + b, 0);
    chosen = candidates[candidates.length - 1];
    for (let i = 0; i < candidates.length; i++) {
      r -= weights[i];
      if (r <= 0) { chosen = candidates[i]; break; }
    }
  }
  await showRecipe(chosen.recipe.id);
}

// ---------- boltválasztó ----------

function renderStores() {
  const row = $("#stores");
  row.replaceChildren();
  const best = bestStore();
  const mine = myStores();
  for (const s of mine) {
    const b = el("button", "store", null, { type: "button", role: "tab", "aria-selected": String(s.id === state.store) });
    b.style.setProperty("--c", s.color);
    b.append(el("span", "store-name", s.name), el("span", "store-meta", `${s.dealCount} jó ár`));
    if (best && s.id === best.id && mine.length > 1) b.appendChild(el("span", "best", "ma itt a legjobb"));
    b.addEventListener("click", () => selectStore(s.id));
    row.appendChild(b);
  }
  const hidden = (state.status?.stores.length || 0) - mine.length;
  if (hidden > 0) {
    const more = el("button", "store store-edit", null, { type: "button" });
    more.append(el("span", "store-name", "+"), el("span", "store-meta", `${hidden} bolt elrejtve`));
    more.addEventListener("click", openSettings);
    row.appendChild(more);
  }
  row.querySelector('[aria-selected="true"]')?.scrollIntoView({ block: "nearest", inline: "nearest" });
}

function selectStore(id, recipe) {
  if (id === state.store && !recipe) return;
  state.store = id;
  state.dealLimit = 12;
  store("sporolos.store", id);
  renderStores();
  loadStore(recipe || state.current?.recipe.id).catch((e) => showError(e.message));
  if (typeof onStoreChanged === "function") onStoreChanged();
}

// ---------- a kiválasztott recept ----------

/** "35 perc · könnyű · 520 kcal · 32 g fehérje / adag" */
function metaLine(r) {
  const parts = [`${r.minutes} perc`, r.difficulty];
  if (r.nutrition) parts.push(`${r.nutrition.kcal} kcal`, `${r.nutrition.protein} g fehérje / adag`);
  return parts.join(" · ");
}

/** Diéta- és hangulatcímkék kis jelvényként. */
function badges(r, max) {
  const wrap = el("span", "badges");
  const list = [...(r.diets || []), ...(r.moods || [])].slice(0, max || 9);
  for (const b of list) wrap.appendChild(el("span", "badge" + (DIET_SET.has(b) ? " diet" : ""), b));
  return wrap;
}
const DIET_SET = new Set(["vega", "gluténmentes", "laktózmentes"]);

/** A hozzávalók a felhasználó kiszerelés-választásaival (alternatíva cserélve, ahol kérte). */
function effectiveIngredients(s) {
  return s.ingredients.map((i) => (state.swapped.has(i.label) && i.alternative ? { ...i.alternative, swappedFrom: i } : i));
}

function totals(items) {
  let cost = 0, checkout = 0, staples = 0;
  for (const i of items) {
    if (i.pantry) continue;
    cost += i.cost;
    if (i.staple) staples += i.checkoutCost; else checkout += i.checkoutCost;
  }
  return { cost, checkout, staples };
}

function renderPick(animate) {
  const pick = $("#pick");
  const s = state.current;
  pick.classList.remove("fresh");
  pick.replaceChildren();
  $("#counter").textContent = "";

  if (!s) {
    pick.appendChild(el("p", "empty", state.tag
      ? "Ehhez a szűrőhöz ebben a boltban most nincs elég árazható recept. Válassz másik szűrőt vagy boltot."
      : "Ebben a boltban most nincs elég árazható alapanyag. Válassz másik boltot."));
    return;
  }

  const r = s.recipe;
  const items = effectiveIngredients(s);
  const t = totals(items);
  const deals = items.filter((i) => i.deal);

  pick.appendChild(el("img", "pick-img", null, { src: r.image, alt: "", width: "640", height: "400", decoding: "async" }));
  const head = el("div", "pick-head");
  const left = el("div");
  left.append(
    el("h2", "pick-name", r.name, { id: "pick-title" }),
    el("p", "pick-meta", metaLine(r)),
    badges(r),
    el("p", "pick-why", whyText(s, deals)),
  );
  const pack = packNote(s, t);
  if (pack) left.appendChild(pack);

  const right = el("div", "pick-side");
  const tag = el("div", "price-tag", null, { role: "img", "aria-label": `A recept költsége kb. ${ft(t.cost)}` });
  tag.append(el("small", null, `${s.servings} adag, kb.`), el("span", "now", ft(t.cost)));
  if (s.otherStoresCost - t.cost >= 20) {
    const was = el("small", "was", "máshol ");
    was.appendChild(el("span", "old", ft(s.otherStoresCost)));
    tag.appendChild(was);
  }
  right.append(tag, servingsControl(s));
  head.append(left, right);
  pick.appendChild(head);

  const body = el("div", "pick-body");
  body.append(renderIngredients(items), renderSteps(r));
  pick.appendChild(body);
  pick.appendChild(renderShoppingList(s, items, t));
  pick.appendChild(renderComparison(s, t));

  if (animate) {
    void pick.offsetWidth;
    pick.classList.add("fresh");
  }

  const left8 = state.suggestions.slice(0, 8).filter((x) => !state.shown.has(x.recipe.id)).length;
  $("#counter").textContent = left8 > 0 ? `még ${left8} jó ötlet erre a boltra` : "körbeértünk, újrakezdjük";
}

/** Rövid megjegyzés a kiszerelésről: mennyit spórolna nagy kiszereléssel (vagy fordítva). */
function packNote(s, t) {
  const withAlt = s.ingredients.filter((i) => i.alternative);
  if (!withAlt.length) return null;
  const allSwapped = withAlt.every((i) => state.swapped.has(i.label));
  const box = el("p", "pack-note");
  const btn = el("button", "linkish", null, { type: "button" });
  if (!s.bulkMode) {
    const save = s.totalCost - s.altTotalCost;
    if (allSwapped) {
      box.append(`Nagy kiszerelésekkel számolva: a recept ${ft(save)}-tal olcsóbb, de ma ${ft(t.checkout)}-ot fizetsz. `);
      btn.textContent = "Vissza a háztartási kiszerelésre";
    } else {
      box.append(`Nagy kiszerelésekkel a recept ${ft(save)}-tal olcsóbb lenne (${ft(s.altTotalCost)}), de ma ${ft(s.altCheckoutTotal)}-ot fizetnél ${ft(s.checkoutTotal)} helyett. `);
      btn.textContent = "Mutasd nagy kiszereléssel";
    }
  } else {
    if (allSwapped) {
      box.append("Háztartási kiszerelésekkel számolva. ");
      btn.textContent = "Vissza a nagy kiszerelésre";
    } else {
      box.append(`Nagy kiszerelésekkel számolva. Háztartási kiszerelésekkel ma csak ${ft(s.altCheckoutTotal)}-ot fizetnél, de a recept ${ft(s.altTotalCost - s.totalCost)}-tal drágább lenne. `);
      btn.textContent = "Mutasd háztartási kiszereléssel";
    }
  }
  btn.addEventListener("click", () => {
    if (allSwapped) state.swapped = new Set();
    else state.swapped = new Set(withAlt.map((i) => i.label));
    renderPick(false);
  });
  box.appendChild(btn);
  return box;
}

function servingsControl(s) {
  const wrap = el("div", "servings", null, { role: "group", "aria-label": "Adagszám" });
  const minus = el("button", "step", "−", { type: "button", "aria-label": "Kevesebb adag" });
  const value = el("span", "servings-value", `${s.servings} adag`);
  const plus = el("button", "step", "+", { type: "button", "aria-label": "Több adag" });
  minus.disabled = s.servings <= 1;
  plus.disabled = s.servings >= 20;
  const change = (d) => {
    state.servings = Math.max(1, Math.min(20, s.servings + d));
    showRecipe(s.recipe.id, true).catch((e) => toast(e.message));
  };
  minus.addEventListener("click", () => change(-1));
  plus.addEventListener("click", () => change(+1));
  wrap.append(minus, value, plus);
  return wrap;
}

function whyText(s, deals) {
  const fmt = (i) => `${i.label.toLowerCase()}${i.deal.savingPercent > 0 ? ` (−${Math.round(i.deal.savingPercent)}%)` : ""}`;
  const promo = deals.filter((i) => i.deal.type === "PROMO");
  const hot = deals.filter((i) => i.deal.type !== "CATEGORY" && i.deal.type !== "PROMO");
  const steady = deals.filter((i) => i.deal.type === "CATEGORY");
  const parts = [];
  if (promo.length) parts.push(`${cap(inStore(s.store))} akcióban van: ${promo.slice(0, 3).map(fmt).join(", ")}.`);
  if (hot.length) parts.push(`${promo.length ? "Emellett épp most jó áron" : `${cap(inStore(s.store))} épp most jó áron`} van: ${hot.slice(0, 3).map(fmt).join(", ")}.`);
  if (steady.length) parts.push(`${hot.length || promo.length ? "Tartósan" : `${cap(inStore(s.store))} tartósan`} olcsó: ${steady.slice(0, 3).map(fmt).join(", ")}.`);
  if (parts.length === 0) parts.push(`Nincs benne kiemelt akció, de ${inStore(s.store)} ez most az egyik legolcsóbban összevásárolható recept.`);
  if (s.savings >= 20) parts.push(`A többi boltodban átlagosan ${ft(s.savings)}-tal többe kerülne.`);
  return parts.join(" ");
}

function renderIngredients(items) {
  const box = el("div", "ingredients");
  box.appendChild(el("h3", null, "Hozzávalók és mit vegyél"));
  const ul = el("ul", "ing");
  let anyEstimated = false;
  for (const i of items) {
    const li = el("li");
    if (i.pantry) li.classList.add("pantry");
    if (i.estimated) { li.classList.add("estimated"); anyEstimated = true; }
    const label = el("span");
    label.append(el("span", "ing-name", i.label), document.createTextNode(" "), el("span", "ing-amount", i.amount ? `– ${i.amount}` : ""));
    li.append(label, el("span", "ing-cost", i.pantry ? "otthon van" : ft(i.cost)));
    if (i.item) {
      const p = el("span", "ing-product");
      const link = el("button", "linkish", i.item.name, { type: "button" });
      link.addEventListener("click", () => openItem(i.item.id));
      p.append(link);
      p.appendChild(el("span", "ing-price", ` · ${i.priceText}`));
      if (i.item.availability < 0.5) {
        p.appendChild(el("span", "avail", ` · a boltjaik ${Math.max(1, Math.round(i.item.availability * 100))}%-ában`));
      }
      p.appendChild(el("span", "ing-buy", `Vegyél: ${i.buyText}${i.bulk ? " (nagy kiszerelés)" : ""}`));
      if (i.deal) {
        p.appendChild(el("span", i.deal.type === "PROMO" ? "ing-deal promo" : "ing-deal",
          `${DEAL_LABEL[i.deal.type]}${i.deal.savingPercent > 0 ? ` · −${Math.round(i.deal.savingPercent)}%` : ""}`, { title: i.deal.reason }));
      }
      li.appendChild(p);
    } else if (i.estimated) {
      li.appendChild(el("span", "ing-product", `Az Árfigyelő nem követi, vagy ebben a boltban nincs – becsült ár (${i.priceText}).`));
    }
    ul.appendChild(li);
  }
  box.appendChild(ul);
  if (anyEstimated) box.appendChild(el("p", "note", "* becsült ár; ha akciót látsz rá az újságban, az „Akciók” gombbal felviheted."));
  return box;
}

function renderSteps(r) {
  const box = el("div", "steps");
  box.appendChild(el("h3", null, "Elkészítés"));
  const ol = el("ol");
  for (const step of r.steps) ol.appendChild(el("li", null, step));
  box.appendChild(ol);
  return box;
}

// ---------- bevásárlólista ----------

function listKey(s) {
  return `sporolos.list.${s.store}.${s.recipe.id}`;
}

function renderShoppingList(s, items, t) {
  const box = el("section", "shopping", null, { "aria-label": "Bevásárlólista" });
  const head = el("div", "shopping-head");
  head.appendChild(el("h3", null, `Bevásárlólista – ${storeName(s.store)}`));
  const copy = el("button", "ghost small", navigator.share ? "Megosztás" : "Lista másolása", { type: "button" });
  copy.addEventListener("click", () => shareList(s, items, t));
  head.appendChild(copy);
  box.appendChild(head);

  let checked = new Set();
  try { checked = new Set(JSON.parse(store(listKey(s)) || "[]")); } catch (e) { /* üres lista */ }

  const buyable = items.filter((i) => !i.pantry);
  const needed = buyable.filter((i) => !i.staple);
  const staples = buyable.filter((i) => i.staple);

  const mkList = (list) => {
    const ul = el("ul", "buy");
    for (const i of list) {
      const li = el("li");
      const id = `buy-${s.recipe.id}-${i.label}`.replace(/[^\w-]/g, "_");
      const cb = el("input", null, null, { type: "checkbox", id });
      cb.checked = checked.has(i.label);
      li.classList.toggle("done", cb.checked);
      cb.addEventListener("change", () => {
        if (cb.checked) checked.add(i.label); else checked.delete(i.label);
        li.classList.toggle("done", cb.checked);
        store(listKey(s), checked.size ? JSON.stringify([...checked]) : null);
      });
      const label = el("label", null, null, { for: id });
      label.append(el("span", "buy-name", i.item ? i.item.name : `${i.label} (becsült)`),
        el("span", "buy-qty", `${i.buyText}${i.item ? ` · ${i.priceText}` : ""}`));
      li.append(cb, label, el("span", "buy-price", ft(i.checkoutCost)));
      const orig = i.swappedFrom || i;
      const other = i.swappedFrom ? i.swappedFrom : i.alternative;
      if (other) li.appendChild(altRow(orig, i, other));
      ul.appendChild(li);
    }
    return ul;
  };

  box.appendChild(mkList(needed));
  if (staples.length) {
    box.appendChild(el("p", "buy-sub", "Ha nincs otthon:"));
    box.appendChild(mkList(staples));
  }
  const total = el("p", "buy-total");
  total.append(el("span", null, "A pénztárnál kb."), el("b", null, ft(t.checkout)));
  box.appendChild(total);
  if (t.staples > 0) box.appendChild(el("p", "note", `+ ${ft(t.staples)}, ha olajat, lisztet, cukrot is venni kell.`));
  box.appendChild(el("p", "note", "Egész csomagokkal számolva; a mérlegelős árut annyiért, amennyit veszel."));
  return box;
}

/** A másik kiszerelés ajánlata egy bevásárlólista-sor alatt, cseregombbal. */
function altRow(orig, current, other) {
  const row = el("div", "alt");
  const otherIsBulk = other.bulk;
  const costDiff = other.cost - current.cost;      // a receptre jutó rész változása
  const payDiff = other.checkoutCost - current.checkoutCost; // a pénztári összeg változása
  const what = otherIsBulk ? "Nagy kiszerelésben olcsóbb" : "Kisebb kiszerelésben";
  const txt = `${what}: ${other.item ? other.item.name : ""} (${other.buyText}, ${ft(other.checkoutCost)}). `
    + `A receptre ${costDiff < 0 ? "−" + ft(-costDiff) : "+" + ft(costDiff)}, `
    + `ma ${payDiff >= 0 ? "+" + ft(payDiff) : "−" + ft(-payDiff)}`
    + (otherIsBulk && other.buyAmount > 0 ? `, és marad a későbbi főzéshez.` : ".");
  row.appendChild(el("span", null, txt));
  const btn = el("button", "ghost small", "Ezt veszem", { type: "button" });
  btn.addEventListener("click", () => {
    if (state.swapped.has(orig.label)) state.swapped.delete(orig.label);
    else state.swapped.add(orig.label);
    renderPick(false);
  });
  row.appendChild(btn);
  return row;
}

function listAsText(s, items, t) {
  const lines = [`Bevásárlólista – ${s.recipe.name} (${s.servings} adag) – ${storeName(s.store)}`];
  const line = (i) => `☐ ${i.item ? i.item.name : i.label} – ${i.buyText} – ${ft(i.checkoutCost)}`;
  for (const i of items) if (!i.pantry && !i.staple) lines.push(line(i));
  const staples = items.filter((i) => i.staple);
  if (staples.length) {
    lines.push("Ha nincs otthon:");
    for (const i of staples) lines.push(line(i));
  }
  lines.push(`Összesen kb. ${ft(t.checkout)}`);
  return lines.join("\n");
}

async function shareList(s, items, t) {
  const text = listAsText(s, items, t);
  try {
    if (navigator.share) {
      await navigator.share({ title: `Bevásárlólista – ${s.recipe.name}`, text });
      return;
    }
    await navigator.clipboard.writeText(text);
    toast("A bevásárlólista a vágólapra került.");
  } catch (e) {
    if (e && e.name === "AbortError") return;
    toast("A lista másolása nem sikerült ebben a böngészőben.");
  }
}

// ---------- összehasonlítás a boltok között ----------

function renderComparison(s, t) {
  const box = el("section", "compare", null, { "aria-label": "A recept ára boltonként" });
  box.appendChild(el("h3", null, "Mennyibe kerülne a többi boltodban?"));
  const rows = s.stores.filter((x) => x.coverage >= 0.4)
    .map((x) => (x.store === s.store ? { ...x, totalCost: t.cost } : x))
    .sort((a, b) => a.totalCost - b.totalCost);
  if (rows.length <= 1) {
    box.appendChild(el("p", "note", "A beállításokban csak ez az egy bolt van kiválasztva."));
    return box;
  }
  const max = Math.max(...rows.map((x) => x.totalCost));
  const ul = el("ul", "bars");
  for (const x of rows) {
    const li = el("li");
    if (x.store === s.store) li.classList.add("current");
    const btn = el("button", "bar-row", null, { type: "button", "aria-label": `${x.name}: ${ft(x.totalCost)}` });
    const bar = el("span", "bar");
    bar.style.setProperty("--w", `${Math.max(6, (x.totalCost / max) * 100)}%`);
    bar.style.setProperty("--c", x.color);
    btn.append(el("span", "bar-name", x.name), bar, el("span", "bar-value", ft(x.totalCost)));
    btn.addEventListener("click", () => {
      if (x.store !== s.store) selectStore(x.store, s.recipe.id);
    });
    li.appendChild(btn);
    ul.appendChild(li);
  }
  box.appendChild(ul);
  const cheapest = rows[0];
  if (cheapest.store !== s.store) {
    box.appendChild(el("p", "note", `${cap(inStore(cheapest.store))} ${ft(t.cost - cheapest.totalCost)}-tal olcsóbb lenne. Koppints egy boltra az átváltáshoz.`));
  } else {
    box.appendChild(el("p", "note", `${cap(inStore(s.store))} a legolcsóbb ez a recept a boltjaid közül.`));
  }
  return box;
}

// ---------- további receptek ----------

function sortedSuggestions() {
  const list = state.suggestions.slice();
  if (state.sort === "cost") list.sort((a, b) => a.totalCost / a.servings - b.totalCost / b.servings);
  if (state.sort === "time") list.sort((a, b) => a.recipe.minutes - b.recipe.minutes);
  return list;
}

function renderMore() {
  const ol = $("#more");
  ol.replaceChildren();
  const currentId = state.current?.recipe.id;
  const list = sortedSuggestions().filter((s) => s.recipe.id !== currentId);
  const rest = state.showAll ? list : list.slice(0, 5);
  $("#more-title").textContent = state.showAll ? `Az összes recept (${state.suggestions.length})` : "Ebben a boltban még kifizetődő";
  $("#sort-wrap").hidden = !state.showAll;
  $("#show-all").hidden = state.showAll || list.length <= 5;
  $("#show-all").textContent = `Az összes recept (${state.suggestions.length})`;
  if (rest.length === 0) {
    ol.appendChild(el("li", "empty", "Nincs több ajánlat ezzel a szűrővel."));
    return;
  }
  for (const s of rest) {
    const li = el("li");
    const b = el("button", null, null, { type: "button" });
    const text = el("span");
    text.append(el("span", "m-name", s.recipe.name),
      el("span", "m-sub", `${s.recipe.minutes} perc · ${s.servings} adag · ${s.dealCount ? `${s.dealCount} hozzávaló jó áron` : "nincs benne akció"}`));
    const cost = el("span", "m-cost");
    cost.appendChild(el("span", "m-total", ft(s.totalCost)));
    if (s.savings >= 20) cost.appendChild(el("span", "m-save", `−${ft(s.savings)}`));
    b.append(el("img", "m-img", null, { src: s.recipe.image, alt: "", width: "64", height: "40", loading: "lazy" }), text, cost);
    b.addEventListener("click", async () => {
      await showRecipe(s.recipe.id).catch((e) => toast(e.message));
      const reduce = matchMedia("(prefers-reduced-motion: reduce)").matches;
      $("#pick").scrollIntoView({ behavior: reduce ? "auto" : "smooth", block: "start" });
      $("#pick").focus({ preventScroll: true });
    });
    li.appendChild(b);
    ol.appendChild(li);
  }
}

// ---------- jó árak és keresés ----------

function renderDeals(deals) {
  const ul = $("#deals");
  ul.replaceChildren();
  $("#search-hint").hidden = true;
  if (deals.length === 0) {
    ul.appendChild(el("li", "empty", "Ebben a boltban most nincs kiemelkedő ár ebben a nézetben."));
    return;
  }
  for (const d of deals) ul.appendChild(tagItem(d.item, d.type, d.referencePrice, d.reason, d.ingredient));
}

function tagItem(item, type, referencePrice, reason, ingredient) {
  const li = $("#tpl-tag").content.firstElementChild.cloneNode(true);
  if (type === "PROMO") li.classList.add("promo");
  const kind = type ? DEAL_LABEL[type] : "";
  li.querySelector(".tag-kind").textContent =
    [kind, ingredient ? `receptekhez: ${ingredient.toLowerCase()}` : ""].filter(Boolean).join(" · ") || item.category;
  const name = li.querySelector(".tag-name");
  name.textContent = item.name;
  name.addEventListener("click", () => openItem(item.id));
  const oldEl = li.querySelector(".tag-old");
  if (!type || referencePrice <= item.price) {
    oldEl.remove();
    if (!type) li.classList.add("plain");
  } else if (type === "CATEGORY") {
    oldEl.replaceWith(el("span", "tag-avg", `átlag ${ft(referencePrice)}`));
  } else {
    oldEl.textContent = ft(referencePrice);
  }
  li.querySelector(".tag-price").textContent = ft(item.price);
  const unitText = item.unit === "kg" && item.quantity === 1 ? "Ft/kg-os ár" : `${qty(item.quantity, item.unit)} · ${ft(item.unitPrice)}/${item.unit}`;
  li.querySelector(".tag-reason").textContent = reason ? `${reason}` : unitText;
  return li;
}

async function runSearch(q) {
  $("#more-deals").hidden = true;
  const hits = await api(`/api/search?q=${encodeURIComponent(q)}&store=${state.store}&limit=25`);
  const ul = $("#deals");
  ul.replaceChildren();
  const hint = $("#search-hint");
  hint.hidden = false;
  hint.textContent = hits.length
    ? `${hits.length} találat ${inStore(state.store)}, egységár szerint (legolcsóbb elöl)`
    : `Nincs találat ${inStore(state.store)} erre: „${q}”.`;
  for (const h of hits) {
    ul.appendChild(tagItem(h, h.deal?.type, h.deal?.referencePrice, h.deal ? h.deal.reason : null, null));
  }
}

// ---------- termék ára minden láncban ----------

async function openItem(id) {
  let data;
  try {
    data = await api(`/api/item?id=${encodeURIComponent(id)}`);
  } catch (e) {
    toast(e.message);
    return;
  }
  const dlg = $("#item-dialog");
  $("#item-title").textContent = data.name;
  $("#item-cat").textContent = data.offers.length > 1
    ? `${data.category} · ${data.offers.length} láncban kapható`
    : `${data.category} · csak egy láncban kapható`;
  const ul = $("#item-offers");
  ul.replaceChildren();
  const min = Math.min(...data.offers.map((o) => o.price));
  for (const o of data.offers) {
    const li = el("li");
    if (o.store === state.store) li.classList.add("current");
    if (!o.mine) li.classList.add("not-mine");
    const left = el("div");
    left.appendChild(el("b", null, o.storeName + (o.mine ? "" : " (nem a boltod)")));
    const shops = o.totalShops ? `${o.shops} / ${o.totalShops} boltjukban` : (o.shops ? `${o.shops} boltjukban` : "saját akció");
    left.appendChild(el("span", "offer-meta", o.maxPrice > o.price ? `${shops} · máshol akár ${ft(o.maxPrice)}` : shops));
    if (o.deal) left.appendChild(el("span", "ing-deal", `${DEAL_LABEL[o.deal.type]}${o.deal.savingPercent > 0 ? ` · −${Math.round(o.deal.savingPercent)}%` : ""}`));
    const right = el("div", "offer-price");
    right.appendChild(el("b", null, ft(o.price)));
    if (o.price > min) right.appendChild(el("span", "offer-diff", `+${ft(o.price - min)}`));
    right.appendChild(el("span", "offer-meta", `${qty(o.quantity, o.unit)} · ${ft(o.unitPrice)}/${o.unit}`));
    li.append(left, right);
    ul.appendChild(li);
  }
  openDialog(dlg);
}

function openDialog(dlg) {
  if (typeof dlg.showModal === "function") { if (!dlg.open) dlg.showModal(); }
  else dlg.setAttribute("open", "");
}

// ---------- beállítások ----------

function openSettings() {
  const s = state.status;
  const chips = $("#set-stores");
  chips.replaceChildren();
  const mine = new Set(myStores().map((x) => x.id));
  for (const st of s.stores) {
    const lab = el("label", "chip");
    const cb = el("input", null, null, { type: "checkbox", value: st.id });
    cb.checked = mine.has(st.id);
    cb.addEventListener("change", () => {
      const sel = $$("#set-stores input:checked").map((x) => x.value);
      if (sel.length === 0) {
        cb.checked = true;
        toast("Legalább egy boltnak maradnia kell.");
        return;
      }
      state.prefs.stores = sel.length === s.stores.length ? [] : sel;
      savePrefs();
      reloadAll().catch((e) => toast(e.message));
    });
    lab.style.setProperty("--c", st.color);
    lab.append(cb, el("span", null, st.name));
    chips.appendChild(lab);
  }

  for (const r of $$('#settings-dialog input[name="pack"]')) r.checked = (r.value === "bulk") === state.prefs.bulk;

  const cards = $("#set-cards");
  cards.replaceChildren();
  for (const st of s.stores) {
    if (!st.cardName) continue;
    const lab = el("label", "chip");
    const cb = el("input", null, null, { type: "checkbox", value: st.id });
    cb.checked = state.prefs.cards.includes(st.id);
    cb.addEventListener("change", () => {
      state.prefs.cards = $$("#set-cards input:checked").map((x) => x.value);
      savePrefs();
      reloadAll().catch((e) => toast(e.message));
    });
    lab.style.setProperty("--c", st.color);
    lab.append(cb, el("span", null, st.cardName));
    cards.appendChild(lab);
  }
  openDialog($("#settings-dialog"));
}

// ---------- akciók ----------

async function openPromos() {
  if (!state.ingredients) state.ingredients = await api("/api/ingredients");
  const form = $("#promo-form");
  const storeSel = form.elements.store;
  storeSel.replaceChildren(...state.status.stores.map((s) => {
    const o = el("option", null, s.name, { value: s.id });
    if (s.id === state.store) o.selected = true;
    return o;
  }));
  const ingSel = form.elements.ingredient;
  if (!ingSel.options.length) {
    ingSel.append(el("option", null, "Válassz…", { value: "" }),
      ...state.ingredients.map((i) => el("option", null, i.name + (i.tracked ? "" : " (az Árfigyelő nem követi)"), { value: i.key })));
  }
  await renderPromos();
  openDialog($("#promo-dialog"));
}

async function renderPromos(data) {
  const d = data || await api("/api/promos");
  const ul = $("#promo-list");
  ul.replaceChildren();
  if (!d.promos.length) {
    ul.appendChild(el("li", "empty small", "Még nincs felvitt akció. Az akciós újságból vagy a lánc appjából ide írhatod be őket."));
  }
  for (const p of d.promos) {
    const li = el("li", `promo-item status-${p.status.toLowerCase()}`);
    const left = el("div");
    left.appendChild(el("b", null, `${p.storeName}: ${p.name}`));
    const amount = p.unit === "kg" && p.quantity === 1 ? "Ft/kg" : qty(p.quantity, p.unit);
    const dates = p.validFrom || p.validTo ? ` · ${p.validFrom || "…"} – ${p.validTo || "…"}` : "";
    left.appendChild(el("span", "offer-meta",
      `${ft(p.price)} (${amount})${p.regularPrice ? `, rendesen ${ft(p.regularPrice)}` : ""}${dates}${p.card ? " · kártyás" : ""}${p.ingredientName ? ` · ${p.ingredientName}` : ""}`));
    left.appendChild(el("span", "promo-status", p.statusText + (p.matchedName ? `: ${p.matchedName}` : "")));
    li.appendChild(left);
    if (p.deletable) {
      const del = el("button", "ghost small", "Törlés", { type: "button" });
      del.addEventListener("click", async () => {
        try {
          const r = await api(`/api/promos?id=${encodeURIComponent(p.id)}`, { method: "DELETE" });
          await renderPromos(r);
          await reloadAll();
        } catch (e) { toast(e.message); }
      });
      li.appendChild(del);
    } else {
      li.appendChild(el("span", "offer-meta", p.source));
    }
    ul.appendChild(li);
  }
  const w = $("#promo-warnings");
  w.hidden = !d.warnings.length;
  w.textContent = d.warnings.join(" · ");
}

async function submitPromo(e) {
  e.preventDefault();
  const f = e.target;
  const body = {
    store: f.elements.store.value,
    ingredient: f.elements.ingredient.value,
    name: f.elements.name.value.trim(),
    price: Number(f.elements.price.value),
    regularPrice: Number(f.elements.regularPrice.value) || 0,
    quantity: Number(f.elements.quantity.value),
    unit: f.elements.unit.value,
    validFrom: f.elements.validFrom.value || null,
    validTo: f.elements.validTo.value || null,
    card: f.elements.card.checked,
  };
  try {
    const r = await api("/api/promos", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });
    await renderPromos(r);
    const added = r.promos.find((p) => p.name === body.name && p.store === body.store);
    toast(added ? `Akció felvéve – ${added.statusText}.` : "Akció felvéve.");
    f.elements.name.value = "";
    f.elements.price.value = "";
    f.elements.regularPrice.value = "";
    await reloadAll();
  } catch (err) {
    toast(err.message);
  }
}

async function importPromos() {
  const text = $("#promo-import").value.trim();
  if (!text) { toast("Előbb illeszd be az akciókat."); return; }
  try {
    const r = await api("/api/promos", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ text }) });
    await renderPromos(r);
    toast(`${r.added} akció felvéve${r.skipped.length ? `, ${r.skipped.length} sor kimaradt` : ""}.`);
    if (r.skipped.length) {
      const w = $("#promo-warnings");
      w.hidden = false;
      w.textContent = r.skipped.join(" · ");
    }
    if (r.added) $("#promo-import").value = "";
    await reloadAll();
  } catch (err) {
    toast(err.message);
  }
}

// ---------- események ----------

function bind() {
  $("#another").addEventListener("click", () => nextRecipe(false).catch((e) => toast(e.message)));

  $("#filters").addEventListener("click", (e) => {
    const b = e.target.closest("button[data-tag]");
    if (!b) return;
    state.tag = b.dataset.tag;
    syncFilterButtons();
    loadStore().catch((err) => showError(err.message));
  });

  $("#onlyIngredients").addEventListener("change", () => loadDeals().catch((err) => toast(err.message)));

  let searchTimer;
  $("#search").addEventListener("input", () => {
    clearTimeout(searchTimer);
    searchTimer = setTimeout(() => loadDeals().catch((err) => toast(err.message)), 250);
  });

  $("#more-deals").addEventListener("click", () => {
    state.dealLimit += 24;
    loadDeals().catch((err) => toast(err.message));
  });

  $("#show-all").addEventListener("click", () => {
    state.showAll = true;
    renderMore();
  });

  $("#sort").addEventListener("change", (e) => {
    state.sort = e.target.value;
    renderMore();
  });

  for (const dlg of $$("dialog")) {
    // a háttérre kattintás bezárja
    dlg.addEventListener("click", (e) => { if (e.target === e.currentTarget) e.currentTarget.close(); });
  }

  $("#open-settings").addEventListener("click", openSettings);
  $("#open-promos").addEventListener("click", () => openPromos().catch((e) => toast(e.message)));
  $("#promo-form").addEventListener("submit", submitPromo);
  $("#promo-import-btn").addEventListener("click", importPromos);

  for (const r of $$('#settings-dialog input[name="pack"]')) {
    r.addEventListener("change", () => {
      state.prefs.bulk = r.value === "bulk" && r.checked;
      savePrefs();
      state.swapped = new Set();
      reloadAll().catch((e) => toast(e.message));
    });
  }

  $("#refresh").addEventListener("click", async () => {
    const btn = $("#refresh");
    btn.disabled = true;
    btn.textContent = "Frissítés…";
    try {
      await api("/api/refresh", { method: "POST" });
      await reloadAll();
      btn.textContent = "Árak frissítve";
    } catch (err) {
      btn.textContent = "Frissítés sikertelen";
      toast(err.message);
    } finally {
      setTimeout(() => { btn.disabled = false; btn.textContent = "Árak frissítése"; }, 2500);
    }
  });

  window.addEventListener("hashchange", () => {
    const h = readHash();
    if (h.store && h.store !== state.store) selectStore(h.store, h.recipe);
    else if (h.recipe && h.recipe !== state.current?.recipe.id) showRecipe(h.recipe).catch((e) => toast(e.message));
  });
}

function syncFilterButtons() {
  for (const x of $$("#filters button")) x.setAttribute("aria-pressed", String(x.dataset.tag === state.tag));
}

async function init() {
  loadPrefs();
  bind();
  const h = readHash();
  if (h.store) state.store = h.store;
  if (h.tag) { state.tag = h.tag; syncFilterButtons(); }
  if (h.servings) state.servings = h.servings;
  if (h.view) state.view = h.view;
  try {
    await loadStatus();
    await loadStore(h.recipe);
    if (typeof initViews === "function") initViews();
  } catch (e) {
    $("#source").textContent = "Az adatok nem tölthetők be.";
    showError(e.message);
  }
}

init();
