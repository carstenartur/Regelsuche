package de.regelsuche.evolution;

import static de.regelsuche.search.program.RewritePrograms.sequence;

import de.regelsuche.evolution.ExactFinitePolynomialPlanCandidateEvidenceVerifier.VerifiedCandidateEvidence;
import de.regelsuche.evolution.ExactFinitePolynomialPlanReplayArtifactVerifier.LoadedArtifact;
import de.regelsuche.math.algorithms.equivalence.ExactFinitePolynomialHoleSolver.HoleDomain;
import de.regelsuche.search.program.RewriteProgram;
import de.regelsuche.search.program.RewritePrograms;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;

/** Each call runs the real solver, artifact checks, independent replay and evidence verifier. */
final class VerifiedPolynomialFixture {
    private static final SchematicProofPlan.Limits PLAN_LIMITS =
        new SchematicProofPlan.Limits(8, 8, 4, 200_000);

    private VerifiedPolynomialFixture() {}

    static VerifiedCandidateEvidence unitEvidence(String id) {
        return prepare(id, "x*x", "(${unit}*x)^2",
            List.of(HoleDomain.integerRange("unit", 1, 1)), 1).evidence().getFirst();
    }

    static RewriteProgram ordinaryTheory(String id, VerifiedCandidateEvidence evidence) {
        return RewritePrograms.source(id, new VerifiedFinitePolynomialTransformationEngine(evidence));
    }

    static RewriteProgram primitive(String id, String ruleId) {
        return RewritePrograms.source(id, new AstRewriteTransformationEngine(AstRewriteTransformationEngine.defaultRules()
            .stream().filter(rule -> rule.id().equals(ruleId)).toList()));
    }

    static RewriteProgram mixedProgram(VerifiedCandidateEvidence evidence) {
        return sequence("mixed", primitive("pre", "ast_add_zero_right"), ordinaryTheory("theory", evidence),
            primitive("post", "ast_multiply_one_left"));
    }

    static long work(VerifiedCandidateEvidence evidence) {
        return evidence.data().canonicalWork().totalWorkUnits();
    }

    static Prepared prepare(String id, String source, String ansatz,
                                    List<HoleDomain> domains, int retainedLimit) {
        var resolver = new ExactFinitePolynomialPlanResolver();
        var plan = resolver.createPlan(id, source, ansatz, domains, retainedLimit, PLAN_LIMITS);
        var run = resolver.resolve(plan, source, ansatz, domains, retainedLimit);
        var receipt = new ExactFinitePolynomialPlanReplayVerifier()
            .verify(plan, source, ansatz, domains, retainedLimit, run);
        var bytesVerifier = new ExactFinitePolynomialPlanReplayArtifactVerifier();
        var receiptReference = bytesVerifier.describeReceipt(receipt);
        byte[] receiptBytes = receipt.toCanonicalJson().getBytes(StandardCharsets.UTF_8);
        var checkedReceiptBytes = bytesVerifier.verifyReceipt(receiptReference,
            ignored -> new LoadedArtifact(receiptReference.artifactId(), receiptBytes));
        var receiptArtifact = new ExactFinitePolynomialPlanReplayReceiptArtifactVerifier()
            .verify(checkedReceiptBytes);
        var runReference = bytesVerifier.describePlanRun(run);
        byte[] runBytes = run.toCanonicalJson().getBytes(StandardCharsets.UTF_8);
        var checkedRunBytes = bytesVerifier.verifyPlanRun(runReference,
            ignored -> new LoadedArtifact(runReference.artifactId(), runBytes));
        var confirmation = new ExactFinitePolynomialPlanReplayConfirmationVerifier().verify(
            receiptArtifact, checkedRunBytes, run, plan, source, ansatz, domains, retainedLimit);
        var evidenceVerifier = new ExactFinitePolynomialPlanCandidateEvidenceVerifier();
        // Deterministic selection without a historical endpoint or expected coefficient.
        var evidence = run.candidates().stream()
            .sorted(Comparator.comparing(ExactFinitePolynomialResolvedCandidate::contentHash))
            .map(candidate -> evidenceVerifier.verify(confirmation, plan, run, candidate.contentHash()))
            .toList();
        return new Prepared(run, evidence);
    }

    record Prepared(ExactFinitePolynomialPlanRun run,
                            List<VerifiedCandidateEvidence> evidence) {}

}
