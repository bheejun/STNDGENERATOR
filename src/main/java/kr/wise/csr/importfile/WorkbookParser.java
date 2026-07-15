package kr.wise.csr.importfile;

import java.nio.file.Path;

public interface WorkbookParser {
    boolean supports(WorkbookType workbookType);
    ImportBatch parse(Path path, ProjectContext context);
}
