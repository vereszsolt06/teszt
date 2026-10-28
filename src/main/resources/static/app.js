// Spórolós Konyha – kliensoldal. Nincs keretrendszer: fetch + DOM.
"use strict";

const $ = (sel) => document.querySelector(sel);

const state = {
  status: null,
  store: null,
  tag: "",
  suggestions: [],
  shown: new Set(),
  current: null,
};

const DEAL_LABEL = {
  CHAIN: "Itt a legolcsóbb",
  HISTORY: "Olcsóbb a szokásosnál",
  SPREAD: "Akciógyanús",
  CATEGORY: "Tartósan olcsó",
};

const ft = (v) => Math.round(v).toLocaleString("hu-HU") + " Ft";

function store(key, value) {
  try {
    if (value === undefined) return localStorage.getItem(key);
    localStorage.setItem(key, value);
  } catch (e) {
    return null;
  }
}

async function api(path, opts) {
  const res = await fetch(path, opts);
  const body = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(body.error || `A szerver ${res.status} hibával válaszolt.`);
  return body;
}

function showError(msg) {
  const pick = $("#pick");
  pick.innerHTML = "";
  const p = document.createElement("p");
  p.className = "empty";
  p.textContent = msg + " Indítsd újra az alkalmazást, vagy próbáld az „Árak frissítése” gombot.";
  pick.appendChild(p);
}

// ---------- adatok ----------

async function loadStatus() {
  state.status = await api("/api/status");
  const s = state.status;
  const d = new Date(s.date + "T00:00:00");
  const dateText = d.toLocaleDateString("hu-HU", { month: "long", day: "numeric" });
  let text = `${s.source} · ${dateText} · ${s.itemCount.toLocaleString("hu-HU")} élelmiszerár`;
  if (s.historyDays > 0) text += ` · ${s.historyDays} nap előzmény`;
  $("#source").textContent = text;

  const saved = store("sporolos.store");
  const ids = s.stores.map((x) => x.id);
  if (!state.store || !ids.includes(state.store)) {
    state.store = ids.includes(saved) ? saved : bestStore()?.id;
  }
  renderStores();
}

function bestStore() {
  const list = state.status?.stores || [];
  return list.reduce((a, b) => (!a || b.topScore > a.topScore ? b : a), null);
}

async function loadStore() {
  if (!state.store) return;
  const tag = encodeURIComponent(state.tag);
  const [sugs] = await Promise.all([
    api(`/api/suggestions?store=${state.store}&tag=${tag}&limit=40`),
    loadDeals(),
  ]);
  state.suggestions = sugs;
  state.shown = new Set();
  nextRecipe(true);
}

async function loadDeals() {
  const only = $("#onlyIngredients").checked;
  const deals = await api(`/api/deals?store=${state.store}&limit=25&ingredients=${only}`);
  renderDeals(deals);
}

// ---------- receptválasztás ----------

/** Súlyozott véletlen a legjobb 8 közül, ismétlés nélkül – így "dobál" recepteket, de mindig jókat. */
function nextRecipe(first) {
  const pool = state.suggestions.slice(0, 8);
  if (pool.length === 0) {
    state.current = null;
    renderPick();
    renderMore();
    return;
  }
  let candidates = pool.filter((s) => !state.shown.has(s.recipe.id));
  if (candidates.length === 0) {
    state.shown = new Set(state.current ? [state.current.recipe.id] : []);
    candidates = pool.filter((s) => !state.shown.has(s.recipe.id));
    if (candidates.length === 0) candidates = pool;
  }
  let chosen;
  if (first) {
    chosen = candidates[0]; // elsőre a legjobb
  } else {
    const weights = candidates.map((s) => Math.pow(s.score + 0.05, 2));
    let r = Math.random() * weights.reduce((a, b) => a + b, 0);
    chosen = candidates[candidates.length - 1];
    for (let i = 0; i < candidates.length; i++) {
      r -= weights[i];
      if (r <= 0) { chosen = candidates[i]; break; }
    }
  }
  state.current = chosen;
  state.shown.add(chosen.recipe.id);
  renderPick();
  renderMore();
}

// ---------- megjelenítés ----------

function renderStores() {
  const row = $("#stores");
  row.innerHTML = "";
  const best = bestStore();
  for (const s of state.status.stores) {
    const b = document.createElement("button");
    b.type = "button";
    b.className = "store";
    b.setAttribute("role", "tab");
    b.setAttribute("aria-selected", String(s.id === state.store));
    b.style.setProperty("--c", s.color);
    b.innerHTML = `<span class="store-name"></span><span class="store-meta"></span>`;
    b.querySelector(".store-name").textContent = s.name;
    b.querySelector(".store-meta").textContent = `${s.dealCount} jó ár`;
    if (best && s.id === best.id) {
      const badge = document.createElement("span");
      badge.className = "best";
      badge.textContent = "ma itt a legjobb";
      b.appendChild(badge);
    }
    b.addEventListener("click", () => selectStore(s.id));
    row.appendChild(b);
  }
}

function selectStore(id) {
  if (id === state.store) return;
  state.store = id;
  store("sporolos.store", id);
  renderStores();
  loadStore().catch((e) => showError(e.message));
}

function storeName(id) {
  return state.status.stores.find((s) => s.id === id)?.name || id;
}

function renderPick() {
  const pick = $("#pick");
  const s = state.current;
  pick.classList.remove("fresh");
  pick.innerHTML = "";
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
  const head = document.createElement("div");
  head.className = "pick-head";
  head.innerHTML = `
    <div>
      <span class="pick-emoji" aria-hidden="true"></span>
      <h2 class="pick-name" id="pick-title"></h2>
      <p class="pick-meta"></p>
      <p class="pick-why"></p>
    </div>
    <div class="price-tag" role="img">
      <small>${r.servings} adag, kb.</small>
      <span class="now"></span>
      <small class="was"></small>
    </div>`;
  head.querySelector(".pick-emoji").textContent = r.emoji;
  head.querySelector(".pick-name").textContent = r.name;
  head.querySelector(".pick-meta").textContent =
    `${r.minutes} perc · ${r.difficulty} · ${r.servings} adag`;
  head.querySelector(".now").textContent = ft(s.totalCost);
  const tag = head.querySelector(".price-tag");
  tag.setAttribute("aria-label", `A recept költsége kb. ${ft(s.totalCost)}`);
  if (s.savings >= 20) {
    const was = head.querySelector(".was");
    was.innerHTML = `máshol <span class="old"></span>`;
    was.querySelector(".old").textContent = ft(s.otherStoresCost);
  }
  head.querySelector(".pick-why").textContent = whyText(s, deals);
  pick.appendChild(head);

  const body = document.createElement("div");
  body.className = "pick-body";
  body.appendChild(renderIngredients(s));
  body.appendChild(renderSteps(r));
  pick.appendChild(body);

  // újrarajzolás után indul az egyetlen animáció
  void pick.offsetWidth;
  pick.classList.add("fresh");

  const left = state.suggestions.slice(0, 8).filter((x) => !state.shown.has(x.recipe.id)).length;
  $("#counter").textContent = left > 0 ? `még ${left} jó ötlet erre a boltra` : "körbeértünk, újrakezdjük";
}

function whyText(s, deals) {
  const where = storeName(s.store);
  const fmt = (i) => `${i.label.toLowerCase()} (−${Math.round(i.deal.savingPercent)}%)`;
  const hot = deals.filter((i) => i.deal.type !== "CATEGORY");
  const steady = deals.filter((i) => i.deal.type === "CATEGORY");
  const parts = [];
  if (hot.length) parts.push(`${where}: épp most jó áron ${hot.slice(0, 3).map(fmt).join(", ")}.`);
  if (steady.length) parts.push(`${hot.length ? "Emellett tartósan" : "Tartósan"} olcsó itt ${steady.slice(0, 3).map(fmt).join(", ")}.`);
  if (parts.length === 0) parts.push(`Nincs benne kiemelt akció, de ${where} kínálatából ez most az egyik legolcsóbban összevásárolható recept.`);
  if (s.savings >= 20) parts.push(`A többi boltban átlagosan ${ft(s.savings)}-tal többe kerülne.`);
  return parts.join(" ");
}

function renderIngredients(s) {
  const box = document.createElement("div");
  box.className = "ingredients";
  box.innerHTML = `<h3>Hozzávalók és mit vegyél</h3><ul class="ing"></ul>`;
  const ul = box.querySelector("ul");
  let anyEstimated = false;
  for (const i of s.ingredients) {
    const li = document.createElement("li");
    if (i.pantry) li.className = "pantry";
    if (i.estimated) { li.className = "estimated"; anyEstimated = true; }
    li.innerHTML = `<span><span class="ing-name"></span> <span class="ing-amount"></span></span><span class="ing-cost"></span>`;
    li.querySelector(".ing-name").textContent = i.label;
    li.querySelector(".ing-amount").textContent = i.amount ? `– ${i.amount}` : "";
    li.querySelector(".ing-cost").textContent = i.pantry ? "otthon van" : ft(i.cost);
    if (i.item) {
      const p = document.createElement("span");
      p.className = "ing-product";
      p.textContent = `${i.item.name} – ${ft(i.item.price)}`;
      if (i.deal) {
        const d = document.createElement("span");
        d.className = "ing-deal";
        d.title = i.deal.reason;
        d.textContent = `${DEAL_LABEL[i.deal.type]} · −${Math.round(i.deal.savingPercent)}%`;
        p.appendChild(document.createElement("br"));
        p.appendChild(d);
      }
      li.appendChild(p);
    } else if (i.estimated) {
      const p = document.createElement("span");
      p.className = "ing-product";
      p.textContent = "Az Árfigyelő nem követi, vagy ebben a boltban nincs – becsült ár.";
      li.appendChild(p);
    }
    ul.appendChild(li);
  }
  if (anyEstimated) {
    const n = document.createElement("p");
    n.className = "note";
    n.textContent = "* becsült ár";
    box.appendChild(n);
  }
  return box;
}

function renderSteps(r) {
  const box = document.createElement("div");
  box.className = "steps";
  box.innerHTML = `<h3>Elkészítés</h3><ol></ol>`;
  const ol = box.querySelector("ol");
  for (const step of r.steps) {
    const li = document.createElement("li");
    li.textContent = step;
    ol.appendChild(li);
  }
  return box;
}

function renderMore() {
  const ol = $("#more");
  ol.innerHTML = "";
  const rest = state.suggestions.filter((s) => s !== state.current).slice(0, 5);
  if (rest.length === 0) {
    const li = document.createElement("li");
    li.className = "empty";
    li.textContent = "Nincs több ajánlat ezzel a szűrővel.";
    ol.appendChild(li);
    return;
  }
  for (const s of rest) {
    const li = document.createElement("li");
    const b = document.createElement("button");
    b.type = "button";
    b.innerHTML = `<span class="m-emoji" aria-hidden="true"></span>
      <span><span class="m-name"></span><span class="m-sub"></span></span>
      <span class="m-cost"><span class="m-total"></span><span class="m-save"></span></span>`;
    b.querySelector(".m-emoji").textContent = s.recipe.emoji;
    b.querySelector(".m-name").textContent = s.recipe.name;
    b.querySelector(".m-sub").textContent =
      `${s.recipe.minutes} perc · ${s.dealCount} hozzávaló jó áron`;
    b.querySelector(".m-total").textContent = ft(s.totalCost);
    if (s.savings >= 20) b.querySelector(".m-save").textContent = `−${ft(s.savings)}`;
    b.addEventListener("click", () => {
      state.current = s;
      state.shown.add(s.recipe.id);
      renderPick();
      renderMore();
      $("#pick").scrollIntoView({ behavior: matchMedia("(prefers-reduced-motion: reduce)").matches ? "auto" : "smooth", block: "start" });
    });
    li.appendChild(b);
    ol.appendChild(li);
  }
}

function renderDeals(deals) {
  const ul = $("#deals");
  ul.innerHTML = "";
  if (deals.length === 0) {
    const li = document.createElement("li");
    li.className = "empty";
    li.textContent = "Ebben a boltban most nincs kiemelkedő ár ebben a nézetben.";
    ul.appendChild(li);
    return;
  }
  const tpl = $("#tpl-tag");
  for (const d of deals) {
    const li = tpl.content.firstElementChild.cloneNode(true);
    li.querySelector(".tag-kind").textContent =
      DEAL_LABEL[d.type] + (d.ingredient ? ` · receptekhez: ${d.ingredient.toLowerCase()}` : "");
    li.querySelector(".tag-name").textContent = d.item.name;
    const oldEl = li.querySelector(".tag-old");
    if (d.type === "CATEGORY") {
      // itt nincs "régi ár", csak kategóriaátlag – ne tűnjön áthúzott akciós árnak
      const avg = document.createElement("span");
      avg.className = "tag-avg";
      avg.textContent = `átlag ${ft(d.referencePrice)}`;
      oldEl.replaceWith(avg);
    } else {
      oldEl.textContent = ft(d.referencePrice);
    }
    li.querySelector(".tag-price").textContent = ft(d.item.price);
    li.querySelector(".tag-reason").textContent = d.reason;
    ul.appendChild(li);
  }
}

// ---------- események ----------

function bind() {
  $("#another").addEventListener("click", () => nextRecipe(false));

  $("#filters").addEventListener("click", (e) => {
    const b = e.target.closest("button[data-tag]");
    if (!b) return;
    state.tag = b.dataset.tag;
    for (const x of document.querySelectorAll("#filters button")) {
      x.setAttribute("aria-pressed", String(x === b));
    }
    loadStore().catch((err) => showError(err.message));
  });

  $("#onlyIngredients").addEventListener("change", () => {
    loadDeals().catch((err) => showError(err.message));
  });

  $("#refresh").addEventListener("click", async () => {
    const btn = $("#refresh");
    btn.disabled = true;
    btn.textContent = "Frissítés…";
    try {
      await api("/api/refresh", { method: "POST" });
      await loadStatus();
      await loadStore();
      btn.textContent = "Árak frissítve";
    } catch (err) {
      btn.textContent = "Frissítés sikertelen";
      showError(err.message);
    } finally {
      setTimeout(() => { btn.disabled = false; btn.textContent = "Árak frissítése"; }, 2500);
    }
  });
}

async function init() {
  bind();
  try {
    await loadStatus();
    await loadStore();
  } catch (e) {
    $("#source").textContent = "Az adatok nem tölthetők be.";
    showError(e.message);
  }
}

init();
