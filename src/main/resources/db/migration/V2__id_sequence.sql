create table id_sequence (
    id_type text primary key,
    last_value bigint not null default 0 check (last_value >= 0)
);
