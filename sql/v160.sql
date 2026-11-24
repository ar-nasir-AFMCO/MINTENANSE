-- v160: (1) the three old employee functions no longer accept anonymous calls (employee app 1.41+ uses the session-token versions)
--       (2) an upload ticket no longer expires: any signed-in employee or approved user can attach photos at any time
do $$ declare f record; begin
  for f in select p.oid::regprocedure::text as sig from pg_proc p where p.pronamespace = 'public'::regnamespace
    and p.proname in ('soot_warn_for','soot_broadcasts_for','soot_app_login_touch') loop
    execute format('grant execute on function %s to authenticated, service_role', f.sig);
    execute format('revoke execute on function %s from public, anon', f.sig);
    insert into public.anon_revoked_log(sig) values (f.sig) on conflict do nothing;
  end loop;
end $$;
create or replace function public.upload_ticket_ok(p_name text) returns boolean
language plpgsql security definer set search_path to 'public' as $fn$
declare n integer;
begin
  update public.upload_tickets set used_at = coalesce(used_at, now()) where name = p_name;
  get diagnostics n = row_count;
  return n = 1;
end $fn$;
