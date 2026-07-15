package kr.wise.csr.normalization;

import java.util.List;

public record NormalizationSummary(long projectId, List<NormalizedRow> rows,
        List<DataConflict> conflicts, int excludedPt01Count, int excludedPt02Count,
        List<String> importErrors) {
    public boolean needsReview() { return !importErrors.isEmpty() || conflicts.stream().anyMatch(c -> !c.resolved()); }
}
