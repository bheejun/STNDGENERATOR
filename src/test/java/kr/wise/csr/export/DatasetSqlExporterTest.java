package kr.wise.csr.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import kr.wise.csr.normalization.NormalizedRow;
import kr.wise.csr.project.ProjectSnapshot;
import kr.wise.csr.project.ProjectStatus;

class DatasetSqlExporterTest {
    @Test void escapesMariaDbLiteralsAndRejectsControlCharacters() {
        assertThat(MariaDbLiteral.of(null)).isEqualTo("NULL");
        assertThat(MariaDbLiteral.of("O'Reilly\\path\r\n한글")).isEqualTo("'O''Reilly\\\\path\\r\\n한글'");
        assertThatThrownBy(() -> MariaDbLiteral.of("bad\u0001value")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void exportsOneExplicitColumnInsertFilePerDataset() {
        ProjectSnapshot snapshot = approvedSnapshot();
        List<GeneratedFile> files = new DatasetSqlExporter().exportDatasetSql(snapshot);
        assertThat(files).hasSize(7);
        assertThat(text(files, "01-db-connection.sql"))
                .contains("'jdbc:oracle:thin:@아이피:포트:SID'")
                .contains("'oracle.jdbc.driver.OracleDriver'")
                .contains("ADD COLUMN BASIS_RGLTN")
                .contains("DELETE FROM dqlite.WAA_VRFC_RULE WHERE VRFC_ID LIKE 'STNDRULE_0%'")
                .contains("DELETE FROM dqlite.WAA_DB_SCH WHERE DB_CONN_TRG_ID='STNDDB_00000001'")
                .doesNotContain("DELETE FROM dqlite.WAA_DB_CONN_TRG");
        String verification = text(files, "03-verification-rule.sql");
        assertThat(verification).contains("INSERT INTO dqlite.WAA_VRFC_RULE (VRFC_ID, VRFC_TYP, VRFC_NM, VRFC_RULE")
                .contains("OBJ_VERS, REG_TYP_CD, WRIT_DTM, WRIT_USER_ID, DQI_ID")
                .contains("SELECT DQI_ID FROM dqlite.WAM_DQI")
                .contains("WHERE DQI_LNM='완전성'")
                .contains("'STNDRULE_0000001', 'RNG'")
                .contains("STNDRULE_0000001").doesNotContain("STAT_00000000001")
                .doesNotContain("PT01").doesNotContain("PT02");
        assertThat(text(files, "05-code-list.sql")).contains("INSERT INTO dqlite.WAA_CD_LIST")
                .contains("'A'", "'코드A'", "'STNDCD_00000001'", "'LC'", "'Y'");
        assertThat(text(files, "04-code-rule.sql")).contains("'LC', NULL, 'Y'");
        assertThat(text(files, "06-column-mapping.sql"))
                .contains("WHERE STND_RULE_SET_ID LIKE 'STND_0%'")
                .contains("'COL', 'COL', 'VRFC', 'STNDRULE_0000001'")
                .contains("'CODE_COL', 'CODE_COL', 'CD', 'STNDCD_00000001', 'LRG009'")
                .doesNotContain("WHERE (STND_SCH_PNM, STND_TBL_PNM, STND_COL_PNM) IN");
        assertThat(text(files, "07-business-rule.sql"))
                .contains("SELECT DQI_ID FROM dqlite.WAM_DQI")
                .contains("WHERE DQI_LNM='완전성'")
                .contains("'업무 설명', '내부 지침'");
        String exclusion = text(files, "02-exclusion.sql");
        assertThat(exclusion)
                .contains("DELETE R FROM dqlite.WAA_EXP_TBL_RULE R")
                .contains("DELETE E FROM dqlite.WAA_EXP_TBL E")
                .contains("JOIN dqlite.WAA_DB_SCH S ON S.DB_SCH_ID=E.DB_SCH_ID")
                .contains("DELETE C FROM dqlite.WAA_EXP_COL C")
                .contains("WHERE S.DB_CONN_TRG_ID='STNDDB_00000001'")
                .contains("WHERE STND_EXP_OBJ_ID LIKE 'STNDEXP_0%'")
                .contains("'EXR', NULL, NULL, NULL, 'TMP', 'B', '[2026표준] 임시테이블'")
                .doesNotContain("WHERE STND_EXP_OBJ_ID IN");
        assertThat(exclusion.indexOf("DELETE E FROM dqlite.WAA_EXP_TBL E"))
                .isLessThan(exclusion.indexOf("WHERE STND_EXP_OBJ_ID LIKE"));
    }

    static ProjectSnapshot approvedSnapshot() {
        List<NormalizedRow> rows=List.of(
                row("SYSTEM","S",Map.of("wdqId","STNDDB_00000001","wdqNamespace","0","systemName","합성시스템","dbmsOriginal","MAILDB","schemaOriginal","APP")),
                row("EXCLUSION","E",Map.of("wdqId","STNDEXP_0000001","systemName","합성시스템","dbmsOriginal","MAILDB","schemaOriginal","APP","tableOriginal","TMP","columnOriginal","","exclusionType","TBL","reason","임시")),
                row("EXCLUSION_PATTERN","EP",Map.of("wdqId","STNDEXP_0000002","dbmsOriginal","MAILDB","schemaOriginal","APP","relation","B","pattern","TMP","reason","[2026표준] 임시테이블")),
                row("VERIFICATION_RULE","V",Map.of("wdqId","STNDRULE_0000001","ruleName","범위","expression","컬럼 >= 0",
                        "qualityIndicator","완전성","ruleOrigin","ADDITIONAL_UPLOADED","ruleType","범위")),
                row("VERIFICATION_RULE","VD",Map.of("wdqId","STAT_00000000001","ruleName","[기본]여부(Y,N)","expression","Y,N","qualityIndicator","완전성")),
                row("CODE_RULE","C",Map.of("wdqId","STNDCD_00000001","ruleName","코드","codeType","목록성코드","lookupSql","select code from codes")),
                row("CODE_VALUE","CV",Map.of("ruleName","코드","codeId","A","codeName","코드A")),
                row("COLUMN_MAPPING","M",Map.of("wdqId","STND_0000000001","dbmsOriginal","MAILDB","schemaOriginal","APP","tableOriginal","TB","columnOriginal","COL","ruleType","VERIFICATION","verificationRuleId","STNDRULE_0000001")),
                row("COLUMN_MAPPING","MC",Map.of("wdqId","STND_0000000002","dbmsOriginal","MAILDB","schemaOriginal","APP","tableOriginal","TB","columnOriginal","CODE_COL","ruleType","CODE","ruleName","코드","codeRuleId","LRG009")),
                row("BUSINESS_RULE","B",Map.ofEntries(
                        Map.entry("wdqId","STNDPRF_0000001"), Map.entry("systemName","합성시스템"),
                        Map.entry("dbmsOriginal","MAILDB"), Map.entry("schemaOriginal","APP"),
                        Map.entry("tableOriginal","TB"), Map.entry("ruleName","업무"),
                        Map.entry("ruleKind","BUSINESS"), Map.entry("ruleSql","select count(*) from TB"),
                        Map.entry("qualityIndicator","완전성"), Map.entry("description","업무 설명"),
                        Map.entry("basis","내부 지침"))));
        ProjectSnapshot base=new ProjectSnapshot(1,1,2026,"202607","APP",ProjectStatus.VALIDATED,rows,List.of(),0,0,List.of(),null,null,null);
        return base.approved("담당자", OffsetDateTime.parse("2026-07-15T10:00:00+09:00"));
    }
    static NormalizedRow row(String type,String key,Map<String,String> values){return new NormalizedRow(type,key,values,List.of(),Integer.toHexString(values.hashCode()));}
    private String text(List<GeneratedFile> files,String name){return files.stream().filter(f->f.fileName().equals(name)).findFirst().map(f->new String(f.content(), StandardCharsets.UTF_8)).orElseThrow();}
}
