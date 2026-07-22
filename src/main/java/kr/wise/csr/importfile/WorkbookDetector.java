package kr.wise.csr.importfile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;

@Component
public class WorkbookDetector {
    private static final String RESULT_SUMMARY = "(진단결과)값진단결과";
    private static final String RESULT_DOMAIN = "(룰설정)도메인";
    private static final String RESULT_EXECUTION = "(진단실행)진단항목실행정보";
    private static final String CRITERIA_NUMBERING = "기관별채번항목";
    private static final String CRITERIA_EXCLUSION = "waa_stnd_exp_obj(테이블)";
    private static final String CRITERIA_VERIFICATION = "waa_vrfc_rule(검증룰관리)";
    private static final String CRITERIA_MAPPING = "waa_stnd_rule_set(도메인규칙관리)";
    private static final String CRITERIA_BUSINESS = "waa_stnd_tbl_prf(업무규칙)";

    public WorkbookType detect(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            throw new WorkbookDetectionException("엑셀 파일을 찾을 수 없습니다: " + path);
        }
        try (InputStream input = Files.newInputStream(path); Workbook workbook = WorkbookFactory.create(input)) {
            Set<String> sheets = sheetNames(workbook);
            if (isResult(workbook, sheets)) return WorkbookType.WISEDQ_RESULT;
            if (isCriteria(workbook, sheets)) return WorkbookType.WDQ_CRITERIA;
            WorkbookType splitCriteria = detectSplitCriteria(workbook);
            if (splitCriteria != null) return splitCriteria;

            boolean knownSheetSet = sheets.containsAll(Set.of(RESULT_SUMMARY, RESULT_DOMAIN, RESULT_EXECUTION))
                    || sheets.containsAll(Set.of(CRITERIA_NUMBERING, CRITERIA_EXCLUSION,
                            CRITERIA_VERIFICATION, CRITERIA_MAPPING, CRITERIA_BUSINESS));
            String reason = knownSheetSet ? "필수 헤더가 누락되었습니다. " : "지원하는 시트 계약과 일치하지 않습니다. ";
            throw new WorkbookDetectionException(reason + "관찰된 시트: " + String.join(", ", sheets));
        } catch (WorkbookDetectionException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new WorkbookDetectionException("엑셀 파일을 읽을 수 없습니다: " + path.getFileName(), e);
        }
    }

    private WorkbookType detectSplitCriteria(Workbook workbook) {
        for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
            Sheet sheet = workbook.getSheetAt(i);
            if (hasHeaders(sheet, "검증룰명", "검증유형", "검증룰", "품질지표명"))
                return WorkbookType.CRITERIA_VERIFICATION_RULE;
            if (hasHeaders(sheet, "DBMS명", "스키마명", "자식테이블명", "자식컬럼명", "부모테이블명", "부모컬럼명"))
                return WorkbookType.CRITERIA_REFERENCE_INTEGRITY;
            if (hasHeaders(sheet, "DBMS명", "스키마명", "테이블명", "컬럼명", "검증룰", "코드분류ID"))
                return WorkbookType.CRITERIA_DOMAIN_MAPPING;
            if (hasHeaders(sheet, "업무규칙명", "DBMS명", "스키마명", "테이블명", "건수SQL", "분석SQL"))
                return WorkbookType.CRITERIA_BUSINESS_RULE;
            if (hasHeaders(sheet, "DBMS명", "스키마명", "테이블명", "테이블한글명", "제외여부", "제외사유"))
                return WorkbookType.CRITERIA_TABLE_EXCLUSION;
            if (hasHeaders(sheet, "DBMS명", "스키마명", "테이블명", "컬럼명", "제외여부", "제외사유"))
                return WorkbookType.CRITERIA_COLUMN_EXCLUSION;
            if (hasHeaders(sheet, "DBMS명", "검증코드명", "코드유형", "코드생성SQL"))
                return WorkbookType.CRITERIA_CODE_RULE;
        }
        return null;
    }

    private boolean isResult(Workbook workbook, Set<String> sheets) {
        return sheets.containsAll(Set.of(RESULT_SUMMARY, RESULT_DOMAIN, RESULT_EXECUTION))
                && containsValue(workbook.getSheet(RESULT_SUMMARY), "WISE DQ 값진단 결과 보고서")
                && hasHeaders(workbook.getSheet(RESULT_DOMAIN), "DBMS명", "스키마명", "테이블명", "컬럼명", "검증룰명")
                && hasHeaders(workbook.getSheet(RESULT_EXECUTION), "DBMS명", "스키마명", "테이블명", "컬럼명");
    }

    private boolean isCriteria(Workbook workbook, Set<String> sheets) {
        return sheets.containsAll(Set.of(CRITERIA_NUMBERING, CRITERIA_EXCLUSION,
                        CRITERIA_VERIFICATION, CRITERIA_MAPPING, CRITERIA_BUSINESS))
                && hasHeaders(workbook.getSheet(CRITERIA_EXCLUSION), "STND_EXP_OBJ_ID", "STND_SYS_NM", "STND_TBL_PNM")
                && hasHeaders(workbook.getSheet(CRITERIA_VERIFICATION), "VRFC_ID", "VRFC_NM")
                && hasHeaders(workbook.getSheet(CRITERIA_MAPPING), "STND_RULE_SET_ID", "VRFC_ID")
                && hasHeaders(workbook.getSheet(CRITERIA_BUSINESS), "STND_TBL_PRF_ID", "PRF_TYP", "BR_NM");
    }

    private boolean hasHeaders(Sheet sheet, String... expected) {
        if (sheet == null) return false;
        DataFormatter formatter = new DataFormatter(Locale.KOREA);
        int lastRow = Math.min(sheet.getLastRowNum(), 9);
        for (int rowIndex = 0; rowIndex <= lastRow; rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) continue;
            Set<String> actual = new LinkedHashSet<>();
            for (int column = 0; column < row.getLastCellNum(); column++) {
                String value = formatter.formatCellValue(row.getCell(column));
                if (!value.isBlank()) actual.add(normalize(value));
            }
            boolean matches = true;
            for (String header : expected) matches &= actual.contains(normalize(header));
            if (matches) return true;
        }
        return false;
    }

    private boolean containsValue(Sheet sheet, String expectedFragment) {
        if (sheet == null) return false;
        DataFormatter formatter = new DataFormatter(Locale.KOREA);
        int lastRow = Math.min(sheet.getLastRowNum(), 19);
        for (int rowIndex = 0; rowIndex <= lastRow; rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) continue;
            for (int column = 0; column < row.getLastCellNum(); column++) {
                if (normalize(formatter.formatCellValue(row.getCell(column)))
                        .contains(normalize(expectedFragment))) return true;
            }
        }
        return false;
    }

    private Set<String> sheetNames(Workbook workbook) {
        Set<String> names = new LinkedHashSet<>();
        for (int i = 0; i < workbook.getNumberOfSheets(); i++) names.add(workbook.getSheetName(i).trim());
        return names;
    }

    private String normalize(String value) {
        return value.replace("(*)", "").replaceAll("\\s+", "").trim().toUpperCase(Locale.ROOT);
    }
}
