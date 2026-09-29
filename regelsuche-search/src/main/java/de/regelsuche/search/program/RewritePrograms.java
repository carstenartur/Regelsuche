package de.regelsuche.search.program;

import de.regelsuche.search.program.RewriteProgram.NodeMetadata;
import de.regelsuche.search.program.RewriteProgram.SourceLocation;
import de.regelsuche.transform.RewriteRule;
import de.regelsuche.transform.TransformationEngine;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.IntStream;

/** Fluent factories for Java-internal rewrite programs. */
public final class RewritePrograms {
    private RewritePrograms() {
    }

    /** Selects the actual engine; no string is interpreted as a procedure name. */
    public static Draft source(TransformationEngine engine) {
        Objects.requireNonNull(engine, "engine");
        return new Draft(id -> source(id, engine));
    }

    public static Draft budgetedSource(BudgetedTransformationSource source) {
        Objects.requireNonNull(source, "source");
        return new Draft(id -> budgetedSource(id, source));
    }

    public static Draft choice(Draft... alternatives) {
        return compose(alternatives, RewriteProgram.Choice::new);
    }

    public static Draft firstApplicable(Draft... alternatives) {
        return compose(alternatives, RewriteProgram.FirstApplicable::new);
    }

    public static Draft sequence(Draft... steps) {
        return compose(steps, RewriteProgram.Sequence::new);
    }

    public static Draft repeat(int maxIterations, Draft body) {
        return repeat(1, maxIterations, body);
    }

    public static Draft repeat(int minIterations, int maxIterations, Draft body) {
        Objects.requireNonNull(body, "body");
        return new Draft(id -> repeat(id, minIterations, maxIterations, body.named(id + "/0")));
    }

    public static Draft require(Draft body, String description, Predicate<RewriteCandidate> condition) {
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(condition, "condition");
        return new Draft(id -> require(id, body.named(id + "/0"), description, condition));
    }

    public static Draft prioritize(Draft body, String description, Comparator<RewriteCandidate> comparator) {
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(comparator, "comparator");
        return new Draft(id -> prioritize(id, body.named(id + "/0"), description, comparator));
    }

    public static Draft prune(Draft body, int maxCandidates, String reason) {
        Objects.requireNonNull(body, "body");
        return new Draft(id -> prune(id, body.named(id + "/0"), maxCandidates, reason));
    }

    /**
     * Immutable construction recipe for the existing typed IR, not a second interpreter.
     * Finalizing assigns deterministic IDs from the root name and child positions.
     * Reusing a draft in two positions yields different IDs, without counters or UUIDs.
     */
    public static final class Draft {
        private final Function<String, RewriteProgram> factory;

        private Draft(Function<String, RewriteProgram> factory) {
            this.factory = factory;
        }

        /** Names the root for tracing/evidence; the name never selects behavior. */
        public RewriteProgram named(String id) {
            NodeMetadata.named(id); // Validate before constructing descendants.
            return factory.apply(id);
        }
    }

    private static Draft compose(
            Draft[] values,
            BiFunction<NodeMetadata, List<RewriteProgram>, RewriteProgram> constructor
    ) {
        List<Draft> children = List.of(Objects.requireNonNull(values, "programs"));
        if (children.isEmpty()) {
            throw new IllegalArgumentException("programs must not be empty");
        }
        return new Draft(id -> constructor.apply(NodeMetadata.named(id),
            IntStream.range(0, children.size())
                .mapToObj(index -> children.get(index).named(id + "/" + index))
                .toList()));
    }

    /** Explicit stable-ID form, retained for persisted programs and import adapters. */
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

    public static RewriteProgram.BudgetedSource budgetedSource(
        String id,
        BudgetedTransformationSource source
    ) {
        return new RewriteProgram.BudgetedSource(
            NodeMetadata.named(id),
            source);
    }

    public static RewriteProgram.BudgetedSource budgetedSource(
        String id,
        String label,
        SourceLocation location,
        BudgetedTransformationSource source
    ) {
        return new RewriteProgram.BudgetedSource(
            new NodeMetadata(id, label, location),
            source);
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
            .thenComparing(RewriteCandidate::orderingKey);
    }

    /** Prefers concrete rule objects, including plugin and learned rules. */
    public static Comparator<RewriteCandidate> preferRules(RewriteRule... rules) {
        return preferRules(List.of(Objects.requireNonNull(rules, "rules")));
    }

    /**
     * Snapshots the stable identities of actual rule objects. This orders candidates;
     * it neither registers rules nor grants applicability or mathematical authority.
     */
    public static Comparator<RewriteCandidate> preferRules(List<? extends RewriteRule> rules) {
        Objects.requireNonNull(rules, "rules");
        var seen = new HashSet<String>();
        List<String> ids = rules.stream().map(rule -> {
            String id = Objects.requireNonNull(rule, "rule").id();
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("preferred rule must have a non-blank identity");
            }
            if (!seen.add(id)) {
                throw new IllegalArgumentException("duplicate preferred rule identity: " + id);
            }
            return id;
        }).toList();
        return preferRuleOrder(ids);
    }

    /**
     * Legacy wire-ID boundary. Java callers should use preferRules with actual rules;
     * text importers must resolve IDs against their registry before calling this method.
     */
    public static Comparator<RewriteCandidate> preferRuleOrder(List<String> ruleIds) {
        List<String> preferred = List.copyOf(Objects.requireNonNull(ruleIds, "ruleIds"));
        return Comparator
            .comparingInt((RewriteCandidate candidate) -> {
                int index = preferred.indexOf(candidate.lastStep().rule());
                return index < 0 ? Integer.MAX_VALUE : index;
            })
            .thenComparing(candidate -> candidate.lastStep().rule())
            .thenComparing(RewriteCandidate::outputExpression)
            .thenComparing(RewriteCandidate::orderingKey);
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
