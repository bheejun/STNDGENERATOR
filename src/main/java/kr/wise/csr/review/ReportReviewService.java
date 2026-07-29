package kr.wise.csr.review;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class ReportReviewService {
    private final JdbcTemplate jdbc;
    private final ReviewEngine engine;
    private final ObjectMapper json = new ObjectMapper();

    public ReportReviewService(JdbcTemplate jdbc, ReviewEngine engine) {
        this.jdbc = jdbc;
        this.engine = engine;
    }

    @Transactional
    public ReviewView review(long projectId, long sourceFileId) {
        ReviewSource source = source(projectId, sourceFileId);
        ReviewResult result = engine.review(Path.of(source.storedPath()),
                new ReviewContext(source.systemName(), source.dbmsName(), source.schemaName()));
        Long runId = jdbc.queryForObject("""
                insert into report_review_run(project_id,source_file_id,engine_version,file_sha256,
                    verdict,criteria_adoptable,metadata_json)
                values (?,?,?,?,?,?,?::jsonb) returning id
                """, Long.class, projectId, sourceFileId, result.engineVersion(), result.fileSha256(),
                result.verdict().name(), result.criteriaAdoptable(), toJson(result.metadata()));
        for (ReviewIssue issue : result.issues()) {
            jdbc.update("""
                    insert into report_review_issue(review_run_id,issue_code,severity,message,sheet_name,
                        row_number,evidence,blocks_adoption) values (?,?,?,?,?,?,?,?)
                    """, runId, issue.code(), issue.severity().name(), issue.message(), issue.sheetName(),
                    issue.rowNumber(), issue.evidence(), issue.blocksAdoption());
        }
        result.metrics().forEach((name, value) -> jdbc.update("""
                insert into report_review_metric(review_run_id,metric_name,metric_value) values (?,?,?)
                """, runId, name, value));
        return get(runId);
    }

    public ReviewView revalidateLatest(long projectId) {
        Long sourceFileId = jdbc.query("""
                select id from source_file
                where project_id=? and input_track='RESULT_REPORT' and parse_status='PARSED'
                order by created_at desc,id desc limit 1
                """, rs -> rs.next() ? rs.getLong(1) : null, projectId);
        if (sourceFileId == null)
            throw new IllegalStateException("재검증할 결과보고서가 없습니다");
        return review(projectId, sourceFileId);
    }

    public ReviewView latest(long projectId) {
        List<Long> ids = jdbc.query("""
                select id from report_review_run where project_id=? order by created_at desc,id desc limit 1
                """, (rs, row) -> rs.getLong(1), projectId);
        return ids.isEmpty() ? null : get(ids.getFirst());
    }

    public List<ReviewSummary> history(long projectId) {
        return jdbc.query("""
                select r.id,r.source_file_id,f.original_name,r.engine_version,r.verdict,
                       r.criteria_adoptable,r.created_at
                from report_review_run r join source_file f on f.id=r.source_file_id
                where r.project_id=? order by r.created_at desc,r.id desc
                """, (rs, row) -> new ReviewSummary(rs.getLong(1), projectId, rs.getLong(2),
                        rs.getString(3), rs.getString(4), ReviewVerdict.valueOf(rs.getString(5)),
                        rs.getBoolean(6), rs.getObject(7, OffsetDateTime.class)), projectId);
    }

    private ReviewView get(long runId) {
        ReviewView base = jdbc.queryForObject("""
                select r.id,r.project_id,r.source_file_id,f.original_name,r.engine_version,r.file_sha256,
                       r.verdict,r.criteria_adoptable,r.metadata_json::text,r.created_at
                from report_review_run r join source_file f on f.id=r.source_file_id where r.id=?
                """, (rs, row) -> new ReviewView(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), ReviewVerdict.valueOf(rs.getString(7)),
                        rs.getBoolean(8), fromJson(rs.getString(9)), Map.of(), List.of(), List.of(),
                        rs.getObject(10, OffsetDateTime.class)), runId);
        Map<String, Long> metrics = new LinkedHashMap<>();
        jdbc.query("select metric_name,metric_value from report_review_metric where review_run_id=? order by metric_name",
                (rs, row) -> Map.entry(rs.getString(1), rs.getLong(2)), runId)
                .forEach(entry -> metrics.put(entry.getKey(), entry.getValue()));
        List<ReviewIssue> storedIssues = jdbc.query("""
                select issue_code,severity,message,sheet_name,row_number,evidence,blocks_adoption
                from report_review_issue where review_run_id=? order by blocks_adoption desc,id
                """, (rs, row) -> new ReviewIssue(rs.getString(1), ReviewSeverity.valueOf(rs.getString(2)),
                        rs.getString(3), rs.getString(4), (Integer) rs.getObject(5), rs.getString(6),
                        rs.getBoolean(7)), runId);
        List<ReviewIssue> issues = new java.util.ArrayList<>(storedIssues.stream()
                .filter(issue -> !"SYSTEM_CONTEXT_MISMATCH".equals(issue.code()))
                .filter(issue -> !"CODE_DATA_MISSING".equals(issue.code()) || hasMissingCodeData(base.projectId()))
                .toList());
        ReviewVerdict currentVerdict = issues.stream().anyMatch(ReviewIssue::blocksAdoption)
                ? ReviewVerdict.FAIL
                : issues.stream().anyMatch(issue -> issue.severity() == ReviewSeverity.WARNING)
                        ? ReviewVerdict.CONDITIONAL : ReviewVerdict.PASS;
        List<ReviewIssue> decisionReasons = switch (currentVerdict) {
            case FAIL -> issues.stream().filter(ReviewIssue::blocksAdoption).toList();
            case CONDITIONAL -> issues.stream()
                    .filter(issue -> issue.severity() == ReviewSeverity.WARNING).toList();
            case PASS -> List.of();
        };
        return new ReviewView(base.reviewRunId(), base.projectId(), base.sourceFileId(), base.fileName(),
                base.engineVersion(), base.fileSha256(), currentVerdict,
                issues.stream().noneMatch(ReviewIssue::blocksAdoption),
                base.metadata(), Map.copyOf(metrics), List.copyOf(issues), decisionReasons, base.createdAt());
    }

    private boolean hasMissingCodeData(long projectId) {
        Boolean missing = jdbc.queryForObject("""
                select exists(
                  select 1 from normalized_item m
                  where m.project_id=? and m.data_type='COLUMN_MAPPING'
                    and m.values_json->>'ruleType'='CODE'
                    and not exists (
                      select 1 from normalized_item r
                      where r.project_id=m.project_id and r.data_type='CODE_RULE'
                        and r.values_json->>'ruleName'=coalesce(nullif(m.values_json->>'ruleName',''),m.values_json->>'codeRuleId')
                        and coalesce(r.values_json->>'lookupSql','')<>'')
                    and not exists (
                      select 1 from normalized_item v
                      where v.project_id=m.project_id and v.data_type='CODE_VALUE'
                        and v.values_json->>'ruleName'=coalesce(nullif(m.values_json->>'ruleName',''),m.values_json->>'codeRuleId'))
                )
                """, Boolean.class, projectId);
        return Boolean.TRUE.equals(missing);
    }

    private ReviewSource source(long projectId, long sourceFileId) {
        List<ReviewSource> rows = jdbc.query("""
                select f.stored_path,coalesce(nullif(s.info_system_name,''),s.system_name),
                       s.dbms_physical_name,s.default_schema_original
                from source_file f join build_project p on p.id=f.project_id
                join standard_system s on s.id=p.system_id
                where f.id=? and f.project_id=?
                """, (rs, row) -> new ReviewSource(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4)), sourceFileId, projectId);
        if (rows.isEmpty()) throw new IllegalArgumentException("검토할 원본 파일을 찾을 수 없습니다");
        return rows.getFirst();
    }

    private String toJson(Map<String, String> value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("검토 메타데이터를 저장할 수 없습니다", e);
        }
    }

    private Map<String, String> fromJson(String value) {
        try {
            return json.readValue(value, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("검토 메타데이터를 읽을 수 없습니다", e);
        }
    }

    private record ReviewSource(String storedPath, String systemName, String dbmsName, String schemaName) {
    }

    public record ReviewSummary(long reviewRunId, long projectId, long sourceFileId, String fileName,
            String engineVersion, ReviewVerdict verdict, boolean criteriaAdoptable, OffsetDateTime createdAt) {
    }

    public record ReviewView(long reviewRunId, long projectId, long sourceFileId, String fileName,
            String engineVersion, String fileSha256, ReviewVerdict verdict, boolean criteriaAdoptable,
            Map<String, String> metadata, Map<String, Long> metrics, List<ReviewIssue> issues,
            List<ReviewIssue> decisionReasons, OffsetDateTime createdAt) {
    }
}
