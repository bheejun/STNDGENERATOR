package kr.wise.csr.api;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import kr.wise.csr.export.DatasetSqlExporter;
import kr.wise.csr.export.GeneratedFile;
import kr.wise.csr.export.StandardWorkbookExporter;
import kr.wise.csr.export.WdqExeBuilder;
import kr.wise.csr.export.ArtifactHistoryService;
import kr.wise.csr.importfile.ProjectImportService;
import kr.wise.csr.importfile.ProjectImportService.InputTrack;
import kr.wise.csr.importfile.ProjectImportService.CriteriaCategory;
import kr.wise.csr.normalization.ConflictResolution;
import kr.wise.csr.normalization.ProjectConflictService;
import kr.wise.csr.normalization.ResolveConflictCommand;
import kr.wise.csr.project.ProjectSnapshot;
import kr.wise.csr.project.ProjectSnapshotRepository;
import kr.wise.csr.validation.ProjectValidator;
import kr.wise.csr.validation.ValidationReport;

@RestController
@RequestMapping("/api/projects")
public class ProjectWorkflowController {
    private final ProjectSnapshotRepository snapshots;
    private final ProjectValidator validator;
    private final StandardWorkbookExporter workbookExporter;
    private final DatasetSqlExporter sqlExporter;
    private final ProjectImportService imports;
    private final ProjectConflictService conflicts;
    private final WdqExeBuilder exeBuilder;
    private final ArtifactHistoryService artifactHistory;

    public ProjectWorkflowController(ProjectSnapshotRepository snapshots, ProjectValidator validator,
            StandardWorkbookExporter workbookExporter, DatasetSqlExporter sqlExporter,
            ProjectImportService imports, ProjectConflictService conflicts, WdqExeBuilder exeBuilder,
            ArtifactHistoryService artifactHistory) {
        this.snapshots = snapshots;
        this.validator = validator;
        this.workbookExporter = workbookExporter;
        this.sqlExporter = sqlExporter;
        this.imports = imports;
        this.conflicts = conflicts;
        this.exeBuilder = exeBuilder;
        this.artifactHistory = artifactHistory;
    }

    @PostMapping("/{projectId}/conflicts/{conflictId}/resolution")
    public ProjectSnapshot resolveConflict(@PathVariable long projectId, @PathVariable long conflictId,
            @Valid @RequestBody ResolveConflictRequest request) {
        return conflicts.resolve(projectId, new ResolveConflictCommand(conflictId, request.resolution(),
                request.manualValue(), request.reason()));
    }

    @PostMapping(value = "/{projectId}/imports", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ProjectImportService.ImportResult importFiles(@PathVariable long projectId,
            @RequestPart("files") List<MultipartFile> files) {
        return imports.importFiles(projectId, files);
    }

    @PostMapping(value = "/{projectId}/imports/result-report", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ProjectImportService.ImportResult importResultReport(@PathVariable long projectId,
            @RequestPart("files") List<MultipartFile> files) {
        return imports.importFiles(projectId, files, InputTrack.RESULT_REPORT);
    }

    @PostMapping(value = "/{projectId}/imports/criteria", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ProjectImportService.ImportResult importCriteria(@PathVariable long projectId,
            @RequestPart("files") List<MultipartFile> files,
            @RequestParam(required = false) CriteriaCategory category) {
        return imports.importFiles(projectId, files, InputTrack.CRITERIA_FILES, category);
    }

    @PostMapping("/{projectId}/reextract")
    public ProjectImportService.ImportResult reextract(@PathVariable long projectId) {
        project(projectId);
        return imports.reextract(projectId);
    }

    @PostMapping("/{projectId}/reextract/result-report")
    public ProjectImportService.ImportResult reextractResultReport(@PathVariable long projectId) {
        project(projectId);
        return imports.reextract(projectId, InputTrack.RESULT_REPORT);
    }

    @PostMapping("/{projectId}/reextract/criteria")
    public ProjectImportService.ImportResult reextractCriteria(@PathVariable long projectId) {
        project(projectId);
        return imports.reextract(projectId, InputTrack.CRITERIA_FILES);
    }

    @GetMapping("/{projectId}")
    public ProjectSnapshot getProject(@PathVariable long projectId) {
        return project(projectId);
    }

    @PostMapping("/{projectId}/validation")
    public ValidationReport validate(@PathVariable long projectId) {
        return validator.validate(project(projectId));
    }

    @GetMapping("/{projectId}/artifacts/workbook")
    public ResponseEntity<byte[]> downloadWorkbook(@PathVariable long projectId) {
        GeneratedFile file = workbookExporter.exportWorkbook(project(projectId));
        return artifact(projectId,"WORKBOOK",file.fileName(),file.mediaType(),file.content());
    }

    @GetMapping("/{projectId}/artifacts/sql")
    public ResponseEntity<byte[]> downloadSql(@PathVariable long projectId) {
        ProjectSnapshot project = validProject(projectId);
        List<GeneratedFile> files = sqlExporter.exportDatasetSql(project);
        String name="common-standard-rules-" + project.targetYear() + "-sql.zip";
        return artifact(projectId,"SQL",name,"application/zip",zip(files));
    }

    @GetMapping("/{projectId}/artifacts/exe")
    public ResponseEntity<byte[]> downloadExe(@PathVariable long projectId) {
        GeneratedFile file = exeBuilder.build(validProject(projectId));
        return artifact(projectId,"PATCH_EXE",file.fileName(),file.mediaType(),file.content());
    }

    @GetMapping("/{projectId}/artifacts/delete-exe")
    public ResponseEntity<byte[]> downloadDeleteExe(@PathVariable long projectId) {
        GeneratedFile file = exeBuilder.buildDelete(validProject(projectId));
        return artifact(projectId,"DELETE_EXE",file.fileName(),file.mediaType(),file.content());
    }

    @GetMapping("/{projectId}/artifacts/history")
    public List<ArtifactHistoryService.History> artifactHistory(@PathVariable long projectId) {
        project(projectId); return artifactHistory.list(projectId);
    }

    private ResponseEntity<byte[]> artifact(long projectId,String kind,String name,String mediaType,byte[] content){
        artifactHistory.record(projectId,kind,name,content); return download(name,mediaType,content);
    }

    private ProjectSnapshot project(long projectId) {
        return snapshots.findByProjectId(projectId)
                .orElseThrow(() -> new ProjectNotFoundException(projectId));
    }

    private ProjectSnapshot validProject(long projectId) {
        ProjectSnapshot project = project(projectId);
        ValidationReport report = validator.validate(project);
        if (report.hasErrors()) {
            var first = report.issues().stream()
                    .filter(issue -> issue.severity() == kr.wise.csr.validation.Severity.ERROR)
                    .findFirst().orElseThrow();
            throw new IllegalStateException("WDQ DDL 유효성 검증 실패: " + first.message()
                    + (first.logicalKey() == null ? "" : " [" + first.logicalKey() + "]"));
        }
        return project;
    }

    private ResponseEntity<byte[]> download(String fileName, String mediaType, byte[] content) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(mediaType));
        headers.setContentDisposition(ContentDisposition.attachment().filename(fileName, StandardCharsets.UTF_8).build());
        return ResponseEntity.ok().headers(headers).contentLength(content.length).body(content);
    }

    private byte[] zip(List<GeneratedFile> files) {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (GeneratedFile file : files) {
                zip.putNextEntry(new ZipEntry(file.fileName()));
                zip.write(file.content());
                zip.closeEntry();
            }
            zip.finish();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("SQL 압축 파일 생성에 실패했습니다", e);
        }
    }

    public record ResolveConflictRequest(@NotNull ConflictResolution resolution, String manualValue, String reason) {
    }
}
