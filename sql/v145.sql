-- v145 — التلفيات والحوادث: من مركبات الاسطول الى الكرت المالي للموظف (بنسبة الخطأ كما في الاسطول) + تسجيل خصمها
--  المبلغ على السائق = التكلفة × نسبة الخطأ (الحادث بلا نسبة = 100%، والتلف 100%). الخصم بحسب تاريخ الحادث/التلف ويسجل في وثيق.
begin;

-- ما تم بشأن كل حادث/تلف: settled (خصم من راتب) | waived (اعفاء) | open (ارجع للخصم). لا يحذف شيء.
create table if not exists public.incident_settlements (
  key text primary key, iqama text, kind text, plate text, amount numeric(12,2),
  status text not null default 'settled', period text, note text,
  at timestamptz not null default now(), by uuid, by_name text
);
alter table public.incident_settlements enable row level security;

-- حوادث وتلفيات المركبات المسجلة على سائق (نفس حساب fleet_incidents) مع مفتاح ثابت لكل سجل
create or replace function public._fleet_incident_rows()
  returns table(key text, iqama text, kind text, plate text, d date, cost numeric, pct numeric, amount numeric, fstatus text, description text, created text)
  language sql stable security definer set search_path = public as $$
  select md5('accident|' || v.plate_no || '|' || coalesce(nullif(a->>'created', ''), coalesce(a->>'date', '') || '|' || coalesce(a->>'description', ''))),
         public.norm_id(a->>'driver_iqama'), 'accident', v.plate_no, public.try_date(a->>'date'),
         coalesce(public.to_num(a->>'cost'), 0), coalesce(public.to_num(a->>'fault_pct'), 100),
         round(coalesce(public.to_num(a->>'cost'), 0) * coalesce(public.to_num(a->>'fault_pct'), 100) / 100.0, 2),
         a->>'status', a->>'description', a->>'created'
    from public.vehicles v, jsonb_array_elements(coalesce(v.data->'accidents', '[]'::jsonb)) a
   where v.deleted_at is null and public.norm_id(a->>'driver_iqama') is not null
  union all
  select md5('damage|' || v.plate_no || '|' || coalesce(nullif(d->>'created', ''), coalesce(d->>'date', '') || '|' || coalesce(d->>'description', ''))),
         public.norm_id(d->>'iqama'), 'damage', v.plate_no, public.try_date(d->>'date'),
         coalesce(public.to_num(d->>'cost'), 0), 100, round(coalesce(public.to_num(d->>'cost'), 0), 2),
         d->>'status', d->>'description', d->>'created'
    from public.vehicles v, jsonb_array_elements(coalesce(v.data->'damages', '[]'::jsonb)) d
   where v.deleted_at is null and public.norm_id(d->>'iqama') is not null
$$;
revoke all on function public._fleet_incident_rows() from public, anon, authenticated;

-- لوثيق: كل الحوادث والتلفيات وما سجل بشأن خصمها
create or replace function public.fleet_incident_dues()
  returns json language plpgsql stable security definer set search_path = public as $$
begin
  if not public._soot_can_pay_advances() then return json_build_object('ok', false); end if;
  return json_build_object('ok', true, 'rows', coalesce((select json_agg(json_build_object(
      'key', r.key, 'iqama', r.iqama, 'kind', r.kind, 'plate', r.plate, 'date', r.d, 'cost', r.cost, 'pct', r.pct, 'amount', r.amount,
      'fstatus', r.fstatus, 'desc', r.description, 'created', r.created,
      'st', case when s.status in ('settled', 'waived') then s.status end, 'period', s.period, 'note', s.note)
      order by r.d nulls last, r.key)
    from public._fleet_incident_rows() r left join public.incident_settlements s on s.key = r.key), '[]'::json));
end $$;
grant execute on function public.fleet_incident_dues() to authenticated;

-- تسجيل ما تم: settled (خصم من راتب p_period) | waived (اعفاء) | open (ارجاع للخصم)
create or replace function public.fleet_incident_mark(p_key text, p_status text, p_period text default null, p_amount numeric default null, p_note text default null)
  returns json language plpgsql security definer set search_path = public as $$
declare r record; o public.incident_settlements%rowtype; v_nm text; v_emp uuid; v_txt text;
        v_per text := nullif(btrim(coalesce(p_period, '')), ''); v_note text := nullif(btrim(coalesce(p_note, '')), '');
begin
  if not public._soot_can_pay_advances() then raise exception 'تسجيل خصم التلفيات والحوادث للمالية والإدارة فقط'; end if;
  if p_status is null or p_status not in ('settled', 'waived', 'open') then raise exception 'إجراء غير معروف'; end if;
  select * into r from public._fleet_incident_rows() x where x.key = p_key limit 1;
  select * into o from public.incident_settlements where key = p_key;
  if r.key is null and o.key is null then raise exception 'السجل غير موجود في الأسطول'; end if;
  if o.key is not null and o.status = p_status and o.period is not distinct from v_per then
    return json_build_object('ok', true, 'status', p_status, 'same', true);
  end if;
  select coalesce(full_name, email) into v_nm from public.profiles where id = auth.uid();
  insert into public.incident_settlements(key, iqama, kind, plate, amount, status, period, note, at, by, by_name)
  values (p_key, coalesce(r.iqama, o.iqama), coalesce(r.kind, o.kind), coalesce(r.plate, o.plate), coalesce(p_amount, r.amount, o.amount),
          p_status, v_per, v_note, now(), auth.uid(), v_nm)
  on conflict (key) do update set iqama = excluded.iqama, kind = excluded.kind, plate = excluded.plate, amount = excluded.amount,
         status = excluded.status, period = excluded.period, note = excluded.note, at = excluded.at, by = excluded.by, by_name = excluded.by_name;
  begin
    select id into v_emp from public.employees where iqama = coalesce(r.iqama, o.iqama) and deleted_at is null limit 1;
    v_txt := case p_status when 'settled' then 'خُصم من راتب ' || coalesce(v_per, '—') when 'waived' then 'إعفاء' else 'أُعيد للخصم' end;
    if v_emp is not null then
      insert into public.audit_log(table_name, row_id, action, old_data, new_data, changed_by, changed_at)
      values('employees', v_emp::text, 'vehicle_incident', null,
        jsonb_build_object('changes', jsonb_strip_nulls(jsonb_build_object(
            case coalesce(r.kind, o.kind) when 'accident' then 'حادث مركبة' else 'تلف مركبة' end,
            coalesce(r.plate, o.plate, '') || ' — ' || trim(to_char(coalesce(p_amount, r.amount, o.amount, 0), 'FM9999990.##')) || ' ريال — ' || v_txt,
            'ملاحظة', v_note)),
          'incident_key', p_key, 'status', p_status, 'period', v_per),
        auth.uid(), now());
    end if;
  exception when others then raise warning 'incident mark log: %', sqlerrm; end;
  return json_build_object('ok', true, 'status', p_status);
end $$;
grant execute on function public.fleet_incident_mark(text, text, text, numeric, text) to authenticated;

commit;
