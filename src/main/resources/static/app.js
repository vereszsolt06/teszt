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
  servings: 0,       // 0 = a recept alapadagja
  showAll: false,
  sort: "score",
  requestId: 0,      // elavult válaszok eldobásához
  dealLimit: 12,     // ennyi jó árat mutatunk elsőre (telefonon ne legyen végtelen a lista)
};

const DEAL_LABEL = {
  CHAIN: "Itt a legolcsóbb",
  HISTORY: "Olcsóbb a szokásosnál",
  SPREAD: "Akciógyanús",
  CATEGORY: "Tartósan olcsó",
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

// ---------- tárolás és állapot az URL-ben ----------

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

function readHash() {
  const p = new URLSearchParams(location.hash.slice(1));
  return { store: p.get("bolt"), recipe: p.get("recept"), servings: Number(p.get("adag")) || 0, tag: p.get("cimke") || "" };
}

function writeHash() {
  const p = new URLSearchParams();
  if (state.store) p.set("bolt", state.store);
  if (state.current) p.set("recept", state.current.recipe.id);
  if (state.servings) p.set("adag", String(state.servings));
  if (state.tag) p.set("cimke", state.tag);
  const h = "#" + p.toString();
  if (location.hash !== h) history.replaceState(null, "", h);
}

// ---------- hálózat ----------

async function api(path, opts) {
  const res = await fetch(path, opts);
  const body = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(body.error || `A szerver ${res.status} hibával válaszolt.`);
  return body;
}

function toast(msg) {
  const t = $("#toast");
  t.textContent = msg;
  t.hidden = false;
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => { t.hidden = true; }, 2600);
}

function showError(msg) {
  const pick = $("#pick");
  pick.replaceChildren();
  const p = document.createElement("p");
  p.className = "empty";
  p.textContent = msg + " Indítsd újra az alkalmazást, vagy próbáld az „Árak frissítése” gombot.";
  pick.appendChild(p);
}

// ---------- betöltés ----------

async function loadStatus() {
  state.status = await api("/api/status");
  const s = state.status;
  const d = new Date(s.date + "T00:00:00");
  const dateText = d.toLocaleDateString("hu-HU", { month: "long", day: "numeric" });
  let text = `${s.source} · ${dateText} · ${nf.format(s.itemCount)} élelmiszerár`;
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

  const ids = s.stores.map((x) => x.id);
  if (!state.store || !ids.includes(state.store)) {
    const saved = store("sporolos.store");
    state.store = ids.includes(saved) ? saved : bestStore()?.id;
  }
  renderStores();
}

function bestStore() {
  const list = state.status?.stores || [];
  return list.reduce((a, b) => (!a || b.topScore > a.topScore ? b : a), null);
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
    await showRecipe(preferredRecipe);
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
  state.current = detail;
  state.shown.add(id);
  renderPick(!keepServings);
  renderMore();
  writeHash();
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
  for (const s of state.status.stores) {
    const b = document.createElement("button");
    b.type = "button";
    b.className = "store";
    b.setAttribute("role", "tab");
    b.setAttribute("aria-selected", String(s.id === state.store));
    b.style.setProperty("--c", s.color);
    const name = document.createElement("span");
    name.className = "store-name";
    name.textContent = s.name;
    const meta = document.createElement("span");
    meta.className = "store-meta";
    meta.textContent = `${s.dealCount} jó ár`;
    b.append(name, meta);
    if (best && s.id === best.id) {
      const badge = document.createElement("span");
      badge.className = "best";
      badge.textContent = "ma itt a legjobb";
      b.appendChild(badge);
    }
    b.addEventListener("click", () => selectStore(s.id));
    row.appendChild(b);
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
}

// ---------- a kiválasztott recept ----------

function renderPick(animate) {
  const pick = $("#pick");
  const s = state.current;
  pick.classList.remove("fresh");
  pick.replaceChildren();
  $("#counter").textContent = "";

  if (!s) {
    const p = document.createElement("p");
    p.className = "empty";
    p.textContent = state.tag
      ? "Ehhez a szűrőhöz ebben a boltban most nincs elég árazható recept. Válassz másik szűrőt vagy boltot."
      : "Ebben a boltban most nincs elég árazható alapanyag. Válassz másik boltot.";
    pick.appendChild(p);
    return;
  }

  const r = s.recipe;
  const deals = s.ingredients.filter((i) => i.deal);

  const head = el("div", "pick-head");
  const left = el("div");
  left.append(
    el("span", "pick-emoji", r.emoji, { "aria-hidden": "true" }),
    el("h2", "pick-name", r.name, { id: "pick-title" }),
    el("p", "pick-meta", `${r.minutes} perc · ${r.difficulty}`),
    el("p", "pick-why", whyText(s, deals)),
  );

  const right = el("div", "pick-side");
  const tag = el("div", "price-tag", null, { role: "img", "aria-label": `A recept költsége kb. ${ft(s.totalCost)}` });
  tag.append(el("small", null, `${s.servings} adag, kb.`), el("span", "now", ft(s.totalCost)));
  if (s.savings >= 20) {
    const was = el("small", "was", "máshol ");
    was.appendChild(el("span", "old", ft(s.otherStoresCost)));
    tag.appendChild(was);
  }
  right.append(tag, servingsControl(s));
  head.append(left, right);
  pick.appendChild(head);

  const body = el("div", "pick-body");
  body.append(renderIngredients(s), renderSteps(r));
  pick.appendChild(body);
  pick.appendChild(renderShoppingList(s));
  pick.appendChild(renderComparison(s));

  if (animate) {
    void pick.offsetWidth;
    pick.classList.add("fresh");
  }

  const left8 = state.suggestions.slice(0, 8).filter((x) => !state.shown.has(x.recipe.id)).length;
  $("#counter").textContent = left8 > 0 ? `még ${left8} jó ötlet erre a boltra` : "körbeértünk, újrakezdjük";
}

function el(tag, cls, text, attrs) {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (text != null) e.textContent = text;
  if (attrs) for (const [k, v] of Object.entries(attrs)) e.setAttribute(k, v);
  return e;
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
  const fmt = (i) => `${i.label.toLowerCase()} (−${Math.round(i.deal.savingPercent)}%)`;
  const hot = deals.filter((i) => i.deal.type !== "CATEGORY");
  const steady = deals.filter((i) => i.deal.type === "CATEGORY");
  const parts = [];
  if (hot.length) parts.push(`${cap(inStore(s.store))} épp most jó áron van: ${hot.slice(0, 3).map(fmt).join(", ")}.`);
  if (steady.length) parts.push(`${hot.length ? "Emellett tartósan" : `${cap(inStore(s.store))} tartósan`} olcsó: ${steady.slice(0, 3).map(fmt).join(", ")}.`);
  if (parts.length === 0) parts.push(`Nincs benne kiemelt akció, de ${inStore(s.store)} ez most az egyik legolcsóbban összevásárolható recept.`);
  if (s.savings >= 20) parts.push(`A többi boltban átlagosan ${ft(s.savings)}-tal többe kerülne.`);
  return parts.join(" ");
}

const cap = (t) => t.charAt(0).toUpperCase() + t.slice(1);

function renderIngredients(s) {
  const box = el("div", "ingredients");
  box.appendChild(el("h3", null, "Hozzávalók és mit vegyél"));
  const ul = el("ul", "ing");
  let anyEstimated = false;
  for (const i of s.ingredients) {
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
      p.append(link, document.createTextNode(` – ${ft(i.item.price)}`));
      if (i.item.availability < 0.5) {
        p.appendChild(el("span", "avail", ` · a boltjaik ${Math.max(1, Math.round(i.item.availability * 100))}%-ában`));
      }
      if (i.deal) {
        p.appendChild(document.createElement("br"));
        p.appendChild(el("span", "ing-deal", `${DEAL_LABEL[i.deal.type]} · −${Math.round(i.deal.savingPercent)}%`, { title: i.deal.reason }));
      }
      li.appendChild(p);
    } else if (i.estimated) {
      li.appendChild(el("span", "ing-product", "Az Árfigyelő nem követi, vagy ebben a boltban nincs – becsült ár."));
    }
    ul.appendChild(li);
  }
  box.appendChild(ul);
  if (anyEstimated) box.appendChild(el("p", "note", "* becsült ár"));
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

function buyText(i) {
  if (!i.item) return i.packages > 1 ? `${i.packages} csomag` : "1 csomag";
  if (i.packages === 0) return `kb. ${qty(i.buyAmount, i.item.unit)}`;
  return i.packages > 1 ? `${i.packages} × ${qty(i.item.quantity, i.item.unit)}` : qty(i.item.quantity, i.item.unit);
}

function renderShoppingList(s) {
  const box = el("section", "shopping", null, { "aria-label": "Bevásárlólista" });
  const head = el("div", "shopping-head");
  head.appendChild(el("h3", null, `Bevásárlólista – ${storeName(s.store)}`));
  const actions = el("div", "shopping-actions");
  const copy = el("button", "ghost small", navigator.share ? "Megosztás" : "Lista másolása", { type: "button" });
  copy.addEventListener("click", () => shareList(s));
  actions.appendChild(copy);
  head.appendChild(actions);
  box.appendChild(head);

  let checked = new Set();
  try { checked = new Set(JSON.parse(store(listKey(s)) || "[]")); } catch (e) { /* üres lista */ }

  const items = s.ingredients.filter((i) => !i.pantry);
  const needed = items.filter((i) => !i.staple);
  const staples = items.filter((i) => i.staple);

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
      label.append(el("span", "buy-name", i.item ? i.item.name : `${i.label} (becsült)`), el("span", "buy-qty", buyText(i)));
      li.append(cb, label, el("span", "buy-price", ft(i.checkoutCost)));
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
  total.append(el("span", null, "A pénztárnál kb."), el("b", null, ft(s.checkoutTotal)));
  box.appendChild(total);
  if (s.staplesTotal > 0) box.appendChild(el("p", "note", `+ ${ft(s.staplesTotal)}, ha olajat, lisztet, cukrot is venni kell.`));
  box.appendChild(el("p", "note", "Egész csomagokkal számolva; a megmaradó rész a következő főzéshez jól jön."));
  return box;
}

function listAsText(s) {
  const lines = [`Bevásárlólista – ${s.recipe.name} (${s.servings} adag) – ${storeName(s.store)}`];
  for (const i of s.ingredients) {
    if (i.pantry || i.staple) continue;
    lines.push(`☐ ${i.item ? i.item.name : i.label} – ${buyText(i)} – ${ft(i.checkoutCost)}`);
  }
  const staples = s.ingredients.filter((i) => i.staple);
  if (staples.length) {
    lines.push("Ha nincs otthon:");
    for (const i of staples) lines.push(`☐ ${i.item ? i.item.name : i.label} – ${buyText(i)} – ${ft(i.checkoutCost)}`);
  }
  lines.push(`Összesen kb. ${ft(s.checkoutTotal)}`);
  return lines.join("\n");
}

async function shareList(s) {
  const text = listAsText(s);
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

function renderComparison(s) {
  const box = el("section", "compare", null, { "aria-label": "A recept ára boltonként" });
  box.appendChild(el("h3", null, "Mennyibe kerülne máshol?"));
  const rows = s.stores.filter((x) => x.coverage >= 0.4).sort((a, b) => a.totalCost - b.totalCost);
  if (rows.length === 0) return box;
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
    box.appendChild(el("p", "note", `${cap(inStore(cheapest.store))} ${ft(s.totalCost - cheapest.totalCost)}-tal olcsóbb lenne. Koppints egy boltra az átváltáshoz.`));
  } else {
    box.appendChild(el("p", "note", `${cap(inStore(s.store))} a legolcsóbb ez a recept.`));
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
    b.append(el("span", "m-emoji", s.recipe.emoji, { "aria-hidden": "true" }), text, cost);
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
  const kind = type ? DEAL_LABEL[type] : "";
  li.querySelector(".tag-kind").textContent =
    [kind, ingredient ? `receptekhez: ${ingredient.toLowerCase()}` : ""].filter(Boolean).join(" · ") || item.category;
  const name = li.querySelector(".tag-name");
  name.textContent = item.name;
  name.addEventListener("click", () => openItem(item.id));
  const oldEl = li.querySelector(".tag-old");
  if (!type) {
    oldEl.remove();
    li.classList.add("plain");
  } else if (type === "CATEGORY") {
    oldEl.replaceWith(el("span", "tag-avg", `átlag ${ft(referencePrice)}`));
  } else {
    oldEl.textContent = ft(referencePrice);
  }
  li.querySelector(".tag-price").textContent = ft(item.price);
  li.querySelector(".tag-reason").textContent = reason || `${ft(item.unitPrice)}/${item.unit} · ${qty(item.quantity, item.unit)}`;
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
    const left = el("div");
    left.appendChild(el("b", null, o.storeName));
    const shops = o.totalShops ? `${o.shops} / ${o.totalShops} boltjukban` : `${o.shops} boltjukban`;
    left.appendChild(el("span", "offer-meta",
      o.maxPrice > o.price ? `${shops} · máshol akár ${ft(o.maxPrice)}` : shops));
    if (o.deal) left.appendChild(el("span", "ing-deal", `${DEAL_LABEL[o.deal.type]} · −${Math.round(o.deal.savingPercent)}%`));
    const right = el("div", "offer-price");
    right.appendChild(el("b", null, ft(o.price)));
    if (o.price > min) right.appendChild(el("span", "offer-diff", `+${ft(o.price - min)}`));
    right.appendChild(el("span", "offer-meta", `${ft(o.unitPrice)}/${o.unit}`));
    li.append(left, right);
    ul.appendChild(li);
  }
  if (typeof dlg.showModal === "function") dlg.showModal();
  else dlg.setAttribute("open", "");
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

  $("#onlyIngredients").addEventListener("change", () => {
    loadDeals().catch((err) => toast(err.message));
  });

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

  $("#item-dialog").addEventListener("click", (e) => {
    // a háttérre kattintás bezárja
    if (e.target === e.currentTarget) e.currentTarget.close();
  });

  $("#refresh").addEventListener("click", async () => {
    const btn = $("#refresh");
    btn.disabled = true;
    btn.textContent = "Frissítés…";
    try {
      await api("/api/refresh", { method: "POST" });
      await loadStatus();
      await loadStore(state.current?.recipe.id);
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
  bind();
  const h = readHash();
  if (h.store) state.store = h.store;
  if (h.tag) { state.tag = h.tag; syncFilterButtons(); }
  try {
    await loadStatus();
    await loadStore(h.recipe);
    if (h.servings && state.current && h.servings !== state.current.servings) {
      state.servings = h.servings;
      await showRecipe(state.current.recipe.id, true);
    }
  } catch (e) {
    $("#source").textContent = "Az adatok nem tölthetők be.";
    showError(e.message);
  }
}

init();
