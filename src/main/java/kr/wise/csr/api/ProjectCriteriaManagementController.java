package kr.wise.csr.api;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

import kr.wise.csr.importfile.CodeValueExcelImportService;
import kr.wise.csr.project.ProjectCriteriaManagementService;

@RestController
@RequestMapping("/api/projects/{projectId}/criteria")
public class ProjectCriteriaManagementController {
    private final ProjectCriteriaManagementService criteria;
    private final CodeValueExcelImportService codeValueExcel;

    public ProjectCriteriaManagementController(ProjectCriteriaManagementService criteria,
            CodeValueExcelImportService codeValueExcel) {
        this.criteria = criteria;
        this.codeValueExcel = codeValueExcel;
    }

    @GetMapping
    public List<ProjectCriteriaManagementService.ManagedCriteria> list(@PathVariable long projectId) {
        return criteria.list(projectId);
    }

    @PatchMapping("/{itemId}")
    public ProjectCriteriaManagementService.ManagedCriteria update(@PathVariable long projectId,
            @PathVariable long itemId, @RequestBody UpdateCriteriaRequest request) {
        return criteria.update(projectId, itemId, request.values());
    }

    @PatchMapping("/bulk")
    public List<ProjectCriteriaManagementService.ManagedCriteria> bulkUpdate(@PathVariable long projectId,
            @RequestBody BulkUpdateCriteriaRequest request) {
        return criteria.bulkUpdate(projectId, request.itemIds(), request.field(), request.mode(),
                request.find(), request.value());
    }

    @PostMapping
    public ProjectCriteriaManagementService.ManagedCriteria create(@PathVariable long projectId,
            @RequestBody CreateCriteriaRequest request) {
        return criteria.create(projectId, request.dataType(), request.logicalKey(), request.values());
    }

    @DeleteMapping("/{itemId}")
    public void delete(@PathVariable long projectId, @PathVariable long itemId) {
        criteria.delete(projectId, itemId);
    }

    @GetMapping("/{itemId}/usage")
    public ProjectCriteriaManagementService.DeleteUsage usage(@PathVariable long projectId,
            @PathVariable long itemId) {
        return criteria.deleteUsage(projectId, itemId);
    }

    @GetMapping("/history")
    public List<ProjectCriteriaManagementService.ChangeHistory> history(@PathVariable long projectId) {
        return criteria.history(projectId);
    }

    @PostMapping(value="/code-values/import",consumes="multipart/form-data")
    public CodeValueExcelImportService.ImportResult importCodeValues(@PathVariable long projectId,
            @RequestParam String ruleName,@RequestParam MultipartFile file) {
        return codeValueExcel.replace(projectId,ruleName,file);
    }

    public record UpdateCriteriaRequest(Map<String, String> values) { }
    public record BulkUpdateCriteriaRequest(List<Long> itemIds,String field,String mode,String find,String value) { }
    public record CreateCriteriaRequest(String dataType, String logicalKey, Map<String, String> values) { }
}
