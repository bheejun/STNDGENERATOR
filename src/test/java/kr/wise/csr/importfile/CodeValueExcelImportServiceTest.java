package kr.wise.csr.importfile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.io.ByteArrayOutputStream;
import java.util.List;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

import kr.wise.csr.project.ProjectCriteriaManagementService;

class CodeValueExcelImportServiceTest {
    @Test
    void importsDataSheetAndReplacesSelectedListCodeValues() throws Exception {
        byte[] content;
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet=workbook.createSheet("DATA");
            var header=sheet.createRow(0);
            header.createCell(0).setCellValue("검증코드명");
            header.createCell(1).setCellValue("CD_ID");
            header.createCell(2).setCellValue("CD_NM");
            var first=sheet.createRow(1);
            first.createCell(0).setCellValue("지역코드");
            first.createCell(1).setCellValue("01");
            first.createCell(2).setCellValue("서울");
            var second=sheet.createRow(2);
            second.createCell(0).setCellValue("지역코드");
            second.createCell(1).setCellValue("02");
            second.createCell(2).setCellValue("부산");
            workbook.write(output);
            content=output.toByteArray();
        }
        ProjectCriteriaManagementService criteria=mock(ProjectCriteriaManagementService.class);
        var service=new CodeValueExcelImportService(criteria);
        var file=new MockMultipartFile("file","codes.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",content);

        var result=service.replace(5,"지역코드",file);

        assertThat(result.importedCount()).isEqualTo(2);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ProjectCriteriaManagementService.CodeValueInput>> values=ArgumentCaptor.forClass(List.class);
        verify(criteria).replaceCodeValues(eq(5L),eq("지역코드"),values.capture());
        assertThat(values.getValue()).extracting(ProjectCriteriaManagementService.CodeValueInput::codeId)
                .containsExactly("01","02");
    }
}
