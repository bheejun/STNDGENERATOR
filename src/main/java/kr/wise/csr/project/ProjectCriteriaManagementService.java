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
import kr.wise.csr.registry.WdqIdAllocator;
import kr.wise.csr.registry.WdqIdType;

@Service
public class ProjectCriteriaManagementService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final WdqIdAllocator ids;

    public ProjectCriteriaManagementService(JdbcTemplate jdbc, WdqIdAllocator ids) {
        this.jdbc = jdbc;
        this.ids = ids;
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
        history(projectId, current.dataType(), String.valueOf(itemId), "UPDATE", current.values(), values);
        invalidateApproval(projectId);
        return new ManagedCriteria(itemId, projectId, current.dataType(), current.logicalKey(), Map.copyOf(values), fingerprint);
    }

    @Transactional
    public ManagedCriteria create(long projectId, String dataType, String logicalKey, Map<String, String> requestedValues) {
        ProjectIdentity project = project(projectId);
        WdqIdType idType = idType(dataType);
        if (logicalKey == null || logicalKey.isBlank()) throw new IllegalArgumentException("논리키가 필요합니다");
        if (requestedValues == null || requestedValues.isEmpty()) throw new IllegalArgumentException("등록할 값이 필요합니다");
        Map<String, String> values = new LinkedHashMap<>(requestedValues);
        values.put("wdqId", ids.allocate(idType, project.systemId(), logicalKey.trim(), projectId));
        String fingerprint = NormalizationService.fingerprint(values);
        long itemId = jdbc.queryForObject("""
                insert into normalized_item(project_id,data_type,logical_key,values_json,sources_json,fingerprint)
                values(?,?,?,?::jsonb,'[]'::jsonb,?) returning id
                """, Long.class, projectId, dataType, logicalKey.trim(), write(values), fingerprint);
        history(projectId, dataType, String.valueOf(itemId), "CREATE", null, values);
        invalidateApproval(projectId);
        return new ManagedCriteria(itemId, projectId, dataType, logicalKey.trim(), Map.copyOf(values), fingerprint);
    }

    @Transactional
    public void delete(long projectId, long itemId) {
        ManagedCriteria current = jdbc.query("select id,data_type,logical_key,values_json::text,fingerprint from normalized_item where project_id=? and id=?",
                rs -> rs.next() ? new ManagedCriteria(rs.getLong(1), projectId, rs.getString(2), rs.getString(3),
                        read(rs.getString(4)), rs.getString(5).trim()) : null, projectId, itemId);
        if (current == null) throw new IllegalArgumentException("진단기준 항목을 찾을 수 없습니다: " + itemId);
        history(projectId, current.dataType(), String.valueOf(itemId), "DELETE", current.values(), null);
        jdbc.update("delete from normalized_item where id=? and project_id=?", itemId, projectId);
        invalidateApproval(projectId);
    }

    public List<ChangeHistory> history(long projectId) {
        requireProject(projectId);
        return jdbc.query("""
                select id,entity_type,entity_id,action,before_json::text,after_json::text,changed_at
                from project_change_history where project_id=? order by changed_at desc,id desc limit 500
                """, (rs, rowNum) -> new ChangeHistory(rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), readNullable(rs.getString(5)), readNullable(rs.getString(6)),
                        rs.getObject(7, java.time.OffsetDateTime.class)), projectId);
    }

    private void requireProject(long projectId) {
        Integer count = jdbc.queryForObject("select count(*) from build_project where id=?", Integer.class, projectId);
        if (count == null || count == 0) throw new IllegalArgumentException("프로젝트를 찾을 수 없습니다: " + projectId);
    }
    private ProjectIdentity project(long projectId) {
        ProjectIdentity value = jdbc.query("select id,system_id from build_project where id=?",
                rs -> rs.next() ? new ProjectIdentity(rs.getLong(1), rs.getLong(2)) : null, projectId);
        if (value == null) throw new IllegalArgumentException("프로젝트를 찾을 수 없습니다: " + projectId);
        return value;
    }
    private WdqIdType idType(String dataType) {
        try {
            WdqIdType type = WdqIdType.valueOf(dataType);
            if (type == WdqIdType.DB_CONNECTION || type == WdqIdType.SCHEMA || type == WdqIdType.RULE_RELATION
                    || type == WdqIdType.EXCLUSION_RULE) throw new IllegalArgumentException();
            return type;
        } catch (Exception e) { throw new IllegalArgumentException("수동 등록할 수 없는 진단기준 영역입니다: " + dataType); }
    }
    private void invalidateApproval(long projectId) {
        jdbc.update("update build_project set status='NEEDS_REVIEW',approved_by=null,approved_at=null,approved_snapshot_hash=null,updated_at=now() where id=?",
                projectId);
    }
    private void history(long projectId, String entityType, String entityId, String action,
            Map<String, String> before, Map<String, String> after) {
        jdbc.update("insert into project_change_history(project_id,entity_type,entity_id,action,before_json,after_json) values(?,?,?,?,?::jsonb,?::jsonb)",
                projectId, entityType, entityId, action, before == null ? null : write(before), after == null ? null : write(after));
    }
    private Map<String, String> read(String value) {
        try { return json.readValue(value, new TypeReference<>() { }); }
        catch (JsonProcessingException e) { throw new IllegalStateException(e); }
    }
    private Map<String, String> readNullable(String value) { return value == null ? null : read(value); }
    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalStateException(e); }
    }

    public record ManagedCriteria(long id, long projectId, String dataType, String logicalKey,
            Map<String, String> values, String fingerprint) { }
    public record ChangeHistory(long id, String entityType, String entityId, String action,
            Map<String, String> beforeValues, Map<String, String> afterValues, java.time.OffsetDateTime changedAt) { }
    private record ProjectIdentity(long projectId, long systemId) { }
}
