package kr.wise.csr.project;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class InitialSchemaTest {

    private static final List<String> REQUIRED_TABLES = List.of(
            "standard_system", "build_project", "source_file", "id_registry",
            "exclusion_rule", "verification_rule", "code_rule", "column_rule_mapping",
            "business_rule", "data_conflict", "generated_artifact");

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void flywayCreatesAllNormalizedTables() {
        List<String> actual = jdbcTemplate.queryForList("""
                select table_name
                from information_schema.tables
                where table_schema = 'public'
                """, String.class);

        assertThat(actual).containsAll(REQUIRED_TABLES);
    }

    @Test
    void activeProjectAndForeignKeyIndexesExist() {
        List<String> indexes = jdbcTemplate.queryForList("""
                select indexname from pg_indexes where schemaname = 'public'
                """, String.class);

        assertThat(indexes).contains(
                "uq_build_project_active_system_year",
                "idx_build_project_system_id",
                "idx_source_file_project_id",
                "idx_id_registry_system_id",
                "idx_id_registry_first_project_id");
    }
}
