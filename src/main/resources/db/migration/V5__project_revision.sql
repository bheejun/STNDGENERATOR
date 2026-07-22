alter table build_project add column project_revision integer not null default 1;

with numbered as (
    select id, row_number() over (partition by system_id order by created_at, id) as revision
    from build_project
)
update build_project p set project_revision = n.revision
from numbered n where n.id = p.id;

create unique index uq_build_project_system_revision on build_project(system_id, project_revision);
