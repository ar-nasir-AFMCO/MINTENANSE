-- ════════════════════════════════════════════════════════════════════
-- v127 — قائمة المناطق + دالة دور المستخدم (لعزل صفحة الحضور لمديري النظام)
--   • v_areas: المناطق الفعلية من الموظفين (لقائمة منسدلة في إعدادات المشرف)
--   • soot_my_role(): يرجّع دور المستخدم الحالي (admin/manager/supervisor…)
-- ════════════════════════════════════════════════════════════════════

begin;

-- مناطق التغطية المتاحة (مميّزة من بيانات الموظفين)
create or replace view public.v_areas as
  select distinct btrim(area) as area
  from public.employees
  where coalesce(btrim(area), '') <> '' and deleted_at is null
  order by 1;
grant select on public.v_areas to anon, authenticated;

-- دور المستخدم الحالي (لعزل الوصول)
create or replace function public.soot_my_role()
  returns text language sql stable security definer set search_path = public as
$$ select role::text from public.profiles where id = auth.uid() $$;
grant execute on function public.soot_my_role() to authenticated;

commit;
