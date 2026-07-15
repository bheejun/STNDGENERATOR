package kr.wise.csr.importfile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkbookDetectorTest {
    @TempDir Path directory;
    WorkbookDetector detector = new WorkbookDetector();

    @Test
    void detectsWisedqResultBySheetsAndHeaders() throws Exception {
        Path file = workbook("result.xlsx", Map.of(
                "(진단결과)값진단결과", List.of("WISE DQ 값진단 결과 보고서 V8.0"),
                "(룰설정)도메인", List.of("DBMS명", "스키마명", "테이블명", "컬럼명", "검증룰명"),
                "(진단실행)진단항목실행정보", List.of("DBMS명", "스키마명", "테이블명", "컬럼명"),
                "(룰설정)참조무결성", List.of("부모테이블", "자식테이블")));

        assertThat(detector.detect(file)).isEqualTo(WorkbookType.WISEDQ_RESULT);
    }

    @Test
    void detectsCriteriaWorkbookByStandardDataSheets() throws Exception {
        Map<String, List<String>> sheets = new LinkedHashMap<>();
        sheets.put("기관별채번항목", List.of("시스템명", "WAA_DB_CONN_TRG", "WAA_VRFC_RULE"));
        sheets.put("waa_stnd_exp_obj(테이블)", List.of("STND_EXP_OBJ_ID", "STND_SYS_NM", "STND_TBL_PNM"));
        sheets.put("waa_vrfc_rule(검증룰관리)", List.of("VRFC_ID", "VRFC_NM", "VRFC_RULE"));
        sheets.put("waa_stnd_rule_set(도메인규칙관리)", List.of("STND_RULE_SET_ID", "VRFC_ID"));
        sheets.put("waa_stnd_tbl_prf(업무규칙)", List.of("STND_TBL_PRF_ID", "PRF_TYP", "BR_NM"));
        Path file = workbook("criteria.xlsx", sheets);

        assertThat(detector.detect(file)).isEqualTo(WorkbookType.WDQ_CRITERIA);
    }

    @Test
    void rejectsUnknownWorkbookAndListsObservedSheets() throws Exception {
        Path file = workbook("unknown.xlsx", Map.of("임의시트", List.of("A", "B")));

        assertThatThrownBy(() -> detector.detect(file))
                .isInstanceOf(WorkbookDetectionException.class)
                .hasMessageContaining("임의시트");
    }

    @Test
    void rejectsMatchingSheetNamesWhenRequiredHeadersAreMissing() throws Exception {
        Path file = workbook("bad-result.xlsx", Map.of(
                "(진단결과)값진단결과", List.of("잘못된헤더"),
                "(룰설정)도메인", List.of("잘못된헤더"),
                "(진단실행)진단항목실행정보", List.of("잘못된헤더")));

        assertThatThrownBy(() -> detector.detect(file))
                .isInstanceOf(WorkbookDetectionException.class)
                .hasMessageContaining("필수 헤더");
    }

    private Path workbook(String name, Map<String, List<String>> sheets) throws Exception {
        Path file = directory.resolve(name);
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            sheets.forEach((sheetName, headers) -> {
                var row = workbook.createSheet(sheetName).createRow(0);
                for (int i = 0; i < headers.size(); i++) row.createCell(i).setCellValue(headers.get(i));
            });
            try (OutputStream output = Files.newOutputStream(file)) { workbook.write(output); }
        }
        return file;
    }
}
