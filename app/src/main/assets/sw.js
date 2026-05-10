/* Service Worker scope: site root — file must stay next to index.html */
const CACHE = 'smallcity-pwa-v1';

/** Only precache small shell files; avoids failing install if optional assets missing */
const PRECACHE_URLS = [
  './index.html',
  './public/manifest.json',
  './public/pwa-icon.svg',
];

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches
      .open(CACHE)
      .then((cache) => cache.addAll(PRECACHE_URLS))
      .then(() => self.skipWaiting())
      .catch(() => self.skipWaiting())
  );
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches
      .keys()
      .then((keys) =>
        Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k)))
      )
      .then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', (event) => {
  const req = event.request;
  if (req.method !== 'GET') return;

  try {
    const url = new URL(req.url);
    if (url.origin !== self.location.origin) return;
  } catch {
    return;
  }

  const path = new URL(req.url).pathname || '';
  if (path.includes('/api/')) return;

  event.respondWith(
    caches.match(req).then((hit) => {
      if (hit) return hit;
      return fetch(req).then((res) => {
        if (
          res &&
          res.ok &&
          res.type === 'basic' &&
          req.url.startsWith(self.location.origin)
        ) {
          const copy = res.clone();
          caches.open(CACHE).then((c) => c.put(req, copy)).catch(() => {});
        }
        return res;
      });
    })
  );
});
