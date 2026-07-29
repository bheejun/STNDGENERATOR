package kr.wise.csr.review;

import java.util.List;
import java.util.Map;

public record ReviewResult(String engineVersion, String fileSha256, ReviewVerdict verdict,
        boolean criteriaAdoptable, Map<String, String> metadata, Map<String, Long> metrics,
        List<ReviewIssue> issues) {
    public ReviewResult {
        metadata = Map.copyOf(metadata);
        metrics = Map.copyOf(metrics);
        issues = List.copyOf(issues);
    }
}
