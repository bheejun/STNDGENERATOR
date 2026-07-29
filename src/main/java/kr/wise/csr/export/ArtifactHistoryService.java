package kr.wise.csr.export;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ArtifactHistoryService {
    private final JdbcTemplate jdbc;
    public ArtifactHistoryService(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public void record(long projectId,String kind,String fileName,byte[] content){
        jdbc.update("insert into artifact_generation_history(project_id,artifact_kind,file_name,byte_size,sha256) values(?,?,?,?,?)",
                projectId,kind,fileName,content.length,sha256(content));
    }
    public List<History> list(long projectId){
        return jdbc.query("select id,artifact_kind,file_name,byte_size,sha256,created_at from artifact_generation_history where project_id=? order by created_at desc",
                (rs,n)->new History(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getLong(4),rs.getString(5),rs.getObject(6,OffsetDateTime.class)),projectId);
    }
    private String sha256(byte[] content){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    public record History(long id,String artifactKind,String fileName,long byteSize,String sha256,OffsetDateTime createdAt){}
}
