package kr.wise.csr.normalization;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.wise.csr.importfile.ImportBatch;
import kr.wise.csr.project.ProjectSnapshotRepository;
import kr.wise.csr.registry.WdqIdAllocator;
import kr.wise.csr.registry.WdqIdType;

@Service
public class ProjectNormalizationService {
    private final NormalizationService normalization = new NormalizationService();
    private final WdqIdAllocator ids;
    private final ProjectSnapshotRepository snapshots;

    public ProjectNormalizationService(WdqIdAllocator ids, ProjectSnapshotRepository snapshots) {
        this.ids = ids;
        this.snapshots = snapshots;
    }

    @Transactional
    public NormalizationSummary normalizeAndSave(long projectId, long systemId, List<ImportBatch> batches) {
        NormalizationSummary merged = normalization.normalize(projectId, batches);
        Set<String> conflicted = new HashSet<>();
        merged.conflicts().stream()
                .filter(conflict -> !conflict.resolved())
                .forEach(conflict -> conflicted.add(conflictKey(conflict.dataType(), conflict.logicalKey())));

        List<NormalizedRow> assigned = new ArrayList<>();
        for (NormalizedRow row : merged.rows()) {
            if (row.dataType().equals("CODE_VALUE")) {
                assigned.add(row);
                continue;
            }
            if (conflicted.contains(conflictKey(row.dataType(), row.logicalKey()))) {
                assigned.add(row);
                continue;
            }
            WdqIdType type = idType(row.dataType());
            Map<String, String> values = new LinkedHashMap<>(row.values());
            String existing = values.get("wdqId");
            if (existing == null || existing.isBlank()) {
                values.put("wdqId", ids.allocate(type, systemId, row.logicalKey(), projectId));
            } else if (row.dataType().equals("VERIFICATION_RULE")
                    && (existing.toUpperCase(java.util.Locale.ROOT).startsWith("STAT_")
                            || existing.toUpperCase(java.util.Locale.ROOT).startsWith("VRF1_"))) {
                // WDQ built-in verification rules are referenced as-is and are not system-owned IDs.
            } else {
                ids.registerExisting(type, systemId, row.logicalKey(), existing, projectId);
            }
            assigned.add(new NormalizedRow(row.dataType(), row.logicalKey(), Map.copyOf(values), row.sources(),
                    NormalizationService.fingerprint(values)));
        }
        NormalizationSummary result = new NormalizationSummary(projectId, List.copyOf(assigned), merged.conflicts(),
                merged.excludedPt01Count(), merged.excludedPt02Count(), merged.importErrors());
        result = snapshots.applyOverrides(result);
        snapshots.save(result);
        return result;
    }

    private String conflictKey(String dataType, String logicalKey) {
        return dataType + "|" + logicalKey;
    }

    private WdqIdType idType(String dataType) {
        return switch (dataType) {
            case "SYSTEM" -> WdqIdType.DB_CONNECTION;
            case "EXCLUSION", "EXCLUSION_PATTERN" -> WdqIdType.EXCLUSION;
            case "VERIFICATION_RULE" -> WdqIdType.VERIFICATION_RULE;
            case "CODE_RULE" -> WdqIdType.CODE_RULE;
            case "COLUMN_MAPPING" -> WdqIdType.COLUMN_MAPPING;
            case "BUSINESS_RULE" -> WdqIdType.BUSINESS_RULE;
            default -> throw new IllegalArgumentException("지원하지 않는 데이터 유형: " + dataType);
        };
    }
}
