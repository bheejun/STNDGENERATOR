package kr.wise.csr.normalization;

import kr.wise.csr.importfile.ImportCandidate;

public class MergeKeyFactory {
    public String create(ImportCandidate candidate) {
        String logicalKey = candidate.logicalKey() == null ? "" : candidate.logicalKey().trim();
        if (logicalKey.isBlank()) throw new IllegalArgumentException("논리키가 없습니다: " + candidate.dataType());
        return candidate.dataType().trim() + "|" + logicalKey;
    }
}
