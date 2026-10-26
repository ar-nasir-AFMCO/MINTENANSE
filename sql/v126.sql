-- ════════════════════════════════════════════════════════════════════
-- v126 — السلف (طلب الموظف → اعتماد المشرف)
--   • الحد الأعلى للسلفة: 300 ريال (مُطبّق في القيد وفي الدالة)
--   • طلب واحد معلّق لكل موظف في الوقت نفسه
--   • تسجيل العهد في وثيق: مؤجّل (حسب الطلب)
--   • لا حذف — الحالة تتغيّر في المكان
-- ════════════════════════════════════════════════════════════════════

begin;

create table if not exists public.salary_advances(
  id uuid primary key default gen_random_uuid(),
  employee_id uuid references public.employees(id),
  iqama text,
  amount numeric(8,2) not null check (amount > 0 and amount <= 300),
  reason text,
  status text not null default 'pending',   -- pending | approved | rejected
  note text,
  reviewed_by uuid, reviewed_at timestamptz,
  created_at timestamptz default now()
);
alter table public.salary_advances enable row level security;

drop policy if exists sa_sel on public.salary_advances;
create policy sa_sel on public.salary_advances for select using (auth.uid() is not null);
grant select on public.salary_advances to authenticated;

-- عرض السلف المعلّقة مع اسم الموظف ومشروعه (للمشرف)
create or replace view public.v_pending_advances as
  select a.id, a.iqama, a.amount, a.reason, a.created_at,
         coalesce(e.full_name_ar, e.full_name_en, a.iqama) as name,
         e.project_code as project
  from public.salary_advances a
  left join public.employees e on e.id = a.employee_id
  where a.status = 'pending'
  order by a.created_at desc;
grant select on public.v_pending_advances to anon, authenticated;

-- الموظف يطلب سلفة (حد أعلى 300)
create or replace function public.emp_request_advance(p_token text, p_amount numeric, p_reason text default null)
  returns json language plpgsql security definer set search_path = public as $$
declare v_emp public.employees%rowtype; v_id uuid;
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
  return json_build_object('id', v_id, 'status', 'pending');
end $$;
grant execute on function public.emp_request_advance(text, numeric, text) to anon, authenticated;

-- الموظف يرى سلفه وحالتها
create or replace function public.emp_my_advances(p_token text)
  returns json language plpgsql stable security definer set search_path = public as $$
declare v_emp uuid;
begin
  v_emp := public._emp_from_token(p_token);
  if v_emp is null then raise exception 'انتهت الجلسة'; end if;
  return coalesce((select json_agg(json_build_object(
      'id', id, 'amount', amount, 'reason', reason, 'status', status, 'note', note, 'created_at', created_at)
      order by created_at desc)
    from public.salary_advances where employee_id = v_emp), '[]'::json);
end $$;
grant execute on function public.emp_my_advances(text) to anon, authenticated;

-- المشرف يعتمد/يرفض السلفة
create or replace function public.sup_review_advance(p_request uuid, p_approve boolean, p_note text default null)
  returns json language plpgsql security definer set search_path = public as $$
declare v_a public.salary_advances%rowtype;
begin
  select * into v_a from public.salary_advances where id = p_request and status = 'pending';
  if v_a.id is null then raise exception 'الطلب غير موجود أو تمّت معالجته'; end if;
  update public.salary_advances
     set status = case when p_approve then 'approved' else 'rejected' end,
         note = p_note, reviewed_by = auth.uid(), reviewed_at = now()
   where id = p_request;
  return json_build_object('ok', true, 'status', case when p_approve then 'approved' else 'rejected' end);
end $$;
grant execute on function public.sup_review_advance(uuid, boolean, text) to authenticated;

commit;
