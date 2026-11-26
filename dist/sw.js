'use strict';

const cachePrefix = `follow-up:${self.registration.scope}:`;
const cacheName = `${cachePrefix}v2`;
const assets = ['./', './index.html', './style.css', './app.js', './manifest.webmanifest', './icon-192.png', './icon-512.png'];

const assetPaths = new Set(assets.map(asset => new URL(asset, self.registration.scope).pathname));

self.addEventListener('install', event => {
  event.waitUntil(caches.open(cacheName).then(cache => cache.addAll(assets)));
});

self.addEventListener('activate', event => {
  event.waitUntil(caches.keys().then(keys => Promise.all(
    keys.filter(key => key.startsWith(cachePrefix) && key !== cacheName).map(key => caches.delete(key))
  )));
});

self.addEventListener('fetch', event => {
  const request = event.request;
  const url = new URL(request.url);
  if (request.method !== 'GET' || url.origin !== self.location.origin || !assetPaths.has(url.pathname)) {
    return;
  }

  event.respondWith(caches.open(cacheName).then(async cache =>
    (await cache.match(request, { ignoreSearch: true })) ?? fetch(request)
  ));
});
