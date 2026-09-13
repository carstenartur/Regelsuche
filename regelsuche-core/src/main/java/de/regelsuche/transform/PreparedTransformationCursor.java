package de.regelsuche.transform;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.input.InputRequest;
import de.regelsuche.input.InputType;
import de.regelsuche.moves.enumerate.TreePosition;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

/** Pauses the real preorder traversal; matcher, setup and one candidate's construction remain atomic. */
final class PreparedTransformationCursor implements TransformationCursor {
    private static final class Frame {
        final Expr expression;
        final List<Integer> path;
        int ruleIndex, childIndex;
        boolean entered;
        String hash;
        Frame(Expr expression, List<Integer> path) { this.expression = expression; this.path = path; }
    }
    private final PreparedAstRewriteTransformationEngine engine;
    private final String source;
    private final Definition definition;
    private final ArrayDeque<Frame> stack = new ArrayDeque<>();
    private final EnumMap<Operation, Long> operations = new EnumMap<>(Operation.class);
    private final List<Attempt> attempts = new ArrayList<>();
    private final LinkedHashSet<Transformation> emitted = new LinkedHashSet<>();
    private Expr root;
    private String formattedSource;
    private int originalSize;
    private long primitiveRewrites;
    private boolean initialized, closed;
    private Status status = Status.READY;
    private String detail = "";

    PreparedTransformationCursor(PreparedAstRewriteTransformationEngine engine, String source, Definition definition) {
        this.engine = engine; this.source = source; this.definition = definition;
        charge(Operation.OPEN, 1);
    }

    @Override public Optional<Transformation> next(long allowance) {
        if (allowance < 0) throw new IllegalArgumentException("negative cursor allowance");
        if (closed || status != Status.READY) return Optional.empty();
        long before = work().totalUnits();
        if (!available(before, allowance)) return Optional.empty();
        if (!initialized) initialize();
        while (status == Status.READY && available(before, allowance)) {
            Frame frame = nextFrame(before, allowance);
            if (frame == null) return Optional.empty();
            var result = executeRule(frame);
            if (result.isPresent()) return result;
        }
        return Optional.empty();
    }

    private boolean available(long before, long allowance) {
        if (work().totalUnits() - before < allowance) return true;
        status = Status.WORK_EXHAUSTED;
        return false;
    }

    private void initialize() {
        initialized = true;
        charge(Operation.PARSE, 1);
        try {
            root = new ExpressionParser().parse(new InputRequest(InputType.TERM, source)).terms().getFirst();
        } catch (IllegalArgumentException failure) {
            status = Status.INVALID_INPUT; detail = failure.getMessage(); return;
        }
        charge(Operation.FORMAT, 1); formattedSource = ExpressionFormatter.format(root);
        charge(Operation.CANONICAL_SIZE, 1); originalSize = engine.canonicalAstNodeCount(root);
        stack.push(new Frame(root, List.of()));
    }

    private Frame nextFrame(long before, long allowance) {
        while (!stack.isEmpty() && available(before, allowance)) {
            var frame = stack.peek();
            if (!frame.entered) { frame.entered = true; charge(Operation.OCCURRENCE_VISIT, 1); }
            if (!available(before, allowance)) return null;
            if (frame.ruleIndex < engine.rules().size()) return frame;
            Expr child = child(frame.expression, frame.childIndex);
            if (child == null) { stack.pop(); continue; }
            var path = new ArrayList<>(frame.path); path.add(frame.childIndex++);
            stack.push(new Frame(child, List.copyOf(path)));
        }
        if (stack.isEmpty()) status = Status.EXHAUSTED;
        return null;
    }

    private static Expr child(Expr expression, int index) {
        if (expression instanceof BinaryExpr binary) {
            return switch (index) { case 0 -> binary.left(); case 1 -> binary.right(); default -> null; };
        }
        if (expression instanceof FunctionExpr function && index < function.arguments().size())
            return function.arguments().get(index);
        return null;
    }

    private Optional<Transformation> executeRule(Frame frame) {
        int index = frame.ruleIndex++;
        var rule = (PatternRewriteRule) engine.rules().get(index);
        charge(Operation.RULE_MATCH, 1);
        var match = EquivalenceAwarePatternMatcher.matchDetailed(rule.source(), frame.expression, new HashMap<>(),
            rule.recognitionProfile(), definition.matcherBranchLimit());
        charge(Operation.MATCHER_BRANCH, match.visitedBranches());
        if (!match.matched()) {
            record(frame, index, match.inconclusive() ? AttemptOutcome.MATCH_INCONCLUSIVE : AttemptOutcome.NOT_MATCHED,
                match.visitedBranches(), match.limitCode());
            return Optional.empty();
        }
        try {
            return materialize(frame, index, rule, match);
        } catch (IllegalArgumentException failure) {
            record(frame, index, AttemptOutcome.FAILED, match.visitedBranches(), failure.getMessage());
            status = Status.FAILED; detail = failure.getMessage();
            return Optional.empty();
        }
    }

    private Optional<Transformation> materialize(Frame frame, int index, PatternRewriteRule rule,
            EquivalenceAwarePatternMatcher.MatchAttempt match) {
        charge(Operation.INSTANTIATE, 1);
        Expr replacement = rule.target().instantiate(match.bindings());
        if (replacement.equals(frame.expression)) return filtered(frame, index, match, AttemptOutcome.UNCHANGED);
        primitiveRewrites = Math.addExact(primitiveRewrites, 1);
        var rebuilt = new TreePosition(frame.path, "cursor-bound-source").replaceAt(root, replacement);
        charge(Operation.ANCESTOR_COPY, rebuilt.copiedAncestors());
        Expr rewritten = rebuilt.rewrittenRoot().orElseThrow();
        charge(Operation.FORMAT, 1); String formatted = ExpressionFormatter.format(rewritten);
        charge(Operation.FILTER, 1);
        if (formatted.equals(formattedSource)) return filtered(frame, index, match, AttemptOutcome.UNCHANGED);
        charge(Operation.CANONICAL_SIZE, 1);
        if ((long) engine.canonicalAstNodeCount(rewritten) - originalSize > definition.maxAstSizeIncrease())
            return filtered(frame, index, match, AttemptOutcome.GROWTH_REJECTED);
        if (frame.hash == null) { charge(Operation.SOURCE_HASH, 1); frame.hash = engine.stableHash(frame.expression); }
        var transformation = new Transformation(rule.id(), formatted, rule.kind(), rule.mayIncreaseComplexity(),
            rule.estimatedCostDelta(), rule.isEquivalencePreservingByConstruction(), rule.id() + ":" + frame.hash,
            List.of(), rule.descriptor().packId(), rule.descriptor().license());
        if (!emitted.add(transformation)) return filtered(frame, index, match, AttemptOutcome.DUPLICATE);
        charge(Operation.EMIT, 1); record(frame, index, AttemptOutcome.EMITTED, match.visitedBranches(), "");
        if (emitted.size() >= definition.maxCandidates()) status = Status.CANDIDATE_LIMIT;
        return Optional.of(transformation);
    }

    private Optional<Transformation> filtered(Frame frame, int index,
            EquivalenceAwarePatternMatcher.MatchAttempt match, AttemptOutcome outcome) {
        record(frame, index, outcome, match.visitedBranches(), "");
        return Optional.empty();
    }

    private void record(Frame frame, int index, AttemptOutcome outcome, int branches, String code) {
        attempts.add(new Attempt(frame.path, index, engine.rules().get(index).id(), outcome, branches, code));
    }
    private void charge(Operation operation, long units) { operations.merge(operation, units, Math::addExact); }
    @Override public Work work() {
        var named = new java.util.TreeMap<String, Long>();
        operations.forEach((operation, units) -> named.put(operation.name(), units));
        return new Work(named, primitiveRewrites);
    }
    @Override public Snapshot snapshot() {
        return new Snapshot(WORK_REVISION, definition, source, status, closed, work(), attempts, emitted.size(), detail);
    }
    @Override public void close() {
        if (closed) return;
        closed = true; charge(Operation.CLOSE, 1); stack.clear();
        if (status == Status.READY) status = Status.CLOSED;
    }
}
