package de.regelsuche.ide;

import java.util.List;

/**
 * Result of a tree-local rule inspection for a given expression.
 *
 * <p>Groups all rule matches by {@link PositionResult#pathKey()} so callers can
 * select a particular subtree position and see exactly which rules match there,
 * what bindings (parameters) they produce, and what the rewrite preview looks
 * like.</p>
 */
public record RuleInspectionDto(
        String expression,
        List<PositionResult> positions) {

    public RuleInspectionDto {
        positions = positions == null ? List.of() : List.copyOf(positions);
    }

    /**
     * All rule matches at a single tree position.
     *
     * @param pathKey    stable string key for this position ({@code "root"} or
     *                   dot-separated zero-padded indices, e.g. {@code "000.001"})
     * @param subtree    infix text of the subtree at this position
     * @param matches    every rule candidate that fired at this position
     */
    public record PositionResult(
            String pathKey,
            String subtree,
            List<RuleMatch> matches) {

        public PositionResult {
            matches = matches == null ? List.of() : List.copyOf(matches);
        }
    }

    /**
     * A single rule match including bindings and rewrite preview.
     *
     * @param enumeratorId   id of the parameter enumerator that produced the match
     * @param kind           human-readable move kind (e.g. {@code "COMPLETE_SQUARE"})
     * @param bindings       parameter name→value pairs extracted by the enumerator
     * @param rewriteBefore  the subtree text before applying the rule
     * @param rewriteAfter   the subtree text after applying the rule
     *                       ({@code null} when no concrete rewrite could be generated)
     */
    public record RuleMatch(
            String enumeratorId,
            String kind,
            List<Binding> bindings,
            String rewriteBefore,
            String rewriteAfter) {

        public RuleMatch {
            bindings = bindings == null ? List.of() : List.copyOf(bindings);
            rewriteBefore = rewriteBefore == null ? "" : rewriteBefore;
            rewriteAfter = rewriteAfter == null ? "" : rewriteAfter;
        }
    }

    /**
     * A single named binding extracted from a rule match.
     *
     * @param name  parameter name
     * @param value parameter value (infix text)
     * @param kind  parameter classification
     */
    public record Binding(String name, String value, String kind) {
        public Binding {
            name = name == null ? "" : name;
            value = value == null ? "" : value;
            kind = kind == null ? "" : kind;
        }
    }
}
