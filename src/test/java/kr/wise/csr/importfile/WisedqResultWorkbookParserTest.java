package kr.wise.csr.importfile;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WisedqResultWorkbookParserTest {
    @TempDir Path directory;

    @Test
    void extractsCandidatesAndIgnoresPt01Pt02() throws Exception {
        Path file = directory.resolve("result.xlsx");
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            sheet(wb, "(진단결과)값진단결과", List.of("WISE DQ 값진단 결과 보고서 V8.0"));
            sheet(wb, "(테이블선정)진단대상테이블",
                    List.of("DBMS명", "스키마명", "테이블명", "상태", "의견"),
                    List.of("MAILDB", "APP", "TMP_LOG", "제외", "임시테이블"));
            sheet(wb, "(룰설정)도메인",
                    List.of("DBMS명", "스키마명", "테이블명", "컬럼명", "검증룰명", "품질지표명", "검증룰"),
                    List.of("MAILDB", "APP", "TB_USER", "USER_YN", "여부검증", "여부 도메인", "Y,N"));
            sheet(wb, "(진단실행)진단항목실행정보",
                    List.of("DBMS명", "스키마명", "테이블명", "컬럼명"));
            sheet(wb, "(룰설정)업무규칙",
                    List.of("DBMS명", "스키마명", "테이블명", "업무규칙명", "PRF_TYP", "분석SQL"),
                    List.of("MAILDB", "APP", "TB_USER", "정상업무규칙", "BUSINESS", "select count(*) from TB_USER"),
                    List.of("MAILDB", "APP", "TB_USER", "참조", "PT01", "select 1"),
                    List.of("MAILDB", "APP", "TB_USER", "중복", "PT02", "select 1"));
            try (OutputStream out = Files.newOutputStream(file)) { wb.write(out); }
        }

        ImportBatch batch = new WisedqResultWorkbookParser().parse(file, context());

        assertThat(batch.errors()).isEmpty();
        assertThat(batch.candidates()).extracting(ImportCandidate::dataType)
                .contains("SYSTEM", "EXCLUSION", "VERIFICATION_RULE", "COLUMN_MAPPING", "BUSINESS_RULE");
        assertThat(batch.excludedPt01Count()).isEqualTo(1);
        assertThat(batch.excludedPt02Count()).isEqualTo(1);
        assertThat(batch.candidates()).noneMatch(c -> c.values().containsValue("PT01") || c.values().containsValue("PT02"));
    }

    private ProjectContext context() { return new ProjectContext(1, 1, 2026, "202607", "MAILDB", "APP"); }

    @SafeVarargs
    private static void sheet(XSSFWorkbook wb, String name, List<String>... rows) {
        var sheet = wb.createSheet(name);
        for (int r = 0; r < rows.length; r++) {
            var row = sheet.createRow(r);
            for (int c = 0; c < rows[r].size(); c++) row.createCell(c).setCellValue(rows[r].get(c));
        }
    }
}
