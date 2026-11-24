-- v150 — صوت: الشكوى التي حلها المشرف ولم يؤكد الموظف اغلاقها خلال 3 ايام تغلق تلقائيا، ويمكن اعادة فتحها من الطرفين
begin;

-- 1) الاغلاق التلقائي: يحسب من وقت «تم الحل» (resolved_at)
create or replace function public.soot_autoclose(p_days integer default 3)
  returns integer language plpgsql security definer set search_path = public as $$
declare r record; n integer := 0; v_days integer := greatest(coalesce(p_days, 3), 1);
begin
  for r in
    update public.complaints set status = 'done'
     where status <> 'done' and resolved_at is not null and deleted_at is null
       and resolved_at < now() - make_interval(days => v_days)
    returning id
  loop
    insert into public.complaint_events(complaint_id, kind, actor_kind, actor_name, note)
    values (r.id, 'resolved', 'system', 'النظام',
            'إغلاق تلقائي — لم يؤكد الموظف خلال ' || v_days || ' أيام من الحل. يمكن إعادة فتحها من الموظف أو المشرف.');
    n := n + 1;
  end loop;
  return n;
end $$;
revoke all on function public.soot_autoclose(integer) from public, anon, authenticated;

-- 2) اعادة الفتح من المشرف / القسم / الادارة (كل من يرى الشكوى)
create or replace function public.sup_reopen(p_complaint uuid, p_note text default null)
  returns public.complaints language plpgsql security definer set search_path = public as $$
declare v_name text; v_row public.complaints;
begin
  if not public.sup_can(p_complaint) then raise exception 'غير مصرّح'; end if;
  select full_name into v_name from public.profiles where id = auth.uid();
  update public.complaints
     set status = 'prog', resolved_at = null, sla_due_at = now() + make_interval(hours => coalesce(sla_hours, 24))
   where id = p_complaint and status = 'done' and deleted_at is null
  returning * into v_row;
  if v_row.id is null then raise exception 'الشكوى ليست مغلقة'; end if;
  insert into public.complaint_events(complaint_id, kind, actor_kind, actor_name, note)
  values (p_complaint, 'reopened', 'supervisor', coalesce(v_name, 'مشرف'),
          'إعادة فتح بعد الإغلاق' || coalesce(' · ' || nullif(btrim(coalesce(p_note, '')), ''), ''));
  return v_row;
end $$;
grant execute on function public.sup_reopen(uuid, text) to authenticated;

-- 3) اعادة الفتح من الموظف صاحب الشكوى
create or replace function public.emp_reopen(p_token text, p_complaint uuid, p_note text default null)
  returns public.complaints language plpgsql security definer set search_path = public as $$
declare v_emp uuid := public._emp_from_token(p_token); v_row public.complaints; v_name text;
begin
  if v_emp is null then raise exception 'انتهت الجلسة'; end if;
  select full_name_ar into v_name from public.employees where id = v_emp;
  update public.complaints
     set status = 'prog', resolved_at = null, sla_due_at = now() + make_interval(hours => coalesce(sla_hours, 24))
   where id = p_complaint and employee_id = v_emp and status = 'done' and deleted_at is null
  returning * into v_row;
  if v_row.id is null then raise exception 'الشكوى غير موجودة أو ليست مغلقة'; end if;
  insert into public.complaint_events(complaint_id, kind, actor_kind, actor_name, note)
  values (p_complaint, 'reopened', 'employee', coalesce(v_name, 'الموظف'),
          'إعادة فتح بعد الإغلاق' || coalesce(' · ' || nullif(btrim(coalesce(p_note, '')), ''), ''));
  return v_row;
end $$;
grant execute on function public.emp_reopen(text, uuid, text) to anon, authenticated;

-- 4) تشغيل الاغلاق التلقائي كل ساعة
select cron.unschedule(jobid) from cron.job where jobname = 'soot_autoclose';
select cron.schedule('soot_autoclose', '7 * * * *', $cron$select public.soot_autoclose(3);$cron$);

commit;
