package kr.wise.csr.export;

import static org.assertj.core.api.Assertions.assertThat;
import java.io.ByteArrayInputStream;
import java.util.List;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class StandardWorkbookExporterTest {
    @Test void exportsSummaryAndSixOrderedReadableSheets() throws Exception {
        GeneratedFile file = new StandardWorkbookExporter().exportWorkbook(DatasetSqlExporterTest.approvedSnapshot());
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(file.content()))) {
            assertThat(java.util.stream.IntStream.range(0,wb.getNumberOfSheets()).mapToObj(i->wb.getSheetName(i)).toList())
                    .containsExactly("요약","시스템_ID","제외기준","검증룰","코드룰","컬럼매핑","업무규칙");
            assertThat(wb.getSheet("검증룰").getLastRowNum()).isEqualTo(1);
            assertThat(wb.getSheet("검증룰").getPaneInformation()).isNotNull();
            assertThat(wb.getSheet("검증룰").getCTWorksheet().isSetAutoFilter()).isTrue();
            assertThat(wb.getSheet("요약").getRow(1).getCell(1).getStringCellValue()).isEqualTo("담당자");
            assertThat(List.of(wb.getSheetName(0),wb.getSheetName(1))).noneMatch(n->n.contains("PT01")||n.contains("PT02"));
        }
    }
}
