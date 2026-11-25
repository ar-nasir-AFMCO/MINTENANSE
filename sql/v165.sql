-- v165: final administrative deductions are settled at payroll. A deduction may be settled partly (monthly cap): the settled part
-- becomes its own row (parent_id) and the remainder stays on the original row for the next month.
alter table public.admin_deductions add column if not exists parent_id uuid;
create or replace function public.ded_settle(p_id uuid, p_period text, p_qty numeric default null) returns jsonb
language plpgsql security definer set search_path to 'public' as $fn$
declare d public.admin_deductions; q numeric;
begin
  if coalesce(public.my_role()::text in ('admin','manager','finance'), false) is not true then raise exception 'not allowed'; end if;
  if coalesce(p_period, '') !~ '^[0-9]{4}-[0-9]{2}$' then raise exception 'bad period'; end if;
  select * into d from public.admin_deductions where id = p_id for update;
  if d.id is null then raise exception 'not found'; end if;
  if d.settled_period is not null then return jsonb_build_object('ok', true, 'already', true); end if;
  if public._ded_state(d) <> 'final' then raise exception 'not final'; end if;
  q := coalesce(p_qty, d.qty);
  if q <= 0 or q > d.qty then raise exception 'bad qty'; end if;
  if q >= d.qty then
    update public.admin_deductions set settled_period = p_period, settled_at = now() where id = p_id;
  else
    insert into public.admin_deductions(employee_id, iqama, kind, qty, orig_qty, reason, incident_date, status, raised_by, raised_by_name, raised_at,
        decided_by, decided_by_name, decided_at, decision_note, approved_at, final_at, objection, objected_at, settled_period, settled_at, parent_id)
      values (d.employee_id, d.iqama, d.kind, q, d.orig_qty, d.reason, d.incident_date, d.status, d.raised_by, d.raised_by_name, d.raised_at,
        d.decided_by, d.decided_by_name, d.decided_at, d.decision_note, d.approved_at, coalesce(d.final_at, now()), d.objection, d.objected_at, p_period, now(), d.id);
    update public.admin_deductions set qty = qty - q, orig_qty = coalesce(orig_qty, d.qty) where id = p_id;
  end if;
  return jsonb_build_object('ok', true);
end $fn$;
revoke execute on function public.ded_settle(uuid, text, numeric) from public, anon;
grant execute on function public.ded_settle(uuid, text, numeric) to authenticated;
