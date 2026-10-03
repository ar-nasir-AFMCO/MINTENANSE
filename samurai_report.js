(function(){
  /* ═══════ تقرير ساموراي — أداة مستقلّة خارج المنصّة ═══════
     تعمل داخل صفحة suppliers.samurai.delivery بجلستك، تقرأ فقط،
     ولا تكتب في ساموراي ولا في قاعدة المنصّة. تُغلق بزر «إغلاق». */
  if (location.host.indexOf('samurai') < 0){ alert('افتح صفحة ساموراي أولاً ثم اضغط الزر'); return; }
  if (document.getElementById('smrRoot')){ document.getElementById('smrRoot').remove(); }
  var T = localStorage.getItem('token') || '';
  if (!T){ alert('ما لقيت جلسة ساموراي — سجّل الدخول ثم أعد المحاولة'); return; }
  var API = 'https://api.samurai.delivery/supplier/';
  var H = { Accept:'application/vnd.api+json', Authorization:(T.indexOf('Bearer')===0?T:'Bearer '+T) };
  var S = { month: new Date(Date.now()-864e5).toISOString().slice(0,7), perf:false, busy:false, data:null, log:'' };

  function esc(s){ return String(s==null?'':s).replace(/[&<>"]/g,function(c){return ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'})[c];}); }
  function n1(v){ var x=parseFloat(v); return isNaN(x)?0:Math.round(x*10)/10; }
  function fmt(v){ return (+v||0).toLocaleString('en-US'); }
  function mEnd(m){ var p=m.split('-'); return new Date(Date.UTC(+p[0],+p[1],0)).getUTCDate(); }
  function pad(n){ return String(n).padStart(2,'0'); }

  function J(u){ return fetch(u,{headers:H}).then(function(r){ if(!r.ok) throw new Error(u.split('?')[0].split('/supplier/')[1]+' HTTP '+r.status); return r.json(); }); }
  function pageAll(res, extra, cap){
    var out=[], n=1;
    function step(){
      return J(API+res+'?page%5Bnumber%5D='+n+'&page%5Bsize%5D=100'+(extra||'')).then(function(j){
        var d=(j&&j.data)||[]; out=out.concat(d);
        if (d.length===100 && out.length<(cap||6000)){ n++; return step(); }
        return out;
      });
    }
    return step();
  }
  function note(t){ S.log=t; var el=document.getElementById('smrLog'); if(el) el.textContent=t; }

  /* ---------- السحب ---------- */
  function load(){
    S.busy=true; paint(); note('… الكباتن');
    var m=S.month, from=m+'-01', to=m+'-'+pad(mEnd(m));
    var D={};
    return pageAll('captains').then(function(c){ D.caps=c; note('… فئات الحظر'); return pageAll('suspension_categories',null,500); })
      .then(function(c){ D.cats=c; note('… سجلات الحظر'); return pageAll('suspension_reasons',null,4000); })
      .then(function(c){ D.susp=c; note('… أنواع المخالفات'); return pageAll('note_type_settings',null,1000); })
      .then(function(c){ D.types=c; note('… الملاحظات والخصومات'); return notesUntil(from); })
      .then(function(c){ D.notes=c; if(!S.perf){ return null; } return perfMonth(m); })
      .then(function(p){ D.perf=p||null; D.range=from+' → '+to; S.data=D; S.busy=false; note(''); paint(); })
      .catch(function(e){ S.busy=false; note('تعذّر: '+e.message); paint(); });
  }
  /* الملاحظات مرتّبة تنازلياً — نقف عند أول صفحة أقدم من بداية الشهر */
  function notesUntil(from){
    var out=[], n=1;
    function step(){
      return J(API+'notes?page%5Bnumber%5D='+n+'&page%5Bsize%5D=100&sort=-createdAt').then(function(j){
        var d=(j&&j.data)||[]; out=out.concat(d);
        var last=d.length?String(d[d.length-1].attributes.createdAt||''):'';
        if (d.length===100 && last>=from && n<60){ n++; note('… الملاحظات ('+out.length+')'); return step(); }
        return out;
      });
    }
    return step();
  }
  /* الأداء: يوماً بيوم بنفس فلتر ساموراي (fromDate/toDate) */
  function perfMonth(m){
    var days=mEnd(m), acc={}, i=1;
    function day(){
      if (i>days) return Promise.resolve(acc);
      var d=m+'-'+pad(i);
      var prev=new Date(Date.parse(d+'T00:00:00Z')-864e5).toISOString().slice(0,10);
      var flt=encodeURIComponent("fromDate=='"+prev+"T21:00:00Z';toDate=='"+d+"T20:59:59Z'");
      note('… الأداء '+d);
      var n=1;
      function pg(){
        return J(API+'tracking/captain_performance_dashboard?pageNumber='+n+'&pageSize=100&sort=id&filter='+flt)
          .then(function(j){
            var rows=(j&&j.data)||[];
            rows.forEach(function(x){
              var a=x.attributes||x, id=String(a.captainId||a.id||'');
              var o=acc[id]=acc[id]||{d:0,h:0,o:0};
              o.d++; o.h+=parseFloat(a.totalWorkingHoursCount||0)||0; o.o+=parseInt(a.totalDeliveredOrdersCount||0,10)||0;
            });
            if (rows.length===100 && n<20){ n++; return pg(); }
            return null;
          });
      }
      return pg().then(function(){ i++; return day(); }).catch(function(){ i++; return day(); });
    }
    return day();
  }

  /* ---------- التجميع ---------- */
  function agg(){
    var D=S.data, m=S.month, from=m+'-01';
    var cat={}; (D.cats||[]).forEach(function(c){ cat[c.id]=(c.attributes.textAr||c.attributes.textEn||'').trim(); });
    var typ={}; (D.types||[]).forEach(function(t){ typ[t.id]=(t.attributes.displayableName||t.attributes.noteType||'').trim(); });
    var cap={};
    (D.caps||[]).forEach(function(c){ var a=c.attributes;
      cap[String(c.id)]={ id:String(c.id), iq:a.idNumber||'', nm:a.name||'', mob:a.mobileNumber||'',
        st:a.status||'', sus:!!a.suspended, ws:a.workingStatus||'', last:(a.lastDeliveredOrderAt||'').slice(0,10),
        v:0, amtP:0, amtN:0, vlist:{}, sN:0, sWhy:[], d:0, h:0, o:0 }; });
    function row(id){ id=String(id); return cap[id] || (cap[id]={ id:id, iq:'', nm:'(خارج القائمة)', mob:'', st:'', sus:false, ws:'', last:'', v:0, amtP:0, amtN:0, vlist:{}, sN:0, sWhy:[], d:0, h:0, o:0 }); }
    /* الموجب خصم فعلي، والسالب إلغاء أو ردّ — يُحسبان منفصلين ولا يُدمجان */
    var nIn=0, amtP=0, amtN=0, nP=0, nN=0;
    (D.notes||[]).forEach(function(x){ var a=x.attributes; var at=String(a.createdAt||'').slice(0,7);
      if (at!==m) return; nIn++;
      var r=row(a.ownerId); r.v++; var v=parseFloat(a.transactionAmount)||0;
      if (v>0){ r.amtP+=v; amtP+=v; nP++; } else if (v<0){ r.amtN+=-v; amtN+=-v; nN++; }
      var t=typ[a.noteTypeSettingId]||'مخالفة'; r.vlist[t]=(r.vlist[t]||0)+1; });
    var sN=0;
    (D.susp||[]).forEach(function(x){ var a=x.attributes; var r=row(a.ownerId);
      if (a.action==='SUSPEND'){ r.sN++; sN++; var c=cat[a.categoryId]; if(c && r.sWhy.indexOf(c)<0) r.sWhy.push(c); } });
    if (D.perf) Object.keys(D.perf).forEach(function(id){ var p=D.perf[id], r=row(id); r.d=p.d; r.h=n1(p.h); r.o=p.o; });
    var rows=Object.keys(cap).map(function(k){ return cap[k]; })
      .sort(function(a,b){ return (b.amtP-a.amtP) || (b.v-a.v) || (b.o-a.o); });
    var r2=function(x){ return Math.round(x*100)/100; };
    return { rows:rows, kpi:{ caps:(D.caps||[]).length, susNow:(D.caps||[]).filter(function(c){return c.attributes.suspended;}).length,
      susM:sN, notes:nIn, amtP:r2(amtP), amtN:r2(amtN), net:r2(amtP-amtN), nP:nP, nN:nN,
      cats:(D.cats||[]).length, range:D.range } };
  }

  /* ---------- تصدير ---------- */
  function csv(name, head, rows){
    var q=function(v){ var x=String(v==null?'':v); return /[",\n]/.test(x)?'"'+x.replace(/"/g,'""')+'"':x; };
    var blob=new Blob(['﻿'+head.map(q).join(',')+'\n'+rows.map(function(r){return r.map(q).join(',');}).join('\n')],{type:'text/csv;charset=utf-8'});
    var u=URL.createObjectURL(blob), a=document.createElement('a'); a.href=u; a.download=name;
    document.body.appendChild(a); a.click(); setTimeout(function(){ URL.revokeObjectURL(u); a.remove(); },4000);
  }

  /* ---------- الواجهة ---------- */
  var CSS = '#smrRoot{position:fixed;inset:0;z-index:2147483000;overflow:auto;direction:rtl;background:#f9f9f7;color:#0b0b0b;'
    + 'font-family:system-ui,-apple-system,"Segoe UI",Tahoma,sans-serif}'
    + '#smrRoot *{box-sizing:border-box}'
    + '#smrRoot .bar{position:sticky;top:0;background:#f9f9f7;border-bottom:1px solid #e1e0d9;display:flex;gap:8px;align-items:center;flex-wrap:wrap;padding:12px 16px;z-index:2}'
    + '#smrRoot h1{font-size:18px;margin:0;font-weight:800}'
    + '#smrRoot button{font:inherit;font-size:13px;border:1px solid #c3c2b7;background:#fff;color:#0b0b0b;border-radius:9px;padding:7px 12px;cursor:pointer}'
    + '#smrRoot button.p{background:#0b0b0b;color:#fff;border-color:#0b0b0b}'
    + '#smrRoot input[type=text]{font:inherit;font-size:13px;border:1px solid #c3c2b7;border-radius:9px;padding:6px 10px;width:110px;text-align:center}'
    + '#smrRoot .wrap{max-width:1250px;margin:0 auto;padding:16px}'
    + '#smrRoot .tiles{display:grid;grid-template-columns:repeat(auto-fit,minmax(150px,1fr));gap:10px;margin:14px 0}'
    + '#smrRoot .tile{background:#fff;border:1px solid #e1e0d9;border-radius:12px;padding:12px 14px}'
    + '#smrRoot .tile b{display:block;font-size:24px;letter-spacing:-.4px}'
    + '#smrRoot .tile span{font-size:12px;color:#898781}'
    + '#smrRoot .card{background:#fff;border:1px solid #e1e0d9;border-radius:12px;padding:14px;margin:12px 0}'
    + '#smrRoot table{width:100%;border-collapse:collapse;font-size:13px}'
    + '#smrRoot th,#smrRoot td{padding:7px 8px;text-align:right;border-bottom:1px solid #e1e0d9;white-space:nowrap}'
    + '#smrRoot th{color:#52514e;font-weight:600;font-size:12px;position:sticky;top:0;background:#fff}'
    + '#smrRoot .scroll{max-height:60vh;overflow:auto}'
    + '#smrRoot .num{font-variant-numeric:tabular-nums}'
    + '#smrRoot .sus{background:#fbecea;color:#a3372b;border-radius:999px;padding:2px 8px;font-size:11px}'
    + '#smrRoot .ok{background:#eaf5ef;color:#186b4a;border-radius:999px;padding:2px 8px;font-size:11px}'
    + '#smrRoot .note{font-size:12px;color:#898781;line-height:1.7}';

  function paint(){
    var root=document.getElementById('smrRoot'); if(!root) return;
    var body=root.querySelector('#smrBody');
    if (S.busy){ body.innerHTML='<div class="card note">… يسحب من ساموراي — لا تغلق الصفحة</div>'; return; }
    if (!S.data){ body.innerHTML='<div class="card note">اختر الشهر ثم اضغط «اسحب».</div>'; return; }
    var A=agg(), k=A.kpi;
    var h='<div class="tiles">'
      + [['كباتن',fmt(k.caps)],['موقوف الآن',fmt(k.susNow)],['حالات حظر في الشهر',fmt(k.susM)],
         ['مخالفات الشهر',fmt(k.notes)],['خصومات (موجب)',fmt(k.amtP)+' ر.س'],['إلغاءات (سالب)',fmt(k.amtN)+' ر.س'],
         ['الصافي',fmt(k.net)+' ر.س'],['فئات الأسباب',fmt(k.cats)]]
        .map(function(t){ return '<div class="tile"><b class="num">'+esc(t[1])+'</b><span>'+esc(t[0])+'</span></div>'; }).join('')
      + '</div>';
    h += '<div class="card"><div style="display:flex;gap:8px;align-items:center;flex-wrap:wrap;margin-bottom:10px">'
      + '<b>الكباتن — '+esc(S.month)+'</b><span class="note">'+esc(k.range||'')+'</span>'
      + '<span style="flex:1"></span><button id="smrCsv">⬇ Excel (CSV)</button><button id="smrJson">⬇ JSON</button></div>'
      + '<div class="scroll"><table><thead><tr>'
      + ['الإقامة','الاسم','الجوال','الحالة','موقوف؟','مخالفات','خصومات +','إلغاءات −','الصافي','أنواع المخالفات','حالات حظر','أسباب الحظر']
          .concat(S.perf?['أيام','ساعات','طلبات']:[])
          .map(function(t){ return '<th>'+t+'</th>'; }).join('')
      + '</tr></thead><tbody>'
      + A.rows.map(function(r){
          var vt=Object.keys(r.vlist).map(function(t){ return t+' ('+r.vlist[t]+')'; }).join(' · ');
          return '<tr><td class="num">'+esc(r.iq||'—')+'</td><td>'+esc(r.nm)+'</td><td class="num">'+esc(r.mob)+'</td>'
            + '<td>'+esc(r.st)+'</td><td>'+(r.sus?'<span class="sus">موقوف</span>':'<span class="ok">يعمل</span>')+'</td>'
            + '<td class="num">'+(r.v||'')+'</td>'
            + '<td class="num">'+(r.amtP?fmt(Math.round(r.amtP*100)/100):'')+'</td>'
            + '<td class="num">'+(r.amtN?'−'+fmt(Math.round(r.amtN*100)/100):'')+'</td>'
            + '<td class="num">'+((r.amtP||r.amtN)?fmt(Math.round((r.amtP-r.amtN)*100)/100):'')+'</td>'
            + '<td>'+esc(vt)+'</td><td class="num">'+(r.sN||'')+'</td><td>'+esc(r.sWhy.join(' · '))+'</td>'
            + (S.perf?('<td class="num">'+(r.d||'')+'</td><td class="num">'+(r.h||'')+'</td><td class="num">'+(r.o||'')+'</td>'):'')
            + '</tr>';
        }).join('')
      + '</tbody></table></div>'
      + '<div class="note" style="margin-top:8px">«خصومات +» المبالغ الموجبة، و«إلغاءات −» المبالغ السالبة (ردّ أو تصحيح)، والصافي الفرق بينهما — لا تُدمج. الصفوف مرتّبة بأكبر خصومات موجبة. «موقوف؟» حالته الآن في ساموراي، و«حالات حظر» عدد مرات الإيقاف المسجّلة (كل الفترة، لا الشهر وحده). الإقامة هي مفتاح الربط مع وثيق.</div></div>';
    body.innerHTML=h;
    body.querySelector('#smrCsv').onclick=function(){
      var A2=agg();
      csv('ساموراي-'+S.month+'.csv',
        ['الإقامة','الاسم','الجوال','الحالة','موقوف','مخالفات','خصومات موجبة','إلغاءات سالبة','الصافي','أنواع المخالفات','حالات حظر','أسباب الحظر','أيام','ساعات','طلبات'],
        A2.rows.map(function(r){ return [r.iq,r.nm,r.mob,r.st,r.sus?'نعم':'لا',r.v,
          Math.round(r.amtP*100)/100, Math.round(r.amtN*100)/100, Math.round((r.amtP-r.amtN)*100)/100,
          Object.keys(r.vlist).map(function(t){return t+' ('+r.vlist[t]+')';}).join(' · '),r.sN,r.sWhy.join(' · '),r.d,r.h,r.o]; }));
    };
    body.querySelector('#smrJson').onclick=function(){
      var blob=new Blob([JSON.stringify({month:S.month,rows:agg().rows},null,1)],{type:'application/json'});
      var u=URL.createObjectURL(blob), a=document.createElement('a'); a.href=u; a.download='ساموراي-'+S.month+'.json';
      document.body.appendChild(a); a.click(); setTimeout(function(){ URL.revokeObjectURL(u); a.remove(); },4000);
    };
  }

  var st=document.createElement('style'); st.textContent=CSS; document.head.appendChild(st);
  var root=document.createElement('div'); root.id='smrRoot';
  root.innerHTML='<div class="bar"><h1>تقرير ساموراي</h1>'
    + '<input type="text" id="smrM" value="'+S.month+'" placeholder="YYYY-MM">'
    + '<label class="note"><input type="checkbox" id="smrPerf"> اسحب الأداء أيضاً (أبطأ)</label>'
    + '<button class="p" id="smrGo">اسحب</button><span class="note" id="smrLog"></span>'
    + '<span style="flex:1"></span><button id="smrX">إغلاق</button></div>'
    + '<div class="wrap"><div id="smrBody"></div>'
    + '<div class="note" style="margin:18px 0 40px">أداة مستقلّة خارج المنصّة — تقرأ من ساموراي فقط ولا تكتب شيئاً، لا هنا ولا في قاعدة المنصّة.</div></div>';
  document.body.appendChild(root);
  root.querySelector('#smrX').onclick=function(){ root.remove(); st.remove(); };
  root.querySelector('#smrGo').onclick=function(){
    var v=root.querySelector('#smrM').value.trim();
    if(!/^\d{4}-\d{2}$/.test(v)){ alert('اكتب الشهر هكذا: 2026-09'); return; }
    S.month=v; S.perf=root.querySelector('#smrPerf').checked; load();
  };
  paint();
})();
