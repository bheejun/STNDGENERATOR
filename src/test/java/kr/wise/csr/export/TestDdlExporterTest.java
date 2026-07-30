package kr.wise.csr.export;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import kr.wise.csr.normalization.NormalizedRow;
import kr.wise.csr.project.ProjectSnapshot;
import kr.wise.csr.project.ProjectStatus;

class TestDdlExporterTest {
    @Test
    void createsOracleDdlAndAddsDummyColumnForTableOnlyExclusion() {
        ProjectSnapshot snapshot = snapshot("ORA", List.of(
                row("COLUMN_MAPPING", "M1", Map.of("schemaOriginal", "APP", "tableOriginal", "MEMBER",
                        "columnOriginal", "MEMBER_ID", "dataType", "NUMBER")),
                row("EXCLUSION", "E1", Map.of("schemaOriginal", "APP", "tableOriginal", "LOG_BACKUP",
                        "columnOriginal", "", "exclusionType", "TBL"))));

        String ddl = text(new TestDdlExporter().export(snapshot));

        assertThat(ddl)
                .contains("CREATE TABLE \"APP\".\"MEMBER\"")
                .contains("\"MEMBER_ID\" DECIMAL(38,10)")
                .contains("CREATE TABLE \"APP\".\"LOG_BACKUP\"")
                .contains("\"DUMMY_COL\" VARCHAR2(255)");
    }

    @Test
    void usesMariaDbIdentifierAndBinaryTypes() {
        ProjectSnapshot snapshot = snapshot("MRA", List.of(
                row("COLUMN_MAPPING", "M1", Map.of("schemaOriginal", "APP", "tableOriginal", "FILES",
                        "columnOriginal", "CONTENT", "dataType", "BLOB"))));

        assertThat(text(new TestDdlExporter().export(snapshot)))
                .contains("CREATE TABLE `APP`.`FILES`")
                .contains("`CONTENT` BLOB");
    }

    @Test
    void usesDomainColumnInventoryForExcludedTable() {
        ProjectSnapshot snapshot = snapshot("ORA", List.of(
                row("EXCLUSION", "E1", Map.of("schemaOriginal", "APP", "tableOriginal", "LOG_BACKUP",
                        "columnOriginal", "", "exclusionType", "TBL", "expYn", "Y")),
                row("COLUMN_INVENTORY", "I1", Map.of("schemaOriginal", "APP", "tableOriginal", "LOG_BACKUP",
                        "columnOriginal", "LOG_ID", "dataType", "NUMBER")),
                row("COLUMN_INVENTORY", "I2", Map.of("schemaOriginal", "APP", "tableOriginal", "LOG_BACKUP",
                        "columnOriginal", "LOG_TEXT", "dataType", "CLOB"))));

        assertThat(text(new TestDdlExporter().export(snapshot)))
                .contains("\"LOG_ID\" DECIMAL(38,10)")
                .contains("\"LOG_TEXT\" CLOB")
                .doesNotContain("\"DUMMY_COL\" VARCHAR2(255)");
    }

    private ProjectSnapshot snapshot(String dbmsType, List<NormalizedRow> rows) {
        return new ProjectSnapshot(1, 1, 2026, "202607", "APP", "테스트시스템", "TESTDB", dbmsType, "STNDDB_1",
                ProjectStatus.VALIDATED, rows, List.of(), 0, 0, List.of(), null, null, null);
    }

    private NormalizedRow row(String type, String key, Map<String, String> values) {
        return new NormalizedRow(type, key, values, List.of(), key);
    }

    private String text(GeneratedFile file) {
        return new String(file.content(), StandardCharsets.UTF_8);
    }
}
