package kr.wise.csr.sqlcompare;

import java.time.OffsetDateTime;

public record SqlBaselineInfo(
        long projectId,
        String originalName,
        String sha256,
        long byteSize,
        OffsetDateTime uploadedAt) {
}
