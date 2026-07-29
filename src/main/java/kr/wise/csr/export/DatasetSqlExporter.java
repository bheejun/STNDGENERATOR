package kr.wise.csr.export;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

import org.springframework.stereotype.Component;

import kr.wise.csr.normalization.NormalizedRow;
import kr.wise.csr.project.DbConnectionTarget;
import kr.wise.csr.project.ProjectSnapshot;

@Component
public class DatasetSqlExporter {
    private static final String SCHEMA = "dqlite.";
    private static final String NULL = "NULL";
    private static final String NOW = "NOW()";
    private static final String END_DATE = "'9999-12-31 00:00:00.000'";

    public List<GeneratedFile> exportDatasetSql(ProjectSnapshot snapshot) {
        ExportContext context = context(snapshot);
        return List.of(
                file("01-db-connection.sql", replacementPreamble(snapshot, context)),
                dataset(snapshot, context, "EXCLUSION", "02-exclusion.sql", "WAA_STND_EXP_OBJ",
                        "STND_EXP_OBJ_ID", this::exclusion),
                dataset(snapshot, context, "VERIFICATION_RULE", "03-verification-rule.sql", "WAA_VRFC_RULE",
                        "VRFC_ID", this::verification),
                dataset(snapshot, context, "CODE_RULE", "04-code-rule.sql", "WAA_CD_RULE",
                        "CD_RULE_ID", this::codeRule),
                codeValues(snapshot, context),
                dataset(snapshot, context, "COLUMN_MAPPING", "06-column-mapping.sql", "WAA_STND_RULE_SET",
                        "STND_RULE_SET_ID", this::mapping),
                dataset(snapshot, context, "BUSINESS_RULE", "07-business-rule.sql", "WAA_STND_TBL_PRF",
                        "STND_TBL_PRF_ID", this::business));
    }

    private GeneratedFile dataset(ProjectSnapshot snapshot, ExportContext context, String type, String fileName,
            String table, String idColumn, BiConsumer<StringBuilder, RowContext> writer) {
        List<NormalizedRow> rows = rows(snapshot, type);
        if ("EXCLUSION".equals(type)) {
            List<NormalizedRow> combined = new ArrayList<>(rows);
            combined.addAll(rows(snapshot, "EXCLUSION_PATTERN"));
            rows = combined.stream().sorted(Comparator.comparing(NormalizedRow::logicalKey)).toList();
        }
        if ("VERIFICATION_RULE".equals(type))
            rows = rows.stream()
                    .filter(row -> !isDefaultRuleId(row.values().get("wdqId")))
                    .toList();
        StringBuilder sql = header(snapshot).append("-- 기존 연결정보는 변경하지 않고 생성 대상 ID만 교체합니다.\n");
        if ("COLUMN_MAPPING".equals(type))
            appendDeleteNamespace(sql, snapshot, "WAA_STND_RULE_SET", "STND_RULE_SET_ID", "STND_", "컬럼 매핑");
        else if ("EXCLUSION".equals(type)) {
            appendDeleteDerivedExclusions(sql, context);
            appendDeleteNamespace(sql, snapshot, "WAA_STND_EXP_OBJ", "STND_EXP_OBJ_ID", "STNDEXP_", "제외대상");
        } else appendDelete(sql, table, idColumn, rows);
        for (NormalizedRow row : rows) writer.accept(sql, new RowContext(row.values(), context));
        sql.append("\nCOMMIT;\n");
        return file(fileName, sql);
    }

    private StringBuilder header(ProjectSnapshot snapshot) {
        return new StringBuilder("-- WDQ 9.0 / 9.1 / 9.2 SQL-only patch\n-- project: ")
                .append(snapshot.projectId()).append(" / target: ").append(snapshot.targetYear())
                .append(" / deploy: ").append(snapshot.deploymentYearMonth())
                .append("\nSET NAMES utf8mb4;\nUSE `dqlite`;\n\n");
    }

    private StringBuilder connectionCheck(ProjectSnapshot snapshot, ExportContext context) {
        if (context.connectionId().isBlank())
            throw new IllegalStateException("WDQ DB 연결 ID가 없습니다. 결과보고서를 먼저 업로드해야 합니다");
        return header(snapshot)
                .append("-- DB 계정/비밀번호/URL 등 기존 연결정보는 삭제하거나 수정하지 않습니다.\n")
                .append("SELECT DB_CONN_TRG_ID, DB_CONN_TRG_PNM, DBMS_TYP_CD, DB_LNK_STS\n")
                .append("  FROM WAA_DB_CONN_TRG\n WHERE DB_CONN_TRG_ID = ")
                .append(MariaDbLiteral.of(context.connectionId())).append("\n")
                .append("   AND REG_TYP_CD IN ('C','U')\n")
                .append("   AND EXP_DTM = STR_TO_DATE('99991231','%Y%m%d');\n");
    }

    private StringBuilder connectionUpsert(ProjectSnapshot snapshot, ExportContext context) {
        if (context.connectionId().isBlank())
            throw new IllegalStateException("WDQ DB 연결 ID가 없습니다. 결과보고서를 먼저 업로드해야 합니다");
        DbConnectionTarget target=snapshot.connectionTarget();
        String physical=first(context.dbmsName(),snapshot.dbmsPhysicalName(),target.logicalName());
        String logical=physical;
        return header(snapshot)
                .append("-- 진단대상 DB 연결정보가 없을 때만 등록합니다.\n")
                .append("INSERT INTO dqlite.WAA_DB_CONN_TRG (DB_CONN_TRG_ID, EXP_DTM, STR_DTM, DB_CONN_TRG_PNM, ")
                .append("DB_CONN_TRG_LNM, DBMS_TYP_CD, DBMS_VERS_CD, CONN_TRG_DB_LNK_CHRW, CONN_TRG_LNK_URL, ")
                .append("CONN_TRG_DRVR_NM, DB_CONN_AC_ID, DB_CONN_AC_PWD, DB_LNK_STS, CRGP_NM, CRGP_CNTEL, ")
                .append("OBJ_DESCN, OBJ_VERS, REG_TYP_CD, WRIT_DTM, WRIT_USER_ID, META_MNG_YN, INFO_SYS_CD, ")
                .append("INFO_SYS_NM, ORG_NM, ADDIT_MNG_NO)\nSELECT DISTINCT ")
                .append(q(context.connectionId())).append(", ").append(END_DATE).append(", ").append(NOW).append(", ")
                .append(q(physical)).append(", ").append(q(logical)).append(", ").append(q(first(snapshot.dbmsType(),"ORA"))).append(", ")
                .append("NULL, NULL, ")
                .append(q(connectionUrl(null,snapshot.dbmsType()))).append(", ")
                .append(q(driverName(null,snapshot.dbmsType()))).append(", ")
                .append(q("입력해주세요.")).append(", ").append(q("입력해주세요."))
                .append(", NULL, NULL, NULL, NULL, 1, 'C', NULL, ")
                .append("'admin', 'N', NULL, ").append(q("입력해주세요.")).append(", ")
                .append(q("입력해주세요.")).append(", '0'\n")
                .append("  FROM dual\n WHERE NOT EXISTS (SELECT 1 FROM dqlite.WAA_DB_CONN_TRG\n")
                .append("        WHERE DB_CONN_TRG_ID=").append(q(context.connectionId()))
                .append(" AND REG_TYP_CD IN ('C','U')\n")
                .append("          AND EXP_DTM=STR_TO_DATE('99991231','%Y%m%d'));\n\nCOMMIT;\n\n")
                .append("-- Existing common-standard connections must use the same DB name as the rule set.\n")
                .append("UPDATE dqlite.WAA_DB_CONN_TRG\n")
                .append("   SET DB_CONN_TRG_PNM=").append(q(physical))
                .append(", DB_CONN_TRG_LNM=").append(q(logical)).append("\n")
                .append(" WHERE DB_CONN_TRG_ID=").append(q(context.connectionId())).append("\n")
                .append("   AND DB_CONN_TRG_ID LIKE 'STNDDB%'\n")
                .append("   AND REG_TYP_CD IN ('C','U')\n")
                .append("   AND EXP_DTM=STR_TO_DATE('99991231','%Y%m%d');\n\nCOMMIT;\n\n")
                .append("-- 등록 결과 확인\n")
                .append("SELECT DB_CONN_TRG_ID, DB_CONN_TRG_PNM, DBMS_TYP_CD, DB_LNK_STS\n")
                .append("  FROM dqlite.WAA_DB_CONN_TRG\n WHERE DB_CONN_TRG_ID = ")
                .append(MariaDbLiteral.of(context.connectionId())).append("\n")
                .append("   AND REG_TYP_CD IN ('C','U')\n")
                .append("   AND EXP_DTM = STR_TO_DATE('99991231','%Y%m%d');\n");
    }

    private StringBuilder replacementPreamble(ProjectSnapshot snapshot, ExportContext context) {
        StringBuilder sql = header(snapshot);
        appendCompatibilityDdl(sql);
        appendFullReplacementCleanup(sql, snapshot, context);
        String connectionSql = connectionUpsert(snapshot, context).toString();
        int body = connectionSql.indexOf("\n\n", connectionSql.indexOf("USE `dqlite`;"));
        sql.append(connectionSql.substring(body + 2));
        return sql;
    }

    private String connectionUrl(String value,String dbmsType) {
        if (!placeholder(value)) return value.trim();
        return switch (first(dbmsType,"ORA").toUpperCase(Locale.ROOT)) {
            case "ORA" -> "jdbc:oracle:thin:@아이피:포트:SID";
            case "MRA" -> "jdbc:mariadb://아이피:포트/DB명";
            case "MYS" -> "jdbc:mysql://아이피:포트/DB명";
            case "POS" -> "jdbc:postgresql://아이피:포트/DB명";
            case "MSQ" -> "jdbc:sqlserver://아이피:포트;databaseName=DB명";
            case "TIB" -> "jdbc:tibero:thin:@아이피:포트:SID";
            default -> "jdbc:DBMS://아이피:포트/DB명";
        };
    }

    private String driverName(String value,String dbmsType) {
        if (!placeholder(value)) return value.trim();
        return switch (first(dbmsType,"ORA").toUpperCase(Locale.ROOT)) {
            case "ORA" -> "oracle.jdbc.driver.OracleDriver";
            case "MRA" -> "org.mariadb.jdbc.Driver";
            case "MYS" -> "com.mysql.cj.jdbc.Driver";
            case "POS" -> "org.postgresql.Driver";
            case "MSQ" -> "com.microsoft.sqlserver.jdbc.SQLServerDriver";
            case "TIB" -> "com.tmax.tibero.jdbc.TbDriver";
            default -> "JDBC 드라이버 클래스명";
        };
    }

    private boolean placeholder(String value) {
        return value==null||value.isBlank()||value.trim().equals("입력해주세요")
                ||value.trim().equals("입력해주세요.");
    }

    private void exclusion(StringBuilder sql, RowContext row) {
        Map<String, String> v = row.values();
        boolean pattern = "EXCLUSION_PATTERN".equals(v.get("dataType")) || !first(v.get("pattern"), "").isBlank();
        if (pattern) {
            insert(sql, "WAA_STND_EXP_OBJ", List.of(
                    "STND_EXP_OBJ_ID", "STND_SYS_NM", "STND_DBMS_PNM", "STND_SCH_PNM", "STND_TBL_PNM",
                    "STND_COL_PNM", "EXP_TYP", "TBL_EXP_RSN", "TBL_ADD_CND", "TBL_ADD_CND_RSN",
                    "TBL_EXP_STND_RULE", "INCLD_REL", "TBL_EXP_STND_RULE_RSN", "COL_EXP_RSN", "OPEN_YM", "EXP_YN"),
                    List.of(q(v, "wdqId"), q(row.context().systemName()), q(row.context().dbmsName()),
                            physical(v, "schemaOriginal", row.context().schema()), NULL, NULL, q("EXR"), NULL,
                            NULL, NULL, q(v, "pattern"), optional(v.get("relation")), optional(v.get("reason")),
                            NULL, q(row.context().openYm()), q("Y")));
            return;
        }
        boolean table = "TBL".equalsIgnoreCase(v.get("exclusionType"));
        insert(sql, "WAA_STND_EXP_OBJ", List.of(
                "STND_EXP_OBJ_ID", "STND_SYS_NM", "STND_DBMS_PNM", "STND_SCH_PNM", "STND_TBL_PNM",
                "STND_COL_PNM", "EXP_TYP", "TBL_EXP_RSN", "TBL_ADD_CND", "TBL_ADD_CND_RSN",
                "TBL_EXP_STND_RULE", "INCLD_REL", "TBL_EXP_STND_RULE_RSN", "COL_EXP_RSN", "OPEN_YM", "EXP_YN"),
                List.of(q(v, "wdqId"), q(row.context().systemName()), q(row.context().dbmsName()),
                        physical(v, "schemaOriginal", row.context().schema()), optional(v.get("tableOriginal")),
                        optional(v.get("columnOriginal")), q(v, "exclusionType"), table ? optional(v.get("reason")) : NULL,
                        NULL, NULL, NULL, NULL, NULL, table ? NULL : optional(v.get("reason")),
                        q(row.context().openYm()), q(first(v.get("expYn"), "Y"))));
    }

    private void verification(StringBuilder sql, RowContext row) {
        Map<String, String> v = row.values();
        insert(sql, "WAA_VRFC_RULE", List.of(
                "VRFC_ID", "VRFC_TYP", "VRFC_NM", "VRFC_RULE", "VRFC_DESCN", "EXP_DTM", "STR_DTM",
                "OBJ_DESCN", "OBJ_VERS", "REG_TYP_CD", "WRIT_DTM", "WRIT_USER_ID", "DQI_ID", "MNG_USER_ID",
                "ERR_EXP_DATA", "ERR_EXP_DATA_SEP", "MTCH_TYP"),
                List.of(q(v, "wdqId"), q(verificationType(v.get("ruleType"))), q(v, "ruleName"), q(v, "expression"),
                        optional(v.get("description")), END_DATE, NOW, NULL, "1", q("C"), NOW, q("admin"),
                        qualityIndicatorId(v.get("qualityIndicator")), NULL,
                        optional(v.get("excludedValues")), optional(v.get("excludedValueSeparator")),
                        q(matchType(v.get("matchType")))));
    }

    private String qualityIndicatorId(String qualityIndicator) {
        if (qualityIndicator == null || qualityIndicator.isBlank()) return NULL;
        return "(SELECT DQI_ID FROM dqlite.WAM_DQI"
                + " WHERE DQI_LNM=" + q(qualityIndicator.trim())
                + " AND COALESCE(REG_TYP_CD,'C')<>'D'"
                + " ORDER BY DQI_LVL DESC LIMIT 1)";
    }

    private void codeRule(StringBuilder sql, RowContext row) {
        Map<String, String> v = row.values();
        insert(sql, "WAA_CD_RULE", List.of(
                "CD_RULE_ID", "DB_CONN_TRG_ID", "CD_RULE_NM", "CD_SQL", "CD_CLS_COL_NM", "CD_CLS_NM_COL_NM",
                "CD_ID_COL_NM", "CD_NM_COL_NM", "OBJ_DESCN", "WRIT_DTM", "CD_TYP_CD", "DB_SCH_ID", "EXL_YN",
                "RQST_DTM", "RQST_USER_ID"),
                List.of(q(v, "wdqId"), q(row.context().connectionId()), q(v, "ruleName"), optional(v.get("lookupSql")),
                        NULL, NULL, NULL, NULL, optional(v.get("description")), NOW, q(codeType(v.get("codeType"))),
                        NULL, q(codeExclusiveYn(v, row.context())), NOW, q("admin")));
    }

    private GeneratedFile codeValues(ProjectSnapshot snapshot, ExportContext context) {
        List<NormalizedRow> rows=rows(snapshot,"CODE_VALUE");
        Map<String,String> codeTypes=new LinkedHashMap<>();
        for(NormalizedRow rule:rows(snapshot,"CODE_RULE")){
            codeTypes.put(normalize(rule.values().get("ruleName")),codeType(rule.values().get("codeType")));
        }
        StringBuilder sql=header(snapshot).append("-- 목록성 코드 데이터\n");
        List<String> ruleIds=rows.stream().map(NormalizedRow::values)
                .map(v->context.ruleIds().get(normalize(v.get("ruleName"))))
                .filter(id->id!=null&&!id.isBlank()).distinct().sorted().toList();
        appendDeleteIds(sql,"WAA_CD_LIST","CD_RULE_ID",ruleIds);
        for(NormalizedRow row:rows){
            Map<String,String> v=row.values();
            if (!"LC".equals(requiredCodeType(codeTypes,v.get("ruleName"))))
                throw new IllegalStateException("코드 데이터는 목록성코드(LC)에만 등록할 수 있습니다: "+v.get("ruleName"));
            String ruleId=context.ruleIds().get(normalize(v.get("ruleName")));
            if(ruleId==null||ruleId.isBlank())
                throw new IllegalStateException("코드값에 대응하는 코드 규칙 ID가 없습니다: "+v.get("ruleName"));
            insert(sql,"WAA_CD_LIST",List.of(
                    "DB_CONN_TRG_ID","CD_CLS","CD_CLS_NM","CD_ID","CD_NM","CD_RULE_ID","CD_RULE_NM","CD_TYP_CD","EXL_YN"),
                    List.of(q(context.connectionId()),NULL,NULL,q(v,"codeId"),q(v,"codeName"),q(ruleId),
                            q(v,"ruleName"),q("LC"),q("Y")));
        }
        sql.append("\nCOMMIT;\n");
        return file("05-code-list.sql",sql);
    }

    private void mapping(StringBuilder sql, RowContext row) {
        Map<String, String> v = row.values();
        boolean codeMapping = "CODE".equalsIgnoreCase(v.get("ruleType"));
        String ruleId = row.context().ruleIds().get(normalize(v.get("ruleName")));
        if (ruleId == null) ruleId = row.context().ruleIds().get(normalize(v.get("verificationRuleId")));
        if (ruleId == null) ruleId = row.context().ruleIds().get(normalize(v.get("codeRuleId")));
        if (ruleId == null && isDefaultRuleId(v.get("verificationRuleId"))) ruleId = v.get("verificationRuleId");
        if (ruleId == null || ruleId.isBlank())
            throw new IllegalStateException("컬럼 매핑의 룰 ID를 찾을 수 없습니다: " + v.getOrDefault("ruleName", v.get("codeRuleId")));
        String codeClassId = first(v.get("codeClassId"),
                codeMapping && !isCodeRuleId(v.get("codeRuleId")) ? v.get("codeRuleId") : null);
        insert(sql, "WAA_STND_RULE_SET", List.of(
                "STND_RULE_SET_ID", "STND_SYS_NM", "STND_DBMS_PNM", "STND_DBMS_LNM", "STND_SCH_PNM",
                "STND_SCH_LNM", "STND_TBL_PNM", "STND_TBL_LNM", "STND_COL_PNM", "STND_COL_LNM",
                "RULE_SET_TYP", "VRFC_ID", "CD_CLS_ID", "OPEN_YM", "COL_RMK"),
                List.of(q(v, "wdqId"), q(row.context().systemName()), q(row.context().dbmsName()),
                        q(row.context().dbmsName()), physical(v, "schemaOriginal", row.context().schema()),
                        physical(v, "schemaOriginal", row.context().schema()), q(v, "tableOriginal"), q(v, "tableOriginal"),
                        q(v, "columnOriginal"), optional(first(v.get("columnLogicalName"), v.get("columnOriginal"))),
                        q(codeMapping ? "CD" : "VRFC"), q(ruleId), codeMapping ? optional(codeClassId) : NULL,
                        q(row.context().openYm()), optional(v.get("comment"))));
    }

    private boolean isCodeRuleId(String ruleId) {
        return ruleId != null && ruleId.trim().toUpperCase(Locale.ROOT).startsWith("STNDCD_");
    }

    private boolean isDefaultRuleId(String ruleId) {
        if (ruleId == null) return false;
        String normalized = ruleId.trim().toUpperCase(Locale.ROOT);
        return normalized.startsWith("STAT_") || normalized.startsWith("VRF1_");
    }

    private void business(StringBuilder sql, RowContext row) {
        Map<String, String> v = row.values();
        String column = v.get("columnOriginal");
        String analysisSql = v.get("ruleSql");
        String errorCountSql = analysisSql == null || analysisSql.isBlank() ? null
                : "SELECT COUNT(1) FROM (\n" + analysisSql + "\n) ERR_CNT";
        insert(sql, "WAA_STND_TBL_PRF", List.of(
                "STND_TBL_PRF_ID", "STND_SYS_NM", "STND_DBMS_PNM", "STND_DBMS_LNM", "STND_SCH_PNM",
                "STND_SCH_LNM", "STND_TBL_PNM", "STND_TBL_LNM", "STND_COL_PNM", "STND_COL_LNM",
                "STND_TGT_TBL_PNM", "STND_TGT_TBL_LNM", "STND_TGT_COL_PNM", "STND_TGT_COL_LNM", "COL_SNO",
                "PRF_TYP", "BR_NM", "CNT_SQL", "ERR_CNT_SQL", "ANA_SQL", "DQI_ID", "OBJ_DESCN", "BASIS_RGLTN", "OPEN_YM"),
                List.of(q(v, "wdqId"), q(row.context().systemName()), q(row.context().dbmsName()),
                        q(row.context().dbmsName()), physical(v, "schemaOriginal", row.context().schema()),
                        physical(v, "schemaOriginal", row.context().schema()), q(v, "tableOriginal"), q(v, "tableOriginal"),
                        optional(column), optional(column), NULL, NULL, NULL, NULL, "1", q("BR"), q(v, "ruleName"),
                        optional(v.get("countSql")), optional(errorCountSql), optional(analysisSql),
                        qualityIndicatorId(v.get("qualityIndicator")),
                        optional(v.get("description")), optional(v.get("basis")), q(row.context().openYm())));
    }

    private ExportContext context(ProjectSnapshot snapshot) {
        Map<String, String> system = rows(snapshot, "SYSTEM").stream().findFirst().map(NormalizedRow::values).orElse(Map.of());
        String connectionId = first(snapshot.connectionWdqId(), system.get("wdqId"));
        String systemName = first(snapshot.systemName(), system.get("systemName"));
        String dbmsName = first(snapshot.dbmsPhysicalName(), system.get("dbmsOriginal"),
                predominantValue(snapshot, "dbmsOriginal"));
        if (systemName.isBlank()) throw new IllegalStateException("시스템명이 없습니다");
        Map<String, String> ruleIds = new LinkedHashMap<>();
        for (NormalizedRow row : snapshot.rows()) {
            if (!row.dataType().equals("VERIFICATION_RULE") && !row.dataType().equals("CODE_RULE")) continue;
            String id = row.values().get("wdqId");
            if (id == null || id.isBlank()) continue;
            ruleIds.put(normalize(id), id);
            ruleIds.put(normalize(row.values().get("ruleName")), id);
            ruleIds.put(normalize(row.logicalKey()), id);
        }
        Set<String> rulesWithCodeValues=snapshot.rows().stream()
                .filter(row -> row.dataType().equals("CODE_VALUE"))
                .map(row -> normalize(row.values().get("ruleName")))
                .filter(name -> !name.isBlank()).collect(java.util.stream.Collectors.toUnmodifiableSet());
        return new ExportContext(systemName, dbmsName, snapshot.defaultSchema(), connectionId,
                snapshot.deploymentYearMonth(), Map.copyOf(ruleIds), rulesWithCodeValues);
    }

    private String predominantValue(ProjectSnapshot snapshot, String key) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (NormalizedRow row : snapshot.rows()) {
            String value = row.values().get(key);
            if (value != null && !value.isBlank()) counts.merge(value.trim(), 1, Integer::sum);
        }
        return counts.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("");
    }

    private void appendDelete(StringBuilder sql, String table, String idColumn, List<NormalizedRow> rows) {
        if (rows.isEmpty()) return;
        sql.append("DELETE FROM ").append(SCHEMA).append(table).append(" WHERE ").append(idColumn).append(" IN (");
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) sql.append(", ");
            sql.append(q(rows.get(i).values(), "wdqId"));
        }
        sql.append(");\n\n");
    }

    private void appendDeleteIds(StringBuilder sql, String table, String idColumn, List<String> ids) {
        if (ids.isEmpty()) return;
        sql.append("DELETE FROM ").append(SCHEMA).append(table).append(" WHERE ").append(idColumn).append(" IN (");
        for (int i=0;i<ids.size();i++) {
            if (i>0) sql.append(", ");
            sql.append(q(ids.get(i)));
        }
        sql.append(");\n");
    }

    private void appendCompatibilityDdl(StringBuilder sql) {
        sql.append("-- WDQ 9.0 / 9.1 / 9.2 컬럼 호환성 보정\n");
        appendColumnIfMissing(sql, "WAA_STND_TBL_PRF", "BASIS_RGLTN",
                "varchar(3000) NULL COMMENT '근거규정'");
        appendColumnIfMissing(sql, "WAA_VRFC_RULE", "ERR_EXP_DATA",
                "varchar(4000) NULL COMMENT '오류제외데이터'");
        appendColumnIfMissing(sql, "WAA_VRFC_RULE", "ERR_EXP_DATA_SEP",
                "varchar(50) NULL COMMENT '오류제외데이터 구분자'");
        appendColumnIfMissing(sql, "WAA_VRFC_RULE", "MTCH_TYP",
                "varchar(1) DEFAULT 'N' COMMENT '검증매치유형'");
        sql.append("COMMIT;\n\n");
    }

    private void appendColumnIfMissing(StringBuilder sql, String table, String column, String definition) {
        String ddl = "ALTER TABLE dqlite." + table + " ADD COLUMN " + column + " " + definition;
        sql.append("SELECT COUNT(*) INTO @csr_col_exists FROM information_schema.columns")
                .append(" WHERE table_schema='dqlite' AND table_name=").append(q(table.toLowerCase(Locale.ROOT)))
                .append(" AND column_name=").append(q(column.toLowerCase(Locale.ROOT))).append(";\n")
                .append("SET @csr_ddl=IF(@csr_col_exists=0,").append(q(ddl)).append(",'SELECT 1');\n")
                .append("PREPARE csr_stmt FROM @csr_ddl;\n")
                .append("EXECUTE csr_stmt;\n")
                .append("DEALLOCATE PREPARE csr_stmt;\n");
    }

    private void appendFullReplacementCleanup(StringBuilder sql, ProjectSnapshot snapshot, ExportContext context) {
        String namespace = predominantValue(snapshot, "wdqNamespace");
        if (namespace.isBlank()) namespace = inferLegacyNamespace(snapshot, "STND_");
        if (!namespace.matches("[0-9]{1,4}"))
            throw new IllegalStateException("2025 방식 전체 교체에 필요한 WDQ 네임스페이스가 없습니다: " + namespace);

        String connection = q(context.connectionId());
        String rulePrefix = q("STNDRULE_" + namespace + "%");
        String codePrefix = q("STNDCD_" + namespace + "%");
        String mappingPrefix = q("STND_" + namespace + "%");
        String businessPrefix = q("STNDPRF_" + namespace + "%");
        String exclusionPrefix = q("STNDEXP_" + namespace + "%");

        sql.append("-- 2025 패치와 동일한 시스템 채번 영역 전체 교체\n")
                .append("DELETE X FROM dqlite.WAM_SHD_JOB X JOIN dqlite.WAA_COL_RULE_REL R")
                .append(" ON X.SHD_JOB_ID=R.RULE_REL_ID JOIN dqlite.WAA_DB_SCH S")
                .append(" ON S.DB_SCH_ID=R.DB_SCH_ID WHERE S.DB_CONN_TRG_ID=").append(connection).append(";\n")
                .append("DELETE FROM dqlite.WAM_SHD_JOB WHERE SHD_JOB_ID LIKE ").append(businessPrefix)
                .append(" OR SHD_JOB_ID=").append(connection).append(";\n")
                .append("DELETE X FROM dqlite.WAM_PRF_ERR_DATA X JOIN dqlite.WAA_COL_RULE_REL R")
                .append(" ON X.PRF_ID=R.RULE_REL_ID JOIN dqlite.WAA_DB_SCH S")
                .append(" ON S.DB_SCH_ID=R.DB_SCH_ID WHERE S.DB_CONN_TRG_ID=").append(connection).append(";\n")
                .append("DELETE FROM dqlite.WAM_PRF_ERR_DATA WHERE PRF_ID LIKE ").append(businessPrefix).append(";\n")
                .append("DELETE X FROM dqlite.WAM_PRF_RESULT X JOIN dqlite.WAA_COL_RULE_REL R")
                .append(" ON X.PRF_ID=R.RULE_REL_ID JOIN dqlite.WAA_DB_SCH S")
                .append(" ON S.DB_SCH_ID=R.DB_SCH_ID WHERE S.DB_CONN_TRG_ID=").append(connection).append(";\n")
                .append("DELETE FROM dqlite.WAM_PRF_RESULT WHERE PRF_ID LIKE ").append(businessPrefix).append(";\n")
                .append("DELETE FROM dqlite.WAM_BR_ERR_DATA WHERE BR_ID LIKE ").append(businessPrefix).append(";\n")
                .append("DELETE FROM dqlite.WAM_BR_DQI_MAP WHERE BR_ID LIKE ").append(businessPrefix).append(";\n")
                .append("DELETE FROM dqlite.WAM_BR_RESULT WHERE BR_ID LIKE ").append(businessPrefix).append(";\n")
                .append("DELETE FROM dqlite.WAM_PRF_REL_COL WHERE PRF_ID LIKE ").append(businessPrefix).append(";\n")
                .append("DELETE FROM dqlite.WAM_PRF_REL_TBL WHERE PRF_ID LIKE ").append(businessPrefix).append(";\n")
                .append("DELETE FROM dqlite.WAM_PRF_UNQ_COL WHERE PRF_ID LIKE ").append(businessPrefix).append(";\n")
                .append("DELETE FROM dqlite.WAM_BR_MSTR WHERE BR_ID LIKE ").append(businessPrefix).append(";\n")
                .append("DELETE FROM dqlite.WAM_PRF_MSTR WHERE PRF_ID LIKE ").append(businessPrefix).append(";\n")
                .append("DELETE R FROM dqlite.WAA_EXP_TBL_RULE R JOIN dqlite.WAA_DB_CONN_TRG T")
                .append(" ON T.DB_CONN_TRG_ID=R.DB_CONN_TRG_ID WHERE T.DB_CONN_TRG_ID=").append(connection).append(";\n")
                .append("DELETE E FROM dqlite.WAA_EXP_TBL E JOIN dqlite.WAA_DB_SCH S")
                .append(" ON S.DB_SCH_ID=E.DB_SCH_ID WHERE S.DB_CONN_TRG_ID=").append(connection).append(";\n")
                .append("DELETE C FROM dqlite.WAA_EXP_COL C JOIN dqlite.WAA_DB_SCH S")
                .append(" ON S.DB_SCH_ID=C.DB_SCH_ID WHERE S.DB_CONN_TRG_ID=").append(connection).append(";\n")
                .append("DELETE R FROM dqlite.WAA_COL_RULE_REL R JOIN dqlite.WAA_DB_SCH S")
                .append(" ON S.DB_SCH_ID=R.DB_SCH_ID WHERE S.DB_CONN_TRG_ID=").append(connection).append(";\n")
                .append("DELETE FROM dqlite.WAA_CD_LIST WHERE CD_RULE_ID LIKE ").append(codePrefix).append(";\n")
                .append("DELETE FROM dqlite.WAA_CD_RULE WHERE CD_RULE_ID LIKE ").append(codePrefix).append(";\n")
                .append("DELETE FROM dqlite.WAA_VRFC_RULE WHERE VRFC_ID LIKE ").append(rulePrefix).append(";\n")
                .append("DELETE FROM dqlite.WAA_STND_RULE_SET WHERE STND_RULE_SET_ID LIKE ").append(mappingPrefix).append(";\n")
                .append("DELETE FROM dqlite.WAA_STND_TBL_PRF WHERE STND_TBL_PRF_ID LIKE ").append(businessPrefix).append(";\n")
                .append("DELETE FROM dqlite.WAA_STND_EXP_OBJ WHERE STND_EXP_OBJ_ID LIKE ").append(exclusionPrefix).append(";\n")
                .append("DELETE C FROM dqlite.WAT_DBC_COL C JOIN dqlite.WAA_DB_SCH S")
                .append(" ON S.DB_SCH_ID=C.DB_SCH_ID WHERE S.DB_CONN_TRG_ID=").append(connection).append(";\n")
                .append("DELETE T FROM dqlite.WAT_DBC_TBL T JOIN dqlite.WAA_DB_SCH S")
                .append(" ON S.DB_SCH_ID=T.DB_SCH_ID WHERE S.DB_CONN_TRG_ID=").append(connection).append(";\n")
                .append("DELETE FROM dqlite.WAA_DB_SCH WHERE DB_CONN_TRG_ID=").append(connection).append(";\n")
                .append("COMMIT;\n\n");
    }

    private void appendDeleteNamespace(StringBuilder sql, ProjectSnapshot snapshot, String table,
            String idColumn, String idPrefix, String label) {
        String namespace=predominantValue(snapshot,"wdqNamespace");
        if (namespace.isBlank()) namespace=inferLegacyNamespace(snapshot,idPrefix);
        if (!namespace.matches("[0-9]{1,4}"))
            throw new IllegalStateException(label+" 전체 교체에 필요한 WDQ 네임스페이스가 없습니다: "+namespace);
        sql.append("-- 시스템 채번 영역의 과거 ").append(label).append(" 전체 교체\n")
                .append("DELETE FROM ").append(SCHEMA).append(table).append("\n")
                .append(" WHERE ").append(idColumn).append(" LIKE ")
                .append(q(idPrefix+namespace+"%"))
                .append(";\n\n");
    }

    private String inferLegacyNamespace(ProjectSnapshot snapshot,String idPrefix) {
        return snapshot.rows().stream()
                .map(row -> row.values().get("wdqId"))
                .filter(id -> id != null && id.startsWith(idPrefix) && id.length() > idPrefix.length())
                .map(id -> id.substring(idPrefix.length(),idPrefix.length()+1))
                .filter(value -> value.matches("[0-9]"))
                .findFirst().orElse("");
    }

    private void appendDeleteDerivedExclusions(StringBuilder sql, ExportContext context) {
        if (context.connectionId().isBlank())
            throw new IllegalStateException("파생 제외정보 삭제에 필요한 DB 연결 ID가 없습니다");
        String connection=q(context.connectionId());
        sql.append("-- 스키마 ID 기반으로 생성된 과거 제외정보를 연결 대상 단위로 먼저 제거\n")
                .append("DELETE R FROM ").append(SCHEMA).append("WAA_EXP_TBL_RULE R\n")
                .append(" JOIN ").append(SCHEMA).append("WAA_DB_CONN_TRG T")
                .append(" ON T.DB_CONN_TRG_ID=R.DB_CONN_TRG_ID\n")
                .append(" WHERE T.DB_CONN_TRG_ID=").append(connection).append(";\n")
                .append("DELETE E FROM ").append(SCHEMA).append("WAA_EXP_TBL E\n")
                .append(" JOIN ").append(SCHEMA).append("WAA_DB_SCH S ON S.DB_SCH_ID=E.DB_SCH_ID\n")
                .append(" WHERE S.DB_CONN_TRG_ID=").append(connection).append(";\n")
                .append("DELETE C FROM ").append(SCHEMA).append("WAA_EXP_COL C\n")
                .append(" JOIN ").append(SCHEMA).append("WAA_DB_SCH S ON S.DB_SCH_ID=C.DB_SCH_ID\n")
                .append(" WHERE S.DB_CONN_TRG_ID=").append(connection).append(";\n\n");
    }

    private void insert(StringBuilder sql, String table, List<String> columns, List<String> values) {
        if (columns.size() != values.size()) throw new IllegalStateException(table + " 컬럼과 값 개수가 다릅니다");
        sql.append("INSERT INTO ").append(SCHEMA).append(table).append(" (")
                .append(String.join(", ", columns)).append(") VALUES\n(")
                .append(String.join(", ", values)).append(");\n");
    }

    private List<NormalizedRow> rows(ProjectSnapshot snapshot, String type) {
        return snapshot.rows().stream().filter(row -> row.dataType().equals(type))
                .filter(row -> row.values().values().stream().noneMatch(v -> "PT01".equalsIgnoreCase(v) || "PT02".equalsIgnoreCase(v)))
                .sorted(Comparator.comparing(NormalizedRow::logicalKey)).toList();
    }

    private String q(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null || value.isBlank()) throw new IllegalStateException("필수 SQL 값이 없습니다: " + key);
        return MariaDbLiteral.of(value);
    }
    private String q(String value) {
        if (value == null || value.isBlank()) throw new IllegalStateException("필수 SQL 값이 없습니다");
        return MariaDbLiteral.of(value);
    }
    private String optional(String value) { return value == null || value.isBlank() ? NULL : MariaDbLiteral.of(value); }
    private String physical(Map<String, String> values, String key, String fallback) { return q(first(values.get(key), fallback)); }
    private String first(String... values) { for (String value : values) if (value != null && !value.isBlank()) return value; return ""; }
    private String normalize(String value) { return value == null ? "" : value.trim().toUpperCase(Locale.ROOT); }
    private String verificationType(String value) {
        String normalized = normalize(value);
        if (normalized.contains("날짜") || normalized.contains("일시") || normalized.equals("DTM")) return "DTM";
        return "FRM";
    }
    private String matchType(String value) { return normalize(value).contains("일치") && !normalize(value).contains("불일치") ? "Y" : "N"; }
    private String codeType(String value) {
        String normalized=normalize(value);
        if(normalized.contains("목록")||normalized.equals("LC")) return "LC";
        if(normalized.contains("공통")||normalized.equals("CC")) return "CC";
        throw new IllegalStateException("지원하지 않는 코드유형입니다. 공통코드(CC) 또는 목록성코드(LC)를 사용하세요: "+value);
    }
    private String requiredCodeType(Map<String,String> codeTypes,String ruleName) {
        String type=codeTypes.get(normalize(ruleName));
        if(type==null) throw new IllegalStateException("코드값에 대응하는 코드유형이 없습니다: "+ruleName);
        return type;
    }
    private String codeExclusiveYn(Map<String,String> values,ExportContext context) {
        return "LC".equals(codeType(values.get("codeType")))
                && context.rulesWithCodeValues().contains(normalize(values.get("ruleName"))) ? "Y" : "N";
    }
    private GeneratedFile file(String name, CharSequence sql) {
        return new GeneratedFile(name, "text/plain; charset=UTF-8", sql.toString().getBytes(StandardCharsets.UTF_8));
    }

    private record ExportContext(String systemName, String dbmsName, String schema, String connectionId,
            String openYm, Map<String, String> ruleIds, Set<String> rulesWithCodeValues) {}
    private record RowContext(Map<String, String> values, ExportContext context) {}
}
