-- ════════════════════════════════════════════════════════════════════
-- v128 — ربط المشرف بمواقع الدوام + حضور صارم (يرفض خارج الموقع ويحفظ المحاولة)
--   • supervisor_settings.site_ids: مواقع الدوام المسندة للمشرف (قائمة UUID مفصولة بفواصل)
--   • sup_checkin: يسجّل الحضور فقط داخل أحد مواقع المشرف؛ خارجها يُرفض وتُحفظ المحاولة
--   • sup_checkin_attempts: سجل محاولات الحضور خارج الموقع (لا يُحذف)
--   • sup_ping: التحقق الصامت يحسب الوجود داخل مواقع المشرف المسندة
--   • لا حذف — إضافة وتعديل في المكان
-- ════════════════════════════════════════════════════════════════════

begin;

alter table public.supervisor_settings add column if not exists site_ids text;

-- سجل محاولات الحضور المرفوضة (خارج الموقع)
create table if not exists public.sup_checkin_attempts(
  id uuid primary key default gen_random_uuid(),
  supervisor_id uuid not null,
  lat double precision, lng double precision,
  nearest_site_id uuid, nearest_site_name text, dist int,
  created_at timestamptz not null default now()
);
alter table public.sup_checkin_attempts enable row level security;
drop policy if exists sca_sel on public.sup_checkin_attempts;
create policy sca_sel on public.sup_checkin_attempts for select using (auth.uid() is not null);
grant select on public.sup_checkin_attempts to authenticated;

-- أقرب موقع ضمن المواقع المسندة للمشرف (أو كل المواقع إن لم تُسند له مواقع)
create or replace function public._nearest_site_for(p_lat double precision, p_lng double precision, p_uid uuid)
  returns table(id uuid, name text, radius int, dist double precision)
  language sql stable set search_path = public as $$
  with a as (select nullif(btrim((select site_ids from public.supervisor_settings where supervisor_id = p_uid)), '') as ids)
  select s.id, s.name, s.radius_m,
    6371000*2*asin(sqrt(power(sin(radians(p_lat - s.lat)/2),2)
      + cos(radians(s.lat))*cos(radians(p_lat))*power(sin(radians(p_lng - s.lng)/2),2))) as dist
  from public.work_sites s, a
  where s.active and s.deleted_at is null
    and (a.ids is null or s.id::text = any(string_to_array(a.ids, ',')))
  order by dist asc limit 1;
$$;

-- الحضور الصارم: داخل الموقع فقط؛ خارجه يُرفض وتُحفظ المحاولة
create or replace function public.sup_checkin(p_lat double precision, p_lng double precision)
  returns json language plpgsql security definer set search_path = public as $$
declare v record; v_id uuid;
begin
  select * into v from public._nearest_site_for(p_lat, p_lng, auth.uid());
  if v.id is not null and v.dist <= v.radius then
    -- داخل موقع مسند → يُسجّل الحضور
    insert into public.attendance_sessions(supervisor_id, site_id, site_name, in_lat, in_lng)
      values(auth.uid(), v.id, v.name, p_lat, p_lng) returning id into v_id;
    insert into public.sup_pings(supervisor_id, lat, lng, inside_site) values(auth.uid(), p_lat, p_lng, v.id);
    return json_build_object('session', v_id, 'inside', true, 'site', v.name, 'dist', round(v.dist));
  else
    -- خارج المواقع → يُرفض وتُحفظ المحاولة
    insert into public.sup_checkin_attempts(supervisor_id, lat, lng, nearest_site_id, nearest_site_name, dist)
      values(auth.uid(), p_lat, p_lng, v.id, v.name, round(coalesce(v.dist, 0)));
    return json_build_object('session', null, 'inside', false, 'rejected', true,
      'site', v.name, 'dist', round(coalesce(v.dist, 0)));
  end if;
end $$;
grant execute on function public.sup_checkin(double precision,double precision) to authenticated;

-- التحقق الصامت: الوجود داخل مواقع المشرف المسندة
create or replace function public.sup_ping(p_lat double precision, p_lng double precision, p_acc double precision default null)
  returns json language plpgsql security definer set search_path = public as $$
declare v record; v_inside uuid;
begin
  select * into v from public._nearest_site_for(p_lat, p_lng, auth.uid());
  if v.id is not null and v.dist <= v.radius then v_inside := v.id; end if;
  insert into public.sup_pings(supervisor_id, lat, lng, accuracy, inside_site) values(auth.uid(), p_lat, p_lng, p_acc, v_inside);
  return json_build_object('ok', true, 'inside', v_inside is not null);
end $$;
grant execute on function public.sup_ping(double precision,double precision,double precision) to authenticated;

-- إضافة site_ids للعرض
create or replace view public.v_sup_settings as
  select p.id as supervisor_id, p.full_name, p.role,
    coalesce(s.work_start,'08:00') as work_start,
    coalesce(s.work_end,'21:00')   as work_end,
    coalesce(s.grace_min,10)       as grace_min,
    s.areas, s.site_ids, s.updated_at
  from public.profiles p
  left join public.supervisor_settings s on s.supervisor_id = p.id
  where p.role in ('supervisor','manager','admin');
grant select on public.v_sup_settings to anon, authenticated;

commit;
