package kr.wise.csr.sqlcompare;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.Test;

class SqlStatementComparatorTest {
    private final SqlStatementComparator comparator = new SqlStatementComparator();

    @Test
    void comparesInsertStatementsIgnoringFormattingAndComments() {
        String baseline = """
                -- old comment
                insert into dqlite.waa_vrfc_rule (ID, NM) values ('A', 'x;y');
                insert into dqlite.waa_exp_tbl (ID) values ('OLD');
                """;
        String current = """
                INSERT  INTO DQLITE.WAA_VRFC_RULE
                  (ID, NM) VALUES ('A', 'x;y');
                insert into dqlite.waa_exp_tbl (ID) values ('NEW');
                """;

        SqlComparisonResult result = comparator.compare("2025.sql", OffsetDateTime.now(), baseline, current);

        assertThat(result.unchangedCount()).isOne();
        assertThat(result.baselineOnlyCount()).isOne();
        assertThat(result.currentOnlyCount()).isOne();
        assertThat(result.tableDifferences()).singleElement()
                .extracting(SqlComparisonResult.TableDifference::tableName)
                .isEqualTo("DQLITE.WAA_EXP_TBL");
    }
}
