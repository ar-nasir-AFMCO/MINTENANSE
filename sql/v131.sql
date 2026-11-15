-- ════════════════════════════════════════════════════════════════════
-- v131 — صوت: طلب تحديث «رقم الآيدي» من تطبيق الموظف
--   • الموظف يطلب تغيير رقم الآيدي على منصة مشروعه (NINJA / KEEMART / …)
--   • يعتمده: مشرف نفس المشروع أو المدير/مدير النظام (بقية الحقول تبقى للتوظيف والإدارة)
--   • عند الاعتماد: يُسجَّل في وثيق (employee_ids) — الرقم القديم يُقفل بتاريخ اليوم ولا يُحذف،
--     ويُحدَّث ربط نينجا/كيمارت، ويُعاد احتساب دوام الرقم الجديد، ويظهر في سجل الموظف
--   • يُرفض الطلب لو الرقم مسجّل لموظف آخر على نفس المنصة
--   • لا حذف — إضافة وتعديل في المكان
-- ════════════════════════════════════════════════════════════════════

begin;

alter table public.data_update_requests add column if not exists platform text;

-- من يعتمد هذا الطلب؟ رقم الآيدي: مشرف المشروع أو الإدارة · بقية الحقول: التوظيف أو الإدارة
create or replace function public._soot_can_approve_request(p_field text, p_employee uuid)
  returns boolean language sql stable security definer set search_path = public as $$
  select case when p_field = 'platform_id' then
      coalesce(public.my_role()::text in ('admin','manager'), false)
      or (coalesce(public.my_role()::text = 'supervisor', false)
          and public.can_access_project((select project_code from public.employees where id = p_employee)))
    else public.soot_can_approve_updates() end
$$;
grant execute on function public._soot_can_approve_request(text, uuid) to authenticated;

-- (1) طلب الموظف: + رقم الآيدي
create or replace function public.emp_request_update(p_token text, p_field text, p_new_value text)
  returns json language plpgsql security definer set search_path = public as $$
declare v_emp public.employees%rowtype; v_old text; v_label text; v_id uuid; v_inbox uuid; v_new text := btrim(coalesce(p_new_value,''));
        v_plat text;
begin
  select * into v_emp from public.employees where id = public._emp_from_token(p_token);
  if v_emp.id is null then raise exception 'انتهت الجلسة'; end if;
  if p_field not in ('phone','project_code','bank_account','platform_id') then raise exception 'حقل غير مدعوم'; end if;
  if v_new = '' then raise exception 'اكتب القيمة الجديدة'; end if;

  if p_field = 'platform_id' then
    v_plat := upper(btrim(coalesce(v_emp.project_code, '')));
    if v_plat = '' then raise exception 'مشروعك غير محدّد في وثيق — راجع المشرف'; end if;
    v_new := public.norm_id(v_new);
    if v_new !~ '^[0-9A-Za-z-]{1,20}$' then raise exception 'رقم الآيدي غير صحيح — أرقام وحروف إنجليزية فقط'; end if;
    select string_agg(external_id, ' / ' order by external_id) into v_old
      from public.employee_ids where employee_id = v_emp.id and platform = v_plat and end_date is null;
    if exists (select 1 from public.employee_ids where employee_id = v_emp.id and platform = v_plat and external_id = v_new and end_date is null) then
      raise exception 'هذا هو رقمك الحالي'; end if;
    if exists (select 1 from public.employee_ids where platform = v_plat and external_id = v_new and employee_id <> v_emp.id) then
      raise exception 'هذا الرقم مسجّل لموظف آخر — راجع مشرفك'; end if;
    if exists (select 1 from public.data_update_requests where employee_id = v_emp.id and field = 'platform_id' and status = 'pending') then
      raise exception 'لديك طلب رقم آيدي معلّق بالفعل'; end if;
    v_label := 'رقم الآيدي (' || v_plat || ')';
  else
    v_old := case p_field
               when 'phone' then v_emp.phone
               when 'project_code' then v_emp.project_code
               when 'bank_account' then v_emp.data->>'bank_account' end;
    v_label := case p_field when 'phone' then 'رقم الاتصال' when 'project_code' then 'المشروع' when 'bank_account' then 'الحساب البنكي' end;
  end if;

  insert into public.data_update_requests(employee_id, iqama, field, field_label, old_value, new_value, platform)
    values(v_emp.id, v_emp.iqama, p_field, v_label, v_old, v_new, v_plat)
    returning id into v_id;

  begin
    insert into public.inbox_items(kind, system, title, body, ref, status, project, created_by, created_by_name)
    values('soot_data_update', 'watheeq',
      'طلب تحديث بيانات (تطبيق الموظف): ' || coalesce(v_emp.full_name_ar, v_emp.full_name_en, v_emp.iqama),
      v_label || ': ' || coalesce(v_old,'—') || '  ←  ' || v_new
        || case when p_field = 'platform_id' then E'\nيعتمده مشرف المشروع أو المدير' else '' end,
      jsonb_build_object('employee_id', v_emp.id, 'iqama', v_emp.iqama, 'field', p_field,
        'label', v_label, 'old', v_old, 'new', v_new, 'request_id', v_id, 'platform', v_plat),
      'new', v_emp.project_code, null, coalesce(v_emp.full_name_ar, v_emp.full_name_en, v_emp.iqama))
    returning id into v_inbox;
    update public.data_update_requests set inbox_id = v_inbox where id = v_id;
  exception when others then raise warning 'soot update inbox: %', sqlerrm; end;

  return json_build_object('id', v_id, 'status','pending');
end $$;
grant execute on function public.emp_request_update(text,text,text) to anon, authenticated;

-- (2) الاعتماد: الصلاحية حسب الحقل + تطبيق رقم الآيدي في وثيق
create or replace function public.sup_review_update(p_request uuid, p_approve boolean, p_note text default null)
  returns json language plpgsql security definer set search_path = public as $$
declare v_req public.data_update_requests%rowtype; v_nm text; v_final text; v_emp_nm text; v_body text; v_inbox uuid;
        v_plat text; v_ext text; v_proj text; v_today date := (now() at time zone 'Asia/Riyadh')::date; v_holder text;
begin
  select * into v_req from public.data_update_requests where id = p_request and status='pending';
  if v_req.id is null then raise exception 'الطلب غير موجود أو تمّت معالجته'; end if;
  if not public._soot_can_approve_request(v_req.field, v_req.employee_id) then
    if v_req.field = 'platform_id' then
      raise exception 'اعتماد رقم الآيدي لمشرف المشروع أو المدير فقط';
    else
      raise exception 'اعتماد تحديث البيانات للتوظيف والإدارة فقط — يمكنك التوصية';
    end if;
  end if;
  select coalesce(full_name, email) into v_nm from public.profiles where id = auth.uid();

  if p_approve then
    if v_req.field = 'phone' then
      update public.employees set phone = v_req.new_value, updated_at = now() where id = v_req.employee_id;
    elsif v_req.field = 'project_code' then
      update public.employees set project_code = v_req.new_value, updated_at = now() where id = v_req.employee_id;
    elsif v_req.field = 'bank_account' then
      update public.employees set data = jsonb_set(coalesce(data,'{}'::jsonb), '{bank_account}', to_jsonb(v_req.new_value)), updated_at = now() where id = v_req.employee_id;
    elsif v_req.field = 'platform_id' then
      select project_code into v_proj from public.employees where id = v_req.employee_id;
      v_plat := coalesce(v_req.platform, upper(btrim(v_proj)));
      v_ext := public.norm_id(v_req.new_value);
      select coalesce(e.full_name_ar, e.full_name_en) || ' · إقامة ' || e.iqama into v_holder
        from public.employee_ids x join public.employees e on e.id = x.employee_id
       where x.platform = v_plat and x.external_id = v_ext and x.employee_id <> v_req.employee_id limit 1;
      if v_holder is not null then
        raise exception 'الرقم % على % مسجّل لـ % — لا يمكن الاعتماد', v_ext, v_plat, v_holder; end if;
      -- الرقم القديم يُقفل بتاريخ اليوم (لا يُحذف)
      update public.employee_ids
         set end_date = v_today, note = coalesce(note || ' | ', '') || 'أُقفل ' || v_today || ': استُبدل بـ ' || v_ext || ' (طلب صوت)'
       where employee_id = v_req.employee_id and platform = v_plat and end_date is null and external_id <> v_ext;
      insert into public.employee_ids as x (employee_id, platform, external_id, project_code, start_date, note)
      values (v_req.employee_id, v_plat, v_ext, v_proj, v_today, 'طلب تحديث من تطبيق صوت — اعتمده ' || coalesce(v_nm, ''))
      on conflict (platform, external_id) do update set end_date = null,
        note = coalesce(x.note || ' | ', '') || 'أُعيد فتحه ' || v_today || ' (طلب صوت)';
      -- ربط المنصة + إعادة احتساب الدوام للرقم الجديد
      if v_plat = 'NINJA' then
        update public.ninja_links set to_date = v_today where iqama = v_req.iqama and to_date is null and captain_id <> v_ext;
        if not exists (select 1 from public.ninja_links where iqama = v_req.iqama and captain_id = v_ext and to_date is null) then
          insert into public.ninja_links(captain_id, iqama, from_date, linked_by, linked_by_name)
          values (v_ext, v_req.iqama, v_today, auth.uid(), v_nm);
        end if;
        update public.ninja_captain_days set pulled_at = pulled_at where public.norm_id(captain_id) = v_ext;
      elsif v_plat = 'KEEMART' then
        update public.keemart_links set to_date = v_today where iqama = v_req.iqama and to_date is null and captain_id <> v_ext;
        if not exists (select 1 from public.keemart_links where iqama = v_req.iqama and captain_id = v_ext and to_date is null) then
          insert into public.keemart_links(captain_id, iqama, from_date, linked_by, note)
          values (v_ext, v_req.iqama, v_today, auth.uid(), 'طلب تحديث من تطبيق صوت');
        end if;
        update public.keemart_captain_days set pulled_at = pulled_at where public.norm_id(captain_id) = v_ext;
      end if;
      -- سجل الموظف في وثيق
      insert into public.audit_log(table_name, row_id, action, old_data, new_data, changed_by, changed_at)
      values ('employees', v_req.employee_id::text, 'soot_platform_id', null,
        jsonb_build_object('changes', jsonb_build_object(
          'رقم الآيدي (' || v_plat || ')', coalesce(v_req.old_value, '—') || ' ← ' || v_ext,
          'طريقة التحديث', 'طلب من تطبيق صوت — اعتمده ' || coalesce(v_nm, ''))),
        auth.uid(), now());
    end if;
  end if;

  update public.data_update_requests
     set status = case when p_approve then 'approved' else 'rejected' end,
         note = p_note, reviewed_by = auth.uid(), reviewed_at = now()
   where id = p_request;

  v_final := case when p_approve then 'done' else 'rejected' end;
  begin
    if v_req.inbox_id is not null then
      update public.inbox_items
         set status = v_final, closed_at = now(), closed_by = auth.uid(),
             close_note = case when p_approve then 'اعتُمد — ' else 'رُفض — ' end || coalesce(v_nm,'')
       where id = v_req.inbox_id;
    else
      select coalesce(full_name_ar, full_name_en, iqama) into v_emp_nm from public.employees where id = v_req.employee_id;
      v_body := v_req.field_label || ': ' || coalesce(v_req.old_value,'—') || '  ←  ' || v_req.new_value;
      insert into public.inbox_items(kind, system, title, body, ref, status, project, created_by, created_by_name, closed_at, closed_by, close_note)
      values('soot_data_update','watheeq',
        'طلب تحديث بيانات (تطبيق الموظف): ' || coalesce(v_emp_nm, v_req.iqama), v_body,
        jsonb_build_object('employee_id', v_req.employee_id, 'iqama', v_req.iqama, 'field', v_req.field,
          'label', v_req.field_label, 'old', v_req.old_value, 'new', v_req.new_value, 'request_id', v_req.id),
        v_final, (select project_code from public.employees where id = v_req.employee_id),
        auth.uid(), coalesce(v_emp_nm, v_req.iqama), now(), auth.uid(),
        case when p_approve then 'اعتُمد — ' else 'رُفض — ' end || coalesce(v_nm,''))
      returning id into v_inbox;
      update public.data_update_requests set inbox_id = v_inbox where id = v_req.id;
    end if;
  exception when others then raise warning 'soot update inbox: %', sqlerrm; end;

  return json_build_object('ok', true, 'status', case when p_approve then 'approved' else 'rejected' end);
end $$;
grant execute on function public.sup_review_update(uuid,boolean,text) to authenticated;

-- (3) القائمة المعلّقة: صلاحية الاعتماد لكل طلب
create or replace function public.soot_pending_updates()
  returns json language plpgsql stable security definer set search_path = public as $$
declare v_all boolean := public.soot_can_approve_updates();
begin
  if public.my_role() is null then raise exception 'غير مصرّح'; end if;
  return coalesce((select json_agg(json_build_object(
      'id', r.id, 'iqama', r.iqama, 'field', r.field, 'field_label', r.field_label, 'old_value', r.old_value, 'new_value', r.new_value,
      'created_at', r.created_at, 'name', coalesce(e.full_name_ar, e.full_name_en, r.iqama), 'project', e.project_code,
      'rec_status', r.rec_status, 'rec_note', r.rec_note, 'rec_by_name', r.rec_by_name, 'rec_at', r.rec_at,
      'can_approve', public._soot_can_approve_request(r.field, r.employee_id))
      order by r.created_at desc)
    from public.data_update_requests r left join public.employees e on e.id = r.employee_id
    where r.status = 'pending' and (v_all or public.can_access_project(e.project_code))), '[]'::json);
end $$;
grant execute on function public.soot_pending_updates() to authenticated;

commit;
