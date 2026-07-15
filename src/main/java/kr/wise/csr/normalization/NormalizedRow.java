package kr.wise.csr.normalization;

import java.util.List;
import java.util.Map;

public record NormalizedRow(String dataType, String logicalKey, Map<String, String> values,
        List<SourceReference> sources, String fingerprint) {
}
