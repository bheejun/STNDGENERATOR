package kr.wise.csr.api;

import java.nio.charset.StandardCharsets;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.core.io.Resource;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import kr.wise.csr.sqlcompare.SqlBaselineComparisonService;
import kr.wise.csr.sqlcompare.SqlComparisonResult;
import kr.wise.csr.sqlcompare.SqlBaselineInfo;

@RestController
@RequestMapping("/api/projects/{projectId}/sql-comparison")
public class ProjectSqlComparisonController {
    private final SqlBaselineComparisonService service;

    public ProjectSqlComparisonController(SqlBaselineComparisonService service) {
        this.service = service;
    }

    @GetMapping
    public SqlComparisonResult compare(@PathVariable long projectId) {
        return service.compare(projectId);
    }

    @PostMapping("/baseline")
    public ResponseEntity<SqlComparisonResult> upload(@PathVariable long projectId,
            @RequestPart("file") MultipartFile file) {
        return ResponseEntity.ok(service.uploadAndCompare(projectId, file));
    }

    @GetMapping("/baseline")
    public SqlBaselineInfo baseline(@PathVariable long projectId) {
        return service.info(projectId);
    }

    @GetMapping("/baseline/file")
    public ResponseEntity<Resource> download(@PathVariable long projectId) {
        var file = service.download(projectId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/plain; charset=UTF-8"))
                .contentLength(file.byteSize())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.originalName(), StandardCharsets.UTF_8).build().toString())
                .body(file.resource());
    }

    @DeleteMapping("/baseline")
    public ResponseEntity<Void> delete(@PathVariable long projectId) {
        service.delete(projectId);
        return ResponseEntity.noContent().build();
    }
}
