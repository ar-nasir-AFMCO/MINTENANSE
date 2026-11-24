-- v153: remaining findings of the security review
-- 1) employee login: lock after 5 wrong attempts (15 minutes). The old body stays as _emp_login_core.
create table if not exists public.employee_login_guard(
  iqama text primary key, fails integer not null default 0, last_fail timestamptz, locked_until timestamptz);
alter table public.employee_login_guard enable row level security;
revoke all on public.employee_login_guard from anon, authenticated;

do $$ begin
  if not exists (select 1 from pg_proc where pronamespace = 'public'::regnamespace and proname = '_emp_login_core') then
    alter function public.emp_login(text, text) rename to _emp_login_core;
  end if;
end $$;
revoke execute on function public._emp_login_core(text, text) from public, anon, authenticated;

create or replace function public.emp_login(p_iqama text, p_pin text) returns json
language plpgsql security definer set search_path to 'public' as $fn$
declare g public.employee_login_guard%rowtype; r json; k text := left(coalesce(p_iqama, ''), 20); m text; n integer;
begin
  select * into g from public.employee_login_guard where iqama = k;
  if g.locked_until is not null and g.locked_until > now() then
    perform set_config('response.status', '429', true);
    return json_build_object('code', 'P0001', 'message',
      'تم ايقاف المحاولات مؤقتا بعد عدة محاولات خاطئة. حاول بعد ' || greatest(1, ceil(extract(epoch from g.locked_until - now()) / 60))::int || ' دقيقة');
  end if;
  begin
    r := public._emp_login_core(p_iqama, p_pin);
  exception when others then
    m := sqlerrm;
    n := case when g.last_fail is null or g.last_fail < now() - interval '15 minutes' then 1 else g.fails + 1 end;
    insert into public.employee_login_guard(iqama, fails, last_fail, locked_until)
      values (k, n, now(), case when n >= 5 then now() + interval '15 minutes' end)
      on conflict (iqama) do update set fails = excluded.fails, last_fail = excluded.last_fail, locked_until = excluded.locked_until;
    perform set_config('response.status', '400', true);
    return json_build_object('code', 'P0001', 'message', m);
  end;
  update public.employee_login_guard set fails = 0, locked_until = null where iqama = k;
  return r;
end $fn$;
grant execute on function public.emp_login(text, text) to anon, authenticated;

-- 2) complaint routing settings: read for signed-in users only, writes through the existing functions only
do $$ declare t text; begin
  foreach t in array array['complaint_categories','complaint_departments','complaint_dept_staff','complaint_project_staff','complaint_routes','complaint_top_mgmt'] loop
    execute format('alter table public.%I enable row level security', t);
    execute format('revoke all on public.%I from anon', t);
    execute format('revoke insert, update, delete, truncate on public.%I from authenticated', t);
    execute format('drop policy if exists %I on public.%I', t || '_read', t);
    execute format('create policy %I on public.%I for select to authenticated using (true)', t || '_read', t);
  end loop;
end $$;

-- 3) complaints attachments bucket: images only, 8 MB, no anonymous listing
update storage.buckets set file_size_limit = 8388608, allowed_mime_types = array['image/jpeg','image/png'] where id = 'complaints';
drop policy if exists complaints_obj_read on storage.objects;
create policy complaints_obj_read on storage.objects for select to authenticated using (bucket_id = 'complaints');

-- 4) employee app: warnings / broadcasts / app-open by session token instead of a bare iqama
create or replace function public.emp_my_warnings(p_token text) returns setof public.soot_warnings
language sql security definer set search_path to 'public' as $fn$
  select w.* from public.employees e, lateral public.soot_warn_for(e.iqama) w where e.id = public._emp_from_token(p_token)
$fn$;
create or replace function public.emp_my_broadcasts(p_token text) returns setof public.soot_broadcasts
language sql security definer set search_path to 'public' as $fn$
  select b.* from public.employees e, lateral public.soot_broadcasts_for(e.iqama, e.project_code) b where e.id = public._emp_from_token(p_token)
$fn$;
create or replace function public.emp_touch(p_token text, p_device text default null) returns void
language sql security definer set search_path to 'public' as $fn$
  select public.soot_app_login_touch(e.iqama, e.project_code, null, coalesce(e.full_name_ar, e.full_name_en), 'employee', p_device)
  from public.employees e where e.id = public._emp_from_token(p_token)
$fn$;
grant execute on function public.emp_my_warnings(text), public.emp_my_broadcasts(text), public.emp_touch(text, text) to anon, authenticated;
