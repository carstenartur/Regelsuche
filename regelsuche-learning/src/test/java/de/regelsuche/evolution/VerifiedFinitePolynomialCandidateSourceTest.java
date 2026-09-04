package de.regelsuche.evolution;

import static de.regelsuche.search.program.RewritePrograms.budgetedSource;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.evolution.ExactFinitePolynomialPlanCandidateEvidenceVerifier.VerifiedCandidateEvidence;
import de.regelsuche.evolution.ExactFinitePolynomialPlanReplayArtifactVerifier.ArtifactReference;
import de.regelsuche.evolution.ExactFinitePolynomialPlanReplayArtifactVerifier.LoadedArtifact;
import de.regelsuche.evolution.ExactFinitePolynomialPlanReplayArtifactVerifier.VerifiedArtifactBytes;
import de.regelsuche.evolution.ExactFinitePolynomialPlanReplayConfirmationVerifier.ConfirmedReplay;
import de.regelsuche.evolution.ExactFinitePolynomialPlanReplayReceiptArtifactVerifier.VerifiedReplayReceiptArtifact;
import de.regelsuche.evolution.ExactFinitePolynomialPlanReplayVerifier.ReplayReceipt;
import de.regelsuche.math.algorithms.equivalence.ExactFinitePolynomialHoleSolver.HoleDomain;
import de.regelsuche.search.program.BudgetedTransformationSource.Status;
import de.regelsuche.search.program.BudgetedTransformationSourceExecutor;
import de.regelsuche.search.program.RewriteProgramInterpreter;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
class VerifiedFinitePolynomialCandidateSourceTest {
    private static final SchematicProofPlan.Limits LIMITS =
        new SchematicProofPlan.Limits(8, 8, 4, 200_000);

    private final ExactFinitePolynomialPlanResolver resolver =
        new ExactFinitePolynomialPlanResolver();
    private final ExactFinitePolynomialPlanReplayVerifier replayVerifier =
        new ExactFinitePolynomialPlanReplayVerifier();
    private final ExactFinitePolynomialPlanReplayArtifactVerifier byteVerifier =
        new ExactFinitePolynomialPlanReplayArtifactVerifier();
    private final ExactFinitePolynomialPlanReplayReceiptArtifactVerifier
        receiptVerifier =
            new ExactFinitePolynomialPlanReplayReceiptArtifactVerifier();
    private final ExactFinitePolynomialPlanReplayConfirmationVerifier
        confirmationVerifier =
            new ExactFinitePolynomialPlanReplayConfirmationVerifier();
    private final ExactFinitePolynomialPlanCandidateEvidenceVerifier
        evidenceVerifier =
            new ExactFinitePolynomialPlanCandidateEvidenceVerifier();
    private final BudgetedTransformationSourceExecutor executor =
        new BudgetedTransformationSourceExecutor();
    private final RewriteProgramInterpreter programInterpreter =
        new RewriteProgramInterpreter();

    @Test
    void exposesOnlyTheEvidenceSelectedCandidateUnderExplicitWorkAuthority() {
        Fixture fixture = fixture();
        VerifiedCandidateEvidence evidence = evidence(fixture);
        var source = new VerifiedFinitePolynomialCandidateSource(evidence);
        long required = evidence.data().canonicalWork().totalWorkUnits();
        String canonicalSource = evidence.data().sourceExpression();

        var execution = executor.execute(source, canonicalSource, required);

        assertEquals(Status.CANDIDATES, execution.status());
        assertTrue(execution.complete());
        assertEquals(evidence.evidenceHash(), source.evidenceHash());
        assertEquals(evidence.evidenceHash(),
            execution.sourceIdentity().authorityHash());
        assertEquals(1, execution.candidates().size());
        var candidate = execution.candidates().getFirst();
        assertEquals(canonicalSource, candidate.sourceExpression());
        assertEquals(evidence.data().transformedExpression(),
            candidate.transformedExpression());
        assertEquals(evidence.data().theoryStepId(), candidate.theoryStepId());
        assertEquals(evidence.evidenceHash(), candidate.evidenceHash());
        assertEquals(required, candidate.mathematicalWorkUnits());
        assertTrue(candidate.assumptions().isEmpty());
        assertEquals(3,
            execution.sourceResult().mechanicalWorkUnits());
        assertEquals(8,
            execution.mechanicalWork().totalMechanicalWorkUnits());
        assertEquals(
            execution,
            executor.execute(source, canonicalSource, required));
    }

    @Test
    void executesVerifiedCandidateThroughExplicitTopLevelProgramSource() {
        Fixture fixture = fixture();
        VerifiedCandidateEvidence evidence = evidence(fixture);
        var source = new VerifiedFinitePolynomialCandidateSource(evidence);
        var program = budgetedSource("verified-finite-plan", source);
        long required = evidence.data().canonicalWork().totalWorkUnits();
        String canonicalSource = evidence.data().sourceExpression();

        var execution = programInterpreter.executeBudgetedSource(
            program,
            canonicalSource,
            required);

        assertEquals(Status.CANDIDATES, execution.status());
        assertTrue(execution.complete());
        assertEquals(evidence.evidenceHash(),
            execution.sourceExecution().sourceIdentity().authorityHash());
        assertEquals(1, execution.candidates().size());
        var candidate = execution.candidates().getFirst();
        assertEquals("verified-finite-plan", candidate.originNodeId());
        assertEquals(canonicalSource, candidate.sourceExpression());
        assertEquals(evidence.data().transformedExpression(),
            candidate.transformedExpression());
        assertEquals(evidence.data().theoryStepId(), candidate.theoryStepId());
        assertEquals(evidence.evidenceHash(), candidate.evidenceHash());
        assertEquals(required, candidate.mathematicalWorkUnits());
        assertEquals(0, candidate.primitiveRewriteSteps());
        assertEquals(1, candidate.exactTheorySteps());
        assertEquals(8,
            execution.programWork().delegatedMechanicalWorkUnits());
        assertEquals(12,
            execution.programWork().totalMechanicalWorkUnits());
        assertEquals(
            execution,
            programInterpreter.executeBudgetedSource(
                program,
                canonicalSource,
                required));

        assertThrows(
            IllegalArgumentException.class,
            () -> programInterpreter.execute(program, canonicalSource));
    }

    @Test
    void distinguishesSourceMismatchFromInsufficientMathematicalBudget() {
        Fixture fixture = fixture();
        VerifiedCandidateEvidence evidence = evidence(fixture);
        var source = new VerifiedFinitePolynomialCandidateSource(evidence);
        long required = evidence.data().canonicalWork().totalWorkUnits();
        String canonicalSource = evidence.data().sourceExpression();

        var mismatch = executor.execute(source, "y", required);
        assertEquals(Status.NO_MATCH, mismatch.status());
        assertTrue(mismatch.complete());
        assertTrue(mismatch.candidates().isEmpty());
        assertEquals("SOURCE_MISMATCH",
            mismatch.sourceResult().detailCode());
        assertEquals(1, mismatch.sourceResult().mechanicalWorkUnits());

        var insufficient = executor.execute(
            source,
            canonicalSource,
            required - 1);
        assertEquals(Status.BUDGET_INCONCLUSIVE, insufficient.status());
        assertFalse(insufficient.complete());
        assertTrue(insufficient.candidates().isEmpty());
        assertEquals(required,
            insufficient.sourceResult()
                .minimumRequiredMathematicalWorkUnits());
        assertEquals(2,
            insufficient.sourceResult().mechanicalWorkUnits());
    }

    private VerifiedCandidateEvidence evidence(Fixture fixture) {
        VerifiedReplayReceiptArtifact receiptArtifact = receiptVerifier.verify(
            verifiedReceiptBytes(fixture.receipt()));
        ConfirmedReplay confirmation = confirmationVerifier.verify(
            receiptArtifact,
            verifiedPlanRunBytes(fixture.run()),
            fixture.run(),
            fixture.plan(),
            fixture.source(),
            fixture.ansatz(),
            fixture.domains(),
            fixture.retainedSolutionLimit());
        String selectedHash = fixture.run().candidates().stream()
            .map(ExactFinitePolynomialResolvedCandidate::contentHash)
            .sorted(Comparator.naturalOrder())
            .findFirst()
            .orElseThrow();
        return evidenceVerifier.verify(
            confirmation,
            fixture.plan(),
            fixture.run(),
            selectedHash);
    }

    private VerifiedArtifactBytes verifiedReceiptBytes(ReplayReceipt receipt) {
        ArtifactReference reference = byteVerifier.describeReceipt(receipt);
        byte[] bytes = receipt.toCanonicalJson()
            .getBytes(StandardCharsets.UTF_8);
        return byteVerifier.verifyReceipt(
            reference,
            ignored -> new LoadedArtifact(reference.artifactId(), bytes));
    }

    private VerifiedArtifactBytes verifiedPlanRunBytes(
        ExactFinitePolynomialPlanRun run
    ) {
        ArtifactReference reference = byteVerifier.describePlanRun(run);
        byte[] bytes = run.toCanonicalJson().getBytes(StandardCharsets.UTF_8);
        return byteVerifier.verifyPlanRun(
            reference,
            ignored -> new LoadedArtifact(reference.artifactId(), bytes));
    }

    private Fixture fixture() {
        String source = "x*x";
        String ansatz = "(${sign}*x)^2";
        List<HoleDomain> domains = List.of(HoleDomain.signs("sign"));
        int retainedSolutionLimit = 2;
        SchematicProofPlan plan = resolver.createPlan(
            "budgeted-candidate-source",
            source,
            ansatz,
            domains,
            retainedSolutionLimit,
            LIMITS);
        ExactFinitePolynomialPlanRun run = resolver.resolve(
            plan,
            source,
            ansatz,
            domains,
            retainedSolutionLimit);
        ReplayReceipt receipt = replayVerifier.verify(
            plan,
            source,
            ansatz,
            domains,
            retainedSolutionLimit,
            run);
        return new Fixture(
            source,
            ansatz,
            domains,
            retainedSolutionLimit,
            plan,
            run,
            receipt);
    }

    private record Fixture(
        String source,
        String ansatz,
        List<HoleDomain> domains,
        int retainedSolutionLimit,
        SchematicProofPlan plan,
        ExactFinitePolynomialPlanRun run,
        ReplayReceipt receipt
    ) {}
}
