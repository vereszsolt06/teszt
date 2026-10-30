// Spórolós Konyha – a heti terv, a "Nom vagy nem" és a receptböngésző nézetei.
// Az app.js segédfüggvényeit (el, $, $$, api, ft, state, store, toast, showRecipe…) használja.
"use strict";

const views = {
  plan: null,         // az utolsó /api/plan válasz
  planBusy: 0,        // elavult válaszok eldobásához
  excluded: [],       // a cserével kidobott receptek (hogy ne jöjjenek vissza azonnal)
  recipes: null,      // /api/recipes a kiválasztott boltra
  recipesStore: null,
  deck: [],           // a "Nom vagy nem" pakli
  filter: "",
};

// ---------- beállítások és ízlés (localStorage) ----------

function planPrefs() {
  const def = { people: 2, dinners: 5, budget: 0, diets: [], equipment: ["tuzhely", "suto"] };
  try {
    const p = JSON.parse(store("sporolos.plan") || "null");
    if (p && typeof p === "object") {
      return {
        people: clamp(Number(p.people) || def.people, 1, 12),
        dinners: clamp(Number(p.dinners) || def.dinners, 1, 7),
        budget: Math.max(0, Number(p.budget) || 0),
        diets: Array.isArray(p.diets) ? p.diets : def.diets,
        equipment: Array.isArray(p.equipment) ? p.equipment : def.equipment,
      };
    }
  } catch (e) { /* alapértékek */ }
  return def;
}
function savePlanPrefs(p) { store("sporolos.plan", JSON.stringify(p)); }
const clamp = (v, lo, hi) => Math.max(lo, Math.min(hi, v));

function taste() {
  try {
    const t = JSON.parse(store("sporolos.taste") || "null");
    if (t && Array.isArray(t.liked) && Array.isArray(t.disliked)) return t;
  } catch (e) { /* üres */ }
  return { liked: [], disliked: [] };
}
function saveTaste(t) { store("sporolos.taste", JSON.stringify(t)); }
function rate(id, liked) {
  const t = taste();
  t.liked = t.liked.filter((x) => x !== id);
  t.disliked = t.disliked.filter((x) => x !== id);
  if (liked === true) t.liked.push(id);
  if (liked === false) t.disliked.push(id);
  saveTaste(t);
  return t;
}

/** A diéta- és eszközszűrő mindhárom nézetben közös (a heti terv beállításaiból). */
function filterQuery(p) {
  const q = new URLSearchParams();
  if (p.diets.length) q.set("diets", p.diets.join(","));
  // ha minden eszköz megvan, nem szűrünk
  if (p.equipment.length && p.equipment.length < 3) q.set("equipment", p.equipment.join(","));
  return q.toString();
}

// ---------- nézetváltás ----------

function setView(v, focus) {
  if (!["ma", "terv", "nom", "receptek"].includes(v)) v = "ma";
  state.view = v;
  for (const b of $$(".view-row button")) b.setAttribute("aria-selected", String(b.dataset.view === v));
  for (const p of $$("[data-panel]")) p.hidden = p.dataset.panel !== v;
  $(".skip").hidden = v !== "ma";
  writeHash();
  refreshView().catch((e) => toast(e.message));
  if (focus) $(`[data-panel="${v}"]`).querySelector("h2")?.focus?.();
}

async function refreshView() {
  if (!state.store) return;
  if (state.view === "terv") return loadPlan({ restore: true });
  if (state.view === "nom") return loadDeck();
  if (state.view === "receptek") return renderBrowse();
}

/** Az app.js hívja boltváltáskor és beállítás-változáskor. */
function onStoreChanged() {
  views.recipes = null;
  views.excluded = [];
  refreshView().catch((e) => toast(e.message));
}

/** Egy recept megnyitása a "Mai ajánlat" nézetben, adott adagszámmal. */
async function openInToday(id, servings) {
  state.servings = servings || 0;
  setView("ma");
  await showRecipe(id, servings > 0);
  window.scrollTo({ top: $("#pick").getBoundingClientRect().top + window.scrollY - 12, behavior: "auto" });
  $("#pick").focus({ preventScroll: true });
}

// ---------- heti terv ----------

function syncPlanForm() {
  const p = planPrefs();
  $("#plan-people").textContent = `${p.people} fő`;
  $("#plan-dinners").textContent = `${p.dinners} vacsora`;
  $("#plan-budget").value = p.budget ? String(p.budget) : "";
  for (const cb of $$("#plan-diets input")) cb.checked = p.diets.includes(cb.value);
  for (const cb of $$("#plan-equipment input")) cb.checked = p.equipment.includes(cb.value);
  for (const b of $$("[data-step]")) {
    const v = p[b.dataset.step], d = Number(b.dataset.d);
    const [lo, hi] = b.dataset.step === "people" ? [1, 12] : [1, 7];
    b.disabled = v + d < lo || v + d > hi;
  }
  const t = taste();
  $("#plan-taste").textContent = t.liked.length || t.disliked.length
    ? `${t.liked.length} kedvenc és ${t.disliked.length} kihagyott recept számít bele`
    : "Tipp: a „Nom vagy nem” fülön megmutathatod, mit szeretsz.";
}

/** A tárolt hét (bolt + receptek), hogy újratöltés után is ugyanazt lásd. */
function savedWeek() {
  try {
    const w = JSON.parse(store("sporolos.week") || "null");
    if (w && w.store === state.store && Array.isArray(w.ids)) return w;
  } catch (e) { /* nincs */ }
  return null;
}

/**
 * @param opts.restore  a tárolt hetet tölti vissza (ha van)
 * @param opts.keep     a megtartandó receptek napi sorrendben (null = új recept arra a napra)
 * @param opts.fresh    teljesen új hét
 */
async function loadPlan(opts = {}) {
  syncPlanForm();
  const p = planPrefs();
  const my = ++views.planBusy;
  const q = new URLSearchParams(filterQuery(p));
  q.set("store", state.store);
  q.set("people", p.people);
  q.set("dinners", p.dinners);
  if (p.budget) q.set("budget", p.budget);
  const t = taste();
  if (t.liked.length) q.set("liked", t.liked.join(","));
  if (t.disliked.length) q.set("disliked", t.disliked.join(","));
  let seed = Number(store("sporolos.seed")) || 1;
  let keep = opts.keep;
  if (opts.restore) {
    const w = savedWeek();
    if (w && w.people === p.people && w.ids.length === p.dinners) keep = w.ids;
  }
  if (opts.fresh) {
    seed += 1;
    store("sporolos.seed", String(seed));
  }
  if (keep) q.set("keep", keep.map((x) => x || "_").join(","));
  if (views.excluded.length) q.set("exclude", views.excluded.join(","));
  q.set("seed", seed);
  const plan = await api(`/api/plan?${q}${prefQuery()}`);
  if (my !== views.planBusy) return;
  views.plan = plan;
  store("sporolos.week", JSON.stringify({ store: state.store, people: p.people, ids: plan.days.map((d) => d.suggestion.recipe.id) }));
  renderPlan();
}

function renderPlan() {
  const plan = views.plan;
  const p = planPrefs();
  const sum = $("#plan-summary");
  sum.replaceChildren();

  const total = plan.checkoutTotal;
  const box = el("div", "plan-total");
  const price = el("div", "price-tag", null, { role: "img", "aria-label": `A heti bevásárlás kb. ${ft(total)}` });
  price.append(el("small", null, `${plan.days.length} vacsora, ${p.people} főre`), el("span", "now", ft(total)));
  box.appendChild(price);
  const facts = el("div", "plan-facts");
  const perServing = plan.days.length ? total / (plan.days.length * p.people) : 0;
  facts.append(
    el("p", null, `${storeName(plan.store)} · kb. ${ft(perServing)} / adag`),
    el("p", null, `Átlagosan ${plan.avgKcal} kcal és ${plan.avgProtein} g fehérje adagonként`),
  );
  if (plan.budget > 0) {
    const pct = Math.min(100, Math.round((total / plan.budget) * 100));
    const meter = el("div", "meter" + (plan.overBudget ? " over" : ""), null,
      { role: "meter", "aria-valuemin": "0", "aria-valuemax": String(plan.budget), "aria-valuenow": String(Math.round(total)), "aria-label": "A keret felhasználása" });
    const fill = el("span");
    fill.style.width = pct + "%";
    meter.appendChild(fill);
    facts.append(meter, el("p", "meter-text", plan.overBudget
      ? `${ft(total - plan.budget)}-tal a ${ft(plan.budget)}-os keret fölött`
      : `A ${ft(plan.budget)}-os keretből ${ft(plan.budget - total)} marad`));
  }
  if (plan.staplesTotal > 0) facts.appendChild(el("p", "note", `+ ${ft(plan.staplesTotal)}, ha olajat, lisztet, cukrot is venni kell.`));
  box.appendChild(facts);
  sum.appendChild(box);
  for (const n of plan.notes) sum.appendChild(el("p", "plan-note", n));

  const ol = $("#plan-days");
  ol.replaceChildren();
  if (!plan.days.length) {
    ol.appendChild(el("li", "empty", "Ezekkel a beállításokkal ebben a boltban most nem áll össze terv. Próbáld kevesebb szűrővel vagy másik bolttal."));
  }
  plan.days.forEach((d, i) => ol.appendChild(dayCard(d, i)));
  renderPlanList();
}

function dayCard(d, i) {
  const s = d.suggestion, r = s.recipe;
  const li = el("li", "day");
  const open = el("button", "day-open", null, { type: "button" });
  open.append(el("img", "day-img", null, { src: r.image, alt: "", width: "160", height: "100", loading: "lazy" }));
  const text = el("span", "day-text");
  text.append(el("span", "day-label", d.label), el("span", "day-name", r.name), el("span", "day-sub", metaLine(r)));
  text.appendChild(badges(r, 4));
  open.appendChild(text);
  open.addEventListener("click", () => openInToday(r.id, s.servings).catch((e) => toast(e.message)));
  const side = el("div", "day-side");
  side.append(el("span", "day-cost", ft(d.shareOfList)), el("small", null, "a listából"));
  const swap = el("button", "ghost small", "Csere", { type: "button", "aria-label": `${d.label}: másik recept` });
  swap.addEventListener("click", () => swapDay(i));
  side.appendChild(swap);
  li.append(open, side);
  return li;
}

function swapDay(i) {
  const ids = views.plan.days.map((d) => d.suggestion.recipe.id);
  views.excluded = [...new Set([...views.excluded, ids[i]])].slice(-30);
  const keep = ids.map((id, j) => (j === i ? null : id));
  loadPlan({ keep }).catch((e) => toast(e.message));
}

function planListKey() {
  return `sporolos.weeklist.${views.plan.store}.${views.plan.days.map((d) => d.suggestion.recipe.id).join(",")}`;
}

function renderPlanList() {
  const root = $("#plan-aisles");
  root.replaceChildren();
  const plan = views.plan;
  let checked = new Set();
  try { checked = new Set(JSON.parse(store(planListKey()) || "[]")); } catch (e) { /* üres */ }
  for (const a of plan.aisles) {
    const sec = el("section", "aisle");
    sec.appendChild(el("h3", null, a.aisle));
    const ul = el("ul", "buy");
    for (const i of a.items) {
      const li = el("li");
      const id = `wk-${i.label}`.replace(/[^\w-]/g, "_");
      const cb = el("input", null, null, { type: "checkbox", id });
      cb.checked = checked.has(i.label);
      li.classList.toggle("done", cb.checked);
      cb.addEventListener("change", () => {
        if (cb.checked) checked.add(i.label); else checked.delete(i.label);
        li.classList.toggle("done", cb.checked);
        store(planListKey(), checked.size ? JSON.stringify([...checked]) : null);
      });
      const label = el("label", null, null, { for: id });
      const name = i.item ? i.item.name : `${i.label} (becsült)`;
      label.append(el("span", "buy-name", name + (i.staple ? " – ha nincs otthon" : "")),
        el("span", "buy-qty", `${i.buyText}${i.item ? ` · ${i.priceText}` : ""}`),
        el("span", "buy-use", `${i.usedIn.join(", ")}${i.leftover ? ` · marad ${i.leftover}` : ""}`));
      li.append(cb, label, el("span", "buy-price", ft(i.checkoutCost)));
      if (i.deal) li.appendChild(el("span", "buy-deal", "jó áron"));
      ul.appendChild(li);
    }
    sec.appendChild(ul);
    root.appendChild(sec);
  }
  const total = el("p", "buy-total");
  total.append(el("span", null, "A pénztárnál kb."), el("b", null, ft(plan.checkoutTotal)));
  root.appendChild(total);
  if (plan.pantry.length) root.appendChild(el("p", "note", `Fűszerek, amik kellenek: ${plan.pantry.join(", ")}.`));
  $("#plan-share").textContent = navigator.share ? "Megosztás" : "Lista másolása";
}

function planAsText() {
  const plan = views.plan;
  const lines = [`Heti bevásárlólista – ${storeName(plan.store)} (${plan.people} fő)`, ""];
  plan.days.forEach((d) => lines.push(`${d.label}: ${d.suggestion.recipe.name}`));
  for (const a of plan.aisles) {
    lines.push("", a.aisle.toUpperCase());
    for (const i of a.items) lines.push(`☐ ${i.item ? i.item.name : i.label} – ${i.buyText} – ${ft(i.checkoutCost)}${i.staple ? " (ha nincs otthon)" : ""}`);
  }
  lines.push("", `Összesen kb. ${ft(plan.checkoutTotal)}`);
  if (plan.pantry.length) lines.push(`Fűszerek: ${plan.pantry.join(", ")}`);
  return lines.join("\n");
}

async function sharePlan() {
  if (!views.plan) return;
  const text = planAsText();
  try {
    if (navigator.share) {
      await navigator.share({ title: "Heti bevásárlólista", text });
      return;
    }
    await navigator.clipboard.writeText(text);
    toast("A heti lista a vágólapra került.");
  } catch (e) {
    if (e && e.name === "AbortError") return;
    toast("A lista másolása nem sikerült ebben a böngészőben.");
  }
}

function bindPlan() {
  for (const b of $$("[data-step]")) {
    b.addEventListener("click", () => {
      const p = planPrefs();
      const k = b.dataset.step;
      p[k] = clamp(p[k] + Number(b.dataset.d), 1, k === "people" ? 12 : 7);
      savePlanPrefs(p);
      views.excluded = [];
      loadPlan().catch((e) => toast(e.message));
    });
  }
  let budgetTimer;
  $("#plan-budget").addEventListener("input", () => {
    clearTimeout(budgetTimer);
    budgetTimer = setTimeout(() => {
      const p = planPrefs();
      p.budget = Math.max(0, Math.round(Number($("#plan-budget").value) || 0));
      savePlanPrefs(p);
      loadPlan().catch((e) => toast(e.message));
    }, 500);
  });
  const chipChange = (sel, key) => {
    for (const cb of $$(`${sel} input`)) {
      cb.addEventListener("change", () => {
        const p = planPrefs();
        p[key] = $$(`${sel} input:checked`).map((x) => x.value);
        if (key === "equipment" && p.equipment.length === 0) {
          cb.checked = true;
          toast("Valamivel főzni kell – legalább egy eszköz maradjon.");
          return;
        }
        savePlanPrefs(p);
        views.recipes = null;
        views.excluded = [];
        loadPlan().catch((e) => toast(e.message));
      });
    }
  };
  chipChange("#plan-diets", "diets");
  chipChange("#plan-equipment", "equipment");
  $("#plan-new").addEventListener("click", () => {
    views.excluded = views.plan ? views.plan.days.map((d) => d.suggestion.recipe.id) : [];
    loadPlan({ fresh: true }).catch((e) => toast(e.message));
  });
  $("#plan-share").addEventListener("click", sharePlan);
}

// ---------- receptek (közös adat a pakli és a böngésző számára) ----------

async function allRecipes() {
  const p = planPrefs();
  const key = `${state.store}|${filterQuery(p)}|${prefQuery()}`;
  if (views.recipes && views.recipesStore === key) return views.recipes;
  const list = await api(`/api/recipes?store=${state.store}&${filterQuery(p)}${prefQuery()}`);
  views.recipes = list;
  views.recipesStore = key;
  return list;
}

const isSweet = (r) => r.tags.some((t) => t === "édes" || t === "sütemény");
const perServing = (x) => (x.totalCost != null ? x.totalCost / x.recipe.servings : null);

// ---------- Nom vagy nem ----------

async function loadDeck() {
  const list = await allRecipes();
  const t = taste();
  const rated = new Set([...t.liked, ...t.disliked]);
  // olcsóbbak elöl, de kicsit megkeverve, hogy ne mindig ugyanaz jöjjön
  views.deck = list.filter((x) => !rated.has(x.recipe.id) && x.totalCost != null)
    // az édességek a pakli végére kerülnek: a terv vacsorákat keres
    .map((x) => ({ x, k: perServing(x) * (0.75 + Math.random() * 0.5) + (isSweet(x.recipe) ? 1e6 : 0) }))
    .sort((a, b) => a.k - b.k)
    .map((o) => o.x);
  renderDeck();
  renderLiked();
}

function renderDeck() {
  const deck = $("#nom-deck");
  deck.replaceChildren();
  const top = views.deck.slice(0, 3);
  const t = taste();
  $("#nom-count").textContent = `${t.liked.length} nom · ${t.disliked.length} nem · még ${views.deck.length} recept`;
  for (const b of ["#nom-no", "#nom-yes", "#nom-skip"]) $(b).disabled = top.length === 0;
  if (!top.length) {
    deck.appendChild(el("p", "empty", "Minden receptet értékeltél ezekkel a szűrőkkel. A heti terv már ezek alapján válogat."));
    return;
  }
  top.slice().reverse().forEach((x, idx) => {
    const depth = top.length - 1 - idx;
    const r = x.recipe;
    const card = el("article", "nom-card", null, { "aria-label": r.name });
    card.style.setProperty("--depth", depth);
    card.append(el("img", null, null, { src: r.image, alt: "", width: "640", height: "400", draggable: "false" }));
    const body = el("div", "nom-body");
    const price = el("span", "nom-price", `${ft(perServing(x))} / adag`);
    body.append(el("h3", null, r.name), el("p", "pick-meta", metaLine(r)), badges(r, 5), price);
    card.appendChild(body);
    card.append(el("span", "stamp yes", "NOM"), el("span", "stamp no", "NEM"));
    if (depth === 0) dragCard(card);
    deck.appendChild(card);
  });
}

function decide(liked) {
  const x = views.deck[0];
  if (!x) return;
  const card = $("#nom-deck .nom-card:last-of-type");
  const finish = () => {
    if (liked === null) views.deck.push(views.deck.shift());
    else { rate(x.recipe.id, liked); views.deck.shift(); }
    renderDeck();
    renderLiked();
  };
  const reduce = matchMedia("(prefers-reduced-motion: reduce)").matches;
  if (!card || reduce) return finish();
  const dir = liked === true ? 1 : liked === false ? -1 : 0;
  card.style.transition = "transform 220ms ease-in, opacity 220ms ease-in";
  card.style.transform = dir ? `translateX(${dir * 120}%) rotate(${dir * 18}deg)` : "translateY(30px) scale(0.92)";
  card.style.opacity = "0";
  setTimeout(finish, 200);
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
  };
  card.addEventListener("pointerup", end);
  card.addEventListener("pointercancel", end);
}

function renderLiked() {
  const ul = $("#nom-liked");
  ul.replaceChildren();
  const t = taste();
  const byId = new Map((views.recipes || []).map((x) => [x.recipe.id, x]));
  const liked = t.liked.map((id) => byId.get(id)).filter(Boolean);
  $("#nom-reset").hidden = !t.liked.length && !t.disliked.length;
  if (!liked.length) {
    ul.appendChild(el("li", "empty", "Még nincs kedvenced. Amit a „Nom” gombbal jelölsz, itt gyűlik."));
    return;
  }
  for (const x of liked) {
    const li = el("li");
    const b = el("button", "liked-open", null, { type: "button" });
    b.append(el("img", null, null, { src: x.recipe.image, alt: "", width: "96", height: "60", loading: "lazy" }),
      el("span", null, x.recipe.name));
    b.addEventListener("click", () => openInToday(x.recipe.id).catch((e) => toast(e.message)));
    const rm = el("button", "ghost small", "Mégsem", { type: "button", "aria-label": `${x.recipe.name} törlése a kedvencek közül` });
    rm.addEventListener("click", () => { rate(x.recipe.id, null); loadDeck().catch((e) => toast(e.message)); });
    li.append(b, rm);
    ul.appendChild(li);
  }
}

function bindNom() {
  $("#nom-yes").addEventListener("click", () => decide(true));
  $("#nom-no").addEventListener("click", () => decide(false));
  $("#nom-skip").addEventListener("click", () => decide(null));
  $("#nom-reset").addEventListener("click", () => {
    saveTaste({ liked: [], disliked: [] });
    loadDeck().catch((e) => toast(e.message));
    toast("Az értékeléseid törölve.");
  });
  document.addEventListener("keydown", (e) => {
    if (state.view !== "nom" || e.target.closest("input, textarea, select, dialog")) return;
    if (e.key === "ArrowRight") decide(true);
    if (e.key === "ArrowLeft") decide(false);
  });
}

// ---------- receptböngésző ----------

async function renderBrowse() {
  const list = await allRecipes();
  const q = $("#browse-q").value.trim().toLowerCase();
  const f = views.filter;
  const liked = new Set(taste().liked);
  const norm = (s) => s.toLowerCase().normalize("NFD").replace(/[̀-ͯ]/g, "");
  const nq = norm(q);
  let rows = list.filter((x) => {
    const r = x.recipe;
    if (nq && !norm(`${r.name} ${r.tags.join(" ")}`).includes(nq)) return false;
    if (!f) return true;
    if (f === "kedvenc") return liked.has(r.id);
    if (f === "airfryer") return (r.equipment || []).some((e) => e.includes("airfryer"));
    return (r.diets || []).includes(f) || (r.moods || []).includes(f);
  });
  const sort = $("#browse-sort").value;
  const key = {
    price: (x) => perServing(x) ?? 1e9,
    time: (x) => x.recipe.minutes,
    kcal: (x) => x.recipe.nutrition?.kcal ?? 1e9,
    protein: (x) => -(x.recipe.nutrition?.protein ?? 0),
  }[sort];
  rows.sort(key ? (a, b) => key(a) - key(b) : (a, b) => a.recipe.name.localeCompare(b.recipe.name, "hu"));
  $("#browse-count").textContent = `${rows.length} recept · árak: ${storeName(state.store)}`;
  const grid = $("#browse-grid");
  grid.replaceChildren();
  if (!rows.length) grid.appendChild(el("li", "empty", "Nincs ilyen recept ezekkel a szűrőkkel."));
  const frag = document.createDocumentFragment();
  for (const x of rows) {
    const r = x.recipe;
    const li = el("li", "card");
    const b = el("button", "card-open", null, { type: "button" });
    b.append(el("img", null, null, { src: r.image, alt: "", width: "320", height: "200", loading: "lazy" }));
    const body = el("span", "card-body");
    body.append(el("span", "card-name", r.name), el("span", "card-sub", metaLine(r)), badges(r, 3));
    b.appendChild(body);
    b.addEventListener("click", () => openInToday(r.id).catch((e) => toast(e.message)));
    const foot = el("div", "card-foot");
    foot.appendChild(el("span", "card-price", x.totalCost != null ? `${ft(perServing(x))} / adag` : "itt nem kapható minden"));
    const heart = el("button", "heart", liked.has(r.id) ? "♥" : "♡",
      { type: "button", "aria-pressed": String(liked.has(r.id)), "aria-label": `${r.name} a kedvencek közé` });
    heart.addEventListener("click", () => {
      const on = heart.getAttribute("aria-pressed") !== "true";
      rate(r.id, on ? true : null);
      heart.setAttribute("aria-pressed", String(on));
      heart.textContent = on ? "♥" : "♡";
    });
    foot.appendChild(heart);
    li.append(b, foot);
    frag.appendChild(li);
  }
  grid.appendChild(frag);
}

function bindBrowse() {
  let timer;
  $("#browse-q").addEventListener("input", () => {
    clearTimeout(timer);
    timer = setTimeout(() => renderBrowse().catch((e) => toast(e.message)), 150);
  });
  $("#browse-sort").addEventListener("change", () => renderBrowse().catch((e) => toast(e.message)));
  $("#browse-filters").addEventListener("click", (e) => {
    const b = e.target.closest("button[data-f]");
    if (!b) return;
    views.filter = b.dataset.f;
    for (const x of $$("#browse-filters button")) x.setAttribute("aria-pressed", String(x === b));
    renderBrowse().catch((err) => toast(err.message));
  });
}

// ---------- indulás ----------

function initViews() {
  for (const b of $$(".view-row button")) b.addEventListener("click", () => setView(b.dataset.view));
  bindPlan();
  bindNom();
  bindBrowse();
  window.addEventListener("hashchange", () => {
    const h = readHash();
    if ((h.view || "ma") !== state.view) setView(h.view || "ma");
  });
  setView(state.view);
}
