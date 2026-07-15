package kr.wise.csr.validation;

public record ValidationIssue(Severity severity, String code, String message, String logicalKey) {
}
