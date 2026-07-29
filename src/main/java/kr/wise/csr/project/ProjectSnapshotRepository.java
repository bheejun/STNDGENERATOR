package kr.wise.csr.project;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import kr.wise.csr.importfile.WorkbookType;
import kr.wise.csr.normalization.ConflictResolution;
import kr.wise.csr.normalization.DataConflict;
import kr.wise.csr.normalization.NormalizationSummary;
import kr.wise.csr.normalization.NormalizedRow;
import kr.wise.csr.normalization.SourceReference;

@Repository
public class ProjectSnapshotRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public ProjectSnapshotRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.json = new ObjectMapper().findAndRegisterModules();
    }

    public NormalizationSummary applyOverrides(NormalizationSummary summary) {
        List<OverrideRow> overrides=jdbc.query("""
                select data_type,logical_key,operation,values_json::text
                from criteria_override where project_id=?
                """,(rs,n)->new OverrideRow(rs.getString(1),rs.getString(2),rs.getString(3),
                        rs.getString(4)==null?Map.of():readMap(rs.getString(4))),summary.projectId());
        Map<String,OverrideRow> byKey=new java.util.LinkedHashMap<>();
        overrides.forEach(row->byKey.put(row.dataType()+"|"+row.logicalKey(),row));
        List<NormalizedRow> rows=new java.util.ArrayList<>();
        for(NormalizedRow row:summary.rows()){
            OverrideRow override=byKey.remove(row.dataType()+"|"+row.logicalKey());
            if(override==null){rows.add(row);continue;}
            if("DELETE".equals(override.operation()))continue;
            Map<String,String> values=new java.util.LinkedHashMap<>(override.values());
            if(!values.containsKey("wdqId")&&row.values().containsKey("wdqId"))
                values.put("wdqId",row.values().get("wdqId"));
            rows.add(new NormalizedRow(row.dataType(),row.logicalKey(),Map.copyOf(values),row.sources(),
                    kr.wise.csr.normalization.NormalizationService.fingerprint(values)));
        }
        for(OverrideRow override:byKey.values())
            if("UPSERT".equals(override.operation()))
                rows.add(new NormalizedRow(override.dataType(),override.logicalKey(),override.values(),List.of(),
                        kr.wise.csr.normalization.NormalizationService.fingerprint(override.values())));
        return new NormalizationSummary(summary.projectId(),List.copyOf(rows),summary.conflicts(),
                summary.excludedPt01Count(),summary.excludedPt02Count(),summary.importErrors());
    }

    @Transactional
    public void save(NormalizationSummary summary) {
        jdbc.update("delete from data_conflict where project_id=?",summary.projectId());
        jdbc.update("delete from normalized_item where project_id=?",summary.projectId());
        for(NormalizedRow row:summary.rows()) jdbc.update("""
                insert into normalized_item(project_id,data_type,logical_key,values_json,sources_json,fingerprint)
                values(?,?,?,?::jsonb,?::jsonb,?)
                """,summary.projectId(),row.dataType(),row.logicalKey(),write(row.values()),write(row.sources()),row.fingerprint());
        for(DataConflict c:summary.conflicts()) jdbc.update("""
                insert into data_conflict(project_id,data_type,logical_key,left_value,right_value,left_source,right_source,resolution,resolution_reason,resolved_at)
                values(?,?,?,?,?,?,?,?,?,?)
                """,summary.projectId(),c.dataType(),c.logicalKey(),write(c.leftValues()),write(c.rightValues()),
                name(c.leftSource()),name(c.rightSource()),name(c.resolution()),c.reason(),c.resolved()?OffsetDateTime.now():null);
        int updated = jdbc.update("""
                update build_project set status='NEEDS_REVIEW',excluded_pt01_count=?,excluded_pt02_count=?,
                  import_errors_json=?::jsonb,approved_by=null,approved_at=null,approved_snapshot_hash=null,updated_at=now()
                where id=?
                """,summary.excludedPt01Count(),summary.excludedPt02Count(),write(summary.importErrors()),summary.projectId());
        requireSingleProject(updated, summary.projectId());
    }

    public Optional<ProjectSnapshot> findByProjectId(long projectId) {
        List<Meta> metadata=jdbc.query("""
                select p.id,p.system_id,p.target_year,p.deployment_year_month,p.status,s.default_schema_original,
                  p.excluded_pt01_count,p.excluded_pt02_count,p.import_errors_json::text,p.approved_by,p.approved_at,p.approved_snapshot_hash,
                  s.system_name,s.dbms_physical_name,s.dbms_type,
                  coalesce((select r.wdq_id from id_registry r where r.system_id=p.system_id and r.id_type='DB_CONNECTION' order by r.id limit 1),''),
                  s.connection_logical_name,s.dbms_version_code,s.connection_url,s.driver_name,
                  s.db_account_id,s.db_account_password,s.info_system_code,s.info_system_name,s.organization_name,
                  s.wdq_namespace
                from build_project p join standard_system s on s.id=p.system_id where p.id=?
                """,(rs,n)->new Meta(rs.getLong(1),rs.getLong(2),rs.getInt(3),rs.getString(4),
                        ProjectStatus.valueOf(rs.getString(5)),rs.getString(6),rs.getInt(7),rs.getInt(8),
                        rs.getString(9),rs.getString(10),rs.getObject(11,OffsetDateTime.class),rs.getString(12),
                        rs.getString(13),rs.getString(14),rs.getString(15),rs.getString(16),
                        new DbConnectionTarget(rs.getString(17),rs.getString(18),rs.getString(19),rs.getString(20),
                                rs.getString(21),rs.getString(22),rs.getString(23),rs.getString(24),rs.getString(25)),
                        rs.getString(26)),
                        projectId);
        if(metadata.isEmpty())return Optional.empty(); Meta m=metadata.getFirst();
        List<NormalizedRow> rows=jdbc.query("select data_type,logical_key,values_json::text,sources_json::text,fingerprint from normalized_item where project_id=? order by data_type,logical_key",(rs,n)->new NormalizedRow(rs.getString(1),rs.getString(2),readMap(rs.getString(3)),readSources(rs.getString(4)),rs.getString(5).trim()),projectId);
        rows=rows.stream().map(row -> {
            if (!row.dataType().equals("SYSTEM")) return row;
            Map<String,String> values=new java.util.LinkedHashMap<>(row.values());
            values.put("wdqNamespace",m.wdqNamespace == null ? "" : m.wdqNamespace);
            return new NormalizedRow(row.dataType(),row.logicalKey(),Map.copyOf(values),row.sources(),row.fingerprint());
        }).toList();
        List<DataConflict> conflicts=jdbc.query("select id,data_type,logical_key,left_value,right_value,left_source,right_source,resolution,resolution_reason from data_conflict where project_id=? order by id",(rs,n)->new DataConflict(rs.getLong(1),rs.getString(2),rs.getString(3),readMap(rs.getString(4)),workbookType(rs.getString(6)),readMap(rs.getString(5)),workbookType(rs.getString(7)),resolution(rs.getString(8)),rs.getString(9)),projectId);
        return Optional.of(new ProjectSnapshot(m.id,m.systemId,m.year,m.month,m.schema,m.systemName,
                m.dbmsPhysicalName,m.dbmsType,m.connectionWdqId,m.connectionTarget,m.status,rows,conflicts,m.pt01,m.pt02,
                readList(m.errors),m.approvedBy,m.approvedAt,m.hash));
    }

    @Transactional
    public void markApproved(long projectId, String approver, OffsetDateTime at, String hash) {
        int updated = jdbc.update("update build_project set status='APPROVED',approved_by=?,approved_at=?,approved_snapshot_hash=?,replacement_policy_name='SAFE_PRESERVE_CONNECTION',replacement_policy_version='1',updated_at=now() where id=?",
                approver, at, hash, projectId);
        requireSingleProject(updated, projectId);
    }

    private void requireSingleProject(int updated, long projectId) {
        if (updated != 1) throw new IllegalArgumentException("프로젝트를 찾을 수 없습니다: " + projectId);
    }
    private String write(Object value){try{return json.writeValueAsString(value);}catch(JsonProcessingException e){throw new IllegalStateException(e);}}
    private Map<String,String> readMap(String value){try{return json.readValue(value,new TypeReference<>(){});}catch(JsonProcessingException e){throw new IllegalStateException(e);}}
    private List<String> readList(String value){try{return json.readValue(value,new TypeReference<>(){});}catch(JsonProcessingException e){throw new IllegalStateException(e);}}
    private List<SourceReference> readSources(String value){try{return json.readValue(value,new TypeReference<>(){});}catch(JsonProcessingException e){throw new IllegalStateException(e);}}
    private String name(Enum<?> value){return value==null?null:value.name();}
    private WorkbookType workbookType(String v){return v==null?null:WorkbookType.valueOf(v);}
    private ConflictResolution resolution(String v){return v==null?null:ConflictResolution.valueOf(v);}
    private record Meta(long id,long systemId,int year,String month,ProjectStatus status,String schema,int pt01,int pt02,
            String errors,String approvedBy,OffsetDateTime approvedAt,String hash,String systemName,
            String dbmsPhysicalName,String dbmsType,String connectionWdqId,DbConnectionTarget connectionTarget,
            String wdqNamespace){}
    private record OverrideRow(String dataType,String logicalKey,String operation,Map<String,String> values){}
}
