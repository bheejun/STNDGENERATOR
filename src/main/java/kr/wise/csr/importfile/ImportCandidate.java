package kr.wise.csr.importfile;

import java.util.Map;

public record ImportCandidate(String dataType, String logicalKey, Map<String, String> values,
        String sourceSheet, int sourceRow) {
}
