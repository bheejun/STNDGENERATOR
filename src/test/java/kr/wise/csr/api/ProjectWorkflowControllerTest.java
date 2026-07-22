package kr.wise.csr.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import kr.wise.csr.approval.ApprovalService;
import kr.wise.csr.export.DatasetSqlExporter;
import kr.wise.csr.export.GeneratedFile;
import kr.wise.csr.export.StandardWorkbookExporter;
import kr.wise.csr.importfile.ProjectImportService;
import kr.wise.csr.normalization.ProjectConflictService;
import kr.wise.csr.project.ProjectSnapshot;
import kr.wise.csr.project.ProjectSnapshotRepository;
import kr.wise.csr.project.ProjectStatus;
import kr.wise.csr.validation.ProjectValidator;

class ProjectWorkflowControllerTest {
    private final ProjectSnapshotRepository snapshots = mock(ProjectSnapshotRepository.class);
    private final ProjectValidator validator = new ProjectValidator();
    private final ApprovalService approvals = mock(ApprovalService.class);
    private final StandardWorkbookExporter workbooks = mock(StandardWorkbookExporter.class);
    private final DatasetSqlExporter sql = mock(DatasetSqlExporter.class);
    private final ProjectImportService imports = mock(ProjectImportService.class);
    private final ProjectConflictService conflicts = mock(ProjectConflictService.class);
    private ProjectWorkflowController controller;

    @BeforeEach
    void setUp() {
        controller = new ProjectWorkflowController(snapshots, validator, approvals, workbooks, sql, imports, conflicts);
    }

    @Test
    void returnsNotFoundForUnknownProject() {
        when(snapshots.findByProjectId(99)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.getProject(99))
                .isInstanceOf(ProjectNotFoundException.class)
                .hasMessageContaining("99");
    }

    @Test
    void approvesAndReturnsReloadedProject() {
        ProjectSnapshot before = snapshot(ProjectStatus.NEEDS_REVIEW, null, null, null);
        ProjectSnapshot after = snapshot(ProjectStatus.APPROVED, "담당자", OffsetDateTime.now(), before.contentHash());
        when(snapshots.findByProjectId(1)).thenReturn(Optional.of(after));

        ProjectSnapshot result = controller.approve(1, new ProjectWorkflowController.ApprovalRequest("담당자"));

        verify(approvals).approve(1, "담당자");
        assertThat(result.status()).isEqualTo(ProjectStatus.APPROVED);
    }

    @Test
    void packagesSqlFilesAsZipDownload() throws Exception {
        ProjectSnapshot project = snapshot(ProjectStatus.APPROVED, "담당자", OffsetDateTime.now(), null);
        when(snapshots.findByProjectId(1)).thenReturn(Optional.of(project));
        when(sql.exportDatasetSql(project)).thenReturn(List.of(
                new GeneratedFile("01.sql", "text/plain", "select 1;".getBytes(StandardCharsets.UTF_8))));

        byte[] body = controller.downloadSql(1).getBody();

        assertThat(body).isNotNull();
        try (ZipInputStream zip = new ZipInputStream(new java.io.ByteArrayInputStream(body))) {
            assertThat(zip.getNextEntry().getName()).isEqualTo("01.sql");
            assertThat(new String(zip.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("select 1;");
        }
    }

    private ProjectSnapshot snapshot(ProjectStatus status, String approver, OffsetDateTime approvedAt, String hash) {
        ProjectSnapshot base = new ProjectSnapshot(1, 2, 2026, "202607", "APP", status, List.of(), List.of(),
                0, 0, List.of(), approver, approvedAt, hash);
        if (status == ProjectStatus.APPROVED && hash == null) return base.approved(approver, approvedAt);
        return base;
    }
}
