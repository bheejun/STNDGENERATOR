package kr.wise.csr.export;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Component;

import kr.wise.csr.normalization.NormalizedRow;
import kr.wise.csr.project.ProjectSnapshot;

@Component
public class TestDdlExporter {
    public GeneratedFile export(ProjectSnapshot snapshot) {
        Dialect dialect = Dialect.of(snapshot.dbmsType());
        Map<TableKey, LinkedHashMap<String, String>> tables = new LinkedHashMap<>();

        for (NormalizedRow row : snapshot.rows()) {
            Map<String, String> values = row.values();
            String table = value(values, "tableOriginal");
            if (table.isBlank()) continue;
            String schema = value(values, "schemaOriginal");
            if (schema.isBlank()) schema = snapshot.defaultSchema();
            TableKey key = new TableKey(schema, table);
            LinkedHashMap<String, String> columns = tables.computeIfAbsent(key, ignored -> new LinkedHashMap<>());
            String column = value(values, "columnOriginal");
            if (!column.isBlank()) columns.putIfAbsent(column, dialect.columnType(value(values, "dataType")));
        }

        if (tables.isEmpty()) {
            throw new IllegalStateException("DDL을 생성할 테이블 정보가 없습니다");
        }

        StringBuilder ddl = new StringBuilder()
                .append("-- 테스트용 DDL / DBMS: ").append(dialect.label).append('\n')
                .append("-- 프로젝트: ").append(snapshot.systemName()).append(" / ")
                .append(snapshot.targetYear()).append('\n')
                .append("-- 원본 자료에 컬럼이 없는 테이블은 DUMMY_COL로 생성합니다.\n\n");

        for (Map.Entry<TableKey, LinkedHashMap<String, String>> entry : tables.entrySet()) {
            TableKey table = entry.getKey();
            LinkedHashMap<String, String> columns = entry.getValue();
            if (columns.isEmpty()) columns.put("DUMMY_COL", dialect.defaultTextType);
            ddl.append("CREATE TABLE ").append(dialect.qualified(table.schema(), table.table())).append(" (\n");
            int index = 0;
            for (Map.Entry<String, String> column : columns.entrySet()) {
                ddl.append("    ").append(dialect.quote(column.getKey())).append(' ').append(column.getValue());
                if (++index < columns.size()) ddl.append(',');
                ddl.append('\n');
            }
            ddl.append(");\n\n");
        }

        String system = snapshot.systemName() == null ? "system"
                : snapshot.systemName().replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
        return new GeneratedFile(system + "-" + snapshot.targetYear() + "-" + dialect.fileCode + "-test-ddl.sql",
                "text/plain; charset=UTF-8", ddl.toString().getBytes(StandardCharsets.UTF_8));
    }

    private String value(Map<String, String> values, String key) {
        String value = values.get(key);
        return value == null ? "" : value.trim();
    }

    private record TableKey(String schema, String table) {
    }

    private enum Dialect {
        ORACLE("Oracle", "oracle", "VARCHAR2(255)", Quote.DOUBLE),
        TIBERO("Tibero", "tibero", "VARCHAR2(255)", Quote.DOUBLE),
        ALTIBASE("Altibase", "altibase", "VARCHAR(255)", Quote.DOUBLE),
        MARIA("MariaDB", "mariadb", "VARCHAR(255)", Quote.BACKTICK),
        MYSQL("MySQL", "mysql", "VARCHAR(255)", Quote.BACKTICK),
        POSTGRES("PostgreSQL", "postgresql", "VARCHAR(255)", Quote.DOUBLE),
        SQLSERVER("SQL Server", "sqlserver", "NVARCHAR(255)", Quote.BRACKET),
        CUBRID("CUBRID", "cubrid", "VARCHAR(255)", Quote.DOUBLE),
        DB2("DB2", "db2", "VARCHAR(255)", Quote.DOUBLE),
        SYBASE("Sybase", "sybase", "VARCHAR(255)", Quote.BRACKET);

        private final String label;
        private final String fileCode;
        private final String defaultTextType;
        private final Quote quote;

        Dialect(String label, String fileCode, String defaultTextType, Quote quote) {
            this.label = label;
            this.fileCode = fileCode;
            this.defaultTextType = defaultTextType;
            this.quote = quote;
        }

        static Dialect of(String value) {
            String type = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
            if (type.contains("ORA")) return ORACLE;
            if (type.contains("TIB")) return TIBERO;
            if (type.contains("ALT")) return ALTIBASE;
            if (type.contains("MRA") || type.contains("MARIA")) return MARIA;
            if (type.contains("MYS") || type.contains("MYSQL")) return MYSQL;
            if (type.contains("POS") || type.contains("POST")) return POSTGRES;
            if (type.contains("MSQ") || type.contains("SQL SERVER") || type.contains("MSSQL")) return SQLSERVER;
            if (type.contains("CBR") || type.contains("CUBRID")) return CUBRID;
            if (type.contains("UDB") || type.contains("DB2")) return DB2;
            if (type.contains("SYA") || type.contains("SYQ") || type.contains("SYBASE")) return SYBASE;
            throw new IllegalStateException("지원하지 않는 DBMS 유형입니다: " + value);
        }

        String quote(String identifier) {
            return quote.apply(identifier);
        }

        String qualified(String schema, String table) {
            return schema == null || schema.isBlank() ? quote(table) : quote(schema) + "." + quote(table);
        }

        String columnType(String sourceType) {
            String type = sourceType == null ? "" : sourceType.trim().toUpperCase(Locale.ROOT);
            if (type.isBlank()) return defaultTextType;
            if (type.contains("TIMESTAMP") || type.contains("DATETIME")) return this == SQLSERVER ? "DATETIME2" : "TIMESTAMP";
            if (type.equals("DATE") || type.startsWith("DATE(")) return "DATE";
            if (type.contains("BIGINT") || type.contains("LONG")) return "BIGINT";
            if (type.contains("INT")) return "INTEGER";
            if (type.contains("NUMBER") || type.contains("NUMERIC") || type.contains("DECIMAL")
                    || type.contains("FLOAT") || type.contains("DOUBLE")) return "DECIMAL(38,10)";
            if (type.contains("BLOB") || type.contains("BINARY") || type.contains("RAW")) {
                return switch (this) {
                    case POSTGRES -> "BYTEA";
                    case SQLSERVER -> "VARBINARY(MAX)";
                    default -> "BLOB";
                };
            }
            if (type.contains("CLOB") || type.contains("TEXT")) {
                return switch (this) {
                    case ORACLE, TIBERO -> "CLOB";
                    case SQLSERVER -> "NVARCHAR(MAX)";
                    default -> "TEXT";
                };
            }
            return defaultTextType;
        }
    }

    private enum Quote {
        DOUBLE {
            @Override String apply(String value) { return "\"" + value.replace("\"", "\"\"") + "\""; }
        },
        BACKTICK {
            @Override String apply(String value) { return "`" + value.replace("`", "``") + "`"; }
        },
        BRACKET {
            @Override String apply(String value) { return "[" + value.replace("]", "]]") + "]"; }
        };

        abstract String apply(String value);
    }
}
