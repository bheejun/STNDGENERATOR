package kr.wise.csr.api;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import kr.wise.csr.project.ProjectDeletionService;

@RestController
@RequestMapping("/api/projects")
public class ProjectDeletionController {
    private final ProjectDeletionService projects;

    public ProjectDeletionController(ProjectDeletionService projects) {
        this.projects = projects;
    }

    @DeleteMapping("/{projectId}")
    public ProjectDeletionService.DeletedProject delete(@PathVariable long projectId) {
        return projects.delete(projectId);
    }
}
