-- v144 — السلف: سلفة «صوت» المعتمدة تبقى معلقة حتى يسجل صرفها من وثيق + حصر السلف المعتمدة
--  pay_status: pending (معتمدة لم تصرف) | paid (صرفت) | void (الغي صرفها). لا تخصم من الراتب الا بعد الصرف (في وثيق).
begin;

alter table public.salary_advances add column if not exists pay_status text not null default 'pending';
alter table public.salary_advances add column if not exists paid_on date;
alter table public.salary_advances add column if not exists paid_at timestamptz;
alter table public.salary_advances add column if not exists paid_by uuid;
alter table public.salary_advances add column if not exists paid_by_name text;
alter table public.salary_advances add column if not exists pay_note text;
alter table public.salary_advances add column if not exists deducted_period text;
alter table public.salary_advances add column if not exists deducted_at timestamptz;

-- تسجيل قرار السلفة: المعتمدة لا تغلق في الصندوق حتى تصرف
create or replace function public._soot_log_advance(p_id uuid)
  returns void language plpgsql security definer set search_path = public as $$
declare a public.salary_advances%rowtype; e public.employees%rowtype; v_by text; v_st text; v_body text; v_inbox uuid;
begin
  select * into a from public.salary_advances where id = p_id;
  if a.id is null or a.status = 'pending' or a.watheeq_logged_at is not null then return; end if;
  select * into e from public.employees where id = a.employee_id;
  select coalesce(full_name, email) into v_by from public.profiles where id = a.reviewed_by;
  v_st := case a.status when 'approved' then 'معتمدة' else 'مرفوضة' end;
  v_body := 'سلفة ' || trim(to_char(a.amount, 'FM999990.##')) || ' ريال — ' || v_st
         || coalesce(' · السبب: ' || a.reason, '') || coalesce(' · ملاحظة: ' || a.note, '');

  insert into public.audit_log(table_name, row_id, action, old_data, new_data, changed_by, changed_at)
  values('employees', a.employee_id::text, 'salary_advance', null,
    jsonb_build_object('changes', jsonb_strip_nulls(jsonb_build_object(
        'سلفة', trim(to_char(a.amount, 'FM999990.##')) || ' ريال — ' || v_st,
        'سبب السلفة', a.reason,
        'توصية المشرف', case a.rec_status when 'ok' then 'موافق' when 'no' then 'غير موافق' end
                         || coalesce(' — ' || a.rec_by_name, ''),
        'ملاحظة القرار', a.note)),
      'advance_id', a.id, 'amount', a.amount, 'status', a.status),
    a.reviewed_by, coalesce(a.reviewed_at, now()));

  begin
    if a.inbox_id is not null then
      -- المعتمدة تبقى مفتوحة «قيد الصرف» حتى يسجل صرفها من وثيق
      update public.inbox_items set status = case when a.status = 'approved' then 'in_progress' else 'rejected' end,
             closed_at = case when a.status = 'approved' then null else coalesce(a.reviewed_at, now()) end,
             closed_by = case when a.status = 'approved' then null else a.reviewed_by end,
             close_note = v_st || ' — ' || coalesce(v_by, '') || case when a.status = 'approved' then ' · بانتظار الصرف من وثيق' else '' end
       where id = a.inbox_id;
    else
      insert into public.inbox_items(kind, system, title, body, ref, status, project, created_by, created_by_name, closed_at, closed_by, close_note)
      values('soot_advance', 'watheeq',
        'طلب سلفة (تطبيق الموظف): ' || coalesce(e.full_name_ar, e.full_name_en, a.iqama), v_body,
        jsonb_build_object('employee_id', a.employee_id, 'iqama', a.iqama, 'advance_id', a.id, 'amount', a.amount),
        case when a.status = 'approved' then 'in_progress' else 'rejected' end, e.project_code,
        a.reviewed_by, coalesce(e.full_name_ar, e.full_name_en, a.iqama),
        case when a.status = 'approved' then null else coalesce(a.reviewed_at, now()) end,
        case when a.status = 'approved' then null else a.reviewed_by end,
        v_st || ' — ' || coalesce(v_by, '') || case when a.status = 'approved' then ' · بانتظار الصرف من وثيق' else '' end)
      returning id into v_inbox;
      update public.salary_advances set inbox_id = v_inbox where id = a.id;
    end if;
  exception when others then
    raise warning 'soot advance inbox: %', sqlerrm;
  end;

  update public.salary_advances set watheeq_logged_at = now() where id = a.id;
end $$;


create or replace function public._soot_can_pay_advances()
  returns boolean language sql stable security definer set search_path = public as $$
  select coalesce(public.my_role()::text in ('admin', 'manager', 'finance'), false)
$$;

-- حصر السلف المعتمدة (لوثيق): معلقة / مصروفة / ملغاة
create or replace function public.soot_advances_approved()
  returns json language plpgsql stable security definer set search_path = public as $$
begin
  if not public._soot_can_pay_advances() then return '[]'::json; end if;
  return coalesce((select json_agg(json_build_object(
      'id', a.id, 'iqama', coalesce(e.iqama, a.iqama), 'amount', a.amount, 'reason', a.reason,
      'approved_at', a.reviewed_at, 'approved_by', (select coalesce(p.full_name, p.email) from public.profiles p where p.id = a.reviewed_by),
      'pay_status', a.pay_status, 'paid_on', a.paid_on, 'paid_at', a.paid_at, 'paid_by_name', a.paid_by_name, 'pay_note', a.pay_note,
      'deducted_period', a.deducted_period)
      order by a.reviewed_at)
    from public.salary_advances a left join public.employees e on e.id = a.employee_id
   where a.status = 'approved'), '[]'::json);
end $$;
grant execute on function public.soot_advances_approved() to authenticated;

-- تسجيل الصرف من وثيق: pay | void | reopen · وتسجيل الخصم من الراتب: deduct | undeduct
create or replace function public.soot_advance_pay(p_id uuid, p_action text default 'pay', p_date date default null, p_note text default null)
  returns json language plpgsql security definer set search_path = public as $$
declare a public.salary_advances%rowtype; v_nm text; v_txt text; v_new text;
        v_day date := coalesce(p_date, (now() at time zone 'Asia/Riyadh')::date);
begin
  if not public._soot_can_pay_advances() then raise exception 'تسجيل صرف السلف للمالية والإدارة فقط'; end if;
  select * into a from public.salary_advances where id = p_id for update;
  if a.id is null or a.status <> 'approved' then raise exception 'السلفة غير موجودة أو غير معتمدة'; end if;
  select coalesce(full_name, email) into v_nm from public.profiles where id = auth.uid();
  if p_action = 'pay' then
    if a.pay_status = 'paid' then raise exception 'سُجّل صرف هذه السلفة من قبل'; end if;
    v_new := 'paid'; v_txt := 'صُرفت ' || to_char(v_day, 'YYYY-MM-DD');
  elsif p_action = 'void' then
    if a.pay_status = 'paid' then raise exception 'السلفة مصروفة — تراجع عن الصرف أولاً'; end if;
    v_new := 'void'; v_txt := 'أُلغي صرفها';
  elsif p_action = 'reopen' then
    if a.deducted_period is not null then raise exception 'السلفة خُصمت من راتب % — لا يمكن التراجع عن صرفها', a.deducted_period; end if;
    v_new := 'pending'; v_txt := 'أُعيدت معلّقة (لم تُصرف)';
  elsif p_action in ('deduct', 'undeduct') then
    if a.pay_status <> 'paid' then raise exception 'السلفة لم تُصرف بعد'; end if;
    v_txt := case when p_action = 'deduct' then coalesce(nullif(btrim(coalesce(p_note, '')), ''), to_char(v_day, 'YYYY-MM')) end;
    if a.deducted_period is not distinct from v_txt then return json_build_object('ok', true, 'deducted_period', v_txt); end if;
    update public.salary_advances set deducted_period = v_txt, deducted_at = case when v_txt is null then null else now() end where id = a.id;
    begin
      insert into public.audit_log(table_name, row_id, action, old_data, new_data, changed_by, changed_at)
      values('employees', a.employee_id::text, 'salary_advance', null,
        jsonb_build_object('changes', jsonb_build_object('خصم السلفة',
            trim(to_char(a.amount, 'FM999990.##')) || ' ريال — ' || coalesce('خُصمت من راتب ' || v_txt, 'أُلغي تسجيل الخصم')),
          'advance_id', a.id, 'amount', a.amount, 'deducted_period', v_txt),
        auth.uid(), now());
    exception when others then raise warning 'soot advance deduct log: %', sqlerrm; end;
    return json_build_object('ok', true, 'deducted_period', v_txt);
  else
    raise exception 'إجراء غير معروف';
  end if;
  update public.salary_advances
     set pay_status = v_new,
         paid_on = case when v_new = 'paid' then v_day end,
         paid_at = case when v_new = 'pending' then null else now() end,
         paid_by = case when v_new = 'pending' then null else auth.uid() end,
         paid_by_name = case when v_new = 'pending' then null else v_nm end,
         pay_note = nullif(btrim(coalesce(p_note, '')), '')
   where id = a.id;
  begin
    insert into public.audit_log(table_name, row_id, action, old_data, new_data, changed_by, changed_at)
    values('employees', a.employee_id::text, 'salary_advance', null,
      jsonb_build_object('changes', jsonb_strip_nulls(jsonb_build_object(
          'صرف السلفة', trim(to_char(a.amount, 'FM999990.##')) || ' ريال — ' || v_txt,
          'ملاحظة', nullif(btrim(coalesce(p_note, '')), ''))),
        'advance_id', a.id, 'amount', a.amount, 'pay_status', v_new),
      auth.uid(), now());
    if a.inbox_id is not null then
      update public.inbox_items
         set status = case v_new when 'paid' then 'done' when 'void' then 'rejected' else 'in_progress' end,
             closed_at = case when v_new = 'pending' then null else now() end,
             closed_by = case when v_new = 'pending' then null else auth.uid() end,
             close_note = 'معتمدة · ' || v_txt || ' — ' || coalesce(v_nm, '')
       where id = a.inbox_id;
    end if;
  exception when others then raise warning 'soot advance pay log: %', sqlerrm; end;
  return json_build_object('ok', true, 'pay_status', v_new, 'paid_on', case when v_new = 'paid' then v_day end);
end $$;
grant execute on function public.soot_advance_pay(uuid, text, date, text) to authenticated;

-- تطبيق الموظف: حالة الصرف مع كل سلفة
create or replace function public.emp_my_advances(p_token text)
  returns json language plpgsql stable security definer set search_path = public as $$
declare v_emp uuid;
begin
  v_emp := public._emp_from_token(p_token);
  if v_emp is null then raise exception 'انتهت الجلسة'; end if;
  return coalesce((select json_agg(json_build_object(
      'id', id, 'amount', amount, 'reason', reason, 'status', status, 'note', note, 'created_at', created_at,
      'pay', case when status = 'approved' then pay_status end, 'paid_on', paid_on, 'deducted', deducted_period)
      order by created_at desc)
    from public.salary_advances where employee_id = v_emp), '[]'::json);
end $$;
grant execute on function public.emp_my_advances(text) to anon, authenticated;

-- السلف المعتمدة سابقا ما زال بندها في الصندوق مغلقا: يعاد «قيد الصرف» حتى يسجل صرفها
update public.inbox_items i set status = 'in_progress', closed_at = null, closed_by = null,
       close_note = coalesce(i.close_note, '') || ' · بانتظار الصرف من وثيق'
  from public.salary_advances a
 where a.inbox_id = i.id and a.status = 'approved' and a.pay_status = 'pending' and i.status = 'done';

commit;
