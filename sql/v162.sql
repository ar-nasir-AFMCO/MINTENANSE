-- v162: account holder name for the bank account (employee app -> request -> approval -> employee_pay.iban_holder)
alter table public.employee_pay add column if not exists iban_holder text;
alter table public.data_update_requests add column if not exists holder text;
do $$ declare d text;
  a1 text := 'p_new_value text)';
  a2 text := 'new_value, platform)';
  a3 text := 'values(v_emp.id, v_emp.iqama, p_field, v_label, v_old, v_new, v_plat)';
begin
  if exists (select 1 from pg_proc where pronamespace = 'public'::regnamespace and proname = 'emp_request_update' and pronargs = 4) then
    raise notice 'emp_request_update already patched';
  else
    select pg_get_functiondef(p.oid) into strict d from pg_proc p where p.pronamespace = 'public'::regnamespace and p.proname = 'emp_request_update';
    if position(a1 in d) = 0 or position(a2 in d) = 0 or position(a3 in d) = 0 then raise exception 'emp_request_update anchors not found'; end if;
    d := replace(d, a1, 'p_new_value text, p_holder text DEFAULT NULL::text)');
    d := replace(d, a2, 'new_value, platform, holder)');
    d := replace(d, a3, 'values(v_emp.id, v_emp.iqama, p_field, v_label || case when p_field = ''bank_account'' and nullif(btrim(coalesce(p_holder, '''')), '''') is not null then '' - صاحب الحساب: '' || btrim(p_holder) else '''' end, v_old, v_new, v_plat, nullif(btrim(coalesce(p_holder, '''')), ''''))');
    drop function public.emp_request_update(text, text, text);
    execute d;
    grant execute on function public.emp_request_update(text, text, text, text) to anon, authenticated;
  end if;
end $$;
do $$ declare d text; a text := '''{iban}'', to_jsonb(excluded.iban), true), updated_at = now();';
begin
  select pg_get_functiondef(p.oid) into strict d from pg_proc p where p.pronamespace = 'public'::regnamespace and p.proname = 'sup_review_update';
  if position('iban_holder' in d) > 0 then raise notice 'sup_review_update already patched'; return; end if;
  if position(a in d) = 0 then raise exception 'sup_review_update anchor not found'; end if;
  execute replace(d, a, a || ' update public.employee_pay set iban_holder = coalesce((select nullif(btrim(coalesce(r.holder, '''')), '''') from public.data_update_requests r where r.id = p_request), iban_holder) where employee_id = v_req.employee_id;');
end $$;
