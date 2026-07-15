package kr.wise.csr.importfile;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QualityCriteriaWorkbookParserTest {
    @TempDir Path directory;

    @Test
    void extractsAllCriteriaDatasetsAndBusinessOnlyProfiles() throws Exception {
        Path file = criteria(false);
        ImportBatch batch = new QualityCriteriaWorkbookParser().parse(file, context());
        assertThat(batch.errors()).isEmpty();
        assertThat(batch.candidates()).extracting(ImportCandidate::dataType)
                .contains("SYSTEM", "EXCLUSION", "VERIFICATION_RULE", "CODE_RULE", "COLUMN_MAPPING", "BUSINESS_RULE");
        assertThat(batch.excludedPt01Count()).isEqualTo(1);
        assertThat(batch.excludedPt02Count()).isEqualTo(1);
    }

    @Test
    void reportsSheetAndMissingColumns() throws Exception {
        Path file = criteria(true);
        ImportBatch batch = new QualityCriteriaWorkbookParser().parse(file, context());
        assertThat(batch.errors()).anyMatch(error -> error.contains("waa_vrfc_rule") && error.contains("VRFC_NM"));
    }

    private Path criteria(boolean brokenVerification) throws Exception {
        Path file = directory.resolve(brokenVerification ? "broken.xlsx" : "criteria.xlsx");
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            sheet(wb, "기관별채번항목", List.of("시스템명"), List.of("합성시스템"));
            sheet(wb, "waa_stnd_exp_obj(테이블)",
                    List.of("STND_EXP_OBJ_ID", "STND_SYS_NM", "STND_DBMS_PNM", "STND_SCH_PNM", "STND_TBL_PNM", "EXP_TYP", "TBL_EXP_RSN"),
                    List.of("STNDEXP_0000001", "합성시스템", "MAILDB", "APP", "TMP", "TBL", "임시"));
            sheet(wb, "waa_vrfc_rule(검증룰관리)",
                    brokenVerification ? List.of("VRFC_ID") : List.of("VRFC_ID", "VRFC_NM", "VRFC_RULE"),
                    brokenVerification ? List.of("STNDRULE_000001") : List.of("STNDRULE_000001", "여부", "Y,N"));
            sheet(wb, "waa_cd_rule(코드관리)", List.of("CD_RULE_ID", "CD_RULE_NM", "CD_SQL"),
                    List.of("STNDCD_00000001", "코드", "select code from codes"));
            sheet(wb, "waa_stnd_rule_set(도메인규칙관리)",
                    List.of("STND_RULE_SET_ID", "STND_DBMS_PNM", "STND_SCH_PNM", "STND_TBL_PNM", "STND_COL_PNM", "VRFC_ID"),
                    List.of("STND_0000000001", "MAILDB", "APP", "TB", "COL", "STNDRULE_000001"));
            sheet(wb, "waa_stnd_tbl_prf(업무규칙)",
                    List.of("STND_TBL_PRF_ID", "STND_DBMS_PNM", "STND_SCH_PNM", "STND_TBL_PNM", "BR_NM", "PRF_TYP", "ANA_SQL"),
                    List.of("STNDPRF_0000001", "MAILDB", "APP", "TB", "업무", "BUSINESS", "select 1"),
                    List.of("STNDPRF_0000002", "MAILDB", "APP", "TB", "참조", "PT01", "select 1"),
                    List.of("STNDPRF_0000003", "MAILDB", "APP", "TB", "중복", "PT02", "select 1"));
            try (OutputStream out = Files.newOutputStream(file)) { wb.write(out); }
        }
        return file;
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
