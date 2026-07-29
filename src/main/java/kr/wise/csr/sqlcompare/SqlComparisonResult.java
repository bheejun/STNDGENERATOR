package kr.wise.csr.sqlcompare;

import java.time.OffsetDateTime;
import java.util.List;

public record SqlComparisonResult(
        String baselineName,
        OffsetDateTime uploadedAt,
        int baselineRowCount,
        int currentRowCount,
        int unchangedCount,
        int changedCount,
        int baselineOnlyCount,
        int currentOnlyCount,
        List<TableDifference> tableDifferences) {

    public record TableDifference(
            String tableName,
            int baselineCount,
            int currentCount,
            int unchangedCount,
            int changedCount,
            int baselineOnlyCount,
            int currentOnlyCount,
            List<RowChange> changedRows,
            List<RowSample> baselineOnlySamples,
            List<RowSample> currentOnlySamples) {
    }

    public record RowChange(String logicalKey, List<ValueDifference> differences) {
    }

    public record ValueDifference(String column, String baselineValue, String currentValue) {
    }

    public record RowSample(String logicalKey, String sql) {
    }
}
