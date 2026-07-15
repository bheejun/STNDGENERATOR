package kr.wise.csr.storage;

public record StoredFile(
        String originalName,
        String storedPath,
        String sha256,
        long byteSize) {
}
