package kr.wise.csr.project;

import java.io.InputStream;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.wise.csr.storage.ArtifactKind;
import kr.wise.csr.storage.FileStorage;
import kr.wise.csr.storage.StoredFile;

@Service
public class SourceFileService {
    private final FileStorage storage;
    private final JdbcTemplate jdbc;

    public SourceFileService(FileStorage storage, JdbcTemplate jdbc) {
        this.storage = storage;
        this.jdbc = jdbc;
    }

    @Transactional
    public SourceFileRecord store(long projectId, ArtifactKind kind, String originalName, InputStream input) {
        StoredFile stored = storage.store(projectId, kind, originalName, input);
        return jdbc.queryForObject("""
                with inserted as (
                  insert into source_file(project_id, artifact_kind, original_name, stored_path, sha256, byte_size,
                    parse_status, excluded_pt01_count, excluded_pt02_count)
                  values (?, ?, ?, ?, ?, ?, 'UNDETECTED', 0, 0)
                  on conflict (project_id, sha256) do nothing
                  returning id, project_id, artifact_kind, original_name, stored_path, sha256, byte_size, parse_status
                )
                select * from inserted
                union all
                select id, project_id, artifact_kind, original_name, stored_path, sha256, byte_size, parse_status
                  from source_file where project_id=? and sha256=?
                limit 1
                """, (rs, row) -> new SourceFileRecord(rs.getLong("id"), rs.getLong("project_id"),
                        rs.getString("artifact_kind"), rs.getString("original_name"), rs.getString("stored_path"),
                        rs.getString("sha256"), rs.getLong("byte_size"), rs.getString("parse_status")),
                projectId, kind.name(), stored.originalName(), stored.storedPath(), stored.sha256(), stored.byteSize(),
                projectId, stored.sha256());
    }
}
