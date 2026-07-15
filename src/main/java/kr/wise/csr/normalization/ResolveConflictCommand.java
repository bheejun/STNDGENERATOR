package kr.wise.csr.normalization;

public record ResolveConflictCommand(long conflictId, ConflictResolution resolution,
        String manualValue, String reason) {
}
