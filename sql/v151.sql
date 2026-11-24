-- v151 (applied 2026-11-24): close anonymous access found by the security review
-- 1) employee app auth tables: no direct access, SECURITY DEFINER functions only
alter table public.employee_auth enable row level security;
alter table public.employee_sessions enable row level security;
revoke all on public.employee_auth, public.employee_sessions from anon, authenticated;
-- 2) views that were readable without sign-in
revoke select on public.v_employee_first_seen, public.v_data_review_approved, public.v_samurai_captain_days, public.v_sup_settings, public.v_employee_ids, public.v_stops_daily, public.v_pending_advances, public.ninja_last_work, public.v_sup_tracking, public.v_areas, public.v_data_review_scoped from anon;
-- 3) functions that must require a signed-in user
do $$ declare f record; begin
  for f in select p.oid::regprocedure as sig from pg_proc p where p.pronamespace = 'public'::regnamespace
    and p.proname in ('soot_broadcast_send','soot_warn_issue','sup_set_priority','vehicles_by_iqama','housing_by_iqama') loop
    execute format('revoke execute on function %s from public, anon', f.sig);
    execute format('grant execute on function %s to authenticated', f.sig);
  end loop;
end $$;
-- 4) end all employee app sessions (rows kept; employees sign in again with the same PIN)
update public.employee_sessions set expires_at = now() where expires_at > now();
