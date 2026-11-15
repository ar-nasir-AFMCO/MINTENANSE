-- ════════════════════════════════════════════════════════════════════
-- v133 — إصلاح وصول الرسائل الموجّهة (مُطبّق على القاعدة 15/11/2026)
--   • soot_broadcasts_unread كانت تفشل دائماً: lower() على نوع المركبة (enum) → التطبيق ما يستلم شي
--   • مطابقة المركبة: bike/دبّاب = motorcycle · car/سيارة = car · van/فان = van
--   • مطابقة المدينة: الأسماء العربية في الإرسال ↔ رموز وثيق (RIYADH, JADAH, DAMAM…)
--   • soot_broadcasts_for (صندوق الرسائل) يأخذ مدينة ومركبة الموظف من وثيق تلقائياً
-- ════════════════════════════════════════════════════════════════════
begin;

create or replace function public._soot_norm_vehicle(p text)
  returns text language sql immutable as $$
  select case
    when p is null or btrim(p) = '' then null
    when lower(btrim(p)) in ('bike','motorcycle','motorbike','moto','دباب','دبّاب','دراجة','دراجة نارية') then 'motorcycle'
    when lower(btrim(p)) in ('car','sedan','سيارة') then 'car'
    when lower(btrim(p)) in ('van','فان') then 'van'
    else lower(btrim(p)) end
$$;

create or replace function public._soot_norm_city(p text)
  returns text language sql immutable as $$
  select case
    when p is null or btrim(p) = '' then null
    when upper(btrim(p)) in ('RIYADH','RIYAD','الرياض') then 'RIYADH'
    when upper(btrim(p)) in ('JADAH','JEDDAH','JIDDAH','JEDDA','جدة','جده') then 'JEDDAH'
    when upper(btrim(p)) in ('MAKKAH','MECCA','MAKKA','مكة','مكه','مكة المكرمة') then 'MAKKAH'
    when upper(btrim(p)) in ('MADINAH','MEDINA','MADINA','المدينة','المدينة المنورة') then 'MADINAH'
    when upper(btrim(p)) in ('DAMAM','DAMMAM','الدمام') then 'DAMMAM'
    when upper(btrim(p)) in ('KHOBAR','ALKHOBAR','AL KHOBAR','الخبر') then 'KHOBAR'
    when upper(btrim(p)) in ('TAIF','الطائف') then 'TAIF'
    when upper(btrim(p)) in ('KHARJ','ALKHARJ','AL KHARJ','الخرج') then 'KHARJ'
    when upper(btrim(p)) in ('MAJMA','MAJMAAH','ALMAJMAAH','المجمعة') then 'MAJMAAH'
    else upper(btrim(p)) end
$$;

-- هل الرسالة موجّهة لهذا الموظف؟ (منطق واحد للصندوق والتنبيه)
create or replace function public._soot_bc_matches(b public.soot_broadcasts, e public.employees)
  returns boolean language sql stable as $$
  select (b.target->>'project' is null or lower(b.target->>'project') = lower(e.project_code::text))
     and (b.target->>'city'    is null or public._soot_norm_city(b.target->>'city') = public._soot_norm_city(e.area::text))
     and (b.target->>'vehicle' is null or public._soot_norm_vehicle(b.target->>'vehicle') = public._soot_norm_vehicle(e.vehicle_type::text))
     and (b.target->>'branch'  is null or lower(coalesce(e.data->>'branch','')) like '%' || lower(b.target->>'branch') || '%')
     and (b.target->'iqamas'   is null or b.target->'iqamas' ? e.iqama)
$$;

create or replace function public.soot_broadcasts_unread(p_token text)
  returns json language plpgsql stable security definer set search_path = public as $$
declare e public.employees%rowtype;
begin
  select * into e from public.employees where id = public._emp_from_token(p_token);
  if e.id is null then raise exception 'انتهت الجلسة'; end if;
  return coalesce((select json_agg(json_build_object('id', b.id, 'title', b.title, 'body', b.body,
            'by_name', b.created_by_name, 'created_at', b.created_at) order by b.created_at)
    from public.soot_broadcasts b
    where b.active and b.require_ack
      and (b.expires_at is null or b.expires_at > now())
      and public._soot_bc_matches(b, e)
      and not exists (select 1 from public.soot_broadcast_reads r where r.broadcast_id = b.id and r.iqama = e.iqama)), '[]'::json);
end $$;
grant execute on function public.soot_broadcasts_unread(text) to anon, authenticated;

-- صندوق الرسائل: نفس المطابقة، ببيانات الموظف من وثيق (المدينة/المركبة/الفرع)
create or replace function public.soot_broadcasts_for(p_iqama text, p_project text default null, p_city text default null, p_vehicle text default null, p_branch text default null)
  returns setof public.soot_broadcasts language sql stable security definer set search_path = public as $$
  select b.* from public.soot_broadcasts b
    join public.employees e on e.iqama = p_iqama and e.deleted_at is null
   where b.active and (b.expires_at is null or b.expires_at > now())
     and public._soot_bc_matches(b, e)
   order by b.created_at desc
$$;
grant execute on function public.soot_broadcasts_for(text,text,text,text,text) to anon, authenticated;

commit;
