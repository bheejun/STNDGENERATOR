package kr.wise.csr.sqlcompare;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.Test;

class SqlStatementComparatorTest {
    private final SqlStatementComparator comparator = new SqlStatementComparator();

    @Test
    void comparesBulkAndSingleRowInsertsByColumnAndLogicalKey() {
        String baseline = """
                INSERT INTO waa_vrfc_rule (VRFC_ID, VRFC_NM, VRFC_RULE, WRIT_DTM) VALUES
                  ('OLD1', '[2025표준시스템DB 지킴-e] 날짜', 'A;B', NOW()),
                  ('OLD2', '[2025표준시스템DB 지킴-e] 금액', '[0-9]+', NOW());
                """;
        String current = """
                -- column order and schema prefix are intentionally different
                INSERT INTO dqlite.WAA_VRFC_RULE (VRFC_RULE, VRFC_NM, VRFC_ID, WRIT_DTM)
                  VALUES ('A;B', '[2026표준시스템DB 지킴-e] 날짜', 'NEW1', CURRENT_TIMESTAMP);
                INSERT INTO dqlite.WAA_VRFC_RULE (VRFC_RULE, VRFC_NM, VRFC_ID, WRIT_DTM)
                  VALUES ('[0-9]{1,10}', '[2026표준시스템DB 지킴-e] 금액', 'NEW2', CURRENT_TIMESTAMP);
                """;

        SqlComparisonResult result = comparator.compare("2025.sql", OffsetDateTime.now(), baseline, current);

        assertThat(result.baselineRowCount()).isEqualTo(2);
        assertThat(result.currentRowCount()).isEqualTo(2);
        assertThat(result.unchangedCount()).isOne();
        assertThat(result.changedCount()).isOne();
        assertThat(result.baselineOnlyCount()).isZero();
        assertThat(result.currentOnlyCount()).isZero();
        assertThat(result.tableDifferences()).singleElement().satisfies(table -> {
            assertThat(table.tableName()).isEqualTo("WAA_VRFC_RULE");
            assertThat(table.changedRows()).hasSize(1);
            assertThat(table.changedRows().getFirst().differences())
                    .extracting(SqlComparisonResult.ValueDifference::column)
                    .containsExactly("VRFC_RULE");
        });
    }

    @Test
    void reportsRowsThatOnlyExistOnOneSide() {
        String baseline = "insert into waa_cd_list (CD_RULE_NM, CD_ID, CD_NM) values ('R', '1', 'OLD');";
        String current = "insert into dqlite.waa_cd_list (CD_RULE_NM, CD_ID, CD_NM) values ('R', '2', 'NEW');";

        SqlComparisonResult result = comparator.compare("2025.sql", OffsetDateTime.now(), baseline, current);

        assertThat(result.baselineOnlyCount()).isOne();
        assertThat(result.currentOnlyCount()).isOne();
        assertThat(result.changedCount()).isZero();
    }

    @Test
    void parsesInsertSelectAsOneRow() {
        String sql = """
                INSERT INTO dqlite.WAA_DB_CONN_TRG (DB_CONN_TRG_ID, DB_CONN_TRG_PNM, INFO_SYS_NM)
                SELECT DISTINCT 'ID', '지킴eDB', '입력해주세요.'
                  FROM dual WHERE NOT EXISTS (SELECT 1 FROM WAA_DB_CONN_TRG);
                """;

        assertThat(comparator.parseRows(sql)).singleElement().satisfies(row -> {
            assertThat(row.table()).isEqualTo("WAA_DB_CONN_TRG");
            assertThat(row.values()).containsEntry("DB_CONN_TRG_PNM", "'지킴eDB'");
        });
    }
}
