package kr.wise.csr.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import kr.wise.csr.sqlcompare.SqlBaselineComparisonService;
import kr.wise.csr.sqlcompare.SqlComparisonResult;

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
}
