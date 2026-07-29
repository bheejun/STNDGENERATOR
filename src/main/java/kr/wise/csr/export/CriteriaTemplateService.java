package kr.wise.csr.export;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import kr.wise.csr.importfile.ProjectImportService.CriteriaCategory;

@Service
public class CriteriaTemplateService {
    public GeneratedFile create(CriteriaCategory category) {
        if (category == CriteriaCategory.INTEGRATED)
            throw new IllegalArgumentException("통합 진단기준은 기존 WDQ 통합 양식을 사용하세요");
        try (XSSFWorkbook workbook = new XSSFWorkbook();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            CellStyle header = headerStyle(workbook);
            if (category == CriteriaCategory.CODE_RULE) {
                addSheet(workbook, "SQL", headers(category), header);
                addSheet(workbook, "DATA", List.of("DB명", "검증코드명", "코드", "코드명", "설명"), header);
            } else {
                addSheet(workbook, category.label(), headers(category), header);
            }
            workbook.write(output);
            return new GeneratedFile(fileName(category),
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", output.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("진단기준 양식을 생성할 수 없습니다", e);
        }
    }

    private List<String> headers(CriteriaCategory category) {
        return switch (category) {
            case EXCLUSION_PATTERN -> List.of("DBMS명", "스키마명", "포함관계", "제외기준룰", "제외사유");
            case TABLE_EXCLUSION -> List.of("DBMS명", "스키마명", "테이블명", "테이블한글명", "제외여부", "제외사유");
            case COLUMN_EXCLUSION -> List.of("DBMS명", "스키마명", "테이블명", "컬럼명", "컬럼한글명", "제외여부", "제외사유");
            case VERIFICATION_RULE -> List.of("검증룰명", "검증유형", "검증룰", "품질지표명", "매칭유형",
                    "오류제외데이터", "오류제외데이터구분자", "검증룰설명");
            case DOMAIN_MAPPING -> List.of("DBMS명", "스키마명", "테이블명", "컬럼명", "컬럼한글명",
                    "데이터타입", "검증룰", "코드분류ID", "컬럼의견");
            case BUSINESS_RULE -> List.of("업무규칙명", "DBMS명", "스키마명", "테이블명", "컬럼명",
                    "품질지표명", "근거규정", "설명", "건수SQL", "분석SQL");
            case CODE_RULE -> List.of("DBMS명", "검증코드명", "코드유형", "코드생성SQL", "설명");
            case INTEGRATED -> throw new IllegalArgumentException("통합 진단기준 템플릿은 지원하지 않습니다");
        };
    }

    private void addSheet(XSSFWorkbook workbook, String name, List<String> headers, CellStyle headerStyle) {
        Sheet sheet = workbook.createSheet(name);
        Row row = sheet.createRow(0);
        row.setHeightInPoints(24);
        for (int i = 0; i < headers.size(); i++) {
            var cell = row.createCell(i);
            cell.setCellValue(headers.get(i));
            cell.setCellStyle(headerStyle);
            sheet.setColumnWidth(i, Math.min(50, Math.max(14, headers.get(i).length() + 5)) * 256);
        }
        sheet.createFreezePane(0, 1);
        sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0, 0, 0, headers.size() - 1));
    }

    private CellStyle headerStyle(XSSFWorkbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setFillForegroundColor(IndexedColors.DARK_GREEN.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        Font font = workbook.createFont();
        font.setBold(true);
        font.setColor(IndexedColors.WHITE.getIndex());
        style.setFont(font);
        return style;
    }

    private String fileName(CriteriaCategory category) {
        return "공통표준_" + category.label().replace(" ", "_") + "_양식.xlsx";
    }
}
