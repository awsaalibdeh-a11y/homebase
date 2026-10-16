/* Homebase offline worker. It keeps the app itself, the map library and every map tile you have looked at,
   so the laptop app opens and shows the last thing it saw even with no connection. Live data (/api) is never cached here:
   the page keeps its own snapshot and message queue. */
const VERSION = "hb-web-v7";
const SHELL = ["/app", "/static/web/web.css", "/static/web/web.js", "/static/web/calls.js", "/static/web/icon.svg", "/static/web/icon-192.png", "/manifest.webmanifest",
  "https://cdnjs.cloudflare.com/ajax/libs/leaflet/1.9.4/leaflet.min.css", "https://cdnjs.cloudflare.com/ajax/libs/leaflet/1.9.4/leaflet.min.js"];
const TILES = "hb-tiles-v1", TILE_LIMIT = 900;

self.addEventListener("install", e => {
  e.waitUntil((async () => { const c = await caches.open(VERSION); await Promise.allSettled(SHELL.map(u => c.add(u))); self.skipWaiting(); })());
});
self.addEventListener("activate", e => {
  e.waitUntil((async () => { for (const k of await caches.keys()) if (k !== VERSION && k !== TILES) await caches.delete(k); await self.clients.claim(); })());
});

async function trim(cache, max) { const keys = await cache.keys(); for (let i = 0; i < keys.length - max; i++) await cache.delete(keys[i]); }

self.addEventListener("fetch", e => {
  const req = e.request, url = new URL(req.url);
  if (req.method !== "GET") return;
  if (url.origin === location.origin && (url.pathname.startsWith("/api/") || url.pathname === "/health" || url.pathname === "/download")) return;

  if (url.hostname.endsWith("tile.openstreetmap.org")) {                       // map tiles: show what we have, refresh quietly
    e.respondWith((async () => {
      const cache = await caches.open(TILES), hit = await cache.match(req);
      const fresh = fetch(req).then(r => { if (r.ok || r.type === "opaque") { cache.put(req, r.clone()); trim(cache, TILE_LIMIT); } return r; }).catch(() => null);
      return hit || (await fresh) || new Response("", { status: 504 });
    })());
    return;
  }
  if (url.hostname === "cdnjs.cloudflare.com") {                                // the map library: cache first
    e.respondWith(caches.match(req).then(h => h || fetch(req).then(r => { const c = r.clone(); caches.open(VERSION).then(x => x.put(req, c)); return r; })));
    return;
  }
  if (url.origin === location.origin && (url.pathname === "/app" || url.pathname.startsWith("/static/web/") || url.pathname === "/manifest.webmanifest")) {
    e.respondWith((async () => {                                                // our own files: newest when online, saved copy when not
      try { const r = await fetch(req); if (r.ok) { const c = r.clone(); caches.open(VERSION).then(x => x.put(req, c)); } return r; }
      catch (err) { return (await caches.match(req)) || (req.mode === "navigate" ? await caches.match("/app") : null) || new Response("Offline", { status: 503 }); }
    })());
  }
});


/* clicking a desktop notification opens Homebase on that conversation */
self.addEventListener("notificationclick", e => {
  e.notification.close();
  const thread = e.notification.data && e.notification.data.thread;
  e.waitUntil((async () => {
    const all = await self.clients.matchAll({ type: "window", includeUncontrolled: true });
    const open = all.find(c => c.url.includes("/app")) || all[0];
    if (open) { await open.focus(); if (thread !== undefined) open.postMessage({ type: "open-thread", thread }); }
    else await self.clients.openWindow("/app");
  })());
});
