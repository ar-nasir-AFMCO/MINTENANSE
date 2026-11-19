-- ═══════════════════════════════════════════════════════════════════════
-- v137 — صوت · روستر المناديب: الغياب = لم يُسجَّل له دوام خلال آخر ٢٤ ساعة
--   • القاعدة: المندوب «مداوم» إذا كان له دوام (حاضر أو ساعات > 0) أمس أو اليوم بتوقيت الرياض،
--     وإلا فهو «غائب» — مهما كان تاريخ آخر ملف دوام مرفوع. (قبلها كان يُقارن بآخر يوم مرفوع فقط)
--   • لكل مندوب: تفاصيل آخر دوام بتاريخه (last_date / last_hours / last_orders / last_text).
--   • stale_projects: مشاريع آخر ملف دوام مرفوع لها أقدم من أمس — ليُنبَّه المشرف أن الغياب سببه عدم الرفع.
--   • لا جداول جديدة ولا تغيير في الصلاحيات. نفس توقيع الدالة، والحقول القديمة كلها باقية.
-- ═══════════════════════════════════════════════════════════════════════
create or replace function public.soot_roster(p_project text default null)
  returns json language plpgsql stable security definer set search_path = public as $$
declare v_role text := public.my_role()::text; v_projects text[];
        v_today date := (now() at time zone 'Asia/Riyadh')::date;
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
    return json_build_object('projects', '[]'::json, 'dates', '{}'::json, 'rows', '[]'::json, 'stale_projects', '[]'::json, 'today', v_today);
  end if;

  return (
    with lastd as (
      select project_code, max(work_date) d from public.attendance_daily
       where project_code = any(v_projects) and work_date <= v_today group by 1),
    att as (
      select a.employee_id
        from public.attendance_daily a join lastd l on l.project_code = a.project_code and a.work_date = l.d),
    emp as (
      select e.id, e.iqama, coalesce(e.full_name_ar, e.full_name_en, e.iqama) as name, e.project_code
        from public.employees e
       where e.deleted_at is null and e.project_code = any(v_projects)
         and (e.status = 'active' or exists (select 1 from att where att.employee_id = e.id))),
    -- آخر دوام فعلي لكل مندوب (خلال آخر ١٢٠ يوماً)
    la as (
      select distinct on (a.employee_id) a.employee_id, a.work_date, a.hours, coalesce(a.orders, 0) as orders
        from public.attendance_daily a
       where a.employee_id in (select id from emp)
         and a.work_date <= v_today and a.work_date >= v_today - 120
         and (a.present or coalesce(a.hours, 0) > 0)
       order by a.employee_id, a.work_date desc),
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
             (la.work_date is not null and la.work_date >= v_today - 1) as present,   -- دوام خلال آخر ٢٤ ساعة (أمس أو اليوم)
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
      'today', v_today,
      'dates', coalesce((select json_object_agg(project_code, d) from lastd), '{}'::json),
      'stale_projects', coalesce((select json_agg(project_code) from lastd where d < v_today - 1), '[]'::json),
      'rows', coalesce((select json_agg(json_build_object(
          'iqama', r.iqama, 'name', r.name, 'project', r.project, 'captain_id', r.captain_id,
          'work_date', r.work_date, 'plates', r.plates,
          'hours', case when r.present then r.last_hours end,
          'orders', case when r.present then coalesce(r.last_orders, 0) else 0 end,
          'present', r.present, 'logged', r.logged, 'warns', r.warns,
          'short', r.present and coalesce(r.last_hours, 0) < 10,
          'last_date', r.last_date, 'last_hours', r.last_hours, 'last_orders', r.last_orders,
          'days_absent', case when r.last_date is not null then v_today - r.last_date end,
          'last_text', case
             when r.last_date is null then 'لم يُسجَّل له أي دوام خلال آخر ١٢٠ يوماً'
             else 'آخر دوام: ' || to_char(r.last_date, 'YYYY-MM-DD')
                  || case when r.last_hours is not null then ' · ' || trim(to_char(r.last_hours, 'FM990.0')) || ' ساعة' else '' end
                  || ' · ' || coalesce(r.last_orders, 0) || ' طلب'
                  || case when v_today - r.last_date >= 2 then ' · منذ ' || (v_today - r.last_date) || ' يوم' else '' end end,
          'result', case
             when not r.has_data then case when r.plates is not null then 'لا يوجد دوام مسجّل · معه مركبة' else 'لا يوجد دوام مسجّل' end
             when r.present and r.plates is not null then 'مداوم'
             when r.present then 'مداوم · بدون مركبة مسجّلة'
             when r.plates is not null then 'غائب ومعه مركبة'
             else 'غائب بدون مركبة' end,
          'level', case when not r.has_data then 'nodata' when r.present then 'present'
                        when r.plates is not null then 'absent_vehicle' else 'absent' end)
          order by (case when not r.has_data then 3 when r.present then 2 when r.plates is not null then 0 else 1 end),
                   (case when r.present then null else r.last_date end) asc nulls first,
                   coalesce(r.last_hours, 0), r.name)
        from rows r), '[]'::json))
  );
end $$;
grant execute on function public.soot_roster(text) to authenticated;
