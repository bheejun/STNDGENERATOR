package kr.wise.csr.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class BuildProjectTest {
    @Test
    void followsTheDefinedLifecycle() {
        BuildProject project = new BuildProject(1, 2026, "202607");
        project.transitionTo(ProjectStatus.IMPORTED);
        project.transitionTo(ProjectStatus.NEEDS_REVIEW);
        project.transitionTo(ProjectStatus.VALIDATED);
        project.transitionTo(ProjectStatus.APPROVED);
        project.transitionTo(ProjectStatus.GENERATED);
        assertThat(project.getStatus()).isEqualTo(ProjectStatus.GENERATED);
    }

    @Test
    void rejectsSkippedStates() {
        BuildProject project = new BuildProject(1, 2026, "202607");
        assertThatThrownBy(() -> project.transitionTo(ProjectStatus.APPROVED))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void editingApprovedOrGeneratedProjectRevokesApproval() {
        BuildProject project = new BuildProject(1, 2026, "202607");
        project.transitionTo(ProjectStatus.IMPORTED);
        project.transitionTo(ProjectStatus.NEEDS_REVIEW);
        project.transitionTo(ProjectStatus.VALIDATED);
        project.transitionTo(ProjectStatus.APPROVED);
        project.markDirty();
        assertThat(project.getStatus()).isEqualTo(ProjectStatus.NEEDS_REVIEW);
    }
}
