-- v159: complaint photos - an upload needs a one-time ticket issued to a signed-in employee or an approved user
create table if not exists public.upload_tickets(name text primary key, issued_at timestamptz not null default now(), used_at timestamptz, by_employee uuid, by_user uuid);
alter table public.upload_tickets enable row level security;
revoke all on public.upload_tickets from anon, authenticated;

create or replace function public.upload_ticket(p_token text default null, p_ext text default 'jpg') returns text
language plpgsql security definer set search_path to 'public' as $fn$
declare v_emp uuid; v_user uuid := auth.uid(); v_name text; v_ext text := case when lower(coalesce(p_ext, '')) = 'png' then 'png' else 'jpg' end;
begin
  if p_token is not null then v_emp := public._emp_from_token(p_token); end if;
  if v_emp is null and (v_user is null or public.my_role() is null) then raise exception 'not allowed'; end if;
  v_name := (case when v_emp is not null then 'c_' else 's_' end) || to_char(now(), 'YYYYMMDD') || '_' || replace(gen_random_uuid()::text, '-', '') || '.' || v_ext;
  insert into public.upload_tickets(name, by_employee, by_user) values (v_name, v_emp, case when v_emp is null then v_user end);
  return v_name;
end $fn$;
grant execute on function public.upload_ticket(text, text) to anon, authenticated;

create or replace function public.upload_ticket_ok(p_name text) returns boolean
language plpgsql security definer set search_path to 'public' as $fn$
declare n integer;
begin
  update public.upload_tickets set used_at = coalesce(used_at, now()) where name = p_name and issued_at > now() - interval '15 minutes';
  get diagnostics n = row_count;
  return n = 1;
end $fn$;
grant execute on function public.upload_ticket_ok(text) to anon, authenticated;

drop policy if exists complaints_obj_insert on storage.objects;
create policy complaints_obj_insert on storage.objects for insert to anon, authenticated
  with check (bucket_id = 'complaints' and public.upload_ticket_ok(name));
