package kr.wise.csr.validation;

import java.util.List;
import kr.wise.csr.project.ProjectSnapshot;

public interface ValidationRule {
    List<ValidationIssue> validate(ProjectSnapshot project);
}
