-- v147 — تخفيض نسبة الخصم على السائق في حادث/تلف: قرار مالي يسجل مع سببه (نسبة الاسطول لا تتغير)
begin;

alter table public.incident_settlements add column if not exists adj_pct numeric(5,2);
alter table public.incident_settlements add column if not exists adj_note text;
alter table public.incident_settlements add column if not exists adj_by_name text;
alter table public.incident_settlements add column if not exists adj_at timestamptz;

-- لوثيق: كل الحوادث والتلفيات وما سجل بشأن خصمها
create or replace function public.fleet_incident_dues()
  returns json language plpgsql stable security definer set search_path = public as $$
begin
  if not public._soot_can_pay_advances() then return json_build_object('ok', false); end if;
  return json_build_object('ok', true, 'rows', coalesce((select json_agg(json_build_object(
      'key', r.key, 'iqama', r.iqama, 'kind', r.kind, 'plate', r.plate, 'date', r.d, 'cost', r.cost, 'pct', r.pct, 'amount', r.amount,
      'fstatus', r.fstatus, 'desc', r.description, 'created', r.created,
      'st', case when s.status in ('settled', 'waived') then s.status end, 'period', s.period, 'note', s.note,
      'plan', case when s.status = 'plan' then s.plan end, 'adj_pct', s.adj_pct, 'adj_note', s.adj_note)
      order by r.d nulls last, r.key)
    from public._fleet_incident_rows() r left join public.incident_settlements s on s.key = r.key), '[]'::json));
end $$;
grant execute on function public.fleet_incident_dues() to authenticated;

-- تخفيض النسبة: p_pct بين 0 ونسبة الاسطول (null او نفس نسبة الاسطول = ارجاعها). لا يطبق على ما خصم او اعفي.
create or replace function public.fleet_incident_adjust(p_key text, p_pct numeric default null, p_note text default null)
  returns json language plpgsql security definer set search_path = public as $$
declare r record; o public.incident_settlements%rowtype; v_nm text; v_emp uuid; v_pct numeric; v_amt numeric;
        v_note text := nullif(btrim(coalesce(p_note, '')), '');
begin
  if not public._soot_can_pay_advances() then raise exception 'تخفيض نسبة الخصم للمالية والإدارة فقط'; end if;
  select * into r from public._fleet_incident_rows() x where x.key = p_key limit 1;
  if r.key is null then raise exception 'السجل غير موجود في الأسطول'; end if;
  select * into o from public.incident_settlements where key = p_key;
  if o.status in ('settled', 'waived') then raise exception 'السجل مخصوم أو معفى — لا تُعدّل نسبته'; end if;
  if p_pct is not null and (p_pct < 0 or p_pct > r.pct) then raise exception 'النسبة يجب أن تكون بين 0 و %', trim(to_char(r.pct, 'FM990.##')); end if;
  v_pct := case when p_pct is null or p_pct = r.pct then null else p_pct end;
  if v_pct is not null and v_note is null then raise exception 'اكتب سبب التخفيض'; end if;
  v_amt := round(r.cost * coalesce(v_pct, r.pct) / 100.0, 2);
  if o.key is not null and o.adj_pct is not distinct from v_pct and o.adj_note is not distinct from (case when v_pct is null then null else v_note end) then
    return json_build_object('ok', true, 'pct', coalesce(v_pct, r.pct), 'amount', v_amt, 'same', true);
  end if;
  select coalesce(full_name, email) into v_nm from public.profiles where id = auth.uid();
  insert into public.incident_settlements(key, iqama, kind, plate, amount, status, at, by, by_name, adj_pct, adj_note, adj_by_name, adj_at)
  values (p_key, r.iqama, r.kind, r.plate, v_amt, 'open', now(), auth.uid(), v_nm, v_pct, case when v_pct is null then null else v_note end, v_nm, now())
  on conflict (key) do update set amount = excluded.amount, adj_pct = excluded.adj_pct, adj_note = excluded.adj_note,
         adj_by_name = excluded.adj_by_name, adj_at = excluded.adj_at;
  begin
    select id into v_emp from public.employees where iqama = r.iqama and deleted_at is null limit 1;
    if v_emp is not null then
      insert into public.audit_log(table_name, row_id, action, old_data, new_data, changed_by, changed_at)
      values('employees', v_emp::text, 'vehicle_incident', null,
        jsonb_build_object('changes', jsonb_strip_nulls(jsonb_build_object(
            case r.kind when 'accident' then 'حادث مركبة' else 'تلف مركبة' end,
            r.plate || ' — ' || case when v_pct is null then 'رجعت نسبة الأسطول ' || trim(to_char(r.pct, 'FM990.##')) || '%'
                 else 'خُفّضت النسبة من ' || trim(to_char(coalesce(o.adj_pct, r.pct), 'FM990.##')) || '% إلى ' || trim(to_char(v_pct, 'FM990.##')) || '%' end
              || ' — على السائق ' || trim(to_char(v_amt, 'FM9999990.##')) || ' ريال',
            'سبب التخفيض', case when v_pct is null then null else v_note end)),
          'incident_key', p_key, 'adj_pct', v_pct),
        auth.uid(), now());
    end if;
  exception when others then raise warning 'incident adjust log: %', sqlerrm; end;
  return json_build_object('ok', true, 'pct', coalesce(v_pct, r.pct), 'amount', v_amt);
end $$;
grant execute on function public.fleet_incident_adjust(text, numeric, text) to authenticated;

commit;
