package kr.wise.csr.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalFileStorageTest {
    @TempDir Path root;

    @Test
    void storesSourceUnderItsProjectWithChecksumAndOriginalName() throws Exception {
        LocalFileStorage storage = new LocalFileStorage(root);
        byte[] content = "synthetic workbook".getBytes(StandardCharsets.UTF_8);

        StoredFile stored = storage.store(42, ArtifactKind.SOURCE, "진단결과.xlsx",
                new ByteArrayInputStream(content));

        assertThat(stored.originalName()).isEqualTo("진단결과.xlsx");
        assertThat(stored.sha256()).hasSize(64);
        assertThat(stored.byteSize()).isEqualTo(content.length);
        assertThat(Path.of(stored.storedPath())).startsWith(root.resolve("42"));
        assertThat(storage.load(stored.storedPath()).getInputStream().readAllBytes()).isEqualTo(content);
    }

    @Test
    void duplicateUploadReusesTheStoredBytes() throws Exception {
        LocalFileStorage storage = new LocalFileStorage(root);
        byte[] content = "same".getBytes(StandardCharsets.UTF_8);

        StoredFile first = storage.store(1, ArtifactKind.SOURCE, "first.xlsx", new ByteArrayInputStream(content));
        StoredFile second = storage.store(1, ArtifactKind.SOURCE, "second.xlsx", new ByteArrayInputStream(content));

        assertThat(second.storedPath()).isEqualTo(first.storedPath());
        assertThat(Files.walk(root).filter(Files::isRegularFile)).hasSize(1);
    }

    @Test
    void rejectsPathTraversalInNamesAndLoads() {
        LocalFileStorage storage = new LocalFileStorage(root);
        assertThatThrownBy(() -> storage.store(1, ArtifactKind.SOURCE, "../secret.xlsx",
                new ByteArrayInputStream(new byte[] {1}))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.load(root.resolve("..").resolve("secret.xlsx").toString()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
