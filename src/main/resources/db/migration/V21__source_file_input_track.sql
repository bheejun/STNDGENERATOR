alter table source_file
  add column if not exists input_track text not null default 'UNKNOWN',
  add column if not exists workbook_type text;

create index if not exists idx_source_file_project_track
  on source_file(project_id, input_track);
