package kr.wise.csr.importfile;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class DefaultVerificationRuleCatalog {
    private final JdbcTemplate jdbc;
    private volatile Map<String, DefaultRule> rules;

    public DefaultVerificationRuleCatalog(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<DefaultRule> findByName(String ruleName) {
        if (ruleName == null || ruleName.isBlank()) return Optional.empty();
        return Optional.ofNullable(load().get(ruleName));
    }

    public Map<String, DefaultRule> activeRules() {
        return load();
    }

    private Map<String, DefaultRule> load() {
        Map<String, DefaultRule> current = rules;
        if (current != null) return current;
        synchronized (this) {
            if (rules == null) {
                LinkedHashMap<String, DefaultRule> loaded = new LinkedHashMap<>();
                jdbc.query("select * from default_verification_rule", rs -> {
                    if (!rs.getBoolean("active")) return;
                    DefaultRule rule = new DefaultRule(
                            rs.getString("rule_id"),
                            rs.getString("rule_type"),
                            rs.getString("rule_name"),
                            rs.getString("rule_expression"),
                            rs.getString("description"),
                            rs.getString("excluded_values"),
                            rs.getString("excluded_value_separator"),
                            rs.getString("match_type"));
                    loaded.put(rule.ruleName(), rule);
                });
                rules = Map.copyOf(loaded);
            }
            return rules;
        }
    }

    public record DefaultRule(
            String ruleId,
            String ruleType,
            String ruleName,
            String expression,
            String description,
            String excludedValues,
            String excludedValueSeparator,
            String matchType) {
    }
}
