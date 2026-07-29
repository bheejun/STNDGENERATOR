alter table id_registry
    drop constraint if exists id_registry_id_type_sequence_value_key;

alter table id_registry
    add constraint id_registry_id_type_system_sequence_key
        unique (id_type, system_id, sequence_value);
