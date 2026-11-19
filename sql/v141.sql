-- v141 — صوت الى وثيق: التعديل المعتمد يظهر في وثيق + المشروع قائمة + تصحيح المعتمد + دوام نينجا المباشر
--  (1) السبب: الاعتماد كان يكتب عمودي phone/project_code فقط ووثيق يقرأ data (mobile/project/iban)،
--      فلا يظهر التعديل وأي حفظ لاحق من وثيق يرجع القديم. الآن يكتب في data والعمود معا ويختم edited_at.
--      الحساب البنكي = الآيبان في وثيق (employee_pay.iban).
--  (2) المشروع يختار من قائمة المشاريع (emp_projects) ويتحقق منه في القاعدة.
--  (3) المعتمد يصحح القيمة عند الاعتماد (p_value) وما كتبه المندوب يبقى في orig_value.
--  (4) دوام نينجا المباشر: من ظهر اليوم يسجل في ninja_presence_days ويظهر مداوما في تطبيق المشرف. لا يمس الرواتب.
begin;

alter table public.data_update_requests add column if not exists orig_value text;
alter table public.data_update_requests add column if not exists fixed_by_name text;

-- تنظيف القيمة والتحقق منها حسب الحقل
create or replace function public._soot_clean_value(p_field text, p_value text)
  returns text language plpgsql stable security definer set search_path = public as $$
declare v text := btrim(translate(coalesce(p_value, ''), '٠١٢٣٤٥٦٧٨٩۰۱۲۳۴۵۶۷۸۹', '01234567890123456789')); d text; c text;
begin
  if v = '' then raise exception 'اكتب القيمة الجديدة'; end if;
  if p_field = 'phone' then
    d := regexp_replace(v, '[^0-9]', '', 'g');
    d := regexp_replace(d, '^(00966|966|0)(?=5[0-9]{8}$)', '');
    if d !~ '^[0-9]{9,14}$' then raise exception 'رقم الجوال غير صحيح — اكتب الأرقام فقط'; end if;
    return d;
  elsif p_field = 'project_code' then
    select code into c from public.projects
     where coalesce(is_active, true) and upper(btrim(code)) = upper(v) limit 1;
    if c is null then raise exception 'اختر المشروع من القائمة'; end if;
    return c;
  elsif p_field = 'bank_account' then
    d := upper(regexp_replace(v, '[^0-9A-Za-z]', '', 'g'));
    if d ~ '^[0-9]{22}$' then d := 'SA' || d; end if;
    if d !~ '^SA[0-9]{22}$' then raise exception 'اكتب رقم الآيبان كاملاً: SA ثم 22 رقماً'; end if;
    return d;
  elsif p_field = 'platform_id' then
    d := public.norm_id(v);
    if d !~ '^[0-9A-Za-z-]{1,20}$' then raise exception 'رقم الآيدي غير صحيح — أرقام وحروف إنجليزية فقط'; end if;
    return d;
  end if;
  return v;
end $$;

-- قائمة المشاريع لتطبيق الموظف
create or replace function public.emp_projects(p_token text)
  returns json language plpgsql stable security definer set search_path = public as $$
begin
  if public._emp_from_token(p_token) is null then raise exception 'انتهت الجلسة'; end if;
  return coalesce((select json_agg(json_build_object('code', code, 'name', coalesce(nullif(name_ar, ''), nullif(name_en, ''), code)) order by code)
                     from public.projects where coalesce(is_active, true) and code !~ '^خامل'), '[]'::json);
end $$;
grant execute on function public.emp_projects(text) to anon, authenticated;

-- (1) طلب الموظف
create or replace function public.emp_request_update(p_token text, p_field text, p_new_value text)
  returns json language plpgsql security definer set search_path = public as $$
declare v_emp public.employees%rowtype; v_old text; v_label text; v_id uuid; v_inbox uuid; v_new text := btrim(coalesce(p_new_value,''));
        v_plat text;
begin
  select * into v_emp from public.employees where id = public._emp_from_token(p_token);
  if v_emp.id is null then raise exception 'انتهت الجلسة'; end if;
  if p_field not in ('phone','project_code','bank_account','platform_id') then raise exception 'حقل غير مدعوم'; end if;
  if v_new = '' then raise exception 'اكتب القيمة الجديدة'; end if;
  v_new := public._soot_clean_value(p_field, v_new);

  if p_field = 'platform_id' then
    v_plat := upper(btrim(coalesce(v_emp.project_code, '')));
    if v_plat = '' then raise exception 'مشروعك غير محدّد في وثيق — راجع المشرف'; end if;
    select string_agg(external_id, ' / ' order by external_id) into v_old
      from public.employee_ids where employee_id = v_emp.id and platform = v_plat and end_date is null;
    if exists (select 1 from public.employee_ids where employee_id = v_emp.id and platform = v_plat and external_id = v_new and end_date is null) then
      raise exception 'هذا هو رقمك الحالي'; end if;
    if exists (select 1 from public.data_update_requests where employee_id = v_emp.id and field = 'platform_id' and status = 'pending') then
      raise exception 'لديك طلب رقم آيدي معلّق بالفعل'; end if;
    v_label := 'رقم الآيدي (' || v_plat || ')';
  else
    v_old := case p_field
               when 'phone' then coalesce(nullif(v_emp.data->>'mobile', ''), v_emp.phone)
               when 'project_code' then v_emp.project_code
               when 'bank_account' then (select iban from public.employee_pay where employee_id = v_emp.id) end;
    v_label := case p_field when 'phone' then 'رقم الاتصال' when 'project_code' then 'المشروع' when 'bank_account' then 'الحساب البنكي (الآيبان)' end;
    if p_field = 'project_code' and upper(coalesce(v_emp.project_code, '')) = upper(v_new) then raise exception 'هذا هو مشروعك الحالي'; end if;
    if exists (select 1 from public.data_update_requests where employee_id = v_emp.id and field = p_field and status = 'pending') then
      raise exception 'لديك طلب معلّق لهذا الحقل — انتظر اعتماده'; end if;
  end if;

  insert into public.data_update_requests(employee_id, iqama, field, field_label, old_value, new_value, platform)
    values(v_emp.id, v_emp.iqama, p_field, v_label, v_old, v_new, v_plat)
    returning id into v_id;

  begin
    insert into public.inbox_items(kind, system, title, body, ref, status, project, created_by, created_by_name)
    values('soot_data_update', 'watheeq',
      'طلب تحديث بيانات (تطبيق الموظف): ' || coalesce(v_emp.full_name_ar, v_emp.full_name_en, v_emp.iqama),
      v_label || ': ' || coalesce(v_old,'—') || '  ←  ' || v_new
        || case when p_field = 'platform_id' then E'\nيؤكده مشرف المشروع ثم يعتمده المدير' else '' end,
      jsonb_build_object('employee_id', v_emp.id, 'iqama', v_emp.iqama, 'field', p_field,
        'label', v_label, 'old', v_old, 'new', v_new, 'request_id', v_id, 'platform', v_plat),
      'new', v_emp.project_code, null, coalesce(v_emp.full_name_ar, v_emp.full_name_en, v_emp.iqama))
    returning id into v_inbox;
    update public.data_update_requests set inbox_id = v_inbox where id = v_id;
  exception when others then raise warning 'soot update inbox: %', sqlerrm; end;

  return json_build_object('id', v_id, 'status','pending');
end $$;
grant execute on function public.emp_request_update(text,text,text) to anon, authenticated;

-- (3) الاعتماد النهائي والتطبيق في وثيق — يكتب في data (ما يقرأه وثيق) وفي العمود معاً
drop function if exists public.sup_review_update(uuid, boolean, text);
create or replace function public.sup_review_update(p_request uuid, p_approve boolean, p_note text default null, p_value text default null)
  returns json language plpgsql security definer set search_path = public as $$
declare v_req public.data_update_requests%rowtype; v_nm text; v_final text; v_emp_nm text; v_body text; v_inbox uuid;
        v_plat text; v_ext text; v_proj text; v_today date := (now() at time zone 'Asia/Riyadh')::date;
        v_holder_emp uuid; v_holder_iqama text; v_holder_nm text; v_moved boolean := false;
        v_val text; v_orig text;
begin
  select * into v_req from public.data_update_requests where id = p_request and status='pending';
  if v_req.id is null then raise exception 'الطلب غير موجود أو تمّت معالجته'; end if;
  if not public._soot_can_approve_request(v_req.field, v_req.employee_id) then
    if v_req.field = 'platform_id' then raise exception 'اعتماد رقم الآيدي للمدير — دور المشرف تأكيد التغيير';
    else raise exception 'اعتماد تحديث البيانات للتوظيف والإدارة فقط — يمكنك التوصية'; end if;
  end if;
  if p_approve and v_req.field = 'platform_id' and coalesce(v_req.rec_status, '') <> 'ok' then
    raise exception 'بانتظار تأكيد مشرف المشروع أولاً'; end if;
  select coalesce(full_name, email) into v_nm from public.profiles where id = auth.uid();

  -- تصحيح المعتمِد: لو أخطأ المندوب تُعتمد القيمة المصحّحة، وما كتبه يبقى في orig_value
  if p_approve then
    v_val := public._soot_clean_value(v_req.field, coalesce(nullif(btrim(coalesce(p_value, '')), ''), v_req.new_value));
    if v_val is distinct from v_req.new_value then
      if nullif(btrim(coalesce(p_value, '')), '') is not null then v_orig := v_req.new_value; end if;
      update public.data_update_requests
         set orig_value = coalesce(orig_value, case when v_orig is not null then new_value end),
             fixed_by_name = case when v_orig is not null then v_nm else fixed_by_name end,
             new_value = v_val
       where id = v_req.id;
      v_req.new_value := v_val;
    end if;
  end if;

  if p_approve then
    if v_req.field = 'phone' then
      update public.employees
         set phone = v_req.new_value,
             data = jsonb_set(coalesce(data, '{}'::jsonb), '{mobile}', to_jsonb(v_req.new_value), true),
             edited_at = now(), edited_by = auth.uid(), updated_at = now()
       where id = v_req.employee_id;
    elsif v_req.field = 'project_code' then
      update public.employees
         set project_code = v_req.new_value,
             data = jsonb_set(coalesce(data, '{}'::jsonb), '{project}', to_jsonb(v_req.new_value), true),
             edited_at = now(), edited_by = auth.uid(), updated_at = now()
       where id = v_req.employee_id;
      if not exists (select 1 from public.employees where id = v_req.employee_id
                      and upper(coalesce(project_code, '')) = upper(v_req.new_value) and upper(coalesce(data->>'project', '')) = upper(v_req.new_value)) then
        raise exception 'تغيير مشروع موظف معتمد صلاحية المدير — يعتمده المدير';
      end if;
    elsif v_req.field = 'bank_account' then
      insert into public.employee_pay as p (employee_id, iban, data)
      values (v_req.employee_id, v_req.new_value, jsonb_build_object('iban', v_req.new_value))
      on conflict (employee_id) do update
         set iban = excluded.iban, data = jsonb_set(coalesce(p.data, '{}'::jsonb), '{iban}', to_jsonb(excluded.iban), true), updated_at = now();
      update public.employees
         set data = jsonb_set(coalesce(data, '{}'::jsonb), '{bank_account}', to_jsonb(v_req.new_value), true),
             edited_at = now(), edited_by = auth.uid(), updated_at = now()
       where id = v_req.employee_id;
    elsif v_req.field = 'platform_id' then
      select project_code into v_proj from public.employees where id = v_req.employee_id;
      v_plat := coalesce(v_req.platform, upper(btrim(v_proj)));
      v_ext := public.norm_id(v_req.new_value);
      select x.employee_id, e.iqama, coalesce(e.full_name_ar, e.full_name_en) into v_holder_emp, v_holder_iqama, v_holder_nm
        from public.employee_ids x join public.employees e on e.id = x.employee_id
       where x.platform = v_plat and x.external_id = v_ext and x.employee_id <> v_req.employee_id limit 1;

      -- رقم الموظف السابق يُقفل بتاريخ اليوم (لا يُحذف)
      update public.employee_ids
         set end_date = v_today, note = coalesce(note || ' | ', '') || 'أُقفل ' || v_today || ': استُبدل بـ ' || v_ext || ' (طلب صوت)'
       where employee_id = v_req.employee_id and platform = v_plat and end_date is null and external_id <> v_ext;

      if v_holder_emp is not null then
        -- الرقم عند موظف آخر → نقل وثيق الرسمي (يُسجَّل في سجل المعرّفات)
        perform public.employee_id_transfer(jsonb_build_object('platform', v_plat, 'external_id', v_ext,
          'to_employee', v_req.employee_id, 'start_date', v_today, 'end_date', v_today - 1,
          'reason', 'طلب تحديث من تطبيق صوت — أكّده ' || coalesce(v_req.rec_by_name, 'المشرف') || ' واعتمده ' || coalesce(v_nm, '')));
        v_moved := true;
        if v_plat = 'NINJA' then
          update public.ninja_links set to_date = v_today where iqama = v_holder_iqama and captain_id = v_ext and to_date is null;
        elsif v_plat = 'KEEMART' then
          update public.keemart_links set to_date = v_today where iqama = v_holder_iqama and captain_id = v_ext and to_date is null;
        end if;
        insert into public.audit_log(table_name, row_id, action, old_data, new_data, changed_by, changed_at)
        values ('employees', v_holder_emp::text, 'soot_platform_id_out', null,
          jsonb_build_object('changes', jsonb_build_object('رقم الآيدي (' || v_plat || ')',
            v_ext || ' — نُقل إلى ' || coalesce((select coalesce(full_name_ar, full_name_en) from public.employees where id = v_req.employee_id), v_req.iqama)
            || ' بطلب من تطبيق صوت (اعتمده ' || coalesce(v_nm, '') || ')')),
          auth.uid(), now());
      else
        insert into public.employee_ids as x (employee_id, platform, external_id, project_code, start_date, note)
        values (v_req.employee_id, v_plat, v_ext, v_proj, v_today, 'طلب تحديث من تطبيق صوت — أكّده ' || coalesce(v_req.rec_by_name, 'المشرف') || ' واعتمده ' || coalesce(v_nm, ''))
        on conflict (platform, external_id) do update set end_date = null,
          note = coalesce(x.note || ' | ', '') || 'أُعيد فتحه ' || v_today || ' (طلب صوت)';
      end if;

      -- ربط المنصة + احتساب الدوام: رقم جديد → كل أيامه · رقم منقول → من اليوم (السابق لصاحبه)
      if v_plat = 'NINJA' then
        update public.ninja_links set to_date = v_today where iqama = v_req.iqama and to_date is null and captain_id <> v_ext;
        if not exists (select 1 from public.ninja_links where iqama = v_req.iqama and captain_id = v_ext and to_date is null) then
          insert into public.ninja_links(captain_id, iqama, from_date, linked_by, linked_by_name) values (v_ext, v_req.iqama, v_today, auth.uid(), v_nm);
        end if;
        update public.ninja_captain_days set pulled_at = pulled_at
         where public.norm_id(captain_id) = v_ext and (not v_moved or work_date >= v_today);
      elsif v_plat = 'KEEMART' then
        update public.keemart_links set to_date = v_today where iqama = v_req.iqama and to_date is null and captain_id <> v_ext;
        if not exists (select 1 from public.keemart_links where iqama = v_req.iqama and captain_id = v_ext and to_date is null) then
          insert into public.keemart_links(captain_id, iqama, from_date, linked_by, note) values (v_ext, v_req.iqama, v_today, auth.uid(), 'طلب تحديث من تطبيق صوت');
        end if;
        update public.keemart_captain_days set pulled_at = pulled_at
         where public.norm_id(captain_id) = v_ext and (not v_moved or work_date >= v_today);
      end if;

      insert into public.audit_log(table_name, row_id, action, old_data, new_data, changed_by, changed_at)
      values ('employees', v_req.employee_id::text, 'soot_platform_id', null,
        jsonb_build_object('changes', jsonb_strip_nulls(jsonb_build_object(
          'رقم الآيدي (' || v_plat || ')', coalesce(v_req.old_value, '—') || ' ← ' || v_ext,
          'نُقل من', case when v_moved then v_holder_nm || ' · إقامة ' || v_holder_iqama end,
          'طريقة التحديث', 'طلب من تطبيق صوت — أكّده ' || coalesce(v_req.rec_by_name, 'المشرف') || ' واعتمده ' || coalesce(v_nm, '')))),
        auth.uid(), now());
    end if;
  end if;

  update public.data_update_requests
     set status = case when p_approve then 'approved' else 'rejected' end,
         note = p_note, reviewed_by = auth.uid(), reviewed_at = now()
   where id = p_request;

  v_final := case when p_approve then 'done' else 'rejected' end;
  begin
    if v_req.inbox_id is not null then
      update public.inbox_items
         set status = v_final, closed_at = now(), closed_by = auth.uid(),
             close_note = case when p_approve then 'اعتُمد — ' else 'رُفض — ' end || coalesce(v_nm,'')
                          || case when v_moved then ' · نُقل الرقم من ' || v_holder_nm else '' end
                          || case when v_orig is not null then ' · صُحّحت القيمة: «' || v_orig || '» ← «' || v_req.new_value || '»' else '' end
       where id = v_req.inbox_id;
    else
      select coalesce(full_name_ar, full_name_en, iqama) into v_emp_nm from public.employees where id = v_req.employee_id;
      v_body := v_req.field_label || ': ' || coalesce(v_req.old_value,'—') || '  ←  ' || v_req.new_value;
      insert into public.inbox_items(kind, system, title, body, ref, status, project, created_by, created_by_name, closed_at, closed_by, close_note)
      values('soot_data_update','watheeq',
        'طلب تحديث بيانات (تطبيق الموظف): ' || coalesce(v_emp_nm, v_req.iqama), v_body,
        jsonb_build_object('employee_id', v_req.employee_id, 'iqama', v_req.iqama, 'field', v_req.field,
          'label', v_req.field_label, 'old', v_req.old_value, 'new', v_req.new_value, 'request_id', v_req.id),
        v_final, (select project_code from public.employees where id = v_req.employee_id),
        auth.uid(), coalesce(v_emp_nm, v_req.iqama), now(), auth.uid(),
        case when p_approve then 'اعتُمد — ' else 'رُفض — ' end || coalesce(v_nm,''))
      returning id into v_inbox;
      update public.data_update_requests set inbox_id = v_inbox where id = v_req.id;
    end if;
  exception when others then raise warning 'soot update inbox: %', sqlerrm; end;

  return json_build_object('ok', true, 'status', case when p_approve then 'approved' else 'rejected' end, 'moved_from', v_holder_nm,
    'value', v_req.new_value, 'corrected_from', v_orig);
end $$;
grant execute on function public.sup_review_update(uuid, boolean, text, text) to authenticated;


-- (4) تصحيح ما اعتُمد سابقاً ولم يظهر في وثيق (الجوال والمشروع) — آخر طلب معتمد لكل موظف
select set_config('afmc.emp_fix', 'on', true);
update public.employees e
   set phone = public._soot_clean_value('phone', r.new_value),
       data = jsonb_set(coalesce(e.data, '{}'::jsonb), '{mobile}', to_jsonb(public._soot_clean_value('phone', r.new_value)), true),
       edited_at = now(), updated_at = now()
  from (select distinct on (employee_id) employee_id, new_value from public.data_update_requests
         where field = 'phone' and status = 'approved' order by employee_id, reviewed_at desc) r
 where r.employee_id = e.id and e.phone = r.new_value
   and coalesce(e.data->>'mobile', '') is distinct from public._soot_clean_value('phone', r.new_value);
update public.employees e
   set data = jsonb_set(coalesce(e.data, '{}'::jsonb), '{project}', to_jsonb(e.project_code), true),
       edited_at = now(), updated_at = now()
  from (select distinct on (employee_id) employee_id, new_value from public.data_update_requests
         where field = 'project_code' and status = 'approved' order by employee_id, reviewed_at desc) r
 where r.employee_id = e.id and upper(e.project_code) = upper(r.new_value)
   and upper(coalesce(e.data->>'project', '')) is distinct from upper(e.project_code);

-- (5) دوام نينجا المباشر: من ظهر اليوم يُسجَّل يوماً بيوم
create table if not exists public.ninja_presence_days (
  work_date  date        not null,
  captain_id text        not null,
  first_at   timestamptz not null,
  last_at    timestamptz not null,
  ticks      integer     not null default 1,
  primary key (work_date, captain_id));
alter table public.ninja_presence_days enable row level security;

create or replace function public.trg_nj_presence()
  returns trigger language plpgsql security definer set search_path = public as $$
declare v_day date := (new.seen_at at time zone 'Asia/Riyadh')::date;
        v_mid timestamptz := (v_day::timestamp at time zone 'Asia/Riyadh');
        v_now boolean; v_del timestamptz;
begin
  if new.seen_at is null or new.seen_at < now() - interval '15 minutes' or coalesce(new.suspended, false) then return null; end if;
  -- مداوم الآن = على طلب · أو جاهز وداخل المنطقة.  داوم اليوم = سلّم طلباً منذ منتصف الليل
  v_now := coalesce(new.busy, false) or (coalesce(new.ready, false) and coalesce(new.in_area, false));
  if new.last_order_hours is not null then v_del := new.seen_at - make_interval(mins => new.last_order_hours); end if;
  if v_now then
    insert into public.ninja_presence_days as p (work_date, captain_id, first_at, last_at)
    values (v_day, public.norm_id(new.captain_id), new.seen_at, new.seen_at)
    on conflict (work_date, captain_id) do update set last_at = excluded.last_at, ticks = p.ticks + 1;
  elsif v_del >= v_mid then
    insert into public.ninja_presence_days as p (work_date, captain_id, first_at, last_at)
    values (v_day, public.norm_id(new.captain_id), v_del, v_del)
    on conflict (work_date, captain_id) do update set last_at = greatest(p.last_at, excluded.last_at);
  end if;
  return null;
exception when others then return null;
end $$;
drop trigger if exists nj_presence on public.ninja_live;
create trigger nj_presence after insert or update on public.ninja_live
  for each row execute function public.trg_nj_presence();

-- (6) الروستر: يضيف دوام نينجا المباشر إلى مصادر الدوام
create or replace function public.soot_roster(p_project text default null, p_date date default null)
  returns json language plpgsql stable security definer set search_path = public as $$
declare v_role text := public.my_role()::text; v_projects text[];
        v_today date := (now() at time zone 'Asia/Riyadh')::date;
        v_d date;
begin
  if v_role is null then raise exception 'غير مصرّح'; end if;
  v_d := least(coalesce(p_date, v_today), v_today);
  if p_project is not null then
    if not public.can_access_project(p_project) then raise exception 'المشروع خارج صلاحيتك'; end if;
    v_projects := array[p_project];
  else
    select array_agg(project_code) into v_projects from public.profile_projects where profile_id = auth.uid();
    if v_projects is null and coalesce((select rm.all_projects from public.role_matrix rm where rm.role = public.my_role()), false) then
      select array_agg(distinct project_code) into v_projects from public.attendance_daily where work_date >= current_date - 7;
    end if;
  end if;
  if v_projects is null then
    return json_build_object('projects', '[]'::json, 'dates', '{}'::json, 'rows', '[]'::json, 'stale_projects', '[]'::json,
                             'no_day', '[]'::json, 'today', v_today, 'date', v_d, 'is_today', v_d = v_today);
  end if;

  return (
    with emp0 as (
      select e.id, e.iqama, coalesce(e.full_name_ar, e.full_name_en, e.iqama) as name, e.project_code, e.status
        from public.employees e
       where e.deleted_at is null and e.project_code = any(v_projects)),
    -- كل مصادر الدوام حتى اليوم المعروض (آخر ١٢٠ يوماً)
    src as (
      select a.employee_id, a.work_date::date as work_date, a.hours::numeric as hours, coalesce(a.orders, 0)::numeric as orders
        from public.attendance_daily a join emp0 e on e.id = a.employee_id
       where a.work_date between v_d - 120 and v_d and (a.present or coalesce(a.hours, 0) > 0)
      union all
      select e.id, s.work_date::date, s.hours_total::numeric, coalesce(s.orders_total, 0)::numeric
        from public.v_samurai_captain_days s join emp0 e on e.iqama = s.iqama::text
       where s.work_date::date between v_d - 120 and v_d and (coalesce(s.hours_total, 0) > 0 or coalesce(s.orders_total, 0) > 0)
      union all
      select e.id, k.work_date::date, k.hours::numeric, coalesce(k.orders, 0)::numeric
        from public.v_keeta_attendance_linked k join emp0 e on e.iqama = k.iqama::text
       where k.work_date::date between v_d - 120 and v_d and (coalesce(k.hours, 0) > 0 or coalesce(k.orders, 0) > 0)
      union all
      -- دوام نينجا المباشر (من ساموراي كل دقيقة) — يُعرف المداوم قبل وصول أرقام اليوم
      select e.id, p.work_date, null::numeric, 0::numeric
        from public.ninja_presence_days p
        join public.employee_ids i on i.platform = 'NINJA' and i.external_id = p.captain_id
                                  and (i.end_date is null or i.end_date >= p.work_date)
        join emp0 e on e.id = i.employee_id
       where p.work_date between v_d - 120 and v_d),
    lv as (
      select i.employee_id, max(p.last_at) as last_at
        from public.ninja_presence_days p
        join public.employee_ids i on i.platform = 'NINJA' and i.external_id = p.captain_id
                                  and (i.end_date is null or i.end_date >= p.work_date)
       where p.work_date = v_d group by 1),
    day as (select employee_id, work_date, max(hours) as hours, max(orders)::int as orders from src group by 1, 2),
    la as (select distinct on (employee_id) employee_id, work_date, hours, orders from day order by employee_id, work_date desc),
    lastd as (select e.project_code, max(d.work_date) d, bool_or(d.work_date = v_d) as has_day
                from day d join emp0 e on e.id = d.employee_id group by 1),
    emp as (
      select e.* from emp0 e
       where e.status = 'active' or exists (select 1 from day d where d.employee_id = e.id and d.work_date between v_d - 1 and v_d)),
    veh as (
      select va.employee_id, string_agg(v.plate_no, ' / ' order by v.plate_no) plates
        from public.vehicle_assignments va join public.vehicles v on v.id = va.vehicle_id
       where va.released_at is null and v.deleted_at is null group by 1),
    lk as (select distinct on (iqama) iqama, captain_id from public.ninja_links where to_date is null order by iqama, from_date desc),
    lg as (select distinct iqama from public.soot_app_logins where app = 'employee'),
    wr as (select iqama, count(*) n from public.soot_warnings where active group by 1),
    rows as (
      select e.iqama, e.name, e.project_code as project, lk.captain_id,
             l.d as work_date, veh.plates,
             la.work_date as last_date, la.hours as last_hours, la.orders as last_orders,
             (la.work_date is not null and la.work_date = v_d) as present,
             (v_d = v_today and la.work_date is not null and la.work_date = v_d - 1) as pending,
             (l.d is not null and l.d >= v_d - 14) as has_data,
             (lg.iqama is not null) as logged, coalesce(wr.n, 0) as warns,
             lv.last_at as live_at
        from emp e
        left join lv on lv.employee_id = e.id
        left join lastd l on l.project_code = e.project_code
        left join la on la.employee_id = e.id
        left join veh on veh.employee_id = e.id
        left join lk on lk.iqama = e.iqama
        left join lg on lg.iqama = e.iqama
        left join wr on wr.iqama = e.iqama)
    select json_build_object(
      'projects', to_json(v_projects),
      'today', v_today, 'date', v_d, 'is_today', v_d = v_today,
      'dates', coalesce((select json_object_agg(project_code, d) from lastd), '{}'::json),
      'stale_projects', coalesce((select json_agg(project_code) from lastd where d < v_d - 1 and d >= v_d - 14), '[]'::json),
      'no_day', coalesce((select json_agg(project_code) from lastd where not has_day and d >= v_d - 14), '[]'::json),
      'pulled', json_build_object(
          'samurai', (select to_char(max(s.pulled_at)::timestamptz at time zone 'Asia/Riyadh', 'YYYY-MM-DD HH24:MI') from public.v_samurai_captain_days s where s.work_date::date = v_d),
          'ninja_live', (select to_char(max(last_at) at time zone 'Asia/Riyadh', 'YYYY-MM-DD HH24:MI') from public.ninja_presence_days where work_date = v_d),
          'keeta',   (select to_char(max(k.pulled_at)::timestamptz at time zone 'Asia/Riyadh', 'YYYY-MM-DD HH24:MI') from public.v_keeta_attendance_linked k where k.work_date::date = v_d)),
      'rows', coalesce((select json_agg(json_build_object(
          'iqama', r.iqama, 'name', r.name, 'project', r.project, 'captain_id', r.captain_id,
          'work_date', r.work_date, 'plates', r.plates,
          'hours', case when r.present then r.last_hours end,
          'orders', case when r.present then coalesce(r.last_orders, 0) else 0 end,
          'present', r.present, 'logged', r.logged, 'warns', r.warns,
          'live_now', (v_d = v_today and r.live_at > now() - interval '10 minutes'),
          'live_at', to_char(r.live_at at time zone 'Asia/Riyadh', 'HH24:MI'),
          'short', r.present and v_d < v_today and coalesce(r.last_hours, 0) < 10,
          'last_date', r.last_date, 'last_hours', r.last_hours, 'last_orders', r.last_orders,
          'days_absent', case when r.last_date is not null then v_d - r.last_date end,
          'last_text', case
             when r.last_date is null then 'لم يُسجَّل له أي دوام خلال آخر ١٢٠ يوماً'
             else 'آخر دوام: ' || to_char(r.last_date, 'YYYY-MM-DD')
                  || case when r.last_hours is not null then ' · ' || trim(to_char(r.last_hours, 'FM990.0')) || ' ساعة' else '' end
                  || ' · ' || coalesce(r.last_orders, 0) || ' طلب'
                  || case when v_d - r.last_date >= 2 then ' · منذ ' || (v_d - r.last_date) || ' يوم' else '' end end,
          'result', case
             when not r.has_data then case when r.plates is not null then 'لا يوجد دوام مسجّل · معه مركبة' else 'لا يوجد دوام مسجّل' end
             when r.present then
               case when v_d = v_today and r.live_at > now() - interval '10 minutes' then 'مداوم الآن'
                    when v_d = v_today and r.live_at is not null then 'داوم اليوم · آخر ظهور ' || to_char(r.live_at at time zone 'Asia/Riyadh', 'HH24:MI')
                    else 'مداوم' end
               || case when r.plates is null then ' · بدون مركبة مسجّلة' else '' end
             when r.pending then 'لم يبدأ دوام اليوم بعد · داوم أمس'
             when r.plates is not null then 'غائب ومعه مركبة'
             else 'غائب بدون مركبة' end,
          'level', case when not r.has_data then 'nodata' when r.present then 'present' when r.pending then 'pending'
                        when r.plates is not null then 'absent_vehicle' else 'absent' end)
          order by (case when not r.has_data then 4 when r.present then 3 when r.pending then 2 when r.plates is not null then 0 else 1 end),
                   (case when r.present then null else r.last_date end) asc nulls first,
                   coalesce(r.last_hours, 0), r.name)
        from rows r), '[]'::json))
  );
end $$;
grant execute on function public.soot_roster(text, date) to authenticated;

commit;
