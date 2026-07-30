package kr.wise.csr.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import kr.wise.csr.normalization.NormalizedRow;
import kr.wise.csr.project.ProjectSnapshot;
import kr.wise.csr.project.ProjectStatus;
import kr.wise.csr.validation.ProjectValidator;

class ApprovalServiceTest {
    ApprovalService service = new ApprovalService(new ProjectValidator());

    @Test void rejectsBlankApproverAndValidationErrors() {
        assertThatThrownBy(() -> service.approve(valid(), " ")).isInstanceOf(IllegalArgumentException.class);
        ProjectSnapshot invalid = new ProjectSnapshot(1,1,2026,"202607","APP",ProjectStatus.NEEDS_REVIEW,
                List.of(),List.of(),0,0,List.of("파싱 오류"),null,null,null);
        assertThatThrownBy(() -> service.approve(invalid, "담당자")).isInstanceOf(ApprovalRejectedException.class);
    }

    @Test void approvalStoresPersonTimeAndExactSnapshotHash() {
        ProjectSnapshot approved = service.approve(valid(), "홍길동");
        assertThat(approved.status()).isEqualTo(ProjectStatus.APPROVED);
        assertThat(approved.approvedBy()).isEqualTo("홍길동");
        assertThat(approved.approvedAt()).isNotNull();
        assertThat(approved.approvedSnapshotHash()).hasSize(64).isEqualTo(approved.contentHash());
    }

    private ProjectSnapshot valid() {
        var row = new NormalizedRow("VERIFICATION_RULE","R",Map.of(
                "wdqId","STNDRULE_0000001","ruleName","여부","expression","Y,N",
                "qualityIndicator","여부 도메인"),List.of(),"f");
        return new ProjectSnapshot(1,1,2026,"202607","APP",ProjectStatus.VALIDATED,List.of(row),List.of(),0,0,List.of(),null,null,null);
    }
}
