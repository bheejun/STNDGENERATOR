package kr.wise.csr.normalization;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.wise.csr.project.ProjectSnapshot;
import kr.wise.csr.project.ProjectSnapshotRepository;

@Service
public class ProjectConflictService {
    private final ProjectSnapshotRepository snapshots;
    private final ConflictService conflicts;

    public ProjectConflictService(ProjectSnapshotRepository snapshots, ConflictService conflicts) {
        this.snapshots = snapshots;
        this.conflicts = conflicts;
    }

    @Transactional
    public ProjectSnapshot resolve(long projectId, ResolveConflictCommand command) {
        ProjectSnapshot project = snapshots.findByProjectId(projectId)
                .orElseThrow(() -> new IllegalArgumentException("프로젝트를 찾을 수 없습니다: " + projectId));
        NormalizationSummary summary = new NormalizationSummary(project.projectId(), project.rows(),
                project.conflicts(), project.excludedPt01Count(), project.excludedPt02Count(), project.importErrors());
        snapshots.save(conflicts.resolve(summary, command));
        return snapshots.findByProjectId(projectId).orElseThrow();
    }
}
