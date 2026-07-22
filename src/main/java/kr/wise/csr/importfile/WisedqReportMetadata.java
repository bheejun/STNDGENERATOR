package kr.wise.csr.importfile;

public record WisedqReportMetadata(
        String institutionName,
        String systemName,
        String dbmsName,
        String dbmsType,
        String schemaName,
        String dbmsVersion,
        String ipAddress,
        String port,
        String reportVersion,
        String outputAt) {
}
