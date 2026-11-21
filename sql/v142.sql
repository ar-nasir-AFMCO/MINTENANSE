-- v142 — كيمارت: سحب تقرير الدوام تلقائيا عبر اضافة AFMCO Keemart Bridge + دوام كيمارت المباشر في تطبيق المشرف
--  keemart_sync_plan: المنصة تسأل كل 20 دقيقة عن الايام المطلوب سحبها (اليوم دائما + اي يوم لم يكتمل او ناقص خلال 10 ايام)
--  والاضافة تسحبها من بوابة كيمارت بجلسة المستخدم، والمنصة تحفظها بـ keemart_upsert (نفس مسار الدوام والرواتب).
begin;

create table if not exists public.keemart_sync_state (
  id        boolean primary key default true check (id),
  last_try  timestamptz,
  last_ok   timestamptz,
  last_note text,
  last_by   uuid);
alter table public.keemart_sync_state enable row level security;
drop policy if exists keemart_sync_state_read on public.keemart_sync_state;
create policy keemart_sync_state_read on public.keemart_sync_state for select to authenticated using (true);
grant select on public.keemart_sync_state to authenticated;
insert into public.keemart_sync_state(id) values (true) on conflict do nothing;

create or replace function public.keemart_sync_plan(p_force boolean default false)
  returns json language plpgsql security definer set search_path = public as $$
declare v_today date := (now() at time zone 'Asia/Riyadh')::date; v_days json; v_st public.keemart_sync_state%rowtype;
begin
  if auth.uid() is null or not (select public.has_system('keemart')) or not (select public.can_do('edit')) then
    return json_build_object('days', '[]'::json, 'skip', 'no-permission');
  end if;
  select * into v_st from public.keemart_sync_state where id for update;
  if not coalesce(p_force, false) and v_st.last_try > now() - interval '12 minutes' then
    return json_build_object('days', '[]'::json, 'skip', 'recent', 'last_ok', v_st.last_ok);
  end if;
  select coalesce(json_agg(to_char(x.d, 'YYYY-MM-DD') order by x.d), '[]'::json) into v_days
    from (select (v_today - s)::date as d from generate_series(0, 9) s) x
    left join lateral (select max(k.pulled_at) as p from public.keemart_captain_days k
                        where k.work_date = x.d and k.src in ('attendance', 'both')) k on true
   where x.d = v_today or k.p is null
      or k.p < (((x.d + 1)::timestamp + interval '6 hours') at time zone 'Asia/Riyadh');
  update public.keemart_sync_state set last_try = now(), last_by = auth.uid() where id;
  return json_build_object('days', v_days, 'last_ok', v_st.last_ok);
end $$;
grant execute on function public.keemart_sync_plan(boolean) to authenticated;

create or replace function public.keemart_sync_mark(p_ok boolean, p_note text default null)
  returns void language plpgsql security definer set search_path = public as $$
begin
  if auth.uid() is null or not (select public.has_system('keemart')) or not (select public.can_do('edit')) then return; end if;
  update public.keemart_sync_state
     set last_ok = case when p_ok then now() else last_ok end, last_note = left(coalesce(p_note, ''), 300), last_by = auth.uid()
   where id;
end $$;
grant execute on function public.keemart_sync_mark(boolean, text) to authenticated;

-- الروستر: يضيف دوام كيمارت المباشر (سجل الدخول اليوم) الى مصادر الدوام
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
       where p.work_date between v_d - 120 and v_d
      union all
      -- دوام كيمارت المباشر: من سجل دخوله اليوم في تقرير الدوام (يسحب تلقائيا)
      select e.id, k.work_date, null::numeric, 0::numeric
        from public.keemart_captain_days k
        join public.employee_ids i on i.platform = 'KEEMART' and i.external_id = public.norm_id(k.captain_id)
                                  and (i.end_date is null or i.end_date >= k.work_date)
        join emp0 e on e.id = i.employee_id
       where k.work_date = v_d and v_d = v_today and k.check_in is not null),
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
