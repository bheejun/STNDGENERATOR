package kr.wise.csr.review;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WisedqReviewEngineTest {
    @TempDir Path tempDirectory;

    @Test
    void reviewsRepresentativeWiseDqReports() throws Exception {
        WisedqReviewEngine engine = new WisedqReviewEngine();
        try (Stream<Path> files = Files.list(Path.of("sample"))) {
            var reports = files.filter(path -> path.getFileName().toString().endsWith("값진단결과보고서.xlsx")).toList();
            assertThat(reports).hasSize(3);
            for (Path report : reports) {
                ReviewResult result = engine.review(report, ReviewContext.empty());
                assertThat(result.criteriaAdoptable()).as(report.toString() + " " + result.issues()).isTrue();
                assertThat(result.metrics().get("targetTableCount")).isPositive();
                assertThat(result.metrics().get("customRuleMappingCount")).isPositive();
                assertThat(result.metrics().get("codeRuleMappingCount")).isPositive();
                assertThat(result.metrics().get("codeDataCount")).isZero();
                assertThat(result.issues()).anyMatch(issue -> issue.code().equals("CODE_DATA_MISSING"));
                assertThat(result.metrics().get("incompleteExecutionCount")).isZero();
                assertThat(result.metrics().get("missingCodeRuleCount")).isZero();
                assertThat(result.metadata().get("schemaName")).isNotBlank();
                if (report.getFileName().toString().contains("지킴e")) {
                    assertThat(result.metadata().get("schemaName")).isEqualTo("NASGRP");
                    assertThat(result.metadata().get("reportedSchemaName")).isEqualTo("ORA9");
                }
            }
        }
    }

    @Test
    void reportsConditionalWhenCodeDomainHasNoVisibleCodeRule() throws Exception {
        Path source;
        try (Stream<Path> files = Files.list(Path.of("sample"))) {
            source = files.filter(path -> path.getFileName().toString().endsWith("값진단결과보고서.xlsx"))
                    .findFirst().orElseThrow();
        }
        Path changed = tempDirectory.resolve("missing-code-rule.xlsx");
        try (var input = Files.newInputStream(source); var workbook = WorkbookFactory.create(input)) {
            var sheet = workbook.sheetIterator().next();
            for (var candidate : workbook) if (candidate.getSheetName().contains("도메인")) sheet = candidate;
            var formatter = new DataFormatter();
            var header = sheet.getRow(0);
            int ruleNameColumn = -1, expressionColumn = -1, indicatorColumn = -1;
            for (var cell : header) {
                String value = formatter.formatCellValue(cell);
                if ("검증룰명".equals(value)) ruleNameColumn = cell.getColumnIndex();
                if ("검증룰".equals(value)) expressionColumn = cell.getColumnIndex();
                if ("품질지표명".equals(value)) indicatorColumn = cell.getColumnIndex();
            }
            boolean changedRow = false;
            for (int rowIndex = 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                var row = sheet.getRow(rowIndex);
                if (row != null && indicatorColumn >= 0
                        && formatter.formatCellValue(row.getCell(indicatorColumn)).contains("코드")) {
                    row.getCell(ruleNameColumn).setBlank();
                    row.getCell(expressionColumn).setBlank();
                    changedRow = true;
                    break;
                }
            }
            assertThat(changedRow).isTrue();
            try (OutputStream output = Files.newOutputStream(changed)) { workbook.write(output); }
        }

        ReviewResult result = new WisedqReviewEngine().review(changed, ReviewContext.empty());

        assertThat(result.verdict()).isEqualTo(ReviewVerdict.CONDITIONAL);
        assertThat(result.criteriaAdoptable()).isTrue();
        assertThat(result.issues()).anyMatch(issue -> issue.code().equals("CODE_RULE_NOT_VISIBLE"));
        assertThat(result.metrics().get("missingCodeRuleCount")).isEqualTo(1);
    }
}
