package kr.wise.csr.project;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import kr.wise.csr.storage.ArtifactKind;

@SpringBootTest(properties = "csr.storage.root=target/test-storage")
class SourceFileServiceTest {
    @Autowired SourceFileService service;
    @Autowired JdbcTemplate jdbc;
    long projectId;

    @BeforeEach
    void setUp() {
        jdbc.update("delete from normalized_item");
        jdbc.update("delete from data_conflict");
        jdbc.update("delete from source_file");
        jdbc.update("delete from id_registry");
        jdbc.update("delete from build_project");
        jdbc.update("delete from standard_system");
        long systemId = jdbc.queryForObject("""
                insert into standard_system(system_code, system_name, dbms_type, dbms_physical_name,
                  default_schema_original, default_schema_normalized)
                values ('UPLOAD-TEST', '업로드 테스트', 'MARIADB', 'DB', 'PUBLIC', 'PUBLIC') returning id
                """, Long.class);
        projectId = jdbc.queryForObject("""
                insert into build_project(system_id, target_year, deployment_year_month, status)
                values (?, 2026, '202607', 'DRAFT') returning id
                """, Long.class, systemId);
    }

    @Test
    void newUploadStartsAsUndetected() {
        SourceFileRecord record = service.store(projectId, ArtifactKind.SOURCE, "criteria.xlsx",
                new ByteArrayInputStream("file".getBytes(StandardCharsets.UTF_8)));

        assertThat(record.parseStatus()).isEqualTo("UNDETECTED");
        assertThat(jdbc.queryForObject("select parse_status from source_file where id=?", String.class, record.id()))
                .isEqualTo("UNDETECTED");
    }

    @Test
    void duplicateContentKeepsTheFirstSourceMetadata() {
        byte[] content = "same-file".getBytes(StandardCharsets.UTF_8);
        SourceFileRecord first = service.store(projectId, ArtifactKind.SOURCE, "first.xlsx",
                new ByteArrayInputStream(content));
        SourceFileRecord duplicate = service.store(projectId, ArtifactKind.SOURCE, "renamed.xlsx",
                new ByteArrayInputStream(content));

        assertThat(duplicate.id()).isEqualTo(first.id());
        assertThat(duplicate.originalName()).isEqualTo("first.xlsx");
        assertThat(jdbc.queryForObject("select count(*) from source_file where project_id=?", Integer.class, projectId))
                .isEqualTo(1);
    }
}
