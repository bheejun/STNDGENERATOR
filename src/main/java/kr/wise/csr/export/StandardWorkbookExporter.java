package kr.wise.csr.export;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import kr.wise.csr.normalization.NormalizedRow;
import kr.wise.csr.project.ProjectSnapshot;

public class StandardWorkbookExporter {
    private static final List<SheetSpec> SHEETS=List.of(
            new SheetSpec("SYSTEM","시스템_ID",List.of("DB_CONN_TRG_ID","DB_CONN_TRG_LNM","DB_CONN_TRG_PNM","DB_SCH_PNM"),List.of("wdqId","systemName","dbmsOriginal","schemaOriginal")),
            new SheetSpec("EXCLUSION","제외기준",List.of("STND_EXP_OBJ_ID","STND_SYS_NM","STND_DBMS_PNM","STND_SCH_PNM","STND_TBL_PNM","STND_COL_PNM","EXP_TYP","TBL_EXP_RSN"),List.of("wdqId","systemName","dbmsOriginal","schemaOriginal","tableOriginal","columnOriginal","exclusionType","reason")),
            new SheetSpec("VERIFICATION_RULE","검증룰",List.of("VRFC_ID","VRFC_NM","VRFC_RULE","DQI_NM"),List.of("wdqId","ruleName","expression","qualityIndicator")),
            new SheetSpec("CODE_RULE","코드룰",List.of("CD_RULE_ID","CD_RULE_NM","CD_SQL"),List.of("wdqId","ruleName","lookupSql")),
            new SheetSpec("COLUMN_MAPPING","컬럼매핑",List.of("STND_RULE_SET_ID","STND_DBMS_PNM","STND_SCH_PNM","STND_TBL_PNM","STND_COL_PNM","RULE_SET_TYP","VRFC_ID","CD_CLS_ID"),List.of("wdqId","dbmsOriginal","schemaOriginal","tableOriginal","columnOriginal","ruleType","verificationRuleId","codeRuleId")),
            new SheetSpec("BUSINESS_RULE","업무규칙",List.of("STND_TBL_PRF_ID","STND_SYS_NM","STND_DBMS_PNM","STND_SCH_PNM","STND_TBL_PNM","BR_NM","PRF_TYP","ANA_SQL"),List.of("wdqId","systemName","dbmsOriginal","schemaOriginal","tableOriginal","ruleName","ruleKind","ruleSql")));

    public GeneratedFile exportWorkbook(ProjectSnapshot snapshot) {
        DatasetSqlExporter.requireApproved(snapshot);
        try(XSSFWorkbook workbook=new XSSFWorkbook(); ByteArrayOutputStream output=new ByteArrayOutputStream()) {
            CellStyle header=headerStyle(workbook);
            summary(workbook,snapshot,header);
            for(SheetSpec spec:SHEETS) dataSheet(workbook,snapshot,spec,header);
            workbook.write(output);
            return new GeneratedFile("common-standard-rules-"+snapshot.targetYear()+".xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",output.toByteArray());
        } catch(IOException e){throw new IllegalStateException("감사용 엑셀 생성 실패",e);}
    }
    private void summary(XSSFWorkbook wb,ProjectSnapshot p,CellStyle header){Sheet s=wb.createSheet("요약");String[][] rows={{"항목","값"},{"승인담당자",p.approvedBy()},{"승인시각",String.valueOf(p.approvedAt())},{"프로젝트ID",Long.toString(p.projectId())},{"적용연도",Integer.toString(p.targetYear())},{"배포년월",p.deploymentYearMonth()},{"스냅샷해시",p.approvedSnapshotHash()},{"PT01 제외",Integer.toString(p.excludedPt01Count())},{"PT02 제외",Integer.toString(p.excludedPt02Count())}};for(int r=0;r<rows.length;r++){Row row=s.createRow(r);for(int c=0;c<2;c++)row.createCell(c).setCellValue(rows[r][c]);}s.getRow(0).forEach(c->c.setCellStyle(header));s.setColumnWidth(0,6000);s.setColumnWidth(1,18000);s.createFreezePane(0,1);s.setAutoFilter(new CellRangeAddress(0,rows.length-1,0,1));}
    private void dataSheet(XSSFWorkbook wb,ProjectSnapshot p,SheetSpec spec,CellStyle header){Sheet s=wb.createSheet(spec.sheet());Row h=s.createRow(0);for(int c=0;c<spec.keys().size();c++){h.createCell(c).setCellValue(spec.headers().get(c));h.getCell(c).setCellStyle(header);}List<NormalizedRow> rows=p.rows().stream().filter(r->r.dataType().equals(spec.type())).filter(r->r.values().values().stream().noneMatch(v->"PT01".equalsIgnoreCase(v)||"PT02".equalsIgnoreCase(v))).sorted(Comparator.comparing(NormalizedRow::logicalKey)).toList();int ri=1;for(NormalizedRow item:rows){Row row=s.createRow(ri++);for(int c=0;c<spec.keys().size();c++){String value=item.values().getOrDefault(spec.keys().get(c),"");if(value.isBlank())row.createCell(c).setBlank();else row.createCell(c).setCellValue(value);}}s.createFreezePane(0,1);s.setAutoFilter(new CellRangeAddress(0,Math.max(0,ri-1),0,spec.keys().size()-1));for(int c=0;c<spec.keys().size();c++){s.autoSizeColumn(c);s.setColumnWidth(c,Math.min(Math.max(s.getColumnWidth(c)+512,3000),16000));}}
    private CellStyle headerStyle(XSSFWorkbook wb){CellStyle style=wb.createCellStyle();style.setFillForegroundColor(IndexedColors.DARK_TEAL.getIndex());style.setFillPattern(FillPatternType.SOLID_FOREGROUND);style.setBorderBottom(BorderStyle.THIN);Font font=wb.createFont();font.setBold(true);font.setColor(IndexedColors.WHITE.getIndex());style.setFont(font);return style;}
    record SheetSpec(String type,String sheet,List<String> headers,List<String> keys){}
}
