/* السكنات — تطبيق: الشبكة أولاً دائماً (البيانات حيّة من القاعدة)، والنسخة المخزّنة للواجهة فقط عند انقطاع النت */
var C = 'afmc-housing-v1';
self.addEventListener('install', function(e){ self.skipWaiting(); });
self.addEventListener('activate', function(e){ e.waitUntil(caches.keys().then(function(k){ return Promise.all(k.filter(function(x){ return x !== C; }).map(function(x){ return caches.delete(x); })); }).then(function(){ return self.clients.claim(); })); });
self.addEventListener('fetch', function(e){
  var u = new URL(e.request.url);
  if (e.request.method !== 'GET' || u.origin !== location.origin || !/housing/.test(u.pathname)) return;
  e.respondWith(fetch(e.request).then(function(r){ var c = r.clone(); caches.open(C).then(function(x){ x.put(e.request, c); }); return r; })
    .catch(function(){ return caches.match(e.request, { ignoreSearch: true }); }));
});
