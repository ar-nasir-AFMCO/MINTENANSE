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
})();
