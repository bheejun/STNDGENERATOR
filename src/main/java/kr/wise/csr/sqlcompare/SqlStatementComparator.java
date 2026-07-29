package kr.wise.csr.sqlcompare;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import kr.wise.csr.sqlcompare.SqlComparisonResult.TableDifference;

@Component
public class SqlStatementComparator {
    private static final Pattern INSERT_TARGET = Pattern.compile(
            "^\\s*INSERT\\s+INTO\\s+((?:[`\"\\[]?[A-Za-z0-9_$]+[`\"\\]]?\\s*\\.\\s*)?[`\"\\[]?[A-Za-z0-9_$]+[`\"\\]]?)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final int SAMPLE_LIMIT = 10;

    public SqlComparisonResult compare(String baselineName, java.time.OffsetDateTime uploadedAt,
            String baselineSql, String currentSql) {
        List<Statement> baseline = insertStatements(baselineSql);
        List<Statement> current = insertStatements(currentSql);
        Map<String, List<Statement>> baselineByTable = groupByTable(baseline);
        Map<String, List<Statement>> currentByTable = groupByTable(current);
        List<TableDifference> differences = new ArrayList<>();
        int unchangedTotal = 0;
        int baselineOnlyTotal = 0;
        int currentOnlyTotal = 0;

        TreeSet<String> tables = new TreeSet<>();
        tables.addAll(baselineByTable.keySet());
        tables.addAll(currentByTable.keySet());
        for (String table : tables) {
            List<Statement> oldStatements = baselineByTable.getOrDefault(table, List.of());
            List<Statement> newStatements = currentByTable.getOrDefault(table, List.of());
            MatchResult match = match(oldStatements, newStatements);
            unchangedTotal += match.unchanged();
            baselineOnlyTotal += match.baselineOnly().size();
            currentOnlyTotal += match.currentOnly().size();
            if (!match.baselineOnly().isEmpty() || !match.currentOnly().isEmpty()) {
                differences.add(new TableDifference(table, oldStatements.size(), newStatements.size(),
                        match.unchanged(), match.baselineOnly().size(), match.currentOnly().size(),
                        samples(match.baselineOnly()), samples(match.currentOnly())));
            }
        }
        return new SqlComparisonResult(baselineName, uploadedAt, baseline.size(), current.size(),
                unchangedTotal, baselineOnlyTotal, currentOnlyTotal, List.copyOf(differences));
    }

    private List<Statement> insertStatements(String sql) {
        List<Statement> result = new ArrayList<>();
        for (String raw : splitStatements(sql == null ? "" : sql)) {
            String canonical = canonicalize(raw);
            Matcher matcher = INSERT_TARGET.matcher(canonical);
            if (!matcher.find()) continue;
            result.add(new Statement(normalizeTable(matcher.group(1)), canonical, raw.trim()));
        }
        return result;
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
                } else {
                    quoted = !quoted;
                }
                continue;
            }
            if (!quoted && ch == ';') {
                if (!statement.toString().isBlank()) result.add(statement.toString());
                statement.setLength(0);
            } else {
                statement.append(ch);
            }
        }
        if (!statement.toString().isBlank()) result.add(statement.toString());
        return result;
    }

    private String canonicalize(String sql) {
        StringBuilder result = new StringBuilder();
        boolean quoted = false;
        boolean whitespace = false;
        for (int i = 0; i < sql.length(); i++) {
            char ch = sql.charAt(i);
            char next = i + 1 < sql.length() ? sql.charAt(i + 1) : '\0';
            if (ch == '\'') {
                if (!quoted && whitespace && !result.isEmpty()) result.append(' ');
                whitespace = false;
                result.append(ch);
                if (quoted && next == '\'') {
                    result.append(next);
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (!quoted && Character.isWhitespace(ch)) {
                whitespace = true;
            } else {
                if (!quoted && whitespace && !result.isEmpty()) result.append(' ');
                whitespace = false;
                result.append(quoted ? ch : Character.toUpperCase(ch));
            }
        }
        return result.toString().trim();
    }

    private String normalizeTable(String value) {
        return value.replace("`", "").replace("\"", "").replace("[", "").replace("]", "")
                .replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    }

    private Map<String, List<Statement>> groupByTable(List<Statement> statements) {
        Map<String, List<Statement>> result = new LinkedHashMap<>();
        statements.forEach(statement -> result.computeIfAbsent(statement.table(), ignored -> new ArrayList<>())
                .add(statement));
        return result;
    }

    private MatchResult match(List<Statement> baseline, List<Statement> current) {
        Map<String, List<Statement>> currentPool = new LinkedHashMap<>();
        current.forEach(statement -> currentPool.computeIfAbsent(statement.canonical(), ignored -> new ArrayList<>())
                .add(statement));
        List<Statement> baselineOnly = new ArrayList<>();
        int unchanged = 0;
        for (Statement statement : baseline) {
            List<Statement> matches = currentPool.get(statement.canonical());
            if (matches == null || matches.isEmpty()) baselineOnly.add(statement);
            else {
                matches.remove(matches.size() - 1);
                unchanged++;
            }
        }
        List<Statement> currentOnly = currentPool.values().stream().flatMap(List::stream).toList();
        return new MatchResult(unchanged, baselineOnly, currentOnly);
    }

    private List<String> samples(List<Statement> statements) {
        return statements.stream().limit(SAMPLE_LIMIT).map(Statement::original).toList();
    }

    private record Statement(String table, String canonical, String original) {
    }

    private record MatchResult(int unchanged, List<Statement> baselineOnly, List<Statement> currentOnly) {
    }
}
