package kr.wise.csr.importfile;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import kr.wise.csr.normalization.NormalizationSummary;
import kr.wise.csr.normalization.ProjectNormalizationService;
import kr.wise.csr.project.SourceFileRecord;
import kr.wise.csr.project.SourceFileService;
import kr.wise.csr.review.ReportReviewService;
import kr.wise.csr.storage.ArtifactKind;

@Service
public class ProjectImportService {
    private final JdbcTemplate jdbc;
    private final SourceFileService sourceFiles;
    private final WorkbookDetector detector;
    private final List<WorkbookParser> parsers;
    private final SplitCriteriaWorkbookParser splitCriteriaParser;
    private final ProjectNormalizationService normalization;
    private final ReportReviewService reviews;

    public ProjectImportService(JdbcTemplate jdbc, SourceFileService sourceFiles, WorkbookDetector detector,
            List<WorkbookParser> parsers, SplitCriteriaWorkbookParser splitCriteriaParser,
            ProjectNormalizationService normalization, ReportReviewService reviews) {
        this.jdbc = jdbc;
        this.sourceFiles = sourceFiles;
        this.detector = detector;
        this.parsers = parsers;
        this.splitCriteriaParser = splitCriteriaParser;
        this.normalization = normalization;
        this.reviews = reviews;
    }

    public ImportResult importFiles(long projectId, List<MultipartFile> files) {
        return importFiles(projectId, files, null, null);
    }

    public ImportResult importFiles(long projectId, List<MultipartFile> files, InputTrack requestedTrack) {
        return importFiles(projectId, files, requestedTrack, null);
    }

    public ImportResult importFiles(long projectId, List<MultipartFile> files, InputTrack requestedTrack,
            CriteriaCategory requestedCategory) {
        if (files == null || files.isEmpty()) throw new IllegalArgumentException("업로드할 엑셀 파일이 필요합니다");
        if (requestedTrack == InputTrack.RESULT_REPORT && files.size() != 1)
            throw new IllegalArgumentException("결과보고서는 한 번에 한 파일만 업로드할 수 있습니다");
        ProjectContext context = context(projectId);
        List<ImportBatch> batches = new ArrayList<>();
        List<ImportedFile> imported = new ArrayList<>();
        Set<Long> uploadedSourceIds = new HashSet<>();

        for (MultipartFile file : files) {
            validate(file);
            try {
                SourceFileRecord stored = sourceFiles.store(projectId, ArtifactKind.SOURCE,
                        file.getOriginalFilename(), file.getInputStream());
                uploadedSourceIds.add(stored.id());
                Path path = Path.of(stored.storedPath());
                WorkbookType type;
                try {
                    type = detector.detect(path);
                } catch (WorkbookDetectionException e) {
                    if (requestedCategory != null)
                        throw new IllegalArgumentException(requestedCategory.label() + " 양식의 필수 컬럼을 확인하세요: "
                                + String.join(", ", requestedCategory.requiredHeaders()), e);
                    throw e;
                }
                InputTrack actualTrack = inputTrack(type);
                sourceFiles.classify(stored.id(), actualTrack.name(), type.name());
                if (type == WorkbookType.CRITERIA_REFERENCE_INTEGRITY) {
                    jdbc.update("update source_file set parse_status='UNSUPPORTED' where id=? and parse_status='UNDETECTED'",
                            stored.id());
                    throw new IllegalArgumentException("참조무결성 진단기준은 현재 처리 대상에서 제외되었습니다");
                }
                if (requestedTrack != null && requestedTrack != actualTrack) {
                    jdbc.update("update source_file set parse_status='TYPE_MISMATCH' where id=? and parse_status='UNDETECTED'",
                            stored.id());
                    throw new IllegalArgumentException(requestedTrack == InputTrack.RESULT_REPORT
                            ? "결과보고서 업로드 화면에는 WISE DQ 결과보고서만 등록할 수 있습니다"
                            : "진단기준 업로드 화면에는 결과보고서가 아닌 진단기준 파일만 등록할 수 있습니다");
                }
                if (requestedCategory != null && !requestedCategory.accepts(type)) {
                    jdbc.update("update source_file set parse_status='TYPE_MISMATCH' where id=? and parse_status='UNDETECTED'",
                            stored.id());
                    throw new IllegalArgumentException(requestedCategory.label()
                            + " 카테고리와 일치하지 않는 파일입니다. 감지 유형: " + type.name());
                }
                ReportReviewService.ReviewView reportReview = null;
                if (type == WorkbookType.WISEDQ_RESULT) {
                    reportReview = reviews.review(projectId, stored.id());
                    if (!reportReview.criteriaAdoptable()) {
                        List<String> reviewErrors = reportReview.issues().stream()
                                .filter(issue -> issue.blocksAdoption()).map(issue -> issue.message()).toList();
                        imported.add(new ImportedFile(stored.id(), stored.originalName(), type, 0,
                                reviewErrors, reportReview));
                        jdbc.update("update source_file set parse_status='REVIEW_REJECTED' where id=?", stored.id());
                        continue;
                    }
                }
                ImportBatch batch = applyPrefix(parse(path, context, type), context);
                batches.add(batch);
                imported.add(new ImportedFile(stored.id(), stored.originalName(), type, batch.candidates().size(),
                        batch.errors(), reportReview));
                jdbc.update("update source_file set parse_status=? where id=?",
                        batch.errors().isEmpty() ? "PARSED" : "PARSE_ERROR", stored.id());
            } catch (IOException e) {
                throw new IllegalStateException("업로드 파일을 읽을 수 없습니다: " + file.getOriginalFilename(), e);
            }
        }

        for (StoredSource previous : previousSources(projectId)) {
            if (uploadedSourceIds.contains(previous.id())) continue;
            WorkbookType type = detector.detect(previous.path());
            batches.add(applyPrefix(parse(previous.path(), context, type), context));
        }
        expandExclusionPatterns(batches);
        orderForMerge(batches);

        NormalizationSummary summary = batches.isEmpty()
                ? new NormalizationSummary(projectId, List.of(), List.of(), 0, 0,
                        imported.stream().flatMap(file -> file.errors().stream()).toList())
                : normalization.normalizeAndSave(projectId, context.systemId(), batches);
        return new ImportResult(projectId, List.copyOf(imported), summary.rows().size(), summary.conflicts().size(),
                summary.excludedPt01Count(), summary.excludedPt02Count(), summary.importErrors(), track(imported),
                criteriaSummary(summary));
    }

    public ImportResult reextract(long projectId) {
        return reextract(projectId, null);
    }

    public ImportResult reextract(long projectId, InputTrack requestedTrack) {
        ProjectContext context = context(projectId);
        List<StoredSource> sources = previousSources(projectId);
        if (sources.isEmpty())
            throw new IllegalStateException("재추출할 정상 원본 엑셀 파일이 없습니다");
        if (requestedTrack != null && sources.stream().noneMatch(source -> source.inputTrack() == requestedTrack))
            throw new IllegalStateException(requestedTrack == InputTrack.RESULT_REPORT
                    ? "재추출할 결과보고서가 없습니다" : "재추출할 진단기준 파일이 없습니다");
        List<ImportBatch> batches = new ArrayList<>();
        List<ImportedFile> imported = new ArrayList<>();
        List<SourceFileService.SourceFileView> fileViews = sourceFiles.list(projectId);
        for (StoredSource source : sources) {
            WorkbookType type = source.workbookType();
            ImportBatch batch = applyPrefix(parse(source.path(), context, type), context);
            batches.add(batch);
            String name = fileViews.stream().filter(file -> file.id() == source.id())
                    .map(SourceFileService.SourceFileView::originalName)
                    .findFirst().orElse(source.path().getFileName().toString());
            if (requestedTrack == null || source.inputTrack() == requestedTrack)
                imported.add(new ImportedFile(source.id(), name, type, batch.candidates().size(),
                        batch.errors(), null));
            jdbc.update("update source_file set parse_status=? where id=?",
                    batch.errors().isEmpty() ? "PARSED" : "PARSE_ERROR", source.id());
        }
        expandExclusionPatterns(batches);
        orderForMerge(batches);
        NormalizationSummary summary = normalization.normalizeAndSave(projectId, context.systemId(), batches);
        return new ImportResult(projectId, List.copyOf(imported), summary.rows().size(), summary.conflicts().size(),
                summary.excludedPt01Count(), summary.excludedPt02Count(), summary.importErrors(), track(imported),
                criteriaSummary(summary));
    }

    private ImportBatch parse(Path path, ProjectContext context, WorkbookType type) {
        if (splitCriteriaParser.supports(type)) return splitCriteriaParser.parse(path, context, type);
        return parsers.stream().filter(parser -> parser != splitCriteriaParser && parser.supports(type)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("파서가 없는 워크북 유형: " + type))
                .parse(path, context);
    }

    private ImportBatch applyPrefix(ImportBatch batch, ProjectContext context) {
        List<ImportCandidate> candidates = new ArrayList<>();
        for (ImportCandidate candidate : batch.candidates()) {
            var values = new java.util.LinkedHashMap<>(candidate.values());
            switch (candidate.dataType()) {
                case "VERIFICATION_RULE", "CODE_RULE", "BUSINESS_RULE" -> {
                    String source = values.getOrDefault("sourceRuleName", values.get("ruleName"));
                    if (!isDefaultReference(values.get("wdqId")))
                        values.put("ruleName", context.prefixed(source));
                }
                case "CODE_VALUE" -> {
                    String name=values.getOrDefault("ruleName","");
                    if(!name.startsWith(context.prefix())) values.put("ruleName", context.prefixed(name));
                }
                case "EXCLUSION", "EXCLUSION_PATTERN" ->
                    values.put("reason", context.prefixedReason(values.get("reason")));
                case "COLUMN_MAPPING" -> {
                    String ruleId = values.getOrDefault("verificationRuleId", "");
                    String ruleName = values.getOrDefault("ruleName", "");
                    if (!ruleName.isBlank() && !isDefaultReference(ruleId)
                            && !ruleName.startsWith(context.prefix()))
                        values.put("ruleName", context.prefixed(ruleName));
                }
                default -> { }
            }
            candidates.add(new ImportCandidate(candidate.dataType(), logicalKey(candidate.dataType(), values,
                    candidate.logicalKey()), java.util.Map.copyOf(values), candidate.sourceSheet(), candidate.sourceRow()));
        }
        return new ImportBatch(batch.workbookType(), List.copyOf(candidates), batch.excludedPt01Count(),
                batch.excludedPt02Count(), batch.errors());
    }

    private String logicalKey(String type, java.util.Map<String,String> values, String fallback) {
        return switch (type) {
            case "VERIFICATION_RULE" -> WisedqResultWorkbookParser.key(values, "ruleName", "expression");
            case "CODE_RULE" -> WisedqResultWorkbookParser.key(values, "dbmsNormalized", "ruleName");
            case "CODE_VALUE" -> WisedqResultWorkbookParser.key(values, "dbmsNormalized", "ruleName", "codeId");
            case "BUSINESS_RULE" -> WisedqResultWorkbookParser.key(values, "tableNormalized", "ruleName");
            default -> fallback;
        };
    }

    private boolean isDefaultReference(String value) {
        String normalized=value==null?"":value.trim().toUpperCase(java.util.Locale.ROOT);
        return normalized.startsWith("STAT_") || normalized.startsWith("VRF1_");
    }

    private ProjectContext context(long projectId) {
        List<ProjectContext> contexts = jdbc.query("""
                select p.id,p.system_id,p.target_year,p.deployment_year_month,s.dbms_physical_name,
                       s.default_schema_original,s.system_name,s.criteria_prefix
                from build_project p join standard_system s on s.id=p.system_id where p.id=?
                """, (rs, row) -> new ProjectContext(rs.getLong(1), rs.getLong(2), rs.getInt(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8)), projectId);
        if (contexts.isEmpty()) throw new IllegalArgumentException("프로젝트를 찾을 수 없습니다: " + projectId);
        return contexts.getFirst();
    }

    private List<StoredSource> previousSources(long projectId) {
        List<StoredSource> sources = jdbc.query("""
                select id,stored_path,input_track,workbook_type from source_file
                where project_id=? and parse_status='PARSED' order by id
                """, (rs, row) -> {
                    Path path = Path.of(rs.getString(2));
                    WorkbookType type = rs.getString(4) == null ? detector.detect(path)
                            : WorkbookType.valueOf(rs.getString(4));
                    InputTrack track = inputTrack(type);
                    if ("UNKNOWN".equals(rs.getString(3)) || rs.getString(4) == null)
                        sourceFiles.classify(rs.getLong(1), track.name(), type.name());
                    return new StoredSource(rs.getLong(1), path, track, type);
                }, projectId);
        return sources.stream().sorted(java.util.Comparator.<StoredSource>
                comparingInt(source -> source.inputTrack() == InputTrack.RESULT_REPORT ? 0 : 1)
                .thenComparingLong(StoredSource::id)).toList();
    }

    private void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("빈 파일은 업로드할 수 없습니다");
        String name = file.getOriginalFilename();
        if (name == null || !(name.toLowerCase().endsWith(".xlsx") || name.toLowerCase().endsWith(".xls")))
            throw new IllegalArgumentException("엑셀 파일만 업로드할 수 있습니다: " + name);
    }

    private InputTrack track(List<ImportedFile> files) {
        boolean report = files.stream().anyMatch(file -> file.workbookType() == WorkbookType.WISEDQ_RESULT);
        boolean criteria = files.stream().anyMatch(file -> file.workbookType() != WorkbookType.WISEDQ_RESULT);
        if (report && criteria) return InputTrack.COMBINED;
        return report ? InputTrack.RESULT_REPORT : InputTrack.CRITERIA_FILES;
    }

    private InputTrack inputTrack(WorkbookType type) {
        return type == WorkbookType.WISEDQ_RESULT ? InputTrack.RESULT_REPORT : InputTrack.CRITERIA_FILES;
    }

    private void orderForMerge(List<ImportBatch> batches) {
        batches.sort(java.util.Comparator.comparingInt(batch ->
                batch.workbookType() == WorkbookType.WISEDQ_RESULT ? 0 : 1));
    }

    private void expandExclusionPatterns(List<ImportBatch> batches) {
        List<ImportBatch> cleaned = new ArrayList<>();
        for (ImportBatch batch : batches) {
            List<ImportCandidate> candidates = batch.candidates().stream()
                    .filter(candidate -> !"TABLE_INVENTORY".equals(candidate.dataType()))
                    .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
            cleaned.add(new ImportBatch(batch.workbookType(), List.copyOf(candidates),
                    batch.excludedPt01Count(), batch.excludedPt02Count(), batch.errors()));
        }
        batches.clear();
        batches.addAll(cleaned);
    }

    private boolean sameScope(java.util.Map<String, String> pattern, java.util.Map<String, String> table) {
        return pattern.getOrDefault("dbmsNormalized", "").equals(table.getOrDefault("dbmsNormalized", ""))
                && pattern.getOrDefault("schemaNormalized", "").equals(table.getOrDefault("schemaNormalized", ""));
    }

    private boolean matchesPattern(String tableName, String relation, String pattern) {
        String name = tableName == null ? "" : tableName.trim();
        String token = pattern == null ? "" : pattern.trim();
        String mode = relation == null ? "" : relation.replaceAll("\\s+", "").toUpperCase(java.util.Locale.ROOT);
        if (name.isBlank() || token.isBlank()) return false;
        return switch (mode) {
            case "앞", "앞쪽", "PREFIX", "STARTSWITH" -> name.startsWith(token);
            case "뒤", "뒤쪽", "SUFFIX", "ENDSWITH" -> name.endsWith(token);
            case "일치", "같음", "EQUALS", "EXACT" -> name.equals(token);
            default -> name.contains(token);
        };
    }

    private CriteriaSummary criteriaSummary(NormalizationSummary summary) {
        int verificationRules = 0, businessRules = 0, tableExclusions = 0, columnExclusions = 0, mappings = 0;
        for (var row : summary.rows()) {
            switch (row.dataType()) {
                case "VERIFICATION_RULE" -> verificationRules++;
                case "BUSINESS_RULE" -> businessRules++;
                case "COLUMN_MAPPING" -> mappings++;
                case "EXCLUSION" -> {
                    if ("COL".equalsIgnoreCase(row.values().get("exclusionType"))) columnExclusions++;
                    else tableExclusions++;
                }
                default -> { }
            }
        }
        return new CriteriaSummary(verificationRules, businessRules, tableExclusions, columnExclusions, mappings);
    }

    public record ImportedFile(long sourceFileId, String fileName, WorkbookType workbookType,
            int candidateCount, List<String> errors, ReportReviewService.ReviewView reportReview) {
    }

    public record ImportResult(long projectId, List<ImportedFile> files, int normalizedRowCount, int conflictCount,
            int excludedPt01Count, int excludedPt02Count, List<String> errors, InputTrack inputTrack,
            CriteriaSummary criteriaSummary) {
    }

    public enum InputTrack { RESULT_REPORT, CRITERIA_FILES, COMBINED }
    public enum CriteriaCategory {
        INTEGRATED("통합 진단기준", WorkbookType.WDQ_CRITERIA),
        VERIFICATION_RULE("검증룰", WorkbookType.CRITERIA_VERIFICATION_RULE),
        DOMAIN_MAPPING("도메인 매핑", WorkbookType.CRITERIA_DOMAIN_MAPPING),
        BUSINESS_RULE("업무규칙", WorkbookType.CRITERIA_BUSINESS_RULE),
        EXCLUSION_PATTERN("제외기준 룰", WorkbookType.CRITERIA_EXCLUSION_PATTERN),
        TABLE_EXCLUSION("테이블 제외", WorkbookType.CRITERIA_TABLE_EXCLUSION),
        COLUMN_EXCLUSION("컬럼 제외", WorkbookType.CRITERIA_COLUMN_EXCLUSION),
        CODE_RULE("코드규칙", WorkbookType.CRITERIA_CODE_RULE);

        private final String label;
        private final WorkbookType workbookType;
        CriteriaCategory(String label, WorkbookType workbookType) {
            this.label = label;
            this.workbookType = workbookType;
        }
        public boolean accepts(WorkbookType type) { return workbookType == type; }
        public String label() { return label; }
        public List<String> requiredHeaders() {
            return switch (this) {
                case EXCLUSION_PATTERN -> List.of("DBMS명", "스키마명", "포함관계", "제외기준룰", "제외사유");
                case TABLE_EXCLUSION -> List.of("DBMS명", "스키마명", "테이블명", "테이블한글명", "제외여부", "제외사유");
                case COLUMN_EXCLUSION -> List.of("DBMS명", "스키마명", "테이블명", "컬럼명", "제외여부", "제외사유");
                case VERIFICATION_RULE -> List.of("검증룰명", "검증유형", "검증룰", "품질지표명");
                case DOMAIN_MAPPING -> List.of("DBMS명", "스키마명", "테이블명", "컬럼명", "검증룰", "코드분류ID");
                case BUSINESS_RULE -> List.of("업무규칙명", "DBMS명", "스키마명", "테이블명", "건수SQL", "분석SQL");
                case CODE_RULE -> List.of("DBMS명", "검증코드명", "코드유형", "코드생성SQL");
                case INTEGRATED -> List.of("기관별채번항목", "waa_stnd_exp_obj(테이블)",
                        "waa_vrfc_rule(검증룰관리)", "waa_stnd_rule_set(도메인규칙관리)",
                        "waa_stnd_tbl_prf(업무규칙)");
            };
        }
    }
    public record CriteriaSummary(int verificationRuleCount, int businessRuleCount, int tableExclusionCount,
            int columnExclusionCount, int columnMappingCount) { }
    private record StoredSource(long id, Path path, InputTrack inputTrack, WorkbookType workbookType) { }
}
