-- v149 — روستر المناديب: يرجع اول واخر دخول للموظف على تطبيق صوت (للعرض والتصدير)
do $$
declare d text;
        o1 text := 'lg as (select distinct e2.iqama from public.employee_sessions s2 join public.employees e2 on e2.id = s2.employee_id),';
        n1 text := 'lg as (select e2.iqama, min(s2.created_at) as first_login, max(s2.created_at) as last_login from public.employee_sessions s2 join public.employees e2 on e2.id = s2.employee_id group by 1),';
        o2 text := '(lg.iqama is not null) as logged, coalesce(wr.n, 0) as warns,';
        n2 text := '(lg.iqama is not null) as logged, lg.first_login as app_first, lg.last_login as app_last, coalesce(wr.n, 0) as warns,';
        o3 text := '''present'', r.present, ''logged'', r.logged, ''warns'', r.warns,';
        n3 text := '''present'', r.present, ''logged'', r.logged, ''warns'', r.warns, ''app_first'', to_char(r.app_first at time zone ''Asia/Riyadh'', ''YYYY-MM-DD HH24:MI''), ''app_last'', to_char(r.app_last at time zone ''Asia/Riyadh'', ''YYYY-MM-DD HH24:MI''),';
begin
  select pg_get_functiondef(p.oid) into d from pg_proc p
   where p.proname = 'soot_roster' and p.pronamespace = 'public'::regnamespace;
  if d is null then raise exception 'soot_roster not found'; end if;
  if position(n1 in d) > 0 then return; end if;
  if position(o1 in d) = 0 or position(o2 in d) = 0 or position(o3 in d) = 0 then raise exception 'soot_roster: expected lines not found'; end if;
  execute replace(replace(replace(d, o1, n1), o2, n2), o3, n3);
end $$;
