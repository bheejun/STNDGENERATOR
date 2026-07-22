package kr.wise.csr.project;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import kr.wise.csr.normalization.NormalizationSummary;
import kr.wise.csr.normalization.NormalizedRow;
import kr.wise.csr.approval.ApprovalService;
import kr.wise.csr.normalization.ProjectNormalizationService;
import kr.wise.csr.importfile.ImportBatch;
import kr.wise.csr.importfile.ImportCandidate;
import kr.wise.csr.importfile.WorkbookType;

@SpringBootTest
class ProjectSnapshotRepositoryTest {
    @Autowired ProjectSnapshotRepository repository;
    @Autowired JdbcTemplate jdbc;
    @Autowired ApprovalService approvalService;
    @Autowired ProjectNormalizationService normalizationService;
    long systemId;
    long projectId;

    @BeforeEach void setUp() {
        jdbc.update("delete from normalized_item"); jdbc.update("delete from data_conflict"); jdbc.update("delete from source_file");
        jdbc.update("delete from id_registry"); jdbc.update("delete from build_project"); jdbc.update("delete from standard_system");
        systemId=jdbc.queryForObject("insert into standard_system(system_code,system_name,dbms_type,dbms_physical_name,default_schema_original,default_schema_normalized) values('SNAP','합성','MARIADB','DB','APP','APP') returning id",Long.class);
        projectId=jdbc.queryForObject("insert into build_project(system_id,target_year,deployment_year_month,status) values(?,2026,'202607','IMPORTED') returning id",Long.class,systemId);
    }

    @Test void persistsAndLoadsNormalizedRowsAndStatus() {
        NormalizedRow row=new NormalizedRow("VERIFICATION_RULE","RULE",Map.of("ruleName","여부","expression","Y,N"),List.of(),"fingerprint");
        repository.save(new NormalizationSummary(projectId,List.of(row),List.of(),1,2,List.of()));
        ProjectSnapshot loaded=repository.findByProjectId(projectId).orElseThrow();
        assertThat(loaded.rows()).singleElement().extracting(NormalizedRow::logicalKey).isEqualTo("RULE");
        assertThat(loaded.status()).isEqualTo(ProjectStatus.NEEDS_REVIEW);
        assertThat(loaded.excludedPt01Count()).isEqualTo(1);
        assertThat(loaded.excludedPt02Count()).isEqualTo(2);
    }

    @Test void persistsApprovalIdentityAndSnapshotHash() {
        NormalizedRow row=new NormalizedRow("VERIFICATION_RULE","RULE",Map.of("wdqId","STNDRULE_0000001","ruleName","YN rule","expression","Y,N"),List.of(),"fingerprint");
        repository.save(new NormalizationSummary(projectId,List.of(row),List.of(),0,0,List.of()));
        approvalService.approve(projectId,"approver");
        ProjectSnapshot approved=repository.findByProjectId(projectId).orElseThrow();
        assertThat(approved.status()).isEqualTo(ProjectStatus.APPROVED);
        assertThat(approved.approvedBy()).isEqualTo("approver");
        assertThat(approved.isApprovalCurrent()).isTrue();
    }

    @Test void allocatesMissingWdqIdBeforePersistingSurvivingCandidate() {
        ImportCandidate candidate=new ImportCandidate("VERIFICATION_RULE","NEW-RULE",Map.of("ruleName","new","expression","Y,N"),"sheet",2);
        normalizationService.normalizeAndSave(projectId,systemId,List.of(new ImportBatch(WorkbookType.WISEDQ_RESULT,List.of(candidate),0,0,List.of())));
        String id=repository.findByProjectId(projectId).orElseThrow().rows().getFirst().values().get("wdqId");
        assertThat(id).startsWith("STNDRULE_");
    }
}
