package kr.wise.csr.project;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import kr.wise.csr.normalization.NormalizationService;

@Service
public class ProjectCriteriaManagementService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public ProjectCriteriaManagementService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.json = new ObjectMapper().findAndRegisterModules();
    }

    public List<ManagedCriteria> list(long projectId) {
        requireProject(projectId);
        return jdbc.query("select id,data_type,logical_key,values_json::text,fingerprint from normalized_item where project_id=? order by data_type,logical_key",
                (rs, rowNum) -> new ManagedCriteria(rs.getLong(1), projectId, rs.getString(2), rs.getString(3),
                        read(rs.getString(4)), rs.getString(5).trim()), projectId);
    }

    @Transactional
    public ManagedCriteria update(long projectId, long itemId, Map<String, String> requestedValues) {
        ManagedCriteria current = jdbc.query("select id,data_type,logical_key,values_json::text,fingerprint from normalized_item where project_id=? and id=?",
                rs -> rs.next() ? new ManagedCriteria(rs.getLong(1), projectId, rs.getString(2), rs.getString(3),
                        read(rs.getString(4)), rs.getString(5).trim()) : null, projectId, itemId);
        if (current == null) throw new IllegalArgumentException("진단기준 항목을 찾을 수 없습니다: " + itemId);
        if (requestedValues == null || requestedValues.isEmpty()) throw new IllegalArgumentException("수정할 값이 필요합니다");

        Map<String, String> values = new LinkedHashMap<>(requestedValues);
        String wdqId = current.values().get("wdqId");
        if (wdqId != null) values.put("wdqId", wdqId);
        String fingerprint = NormalizationService.fingerprint(values);
        jdbc.update("update normalized_item set values_json=?::jsonb,fingerprint=? where id=? and project_id=?",
                write(values), fingerprint, itemId, projectId);
        jdbc.update("update build_project set status='NEEDS_REVIEW',approved_by=null,approved_at=null,approved_snapshot_hash=null,updated_at=now() where id=?",
                projectId);
        return new ManagedCriteria(itemId, projectId, current.dataType(), current.logicalKey(), Map.copyOf(values), fingerprint);
    }

    private void requireProject(long projectId) {
        Integer count = jdbc.queryForObject("select count(*) from build_project where id=?", Integer.class, projectId);
        if (count == null || count == 0) throw new IllegalArgumentException("프로젝트를 찾을 수 없습니다: " + projectId);
    }
    private Map<String, String> read(String value) {
        try { return json.readValue(value, new TypeReference<>() { }); }
        catch (JsonProcessingException e) { throw new IllegalStateException(e); }
    }
    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalStateException(e); }
    }

    public record ManagedCriteria(long id, long projectId, String dataType, String logicalKey,
            Map<String, String> values, String fingerprint) { }
}
