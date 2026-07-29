package kr.wise.csr.normalization;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import kr.wise.csr.importfile.ImportBatch;
import kr.wise.csr.importfile.ImportCandidate;
import kr.wise.csr.importfile.WorkbookType;

class NormalizationServiceTest {
    NormalizationService service = new NormalizationService();

    @Test
    void collapsesIdenticalCandidatesAndRetainsBothSources() {
        ImportCandidate left = candidate(WorkbookType.WISEDQ_RESULT, "RULE-A", "Y,N", "result", 2);
        ImportCandidate right = candidate(WorkbookType.WDQ_CRITERIA, "RULE-A", "Y,N", "criteria", 3);

        NormalizationSummary summary = service.normalize(10, List.of(batch(WorkbookType.WISEDQ_RESULT, left), batch(WorkbookType.WDQ_CRITERIA, right)));

        assertThat(summary.rows()).hasSize(1);
        assertThat(summary.rows().getFirst().sources()).hasSize(2);
        assertThat(summary.conflicts()).isEmpty();
    }

    @Test
    void directCriteriaOverridesDifferingResultReportValue() {
        ImportCandidate left = candidate(WorkbookType.WISEDQ_RESULT, "RULE-A", "Y,N", "result", 2);
        ImportCandidate right = candidate(WorkbookType.CRITERIA_VERIFICATION_RULE, "RULE-A", "0,1", "criteria", 3);

        NormalizationSummary summary = service.normalize(10, List.of(batch(WorkbookType.WISEDQ_RESULT, left),
                batch(WorkbookType.CRITERIA_VERIFICATION_RULE, right)));

        assertThat(summary.rows()).hasSize(1);
        assertThat(summary.rows().getFirst().values().get("expression")).isEqualTo("0,1");
        assertThat(summary.rows().getFirst().sources()).hasSize(2);
        assertThat(summary.conflicts()).isEmpty();
    }

    @Test
    void caseDifferentLogicalKeysRemainSeparate() {
        ImportCandidate upper = candidate(WorkbookType.CRITERIA_VERIFICATION_RULE,
                "[기본]여부(Y,N)|Y,N", "Y,N", "criteria", 2);
        ImportCandidate lower = candidate(WorkbookType.CRITERIA_VERIFICATION_RULE,
                "[기본]여부(y,n)|y,n", "y,n", "criteria", 3);

        NormalizationSummary summary = service.normalize(10, List.of(
                batch(WorkbookType.CRITERIA_VERIFICATION_RULE, upper),
                batch(WorkbookType.CRITERIA_VERIFICATION_RULE, lower)));

        assertThat(summary.rows()).hasSize(2);
        assertThat(summary.conflicts()).isEmpty();
    }

    @Test
    void conflictCanUseSourceManualValueOrBeExcluded() {
        var summary = service.normalize(10, List.of(
                batch(WorkbookType.CRITERIA_VERIFICATION_RULE,
                        candidate(WorkbookType.CRITERIA_VERIFICATION_RULE, "RULE-A", "Y,N", "criteria-a", 2)),
                batch(WorkbookType.CRITERIA_VERIFICATION_RULE,
                        candidate(WorkbookType.CRITERIA_VERIFICATION_RULE, "RULE-A", "0,1", "criteria-b", 3))));
        DataConflict conflict = summary.conflicts().getFirst();

        NormalizationSummary manual = new ConflictService().resolve(summary,
                new ResolveConflictCommand(conflict.id(), ConflictResolution.MANUAL, "Y,N,X", "담당자 확인"));
        assertThat(manual.rows().getFirst().values().get("expression")).isEqualTo("Y,N,X");
        assertThat(manual.conflicts().getFirst().resolved()).isTrue();

        NormalizationSummary excluded = new ConflictService().resolve(summary,
                new ResolveConflictCommand(conflict.id(), ConflictResolution.EXCLUDE, null, "미사용"));
        assertThat(excluded.rows()).isEmpty();
    }

    private ImportCandidate candidate(WorkbookType type, String key, String expression, String sheet, int row) {
        return new ImportCandidate("VERIFICATION_RULE", key,
                Map.of("ruleName", key.toLowerCase(), "expression", expression), sheet, row);
    }
    private ImportBatch batch(WorkbookType type, ImportCandidate candidate) {
        return new ImportBatch(type, List.of(candidate), 0, 0, List.of());
    }
}
