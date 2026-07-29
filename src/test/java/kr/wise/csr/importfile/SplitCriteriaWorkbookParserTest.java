package kr.wise.csr.importfile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SplitCriteriaWorkbookParserTest {
    @TempDir Path directory;

    @Test
    void excludesExactCatalogRulesAndKeepsOnlyAdditionalRules() throws Exception {
        Path file=directory.resolve("verification.xlsx");
        try(XSSFWorkbook workbook=new XSSFWorkbook()){
            sheet(workbook,"검증룰",
                    List.of("검증룰명","검증유형","검증룰","품질지표명"),
                    List.of("[기본]여부(Y,N)","REGEX","Y|N","여부"),
                    List.of("[기본]여부(Y,N )","REGEX","Y|N| ","여부"));
            try(OutputStream output=Files.newOutputStream(file)){workbook.write(output);}
        }
        DefaultVerificationRuleCatalog catalog=mock(DefaultVerificationRuleCatalog.class);
        when(catalog.findByName("[기본]여부(Y,N)")).thenReturn(Optional.of(
                new DefaultVerificationRuleCatalog.DefaultRule("STAT_00000000001","REGEX",
                        "[기본]여부(Y,N)","Y|N","","","","")));
        when(catalog.findByName("[기본]여부(Y,N )")).thenReturn(Optional.empty());

        ImportBatch batch=new SplitCriteriaWorkbookParser(catalog).parse(file,
                new ProjectContext(1,1,2026,"202607","DB","APP","시스템"),
                WorkbookType.CRITERIA_VERIFICATION_RULE);

        assertThat(batch.errors()).isEmpty();
        assertThat(batch.candidates()).singleElement().satisfies(candidate -> assertThat(candidate.values())
                .containsEntry("ruleName","[기본]여부(Y,N )")
                .containsEntry("ruleOrigin","ADDITIONAL_UPLOADED"));
    }

    @Test
    void parsesCodeRulesAndCodeListData() throws Exception {
        Path file=directory.resolve("code.xlsx");
        try(XSSFWorkbook workbook=new XSSFWorkbook()){
            sheet(workbook,"SQL",
                    List.of("DBMS명","검증코드명","코드유형","코드생성SQL","설명"),
                    List.of("지킴eDB","상태코드","목록성코드","","상태"));
            sheet(workbook,"DATA",
                    List.of("DB명","검증코드명","코드","코드명","설명"),
                    List.of("지킴eDB","상태코드","A","정상",""),
                    List.of("지킴eDB","상태코드","E","오류",""));
            try(OutputStream output=Files.newOutputStream(file)){workbook.write(output);}
        }
        ProjectContext context=new ProjectContext(1,1,2026,"202607","지킴eDB","NASGRP","지킴e");

        ImportBatch batch=new SplitCriteriaWorkbookParser().parse(file,context,WorkbookType.CRITERIA_CODE_RULE);

        assertThat(batch.errors()).isEmpty();
        assertThat(batch.candidates()).filteredOn(candidate->candidate.dataType().equals("CODE_RULE")).hasSize(1);
        assertThat(batch.candidates()).filteredOn(candidate->candidate.dataType().equals("CODE_VALUE")).hasSize(2);
        assertThat(batch.candidates()).allMatch(candidate->
                "[2026표준시스템DB 지킴e] 상태코드".equals(candidate.values().get("ruleName")));
    }

    @Test
    void parsesExclusionPatternWorkbook() throws Exception {
        Path file=directory.resolve("exclusion-pattern.xlsx");
        try(XSSFWorkbook workbook=new XSSFWorkbook()){
            sheet(workbook,"제외기준",
                    List.of("DBMS명(*)","스키마명(*)","포함관계(*)","제외기준룰(*)","제외사유(*)"),
                    List.of("kras db","MLTM","뒤","_TMP","임시테이블"));
            try(OutputStream output=Files.newOutputStream(file)){workbook.write(output);}
        }
        ProjectContext context=new ProjectContext(1,1,2026,"202607","kras db","MLTM","부동산종합공부");

        ImportBatch batch=new SplitCriteriaWorkbookParser().parse(
                file,context,WorkbookType.CRITERIA_EXCLUSION_PATTERN);

        assertThat(batch.errors()).isEmpty();
        assertThat(batch.candidates()).singleElement().satisfies(candidate -> {
            assertThat(candidate.dataType()).isEqualTo("EXCLUSION_PATTERN");
            assertThat(candidate.values()).containsEntry("relation","B")
                    .containsEntry("pattern","TMP").containsEntry("reason","임시테이블");
        });
    }

    @Test
    void preservesTargetAndExcludedTablesForDirectUpload() throws Exception {
        Path file=directory.resolve("table-exclusion.xlsx");
        try(XSSFWorkbook workbook=new XSSFWorkbook()){
            sheet(workbook,"table",
                    List.of("DBMS명","스키마명","테이블명","테이블한글명","제외여부","제외사유"),
                    List.of("새올행정시스템DB","NTIS","TARGET_TBL","","N",""),
                    List.of("새올행정시스템DB","NTIS","EXCLUDED_TBL","","Y","로그기록테이블"));
            try(OutputStream output=Files.newOutputStream(file)){workbook.write(output);}
        }
        ProjectContext context=new ProjectContext(1,1,2026,"202607","새올행정시스템DB","NTIS","새올행정");

        ImportBatch batch=new SplitCriteriaWorkbookParser().parse(
                file,context,WorkbookType.CRITERIA_TABLE_EXCLUSION);

        assertThat(batch.errors()).isEmpty();
        assertThat(batch.candidates()).hasSize(2)
                .allMatch(candidate -> candidate.dataType().equals("EXCLUSION"));
        assertThat(batch.candidates()).anySatisfy(candidate -> assertThat(candidate.values())
                .containsEntry("tableOriginal","TARGET_TBL").containsEntry("expYn","N"));
        assertThat(batch.candidates()).anySatisfy(candidate -> assertThat(candidate.values())
                .containsEntry("tableOriginal","EXCLUDED_TBL").containsEntry("expYn","Y")
                .containsEntry("reason","로그기록테이블"));
    }

    @Test
    void selectsLastTargetColumnForBusinessRule() throws Exception {
        Path file=directory.resolve("business.xlsx");
        try(XSSFWorkbook workbook=new XSSFWorkbook()){
            sheet(workbook,"업무규칙",
                    List.of("업무규칙명","DBMS명","스키마명","테이블명","컬럼명","건수SQL","분석SQL"),
                    List.of("기간 검증","새올행정시스템DB","NTIS","TARGET_TBL","START_YMD\nEND_YMD",
                            "SELECT COUNT(*) FROM TARGET_TBL","SELECT * FROM TARGET_TBL"));
            try(OutputStream output=Files.newOutputStream(file)){workbook.write(output);}
        }
        ProjectContext context=new ProjectContext(1,1,2026,"202607","새올행정시스템DB","NTIS","새올행정");

        ImportBatch batch=new SplitCriteriaWorkbookParser().parse(
                file,context,WorkbookType.CRITERIA_BUSINESS_RULE);

        assertThat(batch.errors()).isEmpty();
        assertThat(batch.candidates()).singleElement().satisfies(candidate -> assertThat(candidate.values())
                .containsEntry("columnOriginal","END_YMD")
                .containsEntry("columnNormalized","END_YMD"));
    }

    @SafeVarargs
    private static void sheet(XSSFWorkbook workbook,String name,List<String>... rows){
        var sheet=workbook.createSheet(name);
        for(int r=0;r<rows.length;r++){
            var row=sheet.createRow(r);
            for(int c=0;c<rows[r].size();c++) row.createCell(c).setCellValue(rows[r].get(c));
        }
    }
}
