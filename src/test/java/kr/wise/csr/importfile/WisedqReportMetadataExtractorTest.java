package kr.wise.csr.importfile;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WisedqReportMetadataExtractorTest {
    @TempDir Path directory;

    @Test
    void extractsMetadataByLabel() throws Exception {
        Path file = directory.resolve("result.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("(진단결과)값진단결과");
            sheet.createRow(0).createCell(1).setCellValue("WISE DQ 값진단 결과 보고서 V9.0");
            sheet.createRow(1).createCell(5).setCellValue("출력일 : 2026-07-21 16:22:48");
            row(sheet, 3, "기관명", "경상남도 진주시", null, "DBMS서비스(스키마)명", "POSTMOA");
            row(sheet, 4, "정보시스템명", "우편모아시스템", null, "DBMS버전", "9");
            row(sheet, 5, "DBMS명", "진주시 우편모아DB", null, "IP", "112.3.132.205");
            row(sheet, 6, "DBMS종류", "ORACLE", null, "Port", "1534");
            try (OutputStream output = Files.newOutputStream(file)) { workbook.write(output); }
        }

        WisedqReportMetadata metadata = new WisedqReportMetadataExtractor().extract(file);

        assertThat(metadata).isEqualTo(new WisedqReportMetadata("경상남도 진주시", "우편모아시스템",
                "진주시 우편모아DB", "ORACLE", "POSTMOA", "9", "112.3.132.205", "1534", "9.0",
                "2026-07-21 16:22:48"));
    }

    private static void row(org.apache.poi.ss.usermodel.Sheet sheet, int index, String... values) {
        var row = sheet.createRow(index);
        for (int column = 0; column < values.length; column++) {
            if (values[column] != null) row.createCell(column + 1).setCellValue(values[column]);
        }
    }
}
