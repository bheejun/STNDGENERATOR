package kr.wise.csr.api;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import kr.wise.csr.project.ProjectCriteriaManagementService;

@RestController
@RequestMapping("/api/projects/{projectId}/criteria")
public class ProjectCriteriaManagementController {
    private final ProjectCriteriaManagementService criteria;

    public ProjectCriteriaManagementController(ProjectCriteriaManagementService criteria) { this.criteria = criteria; }

    @GetMapping
    public List<ProjectCriteriaManagementService.ManagedCriteria> list(@PathVariable long projectId) {
        return criteria.list(projectId);
    }

    @PatchMapping("/{itemId}")
    public ProjectCriteriaManagementService.ManagedCriteria update(@PathVariable long projectId,
            @PathVariable long itemId, @RequestBody UpdateCriteriaRequest request) {
        return criteria.update(projectId, itemId, request.values());
    }

    public record UpdateCriteriaRequest(Map<String, String> values) { }
}
