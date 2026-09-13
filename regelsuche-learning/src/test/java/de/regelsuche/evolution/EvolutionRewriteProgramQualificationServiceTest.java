package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EvolutionRewriteProgramQualificationServiceTest {
    @TempDir Path directory;

    @Test
    void consumesNativeGatesForEveryExecutingGeneWithoutPromotingTheSelectedProgram() throws Exception {
        var fixture = ProgramFinalTestFixtures.create(directory);
        var evaluated = fixture.execute();
        var store = new FileEvolutionRewriteProgramFinalTestAttemptStore(fixture.finalDirectory());
        var service = new EvolutionRewriteProgramQualificationService();
        byte[] reservation = Files.readAllBytes(store.reservationPath(fixture.plan()));
        byte[] finalEvidence = Files.readAllBytes(store.evaluationPath(fixture.plan()));
        var assessed = service.assess(fixture.plan(), store, ProgramFinalTestFixtures.REVISION);
        assertEquals(evaluated, assessed.finalTest());
        assertTrue(assessed.nativeGatesPassed());
        assertEquals("PASSED", assessed.proofStatus().name());
        assertEquals("PASSED", assessed.counterexampleStatus().name());
        assertEquals(List.of("add_zero", "mul_one"), assessed.genes().stream()
            .map(EvolutionRewriteProgramQualificationAssessment.GeneAssessment::geneId).toList());
        assertTrue(assessed.genes().getFirst().referencedByProgram());
        assertFalse(assessed.genes().getLast().referencedByProgram());
        var genome = fixture.plan().selectedConfiguration().candidate().genome();
        for (var gene : assessed.genes()) {
            assertTrue(gene.proof().proved());
            assertEquals(new LearnedPatternRuleAuthorizationService().evaluateCounterexamples(
                genome, gene.geneId(), ProgramFinalTestFixtures.REVISION), gene.counterexample());
            assertEquals(LearnedPatternRuleAuthorizationService.authorizationCounterexampleBudget(),
                gene.counterexample().budget().toBudget());
        }
        assertEquals("NOT_EVALUATED", assessed.externalNoveltyStatus());
        assertEquals("NOT_EVALUATED", assessed.publicEvidenceStatus());
        assertEquals("NOT_EVALUATED", assessed.promotionStatus());
        assertEquals(assessed, service.verifyAssessment(assessed.toCanonicalJson(), fixture.plan(), store,
            ProgramFinalTestFixtures.REVISION));
        assertArrayEquals(reservation, Files.readAllBytes(store.reservationPath(fixture.plan())));
        assertArrayEquals(finalEvidence, Files.readAllBytes(store.evaluationPath(fixture.plan())));
    }

    @Test
    void successfulFinalSearchCannotReplaceExactProofOrCounterexampleQualification() throws Exception {
        var fixture = ProgramFinalTestFixtures.create(directory, "synthetic_refuted_program_final", true);
        var evaluated = fixture.execute();
        assertTrue(evaluated.qualificationEligible(), "ordinary correct paths are not a proof of the unused false gene");
        var assessed = new EvolutionRewriteProgramQualificationService().assess(fixture.plan(),
            new FileEvolutionRewriteProgramFinalTestAttemptStore(fixture.finalDirectory()), ProgramFinalTestFixtures.REVISION);
        assertFalse(assessed.nativeGatesPassed());
        assertEquals("REJECTED", assessed.proofStatus().name());
        assertEquals("REJECTED", assessed.counterexampleStatus().name());
        assertEquals(ExactPolynomialPatternIdentityVerifier.Status.NOT_EQUIVALENT, assessed.genes().getFirst().proof().status());
        assertEquals("NOT_EVALUATED", assessed.promotionStatus());
    }

    @Test
    void incompleteFinalEvidenceLeavesUnexecutedNativeGatesUnevaluated() throws Exception {
        var fixture = ProgramFinalTestFixtures.create(directory);
        var store = new FileEvolutionRewriteProgramFinalTestAttemptStore(fixture.finalDirectory());
        new EvolutionRewriteProgramFinalTestRunner().executeOnce(fixture.plan(), fixture.study(), fixture.manifest(),
            fixture.train(), fixture.validationStore(), () -> { throw new IOException("synthetic failure"); }, store);
        var assessed = new EvolutionRewriteProgramQualificationService().assess(
            fixture.plan(), store, ProgramFinalTestFixtures.REVISION);
        assertFalse(assessed.nativeGatesPassed());
        assertNull(assessed.preflight());
        assertEquals("NOT_EVALUATED", assessed.preflightStatus().name());
        assertEquals("NOT_EVALUATED", assessed.proofStatus().name());
        assertEquals("NOT_EVALUATED", assessed.counterexampleStatus().name());
        assertTrue(assessed.genes().stream().allMatch(item -> item.proof() == null && item.counterexample() == null));
        assertNull(assessed.finalTest().summary().candidateWorkUnits());
    }

    @Test
    void rejectsRehashedProofsOmittedGenesAndSubstitutedRevisionAgainstRealConsumers() throws Exception {
        var fixture = ProgramFinalTestFixtures.create(directory);
        fixture.execute();
        var store = new FileEvolutionRewriteProgramFinalTestAttemptStore(fixture.finalDirectory());
        var service = new EvolutionRewriteProgramQualificationService();
        var assessed = service.assess(fixture.plan(), store, ProgramFinalTestFixtures.REVISION);
        ObjectNode forged = EvolutionRewriteProgramFinalTestRunnerTest.object(assessed.toCanonicalJson());
        ((ObjectNode) forged.at("/genes/0/proof")).put("sourceCanonical", "forged_polynomial");
        EvolutionRewriteProgramFinalTestRunnerTest.rehash(forged);
        assertThrows(IllegalArgumentException.class, () -> service.verifyAssessment(
            forged.toString(), fixture.plan(), store, ProgramFinalTestFixtures.REVISION));
        ObjectNode omitted = EvolutionRewriteProgramFinalTestRunnerTest.object(assessed.toCanonicalJson());
        ((com.fasterxml.jackson.databind.node.ArrayNode) omitted.get("genes")).remove(1);
        EvolutionRewriteProgramFinalTestRunnerTest.rehash(omitted);
        assertThrows(IllegalArgumentException.class, () -> service.verifyAssessment(
            omitted.toString(), fixture.plan(), store, ProgramFinalTestFixtures.REVISION));
        var changedRevision = service.assess(fixture.plan(), store, "2".repeat(40));
        assertThrows(IllegalArgumentException.class, () -> service.verifyAssessment(
            changedRevision.toCanonicalJson(), fixture.plan(), store, ProgramFinalTestFixtures.REVISION));
        ObjectNode promoted = EvolutionRewriteProgramFinalTestRunnerTest.object(assessed.toCanonicalJson());
        promoted.put("promotionStatus", "QUALIFIED");
        EvolutionRewriteProgramFinalTestRunnerTest.rehash(promoted);
        assertThrows(IllegalArgumentException.class, () -> service.verifyAssessment(
            promoted.toString(), fixture.plan(), store, ProgramFinalTestFixtures.REVISION));
    }
}
