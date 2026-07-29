package kr.wise.csr.review;

public record ReviewIssue(String code, ReviewSeverity severity, String message, String sheetName,
        Integer rowNumber, String evidence, boolean blocksAdoption) {
}
