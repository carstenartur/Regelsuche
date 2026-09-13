package de.regelsuche.evolution;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable native gate evidence, retaining the full selected identity without granting promotion authority. */
public record EvolutionRewriteProgramQualificationAssessment(String schema, String repositoryRevision,
    EvolutionRewriteProgramFinalTestEvaluation finalTest, EvolutionGenomeValidator.ValidationReport preflight,
    List<GeneAssessment> genes, GateStatus preflightStatus, GateStatus proofStatus, GateStatus counterexampleStatus,
    List<String> blockers, String externalNoveltyStatus, String promotionStatus, String publicEvidenceStatus,
    String contentHash) {
    public static final String SCHEMA = "regelsuche.evolution-rewrite-program-qualification-assessment/v1";
    private static final String NOT_EVALUATED = "NOT_EVALUATED";

    public EvolutionRewriteProgramQualificationAssessment {
        if (!SCHEMA.equals(schema)) { throw new IllegalArgumentException("unsupported combined-program qualification assessment"); }
        LearnedPatternAuthorizationJson.requireRevision(repositoryRevision, "repositoryRevision");
        Objects.requireNonNull(finalTest, "finalTest");
        genes = List.copyOf(Objects.requireNonNull(genes, "genes"));
        blockers = List.copyOf(Objects.requireNonNull(blockers, "blockers"));
        Objects.requireNonNull(preflightStatus, "preflightStatus");
        Objects.requireNonNull(proofStatus, "proofStatus");
        Objects.requireNonNull(counterexampleStatus, "counterexampleStatus");
        for (var status : List.of(externalNoveltyStatus, promotionStatus, publicEvidenceStatus)) {
            if (!NOT_EVALUATED.equals(status)) {
                throw new IllegalArgumentException("native assessment cannot claim external review or promotion authority");
            }
        }
        // A self-rehashed PROVED summary is insufficient: rerun only public gene mathematics, never FINAL TEST.
        var expected = EvolutionRewriteProgramQualificationService.evaluateNative(finalTest, repositoryRevision);
        if (!Objects.equals(preflight, expected.preflight()) || !genes.equals(expected.genes())
                || preflightStatus != expected.preflightStatus() || proofStatus != expected.proofStatus()
                || counterexampleStatus != expected.counterexampleStatus() || !blockers.equals(expected.blockers())) {
            throw new IllegalArgumentException("qualification evidence differs from the existing native gate consumers");
        }
        EvolutionProgramValidationJson.requireHash(contentHash, material(repositoryRevision, finalTest, expected));
    }

    static EvolutionRewriteProgramQualificationAssessment create(String revision,
        EvolutionRewriteProgramFinalTestEvaluation finalTest) {
        var actual = EvolutionRewriteProgramQualificationService.evaluateNative(finalTest, revision);
        return new EvolutionRewriteProgramQualificationAssessment(SCHEMA, revision, finalTest, actual.preflight(),
            actual.genes(), actual.preflightStatus(), actual.proofStatus(), actual.counterexampleStatus(), actual.blockers(),
            NOT_EVALUATED, NOT_EVALUATED, NOT_EVALUATED,
            EvolutionProgramValidationJson.hash(material(revision, finalTest, actual)));
    }

    public boolean nativeGatesPassed() {
        return finalTest.qualificationEligible() && preflightStatus == GateStatus.PASSED
            && proofStatus == GateStatus.PASSED && counterexampleStatus == GateStatus.PASSED && blockers.isEmpty();
    }

    public String toCanonicalJson() { return EvolutionProgramValidationJson.write(this); }

    /** Structural import and native replay only; external artifact custody is checked by verifyAssessment. */
    static EvolutionRewriteProgramQualificationAssessment fromCanonicalJson(String json) {
        return EvolutionProgramValidationJson.read(json, EvolutionRewriteProgramQualificationAssessment.class);
    }

    public enum GateStatus { PASSED, REJECTED, NOT_EVALUATED }

    public record GeneAssessment(String geneId, boolean referencedByProgram,
        ExactPolynomialPatternIdentityVerifier.Verification proof, LearnedPatternCounterexampleEvidence counterexample,
        GateStatus proofStatus, GateStatus counterexampleStatus, List<String> blockers) {
        public GeneAssessment {
            EvolutionValidationArtifactSupport.requireText(geneId, "geneId");
            Objects.requireNonNull(proofStatus, "proofStatus");
            Objects.requireNonNull(counterexampleStatus, "counterexampleStatus");
            blockers = List.copyOf(Objects.requireNonNull(blockers, "blockers"));
        }
    }

    private static Map<String, Object> material(String revision, EvolutionRewriteProgramFinalTestEvaluation finalTest,
        EvolutionRewriteProgramQualificationService.NativeGates gates) {
        return EvolutionProgramValidationJson.material(SCHEMA, "repositoryRevision", revision, "finalTest", finalTest,
            "preflight", gates.preflight(), "genes", gates.genes(), "preflightStatus", gates.preflightStatus(),
            "proofStatus", gates.proofStatus(), "counterexampleStatus", gates.counterexampleStatus(), "blockers", gates.blockers(),
            "externalNoveltyStatus", NOT_EVALUATED, "promotionStatus", NOT_EVALUATED, "publicEvidenceStatus", NOT_EVALUATED);
    }
}
