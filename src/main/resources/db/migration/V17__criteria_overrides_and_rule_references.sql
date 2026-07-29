create table criteria_override (
    project_id bigint not null references build_project(id) on delete cascade,
    data_type text not null,
    logical_key text not null,
    operation varchar(10) not null check (operation in ('UPSERT','DELETE')),
    values_json jsonb,
    updated_at timestamptz not null default now(),
    primary key (project_id, data_type, logical_key)
);

update normalized_item mapping
set values_json = jsonb_set(mapping.values_json,'{verificationRuleId}',
        to_jsonb(rule.values_json->>'wdqId'),true)
from normalized_item rule
where mapping.project_id=rule.project_id and mapping.data_type='COLUMN_MAPPING'
  and rule.data_type='VERIFICATION_RULE'
  and coalesce(mapping.values_json->>'verificationRuleId','')=''
  and upper(coalesce(mapping.values_json->>'ruleName',''))=upper(coalesce(rule.values_json->>'ruleName',''));
