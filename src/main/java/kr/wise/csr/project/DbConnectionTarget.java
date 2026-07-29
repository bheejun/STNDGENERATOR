package kr.wise.csr.project;

public record DbConnectionTarget(String logicalName, String dbmsVersionCode, String connectionUrl,
        String driverName, String accountId, String accountPassword, String infoSystemCode,
        String infoSystemName, String organizationName) {
}
