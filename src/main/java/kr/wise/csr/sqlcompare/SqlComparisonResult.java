package kr.wise.csr.sqlcompare;

import java.time.OffsetDateTime;
import java.util.List;

public record SqlComparisonResult(
        String baselineName,
        OffsetDateTime uploadedAt,
        int baselineStatementCount,
        int currentStatementCount,
        int unchangedCount,
        int baselineOnlyCount,
        int currentOnlyCount,
        List<TableDifference> tableDifferences) {

    public record TableDifference(
            String tableName,
            int baselineCount,
            int currentCount,
            int unchangedCount,
            int baselineOnlyCount,
            int currentOnlyCount,
            List<String> baselineOnlySamples,
            List<String> currentOnlySamples) {
    }
}
