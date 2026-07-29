package kr.wise.csr.export;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import kr.wise.csr.project.ProjectSnapshot;

class WdqDeleteSqlExporterTest {
    @Test
    void deletesOnlyProjectOwnedVerificationRules() {
        ProjectSnapshot base = DatasetSqlExporterTest.approvedSnapshot();
        ProjectSnapshot snapshot = new ProjectSnapshot(base.projectId(), base.systemId(), base.targetYear(),
                base.deploymentYearMonth(), base.defaultSchema(), base.systemName(), base.dbmsPhysicalName(),
                base.dbmsType(), "STNDDB_00000001", base.connectionTarget(), base.status(), base.rows(),
                base.conflicts(), base.excludedPt01Count(), base.excludedPt02Count(), base.importErrors(),
                base.approvedBy(), base.approvedAt(), base.approvedSnapshotHash());

        GeneratedFile file = new WdqDeleteSqlExporter().export(snapshot);
        String sql = new String(file.content(), StandardCharsets.UTF_8);

        assertThat(sql)
                .contains("DELETE FROM WAA_VRFC_RULE WHERE VRFC_ID IN ('STNDRULE_0000001')")
                .doesNotContain("STAT_00000000001")
                .doesNotContain("VRF1_");
    }
}
