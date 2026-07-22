alter table build_project add column excluded_pt01_count integer not null default 0;
alter table build_project add column excluded_pt02_count integer not null default 0;
alter table build_project add column import_errors_json jsonb not null default '[]'::jsonb;
alter table build_project add column approved_snapshot_hash char(64);
alter table build_project add column replacement_policy_name text;
alter table build_project add column replacement_policy_version text;

create table normalized_item (
    id bigint generated always as identity primary key,
    project_id bigint not null references build_project(id),
    data_type text not null check (data_type in ('SYSTEM','EXCLUSION','VERIFICATION_RULE','CODE_RULE','COLUMN_MAPPING','BUSINESS_RULE')),
    logical_key text not null,
    values_json jsonb not null,
    sources_json jsonb not null default '[]'::jsonb,
    fingerprint char(64) not null,
    created_at timestamptz not null default now(),
    unique(project_id, data_type, logical_key)
);
create index idx_normalized_item_project_id on normalized_item(project_id);

alter table data_conflict add column left_source text;
alter table data_conflict add column right_source text;
alter table data_conflict add column resolution_reason text;
