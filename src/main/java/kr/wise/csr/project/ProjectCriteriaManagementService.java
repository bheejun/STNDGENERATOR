package kr.wise.csr.project;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;

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
        String logicalKey = updatedLogicalKey(current, values);
        if (!logicalKey.equals(current.logicalKey())) {
            Integer duplicate = jdbc.queryForObject("""
                    select count(*) from normalized_item
                    where project_id=? and data_type=? and logical_key=? and id<>?
                    """, Integer.class, projectId, current.dataType(), logicalKey, itemId);
            if (duplicate != null && duplicate > 0)
                throw new IllegalArgumentException("수정한 이름과 내용으로 이미 존재하는 진단기준입니다: " + logicalKey);
        }
        String fingerprint = NormalizationService.fingerprint(values);
        int updated = jdbc.update("""
                update normalized_item set logical_key=?,values_json=?::jsonb,fingerprint=?
                where id=? and project_id=?
                """, logicalKey, write(values), fingerprint, itemId, projectId);
        if (updated != 1) throw new IllegalStateException("진단기준 수정 결과를 저장하지 못했습니다: " + itemId);
        if (!logicalKey.equals(current.logicalKey()))
            saveOverride(projectId,current.dataType(),current.logicalKey(),"DELETE",null);
        saveOverride(projectId,current.dataType(),logicalKey,"UPSERT",values);
        if (("VERIFICATION_RULE".equals(current.dataType()) || "CODE_RULE".equals(current.dataType()))
                && wdqId != null && !wdqId.isBlank())
            updateRuleReferences(projectId,current.values().get("ruleName"),values.get("ruleName"),wdqId,
                    "CODE_RULE".equals(current.dataType()));
        history(projectId, current.dataType(), String.valueOf(itemId), "UPDATE", current.values(), values);
        invalidateApproval(projectId);
        return new ManagedCriteria(itemId, projectId, current.dataType(), logicalKey, Map.copyOf(values), fingerprint);
    }

    @Transactional
    public List<ManagedCriteria> bulkUpdate(long projectId, List<Long> itemIds, String field,
            String mode, String find, String value) {
        if (itemIds == null || itemIds.isEmpty()) throw new IllegalArgumentException("수정할 항목을 선택하세요");
        if (itemIds.size() > 5000) throw new IllegalArgumentException("한 번에 최대 5,000건까지 수정할 수 있습니다");
        if (field == null || !field.matches("[A-Za-z][A-Za-z0-9]*"))
            throw new IllegalArgumentException("수정할 필드가 올바르지 않습니다");
        String operation = mode == null ? "" : mode.toUpperCase(Locale.ROOT);
        if (!List.of("PREPEND", "REPLACE", "SET").contains(operation))
            throw new IllegalArgumentException("지원하지 않는 일괄 수정 방식입니다");
        List<ManagedCriteria> result = new java.util.ArrayList<>();
        Map<Long,ManagedCriteria> byId = list(projectId).stream()
                .collect(java.util.stream.Collectors.toMap(ManagedCriteria::id, row -> row));
        for (Long itemId : itemIds.stream().distinct().toList()) {
            ManagedCriteria current = byId.get(itemId);
            if (current == null) throw new IllegalArgumentException("진단기준을 찾을 수 없습니다: " + itemId);
            if ("wdqId".equals(field) || !current.values().containsKey(field))
                throw new IllegalArgumentException(field + " 필드가 없는 항목입니다: " + current.logicalKey());
            Map<String,String> values = new LinkedHashMap<>(current.values());
            String before = values.getOrDefault(field, "");
            String after = switch (operation) {
                case "PREPEND" -> (value == null ? "" : value) + before;
                case "REPLACE" -> {
                    if (find == null || find.isEmpty()) throw new IllegalArgumentException("찾을 값을 입력하세요");
                    yield before.replace(find, value == null ? "" : value);
                }
                case "SET" -> value == null ? "" : value;
                default -> throw new IllegalStateException();
            };
            values.put(field, after);
            result.add(update(projectId, itemId, values));
        }
        return List.copyOf(result);
    }

    private String updatedLogicalKey(ManagedCriteria current, Map<String,String> values) {
        if (!List.of("VERIFICATION_RULE", "CODE_RULE", "BUSINESS_RULE").contains(current.dataType()))
            return current.logicalKey();
        String oldName = normalize(current.values().get("ruleName"));
        String newName = normalize(values.get("ruleName"));
        if (newName.isBlank() || oldName.equals(newName)) {
            if ("VERIFICATION_RULE".equals(current.dataType())) {
                String expression = normalize(values.get("expression"));
                String expected = newName + "|" + expression;
                return expected.equals(current.logicalKey()) ? current.logicalKey() : expected;
            }
            return current.logicalKey();
        }
        String[] parts = current.logicalKey().split("\\|", -1);
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].equals(oldName)) {
                parts[i] = newName;
                return String.join("|", parts);
            }
        }
        return switch (current.dataType()) {
            case "VERIFICATION_RULE" -> newName + "|" + normalize(values.get("expression"));
            case "BUSINESS_RULE" -> parts.length == 0 ? newName : parts[0] + "|" + newName;
            default -> current.logicalKey();
        };
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    @Transactional
    public ManagedCriteria create(long projectId, String dataType, String logicalKey, Map<String, String> requestedValues) {
        ProjectIdentity project = project(projectId);
        boolean codeValue = "CODE_VALUE".equals(dataType);
        WdqIdType idType = codeValue ? null : idType(dataType);
        if (logicalKey == null || logicalKey.isBlank()) throw new IllegalArgumentException("논리키가 필요합니다");
        if (requestedValues == null || requestedValues.isEmpty()) throw new IllegalArgumentException("등록할 값이 필요합니다");
        Map<String, String> values = new LinkedHashMap<>(requestedValues);
        if (!codeValue) values.put("wdqId", ids.allocate(idType, project.systemId(), logicalKey.trim(), projectId));
        String fingerprint = NormalizationService.fingerprint(values);
        long itemId = jdbc.queryForObject("""
                insert into normalized_item(project_id,data_type,logical_key,values_json,sources_json,fingerprint)
                values(?,?,?,?::jsonb,'[]'::jsonb,?) returning id
                """, Long.class, projectId, dataType, logicalKey.trim(), write(values), fingerprint);
        saveOverride(projectId,dataType,logicalKey.trim(),"UPSERT",values);
        history(projectId, dataType, String.valueOf(itemId), "CREATE", null, values);
        invalidateApproval(projectId);
        return new ManagedCriteria(itemId, projectId, dataType, logicalKey.trim(), Map.copyOf(values), fingerprint);
    }

    @Transactional
    public void delete(long projectId, long itemId) {
        DeleteUsage usage = deleteUsage(projectId, itemId);
        if (!usage.canDelete())
            throw new IllegalStateException(deleteBlockedMessage(usage));
        ManagedCriteria current = item(projectId, itemId);
        history(projectId, current.dataType(), String.valueOf(itemId), "DELETE", current.values(), null);
        saveOverride(projectId,current.dataType(),current.logicalKey(),"DELETE",null);
        jdbc.update("delete from normalized_item where id=? and project_id=?", itemId, projectId);
        invalidateApproval(projectId);
    }

    public DeleteUsage deleteUsage(long projectId, long itemId) {
        ManagedCriteria target = item(projectId, itemId);
        List<UsageReference> references = new java.util.ArrayList<>();
        String targetId = target.values().getOrDefault("wdqId", "");
        String targetName = target.values().getOrDefault("ruleName", "");
        for (ManagedCriteria candidate : list(projectId)) {
            if (candidate.id() == target.id()) continue;
            if ("VERIFICATION_RULE".equals(target.dataType())
                    && "COLUMN_MAPPING".equals(candidate.dataType())
                    && ruleReferenceMatches(candidate, targetId, targetName, false))
                references.add(columnReference(candidate, "검증룰 매핑"));
            if ("CODE_RULE".equals(target.dataType())) {
                if ("COLUMN_MAPPING".equals(candidate.dataType())
                        && ruleReferenceMatches(candidate, targetId, targetName, true))
                    references.add(columnReference(candidate, "코드룰 매핑"));
                if ("CODE_VALUE".equals(candidate.dataType())
                        && same(candidate.values().get("ruleName"), targetName))
                    references.add(new UsageReference(candidate.dataType(), candidate.id(),
                            "코드 데이터", candidate.values().getOrDefault("codeId", candidate.logicalKey())));
            }
            if ("COLUMN_MAPPING".equals(target.dataType())
                    && "BUSINESS_RULE".equals(candidate.dataType())
                    && samePhysicalColumn(target, candidate))
                references.add(new UsageReference(candidate.dataType(), candidate.id(), "업무규칙",
                        candidate.values().getOrDefault("ruleName", candidate.logicalKey())));
        }
        return new DeleteUsage(references.isEmpty(), references.size(), List.copyOf(references));
    }

    private ManagedCriteria item(long projectId, long itemId) {
        ManagedCriteria current = jdbc.query(
                "select id,data_type,logical_key,values_json::text,fingerprint from normalized_item where project_id=? and id=?",
                rs -> rs.next() ? new ManagedCriteria(rs.getLong(1), projectId, rs.getString(2), rs.getString(3),
                        read(rs.getString(4)), rs.getString(5).trim()) : null, projectId, itemId);
        if (current == null) throw new IllegalArgumentException("진단기준 항목을 찾을 수 없습니다: " + itemId);
        return current;
    }

    private boolean ruleReferenceMatches(ManagedCriteria mapping, String ruleId, String ruleName,
            boolean codeRule) {
        String idKey = codeRule ? "codeRuleId" : "verificationRuleId";
        return (!ruleId.isBlank() && same(mapping.values().get(idKey), ruleId))
                || same(mapping.values().get("ruleName"), ruleName);
    }

    private UsageReference columnReference(ManagedCriteria mapping, String relation) {
        String location = String.join(".", List.of(
                mapping.values().getOrDefault("schemaOriginal",
                        mapping.values().getOrDefault("schemaNormalized", "")),
                mapping.values().getOrDefault("tableOriginal",
                        mapping.values().getOrDefault("tableNormalized", "")),
                mapping.values().getOrDefault("columnOriginal",
                        mapping.values().getOrDefault("columnNormalized", ""))));
        return new UsageReference(mapping.dataType(), mapping.id(), relation, location);
    }

    private boolean samePhysicalColumn(ManagedCriteria left, ManagedCriteria right) {
        return samePhysical(left, right, "schemaNormalized", "schemaOriginal")
                && samePhysical(left, right, "tableNormalized", "tableOriginal")
                && samePhysical(left, right, "columnNormalized", "columnOriginal");
    }

    private boolean samePhysical(ManagedCriteria left, ManagedCriteria right,
            String normalizedKey, String originalKey) {
        String leftValue = first(left.values().get(normalizedKey), left.values().get(originalKey));
        String rightValue = first(right.values().get(normalizedKey), right.values().get(originalKey));
        return !leftValue.isBlank() && same(leftValue, rightValue);
    }

    private boolean same(String left, String right) {
        return left != null && right != null && !left.isBlank() && left.equalsIgnoreCase(right);
    }

    private String first(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return "";
    }

    private String deleteBlockedMessage(DeleteUsage usage) {
        String details = usage.references().stream().limit(10)
                .map(reference -> reference.relation() + ": " + reference.location())
                .collect(java.util.stream.Collectors.joining(", "));
        if (usage.referenceCount() > 10) details += " 외 " + (usage.referenceCount() - 10) + "건";
        return "다른 진단기준에서 사용 중이어서 삭제할 수 없습니다. " + details;
    }

    private void updateRuleReferences(long projectId,String oldName,String newName,String ruleId,boolean codeRule) {
        for(ManagedCriteria mapping:list(projectId).stream().filter(row->"COLUMN_MAPPING".equals(row.dataType())).toList()){
            String refKey=codeRule?"codeRuleId":"verificationRuleId";
            String currentRef=mapping.values().getOrDefault(refKey,"");
            String currentName=mapping.values().getOrDefault("ruleName","");
            if(!ruleId.equals(currentRef)&&!(currentRef.isBlank()&&currentName.equalsIgnoreCase(oldName==null?"":oldName)))
                continue;
            Map<String,String> values=new LinkedHashMap<>(mapping.values());
            values.put(refKey,ruleId);
            if(newName!=null)values.put("ruleName",newName);
            String fingerprint=NormalizationService.fingerprint(values);
            jdbc.update("update normalized_item set values_json=?::jsonb,fingerprint=? where id=?",
                    write(values),fingerprint,mapping.id());
            saveOverride(projectId,mapping.dataType(),mapping.logicalKey(),"UPSERT",values);
        }
    }

    private void saveOverride(long projectId,String dataType,String logicalKey,String operation,Map<String,String> values) {
        jdbc.update("""
                insert into criteria_override(project_id,data_type,logical_key,operation,values_json)
                values(?,?,?,?,?::jsonb) on conflict(project_id,data_type,logical_key) do update set
                operation=excluded.operation,values_json=excluded.values_json,updated_at=now()
                """,projectId,dataType,logicalKey,operation,values==null?null:write(values));
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

    @Transactional
    public void replaceCodeValues(long projectId,String ruleName,List<CodeValueInput> inputs) {
        project(projectId);
        ManagedCriteria rule=list(projectId).stream()
                .filter(item -> item.dataType().equals("CODE_RULE"))
                .filter(item -> ruleName.equals(item.values().get("ruleName"))).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("코드규칙을 찾을 수 없습니다: "+ruleName));
        String codeType=rule.values().getOrDefault("codeType","");
        if(!("LC".equalsIgnoreCase(codeType)||codeType.contains("목록")))
            throw new IllegalArgumentException("코드 데이터는 목록성코드(LC)에만 등록할 수 있습니다: "+ruleName);
        Integer before=jdbc.queryForObject("""
                select count(*) from normalized_item
                 where project_id=? and data_type='CODE_VALUE'
                   and upper(values_json->>'ruleName')=upper(?)
                """,Integer.class,projectId,ruleName);
        jdbc.update("""
                delete from normalized_item
                 where project_id=? and data_type='CODE_VALUE'
                   and upper(values_json->>'ruleName')=upper(?)
                """,projectId,ruleName);
        for(CodeValueInput input:inputs){
            Map<String,String> values=Map.of("ruleName",ruleName,"codeId",input.codeId(),
                    "codeName",input.codeName()==null?"":input.codeName(),"exclusiveYn","Y");
            String logicalKey=ruleName+"|"+input.codeId();
            jdbc.update("""
                    insert into normalized_item(project_id,data_type,logical_key,values_json,sources_json,fingerprint)
                    values(?,'CODE_VALUE',?,?::jsonb,'[]'::jsonb,?)
                    """,projectId,logicalKey,write(values),NormalizationService.fingerprint(values));
        }
        history(projectId,"CODE_VALUE",ruleName,"REPLACE",
                Map.of("count",String.valueOf(before==null?0:before)),
                Map.of("count",String.valueOf(inputs.size())));
        invalidateApproval(projectId);
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
    public record CodeValueInput(String codeId,String codeName) { }
    public record DeleteUsage(boolean canDelete, int referenceCount, List<UsageReference> references) { }
    public record UsageReference(String dataType, long itemId, String relation, String location) { }
    private record ProjectIdentity(long projectId, long systemId) { }
}
