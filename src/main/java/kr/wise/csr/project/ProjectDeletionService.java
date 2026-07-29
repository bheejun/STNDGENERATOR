package kr.wise.csr.project;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import kr.wise.csr.storage.FileStorage;

@Service
public class ProjectDeletionService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final FileStorage storage;

    public ProjectDeletionService(JdbcTemplate jdbc, TransactionTemplate transaction, FileStorage storage) {
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.storage = storage;
    }

    public DeletedProject delete(long projectId) {
        ProjectIdentity project = find(projectId);
        transaction.executeWithoutResult(status -> {
            int deleted = jdbc.update("delete from build_project where id=?", projectId);
            if (deleted != 1) throw new IllegalArgumentException("프로젝트를 찾을 수 없습니다: " + projectId);
            if (project.active()) {
                jdbc.update("""
                        update build_project set active=true,updated_at=now()
                        where id=(
                          select id from build_project
                          where system_id=? and target_year=?
                          order by project_revision desc,id desc limit 1
                        )
                        """, project.systemId(), project.targetYear());
            }
        });
        storage.deleteProject(projectId);
        return new DeletedProject(projectId, project.systemName(), project.revision());
    }

    private ProjectIdentity find(long projectId) {
        List<ProjectIdentity> rows = jdbc.query("""
                select p.system_id,p.target_year,p.project_revision,p.active,s.system_name
                from build_project p join standard_system s on s.id=p.system_id where p.id=?
                """, (rs, row) -> new ProjectIdentity(rs.getLong(1), rs.getInt(2), rs.getInt(3),
                        rs.getBoolean(4), rs.getString(5)), projectId);
        if (rows.isEmpty()) throw new IllegalArgumentException("프로젝트를 찾을 수 없습니다: " + projectId);
        return rows.getFirst();
    }

    private record ProjectIdentity(long systemId, int targetYear, int revision, boolean active, String systemName) {
    }

    public record DeletedProject(long projectId, String systemName, int revision) {
    }
}
