package kr.wise.csr.project;

import org.springframework.dao.DuplicateKeyException;
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
        try {
            long systemId = jdbc.queryForObject("""
                    insert into standard_system(system_code,system_name,dbms_type,dbms_physical_name,
                      default_schema_original,default_schema_normalized)
                    values(?,?,?,?,?,?) returning id
                    """, Long.class, command.systemCode().trim(), command.systemName().trim(),
                    command.dbmsType().trim().toUpperCase(), command.dbmsPhysicalName().trim(),
                    command.defaultSchema().trim(), command.defaultSchema().trim().toUpperCase());
            long projectId = jdbc.queryForObject("""
                    insert into build_project(system_id,target_year,deployment_year_month,status)
                    values(?,?,?,'DRAFT') returning id
                    """, Long.class, systemId, command.targetYear(), command.deploymentYearMonth());
            return new CreatedProject(projectId, systemId, ProjectStatus.DRAFT);
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException("이미 등록된 시스템 코드 또는 활성 프로젝트입니다", e);
        }
    }

    public record CreateProject(String systemCode, String systemName, String dbmsType, String dbmsPhysicalName,
            String defaultSchema, int targetYear, String deploymentYearMonth) {
    }

    public record CreatedProject(long projectId, long systemId, ProjectStatus status) {
    }
}
