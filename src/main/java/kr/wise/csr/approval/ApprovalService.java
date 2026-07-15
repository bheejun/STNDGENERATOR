package kr.wise.csr.approval;

import java.time.OffsetDateTime;

import kr.wise.csr.project.ProjectSnapshot;
import kr.wise.csr.validation.ProjectValidator;
import kr.wise.csr.validation.ValidationReport;

public class ApprovalService {
    private final ProjectValidator validator;
    public ApprovalService(ProjectValidator validator) { this.validator = validator; }

    public ProjectSnapshot approve(ProjectSnapshot project, String approverName) {
        if (approverName == null || approverName.isBlank()) throw new IllegalArgumentException("승인 담당자명이 필요합니다");
        ValidationReport report = validator.validate(project);
        if (report.hasErrors()) throw new ApprovalRejectedException("승인 차단 오류가 "
                + report.issues().stream().filter(i -> i.severity()==kr.wise.csr.validation.Severity.ERROR).count() + "건 있습니다");
        return project.approved(approverName.trim(), OffsetDateTime.now());
    }
}
