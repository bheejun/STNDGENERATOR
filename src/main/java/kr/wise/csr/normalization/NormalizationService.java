package kr.wise.csr.normalization;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import kr.wise.csr.importfile.ImportBatch;
import kr.wise.csr.importfile.ImportCandidate;

public class NormalizationService {
    private final MergeKeyFactory keys = new MergeKeyFactory();
    private final AtomicLong conflictIds = new AtomicLong(1);

    public NormalizationSummary normalize(long projectId, List<ImportBatch> batches) {
        Map<String, NormalizedRow> rows = new LinkedHashMap<>();
        List<DataConflict> conflicts = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int pt01 = 0, pt02 = 0;
        for (ImportBatch batch : batches) {
            pt01 += batch.excludedPt01Count(); pt02 += batch.excludedPt02Count(); errors.addAll(batch.errors());
            for (ImportCandidate candidate : batch.candidates()) {
                if (candidate.values().values().stream().anyMatch(v -> "PT01".equalsIgnoreCase(v) || "PT02".equalsIgnoreCase(v))) continue;
                String mergeKey = keys.create(candidate);
                String fingerprint = fingerprint(candidate.values());
                SourceReference source = new SourceReference(batch.workbookType(), candidate.sourceSheet(), candidate.sourceRow());
                NormalizedRow existing = rows.get(mergeKey);
                if (existing == null) {
                    rows.put(mergeKey, new NormalizedRow(candidate.dataType(), candidate.logicalKey(),
                            Map.copyOf(candidate.values()), List.of(source), fingerprint));
                } else if (existing.fingerprint().equals(fingerprint)) {
                    List<SourceReference> sources = new ArrayList<>(existing.sources()); sources.add(source);
                    rows.put(mergeKey, new NormalizedRow(existing.dataType(), existing.logicalKey(), existing.values(), List.copyOf(sources), fingerprint));
                } else {
                    conflicts.add(new DataConflict(conflictIds.getAndIncrement(), existing.dataType(), existing.logicalKey(),
                            existing.values(), existing.sources().getFirst().workbookType(), Map.copyOf(candidate.values()),
                            batch.workbookType(), null, null));
                }
            }
        }
        return new NormalizationSummary(projectId, List.copyOf(rows.values()), List.copyOf(conflicts), pt01, pt02, List.copyOf(errors));
    }

    public static String fingerprint(Map<String, String> values) {
        String canonical = values.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(e -> e.getKey() + "=" + normalizeValue(e.getValue())).reduce("", (a,b) -> a + "\n" + b);
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static String normalizeValue(String value) { return value == null ? "" : value.replace("\r\n","\n").trim(); }
}
