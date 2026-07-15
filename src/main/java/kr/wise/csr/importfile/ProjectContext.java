package kr.wise.csr.importfile;

public record ProjectContext(long projectId, long systemId, int targetYear,
        String deploymentYearMonth, String defaultDbms, String defaultSchema) {
}
