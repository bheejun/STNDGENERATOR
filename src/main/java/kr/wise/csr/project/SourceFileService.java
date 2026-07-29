package kr.wise.csr.project;

import java.io.InputStream;
import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.core.io.Resource;
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

    public List<SourceFileView> list(long projectId) {
        return jdbc.query("""
                select id,original_name,artifact_kind,sha256,byte_size,parse_status,input_track,workbook_type,created_at
                from source_file where project_id=? order by created_at,id
                """,(rs,n)->new SourceFileView(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getString(4),
                        rs.getLong(5),rs.getString(6),rs.getString(7),rs.getString(8),
                        rs.getObject(9,OffsetDateTime.class)),projectId);
    }

    public void classify(long fileId, String inputTrack, String workbookType) {
        jdbc.update("update source_file set input_track=?,workbook_type=? where id=?",
                inputTrack, workbookType, fileId);
    }

    public Resource load(long projectId,long fileId) {
        String path=jdbc.query("""
                select stored_path from source_file where project_id=? and id=?
                """,rs->rs.next()?rs.getString(1):null,projectId,fileId);
        if(path==null)throw new IllegalArgumentException("프로젝트 원본 파일을 찾을 수 없습니다: "+fileId);
        return storage.load(path);
    }

    public record SourceFileView(long id,String originalName,String artifactKind,String sha256,long byteSize,
            String parseStatus,String inputTrack,String workbookType,OffsetDateTime createdAt){}
}
