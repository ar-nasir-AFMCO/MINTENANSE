-- v146 — تقسيط خصم التلفيات والحوادث على اكثر من شهر: خطة الاقساط تحفظ مع السجل وكل قسط يخصم مع مسير شهره
begin;

alter table public.incident_settlements add column if not exists plan jsonb;
drop function if exists public.fleet_incident_mark(text, text, text, numeric, text);

-- لوثيق: كل الحوادث والتلفيات وما سجل بشأن خصمها
create or replace function public.fleet_incident_dues()
  returns json language plpgsql stable security definer set search_path = public as $$
begin
  if not public._soot_can_pay_advances() then return json_build_object('ok', false); end if;
  return json_build_object('ok', true, 'rows', coalesce((select json_agg(json_build_object(
      'key', r.key, 'iqama', r.iqama, 'kind', r.kind, 'plate', r.plate, 'date', r.d, 'cost', r.cost, 'pct', r.pct, 'amount', r.amount,
      'fstatus', r.fstatus, 'desc', r.description, 'created', r.created,
      'st', case when s.status in ('settled', 'waived') then s.status end, 'period', s.period, 'note', s.note,
      'plan', case when s.status = 'plan' then s.plan end)
      order by r.d nulls last, r.key)
    from public._fleet_incident_rows() r left join public.incident_settlements s on s.key = r.key), '[]'::json));
end $$;
grant execute on function public.fleet_incident_dues() to authenticated;

-- تسجيل ما تم: settled (خصم من راتب p_period) | waived (اعفاء) | open (ارجاع للخصم) | plan (تقسيط: p_plan = الاقساط)
create or replace function public.fleet_incident_mark(p_key text, p_status text, p_period text default null, p_amount numeric default null, p_note text default null, p_plan jsonb default null)
  returns json language plpgsql security definer set search_path = public as $$
declare r record; o public.incident_settlements%rowtype; v_nm text; v_emp uuid; v_txt text;
        v_per text := nullif(btrim(coalesce(p_period, '')), ''); v_note text := nullif(btrim(coalesce(p_note, '')), '');
begin
  if not public._soot_can_pay_advances() then raise exception 'تسجيل خصم التلفيات والحوادث للمالية والإدارة فقط'; end if;
  if p_status is null or p_status not in ('settled', 'waived', 'open', 'plan') then raise exception 'إجراء غير معروف'; end if;
  if p_status = 'plan' and (p_plan is null or jsonb_typeof(p_plan) <> 'array' or jsonb_array_length(p_plan) = 0) then raise exception 'خطة التقسيط فارغة'; end if;
  select * into r from public._fleet_incident_rows() x where x.key = p_key limit 1;
  select * into o from public.incident_settlements where key = p_key;
  if r.key is null and o.key is null then raise exception 'السجل غير موجود في الأسطول'; end if;
  if o.key is not null and o.status = p_status and o.period is not distinct from v_per and (p_status <> 'plan' or o.plan is not distinct from p_plan) then
    return json_build_object('ok', true, 'status', p_status, 'same', true);
  end if;
  select coalesce(full_name, email) into v_nm from public.profiles where id = auth.uid();
  insert into public.incident_settlements(key, iqama, kind, plate, amount, status, period, note, at, by, by_name, plan)
  values (p_key, coalesce(r.iqama, o.iqama), coalesce(r.kind, o.kind), coalesce(r.plate, o.plate), coalesce(p_amount, r.amount, o.amount),
          p_status, v_per, v_note, now(), auth.uid(), v_nm, case when p_status = 'open' then null else coalesce(p_plan, o.plan) end)
  on conflict (key) do update set iqama = excluded.iqama, kind = excluded.kind, plate = excluded.plate, amount = excluded.amount,
         status = excluded.status, period = excluded.period, note = excluded.note, at = excluded.at, by = excluded.by, by_name = excluded.by_name, plan = excluded.plan;
  begin
    select id into v_emp from public.employees where iqama = coalesce(r.iqama, o.iqama) and deleted_at is null limit 1;
    v_txt := case p_status when 'settled' then 'خُصم من راتب ' || coalesce(v_per, '—') when 'waived' then 'إعفاء'
               when 'plan' then 'تقسيط على ' || jsonb_array_length(p_plan) || ' — خُصم منها ' || (select count(*) from jsonb_array_elements(p_plan) q where q->>'st' = 'settled')
               else 'أُعيد للخصم' end;
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
grant execute on function public.fleet_incident_mark(text, text, text, numeric, text, jsonb) to authenticated;

commit;
