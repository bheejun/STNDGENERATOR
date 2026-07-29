package kr.wise.csr.api;

import java.nio.charset.StandardCharsets;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import kr.wise.csr.export.CriteriaTemplateService;
import kr.wise.csr.export.GeneratedFile;
import kr.wise.csr.importfile.ProjectImportService.CriteriaCategory;

@RestController
@RequestMapping("/api/criteria-templates")
public class CriteriaTemplateController {
    private final CriteriaTemplateService templates;

    public CriteriaTemplateController(CriteriaTemplateService templates) {
        this.templates = templates;
    }

    @GetMapping("/{category}")
    public ResponseEntity<byte[]> download(@PathVariable CriteriaCategory category) {
        GeneratedFile file = templates.create(category);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.fileName(), StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.CONTENT_TYPE, file.mediaType())
                .contentLength(file.content().length)
                .body(file.content());
    }
}
