/* AFMCO Keemart Bridge — الخلفية.
   action 'status' → نسخة الإضافة وهل بوابة كيمارت مفتوحة.
   action 'pull' {days:['YYYY-MM-DD',…]} → يسحب تقرير الدوام (Attendance Statistics) لكل يوم
   من داخل صفحة بوابة كيمارت نفسها (بجلسة المستخدم) ويعيد صفوفاً جاهزة لـ keemart_upsert('attendance'). */
var PORTAL = 'https://courier-keemart.mykeeta.com/';
var PAGE = PORTAL + 'courier/capacityManager/attendanceReport.html';

chrome.runtime.onMessage.addListener(function (msg, sender, send) {
  handle(msg || {}).then(function (r) { send({ ok: true, result: r }); },
                         function (e) { send({ ok: false, error: String((e && e.message) || e) }); });
  return true;
});

async function handle(msg) {
  if (msg.action === 'status') {
    var t = await chrome.tabs.query({ url: PORTAL + '*' });
    return { version: chrome.runtime.getManifest().version, portalOpen: t.length > 0 };
  }
  if (msg.action === 'pull') return pull((msg.payload && msg.payload.days) || []);
  throw new Error('أمر غير معروف');
}

function sleep(ms) { return new Promise(function (r) { setTimeout(r, ms); }); }

async function waitLoaded(tabId) {
  for (var i = 0; i < 60; i++) {
    var t = await chrome.tabs.get(tabId);
    if (t.status === 'complete') return t;
    await sleep(500);
  }
  throw new Error('بوابة كيمارت لم تكتمل في التحميل');
}

async function pull(days) {
  days = (days || []).filter(function (d) { return /^\d{4}-\d{2}-\d{2}$/.test(d); }).slice(0, 14);
  if (!days.length) return {};
  var tabs = await chrome.tabs.query({ url: PORTAL + '*' }), tab = tabs[0], mine = false;
  if (!tab) { tab = await chrome.tabs.create({ url: PAGE, active: false }); mine = true; }
  tab = await waitLoaded(tab.id);
  if (String(tab.url || '').indexOf(PORTAL) !== 0) throw new Error('سجّل الدخول في بوابة كيمارت ثم أعد المحاولة');
  if (mine) await sleep(4000);            /* الصفحة تبني إطارها الداخلي */
  var res;
  try {
    res = await chrome.scripting.executeScript({ target: { tabId: tab.id }, world: 'MAIN', func: kmPullInPage, args: [days] });
  } catch (e) {
    throw new Error('تعذّر الوصول لصفحة كيمارت: ' + ((e && e.message) || e));
  }
  var out = res && res[0] && res[0].result;
  if (!out) throw new Error('بوابة كيمارت لم تُرجع بيانات');
  if (out.error) throw new Error(out.error);      /* تبقى الصفحة مفتوحة لو احتاجت تسجيل دخول */
  if (mine) { try { await chrome.tabs.remove(tab.id); } catch (e) {} }
  return out.days;
}

/* يُنفَّذ داخل صفحة بوابة كيمارت (عالم الصفحة) — نفس نداءات صفحة «Attendance Statistics». */
async function kmPullInPage(days) {
  try {
    var fr = document.querySelector('iframe');
    var W = (fr && fr.contentWindow) || window;
    var P = function (path, body) {
      return W.fetch('/api/s/peisong/' + path, { method: 'POST', credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) })
        .then(function (r) { return r.json(); })
        .then(function (j) { if (j.code !== 0) throw new Error(j.msg || ('code ' + j.code)); return j.data; });
    };
    var sel = await P('supplier/site/selector/param', {}), city = {};
    sel.forEach(function (s) { city[s.siteId] = s.cityName; });
    var sites = sel.map(function (s) { return s.siteId; });
    var num = function (v) { if (v == null || v === '' || v === '--') return null; var n = Number(v); return isFinite(n) ? n : null; };
    var tm = function (v) { var m = /(\d{2}:\d{2}:\d{2})\s*$/.exec(String(v || '')); return m ? m[1] : null; };
    var out = {};
    for (var i = 0; i < days.length; i++) {
      var day = days[i], p = day.split('-'), ms = Date.UTC(+p[0], +p[1] - 1, +p[2]) - 3 * 3600000, all = [], off = 0;
      for (;;) {
        var d = await P('rideroms/atten/statistics/list', { siteIds: sites, riderName: '', supplierId: '59', vehicleType: null,
          startDate: ms, endDate: ms, paging: { limit: 100, offset: off } });
        var l = (d && d.attendanceStatisticsSingleDayInfos) || [];
        all = all.concat(l); off += l.length;
        if (l.length < 100 || off > 6000) break;
      }
      var seen = {};
      out[day] = all.filter(function (x) { var k = String(x.riderId); if (!x.riderId || seen[k]) return false; seen[k] = 1; return true; })
        .map(function (x) {
          return { work_date: day, captain_id: String(x.riderId), captain_name: x.riderName || null,
            branch: x.siteName || null, city: city[x.siteId] || null, shift: x.arrangeRange || null,
            req_hours: num(x.arrangeTimeDuration), act_hours: num(x.actualAttendanceDuration),
            eff_hours: num(x.effectiveAttendanceDuration), leave_hours: num(x.leaveDuration),
            late_hours: num(x.arriveLateDuration), early_hours: num(x.leaveEarlyDuration),
            shift_orders: num(x.deliveryOrderCount), check_in: tm(x.startWorkTime), check_out: tm(x.endWorkTime),
            att_status: x.statusDesc || null };
        });
    }
    return { days: out };
  } catch (e) {
    return { error: 'بوابة كيمارت: ' + ((e && e.message) || e) + ' — تأكد أنك مسجّل الدخول' };
  }
}
