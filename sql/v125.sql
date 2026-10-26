-- ════════════════════════════════════════════════════════════════════
-- v125 — ربط طلبات تحديث بيانات تطبيق الموظف بنظام وثيق
--   • كل طلب تحديث (جوال/مشروع/حساب بنكي) يُسجَّل في صندوق وثيق (inbox_items)
--   • عند اعتماد/رفض المشرف يُغلق عنصر الوارد في وثيق بنفس النتيجة
--   • تغييرات المشروع/الحساب تُسجَّل تلقائيًا في سجل تغييرات الموظف (تريغر موجود)
--   • best-effort: فشل التسجيل في وثيق لا يُفشل الطلب أو الاعتماد
--   • لا حذف — إضافة وتعديل في المكان
-- ════════════════════════════════════════════════════════════════════

begin;

-- ربط الطلب بعنصر صندوق الوارد في وثيق
alter table public.data_update_requests add column if not exists inbox_id uuid;

-- (1) إنشاء الطلب: يسجّل نسخة في صندوق وثيق
create or replace function public.emp_request_update(p_token text, p_field text, p_new_value text)
  returns json language plpgsql security definer set search_path = public as $$
declare v_emp public.employees%rowtype; v_old text; v_label text; v_id uuid; v_inbox uuid;
begin
  select * into v_emp from public.employees where id = public._emp_from_token(p_token);
  if v_emp.id is null then raise exception 'انتهت الجلسة'; end if;
  if p_field not in ('phone','project_code','bank_account') then raise exception 'حقل غير مدعوم'; end if;
  v_old := case p_field
             when 'phone' then v_emp.phone
             when 'project_code' then v_emp.project_code
             when 'bank_account' then v_emp.data->>'bank_account' end;
  v_label := case p_field when 'phone' then 'رقم الاتصال' when 'project_code' then 'المشروع' when 'bank_account' then 'الحساب البنكي' end;

  insert into public.data_update_requests(employee_id, iqama, field, field_label, old_value, new_value)
    values(v_emp.id, v_emp.iqama, p_field, v_label, v_old, p_new_value)
    returning id into v_id;

  -- نسخة في صندوق وثيق (لا تُفشل الطلب لو تعذّرت)
  begin
    insert into public.inbox_items(kind, system, title, body, ref, status, project, created_by, created_by_name)
    values('soot_data_update', 'watheeq',
      'طلب تحديث بيانات (تطبيق الموظف): ' || coalesce(v_emp.full_name_ar, v_emp.full_name_en, v_emp.iqama),
      v_label || ': ' || coalesce(v_old,'—') || '  ←  ' || p_new_value,
      jsonb_build_object('employee_id', v_emp.id, 'iqama', v_emp.iqama, 'field', p_field,
        'label', v_label, 'old', v_old, 'new', p_new_value, 'request_id', v_id),
      'new', v_emp.project_code, null, coalesce(v_emp.full_name_ar, v_emp.full_name_en, v_emp.iqama))
    returning id into v_inbox;
    update public.data_update_requests set inbox_id = v_inbox where id = v_id;
  exception when others then null; end;

  return json_build_object('id', v_id, 'status','pending');
end $$;
grant execute on function public.emp_request_update(text,text,text) to anon, authenticated;

-- (2) القرار: يحدّث بيانات وثيق + يُغلق عنصر الوارد في وثيق
create or replace function public.sup_review_update(p_request uuid, p_approve boolean, p_note text default null)
  returns json language plpgsql security definer set search_path = public as $$
declare v_req public.data_update_requests%rowtype; v_nm text; v_final text; v_emp_nm text; v_body text;
begin
  select * into v_req from public.data_update_requests where id = p_request and status='pending';
  if v_req.id is null then raise exception 'الطلب غير موجود أو تمّت معالجته'; end if;
  select coalesce(full_name, email) into v_nm from public.profiles where id = auth.uid();

  if p_approve then
    if v_req.field = 'phone' then
      update public.employees set phone = v_req.new_value, updated_at = now() where id = v_req.employee_id;
    elsif v_req.field = 'project_code' then
      update public.employees set project_code = v_req.new_value, updated_at = now() where id = v_req.employee_id;
    elsif v_req.field = 'bank_account' then
      update public.employees set data = jsonb_set(coalesce(data,'{}'::jsonb), '{bank_account}', to_jsonb(v_req.new_value)), updated_at = now() where id = v_req.employee_id;
    end if;
  end if;

  update public.data_update_requests
     set status = case when p_approve then 'approved' else 'rejected' end,
         note = p_note, reviewed_by = auth.uid(), reviewed_at = now()
   where id = p_request;

  v_final := case when p_approve then 'done' else 'rejected' end;

  -- إغلاق/تسجيل في صندوق وثيق (best-effort)
  begin
    if v_req.inbox_id is not null then
      update public.inbox_items
         set status = v_final, closed_at = now(), closed_by = auth.uid(),
             close_note = case when p_approve then 'اعتُمد عبر تطبيق المشرف — ' else 'رُفض عبر تطبيق المشرف — ' end || coalesce(v_nm,'')
       where id = v_req.inbox_id;
    else
      -- لم يُنشأ عنصر وقت الطلب؛ ننشئه الآن بالنتيجة النهائية
      select coalesce(full_name_ar, full_name_en, iqama) into v_emp_nm from public.employees where id = v_req.employee_id;
      v_body := v_req.field_label || ': ' || coalesce(v_req.old_value,'—') || '  ←  ' || v_req.new_value;
      insert into public.inbox_items(kind, system, title, body, ref, status, project, created_by, created_by_name, closed_at, closed_by, close_note)
      values('soot_data_update','watheeq',
        'طلب تحديث بيانات (تطبيق الموظف): ' || coalesce(v_emp_nm, v_req.iqama),
        v_body,
        jsonb_build_object('employee_id', v_req.employee_id, 'iqama', v_req.iqama, 'field', v_req.field,
          'label', v_req.field_label, 'old', v_req.old_value, 'new', v_req.new_value, 'request_id', v_req.id),
        v_final, (select project_code from public.employees where id = v_req.employee_id),
        auth.uid(), coalesce(v_emp_nm, v_req.iqama), now(), auth.uid(),
        case when p_approve then 'اعتُمد عبر تطبيق المشرف — ' else 'رُفض عبر تطبيق المشرف — ' end || coalesce(v_nm,''));
    end if;
  exception when others then null; end;

  return json_build_object('ok', true, 'status', case when p_approve then 'approved' else 'rejected' end);
end $$;
grant execute on function public.sup_review_update(uuid,boolean,text) to authenticated;

commit;
