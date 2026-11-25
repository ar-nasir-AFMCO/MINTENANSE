-- v163: administrative deductions (amount or days). Raise: any approved staff within their projects. Decide: admin/manager only.
-- The employee sees a deduction after approval and may object within 3 days; it becomes final after that window or when the objection is refused.
create table if not exists public.admin_deductions(
  id uuid primary key default gen_random_uuid(), employee_id uuid not null, iqama text not null,
  kind text not null check (kind in ('amount','days')), qty numeric not null check (qty > 0), orig_qty numeric,
  reason text not null, incident_date date not null default current_date,
  status text not null default 'pending' check (status in ('pending','approved','objected','rejected','cancelled')),
  raised_by uuid, raised_by_name text, raised_at timestamptz not null default now(),
  decided_by uuid, decided_by_name text, decided_at timestamptz, decision_note text,
  approved_at timestamptz, final_at timestamptz, objection text, objected_at timestamptz,
  settled_period text, settled_at timestamptz);
create index if not exists admin_deductions_emp on public.admin_deductions(employee_id);
alter table public.admin_deductions enable row level security;
revoke all on public.admin_deductions from anon, authenticated;

create or replace function public._ded_state(d public.admin_deductions) returns text language sql stable as $fn$
  select case when d.status = 'approved' and (d.final_at is not null or d.approved_at < now() - interval '3 days') then 'final' else d.status end
$fn$;

create or replace function public._ded_scope(p_project text) returns boolean language sql stable security definer set search_path to 'public' as $fn$
  select public.my_role() is not null and (coalesce((select rm.all_projects from public.role_matrix rm where rm.role::text = public.my_role()::text), false)
    or exists (select 1 from public.profile_projects pp where pp.profile_id = auth.uid() and pp.project_code = p_project))
$fn$;

create or replace function public.ded_raise(p_iqama text, p_kind text, p_qty numeric, p_reason text, p_date date default null) returns jsonb
language plpgsql security definer set search_path to 'public' as $fn$
declare e record; v_id uuid;
begin
  if public.my_role() is null then raise exception 'not allowed'; end if;
  if p_kind not in ('amount','days') or coalesce(p_qty, 0) <= 0 or (p_kind = 'days' and p_qty > 30) then raise exception 'قيمة الخصم غير صحيحة'; end if;
  if btrim(coalesce(p_reason, '')) = '' then raise exception 'اكتب سبب الخصم'; end if;
  select id, iqama, project_code into e from public.employees where iqama = btrim(p_iqama) and deleted_at is null;
  if e.id is null then raise exception 'لا يوجد موظف بهذه الاقامة'; end if;
  if not public._ded_scope(e.project_code) then raise exception 'هذا الموظف ليس ضمن مشاريعك'; end if;
  insert into public.admin_deductions(employee_id, iqama, kind, qty, reason, incident_date, raised_by, raised_by_name)
    values (e.id, e.iqama, p_kind, p_qty, btrim(p_reason), coalesce(p_date, current_date), auth.uid(), (select coalesce(full_name, email) from public.profiles where id = auth.uid()))
    returning id into v_id;
  return jsonb_build_object('ok', true, 'id', v_id);
end $fn$;

create or replace function public.ded_decide(p_id uuid, p_action text, p_note text default null, p_qty numeric default null) returns jsonb
language plpgsql security definer set search_path to 'public' as $fn$
declare d public.admin_deductions; s text; n text := nullif(btrim(coalesce(p_note, '')), '');
begin
  if coalesce(public.my_role()::text in ('admin','manager'), false) is not true then raise exception 'التعميد والتعديل للمدير فقط'; end if;
  select * into d from public.admin_deductions where id = p_id for update;
  if d.id is null then raise exception 'not found'; end if;
  if d.settled_period is not null then raise exception 'تم خصمه من الراتب ولا يمكن تعديله'; end if;
  s := public._ded_state(d);
  if p_action = 'approve' and s = 'pending' then
    update public.admin_deductions set status = 'approved', approved_at = now() where id = p_id;
  elsif p_action = 'reject' and s = 'pending' and n is not null then
    update public.admin_deductions set status = 'rejected' where id = p_id;
  elsif p_action = 'keep' and s = 'objected' then
    update public.admin_deductions set status = 'approved', final_at = now() where id = p_id;
  elsif p_action = 'reduce' and s in ('pending','approved','objected','final') and n is not null and coalesce(p_qty, 0) > 0 and p_qty < d.qty then
    update public.admin_deductions set orig_qty = coalesce(orig_qty, qty), qty = p_qty,
      status = case when s = 'objected' then 'approved' else status end, final_at = case when s = 'objected' then now() else final_at end where id = p_id;
  elsif p_action = 'cancel' and s <> 'cancelled' and n is not null then
    update public.admin_deductions set status = 'cancelled' where id = p_id;
  else raise exception 'الاجراء غير متاح لهذه الحالة او ينقصه سبب القرار';
  end if;
  update public.admin_deductions set decided_by = auth.uid(), decided_by_name = (select coalesce(full_name, email) from public.profiles where id = auth.uid()),
    decided_at = now(), decision_note = coalesce(n, decision_note) where id = p_id;
  return jsonb_build_object('ok', true);
end $fn$;

create or replace function public.ded_list() returns jsonb language sql stable security definer set search_path to 'public' as $fn$
  select jsonb_build_object('ok', true, 'rows', coalesce(jsonb_agg(jsonb_build_object('id', d.id, 'iqama', d.iqama, 'name', coalesce(e.full_name_ar, e.full_name_en), 'project', e.project_code,
    'kind', d.kind, 'qty', d.qty, 'orig_qty', d.orig_qty, 'reason', d.reason, 'date', d.incident_date, 'state', public._ded_state(d), 'raised_by_name', d.raised_by_name,
    'approved_at', d.approved_at, 'objection', d.objection, 'decision_note', d.decision_note, 'settled_period', d.settled_period) order by d.raised_at desc), '[]'::jsonb))
  from public.admin_deductions d join public.employees e on e.id = d.employee_id where public._ded_scope(e.project_code)
$fn$;

create or replace function public.emp_my_deductions(p_token text) returns jsonb language sql stable security definer set search_path to 'public' as $fn$
  select coalesce(jsonb_agg(jsonb_build_object('id', d.id, 'kind', d.kind, 'qty', d.qty, 'reason', d.reason, 'date', d.incident_date, 'state', public._ded_state(d),
    'approved_at', d.approved_at, 'can_object', d.status = 'approved' and d.final_at is null and d.approved_at >= now() - interval '3 days',
    'object_until', d.approved_at + interval '3 days', 'objection', d.objection, 'decision_note', d.decision_note) order by d.approved_at desc), '[]'::jsonb)
  from public.admin_deductions d where d.employee_id = public._emp_from_token(p_token) and d.approved_at is not null and d.status in ('approved','objected','cancelled')
$fn$;

create or replace function public.emp_deduction_object(p_token text, p_id uuid, p_text text) returns jsonb
language plpgsql security definer set search_path to 'public' as $fn$
declare v_emp uuid := public._emp_from_token(p_token); n integer;
begin
  if v_emp is null then raise exception 'انتهت الجلسة'; end if;
  if btrim(coalesce(p_text, '')) = '' then raise exception 'اكتب سبب الاعتراض'; end if;
  update public.admin_deductions set status = 'objected', objection = btrim(p_text), objected_at = now()
    where id = p_id and employee_id = v_emp and status = 'approved' and final_at is null and approved_at >= now() - interval '3 days';
  get diagnostics n = row_count;
  if n = 0 then raise exception 'انتهت مهلة الاعتراض او الخصم غير متاح'; end if;
  return jsonb_build_object('ok', true);
end $fn$;

revoke execute on function public.ded_raise(text, text, numeric, text, date), public.ded_decide(uuid, text, text, numeric), public.ded_list(), public._ded_scope(text), public._ded_state(public.admin_deductions) from public, anon;
grant execute on function public.ded_raise(text, text, numeric, text, date), public.ded_decide(uuid, text, text, numeric), public.ded_list() to authenticated;
grant execute on function public.emp_my_deductions(text), public.emp_deduction_object(text, uuid, text) to anon, authenticated;
