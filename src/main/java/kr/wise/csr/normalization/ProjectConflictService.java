package kr.wise.csr.normalization;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.wise.csr.project.ProjectSnapshot;
import kr.wise.csr.project.ProjectSnapshotRepository;
import kr.wise.csr.registry.WdqIdAllocator;
import kr.wise.csr.registry.WdqIdType;

@Service
public class ProjectConflictService {
    private final ProjectSnapshotRepository snapshots;
    private final ConflictService conflicts;
    private final WdqIdAllocator ids;

    public ProjectConflictService(ProjectSnapshotRepository snapshots, ConflictService conflicts, WdqIdAllocator ids) {
        this.snapshots = snapshots;
        this.conflicts = conflicts;
        this.ids = ids;
    }

    @Transactional
    public ProjectSnapshot resolve(long projectId, ResolveConflictCommand command) {
        ProjectSnapshot project = snapshots.findByProjectId(projectId)
                .orElseThrow(() -> new IllegalArgumentException("프로젝트를 찾을 수 없습니다: " + projectId));
        NormalizationSummary summary = new NormalizationSummary(project.projectId(), project.rows(),
                project.conflicts(), project.excludedPt01Count(), project.excludedPt02Count(), project.importErrors());
        NormalizationSummary resolved = conflicts.resolve(summary, command);
        snapshots.save(assignMissingIds(resolved, project.systemId()));
        return snapshots.findByProjectId(projectId).orElseThrow();
    }

    private NormalizationSummary assignMissingIds(NormalizationSummary summary, long systemId) {
        List<NormalizedRow> assigned = new ArrayList<>();
        for (NormalizedRow row : summary.rows()) {
            if (!row.values().getOrDefault("wdqId", "").isBlank()) {
                assigned.add(row);
                continue;
            }
            Map<String, String> values = new LinkedHashMap<>(row.values());
            values.put("wdqId", ids.allocate(idType(row.dataType()), systemId, row.logicalKey(), summary.projectId()));
            assigned.add(new NormalizedRow(row.dataType(), row.logicalKey(), Map.copyOf(values), row.sources(),
                    NormalizationService.fingerprint(values)));
        }
        return new NormalizationSummary(summary.projectId(), List.copyOf(assigned), summary.conflicts(),
                summary.excludedPt01Count(), summary.excludedPt02Count(), summary.importErrors());
    }

    private WdqIdType idType(String dataType) {
        return switch (dataType) {
            case "SYSTEM" -> WdqIdType.DB_CONNECTION;
            case "EXCLUSION" -> WdqIdType.EXCLUSION;
            case "VERIFICATION_RULE" -> WdqIdType.VERIFICATION_RULE;
            case "CODE_RULE" -> WdqIdType.CODE_RULE;
            case "COLUMN_MAPPING" -> WdqIdType.COLUMN_MAPPING;
            case "BUSINESS_RULE" -> WdqIdType.BUSINESS_RULE;
            default -> throw new IllegalArgumentException("지원하지 않는 데이터 유형: " + dataType);
        };
    }
}
