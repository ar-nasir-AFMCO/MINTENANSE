/* AFMCO Keemart Bridge — جسر بين صفحة المنصة والإضافة.
   المنصة ترسل {afmcKm:'req', id, action, payload} فتردّ الإضافة {afmcKm:'res', id, ok, result|error}.
   لا تمرّ من هنا أي جلسة ولا كلمة سر: الحفظ في القاعدة تقوم به المنصة نفسها. */
(function () {
  var VER = chrome.runtime.getManifest().version;
  function mark() { try { document.documentElement.setAttribute('data-afmc-km', VER); } catch (e) {} }
  mark(); document.addEventListener('DOMContentLoaded', mark);
  window.addEventListener('message', function (e) {
    var m = e.data;
    if (e.source !== window || !m || m.afmcKm !== 'req' || !m.id) return;
    var done = function (r) { window.postMessage({ afmcKm: 'res', id: m.id, ok: !!(r && r.ok), result: r && r.result, error: r && r.error }, location.origin); };
    try {
      chrome.runtime.sendMessage({ action: m.action, payload: m.payload || {} }, function (r) {
        if (chrome.runtime.lastError) return done({ ok: false, error: chrome.runtime.lastError.message });
        done(r);
      });
    } catch (x) { done({ ok: false, error: 'أعد تحميل صفحة المنصة — الإضافة حُدّثت' }); }
  });
})();
