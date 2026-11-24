-- v155: the supervisor issues the first PIN - first login no longer creates it
do $$ declare d text; a text := 'insert into public.employee_auth(employee_id, pin_hash, active, updated_at) values(v_emp.id, crypt(p_pin, gen_salt(''bf'')), true, now());';
begin
  select pg_get_functiondef(p.oid) into d from pg_proc p where p.pronamespace = 'public'::regnamespace and p.proname = '_emp_login_core';
  if position(a in d) = 0 then raise notice 'already patched'; return; end if;
  execute replace(d, a, 'raise exception ''لم يصدر لك رمز دخول بعد. اطلب الرمز من مشرفك'';');
end $$;
