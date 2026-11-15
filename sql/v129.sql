-- ════════════════════════════════════════════════════════════════════
-- v129 — صوت: أقسام التوظيف/المالية · اعتماد السلف والتحديثات · تسجيل في وثيق
--        · روستر المناديب حسب المشروع (حضور × مركبات) · رسائل موجّهة بتأكيد قراءة
--   • profile_departments: إسناد المستخدم لقسم (hr = التوظيف، finance = المالية)
--   • تحديث البيانات يعتمده: التوظيف + الإدارة (admin/manager)
--   • السلف يعتمدها: التوظيف + المالية + الإدارة — المشرف يوصي فقط
--   • كل قرار سلفة يُسجَّل في سجل الموظف في وثيق (audit_log) + صندوق وثيق (inbox_items)
--   • إصلاح: صندوق وثيق كان يرفض طلبات التحديث (قيد kind) فلم يصل أي طلب
--   • soot_roster: المناديب حسب مشاريع المستخدم، حضور آخر يوم (attendance_daily) × المركبة المسلّمة
--   • الرسائل الموجّهة: require_ack + سجل قراءة؛ الرسائل القديمة لا تُطلق تنبيه
--   • GRANT صريح لكل جدول جديد (تغيير Supabase من 30 أكتوبر)
--   • لا حذف — إضافة وتعديل في المكان
-- ════════════════════════════════════════════════════════════════════

begin;

-- ───────────── (1) الأقسام ─────────────
create table if not exists public.profile_departments(
  profile_id uuid not null references public.profiles(id) on delete cascade,
  dept text not null check (dept in ('hr','finance')),
  created_at timestamptz not null default now(),
  created_by uuid default auth.uid(),
  primary key (profile_id, dept)
);
alter table public.profile_departments enable row level security;
drop policy if exists pd_sel on public.profile_departments;
create policy pd_sel on public.profile_departments for select using (auth.uid() is not null);
grant select on public.profile_departments to authenticated;
grant select, insert, update, delete on public.profile_departments to service_role;

create or replace function public.soot_my_depts()
  returns text[] language sql stable security definer set search_path = public as
$$ select coalesce(array_agg(dept), '{}') from public.profile_departments where profile_id = auth.uid() $$;

create or replace function public.soot_can_approve_updates()
  returns boolean language sql stable security definer set search_path = public as
$$ select coalesce(public.my_role()::text in ('admin','manager'), false) or 'hr' = any(public.soot_my_depts()) $$;

create or replace function public.soot_can_approve_advances()
  returns boolean language sql stable security definer set search_path = public as
$$ select coalesce(public.my_role()::text in ('admin','manager'), false) or public.soot_my_depts() && array['hr','finance'] $$;

-- صلاحيات المستخدم الحالي (للتطبيق والمنصة)
create or replace function public.soot_my_caps()
  returns json language sql stable security definer set search_path = public as $$
  select json_build_object(
    'role', public.my_role()::text,
    'depts', public.soot_my_depts(),
    'approve_updates', public.soot_can_approve_updates(),
    'approve_advances', public.soot_can_approve_advances(),
    'projects', coalesce((select array_agg(project_code order by project_code) from public.profile_projects where profile_id = auth.uid()), '{}'),
    'all_projects', coalesce((select rm.all_projects from public.role_matrix rm where rm.role = public.my_role()), false))
$$;
grant execute on function public.soot_my_depts() to authenticated;
grant execute on function public.soot_can_approve_updates() to authenticated;
grant execute on function public.soot_can_approve_advances() to authenticated;
grant execute on function public.soot_my_caps() to authenticated;

-- قائمة المستخدمين وأقسامهم (للإدارة)
create or replace function public.soot_dept_directory()
  returns json language plpgsql stable security definer set search_path = public as $$
begin
  if coalesce(public.my_role()::text, '') not in ('admin','manager') then raise exception 'للإدارة فقط'; end if;
  return coalesce((select json_agg(json_build_object(
      'id', p.id, 'name', coalesce(p.full_name, p.email), 'role', p.role::text,
      'depts', coalesce((select array_agg(d.dept) from public.profile_departments d where d.profile_id = p.id), '{}'))
      order by coalesce(p.full_name, p.email))
    from public.profiles p where p.approved), '[]'::json);
end $$;
grant execute on function public.soot_dept_directory() to authenticated;

create or replace function public.soot_set_depts(p_profile uuid, p_depts text[])
  returns json language plpgsql security definer set search_path = public as $$
declare v_old text[];
begin
  if coalesce(public.my_role()::text, '') not in ('admin','manager') then raise exception 'للإدارة فقط'; end if;
  select coalesce(array_agg(dept), '{}') into v_old from public.profile_departments where profile_id = p_profile;
  delete from public.profile_departments where profile_id = p_profile and not (dept = any(coalesce(p_depts,'{}')));
  insert into public.profile_departments(profile_id, dept)
    select p_profile, d from unnest(coalesce(p_depts,'{}')) d where d in ('hr','finance')
    on conflict do nothing;
  insert into public.audit_log(table_name, row_id, action, old_data, new_data, changed_by, changed_at)
    values('profile_departments', p_profile::text, 'set_depts', to_jsonb(v_old), to_jsonb(p_depts), auth.uid(), now());
  return json_build_object('ok', true);
end $$;
grant execute on function public.soot_set_depts(uuid, text[]) to authenticated;

-- ───────────── (2) صندوق وثيق: السماح بأنواع صوت + الظهور للتوظيف/المالية ─────────────
alter table public.inbox_items drop constraint if exists inbox_items_kind_check;
alter table public.inbox_items add constraint inbox_items_kind_check check (kind = any (array[
  'vehicle_request','account_request','message','task','stop_request','soot_data_update','soot_advance']));

do $$ declare d text; a text := 'public.my_role() is not null and (';
begin
  d := pg_get_functiondef('public.inbox_visible(public.inbox_items)'::regprocedure);
  if position('soot_advance' in d) = 0 then
    if (length(d) - length(replace(d, a, ''))) / length(a) <> 1 then raise exception 'inbox_visible: anchor not unique'; end if;
    d := replace(d, a, a || E'\n    /* صوت: تحديث البيانات للتوظيف والإدارة، والسلف للتوظيف والمالية والإدارة */'
      || E'\n    (i.kind = ''soot_data_update'' and public.soot_can_approve_updates())'
      || E'\n    or (i.kind = ''soot_advance'' and public.soot_can_approve_advances())'
      || E'\n    or ');
    execute d;
  end if;
end $$;

-- ───────────── (3) توصية المشرف + ربط وثيق ─────────────
alter table public.salary_advances add column if not exists inbox_id uuid;
alter table public.salary_advances add column if not exists rec_status text;      -- ok | no
alter table public.salary_advances add column if not exists rec_note text;
alter table public.salary_advances add column if not exists rec_by uuid;
alter table public.salary_advances add column if not exists rec_by_name text;
alter table public.salary_advances add column if not exists rec_at timestamptz;
alter table public.salary_advances add column if not exists watheeq_logged_at timestamptz;

alter table public.data_update_requests add column if not exists rec_status text;
alter table public.data_update_requests add column if not exists rec_note text;
alter table public.data_update_requests add column if not exists rec_by uuid;
alter table public.data_update_requests add column if not exists rec_by_name text;
alter table public.data_update_requests add column if not exists rec_at timestamptz;

-- تسجيل قرار السلفة في سجل الموظف (يظهر في «سجل التغييرات» في وثيق) + صندوق وثيق
create or replace function public._soot_log_advance(p_id uuid)
  returns void language plpgsql security definer set search_path = public as $$
declare a public.salary_advances%rowtype; e public.employees%rowtype; v_by text; v_st text; v_body text; v_inbox uuid;
begin
  select * into a from public.salary_advances where id = p_id;
  if a.id is null or a.status = 'pending' or a.watheeq_logged_at is not null then return; end if;
  select * into e from public.employees where id = a.employee_id;
  select coalesce(full_name, email) into v_by from public.profiles where id = a.reviewed_by;
  v_st := case a.status when 'approved' then 'معتمدة' else 'مرفوضة' end;
  v_body := 'سلفة ' || trim(to_char(a.amount, 'FM999990.##')) || ' ريال — ' || v_st
         || coalesce(' · السبب: ' || a.reason, '') || coalesce(' · ملاحظة: ' || a.note, '');

  insert into public.audit_log(table_name, row_id, action, old_data, new_data, changed_by, changed_at)
  values('employees', a.employee_id::text, 'salary_advance', null,
    jsonb_build_object('changes', jsonb_strip_nulls(jsonb_build_object(
        'سلفة', trim(to_char(a.amount, 'FM999990.##')) || ' ريال — ' || v_st,
        'سبب السلفة', a.reason,
        'توصية المشرف', case a.rec_status when 'ok' then 'موافق' when 'no' then 'غير موافق' end
                         || coalesce(' — ' || a.rec_by_name, ''),
        'ملاحظة القرار', a.note)),
      'advance_id', a.id, 'amount', a.amount, 'status', a.status),
    a.reviewed_by, coalesce(a.reviewed_at, now()));

  begin
    if a.inbox_id is not null then
      update public.inbox_items set status = case when a.status = 'approved' then 'done' else 'rejected' end,
             closed_at = coalesce(a.reviewed_at, now()), closed_by = a.reviewed_by,
             close_note = v_st || ' — ' || coalesce(v_by, '')
       where id = a.inbox_id;
    else
      insert into public.inbox_items(kind, system, title, body, ref, status, project, created_by, created_by_name, closed_at, closed_by, close_note)
      values('soot_advance', 'watheeq',
        'طلب سلفة (تطبيق الموظف): ' || coalesce(e.full_name_ar, e.full_name_en, a.iqama), v_body,
        jsonb_build_object('employee_id', a.employee_id, 'iqama', a.iqama, 'advance_id', a.id, 'amount', a.amount),
        case when a.status = 'approved' then 'done' else 'rejected' end, e.project_code,
        a.reviewed_by, coalesce(e.full_name_ar, e.full_name_en, a.iqama),
        coalesce(a.reviewed_at, now()), a.reviewed_by, v_st || ' — ' || coalesce(v_by, ''))
      returning id into v_inbox;
      update public.salary_advances set inbox_id = v_inbox where id = a.id;
    end if;
  exception when others then
    raise warning 'soot advance inbox: %', sqlerrm;
  end;

  update public.salary_advances set watheeq_logged_at = now() where id = a.id;
end $$;

-- الموظف يطلب سلفة: + عنصر في صندوق وثيق للتوظيف/المالية
create or replace function public.emp_request_advance(p_token text, p_amount numeric, p_reason text default null)
  returns json language plpgsql security definer set search_path = public as $$
declare v_emp public.employees%rowtype; v_id uuid; v_inbox uuid;
begin
  select * into v_emp from public.employees where id = public._emp_from_token(p_token);
  if v_emp.id is null then raise exception 'انتهت الجلسة'; end if;
  if p_amount is null or p_amount <= 0 then raise exception 'أدخل مبلغًا صحيحًا'; end if;
  if p_amount > 300 then raise exception 'الحد الأعلى للسلفة 300 ريال'; end if;
  if exists(select 1 from public.salary_advances where employee_id = v_emp.id and status = 'pending') then
    raise exception 'لديك طلب سلفة معلّق بالفعل'; end if;
  insert into public.salary_advances(employee_id, iqama, amount, reason)
    values(v_emp.id, v_emp.iqama, round(p_amount, 2), nullif(btrim(p_reason), ''))
    returning id into v_id;
  begin
    insert into public.inbox_items(kind, system, title, body, ref, status, project, created_by, created_by_name)
    values('soot_advance', 'watheeq',
      'طلب سلفة (تطبيق الموظف): ' || coalesce(v_emp.full_name_ar, v_emp.full_name_en, v_emp.iqama),
      'المبلغ: ' || trim(to_char(round(p_amount, 2), 'FM999990.##')) || ' ريال' || coalesce(' · السبب: ' || nullif(btrim(p_reason), ''), ''),
      jsonb_build_object('employee_id', v_emp.id, 'iqama', v_emp.iqama, 'advance_id', v_id, 'amount', round(p_amount, 2)),
      'new', v_emp.project_code, null, coalesce(v_emp.full_name_ar, v_emp.full_name_en, v_emp.iqama))
    returning id into v_inbox;
    update public.salary_advances set inbox_id = v_inbox where id = v_id;
  exception when others then raise warning 'soot advance inbox: %', sqlerrm; end;
  return json_build_object('id', v_id, 'status', 'pending');
end $$;
grant execute on function public.emp_request_advance(text, numeric, text) to anon, authenticated;

-- المشرف يوصي (لا يعتمد)
create or replace function public.sup_recommend_advance(p_request uuid, p_ok boolean, p_note text default null)
  returns json language plpgsql security definer set search_path = public as $$
declare a public.salary_advances%rowtype; v_proj text; v_nm text;
begin
  if public.my_role() is null then raise exception 'غير مصرّح'; end if;
  select * into a from public.salary_advances where id = p_request and status = 'pending';
  if a.id is null then raise exception 'الطلب غير موجود أو تمّت معالجته'; end if;
  select project_code into v_proj from public.employees where id = a.employee_id;
  if not public.can_access_project(v_proj) then raise exception 'هذا الموظف خارج مشروعك'; end if;
  select coalesce(full_name, email) into v_nm from public.profiles where id = auth.uid();
  update public.salary_advances set rec_status = case when p_ok then 'ok' else 'no' end,
         rec_note = nullif(btrim(p_note), ''), rec_by = auth.uid(), rec_by_name = v_nm, rec_at = now()
   where id = a.id;
  if a.inbox_id is not null then
    update public.inbox_items set status = 'in_progress',
           body = body || E'\n' || 'توصية المشرف: ' || case when p_ok then 'موافق' else 'غير موافق' end
                  || ' — ' || coalesce(v_nm, '') || coalesce(' · ' || nullif(btrim(p_note), ''), '')
     where id = a.inbox_id and status = 'new';
  end if;
  return json_build_object('ok', true);
end $$;
grant execute on function public.sup_recommend_advance(uuid, boolean, text) to authenticated;

-- الاعتماد النهائي: التوظيف/المالية/الإدارة فقط + تسجيل في وثيق
create or replace function public.sup_review_advance(p_request uuid, p_approve boolean, p_note text default null)
  returns json language plpgsql security definer set search_path = public as $$
declare v_a public.salary_advances%rowtype;
begin
  if not public.soot_can_approve_advances() then
    raise exception 'اعتماد السلف للتوظيف والمالية والإدارة فقط — يمكنك التوصية'; end if;
  select * into v_a from public.salary_advances where id = p_request and status = 'pending';
  if v_a.id is null then raise exception 'الطلب غير موجود أو تمّت معالجته'; end if;
  update public.salary_advances
     set status = case when p_approve then 'approved' else 'rejected' end,
         note = p_note, reviewed_by = auth.uid(), reviewed_at = now()
   where id = p_request;
  perform public._soot_log_advance(p_request);
  return json_build_object('ok', true, 'status', case when p_approve then 'approved' else 'rejected' end);
end $$;
grant execute on function public.sup_review_advance(uuid, boolean, text) to authenticated;

create or replace function public.sup_recommend_update(p_request uuid, p_ok boolean, p_note text default null)
  returns json language plpgsql security definer set search_path = public as $$
declare r public.data_update_requests%rowtype; v_proj text; v_nm text;
begin
  if public.my_role() is null then raise exception 'غير مصرّح'; end if;
  select * into r from public.data_update_requests where id = p_request and status = 'pending';
  if r.id is null then raise exception 'الطلب غير موجود أو تمّت معالجته'; end if;
  select project_code into v_proj from public.employees where id = r.employee_id;
  if not public.can_access_project(v_proj) then raise exception 'هذا الموظف خارج مشروعك'; end if;
  select coalesce(full_name, email) into v_nm from public.profiles where id = auth.uid();
  update public.data_update_requests set rec_status = case when p_ok then 'ok' else 'no' end,
         rec_note = nullif(btrim(p_note), ''), rec_by = auth.uid(), rec_by_name = v_nm, rec_at = now()
   where id = r.id;
  if r.inbox_id is not null then
    update public.inbox_items set status = 'in_progress',
           body = body || E'\n' || 'توصية المشرف: ' || case when p_ok then 'موافق' else 'غير موافق' end
                  || ' — ' || coalesce(v_nm, '') || coalesce(' · ' || nullif(btrim(p_note), ''), '')
     where id = r.inbox_id and status = 'new';
  end if;
  return json_build_object('ok', true);
end $$;
grant execute on function public.sup_recommend_update(uuid, boolean, text) to authenticated;

-- اعتماد تحديث البيانات: التوظيف + الإدارة فقط (بقية المنطق كما في v125)
create or replace function public.sup_review_update(p_request uuid, p_approve boolean, p_note text default null)
  returns json language plpgsql security definer set search_path = public as $$
declare v_req public.data_update_requests%rowtype; v_nm text; v_final text; v_emp_nm text; v_body text; v_inbox uuid;
begin
  if not public.soot_can_approve_updates() then
    raise exception 'اعتماد تحديث البيانات للتوظيف والإدارة فقط — يمكنك التوصية'; end if;
  select * into v_req from public.data_update_requests where id = p_request and status='pending';
  if v_req.id is null then raise exception 'الطلب غير موجود أو تمّت معالجته'; end if;
  select coalesce(full_name, email) into v_nm from public.profiles where id = auth.uid();

  if p_approve then
    if v_req.field = 'phone' then
      update public.employees set phone = v_req.new_value, updated_at = now() where id = v_req.employee_id;
    elsif v_req.field = 'project_code' then
      update public.employees set project_code = v_req.new_value, updated_at = now() where id = v_req.employee_id;
    elsif v_req.field = 'bank_account' then
      update public.employees set data = jsonb_set(coalesce(data,'{}'::jsonb), '{bank_account}', to_jsonb(v_req.new_value)), updated_at = now() where id = v_req.employee_id;
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

  return json_build_object('ok', true, 'status', case when p_approve then 'approved' else 'rejected' end);
end $$;
grant execute on function public.sup_review_update(uuid,boolean,text) to authenticated;

-- الطلبات المعلّقة حسب مشاريع المستخدم (المعتمِد يرى الكل)
create or replace function public.soot_pending_advances()
  returns json language plpgsql stable security definer set search_path = public as $$
declare v_all boolean := public.soot_can_approve_advances();
begin
  if public.my_role() is null then raise exception 'غير مصرّح'; end if;
  return coalesce((select json_agg(json_build_object(
      'id', a.id, 'iqama', a.iqama, 'amount', a.amount, 'reason', a.reason, 'created_at', a.created_at,
      'name', coalesce(e.full_name_ar, e.full_name_en, a.iqama), 'project', e.project_code,
      'rec_status', a.rec_status, 'rec_note', a.rec_note, 'rec_by_name', a.rec_by_name, 'rec_at', a.rec_at)
      order by a.created_at desc)
    from public.salary_advances a left join public.employees e on e.id = a.employee_id
    where a.status = 'pending' and (v_all or public.can_access_project(e.project_code))), '[]'::json);
end $$;
grant execute on function public.soot_pending_advances() to authenticated;

create or replace function public.soot_pending_updates()
  returns json language plpgsql stable security definer set search_path = public as $$
declare v_all boolean := public.soot_can_approve_updates();
begin
  if public.my_role() is null then raise exception 'غير مصرّح'; end if;
  return coalesce((select json_agg(json_build_object(
      'id', r.id, 'iqama', r.iqama, 'field_label', r.field_label, 'old_value', r.old_value, 'new_value', r.new_value,
      'created_at', r.created_at, 'name', coalesce(e.full_name_ar, e.full_name_en, r.iqama), 'project', e.project_code,
      'rec_status', r.rec_status, 'rec_note', r.rec_note, 'rec_by_name', r.rec_by_name, 'rec_at', r.rec_at)
      order by r.created_at desc)
    from public.data_update_requests r left join public.employees e on e.id = r.employee_id
    where r.status = 'pending' and (v_all or public.can_access_project(e.project_code))), '[]'::json);
end $$;
grant execute on function public.soot_pending_updates() to authenticated;

-- ───────────── (4) روستر المناديب حسب المشروع: الحضور × المركبات ─────────────
create or replace function public.soot_roster(p_project text default null)
  returns json language plpgsql stable security definer set search_path = public as $$
declare v_role text := public.my_role()::text; v_projects text[];
begin
  if v_role is null then raise exception 'غير مصرّح'; end if;
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
    return json_build_object('projects', '[]'::json, 'dates', '{}'::json, 'rows', '[]'::json);
  end if;

  return (
    with lastd as (
      select project_code, max(work_date) d from public.attendance_daily
       where project_code = any(v_projects) and work_date <= current_date group by 1),
    att as (
      select a.employee_id, a.project_code, a.work_date, a.present, a.hours, a.orders
        from public.attendance_daily a join lastd l on l.project_code = a.project_code and a.work_date = l.d),
    emp as (
      select e.id, e.iqama, coalesce(e.full_name_ar, e.full_name_en, e.iqama) as name, e.project_code
        from public.employees e
       where e.deleted_at is null and e.project_code = any(v_projects)
         and (e.status = 'active' or exists (select 1 from att where att.employee_id = e.id))),
    veh as (
      select va.employee_id, string_agg(v.plate_no, ' / ' order by v.plate_no) plates
        from public.vehicle_assignments va join public.vehicles v on v.id = va.vehicle_id
       where va.released_at is null and v.deleted_at is null group by 1),
    lk as (select distinct on (iqama) iqama, captain_id from public.ninja_links where to_date is null order by iqama, from_date desc),
    lg as (select distinct iqama from public.soot_app_logins where app = 'employee'),
    wr as (select iqama, count(*) n from public.soot_warnings where active group by 1),
    rows as (
      select e.iqama, e.name, e.project_code as project, lk.captain_id,
             l.d as work_date, at.hours, coalesce(at.orders, 0) orders, veh.plates,
             (at.employee_id is not null and (at.present or coalesce(at.hours,0) > 0)) as present,
             (l.d is not null) as has_data,
             (lg.iqama is not null) as logged, coalesce(wr.n, 0) as warns
        from emp e
        left join lastd l on l.project_code = e.project_code
        left join att at on at.employee_id = e.id
        left join veh on veh.employee_id = e.id
        left join lk on lk.iqama = e.iqama
        left join lg on lg.iqama = e.iqama
        left join wr on wr.iqama = e.iqama)
    select json_build_object(
      'projects', to_json(v_projects),
      'dates', coalesce((select json_object_agg(project_code, d) from lastd), '{}'::json),
      'rows', coalesce((select json_agg(json_build_object(
          'iqama', r.iqama, 'name', r.name, 'project', r.project, 'captain_id', r.captain_id,
          'work_date', r.work_date, 'hours', r.hours, 'orders', r.orders, 'plates', r.plates,
          'present', r.present, 'logged', r.logged, 'warns', r.warns,
          'short', r.present and coalesce(r.hours,0) < 10,
          'result', case
             when not r.has_data then case when r.plates is not null then 'لا يوجد دوام مسجّل · معه مركبة' else 'لا يوجد دوام مسجّل' end
             when r.present and r.plates is not null then 'مداوم'
             when r.present then 'مداوم · بدون مركبة مسجّلة'
             when r.plates is not null then 'غائب ومعه مركبة'
             else 'غائب بدون مركبة' end,
          'level', case when not r.has_data then 'nodata' when r.present then 'present'
                        when r.plates is not null then 'absent_vehicle' else 'absent' end)
          order by (case when not r.has_data then 3 when r.present then 2 when r.plates is not null then 0 else 1 end),
                   coalesce(r.hours, 0), r.name)
        from rows r), '[]'::json))
  );
end $$;
grant execute on function public.soot_roster(text) to authenticated;

-- ───────────── (5) الرسائل الموجّهة: تأكيد قراءة إلزامي ─────────────
alter table public.soot_broadcasts add column if not exists require_ack boolean not null default true;
-- الرسائل السابقة (اختبارات) لا تُطلق تنبيهًا إلزاميًا
update public.soot_broadcasts set require_ack = false where created_at < now() and require_ack;

create or replace function public.soot_broadcasts_unread(p_token text)
  returns json language plpgsql stable security definer set search_path = public as $$
declare e public.employees%rowtype;
begin
  select * into e from public.employees where id = public._emp_from_token(p_token);
  if e.id is null then raise exception 'انتهت الجلسة'; end if;
  return coalesce((select json_agg(json_build_object('id', b.id, 'title', b.title, 'body', b.body,
            'by_name', b.created_by_name, 'created_at', b.created_at) order by b.created_at)
    from public.soot_broadcasts b
    where b.active and b.require_ack
      and (b.expires_at is null or b.expires_at > now())
      and (b.target->>'project' is null or lower(b.target->>'project') = lower(e.project_code))
      and (b.target->>'city'    is null or lower(b.target->>'city')    = lower(e.area))
      and (b.target->>'vehicle' is null or lower(b.target->>'vehicle') = lower(e.vehicle_type))
      and (b.target->'iqamas' is null or b.target->'iqamas' ? e.iqama)
      and not exists (select 1 from public.soot_broadcast_reads r where r.broadcast_id = b.id and r.iqama = e.iqama)), '[]'::json);
end $$;
grant execute on function public.soot_broadcasts_unread(text) to anon, authenticated;

create or replace function public.soot_broadcast_ack(p_token text, p_id bigint)
  returns json language plpgsql security definer set search_path = public as $$
declare v_iqama text;
begin
  select iqama into v_iqama from public.employees where id = public._emp_from_token(p_token);
  if v_iqama is null then raise exception 'انتهت الجلسة'; end if;
  insert into public.soot_broadcast_reads(broadcast_id, iqama) values(p_id, v_iqama) on conflict do nothing;
  return json_build_object('ok', true);
end $$;
grant execute on function public.soot_broadcast_ack(text, bigint) to anon, authenticated;

-- كم مندوب أكّد القراءة (للمشرف)
create or replace function public.soot_broadcast_stats(p_limit int default 10)
  returns json language sql stable security definer set search_path = public as $$
  select coalesce(json_agg(x order by x.created_at desc), '[]'::json) from (
    select b.id, b.title, b.body, b.created_at, b.created_by_name,
           (select count(*) from public.soot_broadcast_reads r where r.broadcast_id = b.id) as reads
      from public.soot_broadcasts b
     where b.require_ack and (b.created_by = auth.uid() or public.my_role()::text in ('admin','manager'))
     order by b.created_at desc limit greatest(1, least(coalesce(p_limit,10), 50))) x
$$;
grant execute on function public.soot_broadcast_stats(int) to authenticated;

-- ───────────── (6) استدراك: تسجيل ما فات في وثيق ─────────────
-- السلف المحسومة سابقًا → سجل الموظف + صندوق وثيق
do $$ declare r record; begin
  for r in select id from public.salary_advances where status <> 'pending' and watheeq_logged_at is null order by reviewed_at loop
    perform public._soot_log_advance(r.id);
  end loop;
end $$;

-- طلبات التحديث التي لم تصل لصندوق وثيق
do $$ declare r record; v_inbox uuid; v_nm text; v_by text; begin
  for r in select q.*, coalesce(e.full_name_ar, e.full_name_en, q.iqama) emp_nm, e.project_code proj
             from public.data_update_requests q left join public.employees e on e.id = q.employee_id
            where q.inbox_id is null order by q.created_at loop
    select coalesce(full_name, email) into v_by from public.profiles where id = r.reviewed_by;
    insert into public.inbox_items(kind, system, title, body, ref, status, project, created_by, created_by_name, created_at, closed_at, closed_by, close_note)
    values('soot_data_update', 'watheeq', 'طلب تحديث بيانات (تطبيق الموظف): ' || r.emp_nm,
      r.field_label || ': ' || coalesce(r.old_value,'—') || '  ←  ' || r.new_value,
      jsonb_build_object('employee_id', r.employee_id, 'iqama', r.iqama, 'field', r.field, 'label', r.field_label,
        'old', r.old_value, 'new', r.new_value, 'request_id', r.id),
      case r.status when 'approved' then 'done' when 'rejected' then 'rejected' else 'new' end, r.proj,
      r.reviewed_by, r.emp_nm, r.created_at,
      case when r.status <> 'pending' then r.reviewed_at end,
      case when r.status <> 'pending' then r.reviewed_by end,
      case r.status when 'approved' then 'اعتُمد — ' || coalesce(v_by,'') when 'rejected' then 'رُفض — ' || coalesce(v_by,'') end)
    returning id into v_inbox;
    update public.data_update_requests set inbox_id = v_inbox where id = r.id;
  end loop;
end $$;

commit;
