// Spórolós Konyha – service worker: a bolti bevásárláshoz térerő nélkül is.
// Minden GET először a hálózatról jön (online mindig friss; a statikus fájlok ETag-gel 304-et kapnak, ez olcsó),
// és a sikeres válasz a gyorsítótárba kerül. Ha nincs hálózat, a legutóbbi példányt adjuk vissza: így a hét,
// a bevásárlólista és a receptek offline is megnyílnak. Az admin felület és az írások (POST/PUT/DELETE) kimaradnak.
"use strict";

const CACHE = "sporolos-v3";
const SHELL = ["/", "/index.html", "/app.css", "/i18n.js", "/app.js", "/favicon.svg", "/manifest.webmanifest",
  "/fonts/bricolage-grotesque.woff", "/fonts/atkinson-hyperlegible-400.woff", "/fonts/atkinson-hyperlegible-700.woff",
  "/icons/icon-192.png", "/icons/apple-touch-icon.png", "/lang/en.js", "/lang/hr.js", "/lang/de.js"];

self.addEventListener("install", (e) => {
  e.waitUntil(caches.open(CACHE).then((c) => c.addAll(SHELL)).then(() => self.skipWaiting()));
});

self.addEventListener("activate", (e) => {
  e.waitUntil(caches.keys()
    .then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k))))
    .then(() => self.clients.claim()));
});

self.addEventListener("fetch", (e) => {
  const req = e.request;
  const url = new URL(req.url);
  if (req.method !== "GET" || url.origin !== location.origin) return;
  if (url.pathname.startsWith("/admin") || url.pathname.startsWith("/api/admin") || url.pathname === "/healthz") return;
  // a heti terv műveletei (csere, új hét) nem ismételhetők offline
  if (url.pathname === "/api/week" && url.searchParams.has("action")) return;
  e.respondWith(networkFirst(req, url));
});

async function networkFirst(req, url) {
  const cache = await caches.open(CACHE);
  try {
    const res = await fetch(req);
    // csak a sikeres, teljes válaszok kerülnek a gyorsítótárba (a 503-as „töltődik” nem)
    if (res.ok && res.status === 200) cache.put(req, res.clone());
    return res;
  } catch (err) {
    const hit = await cache.match(req) || (req.mode === "navigate" ? await cache.match("/index.html") : null);
    if (hit) return hit;
    if (url.pathname.startsWith("/api/")) {
      return new Response(JSON.stringify({ error: "offline", offline: true }),
        { status: 503, headers: { "Content-Type": "application/json; charset=utf-8" } });
    }
    throw err;
  }
}
