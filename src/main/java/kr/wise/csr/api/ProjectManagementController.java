package kr.wise.csr.api;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import kr.wise.csr.project.ProjectCreationService;

@RestController
@RequestMapping("/api/projects")
public class ProjectManagementController {
    private final ProjectCreationService projects;

    public ProjectManagementController(ProjectCreationService projects) {
        this.projects = projects;
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
