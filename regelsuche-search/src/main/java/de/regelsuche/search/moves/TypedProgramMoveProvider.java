package de.regelsuche.search.moves;

import de.regelsuche.assumption.AssumptionSignature;
import de.regelsuche.search.program.CompiledAstReplayCodec;
import de.regelsuche.search.program.CompiledAstRewriteProgram;
import de.regelsuche.search.program.RewriteCandidate;
import de.regelsuche.transform.Transformation;
import de.regelsuche.transform.TransformationWorkMetrics;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * Registered compiled continuations on the typed frontier. Persisted histories are proposals;
 * the verifier regenerates the registered program, including every intermediate state.
 * Accounting covers mechanical work and emitted primitives, not total CPU/AST equality costs.
 */
public final class TypedProgramMoveProvider implements TypedMoveSearch.TypedProvider {
    private static final CompiledAstReplayCodec CODEC = new CompiledAstReplayCodec();
    private final Descriptor descriptor;
    private final CompiledAstRewriteProgram program;

    public TypedProgramMoveProvider(Descriptor descriptor, CompiledAstRewriteProgram program) {
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
        this.program = Objects.requireNonNull(program, "program");
        if (descriptor.sourceKind() != SearchMove.SourceKind.LEARNED
                && descriptor.sourceKind() != SearchMove.SourceKind.EXPERT) {
            throw new IllegalArgumentException("compiled typed programs require LEARNED or EXPERT source kind");
        }
        if (descriptor.proofStrength() != SearchMove.ProofStrength.REPLAYABLE) {
            throw new IllegalArgumentException("compiled regeneration requires REPLAYABLE evidence, not proof promotion");
        }
    }

    @Override public Descriptor descriptor() { return descriptor; }

    @Override public Batch candidates(MoveState state, MoveContext context) {
        if (!context.carries(descriptor.requiredAssumptions(), state)) {
            return new Batch(List.of(),
                new TransformationWorkMetrics(0, 0, 0, 0, 0, 1, 1, 0, 0, 0, 0, 0, 0, 0), true);
        }
        CompiledAstRewriteProgram.Batch batch;
        try {
            batch = program.transformMeasured(CODEC.decodeExpression(state.expression()));
        } catch (CompiledAstRewriteProgram.CandidateLimitExceeded limit) {
            return new Batch(List.of(), limit.workMetrics(), false);
        }
        long work = batch.workMetrics().totalWorkUnits();
        return new Batch(batch.candidates().stream().map(candidate -> move(candidate, work)).toList(),
            batch.workMetrics(), false);
        // The underlying primitive transport has no truncation receipt. Do not claim completeness.
    }

    /** Decoded data grants no authority. A receiving search must still use verifier(). */
    public SearchMove proposal(CompiledAstRewriteProgram.Candidate history) {
        return move(Objects.requireNonNull(history, "history"), 0);
    }

    private SearchMove move(CompiledAstRewriteProgram.Candidate history, long generationWork) {
        String identity = CODEC.contentHash(history);
        var steps = new ArrayList<Transformation>();
        for (int index = 0; index < history.steps().size(); index++) {
            var step = history.steps().get(index);
            steps.add(new Transformation(step.rule(), CODEC.encodeExpression(step.target()), step.kind(),
                step.mayIncreaseComplexity(), step.estimatedCostDelta(), step.equivalencePreservingByConstruction(),
                "typed-program:" + identity + ":" + index, step.assumptions(), step.packId(), step.license()));
        }
        var candidate = new RewriteCandidate(history.programId(), CODEC.encodeExpression(history.source()),
            CODEC.encodeExpression(history.target()), steps);
        return SearchMove.from(candidate.toTransformation(), descriptor, generationWork);
    }

    /** A score or a decoded history cannot bypass source, stage, metadata, or premise checks. */
    public TypedMoveSearch.Verifier verifier() {
        return (source, proposal, context) -> {
            var available = new HashSet<>(context.initialAssumptions());
            available.addAll(AssumptionSignature.ofExpressions(source.assumptions()).normalizedAssumptions());
            if (!available.containsAll(proposal.assumptions())
                    || !available.containsAll(descriptor.requiredAssumptions())) {
                return new MoveVerifier.Verification(false, 1, List.of(), "TYPED_PROGRAM_ASSUMPTIONS_MISSING");
            }
            CompiledAstRewriteProgram.Batch regenerated;
            try {
                regenerated = program.transformMeasured(source.expression());
            } catch (CompiledAstRewriteProgram.CandidateLimitExceeded limit) {
                return new MoveVerifier.Verification(false, verificationWork(limit.workMetrics()), List.of(),
                    "TYPED_PROGRAM_CANDIDATE_LIMIT");
            }
            long work = verificationWork(regenerated.workMetrics());
            work = Math.addExact(work, regenerated.candidates().size());
            boolean accepted = regenerated.candidates().stream()
                .map(candidate -> move(candidate, proposal.generationCost())).anyMatch(proposal::equals);
            return new MoveVerifier.Verification(accepted, work,
                accepted ? List.of("typed-program-replay:" + proposal.transformation().applicationKey()) : List.of(),
                accepted ? "TYPED_PROGRAM_REPLAYED" : "TYPED_PROGRAM_REPLAY_REJECTED");
        };
    }

    private static long verificationWork(TransformationWorkMetrics work) {
        return Math.addExact(work.totalWorkUnits(), work.candidateWork().canonicalWorkUnits());
    }
}
