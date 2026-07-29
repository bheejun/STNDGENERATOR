package kr.wise.csr.export;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import kr.wise.csr.normalization.NormalizedRow;
import kr.wise.csr.project.ProjectSnapshot;

@Component
public class WdqDeleteSqlExporter {
    public GeneratedFile export(ProjectSnapshot snapshot) {
        String legacyPrefix = legacyPrefix(snapshot.systemName());
        String connectionId = legacyPrefix == null ? snapshot.connectionWdqId()
                : "STNDDB_" + "0".repeat(8 - legacyPrefix.length()) + legacyPrefix;
        if (connectionId == null || connectionId.isBlank())
            throw new IllegalStateException("WDQ DB 연결 ID가 없어 삭제 SQL을 생성할 수 없습니다");

        List<String> exclusions = ids(snapshot, "EXCLUSION");
        List<String> rules = ids(snapshot, "VERIFICATION_RULE").stream()
                .filter(id -> !isDefaultVerificationRule(id))
                .toList();
        List<String> codes = ids(snapshot, "CODE_RULE");
        List<String> mappings = ids(snapshot, "COLUMN_MAPPING");
        List<String> businesses = ids(snapshot, "BUSINESS_RULE");
        String connection = MariaDbLiteral.of(connectionId);
        StringBuilder sql = new StringBuilder("-- WDQ 9.0 / 9.1 / 9.2 SQL-only delete\n")
                .append("-- system: ").append(snapshot.systemName()).append(" / project: ")
                .append(snapshot.projectId()).append("\nSET NAMES utf8mb4;\nUSE `dqlite`;\n\n");

        appendRuleRelationDelete(sql, "WAM_SHD_JOB", "SHD_JOB_ID", connection);
        appendRuleRelationDelete(sql, "WAM_PRF_ERR_DATA", "PRF_ID", connection);
        appendRuleRelationDelete(sql, "WAM_PRF_RESULT", "PRF_ID", connection);
        appendIn(sql, "WAM_SHD_JOB", "SHD_JOB_ID", businesses);
        appendIn(sql, "WAM_BR_ERR_DATA", "BR_ID", businesses);
        appendIn(sql, "WAM_BR_DQI_MAP", "BR_ID", businesses);
        appendIn(sql, "WAM_BR_RESULT", "BR_ID", businesses);
        appendIn(sql, "WAM_PRF_REL_COL", "PRF_ID", businesses);
        appendIn(sql, "WAM_PRF_REL_TBL", "PRF_ID", businesses);
        appendIn(sql, "WAM_PRF_UNQ_COL", "PRF_ID", businesses);
        appendIn(sql, "WAM_BR_MSTR", "BR_ID", businesses);
        appendIn(sql, "WAM_PRF_MSTR", "PRF_ID", businesses);
        sql.append("DELETE R FROM WAA_EXP_TBL_RULE R JOIN WAA_DB_CONN_TRG T ON T.DB_CONN_TRG_ID=R.DB_CONN_TRG_ID WHERE T.DB_CONN_TRG_ID=")
                .append(connection).append(";\n")
                .append("DELETE E FROM WAA_EXP_TBL E JOIN WAA_DB_SCH S ON S.DB_SCH_ID=E.DB_SCH_ID WHERE S.DB_CONN_TRG_ID=")
                .append(connection).append(";\n")
                .append("DELETE C FROM WAA_EXP_COL C JOIN WAA_DB_SCH S ON S.DB_SCH_ID=C.DB_SCH_ID WHERE S.DB_CONN_TRG_ID=")
                .append(connection).append(";\n")
                .append("DELETE R FROM WAA_COL_RULE_REL R JOIN WAA_DB_SCH S ON S.DB_SCH_ID=R.DB_SCH_ID WHERE S.DB_CONN_TRG_ID=")
                .append(connection).append(";\n");
        appendIn(sql, "WAA_CD_LIST", "CD_RULE_ID", codes);
        if (legacyPrefix == null) {
            appendIn(sql, "WAA_STND_RULE_SET", "STND_RULE_SET_ID", mappings);
            appendIn(sql, "WAA_STND_TBL_PRF", "STND_TBL_PRF_ID", businesses);
            appendIn(sql, "WAA_VRFC_RULE", "VRFC_ID", rules);
        } else {
            appendLike(sql, "WAA_STND_RULE_SET", "STND_RULE_SET_ID", "STND_" + legacyPrefix + "%");
            appendLike(sql, "WAA_STND_TBL_PRF", "STND_TBL_PRF_ID", "STNDPRF_" + legacyPrefix + "%");
            appendLike(sql, "WAA_VRFC_RULE", "VRFC_ID", "STNDRULE_" + legacyPrefix + "%");
        }
        appendIn(sql, "WAA_CD_RULE", "CD_RULE_ID", codes);
        if (legacyPrefix == null) appendIn(sql, "WAA_STND_EXP_OBJ", "STND_EXP_OBJ_ID", exclusions);
        else appendLike(sql, "WAA_STND_EXP_OBJ", "STND_EXP_OBJ_ID", "STNDEXP_" + legacyPrefix + "%");
        sql.append("DELETE FROM WAA_DB_SCH WHERE DB_CONN_TRG_ID=").append(connection).append(";\n")
                .append("DELETE FROM WAA_DB_CONN_TRG WHERE DB_CONN_TRG_ID=").append(connection).append(";\n");
        sql.append("\nCOMMIT;\n");
        return new GeneratedFile("wdq_delete.sql", "text/plain; charset=UTF-8",
                sql.toString().getBytes(StandardCharsets.UTF_8));
    }

    private String legacyPrefix(String systemName) {
        if (systemName != null && systemName.replace(" ", "").toLowerCase().contains("지킴e")) return "7";
        return null;
    }

    private void appendLike(StringBuilder sql, String table, String column, String pattern) {
        sql.append("DELETE FROM ").append(table).append(" WHERE ").append(column).append(" LIKE ")
                .append(MariaDbLiteral.of(pattern)).append(";\n");
    }

    private List<String> ids(ProjectSnapshot snapshot, String type) {
        return snapshot.rows().stream().filter(row -> row.dataType().equals(type))
                .map(NormalizedRow::values).map(values -> values.get("wdqId"))
                .filter(value -> value != null && !value.isBlank()).distinct().sorted().toList();
    }

    private boolean isDefaultVerificationRule(String id) {
        String normalized = id.trim().toUpperCase(java.util.Locale.ROOT);
        return normalized.startsWith("STAT_") || normalized.startsWith("VRF1_");
    }

    private void appendIn(StringBuilder sql, String table, String column, List<String> ids) {
        if (ids.isEmpty()) return;
        sql.append("DELETE FROM ").append(table).append(" WHERE ").append(column).append(" IN (")
                .append(ids.stream().map(MariaDbLiteral::of).collect(Collectors.joining(", "))).append(");\n");
    }

    private void appendRuleRelationDelete(StringBuilder sql, String table, String idColumn, String connection) {
        sql.append("DELETE X FROM ").append(table).append(" X JOIN WAA_COL_RULE_REL R ON X.")
                .append(idColumn).append("=R.RULE_REL_ID JOIN WAA_DB_SCH S ON S.DB_SCH_ID=R.DB_SCH_ID ")
                .append("WHERE S.DB_CONN_TRG_ID=").append(connection).append(";\n");
    }
}
