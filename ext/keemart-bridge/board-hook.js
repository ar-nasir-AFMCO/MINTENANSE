/* AFMCO Keemart Bridge — لوحة الأداء (Partner Delivery Dashboard).
   يسجّل ردود جدول «Courier Delivery Indicators» التي تطلبها الصفحة نفسها — لا يرسل أي طلب من عنده. */
(function () {
  if (window.__afmcKmCap) return;
  var cap = window.__afmcKmCap = [];
  var X = XMLHttpRequest.prototype, open = X.open, send = X.send;
  X.open = function (m, u) { this.__afmcU = u; return open.apply(this, arguments); };
  X.send = function (b) {
    var x = this;
    if (/osg\/getdetail\/17671/.test(String(x.__afmcU))) {
      x.addEventListener('load', function () {
        try { cap.push({ b: String(b), r: String(x.responseText) }); if (cap.length > 60) cap.splice(0, cap.length - 60); } catch (e) {}
      });
    }
    return send.apply(this, arguments);
  };
  /* تبويب الإضافة الخلفي فقط (#afmc_bg): الصفحة لا تحمّل جداولها وهي مخفية، فنجعلها تتصرف كأنها ظاهرة. */
  if (location.hash.indexOf('afmc_bg') < 0) return;
  try {
    Object.defineProperty(Document.prototype, 'visibilityState', { get: function () { return 'visible'; }, configurable: true });
    Object.defineProperty(Document.prototype, 'hidden', { get: function () { return false; }, configurable: true });
    Document.prototype.hasFocus = function () { return true; };
  } catch (e) {}
  var stop = function (e) { e.stopImmediatePropagation(); };
  window.addEventListener('visibilitychange', stop, true); document.addEventListener('visibilitychange', stop, true);
  var raf = window.requestAnimationFrame.bind(window), caf = window.cancelAnimationFrame.bind(window), seq = 0, live = {};
  window.requestAnimationFrame = function (cb) {
    var id = ++seq, run = function (t) { if (!live[id]) return; delete live[id]; cb(typeof t === 'number' ? t : performance.now()); };
    live[id] = { n: raf(run), t: setTimeout(run, 120) }; return id;
  };
  window.cancelAnimationFrame = function (id) { var x = live[id]; if (x) { caf(x.n); clearTimeout(x.t); delete live[id]; } };
  var IO = function (cb) { this.cb = cb; };
  IO.prototype.observe = function (el) {
    var self = this;
    setTimeout(function () { try { var r = el.getBoundingClientRect();
      self.cb([{ isIntersecting: true, intersectionRatio: 1, target: el, time: performance.now(), boundingClientRect: r, intersectionRect: r, rootBounds: null }], self); } catch (e) {} }, 0);
  };
  IO.prototype.unobserve = function () {}; IO.prototype.disconnect = function () {}; IO.prototype.takeRecords = function () { return []; };
  window.IntersectionObserver = IO;
})();
