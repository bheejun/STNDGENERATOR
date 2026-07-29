create table sql_baseline (
    id bigserial primary key,
    project_id bigint not null unique references build_project(id) on delete cascade,
    original_name varchar(500) not null,
    stored_path text not null,
    sha256 varchar(64) not null,
    byte_size bigint not null,
    uploaded_at timestamptz not null default now()
);
