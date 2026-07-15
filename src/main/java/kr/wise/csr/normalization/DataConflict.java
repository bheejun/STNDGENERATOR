package kr.wise.csr.normalization;

import java.util.Map;
import kr.wise.csr.importfile.WorkbookType;

public record DataConflict(long id, String dataType, String logicalKey,
        Map<String, String> leftValues, WorkbookType leftSource,
        Map<String, String> rightValues, WorkbookType rightSource,
        ConflictResolution resolution, String reason) {
    public boolean resolved() { return resolution != null; }
}
