package kr.wise.csr.importfile;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class WisedqResultWorkbookParser implements WorkbookParser {
    private static final String EXECUTION_SHEET = "(진단실행)진단항목실행정보";
    private static final Set<String> BUSINESS_QUALITY_INDICATORS = Set.of(
            "업무규칙", "시간순서 일관성", "선후관계 정확성", "논리관계 일관성", "계산식");

    private final DefaultVerificationRuleCatalog defaultRules;

    @Autowired
    public WisedqResultWorkbookParser(DefaultVerificationRuleCatalog defaultRules) {
        this.defaultRules = defaultRules;
    }

    WisedqResultWorkbookParser() {
        this.defaultRules = null;
    }

    @Override public boolean supports(WorkbookType type) { return type == WorkbookType.WISEDQ_RESULT; }

    @Override
    public ImportBatch parse(Path path, ProjectContext context) {
        List<ImportCandidate> candidates = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int pt01 = 0, pt02 = 0;
        try (InputStream input = Files.newInputStream(path); Workbook workbook = WorkbookFactory.create(input)) {
            CellReader reader = new CellReader(workbook);
            candidates.add(system(context, "(진단결과)값진단결과", 1));
            try {
                for (var row : reader.rows(workbook.getSheet("(테이블선정)진단대상테이블"),
                        "DBMS명", "스키마명", "테이블명", "상태")) {
                    if (row.get("상태").contains("제외")) candidates.add(exclusion(row));
                }
            } catch (RuntimeException e) { errors.add(e.getMessage()); }
            try {
                for (var row : reader.rows(workbook.getSheet("(룰설정)도메인"),
                        "DBMS명", "스키마명", "테이블명", "컬럼명", "검증룰명", "검증룰")) {
                    String opinion = opinion(row);
                    if (opinion.contains("[컬럼제외사유]")) {
                        candidates.add(columnExclusion(row, opinion));
                    } else if (!row.get("검증룰명").isBlank()) {
                        candidates.add(targetTable(row, "(룰설정)도메인"));
                        if (row.get("품질지표명").contains("코드")) {
                            String outputRuleName = additionalRuleName(context, row.get("검증룰명"));
                            candidates.add(codeRule(row, outputRuleName));
                            candidates.add(codeMapping(row, outputRuleName));
                            continue;
                        }
                        var defaultRule = defaultRules == null
                                ? java.util.Optional.<DefaultVerificationRuleCatalog.DefaultRule>empty()
                                : defaultRules.findByName(row.get("검증룰명"));
                        if (defaultRule.isPresent()) {
                            candidates.add(mapping(row, defaultRule.get().ruleId(),
                                    defaultRule.get().ruleName(), "DEFAULT_CATALOG"));
                        } else {
                            String outputRuleName = additionalRuleName(context, row.get("검증룰명"));
                            candidates.add(verification(row, outputRuleName));
                            candidates.add(mapping(row, "", outputRuleName, "ADDITIONAL_EXTRACTED"));
                        }
                    }
                }
            } catch (RuntimeException e) { errors.add(e.getMessage()); }
            Map<String, List<CellReader.SourceRow>> businessExecutions =
                    readBusinessExecutions(reader, workbook, errors);
            var businessSheet = workbook.getSheet("(룰설정)업무규칙");
            if (businessSheet != null) {
                try {
                    for (var row : reader.rows(businessSheet, "DBMS명", "스키마명", "테이블명", "업무규칙명")) {
                        String type = row.first("PRF_TYP", "프로파일유형", "분석유형", "업무규칙유형").toUpperCase(Locale.ROOT);
                        if (type.equals("PT01")) continue;
                        if (type.equals("PT02")) { pt02++; continue; }
                        CellReader.SourceRow target = findBusinessExecution(row, businessExecutions);
                        candidates.add(business(row, target));
                        candidates.add(targetTable(target == null ? row : target, "(룰설정)업무규칙"));
                    }
                } catch (RuntimeException e) { errors.add(e.getMessage()); }
            }
        } catch (Exception e) {
            errors.add("결과보고서 파싱 실패: " + e.getMessage());
        }
        return new ImportBatch(WorkbookType.WISEDQ_RESULT, List.copyOf(candidates), pt01, pt02, List.copyOf(errors));
    }

    private ImportCandidate system(ProjectContext c, String sheet, int row) {
        return candidate("SYSTEM", Long.toString(c.systemId()), map("systemId", c.systemId(), "dbmsOriginal", c.defaultDbms(),
                "dbmsNormalized", norm(c.defaultDbms()), "schemaOriginal", c.defaultSchema(),
                "schemaNormalized", norm(c.defaultSchema())), sheet, row);
    }
    private ImportCandidate exclusion(CellReader.SourceRow r) {
        Map<String,String> v = physical(r); v.put("exclusionType", "TBL"); v.put("reason", opinion(r)); v.put("expYn", "Y");
        return candidate("EXCLUSION", key(v, "dbmsNormalized","schemaNormalized","tableNormalized","columnNormalized","exclusionType"), v, "(테이블선정)진단대상테이블", r.rowNumber());
    }
    private ImportCandidate columnExclusion(CellReader.SourceRow r, String reason) {
        Map<String,String> v = physical(r); v.put("exclusionType", "COL"); v.put("reason", reason); v.put("expYn", "Y");
        return candidate("EXCLUSION", key(v, "dbmsNormalized","schemaNormalized","tableNormalized",
                "columnNormalized","exclusionType"), v, "(룰설정)도메인", r.rowNumber());
    }
    private ImportCandidate targetTable(CellReader.SourceRow r, String sheet) {
        Map<String,String> v = physical(r);
        v.put("columnOriginal", ""); v.put("columnNormalized", "");
        v.put("exclusionType", "TBL"); v.put("reason", ""); v.put("expYn", "N");
        return candidate("EXCLUSION", key(v, "dbmsNormalized","schemaNormalized","tableNormalized",
                "columnNormalized","exclusionType"), v, sheet, r.rowNumber());
    }
    private ImportCandidate verification(CellReader.SourceRow r, String outputRuleName) {
        Map<String,String> v=map("ruleName",outputRuleName,"sourceRuleName",r.get("검증룰명"),"expression",r.get("검증룰"),
                "qualityIndicator",r.get("품질지표명"),
                "excludedValues",r.get("오류제외데이터"),
                "excludedValueSeparator",r.first("오류제외데이터구분자", "오류제외데이터 구분자"),
                "matchType",r.first("매칭유형", "MTCH_TYP"),
                "ruleOrigin","ADDITIONAL_EXTRACTED","ruleOriginLabel","추가 검증룰");
        return candidate("VERIFICATION_RULE", key(v,"ruleName","expression"),v,"(룰설정)도메인",r.rowNumber());
    }
    private ImportCandidate codeRule(CellReader.SourceRow r, String outputRuleName) {
        Map<String,String> v=map("dbmsOriginal",r.get("DBMS명"),"dbmsNormalized",norm(r.get("DBMS명")),
                "ruleName",outputRuleName,"sourceRuleName",r.get("검증룰명"),"codeType","목록성코드",
                "lookupSql","","description","결과보고서 코드 도메인","exclusiveYn","Y");
        return candidate("CODE_RULE", key(v,"dbmsNormalized","ruleName"),v,"(룰설정)도메인",r.rowNumber());
    }
    private ImportCandidate codeMapping(CellReader.SourceRow r, String outputRuleName) {
        Map<String,String> v=physical(r); v.put("ruleType","CODE"); v.put("ruleName",outputRuleName);
        v.put("codeClassId", r.first("코드분류ID", "CD_CLS_ID"));
        return candidate("COLUMN_MAPPING",key(v,"dbmsNormalized","schemaNormalized","tableNormalized",
                "columnNormalized","ruleType"),v,"(룰설정)도메인",r.rowNumber());
    }
    private ImportCandidate mapping(CellReader.SourceRow r, String verificationRuleId) {
        return mapping(r, verificationRuleId, r.get("검증룰명"));
    }
    private ImportCandidate mapping(CellReader.SourceRow r, String verificationRuleId, String outputRuleName) {
        return mapping(r, verificationRuleId, outputRuleName, "");
    }
    private ImportCandidate mapping(CellReader.SourceRow r, String verificationRuleId,
            String outputRuleName, String ruleOrigin) {
        Map<String,String> v=physical(r); v.put("ruleType","VERIFICATION"); v.put("ruleName",outputRuleName);
        if (verificationRuleId != null && !verificationRuleId.isBlank()) v.put("verificationRuleId", verificationRuleId);
        if (ruleOrigin != null && !ruleOrigin.isBlank()) v.put("ruleOrigin", ruleOrigin);
        return candidate("COLUMN_MAPPING",key(v,"dbmsNormalized","schemaNormalized","tableNormalized","columnNormalized","ruleType"),v,"(룰설정)도메인",r.rowNumber());
    }
    private Map<String, List<CellReader.SourceRow>> readBusinessExecutions(
            CellReader reader, Workbook workbook, List<String> errors) {
        Map<String, List<CellReader.SourceRow>> executions = new LinkedHashMap<>();
        var sheet = workbook.getSheet(EXECUTION_SHEET);
        if (sheet == null) return executions;
        try {
            for (var row : reader.rows(sheet, "DBMS명", "스키마명", "테이블명", "컬럼명",
                    "품질지표명", "검증룰명")) {
                if (!BUSINESS_QUALITY_INDICATORS.contains(row.get("품질지표명").trim())) continue;
                String ruleName = norm(row.get("검증룰명"));
                if (!ruleName.isBlank()) executions.computeIfAbsent(ruleName, ignored -> new ArrayList<>()).add(row);
            }
        } catch (RuntimeException e) {
            errors.add(e.getMessage());
        }
        return executions;
    }

    private CellReader.SourceRow findBusinessExecution(
            CellReader.SourceRow business, Map<String, List<CellReader.SourceRow>> executions) {
        List<CellReader.SourceRow> matches = executions.getOrDefault(norm(business.get("업무규칙명")), List.of());
        if (matches.size() == 1) return matches.get(0);
        if (matches.isEmpty()) return null;

        String table = norm(business.get("테이블명"));
        List<CellReader.SourceRow> tableMatches = matches.stream()
                .filter(row -> norm(row.get("테이블명")).equals(table))
                .toList();
        if (tableMatches.size() == 1) return tableMatches.get(0);

        String schema = norm(business.get("스키마명"));
        List<CellReader.SourceRow> schemaMatches = tableMatches.stream()
                .filter(row -> norm(row.get("스키마명")).equals(schema))
                .toList();
        return schemaMatches.size() == 1 ? schemaMatches.get(0) : null;
    }

    private ImportCandidate business(CellReader.SourceRow r, CellReader.SourceRow execution) {
        Map<String,String> v=physical(r); v.put("ruleName",r.get("업무규칙명")); v.put("ruleKind","BUSINESS");
        v.put("reportedInResult", "Y");
        if (execution != null) {
            v.putAll(physical(execution));
        }
        v.put("sourceRuleId",r.get("업무규칙ID"));
        v.put("qualityIndicator",r.get("품질지표명"));
        v.put("basis",r.get("근거규정"));
        v.put("description",r.get("설명"));
        v.put("countSql",r.first("대상전체건수SQL","건수SQL","CNT_SQL"));
        v.put("ruleSql",r.first("오류데이터추출SQL","분석SQL","ANA_SQL","업무규칙SQL"));
        return candidate("BUSINESS_RULE",key(v,"tableNormalized","ruleName"),v,"(룰설정)업무규칙",r.rowNumber());
    }
    private Map<String,String> physical(CellReader.SourceRow r) {
        return map("dbmsOriginal",r.get("DBMS명"),"dbmsNormalized",norm(r.get("DBMS명")),"schemaOriginal",r.get("스키마명"),"schemaNormalized",norm(r.get("스키마명")),"tableOriginal",r.get("테이블명"),"tableNormalized",norm(r.get("테이블명")),"columnOriginal",r.get("컬럼명"),"columnNormalized",norm(r.get("컬럼명")));
    }
    private String additionalRuleName(ProjectContext context, String sourceRuleName) {
        return context.prefixed(sourceRuleName);
    }
    private String opinion(CellReader.SourceRow row) {
        return row.values().entrySet().stream().filter(entry -> entry.getKey().contains("의견"))
                .map(Map.Entry::getValue).filter(value -> value != null && !value.isBlank()).findFirst().orElse("");
    }
    static ImportCandidate candidate(String type,String key,Map<String,String> values,String sheet,int row) { return new ImportCandidate(type,key,Map.copyOf(values),sheet,row); }
    static LinkedHashMap<String,String> map(Object... pairs) { LinkedHashMap<String,String> m=new LinkedHashMap<>(); for(int i=0;i<pairs.length;i+=2)m.put(String.valueOf(pairs[i]),String.valueOf(pairs[i+1])); return m; }
    static String norm(String v){ return v==null?"":v.trim(); }
    static String key(Map<String,String> v,String... names){ List<String> p=new ArrayList<>(); for(String n:names)p.add(norm(v.get(n))); return String.join("|",p); }
}
