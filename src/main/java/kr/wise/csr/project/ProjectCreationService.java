package kr.wise.csr.project;

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
}
