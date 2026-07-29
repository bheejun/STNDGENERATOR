package kr.wise.csr.approval;

import java.time.OffsetDateTime;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.wise.csr.project.ProjectSnapshot;
import kr.wise.csr.project.ProjectSnapshotRepository;
import kr.wise.csr.review.ReportReviewService;
import kr.wise.csr.validation.ProjectValidator;
import kr.wise.csr.validation.Severity;
import kr.wise.csr.validation.ValidationReport;

@Service
public class ApprovalService {
    private final ProjectValidator validator;
    private final ProjectSnapshotRepository snapshots;
    private final ReportReviewService reportReviews;

    @Autowired
    public ApprovalService(ProjectValidator validator, ProjectSnapshotRepository snapshots,
            ReportReviewService reportReviews) {
        this.validator = validator;
        this.snapshots = snapshots;
        this.reportReviews = reportReviews;
    }

    ApprovalService(ProjectValidator validator) {
        this.validator = validator;
        this.snapshots = null;
        this.reportReviews = null;
    }

    public ProjectSnapshot approve(ProjectSnapshot project, String approverName) {
        if (approverName == null || approverName.isBlank()) {
            throw new IllegalArgumentException("승인 담당자명이 필요합니다");
        }
        ValidationReport report = validator.validate(project);
        if (report.hasErrors()) {
            long errorCount = report.issues().stream()
                    .filter(issue -> issue.severity() == Severity.ERROR)
                    .count();
            throw new ApprovalRejectedException("승인 차단 오류가 " + errorCount + "건 있습니다");
        }
        return project.approved(approverName.trim(), OffsetDateTime.now());
    }

    @Transactional
    public void approve(long projectId, String approverName) {
        if (snapshots == null) {
            throw new IllegalStateException("프로젝트 저장소가 구성되지 않았습니다");
        }
        ProjectSnapshot project = snapshots.findByProjectId(projectId)
                .orElseThrow(() -> new IllegalArgumentException("프로젝트를 찾을 수 없습니다: " + projectId));
        ReportReviewService.ReviewView reportReview = reportReviews == null ? null : reportReviews.latest(projectId);
        if (reportReview != null && !reportReview.criteriaAdoptable())
            throw new ApprovalRejectedException("결과보고서 검토가 부적합하여 승인할 수 없습니다");
        ProjectSnapshot approved = approve(project, approverName);
        snapshots.markApproved(projectId, approved.approvedBy(), approved.approvedAt(),
                approved.approvedSnapshotHash());
    }
}
