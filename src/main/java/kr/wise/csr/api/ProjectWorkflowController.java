package kr.wise.csr.api;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import kr.wise.csr.approval.ApprovalService;
import kr.wise.csr.export.DatasetSqlExporter;
import kr.wise.csr.export.GeneratedFile;
import kr.wise.csr.export.StandardWorkbookExporter;
import kr.wise.csr.export.WdqExeBuilder;
import kr.wise.csr.importfile.ProjectImportService;
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
    private final ApprovalService approvals;
    private final StandardWorkbookExporter workbookExporter;
    private final DatasetSqlExporter sqlExporter;
    private final ProjectImportService imports;
    private final ProjectConflictService conflicts;
    private final WdqExeBuilder exeBuilder;

    public ProjectWorkflowController(ProjectSnapshotRepository snapshots, ProjectValidator validator,
            ApprovalService approvals, StandardWorkbookExporter workbookExporter, DatasetSqlExporter sqlExporter,
            ProjectImportService imports, ProjectConflictService conflicts, WdqExeBuilder exeBuilder) {
        this.snapshots = snapshots;
        this.validator = validator;
        this.approvals = approvals;
        this.workbookExporter = workbookExporter;
        this.sqlExporter = sqlExporter;
        this.imports = imports;
        this.conflicts = conflicts;
        this.exeBuilder = exeBuilder;
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

    @GetMapping("/{projectId}")
    public ProjectSnapshot getProject(@PathVariable long projectId) {
        return project(projectId);
    }

    @PostMapping("/{projectId}/validation")
    public ValidationReport validate(@PathVariable long projectId) {
        return validator.validate(project(projectId));
    }

    @PostMapping("/{projectId}/approval")
    public ProjectSnapshot approve(@PathVariable long projectId, @Valid @RequestBody ApprovalRequest request) {
        approvals.approve(projectId, request.approverName());
        return project(projectId);
    }

    @GetMapping("/{projectId}/artifacts/workbook")
    public ResponseEntity<byte[]> downloadWorkbook(@PathVariable long projectId) {
        GeneratedFile file = workbookExporter.exportWorkbook(project(projectId));
        return download(file.fileName(), file.mediaType(), file.content());
    }

    @GetMapping("/{projectId}/artifacts/sql")
    public ResponseEntity<byte[]> downloadSql(@PathVariable long projectId) {
        ProjectSnapshot project = project(projectId);
        List<GeneratedFile> files = sqlExporter.exportDatasetSql(project);
        return download("common-standard-rules-" + project.targetYear() + "-sql.zip", "application/zip", zip(files));
    }

    @GetMapping("/{projectId}/artifacts/exe")
    public ResponseEntity<byte[]> downloadExe(@PathVariable long projectId) {
        GeneratedFile file = exeBuilder.build(project(projectId));
        return download(file.fileName(), file.mediaType(), file.content());
    }

    private ProjectSnapshot project(long projectId) {
        return snapshots.findByProjectId(projectId)
                .orElseThrow(() -> new ProjectNotFoundException(projectId));
    }

    private ResponseEntity<byte[]> download(String fileName, String mediaType, byte[] content) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(mediaType));
        headers.setContentDisposition(ContentDisposition.attachment().filename(fileName).build());
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

    public record ApprovalRequest(@NotBlank(message = "승인 담당자명이 필요합니다") String approverName) {
    }

    public record ResolveConflictRequest(@NotNull ConflictResolution resolution, String manualValue, String reason) {
    }
}
