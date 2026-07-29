package kr.wise.csr.sqlcompare;

public class SqlBaselineNotFoundException extends RuntimeException {
    public SqlBaselineNotFoundException(long projectId) {
        super("2025 기준 SQL이 등록되지 않았습니다: projectId=" + projectId);
    }
}
