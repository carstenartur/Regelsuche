package de.regelsuche.plugin;

import de.regelsuche.transform.PatternExpr;
import de.regelsuche.transform.PatternRewriteRule;
import de.regelsuche.transform.RewriteRule;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Detects competing rules and transformations that share the same source
 * (left-hand side) pattern. Two distinct rules with structurally identical
 * source patterns compete for the same matches and can cause redundant or
 * conflicting search edges, so the runtime surfaces them as diagnostics.
 *
 * <p>Placeholder names are normalised to their first-appearance order, so
 * {@code A^2 - B^2} and {@code X^2 - Y^2} are recognised as the same shape.</p>
 */
public final class RuleConflictDetector {
    private RuleConflictDetector() {
    }

    /**
     * @param items rules and transformations to analyse, in registration order
     * @return one conflict per group of two or more items that share a source
     *         pattern signature, in deterministic order
     */
    public static List<RuleConflict> detect(List<ConflictCandidate> items) {
        Map<String, List<String>> bySignature = new LinkedHashMap<>();
        for (ConflictCandidate item : items) {
            Optional<PatternExpr> source = sourcePattern(item.rule());
            if (source.isEmpty()) {
                continue;
            }
            String signature = canonicalSignature(source.get());
            bySignature.computeIfAbsent(signature, key -> new ArrayList<>()).add(item.id());
        }
        List<RuleConflict> conflicts = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : bySignature.entrySet()) {
            List<String> ids = entry.getValue();
            if (ids.size() > 1) {
                conflicts.add(new RuleConflict(entry.getKey(), List.copyOf(ids)));
            }
        }
        return List.copyOf(conflicts);
    }

    /**
     * Detects pairs of rules that are structural inverses of one another, i.e.
     * one rewrites {@code S -> T} while the other rewrites {@code T -> S}. Such
     * pairs form a two-step cycle in the search graph and can cause the rewriter
     * to oscillate indefinitely between the two shapes (a potential infinite
     * loop), so the runtime surfaces them so that priorities, directions or
     * activation profiles can break the cycle.
     *
     * <p>Placeholder names are normalised consistently across each rule's source
     * and target, so {@code (A + B)^2 -> A^2 + 2*A*B + B^2} and
     * {@code X^2 + 2*X*Y + Y^2 -> (X + Y)^2} are recognised as inverses.</p>
     *
     * @param items rules and transformations to analyse, in registration order
     * @return one entry per pair of mutually inverse rules, in deterministic order
     */
    public static List<CyclicConflict> detectCycles(List<ConflictCandidate> items) {
        record Directed(String id, String forward, String reverse) {
        }
        List<Directed> directed = new ArrayList<>();
        Map<String, List<Directed>> byForward = new LinkedHashMap<>();
        for (ConflictCandidate item : items) {
            Optional<PatternExpr> source = sourcePattern(item.rule());
            Optional<PatternExpr> target = targetPattern(item.rule());
            if (source.isEmpty() || target.isEmpty()) {
                continue;
            }
            String forward = pairSignature(source.get(), target.get());
            String reverse = pairSignature(target.get(), source.get());
            Directed entry = new Directed(item.id(), forward, reverse);
            directed.add(entry);
            byForward.computeIfAbsent(forward, key -> new ArrayList<>()).add(entry);
        }
        List<CyclicConflict> cycles = new ArrayList<>();
        Set<String> reported = new HashSet<>();
        for (Directed entry : directed) {
            for (Directed inverse : byForward.getOrDefault(entry.reverse(), List.of())) {
                if (inverse.id().equals(entry.id())) {
                    continue;
                }
                String pairKey = entry.id().compareTo(inverse.id()) <= 0
                    ? entry.id() + '\u0000' + inverse.id()
                    : inverse.id() + '\u0000' + entry.id();
                if (!reported.add(pairKey)) {
                    continue;
                }
                List<String> ids = new ArrayList<>(List.of(entry.id(), inverse.id()));
                ids.sort(String::compareTo);
                cycles.add(new CyclicConflict(List.copyOf(ids)));
            }
        }
        return List.copyOf(cycles);
    }

    private static Optional<PatternExpr> sourcePattern(RewriteRule rule) {
        if (rule instanceof PatternRewriteRule patternRule) {
            return Optional.of(patternRule.source());
        }
        if (rule instanceof PatternBasedTransformation transformation) {
            return Optional.of(transformation.source());
        }
        return Optional.empty();
    }

    private static Optional<PatternExpr> targetPattern(RewriteRule rule) {
        if (rule instanceof PatternRewriteRule patternRule) {
            return Optional.of(patternRule.target());
        }
        if (rule instanceof PatternBasedTransformation transformation) {
            return Optional.of(transformation.target());
        }
        return Optional.empty();
    }

    private static String pairSignature(PatternExpr first, PatternExpr second) {
        StringBuilder builder = new StringBuilder();
        Map<String, Integer> placeholders = new LinkedHashMap<>();
        appendSignature(first, builder, placeholders);
        builder.append("=>");
        appendSignature(second, builder, placeholders);
        return builder.toString();
    }

    private static String canonicalSignature(PatternExpr pattern) {
        StringBuilder builder = new StringBuilder();
        appendSignature(pattern, builder, new LinkedHashMap<>());
        return builder.toString();
    }

    private static void appendSignature(PatternExpr pattern, StringBuilder builder, Map<String, Integer> placeholders) {
        switch (pattern) {
            case PatternExpr.Placeholder placeholder -> {
                int index = placeholders.computeIfAbsent(placeholder.name(), key -> placeholders.size());
                builder.append('?').append(index);
            }
            case PatternExpr.LiteralNumber literal -> builder.append('#').append(literal.value());
            case PatternExpr.Operation operation -> {
                builder.append('(');
                appendSignature(operation.left(), builder, placeholders);
                builder.append(operation.operator().symbol());
                appendSignature(operation.right(), builder, placeholders);
                builder.append(')');
            }
            case PatternExpr.Function function -> {
                builder.append(function.name()).append('(');
                List<PatternExpr> arguments = function.arguments();
                for (int i = 0; i < arguments.size(); i++) {
                    if (i > 0) {
                        builder.append(',');
                    }
                    appendSignature(arguments.get(i), builder, placeholders);
                }
                builder.append(')');
            }
        }
    }

    /**
     * A rule or transformation that can participate in conflict detection.
     */
    public record ConflictCandidate(String id, RewriteRule rule) {
    }

    /**
     * A group of two or more rule ids that share the same source pattern.
     */
    public record RuleConflict(String patternSignature, List<String> ruleIds) {
        public RuleConflict {
            ruleIds = List.copyOf(ruleIds);
        }
    }

    /**
     * A pair of rule ids that are structural inverses of one another and thus
     * form a two-step cycle (a potential infinite loop) in the search graph.
     */
    public record CyclicConflict(List<String> ruleIds) {
        public CyclicConflict {
            ruleIds = List.copyOf(ruleIds);
        }
    }
}
