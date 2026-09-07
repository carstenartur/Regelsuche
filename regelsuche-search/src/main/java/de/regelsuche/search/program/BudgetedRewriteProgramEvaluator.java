package de.regelsuche.search.program;

import static de.regelsuche.search.program.BudgetedRewriteProgramExecution.WorkKind.*;

import de.regelsuche.search.program.BudgetedRewriteProgramExecution.ExactTheoryPath;
import de.regelsuche.search.program.BudgetedRewriteProgramExecution.ExplorationLimits;
import de.regelsuche.search.program.BudgetedRewriteProgramExecution.LimitBlock;
import de.regelsuche.search.program.BudgetedRewriteProgramExecution.LimitKind;
import de.regelsuche.search.program.BudgetedRewriteProgramExecution.Material;
import de.regelsuche.search.program.BudgetedRewriteProgramExecution.PathBudget;
import de.regelsuche.search.program.BudgetedRewriteProgramExecution.Pruning;
import de.regelsuche.search.program.BudgetedRewriteProgramExecution.SourceCall;
import de.regelsuche.search.program.BudgetedRewriteProgramExecution.Step;
import de.regelsuche.search.program.BudgetedRewriteProgramExecution.WorkKind;
import de.regelsuche.search.program.BudgetedTransformationSource.SourceIdentity;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Package-private typed lane of RewriteProgramInterpreter; no second search engine. */
final class BudgetedRewriteProgramEvaluator {
    private static final int MAX_PROGRAM_NODES = 4096;
    private static final int MAX_PROGRAM_DEPTH = 128;

    private final BudgetedTransformationSourceExecutor sourceExecutor;

    BudgetedRewriteProgramEvaluator(BudgetedTransformationSourceExecutor sourceExecutor) {
        this.sourceExecutor = Objects.requireNonNull(sourceExecutor, "sourceExecutor");
    }

    BudgetedRewriteProgramExecution execute(
        RewriteProgram program, String expression, PathBudget budget, ExplorationLimits limits
    ) {
        Objects.requireNonNull(program, "program");
        Objects.requireNonNull(budget, "budget");
        Objects.requireNonNull(limits, "limits");
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("expression must not be blank");
        }
        String input = expression.trim().replaceAll("\\s+", " ");
        if (input.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("expression contains a control character");
        }
        // Validate Unicode before any source callback, including identity().
        new Material("input").add(input);
        Context context = new Context(budget, limits);
        context.add(INTERPRETER_INVOCATIONS, 1);
        List<RewriteProgram> nodes = new ArrayList<>();
        collect(program, 1, new HashSet<>(), nodes, context);
        context.freezeSources(nodes);
        Material programMaterial = new Material("topology")
            .add(BudgetedTransformationSource.PROTOCOL_REVISION)
            .add(BudgetedTransformationSourceExecutor.EXECUTOR_REVISION)
            .add("exact-theory-only;program-order/source-hash;full-path-dedup")
            .add(nodes.size());
        for (RewriteProgram node : nodes) { appendNode(programMaterial, node, context); }
        // Identity callbacks cannot retroactively change an earlier source.
        context.verifySources();
        Evaluation result = evaluate(program, ExactTheoryPath.seed(input), context);
        context.verifySources();
        return new BudgetedRewriteProgramExecution(
            programMaterial.encoding(), input, budget, limits, result.complete(),
            result.paths(), context.calls, context.limitBlocks, context.pruning, context.work);
    }

    /** A purely structural first pass rejects unsupported nodes before callbacks. */
    private static void collect(
        RewriteProgram node, int depth, Set<String> ids, List<RewriteProgram> nodes, Context context
    ) {
        if (depth > MAX_PROGRAM_DEPTH || nodes.size() >= MAX_PROGRAM_NODES) {
            throw new IllegalArgumentException("budgeted program exceeds structural preflight limits");
        }
        if (!ids.add(node.id())) { throw new IllegalArgumentException("duplicate program node id: " + node.id()); }
        context.add(PREFLIGHT_NODE_VISITS, 1);
        nodes.add(node);
        List<RewriteProgram> children = children(node);
        // Reject malformed metadata before the identity callback phase as well.
        appendMetadata(new Material("metadata-preflight"), node.metadata());
        for (RewriteProgram child : children) { collect(child, depth + 1, ids, nodes, context); }
    }

    private static List<RewriteProgram> children(RewriteProgram node) {
        return switch (node) {
            case RewriteProgram.BudgetedSource ignored -> List.of();
            case RewriteProgram.Choice choice -> choice.alternatives();
            case RewriteProgram.FirstApplicable first -> first.alternatives();
            case RewriteProgram.Sequence sequence -> sequence.steps();
            case RewriteProgram.Repeat repeat -> List.of(repeat.body());
            case RewriteProgram.Prune prune -> List.of(prune.body());
            default -> throw new IllegalArgumentException(
                "budgeted composition rejects ordinary Source, Require and Prioritize: " + node.id());
        };
    }

    private static void appendNode(Material material, RewriteProgram node, Context context) {
        material.add(node.getClass().getSimpleName());
        appendMetadata(material, node.metadata());
        List<RewriteProgram> children = children(node);
        material.add(children.size());
        children.forEach(child -> material.add(child.id()));
        switch (node) {
            case RewriteProgram.BudgetedSource source -> {
                SourceIdentity identity = context.identities.get(source.id());
                material.add(identity.sourceId()).add(identity.revisionHash()).add(identity.authorityHash());
            }
            case RewriteProgram.Repeat repeat -> material.add(repeat.minIterations()).add(repeat.maxIterations());
            case RewriteProgram.Prune prune -> material.add(prune.maxCandidates()).add(prune.reason());
            default -> { /* Child order and complete metadata are already bound. */ }
        }
    }

    private static void appendMetadata(Material material, RewriteProgram.NodeMetadata metadata) {
        material.add(metadata.id()).add(metadata.label()).add(metadata.sourceLocation().sourceName())
            .add(metadata.sourceLocation().line()).add(metadata.sourceLocation().column());
    }

    private Evaluation evaluate(RewriteProgram node, ExactTheoryPath prefix, Context context) {
        context.add(EVALUATOR_CALLS, 1);
        if (!context.enter(node, prefix)) { return Evaluation.incomplete(); }
        return switch (node) {
            case RewriteProgram.BudgetedSource source -> leaf(source, prefix, context);
            case RewriteProgram.Choice choice -> alternatives(choice.alternatives(), prefix, context, false);
            case RewriteProgram.FirstApplicable first -> alternatives(first.alternatives(), prefix, context, true);
            case RewriteProgram.Sequence sequence -> sequence(sequence, prefix, context);
            case RewriteProgram.Repeat repeat -> repeat(repeat, prefix, context);
            case RewriteProgram.Prune prune -> prune(prune, prefix, context);
            default -> throw new IllegalStateException("unsupported node passed preflight");
        };
    }

    private Evaluation leaf(RewriteProgram.BudgetedSource source, ExactTheoryPath prefix, Context context) {
        if (prefix.steps().size() >= context.limits.maxPathSteps()) {
            context.limit(source, prefix, LimitKind.PATH_STEPS);
            return Evaluation.incomplete();
        }
        context.verifySource(source);
        PathBudget available = context.remaining(prefix);
        context.add(LEAF_EXECUTIONS, 1);
        BudgetedTransformationSourceExecutor.Execution execution = sourceExecutor.execute(
            source.source(), prefix.transformedExpression(), available.exactTheoryWorkUnits());
        if (!context.identities.get(source.id()).equals(execution.sourceIdentity())) {
            throw new IllegalArgumentException("source execution differs from frozen program identity");
        }
        context.verifySource(source);
        context.add(DELEGATED_MECHANICAL_WORK, execution.mechanicalWork().totalMechanicalWorkUnits());
        context.calls.add(new SourceCall(source.metadata(), prefix.contentHash(),
            available.primitiveRewriteUnits(), execution));
        if (!execution.complete()) { context.add(BUDGET_BLOCKS, 1); }
        List<ExactTheoryPath> paths = new ArrayList<>();
        boolean complete = execution.complete();
        for (BudgetedTransformationSource.ExactTheoryTransition transition : execution.candidates()) {
            context.add(CANDIDATE_PROJECTIONS, 1);
            if (context.count(PATH_EXTENSIONS) >= context.limits.maxPathExtensions()) {
                context.limit(source, prefix, LimitKind.PATH_EXTENSIONS);
                context.halted = true;
                complete = false;
                break;
            }
            context.add(PATH_EXTENSIONS, 1);
            ExactTheoryPath path = prefix.append(new Step(source.metadata(), execution, transition));
            context.remaining(path); // Validate before admission, not after returning a candidate.
            paths.add(path);
        }
        return new Evaluation(paths, complete);
    }

    private Evaluation alternatives(
        List<RewriteProgram> alternatives, ExactTheoryPath prefix, Context context, boolean firstOnly
    ) {
        List<ExactTheoryPath> paths = new ArrayList<>();
        boolean complete = true;
        for (int index = 0; index < alternatives.size(); index++) {
            context.add(ALTERNATIVES_EVALUATED, 1);
            Evaluation result = evaluate(alternatives.get(index), prefix, context);
            complete &= result.complete();
            paths.addAll(result.paths());
            if (firstOnly && (!result.paths().isEmpty() || !result.complete())) {
                if (!result.paths().isEmpty()) { context.add(ALTERNATIVES_SELECTED, 1); }
                context.add(ALTERNATIVES_SKIPPED, alternatives.size() - index - 1L);
                break;
            }
            if (context.halted) {
                context.add(ALTERNATIVES_SKIPPED, alternatives.size() - index - 1L);
                complete = false;
                break;
            }
        }
        return new Evaluation(distinct(paths, context), complete);
    }

    private Evaluation sequence(RewriteProgram.Sequence sequence, ExactTheoryPath prefix, Context context) {
        List<ExactTheoryPath> current = List.of(prefix);
        boolean complete = true;
        for (int index = 0; index < sequence.steps().size() && !current.isEmpty(); index++) {
            List<ExactTheoryPath> next = new ArrayList<>();
            for (ExactTheoryPath path : current) {
                Evaluation result = evaluate(sequence.steps().get(index), path, context);
                complete &= result.complete();
                if (index > 0) { context.add(COMPOSITIONS, result.paths().size()); }
                next.addAll(result.paths());
                if (context.halted) { complete = false; break; }
            }
            current = distinct(next, context);
        }
        return new Evaluation(current, complete);
    }

    private Evaluation repeat(RewriteProgram.Repeat repeat, ExactTheoryPath prefix, Context context) {
        List<ExactTheoryPath> frontier = List.of(prefix);
        List<ExactTheoryPath> endpoints = new ArrayList<>();
        boolean complete = true;
        // Zero-based form avoids overflow for maxIterations == Integer.MAX_VALUE.
        for (int index = 0; index < repeat.maxIterations() && !frontier.isEmpty(); index++) {
            context.add(REPEAT_ITERATIONS, 1);
            List<ExactTheoryPath> next = new ArrayList<>();
            for (ExactTheoryPath path : frontier) {
                Evaluation result = evaluate(repeat.body(), path, context);
                complete &= result.complete();
                if (index > 0) { context.add(COMPOSITIONS, result.paths().size()); }
                next.addAll(result.paths());
                if (context.halted) { complete = false; break; }
            }
            frontier = distinct(next, context);
            if (index >= repeat.minIterations() - 1) {
                context.add(REPEAT_ENDPOINTS, frontier.size());
                endpoints.addAll(frontier);
            }
            if (context.halted) { complete = false; break; }
        }
        return new Evaluation(distinct(endpoints, context), complete);
    }

    private Evaluation prune(RewriteProgram.Prune prune, ExactTheoryPath prefix, Context context) {
        Evaluation result = evaluate(prune.body(), prefix, context);
        if (result.paths().size() <= prune.maxCandidates()) { return result; }
        List<ExactTheoryPath> removed = result.paths().subList(prune.maxCandidates(), result.paths().size());
        context.add(PRUNED_PATHS, removed.size());
        context.pruning.add(new Pruning(prune.id(), prefix.contentHash(), prune.reason(), removed));
        return new Evaluation(result.paths().subList(0, prune.maxCandidates()), false);
    }

    private static List<ExactTheoryPath> distinct(List<ExactTheoryPath> paths, Context context) {
        Map<String, ExactTheoryPath> retained = new LinkedHashMap<>();
        for (ExactTheoryPath path : paths) {
            context.add(DEDUP_VISITS, 1);
            if (retained.putIfAbsent(path.contentHash(), path) != null) { context.add(DUPLICATES_DROPPED, 1); }
        }
        return List.copyOf(retained.values());
    }

    private record Evaluation(List<ExactTheoryPath> paths, boolean complete) {
        private Evaluation { paths = List.copyOf(paths); }
        static Evaluation incomplete() { return new Evaluation(List.of(), false); }
    }

    private static final class Context {
        private final PathBudget budget;
        private final ExplorationLimits limits;
        private final Map<WorkKind, Long> work = new EnumMap<>(WorkKind.class);
        private final Map<String, SourceIdentity> identities = new LinkedHashMap<>();
        private final List<RewriteProgram.BudgetedSource> sources = new ArrayList<>();
        private final List<SourceCall> calls = new ArrayList<>();
        private final List<LimitBlock> limitBlocks = new ArrayList<>();
        private final List<Pruning> pruning = new ArrayList<>();
        private boolean halted;

        private Context(PathBudget budget, ExplorationLimits limits) { this.budget = budget; this.limits = limits; }
        private long count(WorkKind kind) { return work.getOrDefault(kind, 0L); }
        private void add(WorkKind kind, long value) {
            if (value < 0) { throw new IllegalArgumentException("negative mechanical work"); }
            work.put(kind, Math.addExact(count(kind), value));
        }
        private PathBudget remaining(ExactTheoryPath path) {
            return budget.afterExactTheoryWork(path.mathematicalWorkUnits());
        }
        private boolean enter(RewriteProgram node, ExactTheoryPath prefix) {
            if (halted) { return false; }
            if (count(NODE_VISITS) >= limits.maxNodeVisits()) {
                limit(node, prefix, LimitKind.NODE_VISITS);
                halted = true;
                return false;
            }
            add(NODE_VISITS, 1);
            return true;
        }
        private void limit(RewriteProgram node, ExactTheoryPath prefix, LimitKind kind) {
            add(LIMIT_BLOCKS, 1);
            limitBlocks.add(new LimitBlock(node.id(), prefix.contentHash(),
                prefix.transformedExpression(), remaining(prefix), kind));
        }
        private void freezeSources(List<RewriteProgram> nodes) {
            for (RewriteProgram node : nodes) {
                if (node instanceof RewriteProgram.BudgetedSource source) {
                    sources.add(source);
                    identities.put(source.id(), readIdentity(source));
                }
            }
        }
        private SourceIdentity readIdentity(RewriteProgram.BudgetedSource source) {
            add(SOURCE_IDENTITY_READS, 1);
            return Objects.requireNonNull(source.source().identity(), "source identity");
        }
        private void verifySource(RewriteProgram.BudgetedSource source) {
            if (!identities.get(source.id()).equals(readIdentity(source))) {
                throw new IllegalArgumentException("source identity differs from frozen program: " + source.id());
            }
        }
        private void verifySources() { sources.forEach(this::verifySource); }
    }
}
