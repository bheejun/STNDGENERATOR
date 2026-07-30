package kr.wise.csr.importfile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
                    List.of("DBMS명", "스키마명", "테이블명", "컬럼명", "검증룰명", "품질지표명", "검증룰", "오류제외데이터", "의견 (컬럼관련 의견)"),
                    List.of("MAILDB", "APP", "TB_USER", "USER_YN", "여부검증", "여부 도메인", "Y,N", "UNKNOWN", ""),
                    List.of("MAILDB", "APP", "TB_USER", "NAME", "[기본]공백", "공백", "X", "", ""),
                    List.of("MAILDB", "APP", "TB_USER", "ALIAS", "[기본]카탈로그불일치", "공백", "A", "", ""),
                    List.of("MAILDB", "APP", "TB_USER", "BLOB_DATA", "", "", "", "", "[컬럼제외사유] 진단 불가 자료형"));
            sheet(wb, "(진단실행)진단항목실행정보",
                    List.of("DBMS명", "스키마명", "테이블명", "컬럼명", "품질지표명", "검증룰명"),
                    List.of("MAILDB", "APP", "TB_USER", "USER_ID", "업무규칙", "정상업무규칙"),
                    List.of("MAILDB", "APP", "TB_USER", "IGNORED_COLUMN", "필수값", "정상업무규칙"));
            sheet(wb, "(룰설정)업무규칙",
                    List.of("품질지표명", "업무규칙ID", "업무규칙명", "근거규정", "설명",
                            "DBMS명", "스키마명", "테이블명", "PRF_TYP", "분석SQL"),
                    List.of("완전성", "OBJ_SOURCE_1", "정상업무규칙", "내부지침", "필수값 확인",
                            "MAILDB", "APP", "TB_USER", "BUSINESS", "select count(*) from TB_USER"),
                    List.of("", "", "참조", "", "", "MAILDB", "APP", "TB_USER", "PT01", "select 1"),
                    List.of("", "", "중복", "", "", "MAILDB", "APP", "TB_USER", "PT02", "select 1"));
            try (OutputStream out = Files.newOutputStream(file)) { wb.write(out); }
        }

        DefaultVerificationRuleCatalog catalog = mock(DefaultVerificationRuleCatalog.class);
        when(catalog.findByName("[기본]공백")).thenReturn(Optional.of(
                new DefaultVerificationRuleCatalog.DefaultRule("VRF1_00000000098", "FRM", "[기본]공백",
                        "X", "", "", "", "N")));
        ImportBatch batch = new WisedqResultWorkbookParser(catalog).parse(file, context());

        assertThat(batch.errors()).isEmpty();
        assertThat(batch.candidates()).extracting(ImportCandidate::dataType)
                .contains("SYSTEM", "EXCLUSION", "VERIFICATION_RULE", "COLUMN_MAPPING", "COLUMN_INVENTORY",
                        "BUSINESS_RULE");
        assertThat(batch.excludedPt01Count()).isZero();
        assertThat(batch.excludedPt02Count()).isEqualTo(1);
        assertThat(batch.candidates()).noneMatch(c -> c.values().containsValue("PT01") || c.values().containsValue("PT02"));
        assertThat(batch.candidates()).noneMatch(c -> c.dataType().equals("VERIFICATION_RULE")
                && c.values().containsValue("[기본]공백"));
        assertThat(batch.candidates()).noneMatch(c -> c.dataType().equals("EXCLUSION")
                && "COL".equals(c.values().get("exclusionType"))
                && "N".equals(c.values().get("expYn")));
        assertThat(batch.candidates()).anyMatch(c -> c.dataType().equals("COLUMN_MAPPING")
                && "VRF1_00000000098".equals(c.values().get("verificationRuleId")));
        assertThat(batch.candidates()).anyMatch(c -> c.dataType().equals("VERIFICATION_RULE")
                && "UNKNOWN".equals(c.values().get("excludedValues")));
        assertThat(batch.candidates()).anyMatch(c -> c.dataType().equals("VERIFICATION_RULE")
                && "[2026표준시스템DB 테스트시스템] 여부검증".equals(c.values().get("ruleName"))
                && "".equals(c.values().get("ruleType")));
        assertThat(batch.candidates()).anyMatch(c -> c.dataType().equals("VERIFICATION_RULE")
                && "[기본]카탈로그불일치".equals(c.values().get("sourceRuleName"))
                && "ADDITIONAL_EXTRACTED".equals(c.values().get("ruleOrigin")));
        assertThat(batch.candidates()).anyMatch(c -> c.dataType().equals("EXCLUSION")
                && c.values().get("exclusionType").equals("COL")
                && "Y".equals(c.values().get("expYn")));
        assertThat(batch.candidates()).anyMatch(c -> c.dataType().equals("BUSINESS_RULE")
                && "완전성".equals(c.values().get("qualityIndicator"))
                && "Y".equals(c.values().get("reportedInResult"))
                && "USER_ID".equals(c.values().get("columnOriginal"))
                && "내부지침".equals(c.values().get("basis"))
                && "필수값 확인".equals(c.values().get("description"))
                && "OBJ_SOURCE_1".equals(c.values().get("sourceRuleId")));
    }

    private ProjectContext context() {
        return new ProjectContext(1, 1, 2026, "202607", "MAILDB", "APP", "테스트시스템");
    }

    @SafeVarargs
    private static void sheet(XSSFWorkbook wb, String name, List<String>... rows) {
        var sheet = wb.createSheet(name);
        for (int r = 0; r < rows.length; r++) {
            var row = sheet.createRow(r);
            for (int c = 0; c < rows[r].size(); c++) row.createCell(c).setCellValue(rows[r].get(c));
        }
    }
}
