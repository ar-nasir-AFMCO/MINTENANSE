-- ════════════════════════════════════════════════════════════════════
-- v130 — كيمارت: مراجعة الربط والسحب (مُطبّق على القاعدة 15/11/2026)
--   • keemart_link: بعد الربط يُسجَّل دوام المندوب في وثيق فوراً
--       أول ربط → كل أيامه المخزّنة · نقل لموظف آخر → من اليوم (السابق يبقى لصاحبه)
--   • v_keemart_links: الربط الفعلي من employee_ids (نفس مصدر تسجيل الدوام) لعرضه في المتابعة
--   • keemart_upsert(jsonb) و keemart_replace_day: تسجيل كل دفعة في keemart_pull_log
--     + البحث عن رقم الكابتن من سجلاته السابقة بالاسم قبل اللجوء لـ KMN:
--   • تصحيح بيانات (نُفّذ مباشرة): الرقم 6923 → MD KHADIMUL ISLAM ونقل 80 يوماً من HASAN ALI MOHAMED،
--     و12 ربطاً مؤكداً بملاحظة «مراجعة ربط كيمارت 15/11/2026»
-- ════════════════════════════════════════════════════════════════════

begin;

create or replace function public.keemart_link(p_captain text, p_iqama text, p_note text default null)
 returns jsonb language plpgsql security definer set search_path to 'public' as $function$
declare v_old record; v_from date := (now() at time zone 'Asia/Riyadh')::date; v_emp uuid; v_closed boolean := false;
        v_prev uuid; v_cid text := public.norm_id(p_captain); n_days int := 0;
begin
  if not (public.has_system('keemart') and public.can_do('edit')) then raise exception 'غير مصرّح'; end if;
  select id into v_emp from public.employees where iqama = p_iqama and deleted_at is null;
  if v_emp is null then raise exception 'لا يوجد موظف بهذه الإقامة في وثيق'; end if;
  select * into v_old from public.keemart_links where captain_id = p_captain and to_date is null;
  if found then
    if v_old.iqama <> p_iqama then
      update public.keemart_links set to_date = v_from where id = v_old.id; v_closed := true;
      insert into public.keemart_links (captain_id, iqama, from_date, linked_by, note) values (p_captain, p_iqama, v_from, auth.uid(), p_note);
    end if;
  else
    insert into public.keemart_links (captain_id, iqama, from_date, linked_by, note) values (p_captain, p_iqama, v_from, auth.uid(), p_note);
  end if;
  select employee_id into v_prev from public.employee_ids where platform = 'KEEMART' and external_id = v_cid;
  insert into public.employee_ids (employee_id, platform, external_id, project_code, start_date)
  values (v_emp, 'KEEMART', v_cid, 'KEEMART', v_from)
  on conflict (platform, external_id) do update set employee_id = excluded.employee_id, project_code = excluded.project_code,
    start_date = excluded.start_date, end_date = null;
  if v_prev is null or v_prev = v_emp then
    update public.keemart_captain_days set pulled_at = pulled_at where public.norm_id(captain_id) = v_cid;
  else
    update public.keemart_captain_days set pulled_at = pulled_at where public.norm_id(captain_id) = v_cid and work_date >= v_from;
  end if;
  get diagnostics n_days = row_count;
  return jsonb_build_object('ok', true, 'from', v_from, 'closed_prev', v_closed, 'days_bridged', n_days);
end $function$;

create or replace view public.v_keemart_links with (security_invoker = true) as
  select ei.external_id as captain_id, e.iqama, coalesce(e.full_name_ar, e.full_name_en) as name, ei.start_date as from_date
    from public.employee_ids ei join public.employees e on e.id = ei.employee_id
   where ei.platform = 'KEEMART' and ei.end_date is null;
grant select on public.v_keemart_links to authenticated;

do $do$
declare d text; a text; b text;
begin
  d := pg_get_functiondef('public.keemart_upsert(jsonb)'::regprocedure);
  if position('keemart_pull_log' in d) = 0 then
    a := 'return json_build_object(''ok'', true, ''inserted'', n_ins';
    d := replace(d, a, 'insert into public.keemart_pull_log(kind, rows_in, ok, by_user) values (''board'', n_ins + n_upd, true, auth.uid());' || chr(10) || '  ' || a);
    b := 'if v_cid is null then v_cid := ''KMN:'' || lower(btrim(v_name)); end if;';
    d := replace(d, b, 'if v_cid is null then select captain_id into v_cid from public.keemart_captain_days where lower(btrim(captain_name)) = lower(v_name) and captain_id not like ''KMN:%'' order by work_date desc limit 1; end if;' || chr(10) || '    ' || b);
    execute d;
  end if;
  d := pg_get_functiondef('public.keemart_replace_day(date,jsonb)'::regprocedure);
  if position('keemart_pull_log' in d) = 0 then
    a := 'return json_build_object(''ok'',true,''deleted'',n_del';
    d := replace(d, a, 'insert into public.keemart_pull_log(kind, rows_in, ok, by_user, from_date, to_date) values (''board'', n_ins + n_upd, true, auth.uid(), p_date, p_date);' || chr(10) || '  ' || a);
    b := 'if v_cid is null then v_cid := ''KMN:''||lower(btrim(v_name)); end if;';
    d := replace(d, b, 'if v_cid is null then select captain_id into v_cid from public.keemart_captain_days where lower(btrim(captain_name)) = lower(v_name) and captain_id not like ''KMN:%'' order by work_date desc limit 1; end if;' || chr(10) || '    ' || b);
    execute d;
  end if;
end $do$;

commit;
