package kr.wise.csr.api;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import kr.wise.csr.project.SourceFileService;

@RestController
@RequestMapping("/api/projects/{projectId}/files")
public class ProjectSourceFileController {
    private final SourceFileService files;

    public ProjectSourceFileController(SourceFileService files) { this.files=files; }

    @GetMapping
    public List<SourceFileService.SourceFileView> list(@PathVariable long projectId) {
        return files.list(projectId);
    }

    @GetMapping("/{fileId}")
    public ResponseEntity<Resource> download(@PathVariable long projectId,@PathVariable long fileId) {
        SourceFileService.SourceFileView file=files.list(projectId).stream().filter(row->row.id()==fileId)
                .findFirst().orElseThrow(()->new IllegalArgumentException("프로젝트 원본 파일을 찾을 수 없습니다: "+fileId));
        Resource resource=files.load(projectId,fileId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment()
                        .filename(file.originalName(),StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(file.byteSize()).body(resource);
    }
}
