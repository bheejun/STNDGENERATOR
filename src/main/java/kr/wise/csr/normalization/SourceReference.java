package kr.wise.csr.normalization;

import kr.wise.csr.importfile.WorkbookType;

public record SourceReference(WorkbookType workbookType, String sheet, int row) {
}
