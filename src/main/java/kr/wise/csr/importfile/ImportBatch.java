package kr.wise.csr.importfile;

import java.util.List;

public record ImportBatch(WorkbookType workbookType, List<ImportCandidate> candidates,
        int excludedPt01Count, int excludedPt02Count, List<String> errors) {
}
