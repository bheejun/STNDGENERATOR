create table report_review_run (
    id bigint generated always as identity primary key,
    project_id bigint not null references build_project(id),
    source_file_id bigint not null references source_file(id),
    engine_version varchar(80) not null,
    file_sha256 char(64) not null,
    verdict varchar(20) not null check (verdict in ('PASS','CONDITIONAL','FAIL')),
    criteria_adoptable boolean not null,
    metadata_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now()
);
create index idx_report_review_run_project on report_review_run(project_id, created_at desc);
create index idx_report_review_run_source on report_review_run(source_file_id, created_at desc);

create table report_review_issue (
    id bigint generated always as identity primary key,
    review_run_id bigint not null references report_review_run(id) on delete cascade,
    issue_code varchar(80) not null,
    severity varchar(20) not null check (severity in ('INFO','WARNING','ERROR')),
    message text not null,
    sheet_name text,
    row_number integer,
    evidence text,
    blocks_adoption boolean not null
);
create index idx_report_review_issue_run on report_review_issue(review_run_id);

create table report_review_metric (
    review_run_id bigint not null references report_review_run(id) on delete cascade,
    metric_name varchar(100) not null,
    metric_value bigint not null,
    primary key(review_run_id, metric_name)
);

