package kr.wise.csr.validation;

import java.util.List;

public record ValidationReport(List<ValidationIssue> issues, String snapshotHash) {
    public ValidationReport { issues = List.copyOf(issues); }
    public boolean hasErrors() { return issues.stream().anyMatch(issue -> issue.severity() == Severity.ERROR); }
}
