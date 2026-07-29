package kr.wise.csr.importfile;

public record ProjectContext(long projectId, long systemId, int targetYear,
        String deploymentYearMonth, String defaultDbms, String defaultSchema, String systemName,
        String criteriaPrefix) {
    public ProjectContext(long projectId, long systemId, int targetYear,
            String deploymentYearMonth, String defaultDbms, String defaultSchema, String systemName) {
        this(projectId, systemId, targetYear, deploymentYearMonth, defaultDbms, defaultSchema, systemName,
                "[{year}표준시스템DB {systemName}]");
    }
    public ProjectContext(long projectId, long systemId, int targetYear,
            String deploymentYearMonth, String defaultDbms, String defaultSchema) {
        this(projectId, systemId, targetYear, deploymentYearMonth, defaultDbms, defaultSchema, "");
    }

    public String prefix() {
        String template = criteriaPrefix == null || criteriaPrefix.isBlank()
                ? "[{year}표준시스템DB {systemName}]" : criteriaPrefix.trim();
        return template.replace("{year}", Integer.toString(targetYear))
                .replace("{systemName}", systemName == null ? "" : systemName).trim();
    }

    public String prefixed(String value) {
        String base = value == null ? "" : value.trim()
                .replaceFirst("^(?:\\[\\d{4}(?:년)?\\s*표준시스템DB(?:\\([^]]+\\)|\\s+[^]]+)?]\\s*)+", "");
        if (base.isBlank() || prefix().isBlank()) return base;
        return prefix() + " " + base;
    }

    public String prefixedReason(String value) {
        return value == null || value.isBlank() ? "" : prefixed(value);
    }
}
