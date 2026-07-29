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
import kr.wise.csr.export.GeneratedFile;
import kr.wise.csr.export.TestDdlExporter;
import kr.wise.csr.project.ProjectSnapshot;
import kr.wise.csr.project.ProjectSnapshotRepository;

@RestController
@RequestMapping("/api/projects")
public class ProjectDdlController {
    private final ProjectSnapshotRepository snapshots;
    private final TestDdlExporter exporter;
    private final ArtifactHistoryService history;

    public ProjectDdlController(ProjectSnapshotRepository snapshots, TestDdlExporter exporter,
            ArtifactHistoryService history) {
        this.snapshots = snapshots;
        this.exporter = exporter;
        this.history = history;
    }

    @GetMapping("/{projectId}/artifacts/ddl")
    public ResponseEntity<byte[]> download(@PathVariable long projectId) {
        ProjectSnapshot project = snapshots.findByProjectId(projectId)
                .orElseThrow(() -> new ProjectNotFoundException(projectId));
        GeneratedFile file = exporter.export(project);
        history.record(projectId, "TEST_DDL", file.fileName(), file.content());
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(file.mediaType()));
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(file.fileName(), StandardCharsets.UTF_8).build());
        return ResponseEntity.ok().headers(headers).contentLength(file.content().length).body(file.content());
    }
}
