// Spórolós Konyha – kliensoldal. Nincs keretrendszer: fetch + DOM, minden szöveg textContent-tel kerül ki.
"use strict";

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
};

const DEAL_LABEL = {
  CHAIN: "Itt a legolcsóbb",
  HISTORY: "Olcsóbb a szokásosnál",
  SPREAD: "Akciógyanús",
  CATEGORY: "Tartósan olcsó",
  PROMO: "Bolti akció",
};
const DIETS = [["vega", "Húsmentes"], ["gluténmentes", "Gluténmentes"], ["laktózmentes", "Laktózmentes"]];
const EQUIPMENT = [["tuzhely", "Tűzhely"], ["suto", "Sütő"], ["airfryer", "Airfryer"]];
const DAY_TO = { Hétfő: "hétfőre", Kedd: "keddre", Szerda: "szerdára", Csütörtök: "csütörtökre", Péntek: "péntekre", Szombat: "szombatra", Vasárnap: "vasárnapra" };
const IN_STORE = {
  aldi: "az Aldiban", lidl: "a Lidlben", penny: "a Pennyben", auchan: "az Auchanban",
  tesco: "a Tescóban", spar: "a Sparban",
};

// ---------- formázás, DOM ----------

const nf = new Intl.NumberFormat("hu-HU", { maximumFractionDigits: 0 });
const ft = (v) => nf.format(Math.round(v)).replace(/\s/g, " ") + " Ft";
const cap = (t) => t.charAt(0).toUpperCase() + t.slice(1);

function qty(amount, unit) {
  const comma = (n) => String(n).replace(".", ",");
  if (unit === "kg" && amount < 1) return `${Math.round(amount * 1000)} g`;
  if (unit === "l" && amount < 1) return `${comma(Math.round(amount * 100) / 10)} dl`;
  return `${comma(Math.round(amount * 100) / 100)} ${unit}`;
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

async function api(path, opts) {
  const res = await fetch(path, opts);
  let body = null;
  try { body = await res.json(); } catch (e) { /* nem JSON */ }
  if (!res.ok) {
    const err = new Error(body?.error || `Hiba (${res.status})`);
    err.status = res.status;
    throw err;
  }
  return body;
}
const jsonOpts = (method, data) => ({ method, headers: { "Content-Type": "application/json" }, body: JSON.stringify(data) });

let toastTimer;
function toast(msg) {
  const t = $("#toast");
  t.textContent = msg;
  t.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { t.hidden = true; }, 3800);
}

function openDialog(dlg) {
  if (typeof dlg.showModal === "function") { if (!dlg.open) dlg.showModal(); }
  else dlg.setAttribute("open", "");
}

const settings = () => state.profile?.settings || {};
const storeInfo = (id) => state.status?.stores.find((s) => s.id === id);
const storeName = (id) => storeInfo(id)?.name || id;
const inStore = (id) => IN_STORE[id] || `itt: ${storeName(id)}`;
const currentStore = () => settings().store || bestStore()?.id;

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

// ---------- fotók ----------

/** Recept fotója; ha nincs (még nem töltődött le / nincs szabad licencű kép), egyszerű színes kártya az emojival. */
function photo(r, cls, width) {
  const wrap = el("div", `photo ${cls || ""}`);
  let h = 0;
  for (const c of r.id) h = (h * 31 + c.charCodeAt(0)) % 360;
  wrap.style.setProperty("--h", h);
  const fallback = () => {
    wrap.classList.add("ph");
    wrap.replaceChildren(el("span", "ph-emoji", r.emoji || "🍽️", { "aria-hidden": "true" }));
  };
  if (r.image) {
    const img = el("img", null, null, { src: r.image, alt: "", loading: "lazy", decoding: "async", width: String(width || 640), height: String(Math.round((width || 640) * 0.625)) });
    img.addEventListener("error", fallback, { once: true });
    wrap.appendChild(img);
  } else {
    fallback();
  }
  return wrap;
}

function macroLine(r) {
  if (!r.nutrition) return "";
  return `${r.nutrition.kcal} kcal · ${r.nutrition.protein} g fehérje`;
}

function badges(r, max) {
  const wrap = el("span", "badges");
  const list = [...(r.moods || []), ...(r.diets || [])].slice(0, max || 9);
  for (const b of list) wrap.appendChild(el("span", "badge" + (DIETS.some(([d]) => d === b) ? " diet" : ""), b));
  return wrap;
}

// ---------- profil (fiók nélkül: véletlen azonosító, megosztható) ----------

async function loadProfile() {
  const fromLink = new URLSearchParams(location.hash.slice(1)).get("profil");
  if (fromLink) {
    local("sporolos.profile", fromLink);
    history.replaceState(null, "", "#het");
    toast("Csatlakoztál a háztartás közös tervéhez.");
  }
  const id = local("sporolos.profile");
  if (!id) return null;
  try {
    state.profile = await api(`/api/profile?id=${encodeURIComponent(id)}`);
    return state.profile;
  } catch (e) {
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
  const d = new Date(s.date + "T00:00:00");
  const dateText = d.toLocaleDateString("hu-HU", { month: "long", day: "numeric" });
  $("#foot-source").textContent = `Most: ${s.source}, ${dateText}, ${nf.format(s.itemCount)} élelmiszerár.`;
  const banner = $("#banner");
  if (s.demo) {
    banner.textContent = "Demó árakat látsz: az Árfigyelő napi fájlja most nem volt elérhető. A Beállításokban újra próbálhatod.";
    banner.hidden = false;
  } else if (s.stale) {
    banner.textContent = `Az árak ${dateText}-i állapotot mutatnak.`;
    banner.hidden = false;
  } else {
    banner.hidden = true;
  }
}

function renderStorePill() {
  const id = currentStore();
  const s = storeInfo(id);
  const pill = $("#store-pill");
  pill.style.setProperty("--c", s?.color || "var(--line)");
  pill.querySelector(".pill-text").textContent = s ? s.name : "Bolt";
  pill.setAttribute("aria-label", `Bolt: ${s ? s.name : "nincs"} – módosítás`);
}

function storeCards(container, selected, onPick) {
  container.replaceChildren();
  const best = bestStore();
  for (const s of state.status.stores) {
    const b = el("button", "store-card", null, { type: "button", "aria-pressed": String(s.id === selected) });
    b.style.setProperty("--c", s.color);
    b.append(el("span", "sc-name", s.name), el("span", "sc-meta", `${s.dealCount} jó ár ma · ${s.recipeCount} recept`));
    if (best && s.id === best.id) b.appendChild(el("span", "sc-best", "ma itt a legjobb"));
    b.addEventListener("click", () => onPick(s.id));
    container.appendChild(b);
  }
}

function openStoreSheet() {
  storeCards($("#store-grid"), currentStore(), (id) => {
    $("#store-sheet").close();
    updateSettings({ store: id }, true);
  });
  openDialog($("#store-sheet"));
}

// ---------- nézetek ----------

const VIEWS = ["het", "lista", "nom", "receptek"];

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
  for (const sec of $$(".view")) sec.hidden = sec.dataset.view !== v;
  for (const a of $$("a[data-view]")) {
    if (a.dataset.view === v) a.setAttribute("aria-current", "page");
    else a.removeAttribute("aria-current");
  }
  if (v === "het" || v === "lista") {
    if (!state.week) loadWeek().catch((e) => toast(e.message));
    else (v === "het" ? renderWeek : renderList)();
  }
  if (v === "nom") loadDeck().catch((e) => toast(e.message));
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
  if (!settings().store) state.profile.settings.store = w.store;
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
  $("#week-meta").textContent = `${w.days.length} vacsora ${people} főre · ${w.storeName} · átlagosan ${w.avgKcal} kcal, ${w.avgProtein} g fehérje adagonként`;
  const meter = $("#week-meter");
  if (w.budget > 0) {
    meter.hidden = false;
    meter.classList.toggle("over", w.overBudget);
    meter.firstElementChild.style.width = `${Math.min(100, (w.checkoutTotal / w.budget) * 100)}%`;
    $("#week-meter-text").textContent = w.overBudget
      ? `${ft(w.checkoutTotal - w.budget)}-tal a ${ft(w.budget)}-os keret fölött`
      : `keret ${ft(w.budget)} · ${ft(w.budget - w.checkoutTotal)} marad`;
  } else {
    meter.hidden = true;
    $("#week-meter-text").textContent = "nincs megadva heti keret";
  }
  const notes = $("#week-notes");
  notes.replaceChildren(...w.notes.map((n) => el("p", "note-box", n)));

  const ol = $("#dinners");
  ol.replaceChildren();
  if (!w.days.length) {
    ol.appendChild(el("li", "empty", "Ezekkel a beállításokkal ebben a boltban most nem áll össze terv. Próbáld kevesebb szűrővel vagy másik bolttal."));
  }
  w.days.forEach((d, i) => ol.appendChild(dinnerCard(d, i, people)));
  $("#week-fine").textContent = w.staplesTotal > 0
    ? `Az összegben nincs benne ${ft(w.staplesTotal)} kamraáru (olaj, liszt, cukor…), ha venni kell. A kártyákon az adagár a közös bevásárlólistából a vacsorára eső rész (a megmaradó csomagrésszel együtt); a receptlapon a ténylegesen felhasznált mennyiség ára.`
    : "A kártyákon az adagár a közös bevásárlólistából a vacsorára eső rész; a receptlapon a ténylegesen felhasznált mennyiség ára.";
}

function dinnerCard(d, i, people) {
  const s = d.suggestion, r = s.recipe;
  const li = el("li", "dinner");
  const open = el("button", "dinner-open", null, { type: "button", "aria-label": `${d.label}: ${r.name} – recept megnyitása` });
  const pic = photo(r, "dinner-photo", 640);
  pic.appendChild(el("span", "day-chip", d.label));
  open.appendChild(pic);
  const body = el("span", "dinner-body");
  body.append(el("span", "dinner-name", r.name));
  const line = el("span", "dinner-line");
  line.append(el("b", null, `≈ ${ft(d.shareOfList / people)}`), document.createTextNode(` / adag · ${people} adag · ${r.minutes} perc`));
  body.appendChild(line);
  const sub = el("span", "dinner-sub", macroLine(r));
  body.appendChild(sub);
  body.appendChild(badges(r, 3));
  open.appendChild(body);
  open.addEventListener("click", () => openRecipe(r.id, { servings: people, day: i }));
  const swap = el("button", "swap-btn", null, { type: "button", "aria-label": `${d.label}: másik vacsorát kérek`, title: "Másikat kérek" });
  swap.innerHTML = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 9h13l-3-3M20 15H7l3 3" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg><span>Csere</span>';
  swap.addEventListener("click", () => {
    li.classList.add("swapping");
    loadWeek("swap", i).catch((e) => toast(e.message));
  });
  li.append(open, swap);
  return li;
}

// ---------- bevásárlólista ----------

function renderList() {
  const w = state.week;
  if (!w) return;
  $("#list-sub").textContent = `${w.storeName} · ${w.days.length} vacsora, ${w.people} főre`;
  $("#list-total").textContent = ft(w.checkoutTotal);
  const checked = new Set(w.checked || []);
  const root = $("#aisles");
  root.replaceChildren();
  let total = 0;
  const staples = [];
  for (const a of w.aisles) {
    const items = a.items.filter((i) => !i.staple);
    staples.push(...a.items.filter((i) => i.staple));
    if (!items.length) continue;
    root.appendChild(aisleSection(a.aisle, items, checked));
    total += items.length;
  }
  if (staples.length) root.appendChild(aisleSection("Ha nincs otthon", staples, checked));
  if (w.pantry.length) root.appendChild(el("p", "pantry", `Fűszerek a kamrából: ${w.pantry.join(", ")}.`));
  const done = w.aisles.flatMap((a) => a.items).filter((i) => !i.staple && checked.has(i.label)).length;
  $("#list-progress").textContent = `${done} / ${total} a kosárban`;
  $("#list-share").textContent = navigator.share ? "Megosztás" : "Lista másolása";
}

function aisleSection(title, items, checked) {
  const sec = el("section", "aisle");
  sec.appendChild(el("h2", null, title));
  const ul = el("ul", "buy");
  for (const i of items) {
    const li = el("li");
    const id = `chk-${i.label}`.replace(/[^\w-]/g, "_");
    const cb = el("input", null, null, { type: "checkbox", id });
    cb.checked = checked.has(i.label);
    li.classList.toggle("done", cb.checked);
    cb.addEventListener("change", async () => {
      li.classList.toggle("done", cb.checked);
      try {
        const r = await api(`/api/check?id=${state.profile.id}`, jsonOpts("POST", { key: state.week.listKey, item: i.label, on: cb.checked }));
        state.week.checked = r.checked;
        const all = state.week.aisles.flatMap((a) => a.items).filter((x) => !x.staple);
        $("#list-progress").textContent = `${all.filter((x) => r.checked.includes(x.label)).length} / ${all.length} a kosárban`;
      } catch (e) { toast(e.message); }
    });
    const label = el("label", null, null, { for: id });
    label.append(el("span", "buy-name", i.item ? i.item.name : `${i.label} (becsült ár)`),
      el("span", "buy-qty", `${i.buyText}${i.item ? ` · ${i.priceText}` : ""}`),
      el("span", "buy-use", `${i.usedIn.join(", ")}${i.leftover ? ` · marad ${i.leftover}` : ""}`));
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
  const lines = [`Bevásárlólista – ${w.storeName} (${w.people} fő, ${w.days.length} vacsora)`];
  for (const a of w.aisles) {
    lines.push("", a.aisle.toUpperCase());
    for (const i of a.items) lines.push(`☐ ${i.item ? i.item.name : i.label} – ${i.buyText} – ${ft(i.checkoutCost)}${i.staple ? " (ha nincs otthon)" : ""}`);
  }
  lines.push("", `Összesen kb. ${ft(w.checkoutTotal)}`, "", "Vacsorák:", ...w.days.map((d) => `${d.label}: ${d.suggestion.recipe.name}`));
  const text = lines.join("\n");
  try {
    if (navigator.share) {
      await navigator.share({ title: "Bevásárlólista", text });
      return;
    }
    await navigator.clipboard.writeText(text);
    toast("A lista a vágólapra került.");
  } catch (e) {
    if (e && e.name === "AbortError") return;
    toast("A lista másolása nem sikerült ebben a böngészőben.");
  }
}

// ---------- receptlap ----------

async function openRecipe(id, opts) {
  const servings = opts.servings || settings().people || 0;
  state.sheet = { id, servings, day: opts.day ?? null, swapped: new Set(), data: null };
  const dlg = $("#recipe-sheet");
  const body = $("#rs-body");
  body.replaceChildren(el("p", "empty", "Recept betöltése…"));
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

  const hero = el("div", "rs-hero");
  hero.appendChild(photo(r, "rs-photo", 800));
  const close = el("button", "rs-close", "✕", { type: "button", "aria-label": "Bezárás" });
  close.addEventListener("click", () => $("#recipe-sheet").close());
  hero.appendChild(close);
  body.appendChild(hero);
  if (s.photo) {
    const c = el("p", "credit");
    c.append("Fotó: ", s.photo.author || "", s.photo.license ? `, ${s.photo.license}` : "", " – ");
    if (s.photo.pageUrl) c.appendChild(el("a", null, "Wikimedia Commons", { href: s.photo.pageUrl, target: "_blank", rel: "noopener" }));
    else c.append(s.photo.title || "");
    if (s.photo.title && s.photo.pageUrl) c.append(` (${s.photo.title})`);
    body.appendChild(c);
  }

  const head = el("div", "rs-head");
  const left = el("div");
  left.append(el("h2", "rs-title", r.name, { id: "rs-title" }),
    el("p", "sub", `${r.minutes} perc · ${r.difficulty} · ${(r.equipment || []).join(", ")}`),
    badges(r));
  const right = el("div", "rs-side");
  const tag = el("div", "price-tag", null, { role: "img", "aria-label": `Adagonként kb. ${ft(cost / s.servings)}` });
  tag.append(el("small", null, "adagonként"), el("span", "now", ft(cost / s.servings)), el("small", null, `${s.servings} adag ${ft(cost)} · ma fizetsz ${ft(checkout)}`));
  right.append(tag, servingsControl(s.servings));
  head.append(left, right);
  body.appendChild(head);

  if (r.nutrition) {
    const m = el("dl", "macros");
    for (const [k, label, unit] of [["kcal", "kalória", "kcal"], ["protein", "fehérje", "g"], ["carbs", "szénhidrát", "g"], ["fat", "zsír", "g"]]) {
      const d = el("div");
      d.append(el("dt", null, label), el("dd", null, `${r.nutrition[k]} ${unit}`));
      m.appendChild(d);
    }
    body.appendChild(m);
    body.appendChild(el("p", "fineprint", "Becsült érték egy adagra, tipikus tápértékekből."));
  }

  // hét-műveletek
  const acts = el("div", "rs-actions");
  if (sh.day != null && state.week?.days[sh.day]?.suggestion.recipe.id === r.id) {
    const swap = el("button", "ghost", `Másikat kérek ${DAY_TO[state.week.days[sh.day].label] || "erre a napra"}`, { type: "button" });
    swap.addEventListener("click", async () => {
      $("#recipe-sheet").close();
      await loadWeek("swap", sh.day).catch((e) => toast(e.message));
    });
    acts.appendChild(swap);
  } else if (state.week?.days.length) {
    const sel = el("select", null, null, { "aria-label": "Melyik napra?" });
    state.week.days.forEach((d, i) => sel.appendChild(el("option", null, `${d.label} (most: ${d.suggestion.recipe.name})`, { value: String(i) })));
    const add = el("button", "primary", "Betesszük a hétre", { type: "button" });
    add.addEventListener("click", async () => {
      $("#recipe-sheet").close();
      await loadWeek("set", sel.value, r.id).catch((e) => toast(e.message));
      toast(`${r.name} → ${state.week.days[Number(sel.value)].label}`);
      if (state.view !== "het") location.hash = "het";
    });
    acts.append(sel, add);
  }
  const liked = state.profile?.liked.includes(r.id);
  const heart = el("button", "ghost", liked ? "♥ Kedvenc" : "♡ Kedvencekhez", { type: "button", "aria-pressed": String(!!liked) });
  heart.addEventListener("click", async () => {
    await rate(r.id, liked ? null : true);
    renderSheet();
  });
  acts.appendChild(heart);
  body.appendChild(acts);

  const cols = el("div", "rs-cols");
  cols.append(ingredientsBox(items, s), stepsBox(r));
  body.appendChild(cols);
  body.appendChild(compareBox(s, cost));
}

function servingsControl(n) {
  const wrap = el("div", "stepper", null, { role: "group", "aria-label": "Adagszám" });
  const minus = el("button", "step", "−", { type: "button", "aria-label": "Kevesebb adag" });
  const plus = el("button", "step", "+", { type: "button", "aria-label": "Több adag" });
  minus.disabled = n <= 1;
  plus.disabled = n >= 20;
  const change = (d) => {
    state.sheet.servings = Math.max(1, Math.min(20, n + d));
    loadSheet();
  };
  minus.addEventListener("click", () => change(-1));
  plus.addEventListener("click", () => change(+1));
  wrap.append(minus, el("span", "step-value", `${n} adag`), plus);
  return wrap;
}

function ingredientsBox(items, s) {
  const box = el("section", "rs-ing");
  box.appendChild(el("h3", null, `Mi kell hozzá ${inStore(s.store)}`));
  const ul = el("ul", "ing");
  for (const i of items) {
    const li = el("li");
    if (i.pantry) li.classList.add("pantry");
    const label = el("span");
    label.append(el("span", "ing-name", i.label), document.createTextNode(" "), el("span", "ing-amount", i.amount || ""));
    li.append(label, el("span", "ing-cost", i.pantry ? "kamrából" : ft(i.cost)));
    if (i.item) {
      const p = el("span", "ing-product");
      const link = el("button", "linkish", i.item.name, { type: "button" });
      link.addEventListener("click", () => openItem(i.item.id));
      p.append(link, el("span", "muted", ` · vegyél: ${i.buyText}${i.bulk ? " (nagy kiszerelés)" : ""} · ${ft(i.checkoutCost)}`));
      if (i.deal) p.appendChild(el("span", i.deal.type === "PROMO" ? "ing-deal promo" : "ing-deal",
        `${DEAL_LABEL[i.deal.type]}${i.deal.savingPercent > 0 ? ` −${Math.round(i.deal.savingPercent)}%` : ""}`, { title: i.deal.reason }));
      li.appendChild(p);
      const other = i.swappedFrom ? i.swappedFrom : i.alternative;
      if (other) li.appendChild(altRow(i.swappedFrom || i, i, other));
    } else if (i.estimated) {
      li.appendChild(el("span", "ing-product muted", "Az Árfigyelő nem követi, vagy itt nincs – becsült ár."));
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
  const what = other.bulk ? "Nagy kiszerelésben olcsóbb" : "Kisebb kiszerelésben";
  row.appendChild(el("span", null, `${what}: ${other.item ? other.item.name : ""} (${other.buyText}). A receptre ${costDiff < 0 ? "−" + ft(-costDiff) : "+" + ft(costDiff)}, ma ${payDiff >= 0 ? "+" + ft(payDiff) : "−" + ft(-payDiff)}.`));
  const btn = el("button", "ghost small", "Ezt veszem", { type: "button" });
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
  box.appendChild(el("h3", null, "Elkészítés"));
  const ol = el("ol");
  for (const step of r.steps) ol.appendChild(el("li", null, step));
  box.appendChild(ol);
  return box;
}

function compareBox(s, cost) {
  const box = el("section", "rs-compare");
  box.appendChild(el("h3", null, "Mennyibe kerülne máshol?"));
  const rows = s.stores.filter((x) => x.coverage >= 0.4)
    .map((x) => (x.store === s.store ? { ...x, totalCost: cost } : x))
    .sort((a, b) => a.totalCost - b.totalCost);
  const max = Math.max(...rows.map((x) => x.totalCost), 1);
  const ul = el("ul", "bars");
  for (const x of rows) {
    const li = el("li", x.store === s.store ? "current" : null);
    const bar = el("span", "bar");
    bar.style.setProperty("--w", `${Math.max(6, (x.totalCost / max) * 100)}%`);
    bar.style.setProperty("--c", x.color);
    li.append(el("span", "bar-name", x.name), bar, el("span", "bar-value", ft(x.totalCost)));
    ul.appendChild(li);
  }
  box.appendChild(ul);
  if (rows.length && rows[0].store !== s.store) {
    box.appendChild(el("p", "fineprint", `${cap(inStore(rows[0].store))} ${ft(cost - rows[0].totalCost)}-tal olcsóbb lenne. A bolt a fejlécben váltható.`));
  }
  return box;
}

// ---------- Nom vagy nem ----------

async function rate(recipeId, liked) {
  try {
    state.profile = await api(`/api/rate?id=${state.profile.id}`, jsonOpts("POST", { recipe: recipeId, liked }));
  } catch (e) { toast(e.message); }
}

async function allRecipes() {
  const key = `${currentStore()}|${filterQuery()}|${prefQuery()}`;
  if (state.recipes && state.recipesKey === key) return state.recipes;
  const list = await api(`/api/recipes?store=${currentStore()}&${filterQuery()}${prefQuery()}`);
  state.recipes = list;
  state.recipesKey = key;
  return list;
}

const perServing = (x) => (x.totalCost != null ? x.totalCost / x.recipe.servings : null);
const isSweet = (r) => r.tags.some((t) => t === "édes" || t === "sütemény" || t === "reggeli");

async function loadDeck() {
  const list = await allRecipes();
  const rated = new Set([...state.profile.liked, ...state.profile.disliked]);
  // vacsorák elöl, kicsit keverve; az édességek és reggelik a pakli végén
  state.deck = list.filter((x) => !rated.has(x.recipe.id) && x.totalCost != null)
    .map((x) => ({ x, k: Math.random() + (isSweet(x.recipe) ? 10 : 0) + (x.recipe.image ? 0 : 0.5) }))
    .sort((a, b) => a.k - b.k).map((o) => o.x);
  renderDeck();
  renderLiked();
}

function renderDeck() {
  const deck = $("#nom-deck");
  deck.replaceChildren();
  const top = state.deck.slice(0, 3);
  $("#nom-count").textContent = `${state.profile.liked.length} nom · ${state.profile.disliked.length} nem · még ${state.deck.length} recept`;
  for (const b of ["#nom-no", "#nom-yes", "#nom-later"]) $(b).disabled = !top.length;
  if (!top.length) {
    deck.appendChild(el("p", "empty", "Minden receptet értékeltél ezekkel a szűrőkkel. A heti terv már ezek alapján válogat."));
    return;
  }
  top.slice().reverse().forEach((x, idx) => {
    const depth = top.length - 1 - idx;
    const r = x.recipe;
    const card = el("article", "nom-card", null, { "aria-label": r.name });
    card.style.setProperty("--depth", depth);
    card.appendChild(photo(r, "nom-photo", 640));
    const body = el("div", "nom-body");
    body.append(el("h2", null, r.name), el("p", "sub", `${r.minutes} perc · ${macroLine(r)}`), badges(r, 4),
      el("span", "nom-price", `${ft(perServing(x))} / adag`));
    card.appendChild(body);
    card.append(el("span", "stamp yes", "NOM"), el("span", "stamp no", "NEM"));
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
    $("#nom-count").textContent = `${state.profile.liked.length} nom · ${state.profile.disliked.length} nem · még ${state.deck.length} recept`;
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
    ul.appendChild(el("li", "empty", "Még nincs kedvenced. Amit a „Nom” gombbal jelölsz, itt gyűlik, és a heti terv előnyben részesíti."));
    return;
  }
  for (const x of liked) {
    const li = el("li");
    const b = el("button", "liked-open", null, { type: "button" });
    b.append(photo(x.recipe, "liked-photo", 160), el("span", null, x.recipe.name));
    b.addEventListener("click", () => openRecipe(x.recipe.id, {}));
    const rm = el("button", "ghost small", "Mégsem", { type: "button", "aria-label": `${x.recipe.name} törlése a kedvencek közül` });
    rm.addEventListener("click", async () => { await rate(x.recipe.id, null); loadDeck(); });
    li.append(b, rm);
    ul.appendChild(li);
  }
}

// ---------- receptek, jó árak ----------

async function renderBrowse() {
  const store = currentStore();
  $("#today-store").textContent = inStore(store);
  const [list] = await Promise.all([allRecipes(), renderToday(store), renderDeals(store)]);
  const q = $("#browse-q").value.trim();
  const f = state.filter;
  const liked = new Set(state.profile.liked);
  const norm = (s) => s.toLowerCase().normalize("NFD").replace(/[̀-ͯ]/g, "");
  const nq = norm(q);
  const rows = list.filter((x) => {
    const r = x.recipe;
    if (nq && !norm(`${r.name} ${r.tags.join(" ")}`).includes(nq)) return false;
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
  rows.sort(key ? (a, b) => key(a) - key(b) : (a, b) => a.recipe.name.localeCompare(b.recipe.name, "hu"));
  $("#browse-count").textContent = `${rows.length} recept · adagárak ${inStore(store)}${filterQuery() ? " · a diétád és a konyhád szerint szűrve" : ""}`;
  const grid = $("#browse-grid");
  grid.replaceChildren();
  if (!rows.length) grid.appendChild(el("li", "empty", "Nincs ilyen recept ezekkel a szűrőkkel."));
  const frag = document.createDocumentFragment();
  for (const x of rows) frag.appendChild(recipeCard(x, liked.has(x.recipe.id)));
  grid.appendChild(frag);
}

function recipeCard(x, isLiked) {
  const r = x.recipe;
  const li = el("li", "card");
  const b = el("button", "card-open", null, { type: "button" });
  b.appendChild(photo(r, "card-photo", 400));
  const body = el("span", "card-body");
  body.append(el("span", "card-name", r.name), el("span", "card-sub", `${r.minutes} perc · ${macroLine(r)}`), badges(r, 2));
  b.appendChild(body);
  b.addEventListener("click", () => openRecipe(r.id, {}));
  const foot = el("div", "card-foot");
  foot.appendChild(el("span", "card-price", x.totalCost != null ? `${ft(perServing(x))} / adag` : "itt nem kapható minden"));
  const heart = el("button", "heart", isLiked ? "♥" : "♡", { type: "button", "aria-pressed": String(isLiked), "aria-label": `${r.name}: kedvenc` });
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
  const ok = new Set((await allRecipes()).map((x) => x.recipe.id));
  const sugs = (await api(`/api/suggestions?store=${store}&limit=60${prefQuery()}`))
    .filter((s) => ok.has(s.recipe.id) && !isSweet(s.recipe)).slice(0, 10);
  const ul = $("#today");
  ul.replaceChildren();
  for (const s of sugs) {
    const li = el("li");
    const b = el("button", "mini", null, { type: "button" });
    b.appendChild(photo(s.recipe, "mini-photo", 320));
    const deals = s.ingredients.filter((i) => i.deal).map((i) => i.label.toLowerCase()).slice(0, 2);
    b.append(el("span", "mini-name", s.recipe.name),
      el("span", "mini-sub", `${ft(s.totalCost / s.servings)} / adag${deals.length ? ` · jó áron: ${deals.join(", ")}` : ""}`));
    b.addEventListener("click", () => openRecipe(s.recipe.id, {}));
    li.appendChild(b);
    ul.appendChild(li);
  }
}

async function renderDeals(store) {
  const q = $("#deal-q").value.trim();
  const ul = $("#deals");
  const list = q.length >= 2
    ? (await api(`/api/search?q=${encodeURIComponent(q)}&store=${store}&limit=16${prefQuery()}`)).map((h) => ({ item: h, type: h.deal?.type, referencePrice: h.deal?.referencePrice, reason: h.deal?.reason }))
    : await api(`/api/deals?store=${store}&limit=16&ingredients=true${prefQuery()}`);
  ul.replaceChildren();
  if (!list.length) ul.appendChild(el("li", "empty", q ? `Nincs találat ${inStore(store)}.` : "Most nincs kiemelt ár."));
  for (const d of list) {
    const li = el("li", "deal" + (d.type === "PROMO" ? " promo" : ""));
    const b = el("button", null, null, { type: "button" });
    b.append(el("span", "deal-kind", d.type ? DEAL_LABEL[d.type] : d.item.category),
      el("span", "deal-name", d.item.name));
    const prices = el("span", "deal-prices");
    if (d.type && d.type !== "CATEGORY" && d.referencePrice > d.item.price) prices.appendChild(el("s", null, ft(d.referencePrice)));
    prices.appendChild(el("b", null, ft(d.item.price)));
    b.appendChild(prices);
    b.addEventListener("click", () => openItem(d.item.id));
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
  $("#item-cat").textContent = `${data.category} · ${data.offers.length > 1 ? `${data.offers.length} láncban kapható` : "csak egy láncban kapható"}`;
  const ul = $("#item-offers");
  ul.replaceChildren();
  const min = Math.min(...data.offers.map((o) => o.price));
  for (const o of data.offers) {
    const li = el("li", o.store === currentStore() ? "current" : null);
    if (!o.mine) li.classList.add("not-mine");
    const left = el("div");
    left.appendChild(el("b", null, o.storeName));
    const shops = o.totalShops ? `${o.shops} / ${o.totalShops} boltjukban` : (o.shops ? `${o.shops} boltjukban` : "saját akció");
    left.appendChild(el("span", "offer-meta", o.maxPrice > o.price ? `${shops} · máshol akár ${ft(o.maxPrice)}` : shops));
    if (o.deal) left.appendChild(el("span", "ing-deal", DEAL_LABEL[o.deal.type]));
    const right = el("div", "offer-price");
    right.appendChild(el("b", null, ft(o.price)));
    if (o.price > min) right.appendChild(el("span", "offer-diff", `+${ft(o.price - min)}`));
    right.appendChild(el("span", "offer-meta", `${qty(o.quantity, o.unit)} · ${ft(o.unitPrice)}/${o.unit}`));
    li.append(left, right);
    ul.appendChild(li);
  }
  openDialog($("#item-dialog"));
}

// ---------- első indítás ----------

const ob = { step: 0, draft: null };

const OB_STEPS = [
  {
    title: "Hol vásárolsz?",
    hint: "A terv ennek a láncnak a mai áraival számol (GVH Árfigyelő). Később bármikor átválthatsz.",
    render(box) {
      const grid = el("div", "store-grid");
      const pick = (id) => { ob.draft.store = id; storeCards(grid, id, pick); };
      storeCards(grid, ob.draft.store, pick);
      box.appendChild(grid);
    },
    ok: () => !!ob.draft.store,
  },
  {
    title: "Hányan esztek?",
    hint: "Ennyi adaggal számolunk, és ennyi vacsorát tervezünk a hétre.",
    render(box) {
      box.append(stepperField("Fő", "people", 1, 12, (v) => `${v} fő`), stepperField("Vacsora a héten", "dinners", 1, 7, (v) => `${v} vacsora`));
    },
  },
  {
    title: "Mennyi a heti keret?",
    hint: "A vacsorák bevásárlására. A terv ebbe próbál beleférni – és ha lehet, maradjon is belőle.",
    render(box) {
      const chips = el("div", "chips-row");
      for (const v of [8000, 12000, 15000, 20000, 30000, 0]) {
        const b = el("button", null, v ? ft(v) : "nincs keret", { type: "button", "aria-pressed": String(ob.draft.budget === v) });
        b.addEventListener("click", () => { ob.draft.budget = v; renderOb(); });
        chips.appendChild(b);
      }
      const input = el("input", null, null, { type: "number", min: "0", step: "500", inputmode: "numeric", "aria-label": "Saját összeg forintban" });
      input.value = ob.draft.budget || "";
      input.addEventListener("input", () => { ob.draft.budget = Math.max(0, Math.round(Number(input.value) || 0)); });
      const money = el("label", "money");
      money.append(el("span", null, "vagy saját összeg:"), input, el("span", null, "Ft"));
      box.append(chips, money);
    },
  },
  {
    title: "Van, amit nem esztek?",
    hint: "Csak olyan vacsorák jönnek, amik minden bejelöltnek megfelelnek. Ha nincs ilyen, lépj tovább.",
    render(box) {
      box.appendChild(toggleChips(DIETS, "diets"));
    },
  },
  {
    title: "Mivel főzöl?",
    hint: "Csak olyan receptet kapsz, ami ezekkel elkészíthető. Airfryerrel a sütős receptek egy része is megy.",
    render(box) {
      box.appendChild(toggleChips(EQUIPMENT, "equipment", true));
    },
  },
];

function stepperField(label, key, min, max, fmt) {
  const wrap = el("div", "field");
  wrap.appendChild(el("span", "field-label", label));
  const st = el("div", "stepper big");
  const minus = el("button", "step", "−", { type: "button", "aria-label": `${label}: kevesebb` });
  const val = el("span", "step-value", fmt(ob.draft[key]));
  const plus = el("button", "step", "+", { type: "button", "aria-label": `${label}: több` });
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
      if (!on && atLeastOne && ob.draft[key].length === 1) { toast("Legalább egy maradjon."); return; }
      ob.draft[key] = on ? [...ob.draft[key], v] : ob.draft[key].filter((x) => x !== v);
      b.setAttribute("aria-pressed", String(on));
    });
    row.appendChild(b);
  }
  return row;
}

function startOnboarding() {
  ob.step = 0;
  ob.draft = { store: bestStore()?.id, people: 2, dinners: 5, budget: 15000, diets: [], equipment: ["tuzhely", "suto"] };
  renderOb();
  const dlg = $("#onboard");
  dlg.addEventListener("cancel", (e) => e.preventDefault());
  openDialog(dlg);
}

function renderOb() {
  const st = OB_STEPS[ob.step];
  const box = $("#ob-step");
  box.replaceChildren();
  if (ob.step === 0) {
    box.appendChild(el("p", "ob-kicker", "Mi legyen ma vacsora – megint?"));
  }
  box.append(el("h1", "ob-title", st.title, { id: "ob-title" }), el("p", "sub", st.hint));
  st.render(box);
  $("#ob-progress").replaceChildren(...OB_STEPS.map((_, i) => el("span", i <= ob.step ? "on" : null)));
  $("#ob-back").hidden = ob.step === 0;
  $("#ob-next").textContent = ob.step === OB_STEPS.length - 1 ? "Kérem a hetet" : "Tovább";
}

async function obNext() {
  const st = OB_STEPS[ob.step];
  if (st.ok && !st.ok()) { toast("Válassz egy boltot."); return; }
  if (ob.step < OB_STEPS.length - 1) {
    ob.step++;
    renderOb();
    return;
  }
  $("#ob-next").disabled = true;
  try {
    state.profile = await api("/api/profile", jsonOpts("POST", { ...ob.draft, onboarded: true }));
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

  const house = group("A háztartásod", "Ezekből készül a heti terv.");
  const row = el("div", "fields");
  row.append(
    settingStepper("Fő", s.people, 1, 12, (v) => `${v} fő`, (v) => updateSettings({ people: v })),
    settingStepper("Vacsora a héten", s.dinners, 1, 7, (v) => `${v}`, (v) => updateSettings({ dinners: v })));
  const money = el("label", "field");
  money.appendChild(el("span", "field-label", "Heti keret"));
  const m = el("span", "money");
  const input = el("input", null, null, { type: "number", min: "0", step: "500", inputmode: "numeric", placeholder: "nincs" });
  input.value = s.budget || "";
  input.addEventListener("input", () => updateSettings({ budget: Math.max(0, Math.round(Number(input.value) || 0)) }));
  m.append(input, el("span", null, "Ft"));
  money.appendChild(m);
  row.appendChild(money);
  house.appendChild(row);

  group("Diéta").appendChild(checkChips(DIETS, s.diets, (v) => updateSettings({ diets: v })));
  group("Konyha", "Csak ezekkel elkészíthető recepteket kapsz.").appendChild(checkChips(EQUIPMENT, s.equipment, (v) => {
    if (!v.length) { toast("Legalább egy eszköz maradjon."); openSettings(); return; }
    updateSettings({ equipment: v });
  }));

  const stores = group("Boltjaim", "Ezekkel vetjük össze az árakat („mennyibe kerülne máshol”). A nem kedvelt láncot kiveheted.");
  const all = state.status.stores;
  const mine = s.stores?.length ? s.stores : all.map((x) => x.id);
  stores.appendChild(checkChips(all.map((x) => [x.id, x.name, x.color]), mine, (v) => {
    if (!v.length) { toast("Legalább egy bolt maradjon."); openSettings(); return; }
    const patch = { stores: v.length === all.length ? [] : v };
    if (!v.includes(currentStore())) patch.store = v[0];
    updateSettings(patch, true).then(() => loadStatus());
  }));

  const pack = group("Kiszerelés");
  for (const [v, title, hint] of [[false, "Háztartási kiszerelés", "Annyit veszel, amennyi kell, vagy a legkisebb szokásos csomagot; a nagyobbat alternatívaként mutatjuk."],
    [true, "Nagy kiszerelés is jöhet, ha olcsóbb", "Például 5 kg krumpli vagy 30 tojás, ha a receptre vetítve legalább 10%-kal olcsóbb."]]) {
    const lab = el("label", "radio");
    const r = el("input", null, null, { type: "radio", name: "pack" });
    r.checked = !!s.bulk === v;
    r.addEventListener("change", () => updateSettings({ bulk: v }, true));
    const t = el("span");
    t.append(el("b", null, title), el("br"), el("span", "hint", hint));
    lab.append(r, t);
    pack.appendChild(lab);
  }

  const cards = group("Hűségkártyáim és appjaim", "A kártyás akciók csak akkor számítanak, ha van ilyened.");
  cards.appendChild(checkChips(all.filter((x) => x.cardName).map((x) => [x.id, x.cardName, x.color]), s.cards || [], (v) => updateSettings({ cards: v }, true)));
  const promoBtn = el("button", "ghost", "Bolti akciók felvitele…", { type: "button" });
  promoBtn.addEventListener("click", () => openPromos().catch((e) => toast(e.message)));
  cards.appendChild(promoBtn);

  const share = group("Közös háztartás", "Ezzel a linkkel a párod vagy a családod ugyanazt a hetet és ugyanazt a bevásárlólistát látja, a kipipálás is közös. Csak annak add oda, akivel közösen vásároltok.");
  const link = `${location.origin}${location.pathname}#profil=${state.profile.id}`;
  const linkBox = el("input", "link-box", null, { type: "text", readonly: "", value: link, "aria-label": "Megosztási link" });
  const copy = el("button", "ghost small", "Link másolása", { type: "button" });
  copy.addEventListener("click", async () => {
    try { await navigator.clipboard.writeText(link); toast("A link a vágólapra került."); }
    catch (e) { linkBox.select(); }
  });
  const restart = el("button", "ghost small", "Újrakezdés új profillal", { type: "button" });
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

  const data = group("Adatok");
  const st = state.status;
  data.appendChild(el("p", "hint", `Árak: ${st.source} (${st.date}), ${nf.format(st.itemCount)} élelmiszerár${st.historyDays ? `, ${st.historyDays} nap előzmény` : ""}. Tárolás: ${state.profile.storage}.`));
  const refresh = el("button", "ghost", "Árak frissítése most", { type: "button" });
  refresh.addEventListener("click", async () => {
    refresh.disabled = true;
    refresh.textContent = "Frissítés…";
    try {
      await api("/api/refresh", { method: "POST" });
      await loadStatus();
      state.recipes = null;
      await loadWeek();
      refresh.textContent = "Árak frissítve";
    } catch (e) {
      refresh.textContent = "Frissítés sikertelen";
      toast(e.message);
    }
  });
  data.appendChild(refresh);
  openDialog($("#settings"));
}

function settingStepper(label, value, min, max, fmt, onChange) {
  const wrap = el("div", "field");
  wrap.appendChild(el("span", "field-label", label));
  const st = el("div", "stepper");
  let v = value;
  const minus = el("button", "step", "−", { type: "button", "aria-label": `${label}: kevesebb` });
  const val = el("span", "step-value", fmt(v));
  const plus = el("button", "step", "+", { type: "button", "aria-label": `${label}: több` });
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

// ---------- bolti akciók ----------

async function openPromos() {
  if (!state.ingredients) state.ingredients = await api("/api/ingredients");
  const form = $("#promo-form");
  form.elements.store.replaceChildren(...state.status.stores.map((s) => {
    const o = el("option", null, s.name, { value: s.id });
    if (s.id === currentStore()) o.selected = true;
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
  const d = data || await api(`/api/promos${prefQuery().replace(/^&/, "?")}`);
  const ul = $("#promo-list");
  ul.replaceChildren();
  if (!d.promos.length) ul.appendChild(el("li", "empty small", "Még nincs felvitt akció."));
  for (const p of d.promos) {
    const li = el("li", `promo-item status-${p.status.toLowerCase()}`);
    const left = el("div");
    left.appendChild(el("b", null, `${p.storeName}: ${p.name}`));
    const amount = p.unit === "kg" && p.quantity === 1 ? "Ft/kg" : qty(p.quantity, p.unit);
    const dates = p.validFrom || p.validTo ? ` · ${p.validFrom || "…"} – ${p.validTo || "…"}` : "";
    left.appendChild(el("span", "offer-meta", `${ft(p.price)} (${amount})${p.regularPrice ? `, rendesen ${ft(p.regularPrice)}` : ""}${dates}${p.card ? " · kártyás" : ""}${p.ingredientName ? ` · ${p.ingredientName}` : ""}`));
    left.appendChild(el("span", "promo-status", p.statusText + (p.matchedName ? `: ${p.matchedName}` : "")));
    li.appendChild(left);
    if (p.deletable) {
      const del = el("button", "ghost small", "Törlés", { type: "button" });
      del.addEventListener("click", async () => {
        try {
          await renderPromos(await api(`/api/promos?id=${encodeURIComponent(p.id)}`, { method: "DELETE" }));
          await afterPriceChange();
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

async function afterPriceChange() {
  state.recipes = null;
  await loadStatus();
  await loadWeek();
}

async function submitPromo(e) {
  e.preventDefault();
  const f = e.target;
  const body = {
    store: f.elements.store.value, ingredient: f.elements.ingredient.value, name: f.elements.name.value.trim(),
    price: Number(f.elements.price.value), regularPrice: Number(f.elements.regularPrice.value) || 0,
    quantity: Number(f.elements.quantity.value), unit: f.elements.unit.value,
    validFrom: f.elements.validFrom.value || null, validTo: f.elements.validTo.value || null, card: f.elements.card.checked,
  };
  try {
    const r = await api("/api/promos", jsonOpts("POST", body));
    await renderPromos(r);
    const added = r.promos.find((p) => p.name === body.name && p.store === body.store);
    toast(added ? `Akció felvéve – ${added.statusText}.` : "Akció felvéve.");
    f.elements.name.value = "";
    f.elements.price.value = "";
    f.elements.regularPrice.value = "";
    await afterPriceChange();
  } catch (err) {
    toast(err.message);
  }
}

async function importPromos() {
  const text = $("#promo-import").value.trim();
  if (!text) { toast("Előbb illeszd be az akciókat."); return; }
  try {
    const r = await api("/api/promos", jsonOpts("POST", { text }));
    await renderPromos(r);
    toast(`${r.added} akció felvéve${r.skipped.length ? `, ${r.skipped.length} sor kimaradt` : ""}.`);
    if (r.added) $("#promo-import").value = "";
    await afterPriceChange();
  } catch (err) {
    toast(err.message);
  }
}

// ---------- indulás ----------

function bind() {
  window.addEventListener("hashchange", route);
  $("#store-pill").addEventListener("click", openStoreSheet);
  $("#open-settings").addEventListener("click", () => state.profile && openSettings());
  $("#week-new").addEventListener("click", () => {
    $("#dinners").classList.add("refreshing");
    loadWeek("new").catch((e) => toast(e.message)).finally(() => $("#dinners").classList.remove("refreshing"));
  });
  $("#list-share").addEventListener("click", shareList);
  $("#nom-yes").addEventListener("click", () => decide(true));
  $("#nom-no").addEventListener("click", () => decide(false));
  $("#nom-later").addEventListener("click", () => decide(null));
  $("#nom-reset").addEventListener("click", async () => {
    try {
      state.profile = await api(`/api/rate?id=${state.profile.id}`, { method: "DELETE" });
      await loadDeck();
      toast("Az értékeléseid törölve.");
    } catch (e) { toast(e.message); }
  });
  document.addEventListener("keydown", (e) => {
    if (state.view !== "nom" || document.querySelector("dialog[open]") || e.target.closest("input, textarea, select")) return;
    if (e.key === "ArrowRight") decide(true);
    if (e.key === "ArrowLeft") decide(false);
  });
  let t1, t2;
  $("#browse-q").addEventListener("input", () => { clearTimeout(t1); t1 = setTimeout(() => renderBrowse().catch((e) => toast(e.message)), 150); });
  $("#deal-q").addEventListener("input", () => { clearTimeout(t2); t2 = setTimeout(() => renderDeals(currentStore()).catch((e) => toast(e.message)), 250); });
  $("#browse-sort").addEventListener("change", () => renderBrowse().catch((e) => toast(e.message)));
  $("#browse-filters").addEventListener("click", (e) => {
    const b = e.target.closest("button[data-f]");
    if (!b) return;
    state.filter = b.dataset.f;
    for (const x of $$("#browse-filters button")) x.setAttribute("aria-pressed", String(x === b));
    renderBrowse().catch((err) => toast(err.message));
  });
  $("#ob-next").addEventListener("click", obNext);
  $("#ob-back").addEventListener("click", () => { ob.step = Math.max(0, ob.step - 1); renderOb(); });
  $("#promo-form").addEventListener("submit", submitPromo);
  $("#promo-import-btn").addEventListener("click", importPromos);
  for (const dlg of $$("dialog:not(#onboard)")) {
    dlg.addEventListener("click", (e) => { if (e.target === e.currentTarget) e.currentTarget.close(); });
  }
  $("#recipe-sheet").addEventListener("close", () => {
    if (new URLSearchParams(location.hash.slice(1)).get("recept")) history.replaceState(null, "", `#${state.view}`);
  });
}

async function afterProfile() {
  await loadStatus();
  renderStorePill();
  const h = location.hash.slice(1);
  if (!VIEWS.includes(h) && !h.startsWith("recept=")) history.replaceState(null, "", "#het");
  route();
  if (state.view !== "het" && state.view !== "lista") loadWeek().catch((e) => toast(e.message));
}

async function init() {
  bind();
  try {
    state.status = await api("/api/status");
    const p = await loadProfile();
    if (!p) {
      await loadStatus();
      startOnboarding();
      return;
    }
    await afterProfile();
  } catch (e) {
    $("#banner").textContent = `Az adatok nem tölthetők be: ${e.message}`;
    $("#banner").hidden = false;
  }
}

init();
