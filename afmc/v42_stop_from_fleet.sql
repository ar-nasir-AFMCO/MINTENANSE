-- =====================================================================
-- v42 — الإيقاف يجي من الأسطول وحده
--
-- القاعدة: في وثيق لا أحد يوقف موظفاً مباشرة (إلا مدير النظام، ويُسجَّل
-- تجاوزه). المستخدم يرسل «طلب إيقاف» يصل صندوق الأسطول.
-- الإيقاف الفعلي يقع بطريقتين، كلتاهما من الأسطول:
--   ١) اعتماد طلب الإيقاف  ← يوقف المندوب ويستلم مركبته معاً
--   ٢) تسليم نهائي للمركبة بسبب «ترك العمل» ← يوقف مباشرة
--      (تسليم الصيانة أو إعادة التخصيص لا يوقف)
--
-- ما وجدناه قبل البناء (لا تبنِ على غيره):
--   • vehicle_assignments.release_reason غير مستعمل إطلاقاً (٠ من ٢٣٩).
--     أسباب التسليم الحقيقية في vehicles.data->history[].reason:
--     إعادة تخصيص ٢٦٠ · انتهاء العقد ٣٦ · أخرى ٢١ · عطل المركبة ١٩ ·
--     طلب السائق ٩ · نقل لمشروع آخر ٢ · طلب الإدارة ١
--   • لا يوجد سبب اسمه «ترك العمل» — فأضفناه سبباً جديداً صريحاً بدل
--     إعادة تفسير أسباب قديمة، حتى لا يتغيّر معنى أي سجل ماضٍ.
-- =====================================================================

-- ---------- ١) الحارس: من أين يجوز الإيقاف ----------
-- يُسمح بالإيقاف من: مسار الأسطول · مدير النظام · أو من خارج التطبيق أصلاً
-- (صيانة قاعدة البيانات والاستيرادات، حيث لا يوجد مستخدم تطبيق: auth.uid() فارغ).
-- طلبات التطبيق كلها تحمل JWT، فالحارس يغطّي المستخدمين لا الخلفية.
create or replace function public.stop_is_allowed() returns boolean
language sql stable as $$
  select coalesce(current_setting('afmc.stop_src', true), '') = 'fleet'
      or public.has_role('admin')
      or auth.uid() is null;
$$;

create or replace function public.trg_guard_stop() returns trigger
language plpgsql set search_path = public as $$
declare stops text[] := array['stopped','salary_only','suspended','terminated'];
begin
  if old.status::text = 'active' and new.status::text = any(stops)
     and not public.stop_is_allowed() then
    raise exception 'الإيقاف يتم من الأسطول فقط. أرسل «طلب إيقاف» من كرت المندوب وسيصل صندوق الأسطول.'
      using errcode = 'check_violation';
  end if;
  /* تجاوز المدير يُعلَّم في السجل */
  if old.status::text = 'active' and new.status::text = any(stops)
     and coalesce(current_setting('afmc.stop_src', true),'') <> 'fleet'
     and auth.uid() is not null then
    new.data := coalesce(new.data,'{}'::jsonb)
      || jsonb_build_object('stop_override', jsonb_build_object(
           'by', auth.uid(), 'at', now(), 'note', 'إيقاف مباشر من مدير النظام'));
  end if;
  return new;
end $$;
drop trigger if exists guard_stop on public.employees;
create trigger guard_stop before update of status on public.employees
  for each row execute function public.trg_guard_stop();

-- ---------- ٢) طلب الإيقاف ----------
alter table public.inbox_items drop constraint if exists inbox_items_kind_check;
alter table public.inbox_items add constraint inbox_items_kind_check
  check (kind in ('vehicle_request','account_request','message','task','stop_request'));
create unique index if not exists inbox_stopreq_uq
  on public.inbox_items ((ref->>'employee_id'))
  where kind = 'stop_request' and status in ('new','in_progress');

create or replace function public.stop_request_create(p_employee uuid, p_reason text)
returns jsonb language plpgsql security definer set search_path = public as $$
declare e public.employees; nm text; plates text;
begin
  select * into e from public.employees where id = p_employee and deleted_at is null;
  if e.id is null then raise exception 'الموظف غير موجود'; end if;
  if not (public.can_do('edit') and public.can_access_project(e.project_code)) then
    raise exception 'هذا الموظف خارج نطاق صلاحيتك';
  end if;
  if e.status::text <> 'active' then raise exception 'الموظف ليس على رأس العمل أصلاً'; end if;
  if nullif(btrim(p_reason),'') is null then raise exception 'اكتب سبب الإيقاف'; end if;

  select string_agg(v.plate_no, ' , ') into plates
    from public.vehicle_assignments a join public.vehicles v on v.id = a.vehicle_id
   where a.employee_id = e.id and a.released_at is null and v.deleted_at is null;
  select coalesce(full_name, email) into nm from public.profiles where id = auth.uid();

  insert into public.inbox_items(kind, system, title, body, ref, project, created_by_name, status)
  values ('stop_request', 'fleet',
          'طلب إيقاف: ' || coalesce(e.full_name_ar, e.full_name_en, e.iqama),
          btrim(p_reason), jsonb_build_object('employee_id', e.id, 'iqama', e.iqama,
            'name', coalesce(e.full_name_ar, e.full_name_en), 'project', e.project_code,
            'plates', coalesce(plates,'')),
          e.project_code, nm, 'new')
  returning to_jsonb(inbox_items.*) into e.data;
  return jsonb_build_object('ok', true, 'plates', coalesce(plates,''));
exception when unique_violation then
  raise exception 'يوجد طلب إيقاف مفتوح لهذا المندوب بالفعل';
end $$;
grant execute on function public.stop_request_create(uuid, text) to authenticated;

-- اعتماد/رفض الطلب — من الأسطول
create or replace function public.stop_request_decide(p_id uuid, p_approve boolean, p_note text default null)
returns jsonb language plpgsql security definer set search_path = public as $$
declare it public.inbox_items; emp uuid; n_rel int := 0;
begin
  select * into it from public.inbox_items where id = p_id and kind = 'stop_request';
  if it.id is null then raise exception 'الطلب غير موجود'; end if;
  if it.status in ('done','rejected') then raise exception 'الطلب مُغلق مسبقاً'; end if;
  if not public.can_do('assign') then raise exception 'الاعتماد من الأسطول فقط'; end if;

  emp := (it.ref->>'employee_id')::uuid;
  if p_approve then
    perform set_config('afmc.stop_src', 'fleet', true);
    update public.vehicle_assignments
       set released_at = now(),
           release_reason = coalesce(release_reason, 'ترك العمل — اعتماد طلب إيقاف')
     where employee_id = emp and released_at is null;
    get diagnostics n_rel = row_count;
    update public.employees set status = 'stopped' where id = emp and status = 'active';
    perform public.auto_stop_emp(emp);
  end if;

  update public.inbox_items
     set status = case when p_approve then 'done' else 'rejected' end,
         closed_at = now(), closed_by = auth.uid(),
         close_note = nullif(btrim(p_note),'')
   where id = p_id;
  return jsonb_build_object('ok', true, 'approved', p_approve, 'released', n_rel);
end $$;
grant execute on function public.stop_request_decide(uuid, boolean, text) to authenticated;

-- ---------- ٣) تسليم نهائي بسبب «ترك العمل» يوقف مباشرة ----------
create or replace function public.trg_stop_on_leave() returns trigger
language plpgsql security definer set search_path = public as $$
begin
  if new.released_at is not null and old.released_at is null
     and coalesce(new.release_reason,'') ilike '%ترك العمل%'
     and new.employee_id is not null then
    perform set_config('afmc.stop_src', 'fleet', true);
    update public.employees set status = 'stopped'
     where id = new.employee_id and status = 'active' and deleted_at is null;
    perform public.auto_stop_emp(new.employee_id);
  end if;
  return null;
end $$;
drop trigger if exists stop_on_leave on public.vehicle_assignments;
create trigger stop_on_leave after update of released_at on public.vehicle_assignments
  for each row execute function public.trg_stop_on_leave();

-- ---------- ٤) ملخّص الصباح: من أُوقف أمس ----------
create or replace view public.v_stops_daily as
  select (a.changed_at at time zone 'Asia/Riyadh')::date as work_date,
         a.changed_at, e.iqama, coalesce(e.full_name_ar, e.full_name_en) as name,
         e.project_code,
         a.old_data->>'status' as st_old, a.new_data->>'status' as st_new,
         coalesce(p.email,'(النظام)') as by_email,
         case when a.new_data->'data'->'stop_override' is not null then 'تجاوز مباشر'
              when a.old_data->>'status' = 'active' then 'من الأسطول'
              else 'أثر تلقائي' end as source,
         (select string_agg(distinct v.plate_no, ' , ')
            from public.vehicle_assignments va join public.vehicles v on v.id = va.vehicle_id
           where va.employee_id = e.id and va.assigned_at <= a.changed_at
             and (va.released_at is null or va.released_at >= a.changed_at - interval '2 minutes')) as plates
    from public.audit_log a
    join public.employees e on e.id = a.row_id::uuid
    left join public.profiles p on p.id = a.changed_by
   where a.table_name = 'employees'
     and coalesce(a.old_data->>'status','') is distinct from coalesce(a.new_data->>'status','')
     and a.new_data->>'status' in ('stopped','salary_only','suspended','terminated')
     and a.old_data->>'status' = 'active';
-- لا تُمنح القراءة المباشرة: العرض يقرأ audit_log (للمدير وحده) بحقوق مالكه،
-- فلو مُنح لـ authenticated لصار ثقباً يقرأ به المشرف سجل التدقيق كاملاً.
-- كل قراءة تمرّ بـ stops_export (مفلترة بالمشروع) أو stop_digest.
revoke select on public.v_stops_daily from authenticated;

-- عنصر يومي في الصندوق الموحّد (آمن للتكرار)
create unique index if not exists inbox_digest_uq
  on public.inbox_items ((ref->>'digest_day')) where kind = 'message' and ref ? 'digest_day';

create or replace function public.stop_digest(p_day date default null)
returns jsonb language plpgsql security definer set search_path = public as $$
declare d date := coalesce(p_day, (now() at time zone 'Asia/Riyadh')::date - 1);
        n int; body text;
begin
  if public.my_role() is null then raise exception 'not approved'; end if;
  select count(*), string_agg(name || ' (' || project_code || ')' || coalesce(' — ' || plates, ''), E'\n' order by changed_at)
    into n, body from public.v_stops_daily where work_date = d;
  if coalesce(n,0) = 0 then return jsonb_build_object('ok', true, 'day', d, 'count', 0); end if;
  begin
    insert into public.inbox_items(kind, system, title, body, ref, status, created_by_name)
    values ('message', 'fleet', 'موقوفو يوم ' || d || ' — ' || n || ' مندوب', body,
            jsonb_build_object('digest_day', d::text, 'count', n), 'new', 'ملخّص يومي');
  exception when unique_violation then null;
  end;
  return jsonb_build_object('ok', true, 'day', d, 'count', n);
end $$;
grant execute on function public.stop_digest(date) to authenticated;

-- ---------- ٥) «ترك العمل» كما يكتبه تطبيق الأسطول نفسه ----------
-- تطبيق الأسطول يسجّل التسليم في vehicles.data->history[] (سبب + تاريخ تسليم)،
-- ودالة المزامنة sync_vehicles تُغلق التفويض بسبب عام («Fleet OS: تم إنهاء التسليم»)
-- ولا تنقل سبب التسليم. فلا نعدّل دالة المزامنة (تمسّ كل مركبة) — بل نقرأ التاريخ
-- نفسه: أي تسليم جديد سببه «ترك العمل» يوقف صاحبه. لا يعمل إلا على المضاف حديثاً،
-- فإعادة المزامنة أو تحديث أي حقل آخر لا توقف أحداً مرتين.
create or replace function public.trg_veh_leave_stop() returns trigger
language plpgsql security definer set search_path = public as $$
declare x jsonb; olds text[]; dq text; emp uuid; k text;
begin
  select coalesce(array_agg(public.norm_id(o->>'driver_id') || '|' || coalesce(o->>'date_deliver','')), '{}')
    into olds
    from jsonb_array_elements(coalesce(old.data->'history','[]'::jsonb)) o
   where coalesce(o->>'date_deliver','') <> '' and coalesce(o->>'reason','') ilike '%ترك العمل%';

  for x in select * from jsonb_array_elements(coalesce(new.data->'history','[]'::jsonb)) loop
    if coalesce(x->>'date_deliver','') = '' then continue; end if;
    if coalesce(x->>'reason','') not ilike '%ترك العمل%' then continue; end if;
    dq := public.norm_id(x->>'driver_id');
    if dq is null then continue; end if;
    k := dq || '|' || coalesce(x->>'date_deliver','');
    if k = any(olds) then continue; end if;                 /* ليس تسليماً جديداً */
    select id into emp from public.employees where iqama = dq and deleted_at is null;
    if emp is null then continue; end if;
    perform set_config('afmc.stop_src', 'fleet', true);
    update public.employees set status = 'stopped'
     where id = emp and status = 'active' and deleted_at is null;
    perform public.auto_stop_emp(emp);
  end loop;
  return null;
end $$;
drop trigger if exists veh_leave_stop on public.vehicles;
create trigger veh_leave_stop after update of data on public.vehicles
  for each row execute function public.trg_veh_leave_stop();

-- ---------- ٦) تصدير: «ما غيّره الأسطول فقط» ----------
-- الأيام الماضية تُقرأ من سجل التدقيق. الفلتر الافتراضي يُظهر ما جاء من الأسطول
-- (اعتماد طلب أو تسليم «ترك العمل») ويستبعد التجاوز المباشر والأثر التلقائي.
create or replace function public.stops_export(
  p_from date default null, p_to date default null, p_fleet_only boolean default true)
returns table(work_date date, changed_at timestamptz, iqama text, name text,
              project_code text, st_old text, st_new text, by_email text,
              source text, plates text)
language sql stable security definer set search_path = public as $$
  select s.work_date, s.changed_at, s.iqama, s.name, s.project_code,
         s.st_old, s.st_new, s.by_email, s.source, s.plates
    from public.v_stops_daily s
   where public.my_role() is not null
     and public.can_access_project(s.project_code)
     and s.work_date >= coalesce(p_from, (now() at time zone 'Asia/Riyadh')::date - 30)
     and s.work_date <= coalesce(p_to,   (now() at time zone 'Asia/Riyadh')::date)
     and (not coalesce(p_fleet_only, true) or s.source = 'من الأسطول')
   order by s.changed_at desc;
$$;
grant execute on function public.stops_export(date, date, boolean) to authenticated;

-- ---------- ٧) من يرى ماذا في الصندوق ----------
-- inbox_visible (v14) لا تعرف 'stop_request' ولا الملخّص اليومي، فكان الطلب
-- لا يراه إلا مُرسِله — والأسطول لا يراه أبداً. نضيف الحالتين ونُبقي بقيّة
-- القواعد حرفياً كما هي.
create or replace function public.inbox_visible(i public.inbox_items) returns boolean
language sql stable security definer set search_path = public as $$
  select auth.uid() is not null and public.my_role() is not null and (
       i.created_by = auth.uid() or i.to_user = auth.uid()
    or (i.to_user is null and i.kind = 'vehicle_request' and public.has_system('fleet') and (public.can_do('assign') or public.can_do('manage'))
        and (i.project is null or public.can_access_project(i.project)))
    /* طلب الإيقاف: يراه الأسطول ليعتمده، ومن يقرأ مشروع المندوب */
    or (i.to_user is null and i.kind = 'stop_request'
        and (public.can_do('assign') or public.can_do('manage') or public.can_do('edit'))
        and (i.project is null or public.can_access_project(i.project)))
    /* ملخّص الصباح: لكل من يرى وثيق أو الأسطول */
    or (i.to_user is null and i.kind = 'message' and i.ref ? 'digest_day'
        and (public.has_system('fleet') or public.has_system('watheeq')))
    or (i.to_user is null and i.kind = 'account_request' and public.can_do('manage'))
    or (i.to_user is null and i.kind = 'task' and (i.system is null or i.system = 'admin' and public.can_do('manage') or public.has_system(i.system))
        and (i.project is null or public.can_access_project(i.project))))
$$;
revoke execute on function public.inbox_visible(public.inbox_items) from public, anon;
grant execute on function public.inbox_visible(public.inbox_items) to authenticated;

-- ---------- ٨) العمود و data لا يفترقان أبداً ----------
-- درس v38: الشاشة تقرأ data->>'employment_status' لا العمود status. مسار
-- «تجاوز مدير النظام» الذي أضفناه في (١) كان يحرّك العمود وحده، فيظهر الموقوف
-- «على رأس العمل». نُعيد كتابة الحارس ليوائم data مع أي تغيير للعمود — إيقافاً
-- كان أو إعادة تشغيل — قبل الكتابة، فلا يحتاج أحد أن يتذكّر ذلك بعد اليوم.
create or replace function public.trg_guard_stop() returns trigger
language plpgsql set search_path = public as $$
declare stops text[] := array['stopped','salary_only','suspended','terminated'];
        lbl text;
begin
  if old.status::text = 'active' and new.status::text = any(stops)
     and not public.stop_is_allowed() then
    raise exception 'الإيقاف يتم من الأسطول فقط. أرسل «طلب إيقاف» من كرت المندوب وسيصل صندوق الأسطول.'
      using errcode = 'check_violation';
  end if;

  if new.status::text is distinct from old.status::text then
    lbl := case new.status::text
             when 'active'      then 'على رأس العمل'
             when 'salary_only' then 'متوقف عن العمل'
             when 'stopped'     then 'موقوف'
             when 'suspended'   then 'معلّق'
             when 'terminated'  then 'منتهي الخدمة'
             when 'on_leave'    then 'إجازة'
             else new.status::text end;
    new.data := coalesce(new.data,'{}'::jsonb) || jsonb_build_object(
                  'employment_status', new.status::text,
                  'status_label', lbl,
                  'salary_only', new.status::text = 'salary_only');
  end if;

  /* تجاوز المدير يُعلَّم في السجل */
  if old.status::text = 'active' and new.status::text = any(stops)
     and coalesce(current_setting('afmc.stop_src', true),'') <> 'fleet'
     and auth.uid() is not null then
    new.data := coalesce(new.data,'{}'::jsonb)
      || jsonb_build_object('stop_override', jsonb_build_object(
           'by', auth.uid(), 'at', now(), 'note', 'إيقاف مباشر من مدير النظام'));
  end if;
  return new;
end $$;

-- مواءمة لمرّة واحدة لما تركته المسارات القديمة مختلفاً (لا يمسّ إلا المخالف)
update public.employees e
   set data = coalesce(e.data,'{}'::jsonb) || jsonb_build_object(
                'employment_status', e.status::text,
                'status_label', case e.status::text
                  when 'active' then 'على رأس العمل' when 'salary_only' then 'متوقف عن العمل'
                  when 'stopped' then 'موقوف' when 'suspended' then 'معلّق'
                  when 'terminated' then 'منتهي الخدمة' when 'on_leave' then 'إجازة'
                  else e.status::text end,
                'salary_only', e.status::text = 'salary_only')
 where e.deleted_at is null
   and coalesce(e.data->>'employment_status','') is distinct from e.status::text;
