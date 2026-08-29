package de.regelsuche.polynomial;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
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
        requireAttemptInputs(root, position, engine);
        Optional<Result> positionFailure = validatePosition(position);
        if (positionFailure.isPresent()) {
            return positionFailure.orElseThrow();
        }

        Outcome<PreflightContext> preflight = preflight(root, position);
        if (preflight.failed()) {
            return preflight.failureOrThrow();
        }
        Outcome<PreparedTransformation> prepared = prepareTransformation(
            root,
            position,
            engine,
            candidateIndex,
            preflight.valueOrThrow());
        if (prepared.failed()) {
            return prepared.failureOrThrow();
        }
        return applyPreparedTransformation(
            root,
            position,
            prepared.valueOrThrow());
    }

    private static void requireAttemptInputs(
        ExactParsedTerm root,
        TreePosition position,
        FactorizationEngine<ExactRational> engine
    ) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(engine, "engine");
        if (position.text() == null || position.text().isBlank()) {
            throw new IllegalArgumentException(
                "tree position text must not be blank");
        }
    }

    private Optional<Result> validatePosition(TreePosition position) {
        if (position.path().stream().anyMatch(
                index -> index == null || index < 0)) {
            return Optional.of(emptyFailure(
                Status.UNSUPPORTED,
                "INVALID_TREE_POSITION_PATH",
                position));
        }
        if (position.path().size() > policy.maxPathDepth()) {
            return Optional.of(emptyFailure(
                Status.BUDGET_INCONCLUSIVE,
                "MAX_NESTED_PATH_DEPTH_EXCEEDED",
                position));
        }
        return Optional.empty();
    }

    private Outcome<PreflightContext> preflight(
        ExactParsedTerm root,
        TreePosition position
    ) {
        Work work = new Work(policy.maxTotalWorkUnits());
        try {
            countNodes(
                root.expression(),
                policy.maxRootNodes(),
                work,
                "nested.root-preflight-node-visits");
        } catch (WorkLimitReached | RepresentationLimitReached exception) {
            return Outcome.failure(failure(
                Status.BUDGET_INCONCLUSIVE,
                exception.getMessage(),
                position,
                work.ledger()));
        } catch (StructuralInvariantFailure exception) {
            return Outcome.failure(failure(
                Status.TECHNICAL_FAILURE,
                exception.getMessage(),
                position,
                work.ledger()));
        }

        PolynomialWorkLedger priorWork = work.ledger();
        long replacementReserve = replacementReplayWorkCeiling();
        long extractionCeiling = extractionWorkCeiling();
        if (!canReserveBeforeProjection(
                priorWork,
                replacementReserve,
                extractionCeiling)) {
            return Outcome.failure(failure(
                Status.BUDGET_INCONCLUSIVE,
                "INSUFFICIENT_AUTHORITY_FOR_PROJECTION_AND_REPLAY",
                position,
                priorWork));
        }
        return Outcome.success(new PreflightContext(
            priorWork,
            replacementReserve,
            extractionCeiling));
    }

    private Outcome<PreparedTransformation> prepareTransformation(
        ExactParsedTerm root,
        TreePosition position,
        FactorizationEngine<ExactRational> engine,
        OptionalInt candidateIndex,
        PreflightContext preflight
    ) {
        ExactParsedSubtermProjector.Result projection = projector.project(
            root,
            position.path(),
            position.text());
        PolynomialWorkLedger priorWork = merge(
            preflight.priorWork(),
            new PolynomialWorkLedger(projection.work().stages()));
        if (!projection.successful()) {
            return Outcome.failure(projectionFailure(
                mapProjectionStatus(projection.status()),
                projection.detailCode(),
                position,
                projection,
                priorWork));
        }

        long factorizationAuthority = remainingAfterReservation(
            priorWork,
            preflight.replacementReserve());
        if (factorizationAuthority < preflight.extractionCeiling()) {
            return Outcome.failure(projectionFailure(
                Status.BUDGET_INCONCLUSIVE,
                "INSUFFICIENT_REMAINING_FACTORIZATION_AUTHORITY",
                position,
                projection,
                priorWork));
        }

        ExactParsedTerm projected = projection.projected().orElseThrow();
        ExactParsedFactorizationPipeline.Result factorization =
            new ExactParsedFactorizationPipeline(
                extractionView,
                factorizationPolicy(factorizationAuthority))
                .factor(projected, engine);
        ExactFactorizationTransformationPipeline.Result transformation =
            transformProjected(projected, factorization, candidateIndex);
        PolynomialWorkLedger throughTransformation = merge(
            priorWork,
            transformation.totalWork());
        if (!transformation.transformed()) {
            return Outcome.failure(transformationFailure(
                mapTransformationStatus(transformation.status()),
                transformation.detailCode(),
                position,
                projection,
                factorization,
                transformation,
                throughTransformation));
        }

        return Outcome.success(new PreparedTransformation(
            projection,
            projected,
            factorization,
            transformation,
            throughTransformation,
            preflight.replacementReserve()));
    }

    private ExactFactorizationTransformationPipeline.Result
            transformProjected(
                ExactParsedTerm projected,
                ExactParsedFactorizationPipeline.Result factorization,
                OptionalInt candidateIndex
            ) {
        ExactFactorizationTransformationPipeline transformationPipeline =
            new ExactFactorizationTransformationPipeline(
                renderer,
                parser,
                reconstructionView);
        if (candidateIndex.isPresent()) {
            return transformationPipeline.transformRoot(
                projected,
                factorization,
                candidateIndex.getAsInt());
        }
        return transformationPipeline.transformRoot(projected, factorization);
    }

    private Result applyPreparedTransformation(
        ExactParsedTerm root,
        TreePosition position,
        PreparedTransformation prepared
    ) {
        Work work = new Work(prepared.replacementReserve());
        try {
            Expr selected = selectAuthorizedOccurrence(
                root,
                position,
                prepared,
                work);
            AppliedReplacement applied = applyAuthorizedReplacement(
                root,
                position,
                prepared,
                selected,
                work);
            String rewrittenHash = verifyReplacementReplay(
                root,
                position,
                applied,
                work);
            PolynomialWorkLedger totalWork = merge(
                prepared.throughTransformation(),
                work.ledger());
            if (!totalWork.within(policy.maxTotalWorkUnits())) {
                throw invariant(
                    "NESTED_PIPELINE_EXCEEDED_TOTAL_WORK_AUTHORITY");
            }
            return success(
                position,
                prepared.projection(),
                prepared.factorization(),
                prepared.transformation(),
                applied.rewrittenRoot(),
                rewrittenHash,
                totalWork);
        } catch (ApplicationFailure exception) {
            return preparedFailure(
                exception.status(),
                exception.getMessage(),
                position,
                prepared,
                work);
        } catch (WorkLimitReached | RepresentationLimitReached exception) {
            return preparedFailure(
                Status.BUDGET_INCONCLUSIVE,
                exception.getMessage(),
                position,
                prepared,
                work);
        } catch (StructuralInvariantFailure exception) {
            return preparedFailure(
                Status.TECHNICAL_FAILURE,
                exception.getMessage(),
                position,
                prepared,
                work);
        }
    }

    private static Expr selectAuthorizedOccurrence(
        ExactParsedTerm root,
        TreePosition position,
        PreparedTransformation prepared,
        Work work
    ) {
        Expr selected = position.subtreeAt(root.expression()).orElse(null);
        work.consume(
            "nested.application-path-navigation",
            position.path().size());
        if (selected == null) {
            throw applicationFailure(
                Status.POSITION_NOT_PRESENT,
                "SELECTED_PATH_DISAPPEARED_BEFORE_APPLICATION");
        }
        if (selected != prepared.projected().expression()) {
            throw applicationFailure(
                Status.POSITION_STALE,
                "SELECTED_OCCURRENCE_IDENTITY_CHANGED_BEFORE_APPLICATION");
        }

        work.consume(
            "nested.application-staleness-format-node-visits",
            prepared.projection().work().units(
                "projection.subtree-node-visits"));
        String applicationText = ExpressionFormatter.format(selected);
        work.consume(
            "nested.application-staleness-text-code-units",
            Math.addExact(
                (long) position.text().length(),
                applicationText.length()));
        if (!position.text().equals(applicationText)) {
            throw applicationFailure(
                Status.POSITION_STALE,
                "SELECTED_POSITION_TEXT_CHANGED_BEFORE_APPLICATION");
        }
        return selected;
    }

    private AppliedReplacement applyAuthorizedReplacement(
        ExactParsedTerm root,
        TreePosition position,
        PreparedTransformation prepared,
        Expr selected,
        Work work
    ) {
        Expr replacement = prepared.transformation().reparsed()
            .orElseThrow().expression();
        countNodes(
            replacement,
            policy.maxReplacementNodes(),
            work,
            "nested.replacement-node-visits");
        TreePosition.ReplacementResult applied = position.replaceAt(
            root.expression(),
            replacement);
        work.consume(
            "nested.replacement-path-navigation",
            position.path().size());
        work.consume(
            "nested.replacement-ancestor-copies",
            applied.copiedAncestors());
        if (!applied.success()
                || applied.selectedSubtree().orElseThrow() != selected) {
            throw invariant(
                "STRUCTURAL_REPLACEMENT_DID_NOT_TARGET_AUTHORIZED_OCCURRENCE");
        }

        Expr rewrittenRoot = applied.rewrittenRoot().orElseThrow();
        if (!surroundingReferencesPreserved(
                root.expression(),
                rewrittenRoot,
                position.path(),
                replacement,
                work)) {
            throw invariant(
                "STRUCTURAL_REPLACEMENT_CHANGED_UNAUTHORIZED_SURROUNDING_AST");
        }
        Expr replayedOccurrence = position.subtreeAt(
            rewrittenRoot).orElse(null);
        work.consume(
            "nested.rewritten-path-replay",
            position.path().size());
        if (replayedOccurrence != replacement) {
            throw invariant(
                "REWRITTEN_PATH_DOES_NOT_RESOLVE_TO_REPLACEMENT");
        }
        return new AppliedReplacement(replacement, rewrittenRoot);
    }

    private String verifyReplacementReplay(
        ExactParsedTerm root,
        TreePosition position,
        AppliedReplacement applied,
        Work work
    ) {
        TreePosition.ReplacementResult replay = position.replaceAt(
            root.expression(),
            applied.replacement());
        work.consume(
            "nested.replacement-replay-path-navigation",
            position.path().size());
        work.consume(
            "nested.replacement-replay-ancestor-copies",
            replay.copiedAncestors());
        if (!replay.success()) {
            throw invariant("STRUCTURAL_REPLACEMENT_IS_NOT_REPLAYABLE");
        }

        int rewrittenNodeLimit = Math.addExact(
            policy.maxRootNodes(),
            policy.maxReplacementNodes());
        String rewrittenHash = structuralHash(
            applied.rewrittenRoot(),
            rewrittenNodeLimit,
            work,
            "nested.rewritten-structural-hash");
        String replayHash = structuralHash(
            replay.rewrittenRoot().orElseThrow(),
            rewrittenNodeLimit,
            work,
            "nested.replay-structural-hash");
        if (!rewrittenHash.equals(replayHash)) {
            throw invariant(
                "STRUCTURAL_REPLACEMENT_REPLAY_HASH_MISMATCH");
        }
        return rewrittenHash;
    }

    private Result emptyFailure(
        Status status,
        String detailCode,
        TreePosition position
    ) {
        return failure(
            status,
            detailCode,
            position,
            PolynomialWorkLedger.empty());
    }

    private Result failure(
        Status status,
        String detailCode,
        TreePosition position,
        PolynomialWorkLedger totalWork
    ) {
        return failure(
            status,
            detailCode,
            position,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            totalWork);
    }

    private Result projectionFailure(
        Status status,
        String detailCode,
        TreePosition position,
        ExactParsedSubtermProjector.Result projection,
        PolynomialWorkLedger totalWork
    ) {
        return failure(
            status,
            detailCode,
            position,
            Optional.of(projection),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            totalWork);
    }

    private Result transformationFailure(
        Status status,
        String detailCode,
        TreePosition position,
        ExactParsedSubtermProjector.Result projection,
        ExactParsedFactorizationPipeline.Result factorization,
        ExactFactorizationTransformationPipeline.Result transformation,
        PolynomialWorkLedger totalWork
    ) {
        return failure(
            status,
            detailCode,
            position,
            Optional.of(projection),
            Optional.of(factorization),
            Optional.of(transformation),
            Optional.empty(),
            Optional.empty(),
            totalWork);
    }

    private Result preparedFailure(
        Status status,
        String detailCode,
        TreePosition position,
        PreparedTransformation prepared,
        Work work
    ) {
        return transformationFailure(
            status,
            detailCode,
            position,
            prepared.projection(),
            prepared.factorization(),
            prepared.transformation(),
            merge(prepared.throughTransformation(), work.ledger()));
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
                Math.multiplyExact(
                    2L,
                    projector.policy().maxFormattedCodeUnits()));
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
            long structuralPayloadBytesPerHash = Math.addExact(
                Math.multiplyExact(
                    4L,
                    Math.addExact(
                        (long) projector.policy().maxRootSourceCodeUnits(),
                        renderer.policy().maxOutputCodeUnits())),
                Math.multiplyExact(64L, hashNodes));
            result = Math.addExact(
                result,
                Math.multiplyExact(2L, structuralPayloadBytesPerHash));
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
        ReferencePair current = new ReferencePair(original, rewritten);
        for (int selectedIndex : path) {
            work.consume("nested.unchanged-ancestor-checks", 1);
            Optional<ReferencePair> next = preservedReferenceStep(
                current,
                selectedIndex,
                work);
            if (next.isEmpty()) {
                return false;
            }
            current = next.orElseThrow();
        }
        return current.after() == replacement;
    }

    private static Optional<ReferencePair> preservedReferenceStep(
        ReferencePair current,
        int selectedIndex,
        Work work
    ) {
        if (current.before() instanceof BinaryExpr originalBinary
                && current.after() instanceof BinaryExpr rewrittenBinary) {
            return preservedBinaryStep(
                originalBinary,
                rewrittenBinary,
                selectedIndex,
                work);
        }
        if (current.before() instanceof FunctionExpr originalFunction
                && current.after() instanceof FunctionExpr rewrittenFunction) {
            return preservedFunctionStep(
                originalFunction,
                rewrittenFunction,
                selectedIndex,
                work);
        }
        return Optional.empty();
    }

    private static Optional<ReferencePair> preservedBinaryStep(
        BinaryExpr original,
        BinaryExpr rewritten,
        int selectedIndex,
        Work work
    ) {
        if (original.operator() != rewritten.operator()) {
            return Optional.empty();
        }
        return switch (selectedIndex) {
            case 0 -> preservedSibling(original.right(), rewritten.right(), work)
                ? Optional.of(new ReferencePair(
                    original.left(),
                    rewritten.left()))
                : Optional.empty();
            case 1 -> preservedSibling(original.left(), rewritten.left(), work)
                ? Optional.of(new ReferencePair(
                    original.right(),
                    rewritten.right()))
                : Optional.empty();
            default -> Optional.empty();
        };
    }

    private static Optional<ReferencePair> preservedFunctionStep(
        FunctionExpr original,
        FunctionExpr rewritten,
        int selectedIndex,
        Work work
    ) {
        if (!sameFunctionShape(original, rewritten, selectedIndex)) {
            return Optional.empty();
        }
        if (!functionSiblingsPreserved(
                original,
                rewritten,
                selectedIndex,
                work)) {
            return Optional.empty();
        }
        return Optional.of(new ReferencePair(
            original.arguments().get(selectedIndex),
            rewritten.arguments().get(selectedIndex)));
    }

    private static boolean sameFunctionShape(
        FunctionExpr original,
        FunctionExpr rewritten,
        int selectedIndex
    ) {
        return original.name().equals(rewritten.name())
            && original.arguments().size() == rewritten.arguments().size()
            && selectedIndex >= 0
            && selectedIndex < original.arguments().size();
    }

    private static boolean functionSiblingsPreserved(
        FunctionExpr original,
        FunctionExpr rewritten,
        int selectedIndex,
        Work work
    ) {
        for (int index = 0; index < original.arguments().size(); index++) {
            if (index == selectedIndex) {
                continue;
            }
            if (!preservedSibling(
                    original.arguments().get(index),
                    rewritten.arguments().get(index),
                    work)) {
                return false;
            }
        }
        return true;
    }

    private static boolean preservedSibling(
        Expr original,
        Expr rewritten,
        Work work
    ) {
        work.consume("nested.unchanged-sibling-checks", 1);
        return original == rewritten;
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
                appendStructuralMaterial(
                    digest, work, stage, "BINARY");
                appendStructuralMaterial(
                    digest,
                    work,
                    stage,
                    binary.operator().name());
                pending.push(binary.right());
                pending.push(binary.left());
            } else if (node instanceof FunctionExpr function) {
                appendStructuralMaterial(
                    digest, work, stage, "FUNCTION");
                appendStructuralMaterial(
                    digest, work, stage, function.name());
                appendStructuralMaterial(
                    digest,
                    work,
                    stage,
                    Integer.toString(function.arguments().size()));
                List<Expr> arguments = function.arguments();
                for (int index = arguments.size() - 1;
                        index >= 0;
                        index--) {
                    pending.push(arguments.get(index));
                }
            } else if (node instanceof VariableExpr variable) {
                appendStructuralMaterial(
                    digest, work, stage, "VARIABLE");
                appendStructuralMaterial(
                    digest, work, stage, variable.name());
            } else if (node instanceof NumberExpr number) {
                appendStructuralMaterial(
                    digest, work, stage, "NUMBER");
                appendStructuralMaterial(
                    digest,
                    work,
                    stage,
                    Double.toHexString(number.value()));
            } else {
                throw invariant("UNSUPPORTED_AST_NODE_IN_STRUCTURAL_HASH");
            }
        }
        appendStructuralMaterial(
            digest, work, stage, Integer.toString(count));
        return digest.finish();
    }

    private static void appendStructuralMaterial(
        EvidenceDigest digest,
        Work work,
        String stage,
        String value
    ) {
        byte[] bytes = Objects.requireNonNull(value, "value")
            .getBytes(StandardCharsets.UTF_8);
        work.consume(
            stage + "-payload-utf8-bytes",
            bytes.length);
        digest.append(value);
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

    private static ApplicationFailure applicationFailure(
        Status status,
        String detailCode
    ) {
        return new ApplicationFailure(status, detailCode);
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
            if (transformed) {
                if (rewrittenRoot.isEmpty()
                        || rewrittenStructuralHash.isEmpty()
                        || projection.isEmpty()
                        || factorization.isEmpty()
                        || transformation.isEmpty()) {
                    throw new IllegalArgumentException(
                        "successful nested transformation lacks evidence");
                }
            } else if (rewrittenRoot.isPresent()
                    || rewrittenStructuralHash.isPresent()) {
                throw new IllegalArgumentException(
                    "failed nested transformation retained rewritten output");
            }
            if (factorization.isPresent() && projection.isEmpty()
                    || transformation.isPresent()
                        && factorization.isEmpty()) {
                throw new IllegalArgumentException(
                    "nested upstream evidence ordering is invalid");
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

    private record PreflightContext(
        PolynomialWorkLedger priorWork,
        long replacementReserve,
        long extractionCeiling
    ) {
    }

    private record PreparedTransformation(
        ExactParsedSubtermProjector.Result projection,
        ExactParsedTerm projected,
        ExactParsedFactorizationPipeline.Result factorization,
        ExactFactorizationTransformationPipeline.Result transformation,
        PolynomialWorkLedger throughTransformation,
        long replacementReserve
    ) {
    }

    private record AppliedReplacement(
        Expr replacement,
        Expr rewrittenRoot
    ) {
    }

    private record ReferencePair(Expr before, Expr after) {
    }

    private record Outcome<T>(
        Optional<T> value,
        Optional<Result> failure
    ) {
        private Outcome {
            value = Objects.requireNonNull(value, "value");
            failure = Objects.requireNonNull(failure, "failure");
            if (value.isPresent() == failure.isPresent()) {
                throw new IllegalArgumentException(
                    "pipeline outcome must contain value xor failure");
            }
        }

        private static <T> Outcome<T> success(T value) {
            return new Outcome<>(
                Optional.of(Objects.requireNonNull(value, "value")),
                Optional.empty());
        }

        private static <T> Outcome<T> failure(Result failure) {
            return new Outcome<>(
                Optional.empty(),
                Optional.of(Objects.requireNonNull(failure, "failure")));
        }

        private boolean failed() {
            return failure.isPresent();
        }

        private T valueOrThrow() {
            return value.orElseThrow();
        }

        private Result failureOrThrow() {
            return failure.orElseThrow();
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
            if (units > limit - total) {
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
            byte[] length = Integer.toString(bytes.length)
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

    private static final class ApplicationFailure extends RuntimeException {
        private final Status status;

        private ApplicationFailure(Status status, String detailCode) {
            super(detailCode);
            this.status = Objects.requireNonNull(status, "status");
        }

        private Status status() {
            return status;
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
