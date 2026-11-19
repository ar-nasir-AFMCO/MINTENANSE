-- ═══════════════════════════════════════════════════════════════════════
-- v139 — صوت · روستر المناديب: دوام مباشر + فلتر تاريخ
--   • p_date: اليوم المعروض (الافتراضي اليوم بتوقيت الرياض). «مداوم» = له دوام في هذا اليوم نفسه.
--   • اليوم الجاري مباشر: من داوم يظهر «مداوم» فور وصول بياناته، ومن داوم أمس ولم يبدأ اليوم بعد
--     يظهر «لم يبدأ دوام اليوم بعد» (pending) لا غائباً. الغائب = لا دوام أمس ولا اليوم (٢٤ ساعة).
--   • اليوم السابق (مكتمل): مداوم أو غائب فقط.
--   • مصادر الدوام مجتمعة: ملفات الدوام المرفوعة (attendance_daily) + السحب المباشر بإضافة AFMCO Sync
--     من Samurai (v_samurai_captain_days) و Keeta (v_keeta_attendance_linked). لا يُكتب شيء ولا تتأثر الرواتب.
--   • التوقيع تغيّر (أُضيف p_date) فتُحذف النسخة القديمة أولاً حتى لا يلتبس الاستدعاء.
-- ═══════════════════════════════════════════════════════════════════════
drop function if exists public.soot_roster(text);
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
       where k.work_date::date between v_d - 120 and v_d and (coalesce(k.hours, 0) > 0 or coalesce(k.orders, 0) > 0)),
    day as (select employee_id, work_date, max(hours) as hours, max(orders)::int as orders from src group by 1, 2),
    la as (select distinct on (employee_id) employee_id, work_date, hours, orders from day order by employee_id, work_date desc),
    lastd as (select e.project_code, max(d.work_date) d, bool_or(d.work_date = v_d) as has_day
                from day d join emp0 e on e.id = d.employee_id group by 1),
    emp as (
      select e.* from emp0 e
       where e.status = 'active' or exists (select 1 from day d where d.employee_id = e.id and d.work_date = v_d)),
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
             (l.d is not null) as has_data,
             (lg.iqama is not null) as logged, coalesce(wr.n, 0) as warns
        from emp e
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
      'stale_projects', coalesce((select json_agg(project_code) from lastd where d < v_d - 1), '[]'::json),
      'no_day', coalesce((select json_agg(project_code) from lastd where not has_day), '[]'::json),
      'pulled', json_build_object(
          'samurai', (select to_char(max(s.pulled_at)::timestamptz at time zone 'Asia/Riyadh', 'YYYY-MM-DD HH24:MI') from public.v_samurai_captain_days s where s.work_date::date = v_d),
          'keeta',   (select to_char(max(k.pulled_at)::timestamptz at time zone 'Asia/Riyadh', 'YYYY-MM-DD HH24:MI') from public.v_keeta_attendance_linked k where k.work_date::date = v_d)),
      'rows', coalesce((select json_agg(json_build_object(
          'iqama', r.iqama, 'name', r.name, 'project', r.project, 'captain_id', r.captain_id,
          'work_date', r.work_date, 'plates', r.plates,
          'hours', case when r.present then r.last_hours end,
          'orders', case when r.present then coalesce(r.last_orders, 0) else 0 end,
          'present', r.present, 'logged', r.logged, 'warns', r.warns,
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
             when r.present and r.plates is not null then 'مداوم'
             when r.present then 'مداوم · بدون مركبة مسجّلة'
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
