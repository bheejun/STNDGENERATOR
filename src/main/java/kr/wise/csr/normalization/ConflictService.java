package kr.wise.csr.normalization;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import kr.wise.csr.importfile.WorkbookType;

@Service
public class ConflictService {
    public NormalizationSummary resolve(NormalizationSummary summary, ResolveConflictCommand command) {
        DataConflict target = summary.conflicts().stream().filter(c -> c.id() == command.conflictId()).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("충돌을 찾을 수 없습니다: " + command.conflictId()));
        if (command.resolution() == null) throw new IllegalArgumentException("해결 방법이 필요합니다");
        List<NormalizedRow> rows = new ArrayList<>(summary.rows());
        int index = -1;
        for (int i=0;i<rows.size();i++) if (rows.get(i).dataType().equals(target.dataType()) && rows.get(i).logicalKey().equals(target.logicalKey())) { index=i; break; }
        if (command.resolution() == ConflictResolution.EXCLUDE) {
            if (index >= 0) rows.remove(index);
        } else if (index >= 0) {
            Map<String,String> selected = switch (command.resolution()) {
                case USE_LEFT -> target.leftValues();
                case USE_RIGHT -> target.rightValues();
                case USE_WISEDQ -> target.leftSource()==WorkbookType.WISEDQ_RESULT ? target.leftValues() : target.rightValues();
                case USE_CRITERIA -> isCriteria(target.leftSource()) ? target.leftValues() : target.rightValues();
                case MANUAL -> manual(target.leftValues(), command.manualValue());
                case EXCLUDE -> throw new IllegalStateException();
            };
            NormalizedRow old=rows.get(index);
            rows.set(index,new NormalizedRow(old.dataType(),old.logicalKey(),Map.copyOf(selected),old.sources(),NormalizationService.fingerprint(selected)));
        }
        List<DataConflict> conflicts = summary.conflicts().stream().map(c -> c.id()==target.id()
                ? new DataConflict(c.id(),c.dataType(),c.logicalKey(),c.leftValues(),c.leftSource(),c.rightValues(),c.rightSource(),command.resolution(),command.reason()) : c).toList();
        return new NormalizationSummary(summary.projectId(),List.copyOf(rows),conflicts,summary.excludedPt01Count(),summary.excludedPt02Count(),summary.importErrors());
    }
    private boolean isCriteria(WorkbookType type) {
        return type != null && (type == WorkbookType.WDQ_CRITERIA || type.name().startsWith("CRITERIA_"));
    }
    private Map<String,String> manual(Map<String,String> base,String manual) {
        if (manual==null||manual.isBlank()) throw new IllegalArgumentException("직접 입력값이 필요합니다");
        Map<String,String> values=new LinkedHashMap<>(base);
        if (values.containsKey("expression")) values.put("expression",manual); else if(values.containsKey("lookupSql")) values.put("lookupSql",manual); else if(values.containsKey("ruleSql")) values.put("ruleSql",manual); else values.put("manualValue",manual);
        return values;
    }
}
