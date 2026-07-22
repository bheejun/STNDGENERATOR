package kr.wise.csr.project;

import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProjectCreationService {
    private final JdbcTemplate jdbc;

    public ProjectCreationService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public CreatedProject create(CreateProject command) {
        String requestedCode = command.systemCode().trim();
        Long existingSystemId = findSystemId(requestedCode);
        if (existingSystemId == null) return createForNewSystem(command, requestedCode, 1);
        return command.duplicateHandling() == DuplicateHandling.CREATE_SEPARATE
                ? createSeparate(command, requestedCode)
                : createVersion(existingSystemId, requestedCode, command);
    }

    private CreatedProject createForNewSystem(CreateProject command, String systemCode, int revision) {
        long systemId = jdbc.queryForObject("""
                insert into standard_system(system_code,system_name,dbms_type,dbms_physical_name,
                  default_schema_original,default_schema_normalized)
                values(?,?,?,?,?,?) returning id
                """, Long.class, systemCode, command.systemName().trim(),
                command.dbmsType().trim().toUpperCase(), command.dbmsPhysicalName().trim(),
                command.defaultSchema().trim(), command.defaultSchema().trim().toUpperCase());
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

    private CreatedProject createSeparate(CreateProject command, String requestedCode) {
        int suffix = 2;
        String separateCode;
        do separateCode = requestedCode + "-" + suffix++;
        while (findSystemId(separateCode) != null);
        return createForNewSystem(command, separateCode, 1);
    }

    private CreatedProject insertProject(long systemId, String systemCode, CreateProject command, int revision) {
        long projectId = jdbc.queryForObject("""
                insert into build_project(system_id,target_year,deployment_year_month,status,project_revision)
                values(?,?,?,'DRAFT',?) returning id
                """, Long.class, systemId, command.targetYear(), command.deploymentYearMonth(), revision);
        return new CreatedProject(projectId, systemId, ProjectStatus.DRAFT, systemCode, revision);
    }

    private Long findSystemId(String systemCode) {
        return jdbc.query("select id from standard_system where system_code=?", rs -> rs.next() ? rs.getLong(1) : null,
                systemCode);
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
}
