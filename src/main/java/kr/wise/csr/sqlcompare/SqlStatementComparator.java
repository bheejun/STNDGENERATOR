package kr.wise.csr.sqlcompare;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import kr.wise.csr.sqlcompare.SqlComparisonResult.RowChange;
import kr.wise.csr.sqlcompare.SqlComparisonResult.RowSample;
import kr.wise.csr.sqlcompare.SqlComparisonResult.TableDifference;
import kr.wise.csr.sqlcompare.SqlComparisonResult.ValueDifference;

@Component
public class SqlStatementComparator {
    private static final Pattern INSERT = Pattern.compile(
            "^\\s*INSERT\\s+(?:IGNORE\\s+)?INTO\\s+((?:[`\"\\[]?[A-Za-z0-9_$]+[`\"\\]]?\\s*\\.\\s*)?[`\"\\[]?[A-Za-z0-9_$]+[`\"\\]]?)\\s*\\(",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Set<String> IGNORED_COLUMNS = Set.of(
            "EXP_DTM", "STR_DTM", "WRIT_DTM", "RQST_DTM", "OBJ_VERS", "WRIT_USER_ID", "OPEN_YM",
            "STND_SYS_NM", "STND_DBMS_PNM", "STND_DBMS_LNM");
    private static final Set<String> PREFIXED_TEXT_COLUMNS = Set.of(
            "VRFC_NM", "BR_NM", "TBL_EXP_RSN", "TBL_EXP_STND_RULE_RSN", "COL_EXP_RSN", "OBJ_DESCN");
    private static final Set<String> SQL_COLUMNS = Set.of(
            "VRFC_RULE", "CD_SQL", "CNT_SQL", "ERR_CNT_SQL", "ANA_SQL");
    private static final Map<String, Set<String>> GENERATED_KEYS = Map.of(
            "WAA_DB_CONN_TRG", Set.of("DB_CONN_TRG_ID"),
            "WAA_STND_EXP_OBJ", Set.of("STND_EXP_OBJ_ID"),
            "WAA_VRFC_RULE", Set.of("VRFC_ID"),
            "WAA_CD_RULE", Set.of("CD_RULE_ID"),
            "WAA_CD_LIST", Set.of("CD_RULE_ID", "DB_CONN_TRG_ID"),
            "WAA_STND_RULE_SET", Set.of("STND_RULE_SET_ID"),
            "WAA_STND_TBL_PRF", Set.of("STND_TBL_PRF_ID"));
    private static final Map<String, List<String>> LOGICAL_KEYS = Map.of(
            "WAA_DB_CONN_TRG", List.of(),
            "WAA_STND_EXP_OBJ", List.of("STND_SCH_PNM", "STND_TBL_PNM", "STND_COL_PNM", "EXP_TYP",
                    "TBL_EXP_STND_RULE"),
            "WAA_VRFC_RULE", List.of("VRFC_NM"),
            "WAA_CD_RULE", List.of("CD_RULE_NM"),
            "WAA_CD_LIST", List.of("CD_RULE_NM", "CD_ID"),
            "WAA_STND_RULE_SET", List.of("STND_SCH_PNM", "STND_TBL_PNM", "STND_COL_PNM"),
            "WAA_STND_TBL_PRF", List.of("STND_SCH_PNM", "STND_TBL_PNM", "STND_COL_PNM", "BR_NM"));
    private static final int SAMPLE_LIMIT = 10;

    public SqlComparisonResult compare(String baselineName, OffsetDateTime uploadedAt,
            String baselineSql, String currentSql) {
        List<Row> baseline = resolveRuleReferences(parseRows(baselineSql));
        List<Row> current = resolveRuleReferences(parseRows(currentSql));
        Map<String, List<Row>> baselineByTable = groupByTable(baseline);
        Map<String, List<Row>> currentByTable = groupByTable(current);
        List<TableDifference> differences = new ArrayList<>();
        int unchangedTotal = 0;
        int changedTotal = 0;
        int baselineOnlyTotal = 0;
        int currentOnlyTotal = 0;

        TreeSet<String> tables = new TreeSet<>();
        tables.addAll(baselineByTable.keySet());
        tables.addAll(currentByTable.keySet());
        for (String table : tables) {
            List<Row> oldRows = baselineByTable.getOrDefault(table, List.of());
            List<Row> newRows = currentByTable.getOrDefault(table, List.of());
            MatchResult match = match(table, oldRows, newRows);
            unchangedTotal += match.unchanged();
            changedTotal += match.changed().size();
            baselineOnlyTotal += match.baselineOnly().size();
            currentOnlyTotal += match.currentOnly().size();
            if (!match.changed().isEmpty() || !match.baselineOnly().isEmpty() || !match.currentOnly().isEmpty()) {
                differences.add(new TableDifference(table, oldRows.size(), newRows.size(), match.unchanged(),
                        match.changed().size(), match.baselineOnly().size(), match.currentOnly().size(),
                        match.changed().stream().limit(SAMPLE_LIMIT).toList(),
                        samples(table, match.baselineOnly()), samples(table, match.currentOnly())));
            }
        }
        return new SqlComparisonResult(baselineName, uploadedAt, baseline.size(), current.size(),
                unchangedTotal, changedTotal, baselineOnlyTotal, currentOnlyTotal, List.copyOf(differences));
    }

    List<Row> parseRows(String sql) {
        List<Row> result = new ArrayList<>();
        for (String statement : splitStatements(sql == null ? "" : sql)) {
            Matcher matcher = INSERT.matcher(statement);
            if (!matcher.find()) continue;
            String table = normalizeTable(matcher.group(1));
            int columnOpen = statement.indexOf('(', matcher.start(1) + matcher.group(1).length());
            int columnClose = matchingParen(statement, columnOpen);
            if (columnClose < 0) continue;
            List<String> columns = splitExpressions(statement.substring(columnOpen + 1, columnClose)).stream()
                    .map(this::normalizeIdentifier).toList();
            String body = statement.substring(columnClose + 1).trim();
            List<List<String>> values = valuesRows(body);
            if (values.isEmpty()) {
                List<String> selected = selectRow(body);
                if (!selected.isEmpty()) values = List.of(selected);
            }
            for (List<String> rowValues : values) {
                if (rowValues.size() != columns.size()) continue;
                Map<String, String> mapped = new LinkedHashMap<>();
                for (int i = 0; i < columns.size(); i++)
                    mapped.put(columns.get(i), normalizeValue(rowValues.get(i)));
                result.add(new Row(table, Map.copyOf(mapped), rowSql(table, columns, rowValues)));
            }
        }
        return result;
    }

    private List<List<String>> valuesRows(String body) {
        int valuesAt = wordAtTopLevel(body, "VALUES");
        if (valuesAt < 0) return List.of();
        String source = body.substring(valuesAt + 6);
        List<List<String>> rows = new ArrayList<>();
        int position = 0;
        while (position < source.length()) {
            position = skipWhitespaceAndComma(source, position);
            if (position >= source.length() || source.charAt(position) != '(') break;
            int close = matchingParen(source, position);
            if (close < 0) break;
            rows.add(splitExpressions(source.substring(position + 1, close)));
            position = close + 1;
        }
        return rows;
    }

    private List<String> selectRow(String body) {
        String upper = body.toUpperCase(Locale.ROOT);
        int start = upper.startsWith("SELECT DISTINCT ") ? 16 : upper.startsWith("SELECT ") ? 7 : -1;
        if (start < 0) return List.of();
        int from = wordAtTopLevel(body.substring(start), "FROM");
        if (from < 0) return List.of();
        return splitExpressions(body.substring(start, start + from));
    }

    private MatchResult match(String table, List<Row> baseline, List<Row> current) {
        Map<String, List<Row>> currentByKey = new LinkedHashMap<>();
        current.forEach(row -> currentByKey.computeIfAbsent(logicalKey(table, row), ignored -> new ArrayList<>())
                .add(row));
        List<RowChange> changed = new ArrayList<>();
        List<Row> baselineOnly = new ArrayList<>();
        int unchanged = 0;
        for (Row oldRow : baseline) {
            String key = logicalKey(table, oldRow);
            List<Row> candidates = currentByKey.get(key);
            if (candidates == null || candidates.isEmpty()) {
                baselineOnly.add(oldRow);
                continue;
            }
            int exact = exactIndex(oldRow, candidates);
            if (exact >= 0) {
                candidates.remove(exact);
                unchanged++;
            } else {
                Row newRow = candidates.remove(0);
                changed.add(new RowChange(key, valueDifferences(oldRow, newRow)));
            }
        }
        List<Row> currentOnly = currentByKey.values().stream().flatMap(List::stream).toList();
        return new MatchResult(unchanged, changed, baselineOnly, currentOnly);
    }

    private int exactIndex(Row baseline, List<Row> candidates) {
        Map<String, String> oldComparable = comparable(baseline);
        for (int i = 0; i < candidates.size(); i++)
            if (oldComparable.equals(comparable(candidates.get(i)))) return i;
        return -1;
    }

    private Map<String, String> comparable(Row row) {
        Map<String, String> result = new LinkedHashMap<>();
        row.values().entrySet().stream().filter(entry -> !ignored(row.table(), entry.getKey()))
                .forEach(entry -> result.put(entry.getKey(), semanticValue(entry.getKey(), entry.getValue())));
        return result;
    }

    private List<ValueDifference> valueDifferences(Row baseline, Row current) {
        Set<String> columns = new LinkedHashSet<>();
        columns.addAll(baseline.values().keySet());
        columns.addAll(current.values().keySet());
        return columns.stream().filter(column -> !ignored(baseline.table(), column))
                .filter(column -> !semanticValue(column, baseline.values().getOrDefault(column, "<컬럼 없음>"))
                        .equals(semanticValue(column, current.values().getOrDefault(column, "<컬럼 없음>"))))
                .map(column -> new ValueDifference(column,
                        baseline.values().getOrDefault(column, "<컬럼 없음>"),
                        current.values().getOrDefault(column, "<컬럼 없음>")))
                .toList();
    }

    private String logicalKey(String table, Row row) {
        List<String> keys = LOGICAL_KEYS.get(table);
        if (keys == null) return comparable(row).toString();
        if (keys.isEmpty()) return table;
        String value = keys.stream().map(column -> keyValue(column, row.values().get(column)))
                .reduce((left, right) -> left + "|" + right).orElse("");
        return value.isBlank() ? comparable(row).toString() : value;
    }

    private String keyValue(String column, String value) {
        if (value == null) return "<NULL>";
        if (column.endsWith("_NM")) {
            String literal = unquote(value);
            literal = literal.replaceFirst("^\\[[^]]+\\]\\s*", "");
            return literal.toUpperCase(Locale.ROOT);
        }
        return value;
    }

    private boolean ignored(String table, String column) {
        return IGNORED_COLUMNS.contains(column)
                || GENERATED_KEYS.getOrDefault(table, Set.of()).contains(column);
    }

    private String semanticValue(String column, String value) {
        String result = value;
        if (PREFIXED_TEXT_COLUMNS.contains(column)) {
            String literal = unquote(result).replaceFirst("^\\[[^]]+\\]\\s*", "");
            result = "'" + literal.replace("'", "''") + "'";
        }
        if (SQL_COLUMNS.contains(column)) {
            String literal = unquote(result).replace("\\n", " ").replaceAll("\\s+", " ").trim();
            result = "'" + literal.replace("'", "''") + "'";
        }
        return result;
    }

    private List<Row> resolveRuleReferences(List<Row> rows) {
        Map<String, String> ruleNames = new LinkedHashMap<>();
        for (Row row : rows) {
            if ("WAA_VRFC_RULE".equals(row.table())) {
                String id = unquote(row.values().getOrDefault("VRFC_ID", ""));
                String name = keyValue("VRFC_NM", row.values().get("VRFC_NM"));
                if (!id.isBlank()) ruleNames.put(id.toUpperCase(Locale.ROOT), "RULE:" + name);
            } else if ("WAA_CD_RULE".equals(row.table())) {
                String id = unquote(row.values().getOrDefault("CD_RULE_ID", ""));
                String name = keyValue("CD_RULE_NM", row.values().get("CD_RULE_NM"));
                if (!id.isBlank()) ruleNames.put(id.toUpperCase(Locale.ROOT), "RULE:" + name);
            }
        }
        return rows.stream().map(row -> {
            if (!"WAA_STND_RULE_SET".equals(row.table())) return row;
            String rawId = row.values().get("VRFC_ID");
            String resolved = ruleNames.get(unquote(rawId == null ? "" : rawId).toUpperCase(Locale.ROOT));
            if (resolved == null) return row;
            Map<String, String> values = new LinkedHashMap<>(row.values());
            values.put("VRFC_ID", "'" + resolved.replace("'", "''") + "'");
            return new Row(row.table(), Map.copyOf(values), row.original());
        }).toList();
    }

    private List<RowSample> samples(String table, List<Row> rows) {
        return rows.stream().limit(SAMPLE_LIMIT)
                .map(row -> new RowSample(logicalKey(table, row), row.original())).toList();
    }

    private List<String> splitStatements(String sql) {
        List<String> result = new ArrayList<>();
        StringBuilder statement = new StringBuilder();
        boolean quoted = false;
        boolean lineComment = false;
        for (int i = 0; i < sql.length(); i++) {
            char ch = sql.charAt(i);
            char next = i + 1 < sql.length() ? sql.charAt(i + 1) : '\0';
            if (lineComment) {
                if (ch == '\r' || ch == '\n') {
                    lineComment = false;
                    statement.append(' ');
                }
                continue;
            }
            if (!quoted && ch == '-' && next == '-') {
                lineComment = true;
                i++;
                continue;
            }
            if (ch == '\'') {
                statement.append(ch);
                if (quoted && next == '\'') {
                    statement.append(next);
                    i++;
                } else quoted = !quoted;
                continue;
            }
            if (!quoted && ch == ';') {
                if (!statement.toString().isBlank()) result.add(statement.toString());
                statement.setLength(0);
            } else statement.append(ch);
        }
        if (!statement.toString().isBlank()) result.add(statement.toString());
        return result;
    }

    private List<String> splitExpressions(String source) {
        List<String> result = new ArrayList<>();
        StringBuilder value = new StringBuilder();
        boolean quoted = false;
        int depth = 0;
        for (int i = 0; i < source.length(); i++) {
            char ch = source.charAt(i);
            char next = i + 1 < source.length() ? source.charAt(i + 1) : '\0';
            if (ch == '\'') {
                value.append(ch);
                if (quoted && next == '\'') {
                    value.append(next);
                    i++;
                } else quoted = !quoted;
            } else if (!quoted && ch == '(') {
                depth++;
                value.append(ch);
            } else if (!quoted && ch == ')') {
                depth--;
                value.append(ch);
            } else if (!quoted && depth == 0 && ch == ',') {
                result.add(value.toString().trim());
                value.setLength(0);
            } else value.append(ch);
        }
        result.add(value.toString().trim());
        return result;
    }

    private int matchingParen(String source, int open) {
        boolean quoted = false;
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char ch = source.charAt(i);
            char next = i + 1 < source.length() ? source.charAt(i + 1) : '\0';
            if (ch == '\'') {
                if (quoted && next == '\'') i++;
                else quoted = !quoted;
            } else if (!quoted && ch == '(') depth++;
            else if (!quoted && ch == ')' && --depth == 0) return i;
        }
        return -1;
    }

    private int wordAtTopLevel(String source, String word) {
        boolean quoted = false;
        int depth = 0;
        for (int i = 0; i <= source.length() - word.length(); i++) {
            char ch = source.charAt(i);
            char next = i + 1 < source.length() ? source.charAt(i + 1) : '\0';
            if (ch == '\'') {
                if (quoted && next == '\'') i++;
                else quoted = !quoted;
                continue;
            }
            if (quoted) continue;
            if (ch == '(') depth++;
            else if (ch == ')') depth--;
            else if (depth == 0 && source.regionMatches(true, i, word, 0, word.length())
                    && (i == 0 || !Character.isLetterOrDigit(source.charAt(i - 1)))
                    && (i + word.length() == source.length()
                            || !Character.isLetterOrDigit(source.charAt(i + word.length()))))
                return i;
        }
        return -1;
    }

    private int skipWhitespaceAndComma(String source, int start) {
        int result = start;
        while (result < source.length()
                && (Character.isWhitespace(source.charAt(result)) || source.charAt(result) == ',')) result++;
        return result;
    }

    private String normalizeValue(String value) {
        StringBuilder result = new StringBuilder();
        boolean quoted = false;
        boolean whitespace = false;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            char next = i + 1 < value.length() ? value.charAt(i + 1) : '\0';
            if (ch == '\'') {
                if (!quoted && whitespace && !result.isEmpty()) result.append(' ');
                whitespace = false;
                result.append(ch);
                if (quoted && next == '\'') {
                    result.append(next);
                    i++;
                } else quoted = !quoted;
            } else if (quoted && (ch == '\r' || ch == '\n')) {
                if (ch == '\r' && next == '\n') i++;
                result.append("\\n");
            } else if (quoted && ch == '\\' && (next == 'n' || next == 'r')) {
                result.append("\\n");
                i++;
            } else if (!quoted && Character.isWhitespace(ch)) whitespace = true;
            else {
                if (!quoted && whitespace && !result.isEmpty()) result.append(' ');
                whitespace = false;
                result.append(quoted ? ch : Character.toUpperCase(ch));
            }
        }
        return result.toString().trim();
    }

    private String normalizeTable(String value) {
        String normalized = normalizeIdentifier(value).replaceAll("\\s+", "");
        int dot = normalized.lastIndexOf('.');
        return dot >= 0 ? normalized.substring(dot + 1) : normalized;
    }

    private String normalizeIdentifier(String value) {
        return value.replace("`", "").replace("\"", "").replace("[", "").replace("]", "")
                .trim().toUpperCase(Locale.ROOT);
    }

    private String unquote(String value) {
        if (value.length() >= 2 && value.startsWith("'") && value.endsWith("'"))
            return value.substring(1, value.length() - 1).replace("''", "'");
        return value;
    }

    private String compact(String value) {
        return value.replaceAll("\\s+", " ").trim();
    }

    private String rowSql(String table, List<String> columns, List<String> values) {
        return "INSERT INTO " + table + " (" + String.join(", ", columns) + ") VALUES ("
                + values.stream().map(this::compact).reduce((left, right) -> left + ", " + right).orElse("")
                + ");";
    }

    private Map<String, List<Row>> groupByTable(List<Row> rows) {
        Map<String, List<Row>> result = new LinkedHashMap<>();
        rows.forEach(row -> result.computeIfAbsent(row.table(), ignored -> new ArrayList<>()).add(row));
        return result;
    }

    record Row(String table, Map<String, String> values, String original) {
    }

    private record MatchResult(int unchanged, List<RowChange> changed, List<Row> baselineOnly,
            List<Row> currentOnly) {
    }
}
