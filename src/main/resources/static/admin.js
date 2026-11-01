// Spórolós Konyha – admin felület. Keretrendszer nélkül; minden szöveg textContent-tel kerül ki.
"use strict";

const $ = (s, r = document) => r.querySelector(s);
const $$ = (s, r = document) => Array.from(r.querySelectorAll(s));
const nf = new Intl.NumberFormat("hu-HU", { maximumFractionDigits: 0 });
const ft = (v) => (v == null ? "–" : nf.format(Math.round(v)).replace(/\s/g, " ") + " Ft");

function el(tag, cls, text, attrs) {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (text != null) e.textContent = text;
  if (attrs) for (const [k, v] of Object.entries(attrs)) if (v != null) e.setAttribute(k, v);
  return e;
}

let toastTimer;
function toast(msg) {
  const t = $("#toast");
  t.textContent = msg;
  t.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { t.hidden = true; }, 4200);
}

const token = {
  get: () => { try { return sessionStorage.getItem("sporolos.admin"); } catch (e) { return null; } },
  set: (v) => { try { if (v) sessionStorage.setItem("sporolos.admin", v); else sessionStorage.removeItem("sporolos.admin"); } catch (e) { /* nincs tárolás */ } },
};

async function api(path, opts = {}) {
  const headers = { ...(opts.headers || {}) };
  if (token.get()) headers["X-Admin-Token"] = token.get();
  if (opts.json !== undefined) {
    headers["Content-Type"] = "application/json";
    opts.body = JSON.stringify(opts.json);
  }
  const res = await fetch(path, { ...opts, headers });
  let body = null;
  try { body = await res.json(); } catch (e) { /* nem JSON */ }
  if (res.status === 401) {
    if (path === "/api/admin/login") throw new Error("Hibás jelszó.");
    token.set(null);
    showLogin();
    throw new Error("Lejárt a belépés, lépj be újra.");
  }
  if (!res.ok) throw new Error(body?.error || `Hiba (${res.status})`);
  return body;
}

// ---------- belépés ----------

function showLogin() {
  $("#shell").hidden = true;
  $("#login").hidden = false;
  $("#login-pw").focus();
}

async function login(e) {
  e.preventDefault();
  const err = $("#login-err");
  err.hidden = true;
  try {
    const r = await api("/api/admin/login", { method: "POST", json: { password: $("#login-pw").value } });
    token.set(r.token);
    $("#login-pw").value = "";
    $("#login").hidden = true;
    $("#shell").hidden = false;
    route();
  } catch (ex) {
    err.textContent = ex.message;
    err.hidden = false;
  }
}

// ---------- útvonalak ----------

let cache = { products: null, categories: null, status: null };

function route() {
  if (!token.get()) return showLogin();
  const [sec, arg] = decodeURIComponent(location.hash.slice(1) || "attekintes").split("/");
  for (const a of $$(".side-nav a")) {
    if (a.dataset.sec === sec) a.setAttribute("aria-current", "page"); else a.removeAttribute("aria-current");
  }
  const main = $("#main");
  main.replaceChildren(el("p", "hint", "Betöltés…"));
  const run = {
    attekintes: overview,
    receptek: () => (arg ? recipeEditor(arg) : recipeList()),
    alapanyagok: () => (arg ? productEditor(arg) : productList()),
    akciok: promos,
    adatok: dataPage,
  }[sec] || overview;
  run().catch((e) => { main.replaceChildren(el("p", "error-text", e.message)); });
  window.scrollTo(0, 0);
}

// ---------- áttekintés ----------

async function overview() {
  const o = await api("/api/admin/overview");
  const main = $("#main");
  main.replaceChildren();
  main.append(el("h1", null, "Áttekintés"),
    el("p", "hint", `Árforrás: ${o.source} · adatok napja: ${o.date} · betöltve: ${new Date(o.loadedAt).toLocaleString("hu-HU")}`));
  const tiles = el("div", "tiles");
  const tile = (label, value, cls) => { const t = el("div", "tile " + (cls || "")); t.append(el("small", null, label), el("b", null, String(value))); tiles.appendChild(t); };
  tile("Élelmiszerár ma", nf.format(o.itemCount), o.demo ? "bad" : "");
  tile("Árelőzmény", `${o.historyDays} nap`, o.historyDays < 3 ? "warn" : "");
  tile("Receptek", o.recipeCount);
  tile("Alapanyagok", o.productCount);
  tile("Recept-figyelmeztetés", o.recipeIssues, o.recipeIssues ? "warn" : "");
  tile("Bolti akciók", o.promoCount);
  tile("Háztartások", o.households);
  tile("Katalógus", o.catalogModified ? "módosított" : "beépített");
  main.appendChild(tiles);
  main.appendChild(el("p", "hint", `Tárolás: ${o.storage}`));
  if (o.warnings.length) {
    const w = el("div", "issues");
    w.append(el("b", null, "Figyelmeztetések"));
    const ul = el("ul");
    for (const x of o.warnings) ul.appendChild(el("li", null, x));
    w.appendChild(ul);
    main.appendChild(w);
  }
  const row = el("div", "row");
  const refresh = el("button", "primary", "Árak újratöltése most", { type: "button" });
  refresh.addEventListener("click", async () => {
    refresh.disabled = true;
    refresh.textContent = "Töltés…";
    try { await api("/api/admin/refresh", { method: "POST" }); toast("Árak újratöltve."); overview(); }
    catch (e) { toast(e.message); refresh.disabled = false; refresh.textContent = "Árak újratöltése most"; }
  });
  row.appendChild(refresh);
  main.appendChild(el("div", null)).appendChild(row);

  main.appendChild(el("h2", null, "Boltok"));
  const wrap = el("div", "table-wrap");
  const t = el("table", "t");
  t.innerHTML = "<thead><tr><th>Lánc</th><th class=num>Élelmiszer</th><th class=num>Jó ár</th><th class=num>Teljesen árazható recept</th><th>Becsült (itt nem kapható) alapanyagok</th></tr></thead>";
  const tb = el("tbody");
  for (const s of o.stores) {
    const tr = el("tr");
    const name = el("td");
    name.append(el("b", null, s.name));
    const miss = el("td");
    const entries = Object.entries(s.missing).sort((a, b) => b[1] - a[1]);
    if (!entries.length) miss.appendChild(el("span", "pill ok", "minden kapható"));
    for (const [k, n] of entries.slice(0, 12)) miss.appendChild(el("span", "pill warn", `${k} (${n} recept)`));
    if (entries.length > 12) miss.appendChild(el("span", "pill", `+${entries.length - 12}`));
    tr.append(name, el("td", "num", nf.format(s.items)), el("td", "num", s.deals), el("td", "num", `${s.fullyPriced} / ${o.recipeCount}`), miss);
    tb.appendChild(tr);
  }
  t.appendChild(tb);
  wrap.appendChild(t);
  main.appendChild(wrap);
  main.appendChild(el("p", "hint", "A becsült alapanyagokat az Árfigyelő nem követi, vagy abban a láncban nincs illeszkedő termék. Az Alapanyagok oldalon a kategóriák és kulcsszavak állításával javítható, vagy bolti akcióként felvihető."));
}

// ---------- receptek ----------

let recipeFilter = { q: "", issues: false };

async function recipeList() {
  const list = await api("/api/admin/recipes");
  const main = $("#main");
  main.replaceChildren();
  const head = el("div", "row between");
  head.append(el("h1", null, `Receptek (${list.length})`));
  const add = el("a", "primary", "Új recept", { href: "#receptek/uj" });
  head.appendChild(add);
  main.appendChild(head);
  const tools = el("div", "row");
  const q = el("input", null, null, { type: "search", placeholder: "Keresés név, címke, azonosító szerint…", style: "max-width:360px" });
  q.value = recipeFilter.q;
  const onlyIssues = el("label", "check");
  const cb = el("input", null, null, { type: "checkbox" });
  cb.checked = recipeFilter.issues;
  onlyIssues.append(cb, el("span", null, "csak a figyelmeztetésesek"));
  tools.append(q, onlyIssues);
  main.appendChild(tools);
  const wrap = el("div", "table-wrap");
  main.appendChild(wrap);
  const render = () => {
    const nq = norm(q.value);
    recipeFilter = { q: q.value, issues: cb.checked };
    const rows = list.filter((r) => (!nq || norm(`${r.name} ${r.id} ${r.tags.join(" ")}`).includes(nq)) && (!cb.checked || r.issues.length || r.minCoverage < 1));
    const t = el("table", "t");
    t.innerHTML = "<thead><tr><th></th><th>Recept</th><th>Címkék</th><th class=num>Adag</th><th class=num>Perc</th><th class=num>kcal/adag</th><th class=num>Legolcsóbb (pénztár)</th><th>Állapot</th></tr></thead>";
    const tb = el("tbody");
    for (const r of rows) {
      const tr = el("tr", "click");
      tr.addEventListener("click", () => { location.hash = `receptek/${r.id}`; });
      const st = el("td");
      if (r.issues.length) st.appendChild(el("span", "pill warn", `${r.issues.length} figyelmeztetés`, { title: r.issues.join("\n") }));
      if (r.minCoverage < 1) st.appendChild(el("span", "pill bad", `becsült ár valahol (${Math.round(r.minCoverage * 100)}%)`));
      if (!r.issues.length && r.minCoverage >= 1) st.appendChild(el("span", "pill ok", "rendben"));
      const name = el("td");
      name.append(el("b", null, r.name), el("br"), el("small", "muted", r.id));
      tr.append(el("td", null, r.emoji), name, el("td", null, r.tags.join(", ")), el("td", "num", r.servings), el("td", "num", r.minutes),
        el("td", "num", r.kcal ?? "–"), el("td", "num", ft(r.cheapestCheckout)), st);
      tb.appendChild(tr);
    }
    t.appendChild(tb);
    wrap.replaceChildren(t);
  };
  q.addEventListener("input", render);
  cb.addEventListener("change", render);
  render();
}

const norm = (s) => s.toLowerCase().normalize("NFD").replace(/[̀-ͯ]/g, "");

async function loadProducts() {
  if (!cache.products) cache.products = await api("/api/admin/products");
  return cache.products;
}

async function recipeEditor(id) {
  const isNew = id === "uj";
  const [products, r] = await Promise.all([loadProducts(), isNew ? Promise.resolve(null) : api(`/api/admin/recipe?id=${encodeURIComponent(id)}`)]);
  const rec = r || { id: "", name: "", emoji: "🍽️", minutes: 30, servings: 4, difficulty: "könnyű", tags: [], ingredients: [], steps: [] };
  const byKey = new Map(products.map((p) => [p.key, p]));
  const main = $("#main");
  main.replaceChildren();
  const back = el("a", null, "← Receptek", { href: "#receptek" });
  main.append(back, el("h1", null, isNew ? "Új recept" : rec.name));

  const ed = el("div", "editor");
  const formBox = el("div", "card-box");
  const side = el("div", "card-box");
  side.appendChild(el("p", "hint", "Az „Előnézet” megmutatja, mennyibe kerülne a recept a boltokban, és mit becsül az app. Mentés nélkül is kipróbálható."));
  ed.append(formBox, side);
  main.appendChild(ed);

  const grid = el("div", "form-grid");
  const input = (label, value, attrs = {}, cls = "") => {
    const f = el("label", "field " + cls);
    const i = el("input", null, null, { type: "text", ...attrs });
    i.value = value ?? "";
    f.append(el("span", "field-label", label), i);
    grid.appendChild(f);
    return i;
  };
  const fId = input("Azonosító (URL-ben)", rec.id, { placeholder: "pl. rakott-krumpli", ...(isNew ? {} : { readonly: "" }) }, "span2");
  const fEmoji = input("Emoji", rec.emoji, { maxlength: "4" });
  const fDiff = el("label", "field");
  const diffSel = el("select");
  for (const d of ["könnyű", "közepes", "nehéz"]) diffSel.appendChild(el("option", null, d, { value: d }));
  diffSel.value = rec.difficulty || "könnyű";
  fDiff.append(el("span", "field-label", "Nehézség"), diffSel);
  grid.appendChild(fDiff);
  const fName = input("Név", rec.name, { required: "" }, "span4");
  const fMin = input("Elkészítés (perc)", rec.minutes, { type: "number", min: "1" });
  const fServ = input("Adag (a mennyiségek erre szólnak)", rec.servings, { type: "number", min: "1" });
  const fTags = input("Címkék (vesszővel)", rec.tags.join(", "), { placeholder: "magyaros, húsos, leves, gyors…" }, "span2");
  const af = el("label", "check span4");
  const afCb = el("input", null, null, { type: "checkbox" });
  afCb.checked = !!rec.airfryer;
  af.append(afCb, el("span", null, "Airfryerben is elkészíthető (a sütős rész)"));
  grid.appendChild(af);
  formBox.appendChild(grid);
  if (isNew) fName.addEventListener("input", () => { if (!fId.dataset.touched) fId.value = slug(fName.value); });
  fId.addEventListener("input", () => { fId.dataset.touched = "1"; });

  formBox.appendChild(el("h2", null, "Hozzávalók"));
  formBox.appendChild(el("p", "hint", "Mennyiség: amit a felületen mutat (pl. „2 db”, „50 dkg”, „1 dl”). Szükséglet: ugyanez az alapanyag egységében (kg / l / db) – ebből számol árat. A kamrafűszereket (só, bors…) nem árazzuk."));
  const rows = el("div", "ing-rows");
  formBox.appendChild(rows);
  const productOptions = [["", "— válassz alapanyagot —"], ["__pantry", "Kamra / fűszer (nem árazott)"],
    ...products.slice().sort((a, b) => a.name.localeCompare(b.name, "hu")).map((p) => [p.key, `${p.name} (${p.unit})`])];
  const addRow = (ing = {}) => {
    const row = el("div", "ing-row");
    const sel = el("select", null, null, { "aria-label": "Alapanyag" });
    for (const [v, l] of productOptions) sel.appendChild(el("option", null, l, { value: v }));
    sel.value = ing.pantry ? "__pantry" : (ing.key || "");
    const label = el("input", null, null, { type: "text", placeholder: "megjelenő név (nem kötelező)", "aria-label": "Megjelenő név" });
    label.value = ing.label || "";
    const amount = el("input", null, null, { type: "text", placeholder: "pl. 2 db", "aria-label": "Mennyiség" });
    amount.value = ing.amount || "";
    const needWrap = el("span", "need-wrap");
    const need = el("input", null, null, { type: "number", step: "any", min: "0", placeholder: "szükséglet", "aria-label": "Szükséglet" });
    need.value = ing.need ?? "";
    const unit = el("span", "unit");
    needWrap.append(need, unit);
    const del = el("button", "icon-x", "✕", { type: "button", "aria-label": "Sor törlése" });
    del.addEventListener("click", () => row.remove());
    const sync = () => {
      const pantry = sel.value === "__pantry";
      unit.textContent = pantry ? "" : (byKey.get(sel.value)?.unit || "");
      need.disabled = amount.disabled = pantry;
      label.placeholder = pantry ? "pl. só, bors, pirospaprika" : "megjelenő név (nem kötelező)";
    };
    sel.addEventListener("change", sync);
    sync();
    row.append(sel, label, amount, needWrap, del);
    row._get = () => {
      if (!sel.value) return null;
      if (sel.value === "__pantry") return { key: "fuszer", label: label.value.trim() || "fűszerek", pantry: true };
      const o = { key: sel.value, amount: amount.value.trim(), need: Number(need.value) };
      if (label.value.trim()) o.label = label.value.trim();
      return o;
    };
    rows.appendChild(row);
  };
  for (const i of rec.ingredients) addRow(i);
  if (!rec.ingredients.length) addRow();
  const addBtn = el("button", "ghost small", "+ Hozzávaló", { type: "button" });
  addBtn.addEventListener("click", () => addRow());
  formBox.appendChild(addBtn);

  formBox.appendChild(el("h2", null, "Elkészítés"));
  const steps = el("textarea", null, null, { rows: "8", placeholder: "Lépésenként egy sor" });
  steps.value = rec.steps.join("\n");
  formBox.appendChild(steps);
  formBox.appendChild(el("p", "hint", "A sütő / tűzhely igényt és a diétákat az app a lépésekből és a hozzávalókból számolja."));

  const collect = () => ({
    id: fId.value.trim(), name: fName.value.trim(), emoji: fEmoji.value.trim(), difficulty: diffSel.value,
    minutes: Number(fMin.value), servings: Number(fServ.value),
    tags: fTags.value.split(",").map((t) => t.trim()).filter(Boolean),
    airfryer: afCb.checked,
    ingredients: $$(".ing-row", rows).map((r) => r._get()).filter(Boolean),
    steps: steps.value.split("\n").map((s) => s.trim()).filter(Boolean),
  });

  const actions = el("div", "sticky-actions");
  const preview = el("button", "ghost", "Előnézet", { type: "button" });
  const save = el("button", "primary", isNew ? "Recept létrehozása" : "Mentés", { type: "button" });
  actions.append(save, preview);
  if (!isNew) {
    const del = el("button", "ghost danger", "Törlés", { type: "button" });
    del.addEventListener("click", async () => {
      if (!confirm(`Biztosan törlöd: ${rec.name}?`)) return;
      try { await api(`/api/admin/recipe?id=${encodeURIComponent(rec.id)}`, { method: "DELETE" }); toast("Recept törölve."); location.hash = "receptek"; }
      catch (e) { toast(e.message); }
    });
    actions.appendChild(el("span", "spacer"));
    actions.appendChild(del);
  }
  formBox.appendChild(actions);

  const runPreview = async () => {
    side.replaceChildren(el("p", "hint", "Számolás…"));
    try {
      const p = await api("/api/admin/recipe/preview", { method: "POST", json: collect() });
      renderPreview(side, p);
    } catch (e) {
      side.replaceChildren(el("p", "error-text", e.message));
    }
  };
  preview.addEventListener("click", runPreview);
  save.addEventListener("click", async () => {
    save.disabled = true;
    try {
      const body = collect();
      const r2 = await api("/api/admin/recipe", { method: isNew ? "POST" : "PUT", json: body });
      cache.products = null;
      toast(r2.issues.length ? `Mentve, ${r2.issues.length} figyelmeztetéssel.` : "Mentve.");
      if (isNew) location.hash = `receptek/${r2.saved}`; else runPreview();
    } catch (e) {
      toast(e.message);
    } finally {
      save.disabled = false;
    }
  });
  if (!isNew) runPreview(); else side.appendChild(el("p", "hint", "Add meg a hozzávalókat, majd nyomd meg az Előnézetet."));
}

function renderPreview(box, p) {
  box.replaceChildren(el("h2", null, "Előnézet"));
  if (p.kcal != null) {
    box.appendChild(el("p", null, `${p.kcal} kcal, ${p.protein} g fehérje / adag · ${[...p.diets, ...p.moods].join(", ") || "–"} · ${p.oven ? "sütő" : ""}${p.oven && p.stove ? " + " : ""}${p.stove ? "tűzhely" : ""} · ${p.keepsWell ? "másnapra is jó" : "frissen az igazi"}`));
  }
  const iss = el("div", p.issues.length ? "issues" : "issues ok");
  if (p.issues.length) {
    iss.appendChild(el("b", null, "Figyelmeztetések"));
    const ul = el("ul");
    for (const i of p.issues) ul.appendChild(el("li", null, i));
    iss.appendChild(ul);
  } else {
    iss.textContent = "A kiírt mennyiségek és a számolt szükségletek egyeznek.";
  }
  box.appendChild(iss);
  const sorted = p.stores.slice().sort((a, b) => a.checkout - b.checkout);
  for (const s of sorted) {
    const d = el("details", "preview-store");
    const sum = el("summary");
    const est = s.lines.filter((l) => l.estimated).length;
    sum.append(el("span", null, s.name), el("span", null, `${ft(s.checkout)} a pénztárnál${est ? ` · ${est} becsült` : ""}`));
    d.appendChild(sum);
    const t = el("table");
    for (const l of s.lines) {
      const tr = el("tr", l.estimated ? "est" : null);
      tr.append(el("td", null, `${l.label} ${l.amount || ""}`), el("td", null, l.product || "becsült ár"), el("td", null, l.buy), el("td", "num", ft(l.pay)));
      t.appendChild(tr);
    }
    d.appendChild(t);
    box.appendChild(d);
  }
}

function slug(s) {
  return norm(s).replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "").slice(0, 60);
}

// ---------- alapanyagok ----------

async function productList() {
  cache.products = null;
  const [list, status] = await Promise.all([loadProducts(), api("/api/status")]);
  const stores = status.stores;
  const main = $("#main");
  main.replaceChildren();
  const head = el("div", "row between");
  head.append(el("h1", null, `Alapanyagok (${list.length})`), el("a", "primary", "Új alapanyag", { href: "#alapanyagok/uj" }));
  main.appendChild(head);
  main.appendChild(el("p", "hint", "A számok azt mutatják, hány bolti termék illeszkedik az alapanyagra láncanként (Árfigyelő-kategória + kulcsszavak). A piros 0 azt jelenti, hogy abban a láncban becsült árral számol."));
  const q = el("input", null, null, { type: "search", placeholder: "Keresés…", style: "max-width:360px" });
  main.appendChild(q);
  const wrap = el("div", "table-wrap");
  main.appendChild(wrap);
  const render = () => {
    const nq = norm(q.value);
    const t = el("table", "t");
    const thead = el("thead");
    const hr = el("tr");
    hr.append(el("th", null, "Alapanyag"), el("th", null, "Egység"), el("th", null, "Bolti sor"), el("th", "num", "Receptben"));
    for (const s of stores) hr.appendChild(el("th", "num", s.name));
    thead.appendChild(hr);
    t.appendChild(thead);
    const tb = el("tbody");
    for (const p of list.filter((p) => !nq || norm(`${p.name} ${p.key}`).includes(nq))) {
      const tr = el("tr", "click");
      tr.addEventListener("click", () => { location.hash = `alapanyagok/${p.key}`; });
      const name = el("td");
      name.append(el("b", null, p.name), el("br"), el("small", "muted", p.key + (p.tracked ? "" : " · az Árfigyelő nem követi")));
      tr.append(name, el("td", null, `${String(p.qty).replace(".", ",")} ${p.unit}`), el("td", null, p.aisle), el("td", "num", p.recipes));
      for (const s of stores) {
        const n = p.matches[s.id] || 0;
        const td = el("td", "num");
        td.appendChild(el("span", "cell-store" + (p.tracked && !n ? " zero" : ""), p.tracked ? String(n) : "–"));
        tr.appendChild(td);
      }
      tb.appendChild(tr);
    }
    t.appendChild(tb);
    wrap.replaceChildren(t);
  };
  q.addEventListener("input", render);
  render();
}

async function productEditor(key) {
  const isNew = key === "uj";
  if (!cache.categories) cache.categories = await api("/api/admin/categories");
  const data = isNew ? null : await api(`/api/admin/product?key=${encodeURIComponent(key)}`);
  const aisles = data?.aisles || ["Zöldség, gyümölcs", "Pékáru", "Hús, hal", "Felvágott, sajt", "Tejtermék, tojás, hűtött", "Száraz áru", "Konzerv, üveges", "Mélyhűtött", "Italok"];
  const p = data?.product || { key: "", name: "", qty: 1, unit: "kg", basePrice: 0, categoryIds: [], keywords: [], exclude: [], aisle: aisles[0] };
  const main = $("#main");
  main.replaceChildren();
  main.append(el("a", null, "← Alapanyagok", { href: "#alapanyagok" }), el("h1", null, isNew ? "Új alapanyag" : p.name));
  const ed = el("div", "editor");
  const formBox = el("div", "card-box");
  const side = el("div", "card-box");
  ed.append(formBox, side);
  main.appendChild(ed);

  const grid = el("div", "form-grid");
  const input = (label, value, attrs = {}, cls = "") => {
    const f = el("label", "field " + cls);
    const i = el("input", null, null, { type: "text", ...attrs });
    i.value = value ?? "";
    f.append(el("span", "field-label", label), i);
    grid.appendChild(f);
    return i;
  };
  const select = (label, value, options, cls = "") => {
    const f = el("label", "field " + cls);
    const s = el("select");
    for (const o of options) s.appendChild(el("option", null, o, { value: o }));
    s.value = value;
    f.append(el("span", "field-label", label), s);
    grid.appendChild(f);
    return s;
  };
  const fKey = input("Kulcs", p.key, { placeholder: "pl. csirkemell", ...(isNew ? {} : { readonly: "" }) });
  const fName = input("Név", p.name, {}, "span2");
  const fUnit = select("Egység", p.unit, ["kg", "l", "db"]);
  const fQty = input("Szokásos kiszerelés (az egységben)", p.qty, { type: "number", step: "any", min: "0" });
  const fBase = input("Becsült ár / kiszerelés (Ft), ha nincs bolti adat", p.basePrice, { type: "number", min: "0" });
  const fAisle = select("Bolti sor (a listában)", p.aisle || aisles[0], aisles, "span2");
  const fPiece = input("Egy darab tömege (kg), pl. paprika 0,15", p.pieceWeight ?? "", { type: "number", step: "any", min: "0" });
  const fBunch = input("Egy csomó tömege (kg)", p.bunchWeight ?? "", { type: "number", step: "any", min: "0" });
  const fMax = input("Háztartási max. kiszerelés", p.maxPack ?? "", { type: "number", step: "any", min: "0" });
  const checks = el("div", "row span4");
  const chk = (label, on) => { const l = el("label", "check"); const c = el("input", null, null, { type: "checkbox" }); c.checked = !!on; l.append(c, el("span", null, label)); checks.appendChild(l); return c; };
  const fStaple = chk("Kamraáru (olaj, liszt… – „ha nincs otthon”)", p.staple);
  const fPack = chk("Csak csomagolva (nem mérlegelős)", p.packaged);
  const cont = new Set(p.contains || []);
  const cMeat = chk("hús", cont.has("hus")), cFish = chk("hal", cont.has("hal")), cGl = chk("glutén", cont.has("glutén")), cLac = chk("laktóz", cont.has("laktóz"));
  grid.appendChild(checks);
  const nut = p.nutrition || [];
  const fKcal = input("kcal / 100 g", nut[0] ?? "", { type: "number", min: "0", step: "any" });
  const fP = input("fehérje g", nut[1] ?? "", { type: "number", min: "0", step: "any" });
  const fC = input("szénhidrát g", nut[2] ?? "", { type: "number", min: "0", step: "any" });
  const fF = input("zsír g", nut[3] ?? "", { type: "number", min: "0", step: "any" });
  const fKw = input("Kulcsszavak (vesszővel; a terméknévben kell lennie legalább egynek)", (p.keywords || []).join(", "), {}, "span4");
  const fEx = input("Kizáró szavak (vesszővel)", (p.exclude || []).join(", "), {}, "span4");
  formBox.appendChild(grid);

  formBox.appendChild(el("h2", null, "Árfigyelő-kategóriák"));
  formBox.appendChild(el("p", "hint", "Ezekben a kategóriákban keresi az alapanyagot. Ha egy sincs bejelölve, az Árfigyelő nem követi, és becsült árral számol."));
  const catFilter = el("input", null, null, { type: "search", placeholder: "Kategória keresése…" });
  const catList = el("div", "cat-list");
  const selected = new Set(p.categoryIds || []);
  const renderCats = () => {
    const nq = norm(catFilter.value);
    catList.replaceChildren();
    for (const c of cache.categories.filter((c) => selected.has(c.id)).concat(cache.categories.filter((c) => !selected.has(c.id)))) {
      if (nq && !norm(c.name).includes(nq) && !selected.has(c.id)) continue;
      const l = el("label");
      const cb = el("input", null, null, { type: "checkbox" });
      cb.checked = selected.has(c.id);
      cb.addEventListener("change", () => { if (cb.checked) selected.add(c.id); else selected.delete(c.id); schedule(); });
      l.append(cb, el("span", null, `${c.id} · ${c.name} (${c.count})`));
      catList.appendChild(l);
    }
  };
  catFilter.addEventListener("input", renderCats);
  formBox.append(catFilter, catList);
  renderCats();

  const csv = (v) => v.split(",").map((x) => x.trim()).filter(Boolean);
  const num = (v) => (v === "" || v == null ? 0 : Number(v));
  const collect = () => {
    const contains = [];
    if (cMeat.checked) contains.push("hus");
    if (cFish.checked) contains.push("hal");
    if (cGl.checked) contains.push("glutén");
    if (cLac.checked) contains.push("laktóz");
    const o = {
      key: fKey.value.trim(), name: fName.value.trim(), unit: fUnit.value, qty: num(fQty.value), basePrice: num(fBase.value),
      categoryIds: [...selected], keywords: csv(fKw.value), exclude: csv(fEx.value),
      pieceWeight: num(fPiece.value), bunchWeight: num(fBunch.value), maxPack: num(fMax.value),
      staple: fStaple.checked, packaged: fPack.checked, aisle: fAisle.value, contains,
    };
    if ([fKcal, fP, fC, fF].every((f) => f.value !== "")) o.nutrition = [fKcal, fP, fC, fF].map((f) => Number(f.value));
    return o;
  };

  let timer;
  const refreshMatches = async () => {
    const body = collect();
    if (!body.key) body.key = "elonezet";
    if (!body.name) body.name = "előnézet";
    try {
      const r = await api("/api/admin/product/matches", { method: "POST", json: body });
      renderMatches(side, r.matches);
    } catch (e) {
      side.replaceChildren(el("p", "error-text", e.message));
    }
  };
  const schedule = () => { clearTimeout(timer); timer = setTimeout(refreshMatches, 400); };
  for (const i of [fKw, fEx, fUnit]) i.addEventListener("input", schedule);

  const actions = el("div", "sticky-actions");
  const save = el("button", "primary", isNew ? "Alapanyag létrehozása" : "Mentés", { type: "button" });
  save.addEventListener("click", async () => {
    save.disabled = true;
    try {
      const r = await api("/api/admin/product", { method: isNew ? "POST" : "PUT", json: collect() });
      cache.products = null;
      toast("Mentve – a receptek árai újraszámolva.");
      if (isNew) location.hash = `alapanyagok/${r.saved}`;
    } catch (e) { toast(e.message); } finally { save.disabled = false; }
  });
  actions.appendChild(save);
  if (!isNew) {
    const del = el("button", "ghost danger", "Törlés", { type: "button" });
    del.addEventListener("click", async () => {
      if (!confirm(`Biztosan törlöd: ${p.name}?`)) return;
      try { await api(`/api/admin/product?key=${encodeURIComponent(p.key)}`, { method: "DELETE" }); cache.products = null; toast("Törölve."); location.hash = "alapanyagok"; }
      catch (e) { toast(e.message); }
    });
    actions.append(el("span", "spacer"), del);
  }
  formBox.appendChild(actions);
  if (data) renderMatches(side, data.matches); else refreshMatches();
}

function renderMatches(box, matches) {
  box.replaceChildren(el("h2", null, "Illeszkedő bolti termékek"), el("p", "hint", "Lánconként a 12 legolcsóbb egységárú. Ha rossz termék kerül ide, adj meg kizáró szót; ha hiányzik, bővítsd a kategóriákat vagy a kulcsszavakat."));
  for (const s of matches) {
    const d = el("div", "match-store");
    const h = el("h3");
    h.append(el("span", null, s.name), el("span", s.count ? "pill ok" : "pill bad", `${s.count} termék`));
    d.appendChild(h);
    const ul = el("ul");
    for (const i of s.items) {
      const li = el("li");
      li.append(el("span", null, i.name), el("span", "num", `${ft(i.price)} · ${nf.format(i.unitPrice)} Ft/${i.unit}`));
      ul.appendChild(li);
    }
    d.appendChild(ul);
    box.appendChild(d);
  }
}

// ---------- bolti akciók ----------

async function promos() {
  const [d, ings, status] = await Promise.all([api("/api/promos"), api("/api/ingredients"), api("/api/status")]);
  const main = $("#main");
  main.replaceChildren(el("h1", null, "Bolti akciók"),
    el("p", "hint", "Az Árfigyelő a polcárakat tartalmazza; az akciós újság, az appos és a hűségkártyás árak nincsenek benne. Itt vihetők fel. Ha egy akció már benne van az Árfigyelő áraiban, az app nem számolja kétszer."));
  const wrap = el("div", "table-wrap");
  const t = el("table", "t");
  t.innerHTML = "<thead><tr><th>Bolt</th><th>Termék</th><th class=num>Ár</th><th>Érvényes</th><th>Alapanyag</th><th>Állapot</th><th></th></tr></thead>";
  const tb = el("tbody");
  if (!d.promos.length) { const tr = el("tr"); tr.appendChild(el("td", "muted", "Még nincs felvitt akció.", { colspan: "7" })); tb.appendChild(tr); }
  for (const p of d.promos) {
    const tr = el("tr");
    const act = el("td");
    if (p.deletable) {
      const b = el("button", "ghost small danger", "Törlés", { type: "button" });
      b.addEventListener("click", async () => { try { await api(`/api/promos?id=${encodeURIComponent(p.id)}`, { method: "DELETE" }); promos(); } catch (e) { toast(e.message); } });
      act.appendChild(b);
    } else act.appendChild(el("span", "muted", p.source));
    tr.append(el("td", null, p.storeName), el("td", null, p.name + (p.card ? " (kártyás)" : "")), el("td", "num", ft(p.price)),
      el("td", null, `${p.validFrom || "…"} – ${p.validTo || "…"}`), el("td", null, p.ingredientName || "–"),
      el("td", null, p.statusText + (p.matchedName ? `: ${p.matchedName}` : "")), act);
    tb.appendChild(tr);
  }
  t.appendChild(tb);
  wrap.appendChild(t);
  main.appendChild(wrap);
  if (d.warnings.length) main.appendChild(el("p", "issues", d.warnings.join(" · ")));

  main.appendChild(el("h2", null, "Új akció"));
  const box = el("div", "card-box");
  const g = el("div", "form-grid");
  const sel = (label, opts, cls) => { const f = el("label", "field " + (cls || "")); const s = el("select"); for (const [v, l] of opts) s.appendChild(el("option", null, l, { value: v })); f.append(el("span", "field-label", label), s); g.appendChild(f); return s; };
  const inp = (label, attrs, cls) => { const f = el("label", "field " + (cls || "")); const i = el("input", null, null, { type: "text", ...attrs }); f.append(el("span", "field-label", label), i); g.appendChild(f); return i; };
  const sStore = sel("Bolt", status.stores.map((s) => [s.id, s.name]));
  const sIng = sel("Alapanyag", [["", "Válassz…"], ...ings.map((i) => [i.key, i.name + (i.tracked ? "" : " (az Árfigyelő nem követi)")])], "span2");
  const iName = inp("Termék neve (ahogy az újságban)", { maxlength: "120" }, "span4");
  const iPrice = inp("Akciós ár (Ft)", { type: "number", min: "1" });
  const iReg = inp("Rendes ár (Ft)", { type: "number", min: "0" });
  const iQty = inp("Kiszerelés", { type: "number", step: "any", min: "0.001", value: "1" });
  const sUnit = sel("Egység", [["kg", "kg"], ["g", "g"], ["l", "l"], ["ml", "ml"], ["db", "db"]]);
  const iFrom = inp("Ettől", { type: "date" });
  const iTo = inp("Eddig", { type: "date" });
  const card = el("label", "check span2");
  const cardCb = el("input", null, null, { type: "checkbox" });
  card.append(cardCb, el("span", null, "Csak hűségkártyával / appal"));
  g.appendChild(card);
  box.appendChild(g);
  const add = el("button", "primary", "Akció felvétele", { type: "button" });
  add.addEventListener("click", async () => {
    try {
      await api("/api/promos", { method: "POST", json: {
        store: sStore.value, ingredient: sIng.value, name: iName.value.trim(), price: Number(iPrice.value), regularPrice: Number(iReg.value) || 0,
        quantity: Number(iQty.value), unit: sUnit.value, validFrom: iFrom.value || null, validTo: iTo.value || null, card: cardCb.checked,
      } });
      toast("Akció felvéve.");
      promos();
    } catch (e) { toast(e.message); }
  });
  box.appendChild(el("div", "sticky-actions")).appendChild(add);
  main.appendChild(box);

  main.appendChild(el("h2", null, "Több akció beillesztése (CSV vagy JSON)"));
  const box2 = el("div", "card-box");
  box2.appendChild(el("p", "hint", "Fejléc: bolt;termek;ar;egyseg;kiszereles;rendes_ar;tol;ig;alapanyag;vonalkod;kartya"));
  const ta = el("textarea", null, null, { rows: "6", placeholder: "bolt;termek;ar;egyseg;kiszereles;tol;ig;alapanyag\nLidl;Csirkemellfilé;1499;kg;1;2026-10-30;2026-11-05;csirkemell" });
  const imp = el("button", "ghost", "Beillesztett akciók felvétele", { type: "button" });
  imp.addEventListener("click", async () => {
    try {
      const r = await api("/api/promos", { method: "POST", json: { text: ta.value } });
      toast(`${r.added} akció felvéve${r.skipped.length ? `, ${r.skipped.length} sor kimaradt: ${r.skipped.join("; ")}` : ""}.`);
      promos();
    } catch (e) { toast(e.message); }
  });
  const impRow = el("div", "sticky-actions");
  impRow.appendChild(imp);
  box2.append(ta, impRow);
  main.appendChild(box2);
}

// ---------- adatok ----------

async function dataPage() {
  const o = await api("/api/admin/overview");
  const main = $("#main");
  main.replaceChildren(el("h1", null, "Adatok"),
    el("p", "hint", `Tárolás: ${o.storage}. A receptek és alapanyagok ${o.catalogModified ? "az adminban módosított változatból" : "a beépített fájlokból"} jönnek.`));
  const box = el("div", "card-box");
  const exp = el("button", "primary", "Receptek és alapanyagok letöltése (JSON)", { type: "button" });
  exp.addEventListener("click", async () => {
    try {
      const r = await api("/api/admin/export");
      download("products.json", r.products);
      download("recipes.json", r.recipes);
    } catch (e) { toast(e.message); }
  });
  box.append(el("p", null, "A letöltött fájlok a src/main/resources/data mappába másolva beépíthetők a programba (így git-be is kerülnek)."), exp);
  main.appendChild(box);
  const box2 = el("div", "card-box");
  box2.style.marginTop = "14px";
  const reset = el("button", "ghost danger", "Vissza a beépített receptekhez", { type: "button" });
  reset.disabled = !o.catalogModified;
  reset.addEventListener("click", async () => {
    if (!confirm("Minden adminban tett recept- és alapanyag-módosítás elvész. Folytatod?")) return;
    try { await api("/api/admin/reset", { method: "POST" }); cache.products = null; toast("Visszaállítva."); dataPage(); }
    catch (e) { toast(e.message); }
  });
  box2.append(el("p", null, "Az adminban tett módosítások elvetése."), reset);
  main.appendChild(box2);
}

function download(name, text) {
  const a = el("a", null, null, { href: URL.createObjectURL(new Blob([text], { type: "application/json" })), download: name });
  document.body.appendChild(a);
  a.click();
  setTimeout(() => { URL.revokeObjectURL(a.href); a.remove(); }, 1000);
}

// ---------- indulás ----------

$("#login-form").addEventListener("submit", login);
$("#logout").addEventListener("click", () => { token.set(null); showLogin(); });
window.addEventListener("hashchange", route);
if (token.get()) { $("#shell").hidden = false; route(); } else showLogin();
