alter table build_project
    add column if not exists applied_schema_original text,
    add column if not exists applied_schema_normalized text;

update build_project p
   set applied_schema_original = s.default_schema_original,
       applied_schema_normalized = s.default_schema_normalized
  from standard_system s
 where s.id = p.system_id
   and p.applied_schema_original is null;
