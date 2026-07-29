package kr.wise.csr.review;

import java.nio.file.Path;

public interface ReviewEngine {
    ReviewResult review(Path report, ReviewContext context);
}
