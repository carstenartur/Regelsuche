package de.regelsuche.evolution;

import de.regelsuche.evolution.ExactFinitePolynomialPlanReplayArtifactVerifier.ArtifactReference;
import de.regelsuche.evolution.ExactFinitePolynomialPlanReplayArtifactVerifier.ArtifactSource;
import de.regelsuche.evolution.ExactFinitePolynomialPlanReplayArtifactVerifier.LoadedArtifact;
import de.regelsuche.evolution.ExactLinearPolynomialPlanResolver.Formation;
import de.regelsuche.evolution.ExactLinearPolynomialPlanResolver.Run;
import de.regelsuche.json.JsonWriter;
import de.regelsuche.math.algorithms.equivalence.ExactLinearPolynomialHoleSolver.Status;
import de.regelsuche.math.algorithms.equivalence.ExactLinearPolynomialHoleSolver.WorkProfile;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * Loads a complete linear run and repeats native solving against independently retained formation inputs.
 * Generic artifact addresses are reused; their linear role/schema does not confer finite-solver authority.
 */
public final class ExactLinearPolynomialPlanEvidenceVerifier {
    public static final String VERIFIER_ID = "regelsuche.exact-linear-polynomial-plan-evidence-verifier/v1";
    public static final String REVISION_HASH = SchematicProofPlan.hash(VERIFIER_ID + "|"
        + ExactLinearPolynomialPlanResolver.REVISION_HASH + "|complete-canonical-bytes-native-replay-original-plus-replay-work");
    public static final String THEORY_STEP_ID = "regelsuche.exact-linear-polynomial-plan-candidate-equivalence/v1";
    public static final String EVIDENCE_SCHEMA = "regelsuche.exact-linear-polynomial-plan-candidate-evidence/v1";
    private static final String ROLE = "linear-polynomial-plan-run";
    private static final String MEDIA = "application/json";

    public ArtifactReference describeRun(Run run) {
        return ArtifactReference.describe(ROLE, Run.SCHEMA, MEDIA, bytes(Objects.requireNonNull(run, "run")));
    }

    /** A loaded artifact cannot choose its plan, template, limits, source, holes or assumption context. */
    public VerifiedReplay verify(SchematicProofPlan expectedPlan, Formation expectedFormation,
                                 ArtifactReference reference, ArtifactSource source) {
        var resolver = new ExactLinearPolynomialPlanResolver();
        resolver.validatePlan(expectedPlan, expectedFormation);
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(source, "source");
        if (!ROLE.equals(reference.role()) || !Run.SCHEMA.equals(reference.contentSchema()) || !MEDIA.equals(reference.mediaType())) {
            throw new IllegalArgumentException("artifact is not a linear polynomial plan run");
        }
        LoadedArtifact loaded = Objects.requireNonNull(source.load(reference.artifactId()), "loaded artifact");
        byte[] retained = loaded.bytes();
        if (!reference.artifactId().equals(loaded.key()) || retained.length > Run.MAX_CANONICAL_BYTES
                || !reference.equals(ArtifactReference.describe(ROLE, Run.SCHEMA, MEDIA, retained))) {
            throw new IllegalArgumentException("linear run artifact key, bytes or reference differ");
        }
        Run replay = resolver.resolve(expectedPlan, expectedFormation);
        // Byte comparison to an independently generated canonical document also rejects malformed UTF-8,
        // unknown/duplicate fields and self-consistently rehashed foreign plans or fabricated solver outcomes.
        if (!Arrays.equals(retained, bytes(replay))) {
            throw new IllegalArgumentException("loaded linear run differs from independent native replay");
        }
        return new Replay(expectedPlan, expectedFormation, replay, reference);
    }

    /** Selects the sole unique native solution; no PASS flag or public run record can issue this capability. */
    public VerifiedCandidateEvidence verifyCandidate(VerifiedReplay replay) {
        Objects.requireNonNull(replay, "replay");
        Run run = replay.run();
        if (run.solverResult().status() != Status.UNIQUE || !run.solverResult().assumptions().isEmpty()
                || run.resolution().isEmpty() || !run.resolution().orElseThrow().isStructurallyCompleteFor(replay.plan())
                || run.solverResult().sourceExpression().equals(run.solverResult().candidate().orElseThrow().instantiatedExpression())
                || replay.mathematicalWorkUnits() < 1) {
            throw new IllegalArgumentException("replayed linear run has no unique changed assumption-free candidate");
        }
        return new CandidateEvidence(replay);
    }

    /** Complete positive or negative replay, issued only after an actual native resolver execution. */
    public sealed interface VerifiedReplay permits Replay {
        SchematicProofPlan plan();
        Formation formation();
        Run run();
        ArtifactReference reference();
        default int replayExecutions() { return 1; }
        default WorkProfile originalWork() { return run().solverResult().work(); }
        default WorkProfile replayWork() { return run().solverResult().work(); }
        default long mathematicalWorkUnits() {
            return Math.addExact((long) originalWork().consumed(), replayWork().consumed());
        }
    }

    /** No implementation or constructor for this evidence is available to a caller. */
    public sealed interface VerifiedCandidateEvidence permits CandidateEvidence {
        VerifiedReplay replay();
        String candidateHash();
        String evidenceHash();
        String toCanonicalJson();
        default String sourceExpression() { return replay().run().solverResult().sourceExpression(); }
        default String transformedExpression() { return replay().run().solverResult().candidate().orElseThrow().instantiatedExpression(); }
        default long mathematicalWorkUnits() { return replay().mathematicalWorkUnits(); }
    }

    private record Replay(SchematicProofPlan plan, Formation formation, Run run, ArtifactReference reference)
            implements VerifiedReplay {}

    private record CandidateEvidence(VerifiedReplay replay) implements VerifiedCandidateEvidence {
        @Override public String candidateHash() {
            return SchematicProofPlan.hash(new JsonWriter().beginObject().property("theoryStepId", THEORY_STEP_ID)
                .property("planHash", replay.plan().contentHash()).property("runHash", replay.run().contentHash())
                .property("resolutionHash", replay.run().resolution().orElseThrow().contentHash())
                .property("sourceExpression", sourceExpression()).property("transformedExpression", transformedExpression())
                .endObject().toString());
        }
        @Override public String evidenceHash() { return SchematicProofPlan.hash(render(false)); }
        @Override public String toCanonicalJson() { return render(true); }
        private String render(boolean includeHash) {
            JsonWriter json = new JsonWriter().beginObject().property("schema", EVIDENCE_SCHEMA)
                .property("verifierId", VERIFIER_ID).property("verifierRevisionHash", REVISION_HASH)
                .property("theoryStepId", THEORY_STEP_ID).property("candidateHash", candidateHash())
                .property("planCanonicalJson", replay.plan().toCanonicalJson())
                .property("runCanonicalJson", replay.run().toCanonicalJson())
                .property("runReferenceCanonicalJson", replay.reference().toCanonicalJson())
                .property("replayExecutions", replay.replayExecutions())
                .object("originalWork", work -> ExactLinearPolynomialPlanJson.work(work, replay.originalWork()))
                .object("replayWork", work -> ExactLinearPolynomialPlanJson.work(work, replay.replayWork()))
                .property("mathematicalWorkUnits", mathematicalWorkUnits());
            if (includeHash) { json.property("evidenceHash", evidenceHash()); }
            return json.endObject().toString();
        }
    }

    private static byte[] bytes(Run run) { return run.toCanonicalJson().getBytes(StandardCharsets.UTF_8); }
}
