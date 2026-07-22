package kr.wise.csr.project;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import kr.wise.csr.importfile.ProjectImportService;
import kr.wise.csr.importfile.WisedqReportMetadata;
import kr.wise.csr.importfile.WisedqReportMetadataExtractor;

@Service
public class ProjectBootstrapService {
    private final WisedqReportMetadataExtractor metadataExtractor;
    private final ProjectCreationService projects;
    private final ProjectImportService imports;

    public ProjectBootstrapService(WisedqReportMetadataExtractor metadataExtractor, ProjectCreationService projects,
            ProjectImportService imports) {
        this.metadataExtractor = metadataExtractor;
        this.projects = projects;
        this.imports = imports;
    }

    @Transactional
    public BootstrappedProject createFromResultReport(String systemCode, int targetYear, String deploymentYearMonth,
            ProjectCreationService.DuplicateHandling duplicateHandling, MultipartFile report) {
        WisedqReportMetadata metadata = metadataExtractor.extract(report);
        ProjectCreationService.CreatedProject project = projects.create(new ProjectCreationService.CreateProject(
                systemCode, metadata.systemName(), metadata.dbmsType(), metadata.dbmsName(), metadata.schemaName(),
                targetYear, deploymentYearMonth, duplicateHandling));
        ProjectImportService.ImportResult imported = imports.importFiles(project.projectId(), List.of(report));
        return new BootstrappedProject(project, metadata, imported);
    }

    @Transactional
    public BootstrappedProject createFromCriteria(ProjectCreationService.CreateProject command,
            List<MultipartFile> criteriaFiles) {
        ProjectCreationService.CreatedProject project = projects.create(command);
        ProjectImportService.ImportResult imported = imports.importFiles(project.projectId(), criteriaFiles);
        if (imported.inputTrack() != ProjectImportService.InputTrack.CRITERIA_FILES)
            throw new IllegalArgumentException("진단기준 경로에는 결과보고서를 포함할 수 없습니다");
        return new BootstrappedProject(project, null, imported);
    }

    public record BootstrappedProject(ProjectCreationService.CreatedProject project,
            WisedqReportMetadata metadata, ProjectImportService.ImportResult importResult) {
    }
}
