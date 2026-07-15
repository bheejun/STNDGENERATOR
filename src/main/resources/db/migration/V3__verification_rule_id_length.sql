alter table id_registry alter column wdq_id type varchar(20);
alter table verification_rule alter column wdq_id type varchar(20);
alter table column_rule_mapping alter column wdq_id type varchar(20);
alter table id_registry drop constraint id_registry_wdq_id_check;
alter table id_registry add constraint id_registry_wdq_id_check check (
    (id_type = 'VERIFICATION_RULE' and char_length(wdq_id) <= 20)
    or (id_type <> 'VERIFICATION_RULE' and char_length(wdq_id) <= 15)
);
