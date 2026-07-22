package kr.wise.csr.export;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;

import org.springframework.stereotype.Component;

import kr.wise.csr.normalization.NormalizedRow;
import kr.wise.csr.project.ProjectSnapshot;

@Component
public class DatasetSqlExporter {
    private static final String SCHEMA = "dqlite.";
    private static final String NULL = "NULL";
    private static final String NOW = "NOW()";
    private static final String END_DATE = "'9999-12-31 00:00:00.000'";

    public List<GeneratedFile> exportDatasetSql(ProjectSnapshot snapshot) {
        requireApproved(snapshot);
        ExportContext context = context(snapshot);
        return List.of(
                file("01-connection-check.sql", connectionCheck(snapshot, context)),
                dataset(snapshot, context, "EXCLUSION", "02-exclusion.sql", "WAA_STND_EXP_OBJ",
                        "STND_EXP_OBJ_ID", this::exclusion),
                dataset(snapshot, context, "VERIFICATION_RULE", "03-verification-rule.sql", "WAA_VRFC_RULE",
                        "VRFC_ID", this::verification),
                dataset(snapshot, context, "CODE_RULE", "04-code-rule.sql", "WAA_CD_RULE",
                        "CD_RULE_ID", this::codeRule),
                dataset(snapshot, context, "COLUMN_MAPPING", "05-column-mapping.sql", "WAA_STND_RULE_SET",
                        "STND_RULE_SET_ID", this::mapping),
                dataset(snapshot, context, "BUSINESS_RULE", "06-business-rule.sql", "WAA_STND_TBL_PRF",
                        "STND_TBL_PRF_ID", this::business));
    }

    static void requireApproved(ProjectSnapshot snapshot) {
        if (snapshot == null || !snapshot.isApprovalCurrent())
            throw new IllegalStateException("현재 데이터가 승인된 스냅샷과 일치하지 않습니다");
    }

    private GeneratedFile dataset(ProjectSnapshot snapshot, ExportContext context, String type, String fileName,
            String table, String idColumn, BiConsumer<StringBuilder, RowContext> writer) {
        List<NormalizedRow> rows = rows(snapshot, type);
        StringBuilder sql = header(snapshot).append("-- 기존 연결정보는 변경하지 않고 생성 대상 ID만 교체합니다.\n");
        appendDelete(sql, table, idColumn, rows);
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

    private void exclusion(StringBuilder sql, RowContext row) {
        Map<String, String> v = row.values();
        boolean table = "TBL".equalsIgnoreCase(v.get("exclusionType"));
        insert(sql, "WAA_STND_EXP_OBJ", List.of(
                "STND_EXP_OBJ_ID", "STND_SYS_NM", "STND_DBMS_PNM", "STND_SCH_PNM", "STND_TBL_PNM",
                "STND_COL_PNM", "EXP_TYP", "TBL_EXP_RSN", "TBL_ADD_CND", "TBL_ADD_CND_RSN",
                "TBL_EXP_STND_RULE", "INCLD_REL", "TBL_EXP_STND_RULE_RSN", "COL_EXP_RSN", "OPEN_YM", "EXP_YN"),
                List.of(q(v, "wdqId"), q(row.context().systemName()), physical(v, "dbmsOriginal", row.context().dbmsName()),
                        physical(v, "schemaOriginal", row.context().schema()), optional(v.get("tableOriginal")),
                        optional(v.get("columnOriginal")), q(v, "exclusionType"), table ? optional(v.get("reason")) : NULL,
                        NULL, NULL, NULL, NULL, NULL, table ? NULL : optional(v.get("reason")),
                        q(row.context().openYm()), q("Y")));
    }

    private void verification(StringBuilder sql, RowContext row) {
        Map<String, String> v = row.values();
        insert(sql, "WAA_VRFC_RULE", List.of(
                "VRFC_ID", "VRFC_TYP", "VRFC_NM", "VRFC_RULE", "VRFC_DESCN", "EXP_DTM", "STR_DTM",
                "OBJ_DESCN", "OBJ_VERS", "REG_TYP_CD", "WRIT_DTM", "WRIT_USER_ID", "DQI_ID", "MNG_USER_ID",
                "ERR_EXP_DATA", "ERR_EXP_DATA_SEP", "MTCH_TYP"),
                List.of(q(v, "wdqId"), q(verificationType(v.get("ruleType"))), q(v, "ruleName"), q(v, "expression"),
                        optional(v.get("description")), END_DATE, NOW, NULL, "1", q("C"), NOW, q("admin"), NULL, NULL,
                        optional(v.get("excludedValues")), optional(v.get("excludedValueSeparator")),
                        q(matchType(v.get("matchType")))));
    }

    private void codeRule(StringBuilder sql, RowContext row) {
        Map<String, String> v = row.values();
        insert(sql, "WAA_CD_RULE", List.of(
                "CD_RULE_ID", "DB_CONN_TRG_ID", "CD_RULE_NM", "CD_SQL", "CD_CLS_COL_NM", "CD_CLS_NM_COL_NM",
                "CD_ID_COL_NM", "CD_NM_COL_NM", "OBJ_DESCN", "WRIT_DTM", "CD_TYP_CD", "DB_SCH_ID", "EXL_YN",
                "RQST_DTM", "RQST_USER_ID"),
                List.of(q(v, "wdqId"), q(row.context().connectionId()), q(v, "ruleName"), optional(v.get("lookupSql")),
                        NULL, NULL, NULL, NULL, optional(v.get("description")), NOW, q(codeType(v.get("codeType"))),
                        NULL, q("Y"), NOW, q("admin")));
    }

    private void mapping(StringBuilder sql, RowContext row) {
        Map<String, String> v = row.values();
        String ruleId = row.context().ruleIds().get(normalize(v.get("ruleName")));
        if (ruleId == null) ruleId = row.context().ruleIds().get(normalize(v.get("verificationRuleId")));
        if (ruleId == null) ruleId = row.context().ruleIds().get(normalize(v.get("codeRuleId")));
        if (ruleId == null || ruleId.isBlank())
            throw new IllegalStateException("컬럼 매핑의 룰 ID를 찾을 수 없습니다: " + v.getOrDefault("ruleName", v.get("codeRuleId")));
        insert(sql, "WAA_STND_RULE_SET", List.of(
                "STND_RULE_SET_ID", "STND_SYS_NM", "STND_DBMS_PNM", "STND_DBMS_LNM", "STND_SCH_PNM",
                "STND_SCH_LNM", "STND_TBL_PNM", "STND_TBL_LNM", "STND_COL_PNM", "STND_COL_LNM",
                "RULE_SET_TYP", "VRFC_ID", "CD_CLS_ID", "OPEN_YM", "COL_RMK"),
                List.of(q(v, "wdqId"), q(row.context().systemName()), physical(v, "dbmsOriginal", row.context().dbmsName()),
                        physical(v, "dbmsOriginal", row.context().dbmsName()), physical(v, "schemaOriginal", row.context().schema()),
                        physical(v, "schemaOriginal", row.context().schema()), q(v, "tableOriginal"), q(v, "tableOriginal"),
                        q(v, "columnOriginal"), optional(first(v.get("columnLogicalName"), v.get("columnOriginal"))), NULL,
                        q(ruleId), NULL, q(row.context().openYm()), optional(v.get("comment"))));
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
                List.of(q(v, "wdqId"), q(row.context().systemName()), physical(v, "dbmsOriginal", row.context().dbmsName()),
                        physical(v, "dbmsOriginal", row.context().dbmsName()), physical(v, "schemaOriginal", row.context().schema()),
                        physical(v, "schemaOriginal", row.context().schema()), q(v, "tableOriginal"), q(v, "tableOriginal"),
                        optional(column), optional(column), NULL, NULL, NULL, NULL, "1", q("BR"), q(v, "ruleName"),
                        optional(v.get("countSql")), optional(errorCountSql), optional(analysisSql), NULL,
                        optional(v.get("description")), optional(v.get("basis")), q(row.context().openYm())));
    }

    private ExportContext context(ProjectSnapshot snapshot) {
        Map<String, String> system = rows(snapshot, "SYSTEM").stream().findFirst().map(NormalizedRow::values).orElse(Map.of());
        String connectionId = first(snapshot.connectionWdqId(), system.get("wdqId"));
        String systemName = first(snapshot.systemName(), system.get("systemName"));
        String dbmsName = first(snapshot.dbmsPhysicalName(), system.get("dbmsOriginal"));
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
        return new ExportContext(systemName, dbmsName, snapshot.defaultSchema(), connectionId,
                snapshot.deploymentYearMonth(), Map.copyOf(ruleIds));
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
    private String codeType(String value) { return normalize(value).contains("목록") || normalize(value).equals("LC") ? "LC" : "SQL"; }
    private GeneratedFile file(String name, CharSequence sql) {
        return new GeneratedFile(name, "text/plain; charset=UTF-8", sql.toString().getBytes(StandardCharsets.UTF_8));
    }

    private record ExportContext(String systemName, String dbmsName, String schema, String connectionId,
            String openYm, Map<String, String> ruleIds) {}
    private record RowContext(Map<String, String> values, ExportContext context) {}
}
