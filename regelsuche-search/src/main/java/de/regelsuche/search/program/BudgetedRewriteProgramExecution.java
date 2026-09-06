package de.regelsuche.search.program;

import de.regelsuche.assumption.AssumptionSignature;
import de.regelsuche.search.program.BudgetedTransformationSource.ExactTheoryTransition;
import de.regelsuche.search.program.BudgetedTransformationSourceExecutor.Execution;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable evidence from exact-theory-only program composition. This is not a
 * primitive rewrite, proof authorization or an ordinary search-frontier edge.
 * Instances are issued by the budgeted interpreter, not by public constructors.
 */
public final class BudgetedRewriteProgramExecution {
    public static final String REVISION = "regelsuche.budgeted-rewrite-program/v1";

    /** Mathematical path authority. Exploration is charged separately. */
    public record PathBudget(long primitiveRewriteUnits, long exactTheoryWorkUnits) {
        public PathBudget {
            if (primitiveRewriteUnits < 0 || exactTheoryWorkUnits < 0) {
                throw new IllegalArgumentException("path budgets must not be negative");
            }
        }

        public PathBudget afterExactTheoryWork(long used) {
            if (used < 0 || used > exactTheoryWorkUnits) {
                throw new IllegalArgumentException("path exceeds its mathematical authority");
            }
            return new PathBudget(primitiveRewriteUnits, exactTheoryWorkUnits - used);
        }
    }

    /** Explicit interpreter ceilings; not a watchdog for arbitrary source code. */
    public record ExplorationLimits(
        long maxNodeVisits, long maxPathExtensions, int maxPathSteps
    ) {
        public ExplorationLimits {
            if (maxNodeVisits < 1 || maxPathExtensions < 1 || maxPathSteps < 1) {
                throw new IllegalArgumentException("exploration limits must be positive");
            }
        }
    }

    public enum Status {
        COMPLETE_WITH_CANDIDATES, COMPLETE_WITHOUT_CANDIDATES,
        INCOMPLETE_WITH_CANDIDATES, INCOMPLETE_WITHOUT_CANDIDATES
    }

    /** Counts of declared operations, not CPU instructions or elapsed time. */
    public enum WorkKind {
        INTERPRETER_INVOCATIONS, PREFLIGHT_NODE_VISITS, SOURCE_IDENTITY_READS,
        EVALUATOR_CALLS, NODE_VISITS, LEAF_EXECUTIONS, DELEGATED_MECHANICAL_WORK,
        CANDIDATE_PROJECTIONS, PATH_EXTENSIONS, COMPOSITIONS, DEDUP_VISITS,
        DUPLICATES_DROPPED, REPEAT_ITERATIONS, REPEAT_ENDPOINTS,
        ALTERNATIVES_EVALUATED, ALTERNATIVES_SELECTED, ALTERNATIVES_SKIPPED,
        PRUNED_PATHS, BUDGET_BLOCKS, LIMIT_BLOCKS
    }

    public enum LimitKind { NODE_VISITS, PATH_EXTENSIONS, PATH_STEPS }

    /** A selected transition remains bound to its complete source execution. */
    public record Step(
        RewriteProgram.NodeMetadata node,
        Execution sourceExecution,
        ExactTheoryTransition transition
    ) {
        public Step {
            Objects.requireNonNull(node, "node");
            Objects.requireNonNull(sourceExecution, "sourceExecution");
            Objects.requireNonNull(transition, "transition");
            if (!sourceExecution.candidates().contains(transition)) {
                throw new IllegalArgumentException("transition is absent from source execution");
            }
        }
    }

    /** Full ordered lineage; equal expressions do not collapse different paths. */
    public static final class ExactTheoryPath {
        private final String sourceExpression;
        private final String transformedExpression;
        private final List<Step> steps;
        private final List<String> assumptions;
        private final long mathematicalWorkUnits;
        private final String contentHash;

        private ExactTheoryPath(String sourceExpression, List<Step> steps) {
            this.sourceExpression = sourceExpression;
            this.steps = List.copyOf(steps);
            String current = sourceExpression;
            long work = 0;
            List<String> conditions = new ArrayList<>();
            Material material = new Material("path").add(sourceExpression).add(steps.size());
            for (Step step : this.steps) {
                ExactTheoryTransition transition = step.transition();
                if (!current.equals(transition.sourceExpression())) {
                    throw new IllegalArgumentException("disconnected exact-theory path");
                }
                current = transition.transformedExpression();
                work = Math.addExact(work, transition.mathematicalWorkUnits());
                conditions.addAll(transition.assumptions());
                material.add(step.node().id()).add(step.sourceExecution().contentHash())
                    .add(transition.contentHash());
            }
            this.transformedExpression = current;
            this.mathematicalWorkUnits = work;
            this.assumptions = AssumptionSignature.ofExpressions(conditions).normalizedAssumptions();
            this.contentHash = material.hash();
        }

        static ExactTheoryPath seed(String source) {
            return new ExactTheoryPath(source, List.of());
        }

        ExactTheoryPath append(Step step) {
            List<Step> joined = new ArrayList<>(steps);
            joined.add(step);
            return new ExactTheoryPath(sourceExpression, joined);
        }

        public String sourceExpression() { return sourceExpression; }
        public String transformedExpression() { return transformedExpression; }
        public List<Step> steps() { return steps; }
        public List<String> assumptions() { return assumptions; }
        public long mathematicalWorkUnits() { return mathematicalWorkUnits; }
        public long primitiveRewriteSteps() { return 0; }
        public List<String> primitiveRuleIds() { return List.of(); }
        public long exactTheorySteps() { return steps.size(); }
        public String contentHash() { return contentHash; }
    }

    /** Retains complete, no-match and budget-inconclusive source executions. */
    public record SourceCall(
        RewriteProgram.NodeMetadata node, String prefixHash,
        long availablePrimitiveRewriteUnits, Execution execution
    ) {
        public SourceCall {
            Objects.requireNonNull(node, "node");
            Objects.requireNonNull(prefixHash, "prefixHash");
            Objects.requireNonNull(execution, "execution");
            if (availablePrimitiveRewriteUnits < 0) {
                throw new IllegalArgumentException("negative primitive allowance");
            }
        }
    }

    public record BudgetBlock(SourceCall call) {
        public BudgetBlock {
            Objects.requireNonNull(call, "call");
            if (call.execution().complete()) {
                throw new IllegalArgumentException("complete execution is not a budget block");
            }
        }
        public String nodeId() { return call.node().id(); }
        public String prefixHash() { return call.prefixHash(); }
        public String inputExpression() { return call.execution().inputExpression(); }
        public long availableWorkUnits() {
            return call.execution().availableMathematicalWorkUnits();
        }
        public long requiredWorkUnits() {
            return call.execution().sourceResult().minimumRequiredMathematicalWorkUnits();
        }
        public String sourceExecutionHash() { return call.execution().contentHash(); }
    }

    public record LimitBlock(
        String nodeId, String prefixHash, String inputExpression,
        PathBudget remaining, LimitKind reason
    ) {
        public LimitBlock {
            Objects.requireNonNull(nodeId, "nodeId");
            Objects.requireNonNull(prefixHash, "prefixHash");
            Objects.requireNonNull(inputExpression, "inputExpression");
            Objects.requireNonNull(remaining, "remaining");
            Objects.requireNonNull(reason, "reason");
        }
    }

    public record Pruning(String nodeId, String prefixHash, String reason,
                          List<ExactTheoryPath> removedPaths) {
        public Pruning {
            Objects.requireNonNull(nodeId, "nodeId");
            Objects.requireNonNull(prefixHash, "prefixHash");
            Objects.requireNonNull(reason, "reason");
            removedPaths = List.copyOf(removedPaths);
            if (removedPaths.isEmpty()) {
                throw new IllegalArgumentException("pruning must remove at least one path");
            }
        }
    }

    private final String programEncoding;
    private final String programHash;
    private final String inputExpression;
    private final PathBudget availableBudget;
    private final ExplorationLimits limits;
    private final Status status;
    private final List<ExactTheoryPath> candidates;
    private final List<SourceCall> sourceExecutions;
    private final List<BudgetBlock> budgetBlocks;
    private final List<LimitBlock> limitBlocks;
    private final List<Pruning> pruning;
    private final Map<WorkKind, Long> work;
    private final long totalMechanicalWorkUnits;
    private final String contentHash;

    BudgetedRewriteProgramExecution(
        String programEncoding, String input, PathBudget budget, ExplorationLimits limits,
        boolean complete, List<ExactTheoryPath> candidates, List<SourceCall> calls,
        List<LimitBlock> limitBlocks, List<Pruning> pruning, Map<WorkKind, Long> work
    ) {
        this.programEncoding = programEncoding;
        this.programHash = new Material("program").add(programEncoding).hash();
        this.inputExpression = input;
        this.availableBudget = budget;
        this.limits = limits;
        this.candidates = List.copyOf(candidates);
        this.sourceExecutions = List.copyOf(calls);
        this.budgetBlocks = calls.stream().filter(call -> !call.execution().complete())
            .map(BudgetBlock::new).toList();
        this.limitBlocks = List.copyOf(limitBlocks);
        this.pruning = List.copyOf(pruning);
        if (complete && (!budgetBlocks.isEmpty() || !limitBlocks.isEmpty() || !pruning.isEmpty())) {
            throw new IllegalArgumentException("incomplete evidence cannot authorize completeness");
        }
        for (ExactTheoryPath candidate : candidates) {
            if (!input.equals(candidate.sourceExpression()) || candidate.steps().isEmpty()) {
                throw new IllegalArgumentException("invalid root candidate");
            }
            budget.afterExactTheoryWork(candidate.mathematicalWorkUnits());
        }
        this.status = complete
            ? (candidates.isEmpty() ? Status.COMPLETE_WITHOUT_CANDIDATES : Status.COMPLETE_WITH_CANDIDATES)
            : (candidates.isEmpty() ? Status.INCOMPLETE_WITHOUT_CANDIDATES : Status.INCOMPLETE_WITH_CANDIDATES);
        EnumMap<WorkKind, Long> counts = new EnumMap<>(WorkKind.class);
        long total = 0;
        for (WorkKind kind : WorkKind.values()) {
            long count = work.getOrDefault(kind, 0L);
            if (count < 0) { throw new IllegalArgumentException("negative work counter"); }
            counts.put(kind, count);
            total = Math.addExact(total, count);
        }
        this.work = Collections.unmodifiableMap(counts);
        this.totalMechanicalWorkUnits = total;
        this.contentHash = executionMaterial().hash();
    }

    public String programEncoding() { return programEncoding; }
    public String programHash() { return programHash; }
    public String inputExpression() { return inputExpression; }
    public PathBudget availableBudget() { return availableBudget; }
    public ExplorationLimits limits() { return limits; }
    public Status status() { return status; }
    public boolean complete() {
        return status == Status.COMPLETE_WITH_CANDIDATES || status == Status.COMPLETE_WITHOUT_CANDIDATES;
    }
    public List<ExactTheoryPath> candidates() { return candidates; }
    public List<SourceCall> sourceExecutions() { return sourceExecutions; }
    public List<BudgetBlock> budgetBlocks() { return budgetBlocks; }
    public List<LimitBlock> limitBlocks() { return limitBlocks; }
    public List<Pruning> pruning() { return pruning; }
    public Map<WorkKind, Long> work() { return work; }
    public long totalMechanicalWorkUnits() { return totalMechanicalWorkUnits; }
    public String contentHash() { return contentHash; }

    private Material executionMaterial() {
        Material material = new Material("execution").add(programHash).add(inputExpression)
            .add(availableBudget.primitiveRewriteUnits()).add(availableBudget.exactTheoryWorkUnits())
            .add(limits.maxNodeVisits()).add(limits.maxPathExtensions()).add(limits.maxPathSteps())
            .add(status.name()).add(candidates.size());
        candidates.forEach(path -> material.add(path.contentHash()));
        material.add(sourceExecutions.size());
        sourceExecutions.forEach(call -> material.add(call.node().id()).add(call.prefixHash())
            .add(call.availablePrimitiveRewriteUnits()).add(call.execution().contentHash()));
        material.add(limitBlocks.size());
        limitBlocks.forEach(block -> material.add(block.nodeId()).add(block.prefixHash())
            .add(block.inputExpression()).add(block.remaining().primitiveRewriteUnits())
            .add(block.remaining().exactTheoryWorkUnits()).add(block.reason().name()));
        material.add(pruning.size());
        pruning.forEach(event -> {
            material.add(event.nodeId()).add(event.prefixHash()).add(event.reason())
                .add(event.removedPaths().size());
            event.removedPaths().forEach(path -> material.add(path.contentHash()));
        });
        for (WorkKind kind : WorkKind.values()) { material.add(kind.name()).add(work.get(kind)); }
        return material.add(totalMechanicalWorkUnits);
    }

    /** Counted, UTF-8 length-prefixed fields; no display toString is an identity. */
    static final class Material {
        private final StringBuilder text = new StringBuilder();
        Material(String kind) { add(REVISION); add(kind); }
        Material add(long value) { return add(Long.toString(value)); }
        Material add(String value) {
            Objects.requireNonNull(value, "canonical field");
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (Character.isHighSurrogate(c)) {
                    if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) {
                        throw new IllegalArgumentException("malformed Unicode in canonical field");
                    }
                } else if (Character.isLowSurrogate(c)) {
                    throw new IllegalArgumentException("malformed Unicode in canonical field");
                }
            }
            text.append(value.getBytes(StandardCharsets.UTF_8).length).append(':').append(value);
            return this;
        }
        String encoding() { return text.toString(); }
        String hash() {
            try {
                return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(encoding().getBytes(StandardCharsets.UTF_8)));
            } catch (NoSuchAlgorithmException exception) {
                throw new IllegalStateException("SHA-256 is unavailable", exception);
            }
        }
    }
}
