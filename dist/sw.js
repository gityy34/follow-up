'use strict';

const cachePrefix = `follow-up:${self.registration.scope}:`;
const cacheName = `${cachePrefix}v1`;
const assets = ['./', './index.html', './style.css', './app.js', './manifest.webmanifest', './icon-192.png', './icon-512.png'];

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
  if (request.method !== 'GET' || new URL(request.url).origin !== self.location.origin) return;

  if (request.mode === 'navigate') {
    event.respondWith(fetch(request).catch(() => caches.open(cacheName).then(cache => cache.match('./'))));
    return;
  }
  event.respondWith(caches.open(cacheName).then(async cache => (await cache.match(request)) ?? fetch(request)));
});
