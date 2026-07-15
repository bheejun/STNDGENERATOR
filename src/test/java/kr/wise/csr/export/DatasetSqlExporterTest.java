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
        assertThat(files).hasSize(6);
        String verification = text(files, "03-verification-rule.sql");
        assertThat(verification).contains("INSERT INTO WAA_VRFC_RULE (VRFC_ID, VRFC_NM, VRFC_RULE)")
                .contains("STNDRULE_0000001").doesNotContain("PT01").doesNotContain("PT02");
    }

    @Test void refusesUnapprovedOrChangedSnapshot() {
        ProjectSnapshot approved = approvedSnapshot();
        ProjectSnapshot changed = new ProjectSnapshot(approved.projectId(),approved.systemId(),approved.targetYear(),approved.deploymentYearMonth(),approved.defaultSchema(),ProjectStatus.APPROVED,
                List.of(row("VERIFICATION_RULE","CHANGED",Map.of("wdqId","STNDRULE_0000002","ruleName","변경","expression","X"))),List.of(),0,0,List.of(),approved.approvedBy(),approved.approvedAt(),approved.approvedSnapshotHash());
        assertThatThrownBy(() -> new DatasetSqlExporter().exportDatasetSql(changed)).isInstanceOf(IllegalStateException.class);
    }

    static ProjectSnapshot approvedSnapshot() {
        List<NormalizedRow> rows=List.of(
                row("SYSTEM","S",Map.of("wdqId","STNDDB_00000001","systemName","합성시스템","dbmsOriginal","MAILDB","schemaOriginal","APP")),
                row("EXCLUSION","E",Map.of("wdqId","STNDEXP_0000001","systemName","합성시스템","dbmsOriginal","MAILDB","schemaOriginal","APP","tableOriginal","TMP","columnOriginal","","exclusionType","TBL","reason","임시")),
                row("VERIFICATION_RULE","V",Map.of("wdqId","STNDRULE_0000001","ruleName","여부","expression","Y,N")),
                row("CODE_RULE","C",Map.of("wdqId","STNDCD_00000001","ruleName","코드","lookupSql","select code from codes")),
                row("COLUMN_MAPPING","M",Map.of("wdqId","STND_0000000001","dbmsOriginal","MAILDB","schemaOriginal","APP","tableOriginal","TB","columnOriginal","COL","ruleType","VERIFICATION","verificationRuleId","STNDRULE_0000001")),
                row("BUSINESS_RULE","B",Map.of("wdqId","STNDPRF_0000001","systemName","합성시스템","dbmsOriginal","MAILDB","schemaOriginal","APP","tableOriginal","TB","ruleName","업무","ruleKind","BUSINESS","ruleSql","select count(*) from TB")));
        ProjectSnapshot base=new ProjectSnapshot(1,1,2026,"202607","APP",ProjectStatus.VALIDATED,rows,List.of(),0,0,List.of(),null,null,null);
        return base.approved("담당자", OffsetDateTime.parse("2026-07-15T10:00:00+09:00"));
    }
    static NormalizedRow row(String type,String key,Map<String,String> values){return new NormalizedRow(type,key,values,List.of(),Integer.toHexString(values.hashCode()));}
    private String text(List<GeneratedFile> files,String name){return files.stream().filter(f->f.fileName().equals(name)).findFirst().map(f->new String(f.content(), StandardCharsets.UTF_8)).orElseThrow();}
}
