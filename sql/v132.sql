-- ════════════════════════════════════════════════════════════════════
-- v132 — صوت: رقم الآيدي بمرحلتين (المشرف يؤكد ← المدير يعتمد ويُطبَّق)
--   • الموظف لا يُقال له إن الرقم مسجّل لموظف آخر — الطلب يُقبل ويُراجَع
--   • المرحلة ١: مشرف المشروع يؤكد التغيير (أو يرفضه فيُقفل الطلب)
--   • المرحلة ٢: المدير/مدير النظام يعتمد بعد تأكيد المشرف ← يُطبَّق في وثيق
--       - الرقم جديد: يُسجَّل للموظف، ويُقفل رقمه السابق بتاريخ اليوم
--       - الرقم عند موظف آخر: يُنقل له عبر employee_id_transfer (نفس نقل وثيق — يُحفظ في السجل)
--         ودوام الأيام السابقة يبقى لصاحبه القديم؛ الاحتساب للجديد من اليوم
--   • المشرف والمدير يشوفون صاحب الرقم الحالي قبل القرار
--   • لا حذف — إضافة وتعديل في المكان
-- ════════════════════════════════════════════════════════════════════

begin;

-- صاحب الرقم الحالي (إن وُجد) — يظهر للمشرف والمدير فقط
create or replace function public._soot_id_holder(p_platform text, p_ext text, p_employee uuid)
  returns text language sql stable security definer set search_path = public as $$
  select coalesce(e.full_name_ar, e.full_name_en) || ' · إقامة ' || e.iqama || coalesce(' · ' || e.project_code, '')
         || case when x.end_date is not null then ' (رقمه مقفول)' else '' end
    from public.employee_ids x join public.employees e on e.id = x.employee_id
   where x.platform = upper(p_platform) and x.external_id = public.norm_id(p_ext) and x.employee_id <> p_employee
   limit 1
$$;

-- الاعتماد النهائي لرقم الآيدي: المدير/مدير النظام · بقية الحقول: التوظيف أو الإدارة
create or replace function public._soot_can_approve_request(p_field text, p_employee uuid)
  returns boolean language sql stable security definer set search_path = public as $$
  select case when p_field = 'platform_id' then coalesce(public.my_role()::text in ('admin','manager'), false)
              else public.soot_can_approve_updates() end
$$;

-- تأكيد المشرف (المرحلة ١) لرقم الآيدي: مشرف المشروع أو الإدارة
create or replace function public._soot_can_confirm_request(p_field text, p_employee uuid)
  returns boolean language sql stable security definer set search_path = public as $$
  select p_field = 'platform_id' and (
      coalesce(public.my_role()::text in ('admin','manager'), false)
      or (coalesce(public.my_role()::text = 'supervisor', false)
          and public.can_access_project((select project_code from public.employees where id = p_employee))))
$$;
grant execute on function public._soot_can_confirm_request(text, uuid) to authenticated;

-- (1) طلب الموظف: بدون كشف إن الرقم عند موظف آخر
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
        || case when p_field = 'platform_id' then E'\nيؤكده مشرف المشروع ثم يعتمده المدير' else '' end,
      jsonb_build_object('employee_id', v_emp.id, 'iqama', v_emp.iqama, 'field', p_field,
        'label', v_label, 'old', v_old, 'new', v_new, 'request_id', v_id, 'platform', v_plat),
      'new', v_emp.project_code, null, coalesce(v_emp.full_name_ar, v_emp.full_name_en, v_emp.iqama))
    returning id into v_inbox;
    update public.data_update_requests set inbox_id = v_inbox where id = v_id;
  exception when others then raise warning 'soot update inbox: %', sqlerrm; end;

  return json_build_object('id', v_id, 'status','pending');
end $$;
grant execute on function public.emp_request_update(text,text,text) to anon, authenticated;

-- (2) المشرف: توصية لبقية الحقول · تأكيد/رفض لرقم الآيدي (الرفض يُقفل الطلب)
create or replace function public.sup_recommend_update(p_request uuid, p_ok boolean, p_note text default null)
  returns json language plpgsql security definer set search_path = public as $$
declare r public.data_update_requests%rowtype; v_proj text; v_nm text; v_line text;
begin
  if public.my_role() is null then raise exception 'غير مصرّح'; end if;
  select * into r from public.data_update_requests where id = p_request and status = 'pending';
  if r.id is null then raise exception 'الطلب غير موجود أو تمّت معالجته'; end if;
  select project_code into v_proj from public.employees where id = r.employee_id;
  if not public.can_access_project(v_proj) then raise exception 'هذا الموظف خارج مشروعك'; end if;
  select coalesce(full_name, email) into v_nm from public.profiles where id = auth.uid();

  if r.field = 'platform_id' then
    if not public._soot_can_confirm_request(r.field, r.employee_id) then raise exception 'تأكيد رقم الآيدي لمشرف المشروع'; end if;
    v_line := case when p_ok then 'أكّد المشرف التغيير — بانتظار اعتماد المدير' else 'رفض المشرف التغيير' end;
    update public.data_update_requests set rec_status = case when p_ok then 'ok' else 'no' end,
           rec_note = nullif(btrim(p_note), ''), rec_by = auth.uid(), rec_by_name = v_nm, rec_at = now(),
           status = case when p_ok then status else 'rejected' end,
           reviewed_by = case when p_ok then reviewed_by else auth.uid() end,
           reviewed_at = case when p_ok then reviewed_at else now() end,
           note = case when p_ok then note else coalesce(nullif(btrim(p_note), ''), 'رفضه المشرف') end
     where id = r.id;
    if r.inbox_id is not null then
      update public.inbox_items set
             status = case when p_ok then 'in_progress' else 'rejected' end,
             body = body || E'\n' || v_line || ' — ' || coalesce(v_nm, '') || coalesce(' · ' || nullif(btrim(p_note), ''), ''),
             closed_at = case when p_ok then closed_at else now() end,
             closed_by = case when p_ok then closed_by else auth.uid() end
       where id = r.inbox_id;
    end if;
    return json_build_object('ok', true, 'stage', case when p_ok then 'manager' else 'rejected' end);
  end if;

  update public.data_update_requests set rec_status = case when p_ok then 'ok' else 'no' end,
         rec_note = nullif(btrim(p_note), ''), rec_by = auth.uid(), rec_by_name = v_nm, rec_at = now()
   where id = r.id;
  if r.inbox_id is not null then
    update public.inbox_items set status = 'in_progress',
           body = body || E'\n' || 'توصية المشرف: ' || case when p_ok then 'موافق' else 'غير موافق' end
                  || ' — ' || coalesce(v_nm, '') || coalesce(' · ' || nullif(btrim(p_note), ''), '')
     where id = r.inbox_id and status = 'new';
  end if;
  return json_build_object('ok', true);
end $$;
grant execute on function public.sup_recommend_update(uuid, boolean, text) to authenticated;

-- (3) الاعتماد النهائي والتطبيق في وثيق
create or replace function public.sup_review_update(p_request uuid, p_approve boolean, p_note text default null)
  returns json language plpgsql security definer set search_path = public as $$
declare v_req public.data_update_requests%rowtype; v_nm text; v_final text; v_emp_nm text; v_body text; v_inbox uuid;
        v_plat text; v_ext text; v_proj text; v_today date := (now() at time zone 'Asia/Riyadh')::date;
        v_holder_emp uuid; v_holder_iqama text; v_holder_nm text; v_moved boolean := false;
begin
  select * into v_req from public.data_update_requests where id = p_request and status='pending';
  if v_req.id is null then raise exception 'الطلب غير موجود أو تمّت معالجته'; end if;
  if not public._soot_can_approve_request(v_req.field, v_req.employee_id) then
    if v_req.field = 'platform_id' then raise exception 'اعتماد رقم الآيدي للمدير — دور المشرف تأكيد التغيير';
    else raise exception 'اعتماد تحديث البيانات للتوظيف والإدارة فقط — يمكنك التوصية'; end if;
  end if;
  if p_approve and v_req.field = 'platform_id' and coalesce(v_req.rec_status, '') <> 'ok' then
    raise exception 'بانتظار تأكيد مشرف المشروع أولاً'; end if;
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
      select x.employee_id, e.iqama, coalesce(e.full_name_ar, e.full_name_en) into v_holder_emp, v_holder_iqama, v_holder_nm
        from public.employee_ids x join public.employees e on e.id = x.employee_id
       where x.platform = v_plat and x.external_id = v_ext and x.employee_id <> v_req.employee_id limit 1;

      -- رقم الموظف السابق يُقفل بتاريخ اليوم (لا يُحذف)
      update public.employee_ids
         set end_date = v_today, note = coalesce(note || ' | ', '') || 'أُقفل ' || v_today || ': استُبدل بـ ' || v_ext || ' (طلب صوت)'
       where employee_id = v_req.employee_id and platform = v_plat and end_date is null and external_id <> v_ext;

      if v_holder_emp is not null then
        -- الرقم عند موظف آخر → نقل وثيق الرسمي (يُسجَّل في سجل المعرّفات)
        perform public.employee_id_transfer(jsonb_build_object('platform', v_plat, 'external_id', v_ext,
          'to_employee', v_req.employee_id, 'start_date', v_today, 'end_date', v_today - 1,
          'reason', 'طلب تحديث من تطبيق صوت — أكّده ' || coalesce(v_req.rec_by_name, 'المشرف') || ' واعتمده ' || coalesce(v_nm, '')));
        v_moved := true;
        if v_plat = 'NINJA' then
          update public.ninja_links set to_date = v_today where iqama = v_holder_iqama and captain_id = v_ext and to_date is null;
        elsif v_plat = 'KEEMART' then
          update public.keemart_links set to_date = v_today where iqama = v_holder_iqama and captain_id = v_ext and to_date is null;
        end if;
        insert into public.audit_log(table_name, row_id, action, old_data, new_data, changed_by, changed_at)
        values ('employees', v_holder_emp::text, 'soot_platform_id_out', null,
          jsonb_build_object('changes', jsonb_build_object('رقم الآيدي (' || v_plat || ')',
            v_ext || ' — نُقل إلى ' || coalesce((select coalesce(full_name_ar, full_name_en) from public.employees where id = v_req.employee_id), v_req.iqama)
            || ' بطلب من تطبيق صوت (اعتمده ' || coalesce(v_nm, '') || ')')),
          auth.uid(), now());
      else
        insert into public.employee_ids as x (employee_id, platform, external_id, project_code, start_date, note)
        values (v_req.employee_id, v_plat, v_ext, v_proj, v_today, 'طلب تحديث من تطبيق صوت — أكّده ' || coalesce(v_req.rec_by_name, 'المشرف') || ' واعتمده ' || coalesce(v_nm, ''))
        on conflict (platform, external_id) do update set end_date = null,
          note = coalesce(x.note || ' | ', '') || 'أُعيد فتحه ' || v_today || ' (طلب صوت)';
      end if;

      -- ربط المنصة + احتساب الدوام: رقم جديد → كل أيامه · رقم منقول → من اليوم (السابق لصاحبه)
      if v_plat = 'NINJA' then
        update public.ninja_links set to_date = v_today where iqama = v_req.iqama and to_date is null and captain_id <> v_ext;
        if not exists (select 1 from public.ninja_links where iqama = v_req.iqama and captain_id = v_ext and to_date is null) then
          insert into public.ninja_links(captain_id, iqama, from_date, linked_by, linked_by_name) values (v_ext, v_req.iqama, v_today, auth.uid(), v_nm);
        end if;
        update public.ninja_captain_days set pulled_at = pulled_at
         where public.norm_id(captain_id) = v_ext and (not v_moved or work_date >= v_today);
      elsif v_plat = 'KEEMART' then
        update public.keemart_links set to_date = v_today where iqama = v_req.iqama and to_date is null and captain_id <> v_ext;
        if not exists (select 1 from public.keemart_links where iqama = v_req.iqama and captain_id = v_ext and to_date is null) then
          insert into public.keemart_links(captain_id, iqama, from_date, linked_by, note) values (v_ext, v_req.iqama, v_today, auth.uid(), 'طلب تحديث من تطبيق صوت');
        end if;
        update public.keemart_captain_days set pulled_at = pulled_at
         where public.norm_id(captain_id) = v_ext and (not v_moved or work_date >= v_today);
      end if;

      insert into public.audit_log(table_name, row_id, action, old_data, new_data, changed_by, changed_at)
      values ('employees', v_req.employee_id::text, 'soot_platform_id', null,
        jsonb_build_object('changes', jsonb_strip_nulls(jsonb_build_object(
          'رقم الآيدي (' || v_plat || ')', coalesce(v_req.old_value, '—') || ' ← ' || v_ext,
          'نُقل من', case when v_moved then v_holder_nm || ' · إقامة ' || v_holder_iqama end,
          'طريقة التحديث', 'طلب من تطبيق صوت — أكّده ' || coalesce(v_req.rec_by_name, 'المشرف') || ' واعتمده ' || coalesce(v_nm, '')))),
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
                          || case when v_moved then ' · نُقل الرقم من ' || v_holder_nm else '' end
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

  return json_build_object('ok', true, 'status', case when p_approve then 'approved' else 'rejected' end, 'moved_from', v_holder_nm);
end $$;
grant execute on function public.sup_review_update(uuid,boolean,text) to authenticated;

-- (4) القائمة المعلّقة: المرحلة + صاحب الرقم الحالي + صلاحيات كل طلب
create or replace function public.soot_pending_updates()
  returns json language plpgsql stable security definer set search_path = public as $$
declare v_all boolean := public.soot_can_approve_updates() or coalesce(public.my_role()::text in ('admin','manager'), false);
begin
  if public.my_role() is null then raise exception 'غير مصرّح'; end if;
  return coalesce((select json_agg(json_build_object(
      'id', r.id, 'iqama', r.iqama, 'field', r.field, 'field_label', r.field_label, 'old_value', r.old_value, 'new_value', r.new_value,
      'created_at', r.created_at, 'name', coalesce(e.full_name_ar, e.full_name_en, r.iqama), 'project', e.project_code,
      'rec_status', r.rec_status, 'rec_note', r.rec_note, 'rec_by_name', r.rec_by_name, 'rec_at', r.rec_at,
      'holder', case when r.field = 'platform_id' then public._soot_id_holder(coalesce(r.platform, e.project_code), r.new_value, r.employee_id) end,
      'stage', case when r.field <> 'platform_id' then null when r.rec_status = 'ok' then 'manager' else 'supervisor' end,
      'can_confirm', r.field = 'platform_id' and r.rec_status is null and public._soot_can_confirm_request(r.field, r.employee_id),
      'can_approve', public._soot_can_approve_request(r.field, r.employee_id)
                     and (r.field <> 'platform_id' or r.rec_status = 'ok'),
      'can_reject', public._soot_can_approve_request(r.field, r.employee_id))
      order by r.created_at desc)
    from public.data_update_requests r left join public.employees e on e.id = r.employee_id
    where r.status = 'pending' and (v_all or public.can_access_project(e.project_code))), '[]'::json);
end $$;
grant execute on function public.soot_pending_updates() to authenticated;

commit;
