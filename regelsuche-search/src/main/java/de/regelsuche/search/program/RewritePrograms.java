package de.regelsuche.search.program;

import de.regelsuche.search.program.RewriteProgram.NodeMetadata;
import de.regelsuche.search.program.RewriteProgram.SourceLocation;
import de.regelsuche.transform.TransformationEngine;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/** Fluent factories for Java-internal rewrite programs. */
public final class RewritePrograms {
    private RewritePrograms() {
    }

    public static RewriteProgram source(String id, TransformationEngine engine) {
        return new RewriteProgram.Source(NodeMetadata.named(id), engine);
    }

    public static RewriteProgram source(
        String id,
        String label,
        SourceLocation location,
        TransformationEngine engine
    ) {
        return new RewriteProgram.Source(new NodeMetadata(id, label, location), engine);
    }

    public static RewriteProgram choice(String id, RewriteProgram... alternatives) {
        return new RewriteProgram.Choice(NodeMetadata.named(id), programs(alternatives));
    }

    public static RewriteProgram firstApplicable(String id, RewriteProgram... alternatives) {
        return new RewriteProgram.FirstApplicable(NodeMetadata.named(id), programs(alternatives));
    }

    public static RewriteProgram sequence(String id, RewriteProgram... steps) {
        return new RewriteProgram.Sequence(NodeMetadata.named(id), programs(steps));
    }

    public static RewriteProgram repeat(
        String id,
        int maxIterations,
        RewriteProgram body
    ) {
        return repeat(id, 1, maxIterations, body);
    }

    public static RewriteProgram repeat(
        String id,
        int minIterations,
        int maxIterations,
        RewriteProgram body
    ) {
        return new RewriteProgram.Repeat(
            NodeMetadata.named(id), body, minIterations, maxIterations);
    }

    public static RewriteProgram require(
        String id,
        RewriteProgram body,
        String description,
        Predicate<RewriteCandidate> condition
    ) {
        return new RewriteProgram.Require(
            NodeMetadata.named(id), body, description, condition);
    }

    public static RewriteProgram prioritize(
        String id,
        RewriteProgram body,
        String description,
        Comparator<RewriteCandidate> comparator
    ) {
        return new RewriteProgram.Prioritize(
            NodeMetadata.named(id), body, description, comparator);
    }

    public static RewriteProgram prune(
        String id,
        RewriteProgram body,
        int maxCandidates,
        String reason
    ) {
        return new RewriteProgram.Prune(
            NodeMetadata.named(id), body, maxCandidates, reason);
    }

    public static Comparator<RewriteCandidate> byEstimatedCostThenRule() {
        return Comparator
            .comparingInt((RewriteCandidate candidate) ->
                candidate.toTransformation().estimatedCostDelta())
            .thenComparing(candidate -> candidate.toTransformation().rule())
            .thenComparing(RewriteCandidate::outputExpression)
            .thenComparing(RewriteCandidate::fingerprint);
    }

    public static Comparator<RewriteCandidate> preferRuleOrder(List<String> ruleIds) {
        List<String> preferred = List.copyOf(Objects.requireNonNull(ruleIds, "ruleIds"));
        return Comparator
            .comparingInt((RewriteCandidate candidate) -> {
                int index = preferred.indexOf(candidate.lastStep().rule());
                return index < 0 ? Integer.MAX_VALUE : index;
            })
            .thenComparing(candidate -> candidate.lastStep().rule())
            .thenComparing(RewriteCandidate::outputExpression)
            .thenComparing(RewriteCandidate::fingerprint);
    }

    public static Predicate<RewriteCandidate> equivalencePreserving() {
        return candidate -> candidate.steps().stream()
            .allMatch(step -> step.equivalencePreservingByConstruction());
    }

    private static List<RewriteProgram> programs(RewriteProgram[] values) {
        Objects.requireNonNull(values, "values");
        return Arrays.stream(values)
            .map(value -> Objects.requireNonNull(value, "program"))
            .toList();
    }
}
