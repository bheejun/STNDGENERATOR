package kr.wise.csr.api;

import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import kr.wise.csr.project.ProjectCreationService;
import kr.wise.csr.project.ProjectBootstrapService;

@RestController
@RequestMapping("/api/projects")
public class ProjectManagementController {
    private final ProjectCreationService projects;
    private final ProjectBootstrapService bootstrap;

    public ProjectManagementController(ProjectCreationService projects, ProjectBootstrapService bootstrap) {
        this.projects = projects;
        this.bootstrap = bootstrap;
    }

    @PostMapping(value = "/from-result-report", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ProjectBootstrapService.BootstrappedProject createFromResultReport(
            @RequestParam @NotBlank String systemCode,
            @RequestParam @Min(2000) @Max(9999) int targetYear,
            @RequestParam @NotBlank @Pattern(regexp = "^[0-9]{6}$") String deploymentYearMonth,
            @RequestPart("file") MultipartFile file) {
        return bootstrap.createFromResultReport(systemCode, targetYear, deploymentYearMonth, file);
    }

    @PostMapping(value = "/from-criteria", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ProjectBootstrapService.BootstrappedProject createFromCriteria(
            @RequestParam @NotBlank String systemCode,
            @RequestParam @NotBlank String systemName,
            @RequestParam @NotBlank String dbmsType,
            @RequestParam @NotBlank String dbmsPhysicalName,
            @RequestParam @NotBlank String defaultSchema,
            @RequestParam @Min(2000) @Max(9999) int targetYear,
            @RequestParam @NotBlank @Pattern(regexp = "^[0-9]{6}$") String deploymentYearMonth,
            @RequestPart("files") List<MultipartFile> files) {
        return bootstrap.createFromCriteria(new ProjectCreationService.CreateProject(systemCode, systemName,
                dbmsType, dbmsPhysicalName, defaultSchema, targetYear, deploymentYearMonth), files);
    }

    @PostMapping
    public ProjectCreationService.CreatedProject create(@Valid @RequestBody CreateProjectRequest request) {
        return projects.create(new ProjectCreationService.CreateProject(request.systemCode(), request.systemName(),
                request.dbmsType(), request.dbmsPhysicalName(), request.defaultSchema(), request.targetYear(),
                request.deploymentYearMonth()));
    }

    public record CreateProjectRequest(
            @NotBlank String systemCode,
            @NotBlank String systemName,
            @NotBlank String dbmsType,
            @NotBlank String dbmsPhysicalName,
            @NotBlank String defaultSchema,
            @Min(2000) @Max(9999) int targetYear,
            @NotBlank @Pattern(regexp = "^[0-9]{6}$", message = "배포년월은 YYYYMM 형식이어야 합니다") String deploymentYearMonth) {
    }
}
