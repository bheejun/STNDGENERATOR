package kr.wise.csr.importfile;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;

@Component
public class WisedqResultWorkbookParser implements WorkbookParser {
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
                    } else if (!row.get("검증룰명").isBlank() && !isBasicRule(row.get("검증룰명"))) {
                        candidates.add(verification(row));
                        candidates.add(mapping(row));
                    }
                }
            } catch (RuntimeException e) { errors.add(e.getMessage()); }
            var businessSheet = workbook.getSheet("(룰설정)업무규칙");
            if (businessSheet != null) {
                try {
                    for (var row : reader.rows(businessSheet, "DBMS명", "스키마명", "테이블명", "업무규칙명")) {
                        String type = row.first("PRF_TYP", "프로파일유형", "분석유형", "업무규칙유형").toUpperCase(Locale.ROOT);
                        if (type.equals("PT01")) { pt01++; continue; }
                        if (type.equals("PT02")) { pt02++; continue; }
                        candidates.add(business(row));
                    }
                } catch (RuntimeException e) { errors.add(e.getMessage()); }
            }
            var referenceSheet = workbook.getSheet("(룰설정)참조무결성");
            if (referenceSheet != null) {
                try {
                    pt01 += reader.rows(referenceSheet, "DBMS명", "스키마명", "테이블명", "컬럼명",
                            "참조컬럼명", "참조테이블명").size();
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
        Map<String,String> v = physical(r); v.put("exclusionType", "TBL"); v.put("reason", opinion(r));
        return candidate("EXCLUSION", key(v, "dbmsNormalized","schemaNormalized","tableNormalized","columnNormalized","exclusionType"), v, "(테이블선정)진단대상테이블", r.rowNumber());
    }
    private ImportCandidate columnExclusion(CellReader.SourceRow r, String reason) {
        Map<String,String> v = physical(r); v.put("exclusionType", "COL"); v.put("reason", reason);
        return candidate("EXCLUSION", key(v, "dbmsNormalized","schemaNormalized","tableNormalized",
                "columnNormalized","exclusionType"), v, "(룰설정)도메인", r.rowNumber());
    }
    private ImportCandidate verification(CellReader.SourceRow r) {
        Map<String,String> v=map("ruleName",r.get("검증룰명"),"expression",r.get("검증룰"),"qualityIndicator",r.get("품질지표명"));
        return candidate("VERIFICATION_RULE", key(v,"ruleName","expression"),v,"(룰설정)도메인",r.rowNumber());
    }
    private ImportCandidate mapping(CellReader.SourceRow r) {
        Map<String,String> v=physical(r); v.put("ruleType","VERIFICATION"); v.put("ruleName",r.get("검증룰명"));
        return candidate("COLUMN_MAPPING",key(v,"dbmsNormalized","schemaNormalized","tableNormalized","columnNormalized","ruleType"),v,"(룰설정)도메인",r.rowNumber());
    }
    private ImportCandidate business(CellReader.SourceRow r) {
        Map<String,String> v=physical(r); v.put("ruleName",r.get("업무규칙명")); v.put("ruleKind","BUSINESS");
        v.put("countSql",r.first("대상전체건수SQL","건수SQL","CNT_SQL"));
        v.put("ruleSql",r.first("오류데이터추출SQL","분석SQL","ANA_SQL","업무규칙SQL"));
        return candidate("BUSINESS_RULE",key(v,"tableNormalized","ruleName"),v,"(룰설정)업무규칙",r.rowNumber());
    }
    private Map<String,String> physical(CellReader.SourceRow r) {
        return map("dbmsOriginal",r.get("DBMS명"),"dbmsNormalized",norm(r.get("DBMS명")),"schemaOriginal",r.get("스키마명"),"schemaNormalized",norm(r.get("스키마명")),"tableOriginal",r.get("테이블명"),"tableNormalized",norm(r.get("테이블명")),"columnOriginal",r.get("컬럼명"),"columnNormalized",norm(r.get("컬럼명")));
    }
    private boolean isBasicRule(String ruleName) {
        return ruleName != null && ruleName.stripLeading().startsWith("[기본]");
    }
    private String opinion(CellReader.SourceRow row) {
        return row.values().entrySet().stream().filter(entry -> entry.getKey().contains("의견"))
                .map(Map.Entry::getValue).filter(value -> value != null && !value.isBlank()).findFirst().orElse("");
    }
    static ImportCandidate candidate(String type,String key,Map<String,String> values,String sheet,int row) { return new ImportCandidate(type,key,Map.copyOf(values),sheet,row); }
    static LinkedHashMap<String,String> map(Object... pairs) { LinkedHashMap<String,String> m=new LinkedHashMap<>(); for(int i=0;i<pairs.length;i+=2)m.put(String.valueOf(pairs[i]),String.valueOf(pairs[i+1])); return m; }
    static String norm(String v){ return v==null?"":v.trim().toUpperCase(Locale.ROOT); }
    static String key(Map<String,String> v,String... names){ List<String> p=new ArrayList<>(); for(String n:names)p.add(norm(v.get(n))); return String.join("|",p); }
}
