package kr.wise.csr.importfile;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import kr.wise.csr.normalization.NormalizationSummary;
import kr.wise.csr.normalization.ProjectNormalizationService;
import kr.wise.csr.project.SourceFileRecord;
import kr.wise.csr.project.SourceFileService;
import kr.wise.csr.storage.ArtifactKind;

@Service
public class ProjectImportService {
    private final JdbcTemplate jdbc;
    private final SourceFileService sourceFiles;
    private final WorkbookDetector detector;
    private final List<WorkbookParser> parsers;
    private final SplitCriteriaWorkbookParser splitCriteriaParser;
    private final ProjectNormalizationService normalization;

    public ProjectImportService(JdbcTemplate jdbc, SourceFileService sourceFiles, WorkbookDetector detector,
            List<WorkbookParser> parsers, SplitCriteriaWorkbookParser splitCriteriaParser,
            ProjectNormalizationService normalization) {
        this.jdbc = jdbc;
        this.sourceFiles = sourceFiles;
        this.detector = detector;
        this.parsers = parsers;
        this.splitCriteriaParser = splitCriteriaParser;
        this.normalization = normalization;
    }

    public ImportResult importFiles(long projectId, List<MultipartFile> files) {
        if (files == null || files.isEmpty()) throw new IllegalArgumentException("업로드할 엑셀 파일이 필요합니다");
        ProjectContext context = context(projectId);
        List<ImportBatch> batches = new ArrayList<>();
        List<ImportedFile> imported = new ArrayList<>();

        for (MultipartFile file : files) {
            validate(file);
            try {
                SourceFileRecord stored = sourceFiles.store(projectId, ArtifactKind.SOURCE,
                        file.getOriginalFilename(), file.getInputStream());
                Path path = Path.of(stored.storedPath());
                WorkbookType type = detector.detect(path);
                ImportBatch batch = parse(path, context, type);
                batches.add(batch);
                imported.add(new ImportedFile(stored.id(), stored.originalName(), type, batch.candidates().size(),
                        batch.errors()));
                jdbc.update("update source_file set parse_status=? where id=?",
                        batch.errors().isEmpty() ? "PARSED" : "PARSE_ERROR", stored.id());
            } catch (IOException e) {
                throw new IllegalStateException("업로드 파일을 읽을 수 없습니다: " + file.getOriginalFilename(), e);
            }
        }

        NormalizationSummary summary = normalization.normalizeAndSave(projectId, context.systemId(), batches);
        return new ImportResult(projectId, List.copyOf(imported), summary.rows().size(), summary.conflicts().size(),
                summary.excludedPt01Count(), summary.excludedPt02Count(), summary.importErrors());
    }

    private ImportBatch parse(Path path, ProjectContext context, WorkbookType type) {
        if (splitCriteriaParser.supports(type)) return splitCriteriaParser.parse(path, context, type);
        return parsers.stream().filter(parser -> parser != splitCriteriaParser && parser.supports(type)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("파서가 없는 워크북 유형: " + type))
                .parse(path, context);
    }

    private ProjectContext context(long projectId) {
        List<ProjectContext> contexts = jdbc.query("""
                select p.id,p.system_id,p.target_year,p.deployment_year_month,s.dbms_physical_name,s.default_schema_original
                from build_project p join standard_system s on s.id=p.system_id where p.id=?
                """, (rs, row) -> new ProjectContext(rs.getLong(1), rs.getLong(2), rs.getInt(3), rs.getString(4),
                        rs.getString(5), rs.getString(6)), projectId);
        if (contexts.isEmpty()) throw new IllegalArgumentException("프로젝트를 찾을 수 없습니다: " + projectId);
        return contexts.getFirst();
    }

    private void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("빈 파일은 업로드할 수 없습니다");
        String name = file.getOriginalFilename();
        if (name == null || !(name.toLowerCase().endsWith(".xlsx") || name.toLowerCase().endsWith(".xls")))
            throw new IllegalArgumentException("엑셀 파일만 업로드할 수 있습니다: " + name);
    }

    public record ImportedFile(long sourceFileId, String fileName, WorkbookType workbookType,
            int candidateCount, List<String> errors) {
    }

    public record ImportResult(long projectId, List<ImportedFile> files, int normalizedRowCount, int conflictCount,
            int excludedPt01Count, int excludedPt02Count, List<String> errors) {
    }
}
