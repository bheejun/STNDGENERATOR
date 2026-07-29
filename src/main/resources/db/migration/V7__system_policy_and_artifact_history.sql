alter table standard_system add column wdq_namespace varchar(4);
alter table standard_system add column active boolean not null default true;

update standard_system
set wdq_namespace = '7'
where lower(system_name) like '%지킴e%' or lower(system_code) like '%jikim%';

create table system_id_policy (
    id bigint generated always as identity primary key,
    system_id bigint not null references standard_system(id),
    id_type text not null,
    id_prefix varchar(20) not null,
    number_width integer not null check (number_width between 1 and 12),
    last_value bigint not null default 0 check (last_value >= 0),
    active boolean not null default true,
    updated_at timestamptz not null default now(),
    unique(system_id, id_type)
);

insert into system_id_policy(system_id,id_type,id_prefix,number_width,last_value)
select s.id,t.id_type,t.id_prefix,t.number_width,
       coalesce((select max(r.sequence_value) from id_registry r where r.system_id=s.id and r.id_type=t.id_type),0)
from standard_system s
cross join (values
 ('DB_CONNECTION','STNDDB_',8),('EXCLUSION','STNDEXP_',7),
 ('VERIFICATION_RULE','STNDRULE_',7),('CODE_RULE','STNDCD_',8),
 ('COLUMN_MAPPING','STND_',10),('BUSINESS_RULE','STNDPRF_',7)
) as t(id_type,id_prefix,number_width);

create table artifact_generation_history (
    id bigint generated always as identity primary key,
    project_id bigint not null references build_project(id),
    artifact_kind text not null,
    file_name text not null,
    byte_size bigint not null check (byte_size >= 0),
    sha256 char(64) not null,
    generated_by text not null default 'WEB',
    created_at timestamptz not null default now()
);
create index idx_artifact_generation_history_project on artifact_generation_history(project_id,created_at desc);

update system_id_policy p set number_width=7
from standard_system s
where p.system_id=s.id and s.wdq_namespace='7' and p.id_type='COLUMN_MAPPING';
