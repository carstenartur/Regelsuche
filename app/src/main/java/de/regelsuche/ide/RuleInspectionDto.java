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
     * @param selected   whether this position is currently selected in the UI
     */
    public record PositionResult(
            String pathKey,
            String subtree,
            List<RuleMatch> matches,
            boolean selected) {

        public PositionResult {
            matches = matches == null ? List.of() : List.copyOf(matches);
        }

        public PositionResult(
                String pathKey,
                String subtree,
                List<RuleMatch> matches) {
            this(pathKey, subtree, matches, false);
        }
    }

    /**
     * A single rule match including bindings and rewrite preview.
     *
     * @param enumeratorId   id of the parameter enumerator that produced the match
     * @param kind           human-readable move kind (e.g. {@code "COMPLETE_SQUARE"})
     * @param bindings       parameter name→value pairs extracted by the enumerator
     * @param rewriteBefore  compatibility alias for {@code subtreeBefore}
     * @param rewriteAfter   compatibility alias for {@code subtreeAfter}
     *                       ({@code null} when no concrete rewrite could be generated)
     * @param subtreeBefore  the subtree text before applying the rule
     * @param subtreeAfter   the subtree text after applying the rule
     * @param expressionAfter the full expression after applying the local rewrite
     * @param applicable    whether this match can currently be applied
     */
    public record RuleMatch(
            String enumeratorId,
            String kind,
            List<Binding> bindings,
            String rewriteBefore,
            String rewriteAfter,
            String subtreeBefore,
            String subtreeAfter,
            String expressionAfter,
            boolean applicable) {

        public RuleMatch {
            bindings = bindings == null ? List.of() : List.copyOf(bindings);
            subtreeBefore = subtreeBefore == null ? (rewriteBefore == null ? "" : rewriteBefore) : subtreeBefore;
            subtreeAfter = subtreeAfter == null ? rewriteAfter : subtreeAfter;
            rewriteBefore = rewriteBefore == null ? subtreeBefore : rewriteBefore;
            rewriteAfter = rewriteAfter == null ? subtreeAfter : rewriteAfter;
            applicable = applicable || (expressionAfter != null && !expressionAfter.isBlank())
                    || (subtreeAfter != null && !subtreeAfter.isBlank());
        }

        public RuleMatch(
                String enumeratorId,
                String kind,
                List<Binding> bindings,
                String rewriteBefore,
                String rewriteAfter) {
            this(enumeratorId, kind, bindings, rewriteBefore, rewriteAfter, rewriteBefore, rewriteAfter, null, false);
        }

        public RuleMatch(
                String enumeratorId,
                String kind,
                List<Binding> bindings,
                String rewriteBefore,
                String rewriteAfter,
                String subtreeBefore,
                String subtreeAfter,
                String expressionAfter) {
            this(enumeratorId, kind, bindings, rewriteBefore, rewriteAfter, subtreeBefore, subtreeAfter, expressionAfter, false);
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
