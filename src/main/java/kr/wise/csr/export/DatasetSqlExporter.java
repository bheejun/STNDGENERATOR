package kr.wise.csr.export;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import kr.wise.csr.normalization.NormalizedRow;
import kr.wise.csr.project.ProjectSnapshot;

public class DatasetSqlExporter {
    private static final List<Dataset> DATASETS = List.of(
            new Dataset("SYSTEM","01-system.sql","WAA_DB_CONN_TRG", List.of("DB_CONN_TRG_ID","DB_CONN_TRG_LNM","DB_CONN_TRG_PNM"), List.of("wdqId","systemName","dbmsOriginal")),
            new Dataset("EXCLUSION","02-exclusion.sql","WAA_STND_EXP_OBJ", List.of("STND_EXP_OBJ_ID","STND_SYS_NM","STND_DBMS_PNM","STND_SCH_PNM","STND_TBL_PNM","STND_COL_PNM","EXP_TYP","TBL_EXP_RSN"), List.of("wdqId","systemName","dbmsOriginal","schemaOriginal","tableOriginal","columnOriginal","exclusionType","reason")),
            new Dataset("VERIFICATION_RULE","03-verification-rule.sql","WAA_VRFC_RULE", List.of("VRFC_ID","VRFC_NM","VRFC_RULE"), List.of("wdqId","ruleName","expression")),
            new Dataset("CODE_RULE","04-code-rule.sql","WAA_CD_RULE", List.of("CD_RULE_ID","CD_RULE_NM","CD_SQL"), List.of("wdqId","ruleName","lookupSql")),
            new Dataset("COLUMN_MAPPING","05-column-mapping.sql","WAA_STND_RULE_SET", List.of("STND_RULE_SET_ID","STND_DBMS_PNM","STND_SCH_PNM","STND_TBL_PNM","STND_COL_PNM","RULE_SET_TYP","VRFC_ID","CD_CLS_ID"), List.of("wdqId","dbmsOriginal","schemaOriginal","tableOriginal","columnOriginal","ruleType","verificationRuleId","codeRuleId")),
            new Dataset("BUSINESS_RULE","06-business-rule.sql","WAA_STND_TBL_PRF", List.of("STND_TBL_PRF_ID","STND_SYS_NM","STND_DBMS_PNM","STND_SCH_PNM","STND_TBL_PNM","BR_NM","PRF_TYP","ANA_SQL"), List.of("wdqId","systemName","dbmsOriginal","schemaOriginal","tableOriginal","ruleName","ruleKind","ruleSql")));

    public List<GeneratedFile> exportDatasetSql(ProjectSnapshot snapshot) {
        requireApproved(snapshot);
        List<GeneratedFile> files = new ArrayList<>();
        for (Dataset dataset : DATASETS) {
            StringBuilder sql = new StringBuilder("-- Common Standard Rule Builder\n-- project: ")
                    .append(snapshot.projectId()).append(" / ").append(snapshot.targetYear()).append("\nSET NAMES utf8mb4;\n\n");
            snapshot.rows().stream().filter(row -> row.dataType().equals(dataset.type()))
                    .filter(row -> row.values().values().stream().noneMatch(v -> "PT01".equalsIgnoreCase(v)||"PT02".equalsIgnoreCase(v)))
                    .sorted(Comparator.comparing(NormalizedRow::logicalKey)).forEach(row -> appendInsert(sql,dataset,row.values()));
            files.add(new GeneratedFile(dataset.fileName(),"text/plain; charset=UTF-8",sql.toString().getBytes(StandardCharsets.UTF_8)));
        }
        return List.copyOf(files);
    }

    static void requireApproved(ProjectSnapshot snapshot) {
        if (snapshot == null || !snapshot.isApprovalCurrent()) throw new IllegalStateException("현재 데이터가 승인된 스냅샷과 일치하지 않습니다");
    }
    private void appendInsert(StringBuilder sql,Dataset d,Map<String,String> values) {
        sql.append("INSERT INTO ").append(d.table()).append(" (").append(String.join(", ",d.columns())).append(") VALUES (");
        for(int i=0;i<d.keys().size();i++){if(i>0)sql.append(", ");sql.append(MariaDbLiteral.of(values.get(d.keys().get(i))));}
        sql.append(");\n");
    }
    record Dataset(String type,String fileName,String table,List<String> columns,List<String> keys) {}
}
