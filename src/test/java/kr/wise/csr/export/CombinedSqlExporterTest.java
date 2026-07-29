package kr.wise.csr.export;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class CombinedSqlExporterTest {
    @Test
    void combinesDatasetSqlInInstallerExecutionOrderWithoutBom() {
        GeneratedFile file = new CombinedSqlExporter(new DatasetSqlExporter())
                .export(DatasetSqlExporterTest.approvedSnapshot());
        String sql = new String(file.content(), StandardCharsets.UTF_8);

        assertThat(file.fileName()).endsWith(".sql");
        assertThat(file.content().length < 3
                || file.content()[0] != (byte) 0xEF
                || file.content()[1] != (byte) 0xBB
                || file.content()[2] != (byte) 0xBF).isTrue();
        assertThat(sql.indexOf("01-db-connection.sql")).isLessThan(sql.indexOf("02-exclusion.sql"));
        assertThat(sql.indexOf("02-exclusion.sql")).isLessThan(sql.indexOf("03-verification-rule.sql"));
        assertThat(sql.indexOf("03-verification-rule.sql")).isLessThan(sql.indexOf("04-code-rule.sql"));
        assertThat(sql.indexOf("04-code-rule.sql")).isLessThan(sql.indexOf("05-code-list.sql"));
        assertThat(sql.indexOf("05-code-list.sql")).isLessThan(sql.indexOf("06-column-mapping.sql"));
        assertThat(sql.indexOf("06-column-mapping.sql")).isLessThan(sql.indexOf("07-business-rule.sql"));
    }
}
