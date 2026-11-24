-- v161 (applied 2026-11-24): old maintenance app (shared code, no official sign-in): each device may upload its data
-- one last time, then it is closed. New writes without an official account are refused. Signed-in accounts are not affected.
-- rollback: drop function public.dbb_push(text, text, jsonb, text, text); alter function public._dbb_push_core(text, text, jsonb, text, text) rename to dbb_push;
--           grant execute on function public.dbb_push(text, text, jsonb, text, text) to anon, authenticated; then restore api_gate from v158.sql
create table if not exists public.dbb_legacy_drain(file text primary key, drained_at timestamptz not null default now());
alter table public.dbb_legacy_drain enable row level security;
revoke all on public.dbb_legacy_drain from anon, authenticated;
do $$ declare o oid; args text; res text; names text; idargs text; begin
  if exists (select 1 from pg_proc where pronamespace = 'public'::regnamespace and proname = '_dbb_push_core') then raise notice 'already wrapped'; return; end if;
  select p.oid, pg_get_function_arguments(p.oid), pg_get_function_result(p.oid), array_to_string(p.proargnames, ', '), pg_get_function_identity_arguments(p.oid)
    into strict o, args, res, names, idargs from pg_proc p where p.pronamespace = 'public'::regnamespace and p.proname = 'dbb_push';
  if position('p_file' in names) = 0 then raise exception 'p_file not found: %', names; end if;
  execute format('alter function public.dbb_push(%s) rename to _dbb_push_core', idargs);
  execute format('revoke execute on function public._dbb_push_core(%s) from public, anon, authenticated', idargs);
  execute 'create function public.dbb_push(' || args || ') returns ' || res || ' language plpgsql security definer set search_path to ''public'' as $w$ declare r ' || res || '; official boolean := coalesce(auth.role(), '''') = ''authenticated'' and public.my_role() is not null; begin if not official and exists (select 1 from public.dbb_legacy_drain d where d.file = p_file) then raise exception ''تم ايقاف التطبيق القديم بعد رفع بياناتك. ادخل من رابط المنصة بحسابك''; end if; r := public._dbb_push_core(' || names || '); if not official then insert into public.dbb_legacy_drain(file) values (p_file) on conflict do nothing; end if; return r; end $w$';
  execute format('grant execute on function public.dbb_push(%s) to anon, authenticated, service_role', idargs);
end $$;
create or replace function public.api_gate() returns void language plpgsql security definer set search_path to 'public' as $fn$
declare p text := coalesce(current_setting('request.path', true), ''); r text := coalesce(auth.role(), '');
begin
  if r = 'authenticated' and public.my_role() is null and p not in ('/rpc/whoami', '/rpc/my_access', '/rpc/signup_projects') then
    raise sqlstate '42501' using message = 'account is not approved yet';
  end if;
  if r not in ('authenticated', 'service_role') and p in ('/rpc/dbb_save', '/rpc/fld_report', '/rpc/fld_update') then
    raise sqlstate '42501' using message = 'sign in from the platform link';
  end if;
end $fn$;
