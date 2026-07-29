package kr.wise.csr.review;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;

import kr.wise.csr.importfile.WisedqSchemaResolver;

@Component
public class WisedqReviewEngine implements ReviewEngine {
    public static final String ENGINE_VERSION = "wisedq-review-0.2.0";
    private static final List<String> REQUIRED_SHEETS = List.of(
            "값진단결과", "진단대상테이블", "도메인", "진단항목실행정보");
    private static final List<String> OPTIONAL_SHEETS = List.of(
            "참조무결성", "업무규칙", "진단항목오류정보");
    private final DataFormatter formatter = new DataFormatter(Locale.KOREA);

    @Override
    public ReviewResult review(Path report, ReviewContext context) {
        List<ReviewIssue> issues = new ArrayList<>();
        Map<String, String> metadata = new LinkedHashMap<>();
        Map<String, Long> metrics = new LinkedHashMap<>();
        String hash = sha256(report);
        try (InputStream input = Files.newInputStream(report); Workbook workbook = WorkbookFactory.create(input)) {
            Map<String, Sheet> sheets = locateSheets(workbook);
            for (String name : REQUIRED_SHEETS) {
                if (!sheets.containsKey(name))
                    issues.add(issue("MISSING_REQUIRED_SHEET", ReviewSeverity.ERROR,
                            "필수 시트가 없습니다: " + name, null, null, name, true));
            }
            for (String name : OPTIONAL_SHEETS) {
                if (!sheets.containsKey(name))
                    issues.add(issue("MISSING_OPTIONAL_SHEET", ReviewSeverity.WARNING,
                            "검토 시트가 없습니다: " + name, null, null, name, false));
            }
            if (REQUIRED_SHEETS.stream().anyMatch(name -> !sheets.containsKey(name)))
                return result(hash, metadata, metrics, issues);

            readMetadata(sheets.get("값진단결과"), metadata, issues);
            String reportedSchema = metadata.getOrDefault("schemaName", "");
            String actualSchema = WisedqSchemaResolver.resolve(workbook);
            if (!actualSchema.isBlank()) {
                metadata.put("reportedSchemaName", reportedSchema);
                metadata.put("schemaName", actualSchema);
            }
            reviewTables(sheets.get("진단대상테이블"), sheets.get("값진단결과"), metrics, issues);
            Set<String> customRules = reviewDomain(sheets.get("도메인"), metrics, issues);
            reviewExecution(sheets.get("진단항목실행정보"), customRules, metrics, issues);
            reviewBusinessRules(sheets.get("업무규칙"), metrics, issues);
            metrics.put("referenceRuleCount", countDataRows(sheets.get("참조무결성")));
            metrics.put("errorInfoCount", countDataRows(sheets.get("진단항목오류정보")));
            checkExpectedContext(context, metadata, issues);
            return result(hash, metadata, metrics, issues);
        } catch (Exception e) {
            issues.add(issue("FILE_READ_ERROR", ReviewSeverity.ERROR, "결과보고서를 읽을 수 없습니다",
                    null, null, e.getMessage(), true));
            return result(hash, metadata, metrics, issues);
        }
    }

    private Map<String, Sheet> locateSheets(Workbook workbook) {
        Map<String, Sheet> result = new HashMap<>();
        for (Sheet sheet : workbook) {
            String compact = compact(sheet.getSheetName());
            for (String expected : concat(REQUIRED_SHEETS, OPTIONAL_SHEETS))
                if (compact.contains(compact(expected))) result.putIfAbsent(expected, sheet);
        }
        return result;
    }

    private void readMetadata(Sheet sheet, Map<String, String> metadata, List<ReviewIssue> issues) {
        Map<String, String> labels = new HashMap<>();
        for (Row row : sheet) {
            for (int column = 0; column < Math.min(row.getLastCellNum(), 12); column++) {
                String label = text(row, column);
                if (!label.isBlank()) labels.put(compact(label), text(row, column + 1));
            }
        }
        putMetadata(metadata, "organizationName", labels, "기관명");
        putMetadata(metadata, "systemName", labels, "정보시스템명");
        putMetadata(metadata, "dbmsName", labels, "DBMS명");
        putMetadata(metadata, "schemaName", labels, "DBMS서비스(스키마)명");
        putMetadata(metadata, "dbmsType", labels, "DBMS종류");
        for (var entry : metadata.entrySet()) {
            if (entry.getValue().isBlank())
                issues.add(issue("MISSING_REPORT_METADATA", ReviewSeverity.ERROR,
                        "결과보고서 기본정보가 없습니다: " + entry.getKey(), sheet.getSheetName(), null,
                        entry.getKey(), true));
        }
    }

    private void reviewTables(Sheet sheet, Sheet summary, Map<String, Long> metrics, List<ReviewIssue> issues) {
        Header header = header(sheet, List.of("상태", "테이블명"));
        requireHeaders(header, sheet, List.of("상태", "테이블명"), issues);
        if (!header.valid()) return;
        long target = 0, excluded = 0, uncollected = 0;
        for (int r = header.rowIndex() + 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null || text(row, header.column("테이블명")).isBlank()) continue;
            String status = text(row, header.column("상태")).trim();
            if ("대상".equals(status) || "수집완료".equals(status)) target++;
            else if ("제외".equals(status)) {
                excluded++;
                int opinion = header.optionalColumnContaining("의견");
                if (opinion < 0 || text(row, opinion).isBlank())
                    issues.add(issue("EXCLUSION_REASON_MISSING", ReviewSeverity.WARNING,
                            "제외 테이블의 사유가 없습니다", sheet.getSheetName(), r + 1,
                            text(row, header.column("테이블명")), false));
            } else if ("미수집".equals(status)) {
                uncollected++;
                issues.add(issue("UNCOLLECTED_TABLE", ReviewSeverity.ERROR,
                        "미수집 테이블이 있습니다", sheet.getSheetName(), r + 1,
                        text(row, header.column("테이블명")), true));
            }
        }
        metrics.put("targetTableCount", target);
        metrics.put("excludedTableCount", excluded);
        metrics.put("uncollectedTableCount", uncollected);
        Long summaryTarget = summaryCount(summary, "대상");
        Long summaryExcluded = summaryCount(summary, "제외");
        Long summaryUncollected = summaryCount(summary, "미수집");
        compareSummary("대상", summaryTarget, target, summary, issues);
        compareSummary("제외", summaryExcluded, excluded, summary, issues);
        compareSummary("미수집", summaryUncollected, uncollected, summary, issues);
    }

    private Set<String> reviewDomain(Sheet sheet, Map<String, Long> metrics, List<ReviewIssue> issues) {
        List<String> required = List.of("스키마명", "테이블명", "컬럼명", "검증룰명", "검증룰");
        Header header = header(sheet, required);
        requireHeaders(header, sheet, required, issues);
        Set<String> customRules = new HashSet<>();
        long domain = 0, custom = 0, basic = 0, codeMappings = 0, codeRuleMissing = 0;
        if (!header.valid()) return customRules;
        int indicatorColumn = header.optionalColumnContaining("품질지표명");
        for (int r = header.rowIndex() + 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null || text(row, header.column("테이블명")).isBlank()) continue;
            domain++;
            String ruleName = text(row, header.column("검증룰명"));
            String indicator = indicatorColumn < 0 ? "" : text(row, indicatorColumn);
            if (ruleName.isBlank()) {
                if (indicator.contains("코드")) {
                    codeRuleMissing++;
                    issues.add(issue("CODE_RULE_NOT_VISIBLE", ReviewSeverity.WARNING,
                            "코드데이터 매핑 컬럼이지만 결과보고서에서 코드 검증룰을 확인할 수 없습니다",
                            sheet.getSheetName(), r + 1,
                            text(row, header.column("테이블명")) + "." + text(row, header.column("컬럼명")), false));
                }
                continue;
            }
            String expression = text(row, header.column("검증룰"));
            if (indicator.contains("코드")) codeMappings++;
            else if (ruleName.startsWith("[기본]")) basic++;
            else {
                custom++;
                customRules.add(ruleKey(row, header));
            }
            if (expression.isBlank())
                issues.add(issue("RULE_EXPRESSION_MISSING", ReviewSeverity.ERROR,
                        "검증룰 식이 없습니다", sheet.getSheetName(), r + 1, ruleName, true));
        }
        metrics.put("domainRowCount", domain);
        metrics.put("basicRuleMappingCount", basic);
        metrics.put("customRuleMappingCount", custom);
        metrics.put("codeRuleMappingCount", codeMappings);
        metrics.put("missingCodeRuleCount", codeRuleMissing);
        metrics.put("codeDataCount", 0L);
        if (codeMappings > 0)
            issues.add(issue("CODE_DATA_MISSING", ReviewSeverity.WARNING,
                    "코드 도메인 사용 컬럼이 있으나 결과보고서에는 코드 데이터가 제공되지 않았습니다. 프로젝트의 코드 데이터 입력 화면에서 보완해야 합니다",
                    sheet.getSheetName(), null, codeMappings + "개 컬럼", false));
        return customRules;
    }

    private void reviewExecution(Sheet sheet, Set<String> customRules, Map<String, Long> metrics,
            List<ReviewIssue> issues) {
        List<String> required = List.of("스키마명", "테이블명", "컬럼명", "검증룰명", "실행상태");
        Header header = header(sheet, required);
        requireHeaders(header, sheet, required, issues);
        if (!header.valid()) return;
        Set<String> executedRules = new HashSet<>();
        long executions = 0, incomplete = 0;
        for (int r = header.rowIndex() + 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null || text(row, header.column("테이블명")).isBlank()) continue;
            executions++;
            executedRules.add(ruleKey(row, header));
            String status = text(row, header.column("실행상태"));
            if (!"COMPLETED".equalsIgnoreCase(status)) {
                incomplete++;
                issues.add(issue("EXECUTION_NOT_COMPLETED", ReviewSeverity.ERROR,
                        "진단 실행이 완료되지 않았습니다", sheet.getSheetName(), r + 1, status, true));
            }
        }
        for (String missing : customRules)
            if (!executedRules.contains(missing))
                issues.add(issue("RULE_NOT_EXECUTED", ReviewSeverity.ERROR,
                        "추가 검증룰의 실행 내역이 없습니다", sheet.getSheetName(), null, missing, true));
        metrics.put("executionCount", executions);
        metrics.put("incompleteExecutionCount", incomplete);
        metrics.put("unexecutedCustomRuleCount",
                customRules.stream().filter(rule -> !executedRules.contains(rule)).count());
    }

    private void reviewBusinessRules(Sheet sheet, Map<String, Long> metrics, List<ReviewIssue> issues) {
        if (sheet == null) {
            metrics.put("businessRuleCount", 0L);
            return;
        }
        List<String> required = List.of("업무규칙명", "테이블명", "대상전체건수SQL", "오류데이터추출SQL");
        Header header = header(sheet, required);
        requireHeaders(header, sheet, required, issues);
        if (!header.valid()) return;
        long count = 0;
        for (int r = header.rowIndex() + 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null || text(row, header.column("업무규칙명")).isBlank()) continue;
            count++;
            if (text(row, header.column("테이블명")).isBlank()
                    || text(row, header.column("대상전체건수SQL")).isBlank()
                    || text(row, header.column("오류데이터추출SQL")).isBlank())
                issues.add(issue("BUSINESS_RULE_SQL_MISSING", ReviewSeverity.ERROR,
                        "업무규칙의 테이블 또는 SQL이 없습니다", sheet.getSheetName(), r + 1,
                        text(row, header.column("업무규칙명")), true));
        }
        metrics.put("businessRuleCount", count);
    }

    private void checkExpectedContext(ReviewContext context, Map<String, String> metadata, List<ReviewIssue> issues) {
        compareExpected("시스템명", context.expectedSystemName(), metadata.get("systemName"), issues);
        compareExpected("DB명", context.expectedDbmsName(), metadata.get("dbmsName"), issues);
        compareExpected("스키마명", context.expectedSchema(), metadata.get("schemaName"), issues);
    }

    private void compareExpected(String label, String expected, String actual, List<ReviewIssue> issues) {
        if (expected == null || expected.isBlank() || actual == null || actual.isBlank()) return;
        if (!compact(expected).equalsIgnoreCase(compact(actual)))
            issues.add(issue("SYSTEM_CONTEXT_MISMATCH", ReviewSeverity.WARNING,
                    "등록 시스템의 " + label + "과 결과보고서 값이 다릅니다", null, null,
                    "expected=" + expected + ", actual=" + actual, false));
    }

    private Header header(Sheet sheet, List<String> expected) {
        int limit = Math.min(sheet.getLastRowNum(), 15);
        for (int r = 0; r <= limit; r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            Map<String, Integer> columns = new HashMap<>();
            for (int c = 0; c < Math.max(0, row.getLastCellNum()); c++)
                if (!text(row, c).isBlank()) columns.put(compact(text(row, c)), c);
            long found = expected.stream().filter(name -> columns.containsKey(compact(name))).count();
            if (found >= Math.min(2, expected.size())) return new Header(r, columns, found == expected.size());
        }
        return new Header(-1, Map.of(), false);
    }

    private void requireHeaders(Header header, Sheet sheet, List<String> required, List<ReviewIssue> issues) {
        for (String name : required)
            if (!header.columns().containsKey(compact(name)))
                issues.add(issue("MISSING_REQUIRED_HEADER", ReviewSeverity.ERROR,
                        "필수 컬럼이 없습니다: " + name, sheet.getSheetName(),
                        header.rowIndex() < 0 ? null : header.rowIndex() + 1, name, true));
    }

    private Long summaryCount(Sheet sheet, String label) {
        for (Row row : sheet) {
            for (int c = 0; c < Math.max(0, row.getLastCellNum()); c++) {
                if (!compact(text(row, c)).equals(compact(label))) continue;
                String value = text(row, c + 1);
                if (value.contains("/")) value = value.substring(0, value.indexOf('/'));
                try {
                    return Long.parseLong(value.replaceAll("[^0-9]", ""));
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private void compareSummary(String label, Long expected, long actual, Sheet sheet, List<ReviewIssue> issues) {
        if (expected != null && expected != actual)
            issues.add(issue("TABLE_SUMMARY_MISMATCH", ReviewSeverity.ERROR,
                    "진단대상 요약과 상세 건수가 다릅니다", sheet.getSheetName(), null,
                    label + ": summary=" + expected + ", detail=" + actual, true));
    }

    private long countDataRows(Sheet sheet) {
        if (sheet == null) return 0;
        long count = 0;
        for (int r = 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row != null && !text(row, 0).isBlank()) count++;
        }
        return count;
    }

    private String ruleKey(Row row, Header header) {
        return compact(text(row, header.column("스키마명"))) + "|"
                + compact(text(row, header.column("테이블명"))) + "|"
                + compact(text(row, header.column("컬럼명"))) + "|"
                + compact(text(row, header.column("검증룰명")));
    }

    private String text(Row row, int column) {
        if (row == null || column < 0 || row.getCell(column) == null) return "";
        return formatter.formatCellValue(row.getCell(column)).trim();
    }

    private void putMetadata(Map<String, String> metadata, String key, Map<String, String> labels, String label) {
        metadata.put(key, labels.getOrDefault(compact(label), ""));
    }

    private ReviewResult result(String hash, Map<String, String> metadata, Map<String, Long> metrics,
            List<ReviewIssue> issues) {
        boolean blocked = issues.stream().anyMatch(ReviewIssue::blocksAdoption);
        ReviewVerdict verdict = blocked ? ReviewVerdict.FAIL
                : issues.stream().anyMatch(issue -> issue.severity() == ReviewSeverity.WARNING)
                        ? ReviewVerdict.CONDITIONAL : ReviewVerdict.PASS;
        return new ReviewResult(ENGINE_VERSION, hash, verdict, !blocked, metadata, metrics, issues);
    }

    private ReviewIssue issue(String code, ReviewSeverity severity, String message, String sheet,
            Integer row, String evidence, boolean blocks) {
        return new ReviewIssue(code, severity, message, sheet, row, evidence == null ? "" : evidence, blocks);
    }

    private String sha256(Path path) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path); DigestInputStream stream = new DigestInputStream(input, digest)) {
                stream.transferTo(java.io.OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("파일 해시를 계산할 수 없습니다", e);
        }
    }

    private String compact(String value) {
        return value == null ? "" : value.replaceAll("[\\s_()]+", "").toUpperCase(Locale.ROOT);
    }

    private List<String> concat(List<String> left, List<String> right) {
        List<String> result = new ArrayList<>(left);
        result.addAll(right);
        return result;
    }

    private record Header(int rowIndex, Map<String, Integer> columns, boolean valid) {
        int column(String name) {
            return columns.getOrDefault(name.replaceAll("[\\s_()]+", "").toUpperCase(Locale.ROOT), -1);
        }

        int optionalColumnContaining(String name) {
            String compact = name.replaceAll("[\\s_()]+", "").toUpperCase(Locale.ROOT);
            return columns.entrySet().stream().filter(entry -> entry.getKey().contains(compact))
                    .map(Map.Entry::getValue).findFirst().orElse(-1);
        }
    }
}
