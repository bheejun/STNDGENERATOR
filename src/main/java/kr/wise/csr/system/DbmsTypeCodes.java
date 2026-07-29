package kr.wise.csr.system;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class DbmsTypeCodes {
    private static final Set<String> CODES = Set.of(
            "ORA", "TIB", "ALT", "POS", "MRA", "MYS", "MSQ", "CBR", "UDB", "DB2", "SYA", "SYQ");
    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("ORACLE", "ORA"), Map.entry("TIBERO", "TIB"), Map.entry("ALTIBASE", "ALT"),
            Map.entry("POSTGRESQL", "POS"), Map.entry("POSTGRES", "POS"), Map.entry("MARIADB", "MRA"),
            Map.entry("MYSQL", "MYS"), Map.entry("MSSQL", "MSQ"), Map.entry("MSSQLSERVER", "MSQ"),
            Map.entry("SQLSERVER", "MSQ"), Map.entry("CUBRID", "CBR"), Map.entry("DB2UDB", "UDB"),
            Map.entry("SYBASEASE", "SYA"), Map.entry("SYBASEIQ", "SYQ"));

    private DbmsTypeCodes() {
    }

    public static String normalize(String value) {
        String key = value == null ? "" : value.trim().toUpperCase(Locale.ROOT)
                .replace("-", "").replace("_", "").replace(" ", "");
        String code = ALIASES.getOrDefault(key, key);
        if (!CODES.contains(code)) throw new IllegalArgumentException("지원하지 않는 DBMS 종류입니다: " + value);
        return code;
    }
}
