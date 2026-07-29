alter table standard_system
    add column if not exists criteria_prefix varchar(300)
    not null default '[{year}표준시스템DB {systemName}]';
