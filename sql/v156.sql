-- v156: emp_set_pin limited to the supervisor's own projects (all-projects roles excepted); issuing a PIN clears the login lock
do $$ declare d text; a text := 'insert into public.employee_auth(employee_id,pin_hash,active,updated_at)';
begin
  select pg_get_functiondef(p.oid) into d from pg_proc p where p.pronamespace = 'public'::regnamespace and p.proname = 'emp_set_pin';
  if position('profile_projects' in d) > 0 then raise notice 'already patched'; return; end if;
  if position(a in d) = 0 then raise exception 'anchor not found'; end if;
  execute replace(d, a, 'if public.my_role() is null or not (coalesce((select rm.all_projects from public.role_matrix rm where rm.role::text = v_role), false) or exists (select 1 from public.profile_projects pp join public.employees e on e.project_code = pp.project_code where pp.profile_id = auth.uid() and e.id = v_emp)) then raise exception ''هذا الموظف ليس ضمن مشاريعك''; end if;
  update public.employee_login_guard set fails = 0, locked_until = null where iqama = p_iqama;
  ' || a);
end $$;
