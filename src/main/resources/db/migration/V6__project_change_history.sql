create table project_change_history (
    id bigint generated always as identity primary key,
    project_id bigint not null references build_project(id),
    entity_type text not null,
    entity_id text not null,
    action text not null check (action in ('CREATE','UPDATE','DELETE')),
    before_json jsonb,
    after_json jsonb,
    changed_at timestamptz not null default now()
);
create index idx_project_change_history_project_id on project_change_history(project_id, changed_at desc);
