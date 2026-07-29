insert into criteria_override(project_id,data_type,logical_key,operation,values_json)
select n.project_id,n.data_type,n.logical_key,'UPSERT',n.values_json
from normalized_item n
where exists (select 1 from project_change_history h where h.project_id=n.project_id
  and h.entity_id=n.id::text and h.action in ('UPDATE','CREATE'))
on conflict(project_id,data_type,logical_key) do update
set operation='UPSERT',values_json=excluded.values_json,updated_at=now();

with changed_rule as (
 select distinct on (h.project_id,h.entity_id) h.project_id,h.before_json->>'ruleName' old_name,
 h.after_json->>'ruleName' new_name,h.after_json->>'wdqId' rule_id
 from project_change_history h where h.entity_type='VERIFICATION_RULE' and h.action='UPDATE'
 order by h.project_id,h.entity_id,h.changed_at desc,h.id desc
)
update normalized_item mapping set values_json=jsonb_set(
 jsonb_set(mapping.values_json,'{verificationRuleId}',to_jsonb(changed_rule.rule_id),true),
 '{ruleName}',to_jsonb(changed_rule.new_name),true)
from changed_rule where mapping.project_id=changed_rule.project_id and mapping.data_type='COLUMN_MAPPING'
 and coalesce(mapping.values_json->>'verificationRuleId','')=''
 and upper(coalesce(mapping.values_json->>'ruleName',''))=upper(changed_rule.old_name);

insert into criteria_override(project_id,data_type,logical_key,operation,values_json)
select n.project_id,n.data_type,n.logical_key,'UPSERT',n.values_json from normalized_item n
where n.data_type='COLUMN_MAPPING' and coalesce(n.values_json->>'verificationRuleId','')<>''
on conflict(project_id,data_type,logical_key) do nothing;
