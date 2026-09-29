package de.regelsuche.plugin;

import de.regelsuche.transform.RewriteRule;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@de.regelsuche.api.StableApi(since = "1")
public final class RuleRegistry {
    private final Map<String, MutableRuleRegistration> rules = new LinkedHashMap<>();

    /** Registers and returns the exact typed object for subsequent Java selection. */
    public <R extends RewriteRule> R registerAndGet(R rule) {
        register(rule);
        return rule;
    }

    /**
     * Resolves a wire identity at an import boundary. Unknown identities are errors,
     * not silent no-ops. Registration is not a statement of mathematical trust.
     */
    public RewriteRule requireRule(String id) {
        MutableRuleRegistration registration = rules.get(Objects.requireNonNull(id, "id"));
        if (registration == null) {
            throw new IllegalArgumentException("Unknown rule id: " + id);
        }
        return registration.rule;
    }

    public void register(RewriteRule rule) {
        register(rule, "plugin", "", List.of(), List.of());
    }

    public void register(RewriteRule rule, String source, String explanation, List<String> tags) {
        register(rule, source, explanation, tags, List.of());
    }

    public void register(
        RewriteRule rule,
        String source,
        String explanation,
        List<String> tags,
        List<RuleFileParser.RuleCondition> conditions
    ) {
        Objects.requireNonNull(rule, "rule");
        if (rules.containsKey(rule.id())) {
            throw new IllegalArgumentException("Duplicate rule id: " + rule.id());
        }
        rules.put(rule.id(), new MutableRuleRegistration(rule, source, explanation, tags, conditions));
    }

    public void disable(String id) {
        MutableRuleRegistration registration = rules.get(id);
        if (registration != null) {
            registration.enabled = false;
        }
    }

    public List<RewriteRule> enabledRules() {
        List<RewriteRule> enabled = new ArrayList<>();
        for (MutableRuleRegistration registration : rules.values()) {
            if (registration.enabled) {
                enabled.add(registration.rule);
            }
        }
        return List.copyOf(enabled);
    }

    public List<RuleRegistration> registrations() {
        List<RuleRegistration> registrations = new ArrayList<>();
        for (MutableRuleRegistration registration : rules.values()) {
            registrations.add(registration.snapshot());
        }
        return List.copyOf(registrations);
    }

    public record RuleRegistration(
        String id,
        RewriteRule rule,
        String source,
        String explanation,
        List<String> tags,
        List<RuleFileParser.RuleCondition> conditions,
        boolean enabled
    ) {
        public RuleRegistration {
            tags = List.copyOf(tags);
            conditions = List.copyOf(conditions);
        }
    }

    private static final class MutableRuleRegistration {
        private final RewriteRule rule;
        private final String source;
        private final String explanation;
        private final List<String> tags;
        private final List<RuleFileParser.RuleCondition> conditions;
        private boolean enabled = true;

        private MutableRuleRegistration(
            RewriteRule rule,
            String source,
            String explanation,
            List<String> tags,
            List<RuleFileParser.RuleCondition> conditions
        ) {
            this.rule = rule;
            this.source = source;
            this.explanation = explanation == null ? "" : explanation;
            this.tags = List.copyOf(tags);
            this.conditions = List.copyOf(conditions);
        }

        private RuleRegistration snapshot() {
            return new RuleRegistration(rule.id(), rule, source, explanation, tags, conditions, enabled);
        }
    }
}
