update build_project p
   set status='NEEDS_REVIEW',
       approved_by=null,
       approved_at=null,
       approved_snapshot_hash=null,
       updated_at=now()
 where exists (
       select 1
         from normalized_item n
        where n.project_id=p.id
          and n.data_type='EXCLUSION'
          and n.values_json->>'exclusionType'='COL'
          and n.values_json->>'expYn'='N'
 );

delete from normalized_item
 where data_type='EXCLUSION'
   and values_json->>'exclusionType'='COL'
   and values_json->>'expYn'='N';
