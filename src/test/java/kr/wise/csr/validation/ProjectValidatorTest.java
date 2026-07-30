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

    @Test
    void warnsWhenCodeDomainMappingHasNoCodeData() {
        ProjectSnapshot snapshot = new ProjectSnapshot(1, 1, 2026, "202607", "APP", ProjectStatus.NEEDS_REVIEW,
                List.of(
                        row("CODE_RULE", "C", Map.of("wdqId", "STNDCD_00000001", "ruleName", "지역코드",
                                "codeType", "공통코드", "exclusiveYn", "Y")),
                        row("COLUMN_MAPPING", "M", Map.of("tableNormalized", "TB", "columnNormalized", "REGION_CD",
                                "ruleType", "CODE", "ruleName", "지역코드"))),
                List.of(), 0, 0, List.of(), null, null, null);

        assertThat(validator.validate(snapshot).issues()).extracting(ValidationIssue::code)
                .contains("MISSING_CODE_DATA");
    }

    @Test
    void rejectsVerificationRuleNameLongerThanWdqDdlLimit() {
        ProjectSnapshot snapshot = new ProjectSnapshot(1, 1, 2026, "202607", "APP",
                ProjectStatus.NEEDS_REVIEW,
                List.of(row("VERIFICATION_RULE", "too-long",
                        Map.of("wdqId", "VRFC_70000000001", "ruleName", "가".repeat(51), "expression", "1=1"))),
                List.of(), 0, 0, List.of(), null, null, null);

        assertThat(validator.validate(snapshot).issues())
                .anyMatch(issue -> issue.code().equals("WDQ_COLUMN_TOO_LONG")
                        && issue.message().contains("WAA_VRFC_RULE.VRFC_NM")
                        && issue.message().contains("현재 51자"));
    }

    @Test
    void requiresManualTypeForVerificationRuleExtractedFromResultReport() {
        ProjectSnapshot snapshot = new ProjectSnapshot(1, 1, 2026, "202607", "APP",
                ProjectStatus.NEEDS_REVIEW,
                List.of(row("VERIFICATION_RULE", "manual-type",
                        Map.of("wdqId", "VRFC_70000000001", "ruleName", "날짜 형식",
                                "expression", "YYYYMMDD", "ruleOrigin", "ADDITIONAL_EXTRACTED",
                                "ruleType", ""))),
                List.of(), 0, 0, List.of(), null, null, null);

        assertThat(validator.validate(snapshot).issues()).anyMatch(issue ->
                issue.code().equals("MISSING_VERIFICATION_TYPE")
                        && issue.severity() == Severity.ERROR);
    }

    @Test
    void acceptsAllWdqVerificationTypesForResultReportRules() {
        for (String ruleType : List.of("YN", "RNG", "FRM", "DTM", "NO", "NN")) {
            ProjectSnapshot snapshot = new ProjectSnapshot(1, 1, 2026, "202607", "APP",
                    ProjectStatus.NEEDS_REVIEW,
                    List.of(row("VERIFICATION_RULE", "type-" + ruleType,
                            Map.of("wdqId", "VRFC_70000000001", "ruleName", "검증룰",
                                    "expression", "X", "ruleOrigin", "ADDITIONAL_EXTRACTED",
                                    "ruleType", ruleType))),
                    List.of(), 0, 0, List.of(), null, null, null);

            assertThat(validator.validate(snapshot).issues())
                    .noneMatch(issue -> issue.code().equals("MISSING_VERIFICATION_TYPE"));
        }
    }

    @Test
    void rejectsQualityIndicatorOutsideInternalCatalog() {
        ProjectSnapshot snapshot = new ProjectSnapshot(1, 1, 2026, "202607", "APP",
                ProjectStatus.NEEDS_REVIEW,
                List.of(row("VERIFICATION_RULE", "unknown-dqi",
                        Map.of("wdqId", "VRFC_70000000001", "ruleName", "검증룰",
                                "expression", "X", "qualityIndicator", "존재하지 않는 지표"))),
                List.of(), 0, 0, List.of(), null, null, null);

        assertThat(validator.validate(snapshot).issues()).anyMatch(issue ->
                issue.code().equals("UNKNOWN_QUALITY_INDICATOR")
                        && issue.severity() == Severity.ERROR);
    }

    @Test
    void validatesBusinessTargetsAgainstMappedColumnsAndTableExclusions() {
        ProjectSnapshot snapshot = new ProjectSnapshot(1, 1, 2026, "202607", "APP",
                ProjectStatus.NEEDS_REVIEW,
                List.of(
                        row("COLUMN_MAPPING", "mapped", Map.of(
                                "schemaNormalized", "APP", "tableNormalized", "MAPPED_TBL",
                                "columnNormalized", "NORMAL_COL", "ruleType", "VERIFICATION",
                                "verificationRuleId", "STAT_00000000001")),
                        row("EXCLUSION", "excluded", Map.of(
                                "schemaNormalized", "APP", "tableNormalized", "MAPPED_TBL",
                                "columnNormalized", "EXCLUDED_COL", "exclusionType", "COL", "expYn", "Y")),
                        row("EXCLUSION", "excluded-table", Map.of(
                                "schemaNormalized", "APP", "tableNormalized", "BLOCKED_TBL",
                                "exclusionType", "TBL", "expYn", "Y")),
                        row("EXCLUSION_PATTERN", "pattern", Map.of(
                                "schemaNormalized", "APP", "relation", "F", "pattern", "TMP_")),
                        business("missing-table", "ONLY_BUSINESS_TBL", "TARGET_COL"),
                        business("excluded-column", "MAPPED_TBL", "EXCLUDED_COL"),
                        business("excluded-table", "BLOCKED_TBL", "TARGET_COL"),
                        business("pattern-table", "TMP_HISTORY", "TARGET_COL"),
                        business("missing-column", "MAPPED_TBL", "")),
                List.of(), 0, 0, List.of(), null, null, null);

        assertThat(validator.validate(snapshot).issues()).extracting(ValidationIssue::code)
                .contains("BUSINESS_TARGET_TABLE_NOT_DIAGNOSTIC",
                        "BUSINESS_TARGET_COLUMN_METADATA_MISSING",
                        "BUSINESS_TARGET_TABLE_EXCLUDED",
                        "BUSINESS_TARGET_TABLE_PATTERN_EXCLUDED",
                        "BUSINESS_TARGET_COLUMN_MISSING");
        assertThat(validator.validate(snapshot).issues()).extracting(ValidationIssue::code)
                .doesNotContain("BUSINESS_TARGET_COLUMN_EXCLUDED");
    }

    @Test
    void trustsBusinessRuleAlreadyIncludedInResultReport() {
        ProjectSnapshot snapshot = new ProjectSnapshot(1, 1, 2026, "202607", "APP",
                ProjectStatus.NEEDS_REVIEW,
                List.of(row("BUSINESS_RULE", "reported", Map.of(
                        "wdqId", "STNDPRF_0000001", "ruleName", "reported", "ruleSql", "select 1",
                        "qualityIndicator", "업무규칙", "schemaNormalized", "APP",
                        "tableNormalized", "RESULT_TBL", "columnNormalized", "RESULT_COL",
                        "reportedInResult", "Y"))),
                List.of(), 0, 0, List.of(), null, null, null);

        assertThat(validator.validate(snapshot).issues()).noneMatch(issue ->
                issue.code().startsWith("BUSINESS_TARGET_"));
    }

    private NormalizedRow business(String key, String table, String column) {
        return row("BUSINESS_RULE", key, Map.of(
                "wdqId", "STNDPRF_0000001", "ruleName", key, "ruleSql", "select 1",
                "qualityIndicator", "업무규칙", "schemaNormalized", "APP",
                "tableNormalized", table, "columnNormalized", column));
    }

    private NormalizedRow row(String type, String key, Map<String,String> values) {
        return new NormalizedRow(type, key, values, List.of(), "fingerprint");
    }
}
