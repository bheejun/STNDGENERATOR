package kr.wise.csr.validation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import kr.wise.csr.catalog.DefaultQualityIndicatorCatalog;
import kr.wise.csr.importfile.DefaultVerificationRuleCatalog;
import kr.wise.csr.normalization.NormalizedRow;
import kr.wise.csr.project.ProjectSnapshot;

@Component
public class ProjectValidator {
    private final DefaultVerificationRuleCatalog defaultRules;
    private final DefaultQualityIndicatorCatalog qualityIndicators;

    @Autowired
    public ProjectValidator(DefaultVerificationRuleCatalog defaultRules,
            DefaultQualityIndicatorCatalog qualityIndicators) {
        this.defaultRules = defaultRules;
        this.qualityIndicators = qualityIndicators;
    }

    public ProjectValidator() {
        this.defaultRules = null;
        this.qualityIndicators = null;
    }

    public ValidationReport validate(ProjectSnapshot project) {
        List<ValidationIssue> issues = new ArrayList<>();
        project.importErrors().forEach(error -> issues.add(error("IMPORT_ERROR", error, null)));
        project.conflicts().stream().filter(c -> !c.resolved()).forEach(c -> issues.add(error(
                "UNRESOLVED_CONFLICT", "미해결 입력자료 충돌", c.logicalKey())));
        if (project.excludedPt01Count() + project.excludedPt02Count() > 0) issues.add(warning("PT_ROWS_IGNORED",
                "PT01/PT02 제외: " + project.excludedPt01Count() + "/" + project.excludedPt02Count(), null));

        Set<String> ruleRefs = new HashSet<>();
        if (defaultRules != null)
            defaultRules.activeRules().values().forEach(rule -> add(ruleRefs, rule.ruleId()));
        for (NormalizedRow row : project.rows()) {
            if (row.dataType().equals("VERIFICATION_RULE") || row.dataType().equals("CODE_RULE")) {
                add(ruleRefs, row.values().get("wdqId")); add(ruleRefs, row.values().get("ruleName"));
            }
        }
        for (NormalizedRow row : project.rows()) validateRow(project, row, ruleRefs, issues);
        validateCodeData(project, issues);
        validateBusinessTargets(project, issues);
        return new ValidationReport(issues, project.contentHash());
    }

    private void validateBusinessTargets(ProjectSnapshot project, List<ValidationIssue> issues) {
        Set<String> knownColumns = new HashSet<>();
        Set<String> knownTables = new HashSet<>();
        Set<String> excludedSchemas = new HashSet<>();
        Set<String> excludedTables = new HashSet<>();
        List<NormalizedRow> exclusionPatterns = new ArrayList<>();

        for (NormalizedRow row : project.rows()) {
            if (row.dataType().equals("EXCLUSION_PATTERN")) {
                exclusionPatterns.add(row);
                continue;
            }
            if (row.dataType().equals("EXCLUSION")
                    && "SCH".equals(norm(row.values().get("exclusionType")))
                    && "Y".equals(norm(row.values().getOrDefault("expYn", "Y")))) {
                excludedSchemas.add(schemaKey(project, row));
                continue;
            }
            String tableKey = tableKey(project, row);
            if (tableKey.isBlank()) continue;
            if (row.dataType().equals("COLUMN_MAPPING")) {
                knownTables.add(tableKey);
                String columnKey = columnKey(project, row);
                if (!columnKey.isBlank()) {
                    knownColumns.add(columnKey);
                }
            } else if (row.dataType().equals("EXCLUSION")) {
                knownTables.add(tableKey);
                String type = norm(row.values().get("exclusionType"));
                if ("TBL".equals(type) && "Y".equals(norm(row.values().getOrDefault("expYn", "Y"))))
                    excludedTables.add(tableKey);
                if ("COL".equals(type)) {
                    String columnKey = columnKey(project, row);
                    if (!columnKey.isBlank()) knownColumns.add(columnKey);
                }
            }
        }

        for (NormalizedRow row : project.rows()) {
            if (!row.dataType().equals("BUSINESS_RULE")) continue;
            String table = tableKey(project, row);
            String column = columnKey(project, row);
            boolean reportedInResult = "Y".equalsIgnoreCase(
                    row.values().getOrDefault("reportedInResult", ""));
            String displayTable = first(row.values().get("schemaOriginal"), project.defaultSchema())
                    + "." + row.values().getOrDefault("tableOriginal",
                            row.values().getOrDefault("tableNormalized", ""));
            String displayColumn = displayTable + "." + row.values().getOrDefault("columnOriginal",
                    row.values().getOrDefault("columnNormalized", ""));
            if (column.isBlank()) {
                issues.add(warning("BUSINESS_TARGET_COLUMN_MISSING",
                        "업무규칙 대상 컬럼이 지정되지 않았습니다: " + displayTable,
                        row.logicalKey()));
                continue;
            }
            if (reportedInResult) continue;

            if (excludedSchemas.contains(schemaKey(project, row)))
                issues.add(warning("BUSINESS_TARGET_SCHEMA_EXCLUDED",
                        "업무규칙 대상 스키마가 진단 제외 대상이라 보고서에 포함되지 않습니다: "
                                + first(row.values().get("schemaOriginal"), project.defaultSchema()),
                        row.logicalKey()));
            if (excludedTables.contains(table))
                issues.add(warning("BUSINESS_TARGET_TABLE_EXCLUDED",
                        "업무규칙 대상 테이블이 진단 제외 대상이라 보고서에 포함되지 않습니다: " + displayTable,
                        row.logicalKey()));
            if (matchesExclusionPattern(project, row, exclusionPatterns))
                issues.add(warning("BUSINESS_TARGET_TABLE_PATTERN_EXCLUDED",
                        "업무규칙 대상 테이블이 테이블명 제외 규칙에 해당하여 보고서에 포함되지 않습니다: "
                                + displayTable, row.logicalKey()));
            if (!knownTables.contains(table)) {
                issues.add(warning("BUSINESS_TARGET_TABLE_NOT_DIAGNOSTIC",
                        "업무규칙 대상 테이블의 수집 메타정보가 없습니다: " + displayTable,
                        row.logicalKey()));
                continue;
            }
            if (!knownColumns.contains(column)) {
                issues.add(warning("BUSINESS_TARGET_COLUMN_METADATA_MISSING",
                        "업무규칙 대상 컬럼의 수집 메타정보가 없습니다: " + displayColumn,
                        row.logicalKey()));
            }
        }
    }

    private void validateCodeData(ProjectSnapshot project, List<ValidationIssue> issues) {
        Set<String> rulesWithValues = new HashSet<>();
        project.rows().stream().filter(row -> row.dataType().equals("CODE_VALUE"))
                .forEach(row -> add(rulesWithValues, row.values().get("ruleName")));
        Set<String> rulesWithSql = new HashSet<>();
        project.rows().stream()
                .filter(row -> row.dataType().equals("CODE_RULE")
                        && !row.values().getOrDefault("lookupSql", "").isBlank())
                .forEach(row -> add(rulesWithSql, row.values().get("ruleName")));
        Set<String> reported = new HashSet<>();
        for (NormalizedRow row : project.rows()) {
            if (!row.dataType().equals("COLUMN_MAPPING")
                    || !"CODE".equalsIgnoreCase(row.values().getOrDefault("ruleType", ""))) continue;
            String rule = row.values().getOrDefault("ruleName", "");
            if (rule.isBlank()) rule = row.values().getOrDefault("codeRuleId", "");
            String normalized = rule.trim();
            if (!normalized.isBlank() && !rulesWithValues.contains(normalized)
                    && !rulesWithSql.contains(normalized) && reported.add(normalized))
                issues.add(warning("MISSING_CODE_DATA",
                        "코드 도메인을 사용하는 규칙이지만 등록된 코드 데이터가 없습니다: " + rule, row.logicalKey()));
        }
    }

    private void validateRow(ProjectSnapshot project, NormalizedRow row, Set<String> ruleRefs, List<ValidationIssue> issues) {
        validateWdqDdl(row, issues);
        if (row.values().values().stream().anyMatch(v -> "PT01".equalsIgnoreCase(v) || "PT02".equalsIgnoreCase(v)))
            issues.add(error("PT_RULE_PERSISTED", "PT01/PT02는 최종 데이터에 저장할 수 없습니다", row.logicalKey()));
        String id = row.values().getOrDefault("wdqId", "");
        int limit = row.dataType().equals("VERIFICATION_RULE") ? 20 : 15;
        if (id.length() > limit) issues.add(error("ID_TOO_LONG", "WDQ ID 길이 초과: " + id, row.logicalKey()));
        String owner = row.values().get("ownerSystemId");
        if (owner != null && !owner.isBlank() && !owner.equals(Long.toString(project.systemId())))
            issues.add(error("CROSS_SYSTEM_ID", "다른 시스템 소유 WDQ ID", row.logicalKey()));

        if (row.dataType().equals("VERIFICATION_RULE")) {
            if ("ADDITIONAL_EXTRACTED".equals(row.values().get("ruleOrigin"))) {
                String ruleType = row.values().getOrDefault("ruleType", "").trim().toUpperCase(Locale.ROOT);
                if (!Set.of("YN", "RNG", "FRM", "DTM", "NO", "NN").contains(ruleType))
                    issues.add(error("MISSING_VERIFICATION_TYPE",
                            "결과보고서에서 추출한 검증룰의 진단유형을 YN, RNG, FRM, DTM, NO, NN 중에서 선택하세요",
                            row.logicalKey()));
            }
            if (blank(row, "expression"))
                issues.add(error("MISSING_RULE_EXPRESSION", "검증식이 없습니다", row.logicalKey()));
            if (blank(row, "qualityIndicator"))
                issues.add(error("MISSING_QUALITY_INDICATOR", "검증룰 품질지표명이 없습니다", row.logicalKey()));
            else if (!knownQualityIndicator(row.values().get("qualityIndicator")))
                issues.add(error("UNKNOWN_QUALITY_INDICATOR",
                        "내부 품질지표 카탈로그에 없는 이름입니다: " + row.values().get("qualityIndicator"),
                        row.logicalKey()));
            String expression = row.values().getOrDefault("expression", "");
            if (expression.matches(".*\\?[dDsSwW].*") && !expression.matches(".*\\\\[dDsSwW].*"))
                issues.add(warning("SUSPICIOUS_REGEX_ESCAPE",
                        "정규식 문자 클래스 앞의 역슬래시가 누락됐을 가능성이 있습니다: " + expression,
                        row.logicalKey()));
        }
        if (row.dataType().equals("CODE_RULE") && blank(row, "lookupSql")
                && !"Y".equalsIgnoreCase(row.values().getOrDefault("exclusiveYn", "")))
            issues.add(error("MISSING_CODE_SQL", "코드조회 SQL이 없습니다", row.logicalKey()));
        if (row.dataType().equals("BUSINESS_RULE")) {
            if (blank(row, "ruleSql")) issues.add(error("MISSING_BUSINESS_SQL", "업무규칙 SQL이 없습니다", row.logicalKey()));
            if (blank(row, "qualityIndicator"))
                issues.add(error("MISSING_QUALITY_INDICATOR", "업무규칙 품질지표명이 없습니다", row.logicalKey()));
            else if (!knownQualityIndicator(row.values().get("qualityIndicator")))
                issues.add(error("UNKNOWN_QUALITY_INDICATOR",
                        "내부 품질지표 카탈로그에 없는 이름입니다: " + row.values().get("qualityIndicator"),
                        row.logicalKey()));
            String sql = row.values().getOrDefault("ruleSql", "").toUpperCase(Locale.ROOT);
            if (project.defaultSchema() != null && !project.defaultSchema().isBlank()
                    && sql.contains(project.defaultSchema().toUpperCase(Locale.ROOT) + "."))
                issues.add(warning("HARDCODED_SCHEMA", "업무규칙 SQL에 물리 스키마명이 포함되어 있습니다", row.logicalKey()));
        }
        if (Set.of("EXCLUSION","COLUMN_MAPPING","BUSINESS_RULE").contains(row.dataType())
                && (blank(row,"tableNormalized") || (row.dataType().equals("COLUMN_MAPPING") && blank(row,"columnNormalized"))))
            issues.add(error("MISSING_PHYSICAL_NAME", "테이블 또는 컬럼 물리명이 없습니다", row.logicalKey()));
        if (row.dataType().equals("COLUMN_MAPPING")) {
            String ref = row.values().getOrDefault("verificationRuleId", row.values().getOrDefault("ruleName", ""));
            if (row.values().getOrDefault("ruleType", "VERIFICATION").equals("CODE")) {
                String ruleName = row.values().getOrDefault("ruleName", "");
                String codeRuleId = row.values().getOrDefault("codeRuleId", "");
                ref = ruleRefs.contains(ruleName) ? ruleName : codeRuleId;
            }
            if (ref.isBlank() || (!isDefaultRuleId(ref) && !ruleRefs.contains(ref)))
                issues.add(error("DANGLING_RULE_REFERENCE", "존재하지 않는 규칙 참조: " + ref, row.logicalKey()));
        }
    }

    private void validateWdqDdl(NormalizedRow row, List<ValidationIssue> issues) {
        switch (row.dataType()) {
            case "VERIFICATION_RULE" -> {
                max(row, issues, "ruleName", "WAA_VRFC_RULE.VRFC_NM", 50);
                max(row, issues, "expression", "WAA_VRFC_RULE.VRFC_RULE", 4000);
                max(row, issues, "description", "WAA_VRFC_RULE.VRFC_DESCN", 3000);
                max(row, issues, "excludedValues", "WAA_VRFC_RULE.ERR_EXP_DATA", 4000);
                max(row, issues, "excludedValueSeparator", "WAA_VRFC_RULE.ERR_EXP_DATA_SEP", 50);
            }
            case "CODE_RULE" -> {
                max(row, issues, "ruleName", "WAA_CD_RULE.CD_RULE_NM", 255);
                max(row, issues, "lookupSql", "WAA_CD_RULE.CD_SQL", 17000);
                max(row, issues, "description", "WAA_CD_RULE.OBJ_DESCN", 4000);
            }
            case "CODE_VALUE" -> {
                max(row, issues, "ruleName", "WAA_CD_LIST.CD_RULE_NM", 50);
                max(row, issues, "codeId", "WAA_CD_LIST.CD_ID", 50);
                max(row, issues, "codeName", "WAA_CD_LIST.CD_NM", 300);
            }
            case "EXCLUSION", "EXCLUSION_PATTERN" -> {
                max(row, issues, "schemaOriginal", "WAA_STND_EXP_OBJ.STND_SCH_PNM", 100);
                max(row, issues, "tableOriginal", "WAA_STND_EXP_OBJ.STND_TBL_PNM", 100);
                max(row, issues, "columnOriginal", "WAA_STND_EXP_OBJ.STND_COL_PNM", 100);
                max(row, issues, "reason", "WAA_STND_EXP_OBJ 제외사유", 4000);
                max(row, issues, "pattern", "WAA_STND_EXP_OBJ.TBL_EXP_STND_RULE", 100);
                max(row, issues, "relation", "WAA_STND_EXP_OBJ.INCLD_REL", 10);
            }
            case "COLUMN_MAPPING" -> {
                max(row, issues, "schemaOriginal", "WAA_STND_RULE_SET.STND_SCH_PNM", 100);
                max(row, issues, "tableOriginal", "WAA_STND_RULE_SET.STND_TBL_PNM", 100);
                max(row, issues, "columnOriginal", "WAA_STND_RULE_SET.STND_COL_PNM", 100);
                max(row, issues, "columnLogicalName", "WAA_STND_RULE_SET.STND_COL_LNM", 500);
                max(row, issues, "comment", "WAA_STND_RULE_SET.COL_RMK", 4000);
            }
            case "BUSINESS_RULE" -> {
                max(row, issues, "schemaOriginal", "WAA_STND_TBL_PRF.STND_SCH_PNM", 100);
                max(row, issues, "tableOriginal", "WAA_STND_TBL_PRF.STND_TBL_PNM", 100);
                max(row, issues, "columnOriginal", "WAA_STND_TBL_PRF.STND_COL_PNM", 100);
                max(row, issues, "ruleName", "WAA_STND_TBL_PRF.BR_NM", 3000);
                max(row, issues, "description", "WAA_STND_TBL_PRF.OBJ_DESCN", 3000);
                max(row, issues, "basis", "WAA_STND_TBL_PRF.BASIS_RGLTN", 3000);
            }
            default -> { }
        }
    }

    private void max(NormalizedRow row, List<ValidationIssue> issues, String key, String column, int limit) {
        String value = row.values().get(key);
        if (value == null) return;
        int length = value.codePointCount(0, value.length());
        if (length > limit)
            issues.add(error("WDQ_COLUMN_TOO_LONG",
                    column + " 최대 " + limit + "자, 현재 " + length + "자입니다. '" + key + "' 값을 줄여주세요.",
                    row.logicalKey()));
    }
    private boolean isDefaultRuleId(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        return normalized.startsWith("STAT_") || normalized.startsWith("VRF1_");
    }
    private boolean blank(NormalizedRow row,String key){return row.values().getOrDefault(key,"").isBlank();}
    private boolean knownQualityIndicator(String name) {
        return qualityIndicators == null
                ? DefaultQualityIndicatorCatalog.isBuiltIn(name)
                : qualityIndicators.findId(name).isPresent();
    }
    private void add(Set<String>s,String value){if(value!=null&&!value.isBlank())s.add(value.trim());}
    private String tableKey(ProjectSnapshot project, NormalizedRow row) {
        String table = norm(first(row.values().get("tableNormalized"), row.values().get("tableOriginal")));
        if (table.isBlank()) return "";
        String schema = norm(first(row.values().get("schemaNormalized"), row.values().get("schemaOriginal"),
                project.defaultSchema()));
        return schema + "|" + table;
    }
    private String columnKey(ProjectSnapshot project, NormalizedRow row) {
        String table = tableKey(project, row);
        String column = norm(first(row.values().get("columnNormalized"), row.values().get("columnOriginal")));
        return table.isBlank() || column.isBlank() ? "" : table + "|" + column;
    }
    private String schemaKey(ProjectSnapshot project, NormalizedRow row) {
        return norm(first(row.values().get("schemaNormalized"), row.values().get("schemaOriginal"),
                project.defaultSchema()));
    }
    private boolean matchesExclusionPattern(
            ProjectSnapshot project, NormalizedRow business, List<NormalizedRow> patterns) {
        String schema = schemaKey(project, business);
        String table = norm(first(business.values().get("tableNormalized"),
                business.values().get("tableOriginal")));
        for (NormalizedRow patternRow : patterns) {
            if (!schema.equals(schemaKey(project, patternRow))) continue;
            String pattern = norm(patternRow.values().get("pattern"));
            if (pattern.isBlank()) continue;
            String relation = norm(patternRow.values().get("relation"));
            boolean matches = switch (relation) {
                case "F" -> table.startsWith(pattern);
                case "B" -> table.endsWith(pattern);
                case "E" -> table.equals(pattern);
                default -> table.contains(pattern);
            };
            if (matches) return true;
        }
        return false;
    }
    private String first(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return "";
    }
    private String norm(String value) { return value == null ? "" : value.trim().toUpperCase(Locale.ROOT); }
    private ValidationIssue error(String code,String message,String key){return new ValidationIssue(Severity.ERROR,code,message,key);}
    private ValidationIssue warning(String code,String message,String key){return new ValidationIssue(Severity.WARNING,code,message,key);}
}
