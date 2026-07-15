package kr.wise.csr.importfile;

import static kr.wise.csr.importfile.WisedqResultWorkbookParser.candidate;
import static kr.wise.csr.importfile.WisedqResultWorkbookParser.key;
import static kr.wise.csr.importfile.WisedqResultWorkbookParser.map;
import static kr.wise.csr.importfile.WisedqResultWorkbookParser.norm;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;

@Component
public class QualityCriteriaWorkbookParser implements WorkbookParser {
    @Override public boolean supports(WorkbookType type) { return type == WorkbookType.WDQ_CRITERIA; }

    @Override
    public ImportBatch parse(Path path, ProjectContext context) {
        List<ImportCandidate> candidates=new ArrayList<>(); List<String> errors=new ArrayList<>(); int pt01=0,pt02=0;
        try(InputStream input=Files.newInputStream(path); Workbook wb=WorkbookFactory.create(input)) {
            CellReader reader=new CellReader(wb);
            candidates.add(candidate("SYSTEM",Long.toString(context.systemId()),map("systemId",context.systemId(),"dbmsOriginal",context.defaultDbms(),"dbmsNormalized",norm(context.defaultDbms()),"schemaOriginal",context.defaultSchema(),"schemaNormalized",norm(context.defaultSchema())),"기관별채번항목",1));
            parse(errors, () -> {
                for(String name:List.of("waa_stnd_exp_obj(테이블)","waa_stnd_exp_obj(컬럼)")) {
                    Sheet sheet=wb.getSheet(name); if(sheet==null) continue;
                    for(var r:reader.rows(sheet,"STND_EXP_OBJ_ID","STND_SYS_NM","STND_DBMS_PNM","STND_SCH_PNM","STND_TBL_PNM")) {
                        Map<String,String> v=physical(r); v.put("wdqId",r.get("STND_EXP_OBJ_ID")); v.put("exclusionType",r.first("EXP_TYP","제외유형")); v.put("reason",r.first("TBL_EXP_RSN","COL_EXP_RSN"));
                        candidates.add(candidate("EXCLUSION",key(v,"dbmsNormalized","schemaNormalized","tableNormalized","columnNormalized","exclusionType"),v,name,r.rowNumber()));
                    }
                }
            });
            parse(errors, () -> { for(var r:reader.rows(wb.getSheet("waa_vrfc_rule(검증룰관리)"),"VRFC_ID","VRFC_NM","VRFC_RULE")){ Map<String,String>v=map("wdqId",r.get("VRFC_ID"),"ruleName",r.get("VRFC_NM"),"expression",r.get("VRFC_RULE")); candidates.add(candidate("VERIFICATION_RULE",key(v,"ruleName","expression"),v,"waa_vrfc_rule(검증룰관리)",r.rowNumber())); }});
            Sheet code=wb.getSheet("waa_cd_rule(코드관리)"); if(code!=null) parse(errors, () -> { for(var r:reader.rows(code,"CD_RULE_ID","CD_RULE_NM","CD_SQL")){ Map<String,String>v=map("wdqId",r.get("CD_RULE_ID"),"ruleName",r.get("CD_RULE_NM"),"lookupSql",r.get("CD_SQL")); candidates.add(candidate("CODE_RULE",key(v,"ruleName","lookupSql"),v,code.getSheetName(),r.rowNumber())); }});
            parse(errors, () -> { Sheet s=wb.getSheet("waa_stnd_rule_set(도메인규칙관리)"); for(var r:reader.rows(s,"STND_RULE_SET_ID","STND_DBMS_PNM","STND_SCH_PNM","STND_TBL_PNM","STND_COL_PNM")){ Map<String,String>v=physical(r); v.put("wdqId",r.get("STND_RULE_SET_ID")); v.put("verificationRuleId",r.get("VRFC_ID")); v.put("codeRuleId",r.first("CD_RULE_ID","CD_CLS_ID")); v.put("ruleType",v.get("codeRuleId").isBlank()?"VERIFICATION":"CODE"); candidates.add(candidate("COLUMN_MAPPING",key(v,"dbmsNormalized","schemaNormalized","tableNormalized","columnNormalized","ruleType"),v,s.getSheetName(),r.rowNumber())); }});
            Sheet business=wb.getSheet("waa_stnd_tbl_prf(업무규칙)"); if(business!=null) {
                try { for(var r:reader.rows(business,"STND_TBL_PRF_ID","STND_DBMS_PNM","STND_SCH_PNM","STND_TBL_PNM","BR_NM","PRF_TYP")){ String type=r.get("PRF_TYP").toUpperCase(Locale.ROOT); if(type.equals("PT01")){pt01++;continue;} if(type.equals("PT02")){pt02++;continue;} Map<String,String>v=physical(r); v.put("wdqId",r.get("STND_TBL_PRF_ID")); v.put("ruleName",r.get("BR_NM")); v.put("ruleKind","BUSINESS"); v.put("ruleSql",r.first("ANA_SQL","CNT_SQL")); candidates.add(candidate("BUSINESS_RULE",key(v,"tableNormalized","ruleName"),v,business.getSheetName(),r.rowNumber())); }} catch(RuntimeException e){errors.add(e.getMessage());}
            }
        } catch(Exception e){ errors.add("품질진단기준 파싱 실패: "+e.getMessage()); }
        return new ImportBatch(WorkbookType.WDQ_CRITERIA,List.copyOf(candidates),pt01,pt02,List.copyOf(errors));
    }
    private Map<String,String> physical(CellReader.SourceRow r){ return map("dbmsOriginal",r.first("STND_DBMS_PNM","DBMS명"),"dbmsNormalized",norm(r.first("STND_DBMS_PNM","DBMS명")),"schemaOriginal",r.first("STND_SCH_PNM","스키마명"),"schemaNormalized",norm(r.first("STND_SCH_PNM","스키마명")),"tableOriginal",r.first("STND_TBL_PNM","테이블명"),"tableNormalized",norm(r.first("STND_TBL_PNM","테이블명")),"columnOriginal",r.first("STND_COL_PNM","컬럼명"),"columnNormalized",norm(r.first("STND_COL_PNM","컬럼명"))); }
    private void parse(List<String> errors,Runnable action){try{action.run();}catch(RuntimeException e){errors.add(e.getMessage());}}
}
