package kr.wise.csr.sqlcompare;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import kr.wise.csr.api.ProjectNotFoundException;
import kr.wise.csr.export.CombinedSqlExporter;
import kr.wise.csr.project.ProjectSnapshot;
import kr.wise.csr.project.ProjectSnapshotRepository;
import kr.wise.csr.storage.ArtifactKind;
import kr.wise.csr.storage.FileStorage;
import kr.wise.csr.storage.StoredFile;

@Service
public class SqlBaselineComparisonService {
    private final JdbcTemplate jdbc;
    private final FileStorage storage;
    private final ProjectSnapshotRepository snapshots;
    private final CombinedSqlExporter exporter;
    private final SqlStatementComparator comparator;

    public SqlBaselineComparisonService(JdbcTemplate jdbc, FileStorage storage,
            ProjectSnapshotRepository snapshots, CombinedSqlExporter exporter,
            SqlStatementComparator comparator) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.snapshots = snapshots;
        this.exporter = exporter;
        this.comparator = comparator;
    }

    @Transactional
    public SqlComparisonResult uploadAndCompare(long projectId, MultipartFile file) {
        requireProject(projectId);
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("2025 기준 SQL 파일을 선택해주세요.");
        String name = file.getOriginalFilename();
        if (name == null || !name.toLowerCase().endsWith(".sql"))
            throw new IllegalArgumentException(".sql 파일만 등록할 수 있습니다.");
        try {
            StoredFile stored = storage.store(projectId, ArtifactKind.BASELINE_SQL, name, file.getInputStream());
            jdbc.update("""
                    insert into sql_baseline(project_id,original_name,stored_path,sha256,byte_size,uploaded_at)
                    values(?,?,?,?,?,now())
                    on conflict(project_id) do update set
                      original_name=excluded.original_name,stored_path=excluded.stored_path,
                      sha256=excluded.sha256,byte_size=excluded.byte_size,uploaded_at=now()
                    """, projectId, stored.originalName(), stored.storedPath(), stored.sha256(), stored.byteSize());
            return compare(projectId);
        } catch (IOException e) {
            throw new IllegalStateException("SQL 파일을 저장하지 못했습니다.", e);
        }
    }

    public SqlComparisonResult compare(long projectId) {
        ProjectSnapshot project = requireProject(projectId);
        List<Baseline> rows = jdbc.query("""
                select original_name,stored_path,uploaded_at
                from sql_baseline where project_id=?
                """, (rs, rowNum) -> new Baseline(rs.getString(1), rs.getString(2),
                        rs.getObject(3, OffsetDateTime.class)), projectId);
        if (rows.isEmpty()) throw new SqlBaselineNotFoundException(projectId);
        Baseline baseline = rows.getFirst();
        try {
            byte[] baselineBytes = storage.load(baseline.storedPath()).getInputStream().readAllBytes();
            String currentSql = new String(exporter.export(project).content(), StandardCharsets.UTF_8);
            return comparator.compare(baseline.originalName(), baseline.uploadedAt(),
                    decodeSql(baselineBytes), currentSql);
        } catch (IOException e) {
            throw new IllegalStateException("2025 기준 SQL 파일을 읽지 못했습니다.", e);
        }
    }

    private ProjectSnapshot requireProject(long projectId) {
        return snapshots.findByProjectId(projectId).orElseThrow(() -> new ProjectNotFoundException(projectId));
    }

    private String decodeSql(byte[] bytes) {
        String value;
        try {
            value = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            value = Charset.forName("MS949").decode(ByteBuffer.wrap(bytes)).toString();
        }
        return value.startsWith("\uFEFF") ? value.substring(1) : value;
    }

    private record Baseline(String originalName, String storedPath, OffsetDateTime uploadedAt) {
    }
}
