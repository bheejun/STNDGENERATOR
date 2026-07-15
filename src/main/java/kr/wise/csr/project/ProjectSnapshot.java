package kr.wise.csr.project;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;

import kr.wise.csr.normalization.DataConflict;
import kr.wise.csr.normalization.NormalizedRow;

public record ProjectSnapshot(long projectId, long systemId, int targetYear, String deploymentYearMonth,
        String defaultSchema, ProjectStatus status, List<NormalizedRow> rows, List<DataConflict> conflicts,
        int excludedPt01Count, int excludedPt02Count, List<String> importErrors,
        String approvedBy, OffsetDateTime approvedAt, String approvedSnapshotHash) {

    public ProjectSnapshot {
        rows = List.copyOf(rows); conflicts = List.copyOf(conflicts); importErrors = List.copyOf(importErrors);
    }

    public String contentHash() {
        StringBuilder content = new StringBuilder().append(projectId).append('|').append(systemId).append('|')
                .append(targetYear).append('|').append(deploymentYearMonth).append('|').append(defaultSchema).append('\n');
        rows.stream().sorted(java.util.Comparator.comparing(NormalizedRow::dataType).thenComparing(NormalizedRow::logicalKey))
                .forEach(row -> content.append(row.dataType()).append('|').append(row.logicalKey()).append('|').append(row.fingerprint()).append('\n'));
        conflicts.stream().sorted(java.util.Comparator.comparingLong(DataConflict::id))
                .forEach(c -> content.append("CONFLICT|").append(c.id()).append('|').append(c.resolution()).append('\n'));
        content.append("PT|").append(excludedPt01Count).append('|').append(excludedPt02Count);
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.toString().getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    public ProjectSnapshot approved(String approver, OffsetDateTime time) {
        String hash = contentHash();
        return new ProjectSnapshot(projectId, systemId, targetYear, deploymentYearMonth, defaultSchema,
                ProjectStatus.APPROVED, rows, conflicts, excludedPt01Count, excludedPt02Count, importErrors,
                approver, time, hash);
    }

    public boolean isApprovalCurrent() {
        return status == ProjectStatus.APPROVED && approvedSnapshotHash != null && approvedSnapshotHash.equals(contentHash());
    }
}
