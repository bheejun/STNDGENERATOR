package kr.wise.csr.importfile;

import static kr.wise.csr.importfile.WisedqResultWorkbookParser.candidate;
import static kr.wise.csr.importfile.WisedqResultWorkbookParser.key;
import static kr.wise.csr.importfile.WisedqResultWorkbookParser.map;
import static kr.wise.csr.importfile.WisedqResultWorkbookParser.norm;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;

@Component
public class SplitCriteriaWorkbookParser implements WorkbookParser {
    private final DefaultVerificationRuleCatalog defaultRules;

    public SplitCriteriaWorkbookParser() {
        this.defaultRules = null;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public SplitCriteriaWorkbookParser(DefaultVerificationRuleCatalog defaultRules) {
        this.defaultRules = defaultRules;
    }

    private static final Set<WorkbookType> SUPPORTED = Set.of(
            WorkbookType.CRITERIA_VERIFICATION_RULE,
            WorkbookType.CRITERIA_DOMAIN_MAPPING,
            WorkbookType.CRITERIA_BUSINESS_RULE,
            WorkbookType.CRITERIA_EXCLUSION_PATTERN,
            WorkbookType.CRITERIA_TABLE_EXCLUSION,
            WorkbookType.CRITERIA_COLUMN_EXCLUSION,
            WorkbookType.CRITERIA_CODE_RULE);

    @Override
    public boolean supports(WorkbookType workbookType) {
        return SUPPORTED.contains(workbookType);
    }

    @Override
    public ImportBatch parse(Path path, ProjectContext context) {
        throw new UnsupportedOperationException("워크북 유형을 지정해야 합니다");
    }

    public ImportBatch parse(Path path, ProjectContext context, WorkbookType type) {
        List<ImportCandidate> candidates = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int pt01 = 0;
        try (InputStream input = Files.newInputStream(path); Workbook workbook = WorkbookFactory.create(input)) {
            CellReader reader = new CellReader(workbook);
            Sheet sheet = workbook.getSheetAt(0);
            switch (type) {
                case CRITERIA_VERIFICATION_RULE -> parseVerification(reader, sheet, candidates);
                case CRITERIA_DOMAIN_MAPPING -> parseDomainMapping(reader, sheet, candidates);
                case CRITERIA_BUSINESS_RULE -> parseBusiness(reader, sheet, candidates);
                case CRITERIA_EXCLUSION_PATTERN -> parseExclusionPatterns(reader, sheet, candidates);
                case CRITERIA_TABLE_EXCLUSION -> parseTableExclusions(reader, sheet, candidates);
                case CRITERIA_COLUMN_EXCLUSION -> parseColumnExclusions(reader, sheet, candidates);
                case CRITERIA_CODE_RULE -> {
                    parseCodeRules(reader, workbook.getSheet("SQL"), candidates, context);
                    parseCodeValues(reader, workbook.getSheet("DATA"), candidates, context);
                }
                default -> throw new IllegalArgumentException("지원하지 않는 분리형 기준 파일: " + type);
            }
        } catch (Exception e) {
            errors.add("진단기준 파싱 실패: " + e.getMessage());
        }
        return new ImportBatch(type, List.copyOf(candidates), pt01, 0, List.copyOf(errors));
    }

    private void parseVerification(CellReader reader, Sheet sheet, List<ImportCandidate> candidates) {
        for (var row : reader.rows(sheet, "검증룰명", "검증유형", "검증룰", "품질지표명")) {
            Map<String, String> values = map(
                    "ruleName", row.get("검증룰명"),
                    "ruleType", row.get("검증유형"),
                    "matchType", row.get("매칭유형"),
                    "expression", row.get("검증룰"),
                    "qualityIndicator", row.get("품질지표명"),
                    "excludedValues", row.get("오류제외데이터"),
                    "excludedValueSeparator", row.get("오류제외데이터구분자"),
                    "description", row.get("검증룰설명"));
            if (defaultRules != null && defaultRules.findByName(values.get("ruleName")).isPresent())
                continue;
            values.put("ruleOrigin", "ADDITIONAL_UPLOADED");
            values.put("ruleOriginLabel", "추가 검증룰");
            candidates.add(candidate("VERIFICATION_RULE", key(values, "ruleName", "expression"), values,
                    sheet.getSheetName(), row.rowNumber()));
        }
    }

    private int parseReferenceIntegrity(CellReader reader, Sheet sheet) {
        return reader.rows(sheet, "DBMS명", "스키마명", "자식테이블명", "자식컬럼명", "부모테이블명", "부모컬럼명").size();
    }

    private void parseDomainMapping(CellReader reader, Sheet sheet, List<ImportCandidate> candidates) {
        for (var row : reader.rows(sheet, "DBMS명", "스키마명", "테이블명", "컬럼명", "검증룰", "코드분류ID")) {
            Map<String, String> values = physical(row, "테이블명", "컬럼명");
            String codeId = row.get("코드분류ID");
            values.put("ruleType", codeId.isBlank() ? "VERIFICATION" : "CODE");
            values.put("ruleName", row.get("검증룰"));
            if (codeId.isBlank() && defaultRules != null) {
                defaultRules.findByName(values.get("ruleName"))
                        .ifPresent(rule -> {
                            values.put("verificationRuleId", rule.ruleId());
                            values.put("ruleOrigin", "DEFAULT_CATALOG");
                        });
            }
            values.put("codeClassId", codeId);
            values.put("columnLogicalName", row.get("컬럼한글명"));
            values.put("dataType", row.first("DATATYPE", "데이터타입"));
            values.put("comment", row.get("컬럼의견"));
            candidates.add(candidate("COLUMN_MAPPING",
                    key(values, "dbmsNormalized", "schemaNormalized", "tableNormalized", "columnNormalized", "ruleType"),
                    values, sheet.getSheetName(), row.rowNumber()));
        }
    }

    private void parseBusiness(CellReader reader, Sheet sheet, List<ImportCandidate> candidates) {
        for (var row : reader.rows(sheet, "업무규칙명", "DBMS명", "스키마명", "테이블명", "건수SQL", "분석SQL")) {
            Map<String, String> values = physical(row, "테이블명", "컬럼명");
            String targetColumn = lastLine(values.get("columnOriginal"));
            values.put("columnOriginal", targetColumn);
            values.put("columnNormalized", norm(targetColumn));
            values.put("ruleName", row.get("업무규칙명"));
            values.put("ruleKind", "BUSINESS");
            values.put("qualityIndicator", row.get("품질지표명"));
            values.put("basis", row.get("근거규정"));
            values.put("description", row.get("설명"));
            values.put("countSql", row.get("건수SQL"));
            values.put("ruleSql", row.get("분석SQL"));
            candidates.add(candidate("BUSINESS_RULE", key(values, "tableNormalized", "ruleName"), values,
                    sheet.getSheetName(), row.rowNumber()));
        }
    }

    private void parseTableExclusions(CellReader reader, Sheet sheet, List<ImportCandidate> candidates) {
        for (var row : reader.rows(sheet, "DBMS명", "스키마명", "테이블명", "제외여부", "제외사유")) {
            Map<String, String> values = physical(row, "테이블명", "컬럼명");
            boolean excluded = yes(row.get("제외여부"));
            values.put("exclusionType", "TBL");
            values.put("expYn", excluded ? "Y" : "N");
            values.put("reason", excluded ? row.get("제외사유") : "");
            candidates.add(candidate("EXCLUSION",
                    key(values, "dbmsNormalized", "schemaNormalized", "tableNormalized", "exclusionType"),
                    values, sheet.getSheetName(), row.rowNumber()));
        }
    }

    private void parseExclusionPatterns(CellReader reader, Sheet sheet, List<ImportCandidate> candidates) {
        for (var row : reader.rows(sheet, "DBMS명", "스키마명", "포함관계", "제외기준룰", "제외사유")) {
            String relation = exclusionRelation(row.get("포함관계"));
            String pattern = exclusionPattern(row.get("제외기준룰"), relation);
            Map<String, String> values = map(
                    "dbmsOriginal", row.get("DBMS명"),
                    "dbmsNormalized", norm(row.get("DBMS명")),
                    "schemaOriginal", row.get("스키마명"),
                    "schemaNormalized", norm(row.get("스키마명")),
                    "relation", relation,
                    "pattern", pattern,
                    "reason", row.get("제외사유"));
            candidates.add(candidate("EXCLUSION_PATTERN",
                    key(values, "dbmsNormalized", "schemaNormalized", "relation", "pattern"),
                    values, sheet.getSheetName(), row.rowNumber()));
        }
    }

    private String exclusionRelation(String value) {
        String normalized = value == null ? "" : value.trim();
        if ("앞".equals(normalized) || "F".equalsIgnoreCase(normalized)) return "F";
        if ("뒤".equals(normalized) || "B".equalsIgnoreCase(normalized)) return "B";
        return normalized;
    }

    private String exclusionPattern(String value, String relation) {
        String normalized = value == null ? "" : value.trim();
        if ("B".equals(relation) && normalized.startsWith("_")) return normalized.substring(1);
        if ("F".equals(relation) && normalized.endsWith("_")) return normalized.substring(0, normalized.length() - 1);
        return normalized;
    }

    private void parseColumnExclusions(CellReader reader, Sheet sheet, List<ImportCandidate> candidates) {
        for (var row : reader.rows(sheet, "DBMS명", "스키마명", "테이블명", "컬럼명", "제외여부", "제외사유")) {
            if (!yes(row.get("제외여부"))) continue;
            Map<String, String> values = physical(row, "테이블명", "컬럼명");
            values.put("exclusionType", "COL");
            values.put("reason", row.get("제외사유"));
            candidates.add(candidate("EXCLUSION",
                    key(values, "dbmsNormalized", "schemaNormalized", "tableNormalized", "columnNormalized", "exclusionType"),
                    values, sheet.getSheetName(), row.rowNumber()));
        }
    }

    private void parseCodeRules(CellReader reader, Sheet sheet, List<ImportCandidate> candidates, ProjectContext context) {
        for (var row : reader.rows(sheet, "DBMS명", "검증코드명", "코드유형", "코드생성SQL")) {
            String ruleName = prefixed(context, row.get("검증코드명"));
            Map<String, String> values = map(
                    "dbmsOriginal", row.get("DBMS명"),
                    "dbmsNormalized", norm(row.get("DBMS명")),
                    "ruleName", ruleName,
                    "sourceRuleName", row.get("검증코드명"),
                    "codeType", row.get("코드유형"),
                    "lookupSql", row.get("코드생성SQL"),
                    "description", row.get("설명"),
                    "exclusiveYn", row.get("코드생성SQL").isBlank() ? "Y" : "N");
            candidates.add(candidate("CODE_RULE", key(values, "dbmsNormalized", "ruleName"), values,
                    sheet.getSheetName(), row.rowNumber()));
        }
    }

    private void parseCodeValues(CellReader reader, Sheet sheet, List<ImportCandidate> candidates,
            ProjectContext context) {
        if (sheet == null) return;
        for (var row : reader.rows(sheet, "DB명", "검증코드명", "코드", "코드명")) {
            String ruleName = prefixed(context, row.get("검증코드명"));
            Map<String,String> values=map("dbmsOriginal",row.get("DB명"),"dbmsNormalized",norm(row.get("DB명")),
                    "ruleName",ruleName,"codeId",row.get("코드"),"codeName",row.get("코드명"),
                    "description",row.get("설명"),"exclusiveYn","Y");
            candidates.add(candidate("CODE_VALUE",key(values,"dbmsNormalized","ruleName","codeId"),values,
                    sheet.getSheetName(),row.rowNumber()));
        }
    }

    private String prefixed(ProjectContext context, String sourceName) {
        return context.prefixed(sourceName);
    }

    private Map<String, String> physical(CellReader.SourceRow row, String tableHeader, String columnHeader) {
        return map(
                "dbmsOriginal", row.get("DBMS명"), "dbmsNormalized", norm(row.get("DBMS명")),
                "schemaOriginal", row.get("스키마명"), "schemaNormalized", norm(row.get("스키마명")),
                "tableOriginal", row.get(tableHeader), "tableNormalized", norm(row.get(tableHeader)),
                "columnOriginal", row.get(columnHeader), "columnNormalized", norm(row.get(columnHeader)));
    }

    private boolean yes(String value) {
        return "Y".equalsIgnoreCase(value) || "YES".equalsIgnoreCase(value) || "제외".equals(value);
    }

    private String lastLine(String value) {
        if (value == null || value.isBlank()) return "";
        String selected = "";
        for (String line : value.split("\\R")) {
            if (!line.isBlank()) selected = line.trim();
        }
        return selected;
    }
}
