package kr.wise.csr.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.PathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

@Component
public class LocalFileStorage implements FileStorage {
    private final Path root;

    @Autowired
    public LocalFileStorage(@Value("${csr.storage.root:./data}") String root) {
        this(Path.of(root));
    }

    public LocalFileStorage(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public StoredFile store(long projectId, ArtifactKind kind, String originalName, InputStream input) {
        if (projectId < 1) throw new IllegalArgumentException("projectId must be positive");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(input, "input");
        validateOriginalName(originalName);

        Path directory = confined(root.resolve(Long.toString(projectId)).resolve(kind.name().toLowerCase()));
        try {
            Files.createDirectories(directory);
            Path temporary = Files.createTempFile(directory, "upload-", ".tmp");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long size;
            try (input; DigestInputStream digested = new DigestInputStream(input, digest)) {
                size = Files.copy(digested, temporary, StandardCopyOption.REPLACE_EXISTING);
            }
            String checksum = HexFormat.of().formatHex(digest.digest());
            Path target = confined(directory.resolve(checksum + ".bin"));
            if (Files.exists(target)) {
                Files.deleteIfExists(temporary);
            } else {
                moveAtomically(temporary, target);
            }
            return new StoredFile(originalName, target.toString(), checksum, size);
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new FileStorageException("Failed to store file", e);
        }
    }

    @Override
    public Resource load(String storedPath) {
        if (storedPath == null || storedPath.isBlank()) throw new IllegalArgumentException("storedPath is required");
        Path path = confined(Path.of(storedPath));
        if (!Files.isRegularFile(path)) throw new FileStorageException("Stored file does not exist: " + path);
        return new PathResource(path);
    }

    private void validateOriginalName(String originalName) {
        if (originalName == null || originalName.isBlank()
                || !Path.of(originalName).getFileName().toString().equals(originalName)
                || originalName.contains("/") || originalName.contains("\\")) {
            throw new IllegalArgumentException("originalName must be a plain file name");
        }
    }

    private Path confined(Path candidate) {
        Path normalized = candidate.toAbsolutePath().normalize();
        if (!normalized.startsWith(root)) throw new IllegalArgumentException("Path escapes storage root");
        return normalized;
    }

    private void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target);
        }
    }
}
