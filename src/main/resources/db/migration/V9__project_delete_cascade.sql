alter table source_file drop constraint source_file_project_id_fkey;
alter table source_file add constraint source_file_project_id_fkey
    foreign key(project_id) references build_project(id) on delete cascade;

alter table id_registry drop constraint id_registry_first_project_id_fkey;
alter table id_registry add constraint id_registry_first_project_id_fkey
    foreign key(first_project_id) references build_project(id) on delete set null;

alter table exclusion_rule drop constraint exclusion_rule_project_id_fkey;
alter table exclusion_rule add constraint exclusion_rule_project_id_fkey
    foreign key(project_id) references build_project(id) on delete cascade;
alter table verification_rule drop constraint verification_rule_project_id_fkey;
alter table verification_rule add constraint verification_rule_project_id_fkey
    foreign key(project_id) references build_project(id) on delete cascade;
alter table code_rule drop constraint code_rule_project_id_fkey;
alter table code_rule add constraint code_rule_project_id_fkey
    foreign key(project_id) references build_project(id) on delete cascade;
alter table column_rule_mapping drop constraint column_rule_mapping_project_id_fkey;
alter table column_rule_mapping add constraint column_rule_mapping_project_id_fkey
    foreign key(project_id) references build_project(id) on delete cascade;
alter table business_rule drop constraint business_rule_project_id_fkey;
alter table business_rule add constraint business_rule_project_id_fkey
    foreign key(project_id) references build_project(id) on delete cascade;
alter table data_conflict drop constraint data_conflict_project_id_fkey;
alter table data_conflict add constraint data_conflict_project_id_fkey
    foreign key(project_id) references build_project(id) on delete cascade;
alter table generated_artifact drop constraint generated_artifact_project_id_fkey;
alter table generated_artifact add constraint generated_artifact_project_id_fkey
    foreign key(project_id) references build_project(id) on delete cascade;
alter table normalized_item drop constraint normalized_item_project_id_fkey;
alter table normalized_item add constraint normalized_item_project_id_fkey
    foreign key(project_id) references build_project(id) on delete cascade;
alter table project_change_history drop constraint project_change_history_project_id_fkey;
alter table project_change_history add constraint project_change_history_project_id_fkey
    foreign key(project_id) references build_project(id) on delete cascade;
alter table artifact_generation_history drop constraint artifact_generation_history_project_id_fkey;
alter table artifact_generation_history add constraint artifact_generation_history_project_id_fkey
    foreign key(project_id) references build_project(id) on delete cascade;
alter table report_review_run drop constraint report_review_run_project_id_fkey;
alter table report_review_run add constraint report_review_run_project_id_fkey
    foreign key(project_id) references build_project(id) on delete cascade;

