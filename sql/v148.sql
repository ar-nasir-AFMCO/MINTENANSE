-- v148 — «مسجّل في تطبيق صوت» في روستر المناديب: يحسب من جلسات الدخول الفعلية (employee_sessions)
--  كان يعتمد على اشعار يرسله التطبيق بعد الدخول (soot_app_logins) فيفوت من دخل بنسخة اقدم او لم يصل اشعاره.
do $$
declare d text;
        o text := 'lg as (select distinct iqama from public.soot_app_logins where app = ''employee''),';
        n text := 'lg as (select distinct e2.iqama from public.employee_sessions s2 join public.employees e2 on e2.id = s2.employee_id),';
begin
  select pg_get_functiondef(p.oid) into d from pg_proc p
   where p.proname = 'soot_roster' and p.pronamespace = 'public'::regnamespace;
  if d is null then raise exception 'soot_roster not found'; end if;
  if position(n in d) > 0 then return; end if;
  if position(o in d) = 0 then raise exception 'soot_roster: lg line not found'; end if;
  execute replace(d, o, n);
end $$;
