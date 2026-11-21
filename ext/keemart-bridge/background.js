/* AFMCO Keemart Bridge — الخلفية.
   action 'status' → نسخة الإضافة وهل بوابة كيمارت مفتوحة.
   action 'board' {today, days} → لوحة الأداء: يفتح صفحة اللوحة في تبويب خلفي، يلتقط اليوم، ثم يختار الأيام الأقدم من التقويم.
   action 'pull' {days:['YYYY-MM-DD',…]} → يسحب تقرير الدوام (Attendance Statistics) لكل يوم
   من داخل صفحة بوابة كيمارت نفسها (بجلسة المستخدم) ويعيد صفوفاً جاهزة لـ keemart_upsert('attendance'). */
var PORTAL = 'https://courier-keemart.mykeeta.com/';
var PAGE = PORTAL + 'courier/capacityManager/attendanceReport.html';
var BOARD = PORTAL + 'jimu/data/osg-partner-delivery?region=SA&locale=en&supplier_id_list[0]=59&from=courier-keemart';

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
  if (msg.action === 'board') return board(msg.payload || {});
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

async function board(p) {
  var today = String(p.today || ''), ok = function (d) { return /^\d{4}-\d{2}-\d{2}$/.test(d); };
  if (!ok(today)) throw new Error('تاريخ اليوم ناقص');
  var days = (p.days || []).filter(function (d, i, a) { return ok(d) && d <= today && a.indexOf(d) === i; }).sort().reverse().slice(0, 12);
  if (!days.length) return { days: {}, errors: {} };
  days = days.slice(0, 6);
  var tab = await chrome.tabs.create({ url: BOARD + '#afmc_bg', active: false }), out, mode = 'tab';
  var run = async function (firstWait) {
    tab = await waitLoaded(tab.id);
    if (String(tab.url || '').indexOf(PORTAL + 'jimu/') !== 0) throw new Error('سجّل الدخول في بوابة كيمارت ثم أعد المحاولة');
    var res = await chrome.scripting.executeScript({ target: { tabId: tab.id }, world: 'MAIN', func: kmBoardInPage, args: [today, days, firstWait] });
    return res && res[0] && res[0].result;
  };
  try {
    out = await run(35);
    if (out && out.noFirst) {            /* الصفحة لم تحمّل شيئاً وهي مخفية: تُعاد في نافذة صغيرة ظاهرة بلا تركيز */
      mode = 'window';
      await chrome.windows.create({ tabId: tab.id, focused: false, width: 1100, height: 820 });
      await chrome.tabs.reload(tab.id); await sleep(1500);
      out = await run(50);
    }
  } finally { try { await chrome.tabs.remove(tab.id); } catch (e) {} }
  if (!out) throw new Error('لوحة الأداء لم تُرجع بيانات');
  if (out.error) throw new Error(out.error);
  if (out.noFirst) throw new Error('لوحة الأداء لم تحمّل بيانات اليوم (' + (out.vis || '') + ')');
  out.mode = mode;
  return out;
}

/* يعمل داخل صفحة لوحة الأداء: يقرأ ما التقطه board-hook.js من ردود الصفحة نفسها، ويختار التاريخ من تقويم الصفحة. */
async function kmBoardInPage(today, days, firstWait) {
  try {
    var cap = window.__afmcKmCap; if (!cap) return { error: 'لوحة الأداء: الالتقاط غير مفعّل — أعد تحميل الإضافة' };
    var wait = function (ms) { return new Promise(function (r) { setTimeout(r, ms); }); };
    var MON = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
    var ins = function () { return Array.prototype.slice.call(document.querySelectorAll('.ant-picker-range input')); };
    var match = function (key) { return function (c) { try { var b = JSON.parse(c.b).customParams; return b.dateType === 'day' && b.referenceDatekey[0] === key && b.referenceDatekey[1] === key; } catch (e) { return false; } }; };
    var t0 = Date.now();
    var settle = async function (key, n0, maxMs) {       /* آخر ردّ لليوم بعد أن تهدأ الصفحة 3 ثوان — بالوقت الفعلي لا بعدد المحاولات */
      var end = Date.now() + maxMs, last = null, since = 0;
      while (Date.now() < end) {
        await wait(400);
        var c = cap.slice(n0).filter(match(key)), cu = c.length ? c[c.length - 1] : null;
        if (cu !== last) { last = cu; since = Date.now(); } else if (cu && Date.now() - since >= 3000) break;
      }
      if (!last) return null;
      var r = JSON.parse(last.r); if (r.code !== 0) throw new Error('code ' + r.code);
      return (r.data && r.data.body) || [];
    };
    var num = function (v) { if (v == null || v === '' || v === 'null' || v === '--') return null; var n = Number(v); return isFinite(n) ? n : null; };
    var pct = function (v) { var n = num(v); return n == null ? null : Math.round(n * 10000) / 100; };
    var r2 = function (v) { var n = num(v); return n == null ? null : Math.round(n * 100) / 100; };
    var map = function (day, rows) {
      var best = {};
      rows.forEach(function (x) { var k = String(x.rider_id || ''); if (!k) return;
        if (!best[k] || (num(x.transport_end_order_num) || 0) > (num(best[k].transport_end_order_num) || 0)) best[k] = x; });
      return Object.keys(best).map(function (k) {
        var x = best[k], c = /^([A-Za-z][A-Za-z ]*?)\d/.exec(String(x.poi_name || ''));
        return { work_date: day, captain_id: k, captain_name: x.rider_name || null, branch: x.poi_name || null, city: c ? c[1].trim() : null,
          orders_pushed: num(x.transport_push_order_num), orders_delivered: num(x.transport_end_order_num),
          late_orders: num(x.transport_supplier_timeout_order_num), major_late: num(x.transport_supplier_serious_timeout_order_num),
          ont_rate: pct(x.transport_supplier_ontime_ratio), major_rate: pct(x.transport_supplier_serious_timeout_ratio),
          pre_marked: num(x.transport_supplier_illegal_point_finish_num),
          neg_orders: num(x.transport_supplier_delivery_bad_comment_order_num), pos_orders: num(x.transport_supplier_delivery_good_comment_order_num),
          r15: pct(x.fast_15min_delivery_order_ratio), r20: pct(x.fast_20min_delivery_order_ratio), r30: pct(x.fast_30min_delivery_order_ratio),
          d_pickup: r2(x.order_avg_rider_take_interval), d_transit: r2(x.order_avg_rider_transport_interval),
          d_return: r2(x.avg_rider_trip_rider_back_interval), d_deliver: r2(x.order_avg_push_trans_package_interval),
          valid_order_min: r2(x.online_delivery_total_interval) };
      });
    };
    var out = { days: {}, errors: {} };
    for (var endIns = Date.now() + 20000; ins().length < 2 && Date.now() < endIns;) await wait(400);          /* الصفحة تبني نفسها */
    if (ins().length < 2) return { noFirst: true, vis: 'no picker' };
    /* اليوم: الصفحة تفتح عليه تلقائياً — يُؤخذ من ردّها الأول */
    var tKey = today.split('-').join(''), first = await settle(tKey, 0, (firstWait || 35) * 1000);
    if (!first) return { noFirst: true, vis: document.visibilityState };
    if (days.indexOf(today) >= 0) { if (first) out.days[today] = map(today, first); else out.errors[today] = 'لم يصل ردّ اليوم'; }
    /* الأيام الأقدم: تنازلياً، باختيار اليوم مرتين من تقويم الصفحة (بداية ثم نهاية) */
    for (var i = 0; i < days.length; i++) {
      var day = days[i]; if (day === today) continue;
      if (Date.now() - t0 > 150000) { out.errors[day] = 'time budget'; break; }
      try {
        var p = day.split('-'), want = p[2] + ' ' + MON[+p[1] - 1] + ' ' + p[0], key = p.join(''), n0 = cap.length;
        var q = 'td.ant-picker-cell-in-view[title="' + day + '"]', cell = function () { var a = document.querySelectorAll(q); return a.length ? a[0] : null; };
        if (!(ins()[0].value === want && ins()[1].value === want)) {
          var x = ins()[0];
          x.dispatchEvent(new MouseEvent('mousedown', { bubbles: true })); x.focus(); x.click(); await wait(700);
          var td = cell();
          for (var s = 0; s < 3 && !td; s++) { var pv = document.querySelector('.ant-picker-header-prev-btn'); if (!pv) break; pv.click(); await wait(300); td = cell(); }
          if (!td) { out.errors[day] = 'اليوم غير ظاهر في التقويم'; break; }
          if (/disabled/.test(td.className)) { out.errors[day] = 'اليوم غير متاح في اللوحة'; continue; }
          td.click(); await wait(700); td = cell();
          for (s = 0; s < 3 && !td; s++) { pv = document.querySelector('.ant-picker-header-prev-btn'); if (!pv) break; pv.click(); await wait(300); td = cell(); }
          if (td) td.click(); await wait(700);
          if (!(ins()[0].value === want && ins()[1].value === want)) { out.errors[day] = 'تعذّر ضبط التاريخ (' + ins()[0].value + ' → ' + ins()[1].value + ')'; break; }
        }
        var rows = await settle(key, n0, 25000);
        if (!rows) { var all = cap.filter(match(key)); rows = all.length ? ((JSON.parse(all[all.length - 1].r).data || {}).body || []) : null; }
        if (rows) out.days[day] = map(day, rows); else out.errors[day] = 'لم يصل ردّ';
      } catch (e) { out.errors[day] = String((e && e.message) || e); }
    }
    return out;
  } catch (e) {
    return { error: 'لوحة الأداء: ' + ((e && e.message) || e) };
  }
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
