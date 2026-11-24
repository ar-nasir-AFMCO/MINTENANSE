-- v158 (applied 2026-11-24): only approved accounts may use the data API.
-- A signed-up account that is still waiting for approval already gets the 'authenticated' role, so it could read
-- anything open to signed-in users. The gate runs before every data API request.
-- rollback: alter role authenticator reset pgrst.db_pre_request; notify pgrst, 'reload config';
create or replace function public.api_gate() returns void language plpgsql security definer set search_path to 'public' as $fn$
begin
  if coalesce(auth.role(), '') = 'authenticated' and public.my_role() is null
     and coalesce(current_setting('request.path', true), '') not in ('/rpc/whoami', '/rpc/my_access', '/rpc/signup_projects') then
    raise sqlstate '42501' using message = 'account is not approved yet';
  end if;
end $fn$;
grant execute on function public.api_gate() to anon, authenticated, service_role;
alter role authenticator set pgrst.db_pre_request to 'public.api_gate';
notify pgrst, 'reload config';
