// Képernyőképek a README-hez a futó appról (valódi fotókkal): node screenshots.js <docs mappa> [alap-URL]
const { chromium } = require("playwright");

(async () => {
  const D = process.argv[2] || "docs";
  const BASE = process.argv[3] || "http://127.0.0.1:8080";
  const b = await chromium.launch();
  const errs = [];
  const hideBanner = (p) => p.evaluate(() => { const x = document.getElementById("banner"); if (x) x.hidden = true; });
  async function onboard(p) {
    await p.goto(BASE + "/");
    await p.waitForSelector("#onboard[open]");
    await p.waitForTimeout(300);
  }
  async function finish(p) {
    await p.click(".store-card >> text=Lidl");
    await p.click("#ob-next");
    await p.click("#ob-next");
    await p.click("#ob-step button >> text=15 000 Ft");
    await p.click("#ob-next");
    await p.click("#ob-next");
    await p.click("#ob-next");
    await p.waitForSelector(".dinner .dinner-name");
    await p.waitForLoadState("networkidle");
    await p.waitForTimeout(800);
  }
  const d = await (await b.newContext({ viewport: { width: 1280, height: 900 } })).newPage();
  d.on("pageerror", (e) => errs.push(e.message));
  await onboard(d);
  await finish(d);
  await hideBanner(d);
  await d.screenshot({ path: `${D}/het.png` });
  await d.click(".dinner-open >> nth=0");
  await d.waitForSelector(".rs-title");
  await d.waitForLoadState("networkidle");
  await d.waitForTimeout(500);
  await d.screenshot({ path: `${D}/recept.png` });
  await d.keyboard.press("Escape");
  await d.goto(BASE + "/#lista");
  await d.waitForSelector(".aisle");
  await hideBanner(d);
  await d.click(".buy input >> nth=0");
  await d.click(".buy input >> nth=1");
  await d.waitForTimeout(300);
  await d.screenshot({ path: `${D}/lista.png` });
  await d.goto(BASE + "/#receptek");
  await d.waitForSelector(".card");
  await d.waitForLoadState("networkidle");
  await hideBanner(d);
  await d.waitForTimeout(800);
  await d.screenshot({ path: `${D}/receptek.png` });
  await d.evaluate(() => window.scrollTo(0, document.getElementById("browse-grid").getBoundingClientRect().top + scrollY - 20));
  await d.waitForTimeout(800);
  await d.screenshot({ path: `${D}/receptek-racs.png` });

  const m = await (await b.newContext({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 2 })).newPage();
  m.on("pageerror", (e) => errs.push(e.message));
  await onboard(m);
  await m.screenshot({ path: `${D}/elso-inditas-mobil.png` });
  await finish(m);
  await hideBanner(m);
  await m.screenshot({ path: `${D}/het-mobil.png` });
  await m.click(".dinner-open >> nth=2");
  await m.waitForSelector(".rs-title");
  await m.waitForLoadState("networkidle");
  await m.waitForTimeout(500);
  await m.screenshot({ path: `${D}/recept-mobil.png` });
  await m.keyboard.press("Escape");
  await m.goto(BASE + "/#nom");
  await m.waitForSelector(".nom-card");
  await m.waitForLoadState("networkidle");
  await hideBanner(m);
  await m.waitForTimeout(500);
  await m.screenshot({ path: `${D}/nom-mobil.png` });
  console.log("hibák:", errs);
  await b.close();
  if (errs.length) process.exit(1);
})();
