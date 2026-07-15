package kr.wise.csr.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import kr.wise.csr.normalization.DataConflict;
import kr.wise.csr.normalization.NormalizedRow;
import kr.wise.csr.project.ProjectSnapshot;
import kr.wise.csr.project.ProjectStatus;

class ProjectValidatorTest {
    ProjectValidator validator = new ProjectValidator();

    @Test
    void blocksMissingValuesPtRowsLongIdsDanglingMappingsAndConflicts() {
        List<NormalizedRow> rows = List.of(
                row("VERIFICATION_RULE", "R1", Map.of("wdqId", "STNDRULE_123456789012", "ruleName", "룰", "expression", "")),
                row("BUSINESS_RULE", "B1", Map.of("wdqId", "STNDPRF_0000001", "ruleKind", "PT01", "ruleName", "참조", "ruleSql", "select 1")),
                row("COLUMN_MAPPING", "M1", Map.of("dbmsNormalized", "DB", "schemaNormalized", "APP", "tableNormalized", "TB", "columnNormalized", "COL", "ruleType", "VERIFICATION", "verificationRuleId", "UNKNOWN")));
        DataConflict conflict = new DataConflict(1, "VERIFICATION_RULE", "R1", Map.of(), null, Map.of(), null, null, null);
        ProjectSnapshot snapshot = new ProjectSnapshot(1, 1, 2026, "202607", "APP", ProjectStatus.NEEDS_REVIEW,
                rows, List.of(conflict), 1, 0, List.of(), null, null, null);

        ValidationReport report = validator.validate(snapshot);

        assertThat(report.hasErrors()).isTrue();
        assertThat(report.issues()).extracting(ValidationIssue::code)
                .contains("UNRESOLVED_CONFLICT", "PT_RULE_PERSISTED", "ID_TOO_LONG", "MISSING_RULE_EXPRESSION", "DANGLING_RULE_REFERENCE");
    }

    @Test
    void hardCodedSchemaAndIgnoredPtCountsAreWarnings() {
        ProjectSnapshot snapshot = new ProjectSnapshot(1, 1, 2026, "202607", "APP", ProjectStatus.NEEDS_REVIEW,
                List.of(row("BUSINESS_RULE", "B", Map.of("wdqId", "STNDPRF_0000001", "ruleKind", "BUSINESS", "ruleName", "업무", "tableNormalized", "TB", "ruleSql", "select * from APP.TB"))),
                List.of(), 2, 3, List.of(), null, null, null);
        assertThat(validator.validate(snapshot).issues()).filteredOn(i -> i.severity() == Severity.WARNING)
                .extracting(ValidationIssue::code).contains("HARDCODED_SCHEMA", "PT_ROWS_IGNORED");
    }

    private NormalizedRow row(String type, String key, Map<String,String> values) {
        return new NormalizedRow(type, key, values, List.of(), "fingerprint");
    }
}
