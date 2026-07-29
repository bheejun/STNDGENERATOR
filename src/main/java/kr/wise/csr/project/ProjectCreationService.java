package kr.wise.csr.project;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.wise.csr.registry.WdqIdAllocator;
import kr.wise.csr.registry.WdqIdType;
import kr.wise.csr.system.DbmsTypeCodes;

@Service
public class ProjectCreationService {
    private final JdbcTemplate jdbc;
    private final WdqIdAllocator ids;
    private final com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();

    public ProjectCreationService(JdbcTemplate jdbc, WdqIdAllocator ids) {
        this.jdbc = jdbc;
        this.ids = ids;
    }

    @Transactional
    public CreatedProject create(CreateProject command) {
        String requestedCode = command.systemCode().trim();
        Long existingSystemId = findSystemId(requestedCode, command.systemName());
        if (existingSystemId == null) return createForNewSystem(command, requestedCode, 1);
        String canonicalCode = jdbc.queryForObject("select system_code from standard_system where id=?",
                String.class, existingSystemId);
        return createVersion(existingSystemId, canonicalCode, command);
    }

    public Optional<CreatedProject> findActiveUploadTarget(String systemCode, String systemName, int targetYear) {
        Long systemId = findSystemId(systemCode.trim(), systemName);
        if (systemId == null) return Optional.empty();
        return jdbc.query("""
                select p.id,p.system_id,p.status,s.system_code,p.project_revision
                  from build_project p join standard_system s on s.id=p.system_id
                 where p.system_id=? and p.target_year=? and p.active=true
                 order by p.project_revision desc,p.id desc limit 1
                """, rs -> rs.next() ? Optional.of(new CreatedProject(rs.getLong(1), rs.getLong(2),
                        ProjectStatus.valueOf(rs.getString(3)), rs.getString(4), rs.getInt(5))) : Optional.empty(),
                systemId, targetYear);
    }

    @Transactional
    public CreatedProject createUnderSystem(long systemId,int targetYear,String deploymentYearMonth) {
        SystemIdentity system=jdbc.query("select id,system_code,system_name from standard_system where id=?",
                rs->rs.next()?new SystemIdentity(rs.getLong(1),rs.getString(2),rs.getString(3)):null,systemId);
        if(system==null) throw new IllegalArgumentException("공통표준 시스템을 찾을 수 없습니다: "+systemId);
        Integer revision=jdbc.queryForObject("select coalesce(max(project_revision),0)+1 from build_project where system_id=?",Integer.class,systemId);
        jdbc.update("update build_project set active=false,updated_at=now() where system_id=? and target_year=? and active",systemId,targetYear);
        long projectId=jdbc.queryForObject("""
                insert into build_project(system_id,target_year,deployment_year_month,status,project_revision)
                values(?,?,?,'DRAFT',?) returning id
                """,Long.class,systemId,targetYear,deploymentYearMonth,revision==null?1:revision);
        ids.allocate(WdqIdType.DB_CONNECTION, systemId, Long.toString(systemId), projectId);
        return new CreatedProject(projectId,systemId,ProjectStatus.DRAFT,system.systemCode(),revision==null?1:revision);
    }

    private CreatedProject createForNewSystem(CreateProject command, String systemCode, int revision) {
        long systemId = jdbc.queryForObject("""
                insert into standard_system(system_code,system_name,dbms_type,dbms_physical_name,
                  default_schema_original,default_schema_normalized)
                values(?,?,?,?,?,?) returning id
                """, Long.class, systemCode, command.systemName().trim(),
                DbmsTypeCodes.normalize(command.dbmsType()), command.dbmsPhysicalName().trim(),
                command.defaultSchema().trim(), command.defaultSchema().trim().toUpperCase());
        jdbc.update("""
                insert into system_id_policy(system_id,id_type,id_prefix,number_width)
                values (?, 'DB_CONNECTION', 'STNDDB_', 8), (?, 'EXCLUSION', 'STNDEXP_', 7),
                  (?, 'VERIFICATION_RULE', 'STNDRULE_', 7), (?, 'CODE_RULE', 'STNDCD_', 8),
                  (?, 'COLUMN_MAPPING', 'STND_', 10), (?, 'BUSINESS_RULE', 'STNDPRF_', 7)
                """, systemId,systemId,systemId,systemId,systemId,systemId);
        return insertProject(systemId, systemCode, command, revision);
    }

    private CreatedProject createVersion(long systemId, String systemCode, CreateProject command) {
        Integer revision = jdbc.queryForObject(
                "select coalesce(max(project_revision), 0) + 1 from build_project where system_id=?",
                Integer.class, systemId);
        jdbc.update("update build_project set active=false,updated_at=now() where system_id=? and target_year=? and active",
                systemId, command.targetYear());
        return insertProject(systemId, systemCode, command, revision == null ? 1 : revision);
    }

    private CreatedProject insertProject(long systemId, String systemCode, CreateProject command, int revision) {
        long projectId = jdbc.queryForObject("""
                insert into build_project(system_id,target_year,deployment_year_month,status,project_revision)
                values(?,?,?,'DRAFT',?) returning id
                """, Long.class, systemId, command.targetYear(), command.deploymentYearMonth(), revision);
        ids.allocate(WdqIdType.DB_CONNECTION, systemId, Long.toString(systemId), projectId);
        return new CreatedProject(projectId, systemId, ProjectStatus.DRAFT, systemCode, revision);
    }

    private Long findSystemId(String systemCode, String systemName) {
        List<SystemIdentity> systems = jdbc.query("select id,system_code,system_name from standard_system",
                (rs,n) -> new SystemIdentity(rs.getLong(1),rs.getString(2),rs.getString(3)));
        String canonicalName = canonicalSystemName(systemName);
        return systems.stream()
                .filter(system -> system.systemCode().equalsIgnoreCase(systemCode)
                        || canonicalSystemName(system.systemName()).equals(canonicalName))
                .map(SystemIdentity::id).findFirst().orElse(null);
    }

    private String canonicalSystemName(String value) {
        if (value == null) return "";
        return value.trim().toUpperCase().replaceAll("\\s+", "").replace("시스템", "");
    }

    public List<ProjectOverview> list() {
        return jdbc.query("""
                select p.id,s.system_code,s.system_name,p.project_revision,p.target_year,p.deployment_year_month,
                  p.status,p.active,p.created_at,p.updated_at,
                  (select count(*) from source_file f where f.project_id=p.id),
                  (select count(*) from normalized_item n where n.project_id=p.id),
                  (select count(*) from data_conflict c where c.project_id=p.id and c.resolution is null)
                from build_project p join standard_system s on s.id=p.system_id
                order by p.updated_at desc,p.id desc
                """, (rs, rowNum) -> new ProjectOverview(rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getInt(4), rs.getInt(5), rs.getString(6), ProjectStatus.valueOf(rs.getString(7)),
                        rs.getBoolean(8), rs.getObject(9, OffsetDateTime.class), rs.getObject(10, OffsetDateTime.class),
                        rs.getInt(11), rs.getInt(12), rs.getInt(13)));
    }

    @Transactional
    public ProjectOverview update(long projectId, UpdateProject command) {
        ProjectDetails before = details(projectId);
        try {
            jdbc.update("""
                    update standard_system set system_code=?,system_name=?,dbms_type=?,dbms_physical_name=?,
                      default_schema_original=?,default_schema_normalized=?,updated_at=now() where id=?
                    """, command.systemCode().trim(), command.systemName().trim(), DbmsTypeCodes.normalize(command.dbmsType()),
                    command.dbmsPhysicalName().trim(), command.defaultSchema().trim(),
                    command.defaultSchema().trim().toUpperCase(), before.systemId());
            jdbc.update("""
                    update build_project set status=case when status in ('APPROVED','GENERATED','VALIDATED')
                      then 'NEEDS_REVIEW' else status end,approved_by=null,approved_at=null,
                      approved_snapshot_hash=null,updated_at=now() where system_id=?
                    """, before.systemId());
            jdbc.update("""
                    update build_project set target_year=?,deployment_year_month=?,
                      status=case when status in ('APPROVED','GENERATED','VALIDATED') then 'NEEDS_REVIEW' else status end,
                      approved_by=null,approved_at=null,approved_snapshot_hash=null,updated_at=now() where id=?
                    """, command.targetYear(), command.deploymentYearMonth(), projectId);
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException("이미 사용 중인 시스템 코드 또는 대상연도입니다", e);
        }
        ProjectDetails after = details(projectId);
        jdbc.update("insert into project_change_history(project_id,entity_type,entity_id,action,before_json,after_json) values(?, 'PROJECT', ?, 'UPDATE', ?::jsonb, ?::jsonb)",
                projectId, String.valueOf(projectId), write(detailsMap(before)), write(detailsMap(after)));
        return list().stream().filter(row -> row.projectId() == projectId).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("프로젝트를 찾을 수 없습니다: " + projectId));
    }

    private ProjectDetails details(long projectId) {
        ProjectDetails value = jdbc.query("""
                select p.system_id,s.system_code,s.system_name,s.dbms_type,s.dbms_physical_name,
                  s.default_schema_original,p.target_year,p.deployment_year_month
                from build_project p join standard_system s on s.id=p.system_id where p.id=?
                """, rs -> rs.next() ? new ProjectDetails(rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getInt(7), rs.getString(8)) : null,
                projectId);
        if (value == null) throw new IllegalArgumentException("프로젝트를 찾을 수 없습니다: " + projectId);
        return value;
    }
    private Map<String, Object> detailsMap(ProjectDetails value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("systemCode", value.systemCode()); map.put("systemName", value.systemName());
        map.put("dbmsType", value.dbmsType()); map.put("dbmsPhysicalName", value.dbmsPhysicalName());
        map.put("defaultSchema", value.defaultSchema()); map.put("targetYear", value.targetYear());
        map.put("deploymentYearMonth", value.deploymentYearMonth()); return map;
    }
    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException(e); }
    }

    public enum DuplicateHandling { CREATE_VERSION, CREATE_SEPARATE }

    public record CreateProject(String systemCode, String systemName, String dbmsType, String dbmsPhysicalName,
            String defaultSchema, int targetYear, String deploymentYearMonth, DuplicateHandling duplicateHandling) {
        public CreateProject(String systemCode, String systemName, String dbmsType, String dbmsPhysicalName,
                String defaultSchema, int targetYear, String deploymentYearMonth) {
            this(systemCode, systemName, dbmsType, dbmsPhysicalName, defaultSchema, targetYear, deploymentYearMonth,
                    DuplicateHandling.CREATE_VERSION);
        }
    }

    public record CreatedProject(long projectId, long systemId, ProjectStatus status, String systemCode,
            int revision) { }

    public record ProjectOverview(long projectId, String systemCode, String systemName, int revision,
            int targetYear, String deploymentYearMonth, ProjectStatus status, boolean active,
            OffsetDateTime createdAt, OffsetDateTime updatedAt, int sourceFileCount, int itemCount,
            int unresolvedConflictCount) { }
    public record UpdateProject(String systemCode, String systemName, String dbmsType, String dbmsPhysicalName,
            String defaultSchema, int targetYear, String deploymentYearMonth) { }
    private record ProjectDetails(long systemId, String systemCode, String systemName, String dbmsType,
            String dbmsPhysicalName, String defaultSchema, int targetYear, String deploymentYearMonth) { }
    private record SystemIdentity(long id,String systemCode,String systemName) { }
}
