package kr.wise.csr.api;

import java.nio.charset.StandardCharsets;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import kr.wise.csr.export.ArtifactHistoryService;
import kr.wise.csr.export.CombinedSqlExporter;
import kr.wise.csr.export.GeneratedFile;
import kr.wise.csr.project.ProjectSnapshot;
import kr.wise.csr.project.ProjectSnapshotRepository;
import kr.wise.csr.validation.ProjectValidator;

@RestController
@RequestMapping("/api/projects")
public class ProjectCombinedSqlController {
    private final ProjectSnapshotRepository snapshots;
    private final ProjectValidator validator;
    private final CombinedSqlExporter exporter;
    private final ArtifactHistoryService history;

    public ProjectCombinedSqlController(ProjectSnapshotRepository snapshots, ProjectValidator validator,
            CombinedSqlExporter exporter, ArtifactHistoryService history) {
        this.snapshots = snapshots;
        this.validator = validator;
        this.exporter = exporter;
        this.history = history;
    }

    @GetMapping("/{projectId}/artifacts/combined-sql")
    public ResponseEntity<byte[]> download(@PathVariable long projectId) {
        ProjectSnapshot project = snapshots.findByProjectId(projectId)
                .orElseThrow(() -> new ProjectNotFoundException(projectId));
        var report = validator.validate(project);
        if (report.hasErrors()) {
            var first = report.issues().stream()
                    .filter(issue -> issue.severity() == kr.wise.csr.validation.Severity.ERROR)
                    .findFirst().orElseThrow();
            throw new IllegalStateException("WDQ DDL 유효성 검증 실패: " + first.message());
        }
        GeneratedFile file = exporter.export(project);
        history.record(projectId, "COMBINED_SQL", file.fileName(), file.content());
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(file.mediaType()));
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(file.fileName(), StandardCharsets.UTF_8).build());
        return ResponseEntity.ok().headers(headers).contentLength(file.content().length).body(file.content());
    }
}
