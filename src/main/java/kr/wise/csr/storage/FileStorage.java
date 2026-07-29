package kr.wise.csr.storage;

import java.io.InputStream;
import org.springframework.core.io.Resource;

public interface FileStorage {
    StoredFile store(long projectId, ArtifactKind kind, String originalName, InputStream input);
    Resource load(String storedPath);
    void deleteProject(long projectId);
}
