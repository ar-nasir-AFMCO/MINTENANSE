-- v143 — كيمارت: لوحة الاداء تسحب تلقائيا ايضا + تتبع السحب لكل يوم ونوع (attendance / board)
--  keemart_sync_days: متى سحب كل يوم لكل نوع. اليوم يعاد سحبه دائما، واليوم السابق حتى يكتمل (بعد 06:00 من اليوم التالي).
--  لوحة الاداء تطلب مرة كل ساعة تقريبا (تفتح تبويبا خلفيا)، وتقرير الدوام كل 20 دقيقة.
begin;

create table if not exists public.keemart_sync_days (
  kind      text        not null,
  work_date date        not null,
  pulled_at timestamptz not null default now(),
  rows      integer,
  primary key (kind, work_date));
alter table public.keemart_sync_days enable row level security;
drop policy if exists keemart_sync_days_read on public.keemart_sync_days;
create policy keemart_sync_days_read on public.keemart_sync_days for select to authenticated using (true);
grant select on public.keemart_sync_days to authenticated;
alter table public.keemart_sync_state add column if not exists last_board_try timestamptz;

-- ما سحب حتى الان يسجل كما هو
insert into public.keemart_sync_days(kind, work_date, pulled_at, rows)
select 'attendance', work_date, max(pulled_at), count(*) from public.keemart_captain_days
 where src in ('attendance', 'both') and work_date >= current_date - 40 group by work_date
on conflict do nothing;
insert into public.keemart_sync_days(kind, work_date, pulled_at, rows)
select 'board', work_date, max(pulled_at), count(*) from public.keemart_captain_days
 where src in ('board', 'both') and ont_rate is not null and work_date >= current_date - 40 group by work_date
on conflict do nothing;

create or replace function public.keemart_sync_plan(p_force boolean default false)
  returns json language plpgsql security definer set search_path = public as $$
declare v_today date := (now() at time zone 'Asia/Riyadh')::date; v_days json; v_board json := '[]'::json;
        v_st public.keemart_sync_state%rowtype; v_do_board boolean;
begin
  if auth.uid() is null or not (select public.has_system('keemart')) or not (select public.can_do('edit')) then
    return json_build_object('days', '[]'::json, 'board_days', '[]'::json, 'skip', 'no-permission');
  end if;
  select * into v_st from public.keemart_sync_state where id for update;
  if not coalesce(p_force, false) and v_st.last_try > now() - interval '12 minutes' then
    return json_build_object('days', '[]'::json, 'board_days', '[]'::json, 'skip', 'recent', 'last_ok', v_st.last_ok);
  end if;
  select coalesce(json_agg(to_char(x.d, 'YYYY-MM-DD') order by x.d), '[]'::json) into v_days
    from (select (v_today - s)::date as d from generate_series(0, 9) s) x
    left join public.keemart_sync_days k on k.kind = 'attendance' and k.work_date = x.d
   where x.d = v_today or k.pulled_at is null
      or k.pulled_at < (((x.d + 1)::timestamp + interval '6 hours') at time zone 'Asia/Riyadh');
  v_do_board := coalesce(p_force, false) or v_st.last_board_try is null or v_st.last_board_try < now() - interval '55 minutes';
  if v_do_board then
    select coalesce(json_agg(to_char(x.d, 'YYYY-MM-DD') order by x.d), '[]'::json) into v_board
      from (select (v_today - s)::date as d from generate_series(0, 9) s) x
      left join public.keemart_sync_days k on k.kind = 'board' and k.work_date = x.d
     where x.d = v_today or k.pulled_at is null
        or k.pulled_at < (((x.d + 1)::timestamp + interval '6 hours') at time zone 'Asia/Riyadh');
  end if;
  update public.keemart_sync_state
     set last_try = now(), last_board_try = case when v_do_board then now() else last_board_try end, last_by = auth.uid()
   where id;
  return json_build_object('today', to_char(v_today, 'YYYY-MM-DD'), 'days', v_days, 'board_days', v_board, 'last_ok', v_st.last_ok);
end $$;
grant execute on function public.keemart_sync_plan(boolean) to authenticated;

create or replace function public.keemart_sync_done(p_kind text, p_day date, p_rows integer default null)
  returns void language plpgsql security definer set search_path = public as $$
begin
  if auth.uid() is null or not (select public.has_system('keemart')) or not (select public.can_do('edit')) then return; end if;
  if p_kind not in ('attendance', 'board') or p_day is null then return; end if;
  insert into public.keemart_sync_days as d (kind, work_date, pulled_at, rows) values (p_kind, p_day, now(), p_rows)
  on conflict (kind, work_date) do update set pulled_at = now(), rows = excluded.rows;
end $$;
grant execute on function public.keemart_sync_done(text, date, integer) to authenticated;

commit;
