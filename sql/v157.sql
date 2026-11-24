-- v157: default-deny for anonymous callers. Only the functions the employee app, the legacy maintenance app
-- and the sign-up page call stay open to anon. Every revoked signature is recorded for rollback:
--   select format('grant execute on function %s to anon;', sig) from public.anon_revoked_log;
create table if not exists public.anon_revoked_log(sig text primary key, at timestamptz not null default now());
alter table public.anon_revoked_log enable row level security;
revoke all on public.anon_revoked_log from anon, authenticated;
do $$ declare f record; begin
  for f in select p.oid, p.oid::regprocedure::text as sig from pg_proc p
    where p.pronamespace = 'public'::regnamespace and p.prokind = 'f' and p.prorettype <> 'trigger'::regtype
      and has_function_privilege('anon', p.oid, 'execute')
      and not exists (select 1 from pg_depend d where d.objid = p.oid and d.deptype = 'e')
      and left(p.proname, 4) not in ('emp_', 'dbb_', 'fld_')
      and p.proname not in ('soot_broadcast_ack','soot_broadcasts_unread','soot_warn_for','soot_broadcasts_for','soot_app_login_touch','signup_projects','whoami')
  loop
    if has_function_privilege('authenticated', f.oid, 'execute') then
      execute format('grant execute on function %s to authenticated', f.sig);
    end if;
    execute format('grant execute on function %s to service_role, supabase_auth_admin', f.sig);
    execute format('revoke execute on function %s from public, anon', f.sig);
    insert into public.anon_revoked_log(sig) values (f.sig) on conflict do nothing;
  end loop;
end $$;
