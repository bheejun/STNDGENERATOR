create table standard_system (
    id bigint generated always as identity primary key,
    system_code text not null unique,
    system_name text not null,
    dbms_type text not null,
    dbms_physical_name text not null,
    default_schema_original text not null,
    default_schema_normalized text not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create table build_project (
    id bigint generated always as identity primary key,
    system_id bigint not null references standard_system(id),
    target_year integer not null check (target_year between 2000 and 9999),
    deployment_year_month varchar(6) not null check (deployment_year_month ~ '^[0-9]{6}$'),
    status text not null check (status in ('DRAFT','IMPORTED','NEEDS_REVIEW','VALIDATED','APPROVED','GENERATED')),
    active boolean not null default true,
    approved_by text,
    approved_at timestamptz,
    notes text,
    version bigint not null default 0,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);
create index idx_build_project_system_id on build_project(system_id);
create unique index uq_build_project_active_system_year on build_project(system_id, target_year) where active;

create table source_file (
    id bigint generated always as identity primary key,
    project_id bigint not null references build_project(id),
    artifact_kind text not null,
    original_name text not null,
    stored_path text not null,
    sha256 char(64) not null,
    byte_size bigint not null check (byte_size >= 0),
    parse_status text not null default 'UNDETECTED',
    excluded_pt01_count integer not null default 0 check (excluded_pt01_count >= 0),
    excluded_pt02_count integer not null default 0 check (excluded_pt02_count >= 0),
    created_at timestamptz not null default now(),
    unique(project_id, sha256)
);
create index idx_source_file_project_id on source_file(project_id);

create table id_registry (
    id bigint generated always as identity primary key,
    id_type text not null,
    logical_key text not null,
    system_id bigint not null references standard_system(id),
    wdq_id varchar(15) not null unique check (char_length(wdq_id) <= 15),
    first_project_id bigint references build_project(id),
    sequence_value bigint not null check (sequence_value > 0),
    created_at timestamptz not null default now(),
    unique(id_type, system_id, logical_key),
    unique(id_type, sequence_value)
);
create index idx_id_registry_system_id on id_registry(system_id);
create index idx_id_registry_first_project_id on id_registry(first_project_id);

create table exclusion_rule (
    id bigint generated always as identity primary key,
    project_id bigint not null references build_project(id),
    wdq_id varchar(15), dbms_original text not null, dbms_normalized text not null,
    schema_original text not null, schema_normalized text not null,
    table_original text not null, table_normalized text not null,
    column_original text, column_normalized text, exclusion_type text not null,
    reason text, created_at timestamptz not null default now()
);
create index idx_exclusion_rule_project_id on exclusion_rule(project_id);

create table verification_rule (
    id bigint generated always as identity primary key,
    project_id bigint not null references build_project(id),
    wdq_id varchar(15), rule_name text not null, expression text not null,
    created_at timestamptz not null default now()
);
create index idx_verification_rule_project_id on verification_rule(project_id);

create table code_rule (
    id bigint generated always as identity primary key,
    project_id bigint not null references build_project(id),
    wdq_id varchar(15), rule_name text not null, lookup_sql text not null,
    created_at timestamptz not null default now()
);
create index idx_code_rule_project_id on code_rule(project_id);

create table column_rule_mapping (
    id bigint generated always as identity primary key,
    project_id bigint not null references build_project(id),
    wdq_id varchar(15), dbms_original text not null, dbms_normalized text not null,
    schema_original text not null, schema_normalized text not null,
    table_original text not null, table_normalized text not null,
    column_original text not null, column_normalized text not null,
    rule_type text not null check (rule_type in ('VERIFICATION','CODE')),
    verification_rule_id bigint references verification_rule(id),
    code_rule_id bigint references code_rule(id),
    check ((rule_type='VERIFICATION' and verification_rule_id is not null and code_rule_id is null)
        or (rule_type='CODE' and code_rule_id is not null and verification_rule_id is null))
);
create index idx_column_rule_mapping_project_id on column_rule_mapping(project_id);
create index idx_column_rule_mapping_verification_rule_id on column_rule_mapping(verification_rule_id);
create index idx_column_rule_mapping_code_rule_id on column_rule_mapping(code_rule_id);

create table business_rule (
    id bigint generated always as identity primary key,
    project_id bigint not null references build_project(id),
    wdq_id varchar(15), rule_kind text not null default 'BUSINESS' check (rule_kind='BUSINESS'),
    rule_name text not null, table_original text not null, table_normalized text not null,
    rule_sql text not null, created_at timestamptz not null default now()
);
create index idx_business_rule_project_id on business_rule(project_id);

create table data_conflict (
    id bigint generated always as identity primary key,
    project_id bigint not null references build_project(id),
    data_type text not null, logical_key text not null, left_value text, right_value text,
    resolution text, resolved_value text, resolved_at timestamptz,
    created_at timestamptz not null default now()
);
create index idx_data_conflict_project_id on data_conflict(project_id);

create table generated_artifact (
    id bigint generated always as identity primary key,
    project_id bigint not null references build_project(id),
    artifact_kind text not null, artifact_version bigint not null check (artifact_version > 0),
    policy_name text not null, policy_version text not null,
    stored_path text not null, sha256 char(64) not null,
    created_at timestamptz not null default now(),
    unique(project_id, artifact_kind, artifact_version)
);
create index idx_generated_artifact_project_id on generated_artifact(project_id);
