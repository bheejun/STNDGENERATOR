package kr.wise.csr.api;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import kr.wise.csr.review.ReportReviewService;

@RestController
@RequestMapping("/api/projects/{projectId}/report-reviews")
public class ReportReviewController {
    private final ReportReviewService reviews;

    public ReportReviewController(ReportReviewService reviews) {
        this.reviews = reviews;
    }

    @GetMapping("/latest")
    public ResponseEntity<ReportReviewService.ReviewView> latest(@PathVariable long projectId) {
        ReportReviewService.ReviewView result = reviews.latest(projectId);
        return result == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(result);
    }

    @GetMapping
    public List<ReportReviewService.ReviewSummary> history(@PathVariable long projectId) {
        return reviews.history(projectId);
    }
}
