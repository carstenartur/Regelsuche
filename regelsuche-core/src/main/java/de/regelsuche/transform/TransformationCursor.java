package de.regelsuche.transform;

import de.regelsuche.knowledge.RuleDescriptor;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** An actual suspended native traversal. A pull may finish an atomic operation beyond its allowance. */
public interface TransformationCursor extends AutoCloseable {
    String WORK_REVISION = "regelsuche.native-transformation-cursor-work/v1";
    String ORDER_REVISION = "regelsuche.native-occurrence-preorder-rule-inventory/v1";
    int DEFAULT_MATCHER_BRANCH_LIMIT = 10000;

    enum Status { READY, EXHAUSTED, CANDIDATE_LIMIT, WORK_EXHAUSTED, INVALID_INPUT, FAILED, CLOSED }
    enum Operation { OPEN, PARSE, FORMAT, CANONICAL_SIZE, OCCURRENCE_VISIT, RULE_MATCH,
        MATCHER_BRANCH, INSTANTIATE, SOURCE_HASH, ANCESTOR_COPY, FILTER, EMIT, CLOSE }
    enum AttemptOutcome { NOT_MATCHED, MATCH_INCONCLUSIVE, UNCHANGED, GROWTH_REJECTED, DUPLICATE, EMITTED, FAILED }

    /** One unit per named event/atomic call, plus the matcher's actual branches and copied ancestors. */
    record Work(Map<String, Long> operations, long primitiveRewrites) {
        public Work {
            var copy = new TreeMap<String, Long>();
            operations.forEach((operation, units) -> {
                Operation.valueOf(operation);
                if (units == null || units < 0) throw new IllegalArgumentException("negative cursor work");
                copy.put(operation, units);
            });
            if (primitiveRewrites < 0) throw new IllegalArgumentException("negative rewrite work");
            operations = Collections.unmodifiableMap(copy);
        }
        public long units(Operation operation) { return operations.getOrDefault(operation.name(), 0L); }
        public long mechanicalUnits() { return operations.values().stream().reduce(0L, Math::addExact); }
        public long totalUnits() { return Math.addExact(mechanicalUnits(), primitiveRewrites); }
        public TransformationWorkMetrics metrics() {
            return TransformationWorkMetrics.ZERO.withDelegatedMechanicalWork(mechanicalUnits())
                .withCandidateWork(new ExecutionWork(primitiveRewrites, 0, 0));
        }
    }

    enum PatternKind { PLACEHOLDER, LITERAL_NUMBER, LITERAL_VARIABLE, OPERATION, FUNCTION }
    /** Structured values avoid ambiguous display strings and preserve arbitrary literal/placeholder names. */
    record PatternDefinition(PatternKind kind, String value, List<PatternDefinition> children) {
        public PatternDefinition { children = List.copyOf(children); }
        static PatternDefinition of(PatternExpr pattern) {
            return switch (pattern) {
                case PatternExpr.Placeholder placeholder -> new PatternDefinition(PatternKind.PLACEHOLDER, placeholder.name(), List.of());
                case PatternExpr.LiteralNumber number -> new PatternDefinition(PatternKind.LITERAL_NUMBER, number.value().toString(), List.of());
                case PatternExpr.LiteralVariable variable -> new PatternDefinition(PatternKind.LITERAL_VARIABLE, variable.name(), List.of());
                case PatternExpr.Operation operation -> new PatternDefinition(PatternKind.OPERATION, operation.operator().name(),
                    List.of(of(operation.left()), of(operation.right())));
                case PatternExpr.Function function -> new PatternDefinition(PatternKind.FUNCTION, function.name(),
                    function.arguments().stream().map(PatternDefinition::of).toList());
            };
        }
    }
    /** Full immutable native rule definitions, in invocation order. */
    record RuleDefinition(String id, PatternDefinition sourcePattern, PatternDefinition targetPattern, RewriteKind kind,
            boolean mayIncreaseComplexity, int estimatedCostDelta, boolean equivalencePreservingByConstruction,
            RuleDescriptor descriptor, RecognitionProfile recognitionProfile) {
        static RuleDefinition of(PatternRewriteRule rule) {
            return new RuleDefinition(rule.id(), PatternDefinition.of(rule.source()), PatternDefinition.of(rule.target()), rule.kind(),
                rule.mayIncreaseComplexity(), rule.estimatedCostDelta(), rule.isEquivalencePreservingByConstruction(),
                rule.descriptor(), rule.recognitionProfile());
        }
    }
    record Definition(String orderRevision, List<RuleDefinition> rules, int maxAstSizeIncrease,
            int maxCandidates, int matcherBranchLimit) {
        public Definition { rules = List.copyOf(rules); }
    }
    record Attempt(List<Integer> path, int ruleIndex, String ruleId, AttemptOutcome outcome,
            int matcherBranches, String detailCode) {
        public Attempt { path = List.copyOf(path); }
    }
    record Snapshot(String workRevision, Definition definition, String source, Status status, boolean closed,
            Work work, List<Attempt> attempts, long emittedCandidates, String detailCode) {
        public Snapshot { attempts = List.copyOf(attempts); }
        public boolean complete() {
            return status == Status.EXHAUSTED && attempts.stream().noneMatch(attempt ->
                attempt.outcome() == AttemptOutcome.MATCH_INCONCLUSIVE || attempt.outcome() == AttemptOutcome.FAILED);
        }
    }

    /** Pulls at most one distinct candidate. The allowance includes mathematical and mechanical cursor work. */
    Optional<Transformation> next(long workAllowance);
    Work work();
    Snapshot snapshot();
    @Override void close();
}
