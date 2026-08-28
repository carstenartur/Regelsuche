package de.regelsuche.polynomial;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.moves.apply.TreeExpressionEditor;
import de.regelsuche.moves.enumerate.TreePosition;
import de.regelsuche.parse.ExactParsedSubtermProjector;
import de.regelsuche.parse.ExactParsedTerm;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.scalar.ExactRational;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Applies one verifier-authorized exact factorization at a selected AST
 * occurrence and replays the replacement through the shared tree editor.
 *
 * <p>Projection, factorization, rendering, exact reconstruction, structural
 * replacement and replay are governed by one total-work authority. Before the
 * factorization starts, the pipeline subtracts a conservative replacement and
 * replay reserve from the remaining authority, so no later stage can silently
 * reset the budget.</p>
 */
public final class ExactNestedFactorizationTransformationPipeline {
    public static final String PIPELINE_ID =
        "regelsuche.exact-nested-factorization-transformation/v1";
    public static final int MAX_PATH_DEPTH = 4_096;
    public static final int MAX_ROOT_NODES = 65_536;
    public static final int MAX_REPLACEMENT_NODES = 65_536;
    public static final long MAX_TOTAL_WORK_UNITS = 100_000_000L;
    private static final long STRUCTURAL_HASH_UNITS_PER_NODE = 128L;

    private final ExactParsedSubtermProjector projector;
    private final ExactParsedUnivariatePolynomialView extractionView;
    private final ExactFactorizationExpressionRenderer renderer;
    private final ExpressionParser parser;
    private final ExactParsedUnivariatePolynomialView reconstructionView;
    private final Policy policy;

    public ExactNestedFactorizationTransformationPipeline() {
        this(
            new ExactParsedSubtermProjector(),
            new ExactParsedUnivariatePolynomialView(),
            new ExactFactorizationExpressionRenderer(),
            new ExpressionParser(),
            new ExactParsedUnivariatePolynomialView(),
            Policy.boundedDefaults());
    }

    public ExactNestedFactorizationTransformationPipeline(
        ExactParsedSubtermProjector projector,
        ExactParsedUnivariatePolynomialView extractionView,
        ExactFactorizationExpressionRenderer renderer,
        ExpressionParser parser,
        ExactParsedUnivariatePolynomialView reconstructionView,
        Policy policy
    ) {
        this.projector = Objects.requireNonNull(projector, "projector");
        this.extractionView = Objects.requireNonNull(
            extractionView,
            "extractionView");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.parser = Objects.requireNonNull(parser, "parser");
        this.reconstructionView = Objects.requireNonNull(
            reconstructionView,
            "reconstructionView");
        this.policy = Objects.requireNonNull(policy, "policy");
        if (policy.maxPathDepth() > projector.policy().maxPathDepth()) {
            throw new IllegalArgumentException(
                "nested path policy exceeds projector path authority");
        }
    }

    public Policy policy() {
        return policy;
    }

    public ExactParsedSubtermProjector projector() {
        return projector;
    }

    /** Uses implicit selection only when the verifier issued one candidate. */
    public Result transform(
        ExactParsedTerm root,
        TreePosition position,
        FactorizationEngine<ExactRational> engine
    ) {
        return transformInternal(
            root,
            position,
            engine,
            OptionalInt.empty());
    }

    /** Uses one explicitly selected verifier candidate. */
    public Result transform(
        ExactParsedTerm root,
        TreePosition position,
        FactorizationEngine<ExactRational> engine,
        int candidateIndex
    ) {
        if (candidateIndex < 0) {
            throw new IllegalArgumentException(
                "candidate index must not be negative");
        }
        return transformInternal(
            root,
            position,
            engine,
            OptionalInt.of(candidateIndex));
    }

    private Result transformInternal(
        ExactParsedTerm root,
        TreePosition position,
        FactorizationEngine<ExactRational> engine,
        OptionalInt candidateIndex
    ) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(engine, "engine");
        if (position.text() == null || position.text().isBlank()) {
            throw new IllegalArgumentException(
                "tree position text must not be blank");
        }
        if (position.path().size() > policy.maxPathDepth()) {
            return failure(
                Status.BUDGET_INCONCLUSIVE,
                "MAX_NESTED_PATH_DEPTH_EXCEEDED",
                position,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                PolynomialWorkLedger.empty());
        }

        Work preflight = new Work(policy.maxTotalWorkUnits());
        try {
            countNodes(
                root.expression(),
                policy.maxRootNodes(),
                preflight,
                "nested.root-preflight-node-visits");
        } catch (RepresentationLimitReached exception) {
            return failure(
                Status.BUDGET_INCONCLUSIVE,
                exception.getMessage(),
                position,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                preflight.ledger());
        } catch (StructuralInvariantFailure exception) {
            return failure(
                Status.TECHNICAL_FAILURE,
                exception.getMessage(),
                position,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                preflight.ledger());
        }
        PolynomialWorkLedger priorWork = preflight.ledger();
        long replacementReserve = replacementReplayWorkCeiling();
        long extractionCeiling = extractionWorkCeiling();
        if (!canReserveBeforeProjection(
                priorWork,
                replacementReserve,
                extractionCeiling)) {
            return failure(
                Status.BUDGET_INCONCLUSIVE,
                "INSUFFICIENT_AUTHORITY_FOR_PROJECTION_AND_REPLAY",
                position,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                priorWork);
        }

        ExactParsedSubtermProjector.Result projection = projector.project(
            root,
            position.path(),
            position.text());
        priorWork = merge(
            priorWork,
            new PolynomialWorkLedger(projection.work().stages()));
        if (!projection.successful()) {
            return failure(
                mapProjectionStatus(projection.status()),
                projection.detailCode(),
                position,
                Optional.of(projection),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                priorWork);
        }

        long factorizationAuthority = remainingAfterReservation(
            priorWork,
            replacementReserve);
        if (factorizationAuthority < extractionCeiling) {
            return failure(
                Status.BUDGET_INCONCLUSIVE,
                "INSUFFICIENT_REMAINING_FACTORIZATION_AUTHORITY",
                position,
                Optional.of(projection),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                priorWork);
        }

        ExactParsedFactorizationPipeline factorizationPipeline =
            new ExactParsedFactorizationPipeline(
                extractionView,
                factorizationPolicy(factorizationAuthority));
        ExactParsedTerm projected = projection.projected().orElseThrow();
        ExactParsedFactorizationPipeline.Result factorization =
            factorizationPipeline.factor(projected, engine);
        ExactFactorizationTransformationPipeline transformationPipeline =
            new ExactFactorizationTransformationPipeline(
                renderer,
                parser,
                reconstructionView);
        ExactFactorizationTransformationPipeline.Result transformation =
            candidateIndex.isPresent()
                ? transformationPipeline.transformRoot(
                    projected,
                    factorization,
                    candidateIndex.getAsInt())
                : transformationPipeline.transformRoot(
                    projected,
                    factorization);
        PolynomialWorkLedger throughTransformation = merge(
            priorWork,
            transformation.totalWork());
        if (!transformation.transformed()) {
            return failure(
                mapTransformationStatus(transformation.status()),
                transformation.detailCode(),
                position,
                Optional.of(projection),
                Optional.of(factorization),
                Optional.of(transformation),
                Optional.empty(),
                Optional.empty(),
                throughTransformation);
        }

        Work replacementWork = new Work(replacementReserve);
        try {
            Expr selectedAtApplication = TreeExpressionEditor.subtreeAt(
                root.expression(),
                position.path()).orElse(null);
            replacementWork.consume(
                "nested.application-path-navigation",
                position.path().size());
            if (selectedAtApplication == null) {
                return failure(
                    Status.POSITION_NOT_PRESENT,
                    "SELECTED_PATH_DISAPPEARED_BEFORE_APPLICATION",
                    position,
                    Optional.of(projection),
                    Optional.of(factorization),
                    Optional.of(transformation),
                    Optional.empty(),
                    Optional.empty(),
                    merge(throughTransformation, replacementWork.ledger()));
            }
            if (selectedAtApplication != projected.expression()) {
                return failure(
                    Status.POSITION_STALE,
                    "SELECTED_OCCURRENCE_IDENTITY_CHANGED_BEFORE_APPLICATION",
                    position,
                    Optional.of(projection),
                    Optional.of(factorization),
                    Optional.of(transformation),
                    Optional.empty(),
                    Optional.empty(),
                    merge(throughTransformation, replacementWork.ledger()));
            }
            replacementWork.consume(
                "nested.application-staleness-format-node-visits",
                projection.work().units(
                    "projection.subtree-node-visits"));
            String applicationText = ExpressionFormatter.format(
                selectedAtApplication);
            replacementWork.consume(
                "nested.application-staleness-text-code-units",
                Math.addExact(
                    (long) position.text().length(),
                    applicationText.length()));
            if (!position.text().equals(applicationText)) {
                return failure(
                    Status.POSITION_STALE,
                    "SELECTED_POSITION_TEXT_CHANGED_BEFORE_APPLICATION",
                    position,
                    Optional.of(projection),
                    Optional.of(factorization),
                    Optional.of(transformation),
                    Optional.empty(),
                    Optional.empty(),
                    merge(throughTransformation, replacementWork.ledger()));
            }

            Expr replacement = transformation.reparsed()
                .orElseThrow().expression();
            countNodes(
                replacement,
                policy.maxReplacementNodes(),
                replacementWork,
                "nested.replacement-node-visits");
            TreeExpressionEditor.ReplacementResult applied =
                TreeExpressionEditor.replaceAt(
                    root.expression(),
                    position.path(),
                    replacement);
            replacementWork.consume(
                "nested.replacement-path-navigation",
                position.path().size());
            replacementWork.consume(
                "nested.replacement-ancestor-copies",
                applied.copiedAncestors());
            if (!applied.success()
                    || applied.selectedSubtree().orElseThrow()
                        != selectedAtApplication) {
                throw invariant(
                    "STRUCTURAL_REPLACEMENT_DID_NOT_TARGET_AUTHORIZED_OCCURRENCE");
            }
            Expr rewrittenRoot = applied.rewrittenRoot().orElseThrow();
            if (!surroundingReferencesPreserved(
                    root.expression(),
                    rewrittenRoot,
                    position.path(),
                    replacement,
                    replacementWork)) {
                throw invariant(
                    "STRUCTURAL_REPLACEMENT_CHANGED_UNAUTHORIZED_SURROUNDING_AST");
            }

            Expr replayedOccurrence = TreeExpressionEditor.subtreeAt(
                rewrittenRoot,
                position.path()).orElse(null);
            replacementWork.consume(
                "nested.rewritten-path-replay",
                position.path().size());
            if (replayedOccurrence != replacement) {
                throw invariant(
                    "REWRITTEN_PATH_DOES_NOT_RESOLVE_TO_REPLACEMENT");
            }

            TreeExpressionEditor.ReplacementResult replay =
                TreeExpressionEditor.replaceAt(
                    root.expression(),
                    position.path(),
                    replacement);
            replacementWork.consume(
                "nested.replacement-replay-path-navigation",
                position.path().size());
            replacementWork.consume(
                "nested.replacement-replay-ancestor-copies",
                replay.copiedAncestors());
            if (!replay.success()) {
                throw invariant("STRUCTURAL_REPLACEMENT_IS_NOT_REPLAYABLE");
            }
            int rewrittenNodeLimit = Math.addExact(
                policy.maxRootNodes(),
                policy.maxReplacementNodes());
            String rewrittenHash = structuralHash(
                rewrittenRoot,
                rewrittenNodeLimit,
                replacementWork,
                "nested.rewritten-structural-hash");
            String replayHash = structuralHash(
                replay.rewrittenRoot().orElseThrow(),
                rewrittenNodeLimit,
                replacementWork,
                "nested.replay-structural-hash");
            if (!rewrittenHash.equals(replayHash)) {
                throw invariant(
                    "STRUCTURAL_REPLACEMENT_REPLAY_HASH_MISMATCH");
            }

            PolynomialWorkLedger totalWork = merge(
                throughTransformation,
                replacementWork.ledger());
            if (!totalWork.within(policy.maxTotalWorkUnits())) {
                throw invariant(
                    "NESTED_PIPELINE_EXCEEDED_TOTAL_WORK_AUTHORITY");
            }
            return success(
                position,
                projection,
                factorization,
                transformation,
                rewrittenRoot,
                rewrittenHash,
                totalWork);
        } catch (WorkLimitReached exception) {
            return failure(
                Status.BUDGET_INCONCLUSIVE,
                exception.getMessage(),
                position,
                Optional.of(projection),
                Optional.of(factorization),
                Optional.of(transformation),
                Optional.empty(),
                Optional.empty(),
                merge(throughTransformation, replacementWork.ledger()));
        } catch (RepresentationLimitReached exception) {
            return failure(
                Status.BUDGET_INCONCLUSIVE,
                exception.getMessage(),
                position,
                Optional.of(projection),
                Optional.of(factorization),
                Optional.of(transformation),
                Optional.empty(),
                Optional.empty(),
                merge(throughTransformation, replacementWork.ledger()));
        } catch (StructuralInvariantFailure exception) {
            return failure(
                Status.TECHNICAL_FAILURE,
                exception.getMessage(),
                position,
                Optional.of(projection),
                Optional.of(factorization),
                Optional.of(transformation),
                Optional.empty(),
                Optional.empty(),
                merge(throughTransformation, replacementWork.ledger()));
        }
    }

    private boolean canReserveBeforeProjection(
        PolynomialWorkLedger priorWork,
        long replacementReserve,
        long extractionCeiling
    ) {
        try {
            long required = priorWork.totalWorkUnits();
            required = Math.addExact(
                required,
                projector.policy().maxWorkUnits());
            required = Math.addExact(required, replacementReserve);
            required = Math.addExact(required, extractionCeiling);
            return required <= policy.maxTotalWorkUnits();
        } catch (ArithmeticException exception) {
            return false;
        }
    }

    private long remainingAfterReservation(
        PolynomialWorkLedger priorWork,
        long replacementReserve
    ) {
        long consumedAndReserved = Math.addExact(
            priorWork.totalWorkUnits(),
            replacementReserve);
        return policy.maxTotalWorkUnits() - consumedAndReserved;
    }

    private long extractionWorkCeiling() {
        return Math.addExact(
            (long) extractionView.budget().maxVisitedNodes(),
            extractionView.budget().maxArithmeticOperations());
    }

    private long replacementReplayWorkCeiling() {
        try {
            long result = projector.policy().maxSubtreeNodes();
            result = Math.addExact(
                result,
                projector.policy().maxFormattedCodeUnits());
            result = Math.addExact(
                result,
                Math.multiplyExact(8L, policy.maxPathDepth()));
            result = Math.addExact(
                result,
                Math.multiplyExact(2L, policy.maxRootNodes()));
            result = Math.addExact(
                result,
                Math.multiplyExact(3L, policy.maxReplacementNodes()));
            long hashNodes = Math.addExact(
                (long) policy.maxRootNodes(),
                policy.maxReplacementNodes());
            result = Math.addExact(
                result,
                Math.multiplyExact(
                    Math.multiplyExact(2L, STRUCTURAL_HASH_UNITS_PER_NODE),
                    hashNodes));
            return result;
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(
                "nested replacement reserve exceeds long range",
                exception);
        }
    }

    private ExactParsedFactorizationPipeline.Policy factorizationPolicy(
        long authority
    ) {
        return new ExactParsedFactorizationPipeline.Policy(
            policy.structuralLimits(),
            policy.maxCandidates(),
            authority,
            policy.evidenceRequirement());
    }

    private static boolean surroundingReferencesPreserved(
        Expr original,
        Expr rewritten,
        List<Integer> path,
        Expr replacement,
        Work work
    ) {
        Expr before = original;
        Expr after = rewritten;
        for (int selectedIndex : path) {
            work.consume("nested.unchanged-ancestor-checks", 1);
            if (before instanceof BinaryExpr originalBinary
                    && after instanceof BinaryExpr rewrittenBinary
                    && originalBinary.operator()
                        == rewrittenBinary.operator()) {
                if (selectedIndex == 0) {
                    work.consume("nested.unchanged-sibling-checks", 1);
                    if (originalBinary.right()
                            != rewrittenBinary.right()) {
                        return false;
                    }
                    before = originalBinary.left();
                    after = rewrittenBinary.left();
                } else if (selectedIndex == 1) {
                    work.consume("nested.unchanged-sibling-checks", 1);
                    if (originalBinary.left()
                            != rewrittenBinary.left()) {
                        return false;
                    }
                    before = originalBinary.right();
                    after = rewrittenBinary.right();
                } else {
                    return false;
                }
            } else if (before instanceof FunctionExpr originalFunction
                    && after instanceof FunctionExpr rewrittenFunction
                    && originalFunction.name().equals(
                        rewrittenFunction.name())
                    && originalFunction.arguments().size()
                        == rewrittenFunction.arguments().size()
                    && selectedIndex
                        < originalFunction.arguments().size()) {
                for (int index = 0;
                        index < originalFunction.arguments().size();
                        index++) {
                    if (index != selectedIndex) {
                        work.consume(
                            "nested.unchanged-sibling-checks",
                            1);
                        if (originalFunction.arguments().get(index)
                                != rewrittenFunction.arguments().get(index)) {
                            return false;
                        }
                    }
                }
                before = originalFunction.arguments().get(selectedIndex);
                after = rewrittenFunction.arguments().get(selectedIndex);
            } else {
                return false;
            }
        }
        return after == replacement;
    }

    private static int countNodes(
        Expr root,
        int limit,
        Work work,
        String stage
    ) {
        Deque<Expr> pending = new ArrayDeque<>();
        IdentityHashMap<Expr, Boolean> visited = new IdentityHashMap<>();
        pending.push(Objects.requireNonNull(root, "root"));
        int count = 0;
        while (!pending.isEmpty()) {
            Expr node = pending.pop();
            if (visited.put(node, Boolean.TRUE) != null) {
                throw invariant("AST_REUSES_NODE_IDENTITY");
            }
            count++;
            if (count > limit) {
                throw representationLimit(stage + "_LIMIT_EXCEEDED");
            }
            work.consume(stage, 1);
            pushChildren(node, pending);
        }
        return count;
    }

    private static String structuralHash(
        Expr root,
        int nodeLimit,
        Work work,
        String stage
    ) {
        Deque<Expr> pending = new ArrayDeque<>();
        IdentityHashMap<Expr, Boolean> visited = new IdentityHashMap<>();
        EvidenceDigest digest = new EvidenceDigest();
        pending.push(Objects.requireNonNull(root, "root"));
        int count = 0;
        while (!pending.isEmpty()) {
            Expr node = pending.pop();
            if (visited.put(node, Boolean.TRUE) != null) {
                throw invariant("REWRITTEN_AST_REUSES_NODE_IDENTITY");
            }
            count++;
            if (count > nodeLimit) {
                throw representationLimit(
                    "REWRITTEN_AST_NODE_LIMIT_EXCEEDED");
            }
            work.consume(stage, STRUCTURAL_HASH_UNITS_PER_NODE);
            if (node instanceof BinaryExpr binary) {
                digest.append("BINARY");
                digest.append(binary.operator().name());
                pending.push(binary.right());
                pending.push(binary.left());
            } else if (node instanceof FunctionExpr function) {
                digest.append("FUNCTION");
                digest.append(function.name());
                digest.append(Integer.toString(
                    function.arguments().size()));
                List<Expr> arguments = function.arguments();
                for (int index = arguments.size() - 1;
                        index >= 0;
                        index--) {
                    pending.push(arguments.get(index));
                }
            } else if (node instanceof VariableExpr variable) {
                digest.append("VARIABLE");
                digest.append(variable.name());
            } else if (node instanceof NumberExpr number) {
                digest.append("NUMBER");
                digest.append(Double.toHexString(number.value()));
            } else {
                throw invariant("UNSUPPORTED_AST_NODE_IN_STRUCTURAL_HASH");
            }
        }
        digest.append(Integer.toString(count));
        return digest.finish();
    }

    private static void pushChildren(Expr node, Deque<Expr> pending) {
        if (node instanceof BinaryExpr binary) {
            pending.push(binary.right());
            pending.push(binary.left());
        } else if (node instanceof FunctionExpr function) {
            List<Expr> arguments = function.arguments();
            for (int index = arguments.size() - 1;
                    index >= 0;
                    index--) {
                pending.push(arguments.get(index));
            }
        }
    }

    private Result success(
        TreePosition position,
        ExactParsedSubtermProjector.Result projection,
        ExactParsedFactorizationPipeline.Result factorization,
        ExactFactorizationTransformationPipeline.Result transformation,
        Expr rewrittenRoot,
        String rewrittenStructuralHash,
        PolynomialWorkLedger totalWork
    ) {
        return new Result(
            Status.TRANSFORMED,
            "EXACT_NESTED_FACTORIZATION_REPLACED_AND_REPLAYED",
            policy,
            position,
            Optional.of(projection),
            Optional.of(factorization),
            Optional.of(transformation),
            Optional.of(rewrittenRoot),
            Optional.of(rewrittenStructuralHash),
            totalWork);
    }

    private Result failure(
        Status status,
        String detailCode,
        TreePosition position,
        Optional<ExactParsedSubtermProjector.Result> projection,
        Optional<ExactParsedFactorizationPipeline.Result> factorization,
        Optional<ExactFactorizationTransformationPipeline.Result>
            transformation,
        Optional<Expr> rewrittenRoot,
        Optional<String> rewrittenStructuralHash,
        PolynomialWorkLedger totalWork
    ) {
        return new Result(
            status,
            detailCode,
            policy,
            position,
            projection,
            factorization,
            transformation,
            rewrittenRoot,
            rewrittenStructuralHash,
            totalWork);
    }

    private static Status mapProjectionStatus(
        ExactParsedSubtermProjector.Status status
    ) {
        return switch (status) {
            case PROJECTED -> throw new IllegalStateException(
                "successful projection must continue");
            case POSITION_NOT_PRESENT -> Status.POSITION_NOT_PRESENT;
            case POSITION_STALE -> Status.POSITION_STALE;
            case UNSUPPORTED -> Status.UNSUPPORTED;
            case BUDGET_INCONCLUSIVE -> Status.BUDGET_INCONCLUSIVE;
            case TECHNICAL_FAILURE -> Status.TECHNICAL_FAILURE;
        };
    }

    private static Status mapTransformationStatus(
        ExactFactorizationTransformationPipeline.Status status
    ) {
        return switch (status) {
            case TRANSFORMED -> throw new IllegalStateException(
                "successful transformation must continue");
            case NO_CHANGE -> Status.NO_CHANGE;
            case NO_CANDIDATE -> Status.NO_CANDIDATE;
            case BACKEND_CLAIMED_IRREDUCIBLE ->
                Status.BACKEND_CLAIMED_IRREDUCIBLE;
            case IRREDUCIBLE -> Status.IRREDUCIBLE;
            case UNSUPPORTED -> Status.UNSUPPORTED;
            case BUDGET_INCONCLUSIVE -> Status.BUDGET_INCONCLUSIVE;
            case TECHNICAL_FAILURE -> Status.TECHNICAL_FAILURE;
            case SOURCE_EVIDENCE_MISMATCH ->
                Status.SOURCE_EVIDENCE_MISMATCH;
        };
    }

    private static PolynomialWorkLedger merge(
        PolynomialWorkLedger first,
        PolynomialWorkLedger second
    ) {
        Map<String, Long> stages = new LinkedHashMap<>(first.stages());
        second.stages().forEach((stage, units) -> stages.merge(
            stage,
            units,
            Math::addExact));
        return new PolynomialWorkLedger(stages);
    }

    private static RepresentationLimitReached representationLimit(
        String detailCode
    ) {
        return new RepresentationLimitReached(detailCode);
    }

    private static StructuralInvariantFailure invariant(
        String detailCode
    ) {
        return new StructuralInvariantFailure(detailCode);
    }

    public enum Status {
        TRANSFORMED,
        POSITION_NOT_PRESENT,
        POSITION_STALE,
        NO_CHANGE,
        NO_CANDIDATE,
        BACKEND_CLAIMED_IRREDUCIBLE,
        IRREDUCIBLE,
        UNSUPPORTED,
        BUDGET_INCONCLUSIVE,
        TECHNICAL_FAILURE,
        SOURCE_EVIDENCE_MISMATCH
    }

    /** Global bounds and factorization request policy for one nested attempt. */
    public record Policy(
        int maxPathDepth,
        int maxRootNodes,
        int maxReplacementNodes,
        long maxTotalWorkUnits,
        FactorizationRequest.StructuralLimits structuralLimits,
        int maxCandidates,
        FactorizationRequest.EvidenceRequirement evidenceRequirement
    ) {
        public Policy {
            Objects.requireNonNull(structuralLimits, "structuralLimits");
            Objects.requireNonNull(
                evidenceRequirement,
                "evidenceRequirement");
            if (maxPathDepth < 0
                    || maxPathDepth > MAX_PATH_DEPTH
                    || maxRootNodes < 1
                    || maxRootNodes > MAX_ROOT_NODES
                    || maxReplacementNodes < 1
                    || maxReplacementNodes > MAX_REPLACEMENT_NODES
                    || maxTotalWorkUnits < 1
                    || maxTotalWorkUnits > MAX_TOTAL_WORK_UNITS
                    || maxCandidates < 0) {
                throw new IllegalArgumentException(
                    "nested factorization policy is invalid");
            }
        }

        public static Policy boundedDefaults() {
            return new Policy(
                256,
                4_096,
                4_096,
                40_000_000L,
                new FactorizationRequest.StructuralLimits(
                    1,
                    ExactParsedUnivariatePolynomialView.MAX_DEGREE,
                    ExactParsedUnivariatePolynomialView.MAX_DEGREE + 1,
                    ExactParsedUnivariatePolynomialView
                        .MAX_COEFFICIENT_BITS),
                250_000,
                FactorizationRequest.EvidenceRequirement
                    .VERIFIED_DECOMPOSITION);
        }

        public String canonicalMaterial() {
            return maxPathDepth + ":"
                + maxRootNodes + ":"
                + maxReplacementNodes + ":"
                + maxTotalWorkUnits + ":"
                + structuralLimits.canonicalMaterial() + ":"
                + maxCandidates + ":"
                + evidenceRequirement.name();
        }
    }

    /** Content-addressed result for one nested occurrence attempt. */
    public static final class Result {
        private final Status status;
        private final String detailCode;
        private final Policy policy;
        private final TreePosition position;
        private final Optional<ExactParsedSubtermProjector.Result> projection;
        private final Optional<ExactParsedFactorizationPipeline.Result>
            factorization;
        private final Optional<ExactFactorizationTransformationPipeline.Result>
            transformation;
        private final Optional<Expr> rewrittenRoot;
        private final Optional<String> rewrittenStructuralHash;
        private final PolynomialWorkLedger totalWork;
        private final String certificateHash;

        private Result(
            Status status,
            String detailCode,
            Policy policy,
            TreePosition position,
            Optional<ExactParsedSubtermProjector.Result> projection,
            Optional<ExactParsedFactorizationPipeline.Result> factorization,
            Optional<ExactFactorizationTransformationPipeline.Result>
                transformation,
            Optional<Expr> rewrittenRoot,
            Optional<String> rewrittenStructuralHash,
            PolynomialWorkLedger totalWork
        ) {
            this.status = Objects.requireNonNull(status, "status");
            if (detailCode == null || detailCode.isBlank()) {
                throw new IllegalArgumentException(
                    "nested transformation detail code must not be blank");
            }
            this.detailCode = detailCode;
            this.policy = Objects.requireNonNull(policy, "policy");
            this.position = Objects.requireNonNull(position, "position");
            this.projection = Objects.requireNonNull(
                projection,
                "projection");
            this.factorization = Objects.requireNonNull(
                factorization,
                "factorization");
            this.transformation = Objects.requireNonNull(
                transformation,
                "transformation");
            this.rewrittenRoot = Objects.requireNonNull(
                rewrittenRoot,
                "rewrittenRoot");
            this.rewrittenStructuralHash = validateHash(
                Objects.requireNonNull(
                    rewrittenStructuralHash,
                    "rewrittenStructuralHash"));
            this.totalWork = Objects.requireNonNull(totalWork, "totalWork");
            boolean transformed = status == Status.TRANSFORMED;
            if (transformed != rewrittenRoot.isPresent()
                    || transformed != rewrittenStructuralHash.isPresent()
                    || transformed != projection.isPresent()
                    || transformed != factorization.isPresent()
                    || transformed != transformation.isPresent()) {
                throw new IllegalArgumentException(
                    "nested transformation status/payload mismatch");
            }
            if (!totalWork.within(policy.maxTotalWorkUnits())) {
                throw new IllegalArgumentException(
                    "nested transformation work exceeds policy");
            }
            this.certificateHash = certificate();
        }

        private static Optional<String> validateHash(
            Optional<String> hash
        ) {
            hash.ifPresent(value -> {
                if (!value.matches("sha256:[0-9a-f]{64}")) {
                    throw new IllegalArgumentException(
                        "nested structural hash is invalid");
                }
            });
            return hash;
        }

        public Status status() {
            return status;
        }

        public String detailCode() {
            return detailCode;
        }

        public Policy policy() {
            return policy;
        }

        public TreePosition position() {
            return position;
        }

        public Optional<ExactParsedSubtermProjector.Result> projection() {
            return projection;
        }

        public Optional<ExactParsedFactorizationPipeline.Result>
                factorization() {
            return factorization;
        }

        public Optional<ExactFactorizationTransformationPipeline.Result>
                transformation() {
            return transformation;
        }

        public Optional<Expr> rewrittenRoot() {
            return rewrittenRoot;
        }

        public Optional<String> rewrittenStructuralHash() {
            return rewrittenStructuralHash;
        }

        public PolynomialWorkLedger totalWork() {
            return totalWork;
        }

        public String certificateHash() {
            return certificateHash;
        }

        public boolean transformed() {
            return status == Status.TRANSFORMED;
        }

        public String canonicalMaterial() {
            EvidenceDigest digest = new EvidenceDigest();
            digest.append(certificateMaterial());
            digest.append(certificateHash);
            return digest.finish();
        }

        private String certificate() {
            EvidenceDigest digest = new EvidenceDigest();
            digest.append(certificateMaterial());
            return digest.finish();
        }

        private String certificateMaterial() {
            EvidenceDigest digest = new EvidenceDigest();
            digest.append(PIPELINE_ID);
            digest.append(status.name());
            digest.append(detailCode);
            digest.append(policy.canonicalMaterial());
            digest.append(position.path().stream()
                .map(String::valueOf)
                .collect(java.util.stream.Collectors.joining(".")));
            digest.append(position.text());
            digest.append(projection.map(
                ExactParsedSubtermProjector.Result::certificateHash)
                .orElse(""));
            digest.append(factorization.map(
                ExactParsedFactorizationPipeline.Result::certificateHash)
                .orElse(""));
            digest.append(transformation.map(
                ExactFactorizationTransformationPipeline.Result
                    ::certificateHash).orElse(""));
            digest.append(rewrittenStructuralHash.orElse(""));
            digest.append(totalWork.canonicalMaterial());
            return digest.finish();
        }
    }

    private static final class Work {
        private final long limit;
        private final Map<String, Long> stages = new LinkedHashMap<>();
        private long total;

        private Work(long limit) {
            this.limit = limit;
        }

        private void consume(String stage, long units) {
            if (stage == null || stage.isBlank() || units < 0) {
                throw new IllegalArgumentException(
                    "nested work entry is invalid");
            }
            if (total > limit - units) {
                throw new WorkLimitReached(
                    "NESTED_REPLACEMENT_REPLAY_WORK_BUDGET_EXCEEDED");
            }
            if (units == 0) {
                return;
            }
            total += units;
            stages.merge(stage, units, Math::addExact);
        }

        private PolynomialWorkLedger ledger() {
            return new PolynomialWorkLedger(stages);
        }
    }

    private static final class EvidenceDigest {
        private final MessageDigest digest;

        private EvidenceDigest() {
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException exception) {
                throw new IllegalStateException(
                    "SHA-256 is unavailable",
                    exception);
            }
        }

        private void append(String value) {
            byte[] bytes = Objects.requireNonNull(value, "value")
                .getBytes(StandardCharsets.UTF_8);
            byte[] length = Integer.toString(bytes.length())
                .getBytes(StandardCharsets.US_ASCII);
            digest.update(length);
            digest.update((byte) ':');
            digest.update(bytes);
            digest.update((byte) '\n');
        }

        private String finish() {
            return "sha256:" + HexFormat.of().formatHex(digest.digest());
        }
    }

    private static final class WorkLimitReached extends RuntimeException {
        private WorkLimitReached(String detailCode) {
            super(detailCode);
        }
    }

    private static final class RepresentationLimitReached
            extends RuntimeException {
        private RepresentationLimitReached(String detailCode) {
            super(detailCode);
        }
    }

    private static final class StructuralInvariantFailure
            extends RuntimeException {
        private StructuralInvariantFailure(String detailCode) {
            super(detailCode);
        }
    }
}
