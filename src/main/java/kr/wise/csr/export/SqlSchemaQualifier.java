package kr.wise.csr.export;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class SqlSchemaQualifier {
    private static final Pattern TABLE_REFERENCE = Pattern.compile(
            "(?i)(\\b(?:from|join)\\s+)(?!\\()([A-Za-z_$#][\\w$#]*(?:\\.[A-Za-z_$#][\\w$#]*)?)");
    private static final Set<String> UNQUALIFIED_OBJECTS = Set.of("DUAL");

    private SqlSchemaQualifier() {
    }

    static String qualify(String sql, String targetSchema, Set<String> sourceSchemas) {
        if (sql == null || sql.isBlank() || targetSchema == null || targetSchema.isBlank()) return sql;
        StringBuilder result = new StringBuilder(sql.length() + 32);
        StringBuilder code = new StringBuilder();
        for (int index = 0; index < sql.length();) {
            char current = sql.charAt(index);
            if (current == '\'' || startsWith(sql, index, "--") || startsWith(sql, index, "/*")) {
                appendQualified(result, code, targetSchema.trim(), sourceSchemas);
                int end = protectedEnd(sql, index, current);
                result.append(sql, index, end);
                index = end;
                continue;
            }
            code.append(current);
            index++;
        }
        appendQualified(result, code, targetSchema.trim(), sourceSchemas);
        return result.toString();
    }

    private static void appendQualified(StringBuilder result, StringBuilder code, String targetSchema,
            Set<String> sourceSchemas) {
        if (code.isEmpty()) return;
        Matcher matcher = TABLE_REFERENCE.matcher(code);
        StringBuffer qualified = new StringBuffer(code.length() + 16);
        while (matcher.find()) {
            String object = matcher.group(2);
            String replacement = qualifiedObject(object, targetSchema, sourceSchemas);
            matcher.appendReplacement(qualified, Matcher.quoteReplacement(matcher.group(1) + replacement));
        }
        matcher.appendTail(qualified);
        result.append(qualified);
        code.setLength(0);
    }

    private static int protectedEnd(String sql, int start, char current) {
        if (current == '\'') {
            for (int index = start + 1; index < sql.length(); index++) {
                if (sql.charAt(index) != '\'') continue;
                if (index + 1 < sql.length() && sql.charAt(index + 1) == '\'') {
                    index++;
                    continue;
                }
                return index + 1;
            }
            return sql.length();
        }
        if (startsWith(sql, start, "--")) {
            int newline = sql.indexOf('\n', start + 2);
            return newline < 0 ? sql.length() : newline + 1;
        }
        int close = sql.indexOf("*/", start + 2);
        return close < 0 ? sql.length() : close + 2;
    }

    private static boolean startsWith(String value, int offset, String expected) {
        return value.regionMatches(offset, expected, 0, expected.length());
    }

    private static String qualifiedObject(String object, String targetSchema, Set<String> sourceSchemas) {
        int separator = object.indexOf('.');
        if (separator < 0) {
            if (UNQUALIFIED_OBJECTS.contains(object.toUpperCase(Locale.ROOT))) return object;
            return targetSchema + "." + object;
        }
        String schema = object.substring(0, separator);
        if (sourceSchemas.stream().anyMatch(schema::equalsIgnoreCase))
            return targetSchema + object.substring(separator);
        return object;
    }
}
