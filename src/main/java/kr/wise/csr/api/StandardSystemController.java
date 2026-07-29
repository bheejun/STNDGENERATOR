package kr.wise.csr.api;

import java.util.List;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import kr.wise.csr.system.StandardSystemService;
import kr.wise.csr.project.ProjectCreationService;

@RestController
@RequestMapping("/api/systems")
public class StandardSystemController {
    private final StandardSystemService systems;
    private final ProjectCreationService projects;
    public StandardSystemController(StandardSystemService systems,ProjectCreationService projects) { this.systems=systems;this.projects=projects; }
    @GetMapping public List<StandardSystemService.SystemView> list(){ return systems.list(); }
    @PostMapping public StandardSystemService.SystemView create(@Valid @RequestBody SystemRequest r){
        return systems.create(command(r));
    }
    @PostMapping("/{id}/projects") public ProjectCreationService.CreatedProject createProject(@PathVariable long id,@Valid @RequestBody ProjectRequest r){
        return projects.createUnderSystem(id,r.targetYear,r.deploymentYearMonth);
    }
    @GetMapping("/{id}/id-policies") public List<StandardSystemService.IdPolicyView> policies(@PathVariable long id){ return systems.policies(id); }
    @PatchMapping("/{id}") public StandardSystemService.SystemView update(@PathVariable long id,@Valid @RequestBody SystemRequest r){
        return systems.update(id,command(r));
    }
    @DeleteMapping("/{id}") public void delete(@PathVariable long id){ systems.delete(id); }
    @PutMapping("/{id}/id-policies/{type}") public StandardSystemService.IdPolicyView policy(@PathVariable long id,@PathVariable String type,@Valid @RequestBody PolicyRequest r){
        return systems.updatePolicy(id,type,new StandardSystemService.UpdatePolicy(r.idPrefix,r.numberWidth,r.lastValue,r.active));
    }
    public record SystemRequest(@NotBlank String systemCode,@NotBlank String systemName,@NotBlank String dbmsType,
            @NotBlank String dbmsPhysicalName,@NotBlank String defaultSchema,
            @NotBlank @Pattern(regexp="^[0-9]{1,4}$", message="시스템 채번 영역은 1~4자리 숫자여야 합니다")
            String wdqNamespace,boolean active,String connectionLogicalName,String dbmsVersionCode,
            String connectionUrl,String driverName,String dbAccountId,String dbAccountPassword,
            String infoSystemCode,String infoSystemName,String organizationName,String criteriaPrefix){}
    public record PolicyRequest(@NotBlank String idPrefix,@Min(1) @Max(12) int numberWidth,@Min(0) long lastValue,boolean active){}
    public record ProjectRequest(@Min(2000) @Max(9999) int targetYear,@NotBlank @Pattern(regexp="^[0-9]{6}$") String deploymentYearMonth){}
    private StandardSystemService.UpdateSystem command(SystemRequest r) {
        return new StandardSystemService.UpdateSystem(r.systemCode,r.systemName,r.dbmsType,r.dbmsPhysicalName,
                r.defaultSchema,r.wdqNamespace,r.active,r.connectionLogicalName,r.dbmsVersionCode,r.connectionUrl,
                r.driverName,r.dbAccountId,r.dbAccountPassword,r.infoSystemCode,r.infoSystemName,r.organizationName,
                r.criteriaPrefix);
    }
}
