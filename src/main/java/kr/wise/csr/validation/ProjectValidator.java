package kr.wise.csr.validation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import kr.wise.csr.normalization.NormalizedRow;
import kr.wise.csr.project.ProjectSnapshot;

public class ProjectValidator {
    public ValidationReport validate(ProjectSnapshot project) {
        List<ValidationIssue> issues = new ArrayList<>();
        project.importErrors().forEach(error -> issues.add(error("IMPORT_ERROR", error, null)));
        project.conflicts().stream().filter(c -> !c.resolved()).forEach(c -> issues.add(error(
                "UNRESOLVED_CONFLICT", "미해결 입력자료 충돌", c.logicalKey())));
        if (project.excludedPt01Count() + project.excludedPt02Count() > 0) issues.add(warning("PT_ROWS_IGNORED",
                "PT01/PT02 제외: " + project.excludedPt01Count() + "/" + project.excludedPt02Count(), null));

        Set<String> ruleRefs = new HashSet<>();
        for (NormalizedRow row : project.rows()) {
            if (row.dataType().equals("VERIFICATION_RULE") || row.dataType().equals("CODE_RULE")) {
                add(ruleRefs, row.values().get("wdqId")); add(ruleRefs, row.values().get("ruleName"));
            }
        }
        for (NormalizedRow row : project.rows()) validateRow(project, row, ruleRefs, issues);
        return new ValidationReport(issues, project.contentHash());
    }

    private void validateRow(ProjectSnapshot project, NormalizedRow row, Set<String> ruleRefs, List<ValidationIssue> issues) {
        if (row.values().values().stream().anyMatch(v -> "PT01".equalsIgnoreCase(v) || "PT02".equalsIgnoreCase(v)))
            issues.add(error("PT_RULE_PERSISTED", "PT01/PT02는 최종 데이터에 저장할 수 없습니다", row.logicalKey()));
        String id = row.values().getOrDefault("wdqId", "");
        int limit = row.dataType().equals("VERIFICATION_RULE") ? 20 : 15;
        if (id.length() > limit) issues.add(error("ID_TOO_LONG", "WDQ ID 길이 초과: " + id, row.logicalKey()));
        String owner = row.values().get("ownerSystemId");
        if (owner != null && !owner.isBlank() && !owner.equals(Long.toString(project.systemId())))
            issues.add(error("CROSS_SYSTEM_ID", "다른 시스템 소유 WDQ ID", row.logicalKey()));

        if (row.dataType().equals("VERIFICATION_RULE") && blank(row, "expression"))
            issues.add(error("MISSING_RULE_EXPRESSION", "검증식이 없습니다", row.logicalKey()));
        if (row.dataType().equals("CODE_RULE") && blank(row, "lookupSql"))
            issues.add(error("MISSING_CODE_SQL", "코드조회 SQL이 없습니다", row.logicalKey()));
        if (row.dataType().equals("BUSINESS_RULE")) {
            if (blank(row, "ruleSql")) issues.add(error("MISSING_BUSINESS_SQL", "업무규칙 SQL이 없습니다", row.logicalKey()));
            String sql = row.values().getOrDefault("ruleSql", "").toUpperCase(Locale.ROOT);
            if (project.defaultSchema() != null && !project.defaultSchema().isBlank()
                    && sql.contains(project.defaultSchema().toUpperCase(Locale.ROOT) + "."))
                issues.add(warning("HARDCODED_SCHEMA", "업무규칙 SQL에 물리 스키마명이 포함되어 있습니다", row.logicalKey()));
        }
        if (Set.of("EXCLUSION","COLUMN_MAPPING","BUSINESS_RULE").contains(row.dataType())
                && (blank(row,"tableNormalized") || (row.dataType().equals("COLUMN_MAPPING") && blank(row,"columnNormalized"))))
            issues.add(error("MISSING_PHYSICAL_NAME", "테이블 또는 컬럼 물리명이 없습니다", row.logicalKey()));
        if (row.dataType().equals("COLUMN_MAPPING")) {
            String ref = row.values().getOrDefault(row.values().getOrDefault("ruleType", "VERIFICATION").equals("CODE") ? "codeRuleId" : "verificationRuleId",
                    row.values().getOrDefault("ruleName", ""));
            if (ref.isBlank() || !ruleRefs.contains(ref.toUpperCase(Locale.ROOT)))
                issues.add(error("DANGLING_RULE_REFERENCE", "존재하지 않는 규칙 참조: " + ref, row.logicalKey()));
        }
    }
    private boolean blank(NormalizedRow row,String key){return row.values().getOrDefault(key,"").isBlank();}
    private void add(Set<String>s,String value){if(value!=null&&!value.isBlank())s.add(value.toUpperCase(Locale.ROOT));}
    private ValidationIssue error(String code,String message,String key){return new ValidationIssue(Severity.ERROR,code,message,key);}
    private ValidationIssue warning(String code,String message,String key){return new ValidationIssue(Severity.WARNING,code,message,key);}
}
