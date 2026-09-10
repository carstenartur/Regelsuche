package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.transform.RewriteKind;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LearnedPatternStoredAuthorizationReplayTest {
    private static final String REVISION =
        "0123456789abcdef0123456789abcdef01234567";
    private static final Instant ISSUED = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant AUTHORIZED = Instant.parse("2026-09-10T00:00:00Z");
    private static final Instant LATER = Instant.parse("2026-09-15T00:00:00Z");
    private static final Instant EXPIRES = Instant.parse("2026-10-01T00:00:00Z");

    @Test
    void reloadsReceiptAndReplaysEveryBoundAuthority(@TempDir Path temporary)
            throws IOException {
        Fixture fixture = fixture(temporary);
        LearnedPatternRuleAuthorizationService service =
            new LearnedPatternRuleAuthorizationService();
        LearnedPatternRuleAuthorizationService.Authorization issued =
            service.authorize(
                fixture.genome(),
                "difference-squares",
                REVISION,
                fixture.files(),
                AUTHORIZED);
        Path receipt = temporary.resolve("authorization-receipt.json");
        Files.writeString(
            receipt,
            issued.receipt().toCanonicalJson(),
            StandardCharsets.UTF_8);

        LearnedPatternRuleAuthorizationService.Authorization replayed =
            service.verifyAuthorization(
                fixture.genome(),
                "difference-squares",
                REVISION,
                fixture.files(),
                receipt,
                LATER);

        assertEquals(issued.receipt(), replayed.receipt());
        assertEquals(
            issued.promotion().receipt().contentHash(),
            replayed.promotion().receipt().contentHash());
    }

    @Test
    void rejectsAValidButSubstitutedRootAfterReceiptIssuance(@TempDir Path temporary)
            throws IOException {
        Fixture fixture = fixture(temporary);
        LearnedPatternRuleAuthorizationService service =
            new LearnedPatternRuleAuthorizationService();
        LearnedPatternRuleAuthorizationService.Authorization issued =
            service.authorize(
                fixture.genome(),
                "difference-squares",
                REVISION,
                fixture.files(),
                AUTHORIZED);
        Path receipt = temporary.resolve("authorization-receipt.json");
        Files.writeString(
            receipt,
            issued.receipt().toCanonicalJson(),
            StandardCharsets.UTF_8);

        EvolutionValidationSelection replacement = validation(
            fixture.split(), fixture.genome(), "replacement-validation-suite");
        Files.writeString(
            fixture.files().validationSelection(),
            replacement.toCanonicalJson(),
            StandardCharsets.UTF_8);

        IllegalArgumentException failure = assertThrows(
            IllegalArgumentException.class,
            () -> service.verifyAuthorization(
                fixture.genome(),
                "difference-squares",
                REVISION,
                fixture.files(),
                receipt,
                LATER));
        assertTrue(failure.getMessage().contains("validation selection hash mismatch"));
    }

    @Test
    void rejectsAStoredReceiptAtItsExpiryBoundary(@TempDir Path temporary)
            throws IOException {
        Fixture fixture = fixture(temporary);
        LearnedPatternRuleAuthorizationService service =
            new LearnedPatternRuleAuthorizationService();
        LearnedPatternRuleAuthorizationService.Authorization issued =
            service.authorize(
                fixture.genome(),
                "difference-squares",
                REVISION,
                fixture.files(),
                AUTHORIZED);
        Path receipt = temporary.resolve("authorization-receipt.json");
        Files.writeString(
            receipt,
            issued.receipt().toCanonicalJson(),
            StandardCharsets.UTF_8);

        IllegalArgumentException failure = assertThrows(
            IllegalArgumentException.class,
            () -> service.verifyAuthorization(
                fixture.genome(),
                "difference-squares",
                REVISION,
                fixture.files(),
                receipt,
                EXPIRES));
        assertTrue(failure.getMessage().contains("not valid"));
    }

    private static Fixture fixture(Path directory) throws IOException {
        EvolutionSplitManifest split = EvolutionSplitManifest.create(
            "authorization-replay-study",
            hash("corpus"),
            hash("features"),
            List.of(caseReference("train", "train-family")),
            List.of(caseReference("validation", "validation-family")),
            List.of(caseReference("final", "final-family")));
        EvolutionGenome genome = EvolutionGenome.create(
            EvolutionGenome.Objective.OPEN_TARGET_OPERATOR,
            split.trainingScope(),
            List.of(new EvolutionGenome.RewriteGene(
                "difference-squares",
                "?A^2-?B^2",
                "(?A-?B)*(?A+?B)",
                RewriteKind.FACTOR,
                true,
                -1,
                4,
                8,
                List.of(),
                List.of(
                    EvolutionGenome.EvidenceObligation.SEMANTIC_VALIDATION,
                    EvolutionGenome.EvidenceObligation.COUNTEREXAMPLE_SEARCH,
                    EvolutionGenome.EvidenceObligation.PROOF_OR_CERTIFICATE,
                    EvolutionGenome.EvidenceObligation.HOLDOUT_EVALUATION))),
            List.of(new EvolutionGenome.FeatureWeight(
                EvolutionGenome.FitnessSignal.UNSEEN_TRAIN_CASES_SOLVED,
                1_000)),
            EvolutionGenome.GuardPolicy.strictDefault(),
            EvolutionGenome.ResourceBudget.conservativeDefault(),
            List.of("symbolic-polynomial"),
            List.of());
        EvolutionValidationSelection validation = validation(
            split, genome, "validation-suite");
        EvolutionFinalTestEvaluation finalTest = finalTest(split, validation);
        LearnedPatternRuleAuthorizationService service =
            new LearnedPatternRuleAuthorizationService();
        LearnedPatternCounterexampleEvidence counterexample =
            service.evaluateCounterexamples(
                genome, "difference-squares", REVISION);
        LearnedPatternAuthorizationBundle bundle =
            LearnedPatternAuthorizationBundle.create(
                genome,
                "difference-squares",
                REVISION,
                ISSUED,
                EXPIRES,
                split,
                validation,
                finalTest,
                counterexample);

        Files.createDirectories(directory);
        Path bundleFile = directory.resolve("authorization-bundle.json");
        Path splitFile = directory.resolve("split-manifest.json");
        Path validationFile = directory.resolve("validation-selection.json");
        Path finalFile = directory.resolve("final-test-evaluation.json");
        Path counterexampleFile = directory.resolve("counterexample-evidence.json");
        Files.writeString(bundleFile, bundle.toCanonicalJson(), StandardCharsets.UTF_8);
        Files.writeString(splitFile, split.toCanonicalJson(), StandardCharsets.UTF_8);
        Files.writeString(
            validationFile, validation.toCanonicalJson(), StandardCharsets.UTF_8);
        Files.writeString(finalFile, finalTest.toCanonicalJson(), StandardCharsets.UTF_8);
        Files.writeString(
            counterexampleFile,
            counterexample.toCanonicalJson(),
            StandardCharsets.UTF_8);
        return new Fixture(
            split,
            genome,
            new LearnedPatternRuleAuthorizationService.EvidenceFiles(
                bundleFile,
                splitFile,
                validationFile,
                finalFile,
                counterexampleFile));
    }

    private static EvolutionValidationSelection validation(
        EvolutionSplitManifest split,
        EvolutionGenome genome,
        String suiteIdentity
    ) {
        EvolutionValidationCaseEvidence evidence =
            new EvolutionValidationCaseEvidence(
                "validation",
                "validation-family",
                false,
                true,
                EvolutionCorrectnessStatus.NOT_EVALUATED,
                EvolutionCorrectnessStatus.CONFIRMED,
                "FRONTIER_EXHAUSTED",
                "TARGET_REACHED",
                -1,
                2,
                10,
                10,
                5,
                5,
                true,
                false,
                false,
                false);
        EvolutionValidationCandidate candidate =
            EvolutionValidationCandidate.create(
                genome.contentHash(),
                genome.alphaStructuralHash(),
                new EvolutionValidationSearchConfiguration(5, 200, 12),
                List.of(evidence),
                List.of());
        return EvolutionValidationSelection.create(
            hash("study-plan"),
            split.contentHash(),
            hash("train-population"),
            hash(suiteIdentity),
            List.of("validation"),
            List.of(candidate));
    }

    private static EvolutionFinalTestEvaluation finalTest(
        EvolutionSplitManifest split,
        EvolutionValidationSelection validation
    ) {
        EvolutionFinalTestSuite suite = EvolutionFinalTestSuite.create(
            validation.studyPlanHash(),
            split.contentHash(),
            hash("baseline"),
            List.of(new EvolutionFinalTestSuite.CaseDefinition(
                "final", "final-family", hash("final-material"))));
        EvolutionFinalTestReservation reservation =
            EvolutionFinalTestReservation.create(validation, suite);
        EvolutionFinalTestCaseEvidence evidence =
            EvolutionFinalTestCaseEvidence.create(
                suite.cases().getFirst(),
                new EvolutionFinalTestMeasurement(
                    EvolutionFinalTestMeasurement.Status.COMPLETED,
                    false,
                    "FRONTIER_EXHAUSTED",
                    -1,
                    10,
                    5,
                    EvolutionCorrectnessStatus.NOT_EVALUATED,
                    ""),
                new EvolutionFinalTestMeasurement(
                    EvolutionFinalTestMeasurement.Status.COMPLETED,
                    true,
                    "TARGET_REACHED",
                    2,
                    10,
                    5,
                    EvolutionCorrectnessStatus.CONFIRMED,
                    hash("selected-result")));
        return EvolutionFinalTestEvaluation.create(
            reservation, suite, List.of(evidence));
    }

    private static EvolutionSplitManifest.CaseReference caseReference(
        String id,
        String family
    ) {
        return new EvolutionSplitManifest.CaseReference(
            id,
            family,
            hash(id + "-exact"),
            hash(id + "-alpha"),
            hash(id + "-input"),
            hash(id + "-target"));
    }

    private static String hash(String value) {
        return EvolutionGenome.hash(value);
    }

    private record Fixture(
        EvolutionSplitManifest split,
        EvolutionGenome genome,
        LearnedPatternRuleAuthorizationService.EvidenceFiles files
    ) {
    }
}
