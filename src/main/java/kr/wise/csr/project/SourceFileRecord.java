package kr.wise.csr.project;

public record SourceFileRecord(
        long id,
        long projectId,
        String artifactKind,
        String originalName,
        String storedPath,
        String sha256,
        long byteSize,
        String parseStatus) {
}
