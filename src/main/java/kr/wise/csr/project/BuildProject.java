package kr.wise.csr.project;

import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.Map;

import jakarta.persistence.Entity;
import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "build_project")
public class BuildProject {
    private static final Map<ProjectStatus, EnumSet<ProjectStatus>> TRANSITIONS = Map.of(
            ProjectStatus.DRAFT, EnumSet.of(ProjectStatus.IMPORTED),
            ProjectStatus.IMPORTED, EnumSet.of(ProjectStatus.NEEDS_REVIEW),
            ProjectStatus.NEEDS_REVIEW, EnumSet.of(ProjectStatus.VALIDATED),
            ProjectStatus.VALIDATED, EnumSet.of(ProjectStatus.APPROVED),
            ProjectStatus.APPROVED, EnumSet.of(ProjectStatus.GENERATED),
            ProjectStatus.GENERATED, EnumSet.noneOf(ProjectStatus.class));

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private long systemId;
    private int targetYear;
    @Column(length = 6)
    private String deploymentYearMonth;
    @Enumerated(EnumType.STRING)
    private ProjectStatus status = ProjectStatus.DRAFT;
    private boolean active = true;
    private String approvedBy;
    private OffsetDateTime approvedAt;
    private String notes;
    @Version
    private long version;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    protected BuildProject() {}

    public BuildProject(long systemId, int targetYear, String deploymentYearMonth) {
        this.systemId = systemId;
        this.targetYear = targetYear;
        this.deploymentYearMonth = deploymentYearMonth;
    }

    public void transitionTo(ProjectStatus next) {
        if (!TRANSITIONS.get(status).contains(next)) {
            throw new IllegalStateException("Invalid project status transition: " + status + " -> " + next);
        }
        status = next;
        updatedAt = OffsetDateTime.now();
    }

    public void markDirty() {
        if (status == ProjectStatus.APPROVED || status == ProjectStatus.GENERATED) {
            status = ProjectStatus.NEEDS_REVIEW;
            approvedBy = null;
            approvedAt = null;
        }
        updatedAt = OffsetDateTime.now();
    }

    public ProjectStatus getStatus() { return status; }
    public Long getId() { return id; }
}
