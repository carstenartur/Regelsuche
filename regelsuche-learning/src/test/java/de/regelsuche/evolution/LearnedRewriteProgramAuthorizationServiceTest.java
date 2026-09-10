package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.evolution.EvolutionRewriteProgramPlan.Source;
import de.regelsuche.transform.RewriteKind;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LearnedRewriteProgramAuthorizationServiceTest {
    private static final String REVISION =
        "0123456789abcdef0123456789abcdef01234567";
    private static final String OTHER_REVISION =
        "89abcdef0123456789abcdef0123456789abcdef";
    private static final Instant ISSUED = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant AUTHORIZED = Instant.parse("2026-09-10T00:00:00Z");
    private static final Instant EXPIRES = Instant.parse("2026-10-01T00:00:00Z");

    @Test
    void authorizesAndReplaysStoredProgramFromVerifiedLeaf(@TempDir Path root)
            throws IOException {
        LeafFixture leaf = authorizedLeaf(root.resolve("leaf"));
        EvolutionRewriteProgramCandidate candidate = candidate(leaf.genome());
        LearnedRewriteProgramAuthorizationService service =
            new LearnedRewriteProgramAuthorizationService();
        LearnedRewriteProgramReplayEvidence replay = service.evaluateReplay(
            candidate,
            List.of(leaf.authorization()),
            List.of(new LearnedRewriteProgramReplayEvidence.ReplayInput(
                "difference_squares_case",
                "x^2-y^2")),
            REVISION,
            AUTHORIZED);

        var authorization = service.authorize(
            candidate,
            List.of(leaf.authorization()),
            replay,
            REVISION,
            AUTHORIZED);

        assertEquals(EXPIRES, authorization.receipt().validUntil());
        assertEquals(
            leaf.authorization().receipt().contentHash(),
            authorization.receipt().leafAuthorizationHashes()
                .get("difference-squares"));
        assertEquals(replay.contentHash(),
            authorization.receipt().replayEvidenceHash());
        assertEquals(candidate.contentHash(),
            authorization.receipt().candidateHash());
        assertTrue(authorization.compiledProgram().engine()
            .transform("x^2-y^2").getFirst().rule()
            .startsWith("learned.promoted."));
        assertEquals(1,
            replay.cases().getFirst().candidates().getFirst()
                .executionWork().primitiveRewrites());

        LearnedRewriteProgramReplayEvidence loadedReplay =
            LearnedRewriteProgramReplayEvidence.fromCanonicalJson(
                replay.toCanonicalJson());
        LearnedRewriteProgramAuthorizationReceipt loadedReceipt =
            LearnedRewriteProgramAuthorizationReceipt.fromCanonicalJson(
                authorization.receipt().toCanonicalJson());
        var replayed = service.replayStoredAuthorization(
            candidate,
            List.of(leaf.authorization()),
            loadedReplay,
            loadedReceipt,
            REVISION,
            AUTHORIZED.plusSeconds(1));

        assertEquals(authorization.receipt(), replayed.receipt());
        assertEquals(replay, replayed.replayEvidence());
    }

    @Test
    void rejectsMissingLeafWrongRevisionExpiryAndPlanSubstitution(
        @TempDir Path root
    ) throws IOException {
        LeafFixture leaf = authorizedLeaf(root.resolve("leaf"));
        EvolutionRewriteProgramCandidate candidate = candidate(leaf.genome());
        LearnedRewriteProgramAuthorizationService service =
            new LearnedRewriteProgramAuthorizationService();
        LearnedRewriteProgramReplayEvidence replay = service.evaluateReplay(
            candidate,
            List.of(leaf.authorization()),
            List.of(new LearnedRewriteProgramReplayEvidence.ReplayInput(
                "difference_squares_case",
                "x^2-y^2")),
            REVISION,
            AUTHORIZED);
        var authorization = service.authorize(
            candidate,
            List.of(leaf.authorization()),
            replay,
            REVISION,
            AUTHORIZED);

        assertThrows(IllegalArgumentException.class, () ->
            service.authorize(
                candidate,
                List.of(),
                replay,
                REVISION,
                AUTHORIZED));
        assertThrows(IllegalArgumentException.class, () ->
            service.replayStoredAuthorization(
                candidate,
                List.of(leaf.authorization()),
                replay,
                authorization.receipt(),
                OTHER_REVISION,
                AUTHORIZED.plusSeconds(1)));
        assertThrows(IllegalArgumentException.class, () ->
            service.replayStoredAuthorization(
                candidate,
                List.of(leaf.authorization()),
                replay,
                authorization.receipt(),
                REVISION,
                EXPIRES));

        EvolutionRewriteProgramPlan substitutedPlan =
            EvolutionRewriteProgramPlan.create(
                leaf.genome(),
                new EvolutionRewriteProgramPlan.Repeat(
                    "substituted_repeat",
                    new Source(
                        "difference_squares_source",
                        List.of("difference-squares")),
                    1,
                    2),
                4,
                4);
        EvolutionRewriteProgramCandidate substituted =
            EvolutionRewriteProgramCandidate.create(
                leaf.genome(), substitutedPlan);
        assertThrows(IllegalArgumentException.class, () ->
            service.replayStoredAuthorization(
                substituted,
                List.of(leaf.authorization()),
                replay,
                authorization.receipt(),
                REVISION,
                AUTHORIZED.plusSeconds(1)));
    }

    @Test
    void receiptJsonRejectsTampering(@TempDir Path root) throws IOException {
        LeafFixture leaf = authorizedLeaf(root.resolve("leaf"));
        EvolutionRewriteProgramCandidate candidate = candidate(leaf.genome());
        LearnedRewriteProgramAuthorizationService service =
            new LearnedRewriteProgramAuthorizationService();
        LearnedRewriteProgramReplayEvidence replay = service.evaluateReplay(
            candidate,
            List.of(leaf.authorization()),
            List.of(new LearnedRewriteProgramReplayEvidence.ReplayInput(
                "difference_squares_case",
                "x^2-y^2")),
            REVISION,
            AUTHORIZED);
        var receipt = service.authorize(
            candidate,
            List.of(leaf.authorization()),
            replay,
            REVISION,
            AUTHORIZED).receipt();

        assertEquals(
            receipt,
            LearnedRewriteProgramAuthorizationReceipt.fromCanonicalJson(
                receipt.toCanonicalJson()));
        String tampered = receipt.toCanonicalJson().replace(
            receipt.contentHash(),
            EvolutionGenome.hash("tampered-program-receipt"));
        assertThrows(IllegalArgumentException.class, () ->
            LearnedRewriteProgramAuthorizationReceipt.fromCanonicalJson(
                tampered));
    }

    private static EvolutionRewriteProgramCandidate candidate(
        EvolutionGenome genome
    ) {
        EvolutionRewriteProgramPlan plan = EvolutionRewriteProgramPlan.create(
            genome,
            new Source(
                "difference_squares_source",
                List.of("difference-squares")),
            4,
            4);
        return EvolutionRewriteProgramCandidate.create(genome, plan);
    }

    private static LeafFixture authorizedLeaf(Path output) throws IOException {
        Files.createDirectories(output);
        EvolutionSplitManifest split = split();
        EvolutionGenome genome = genome(split);
        EvolutionValidationSelection validation = validation(split, genome);
        EvolutionFinalTestEvaluation finalTest = finalTest(split, validation);
        LearnedPatternRuleAuthorizationService service =
            new LearnedPatternRuleAuthorizationService();
        LearnedPatternCounterexampleEvidence counterexample =
            service.evaluateCounterexamples(
                genome,
                "difference-squares",
                REVISION);
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

        Path bundleFile = write(
            output.resolve("authorization-bundle.json"),
            bundle.toCanonicalJson());
        Path splitFile = write(
            output.resolve("split-manifest.json"),
            split.toCanonicalJson());
        Path validationFile = write(
            output.resolve("validation-selection.json"),
            validation.toCanonicalJson());
        Path finalFile = write(
            output.resolve("final-test-evaluation.json"),
            finalTest.toCanonicalJson());
        Path counterexampleFile = write(
            output.resolve("counterexample-evidence.json"),
            counterexample.toCanonicalJson());

        LearnedPatternRuleAuthorizationService.Authorization authorization =
            service.authorize(
                genome,
                "difference-squares",
                REVISION,
                new LearnedPatternRuleAuthorizationService.EvidenceFiles(
                    bundleFile,
                    splitFile,
                    validationFile,
                    finalFile,
                    counterexampleFile),
                AUTHORIZED);
        return new LeafFixture(genome, authorization);
    }

    private static EvolutionSplitManifest split() {
        return EvolutionSplitManifest.create(
            "program-authorization-fixture",
            hash("fixture-corpus"),
            hash("fixture-features"),
            List.of(caseReference("train", "train-family")),
            List.of(caseReference("validation", "validation-family")),
            List.of(caseReference("final", "final-family")));
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

    private static EvolutionGenome genome(EvolutionSplitManifest split) {
        return EvolutionGenome.create(
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
    }

    private static EvolutionValidationSelection validation(
        EvolutionSplitManifest split,
        EvolutionGenome genome
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
            hash("fixture-study-plan"),
            split.contentHash(),
            hash("fixture-train-population"),
            hash("fixture-validation-suite"),
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
            hash("fixture-baseline"),
            List.of(new EvolutionFinalTestSuite.CaseDefinition(
                "final",
                "final-family",
                hash("fixture-final-material"))));
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
                    hash("fixture-selected-result")));
        return EvolutionFinalTestEvaluation.create(
            reservation,
            suite,
            List.of(evidence));
    }

    private static Path write(Path path, String content) throws IOException {
        Files.writeString(path, content, StandardCharsets.UTF_8);
        return path;
    }

    private static String hash(String value) {
        return EvolutionGenome.hash(value);
    }

    private record LeafFixture(
        EvolutionGenome genome,
        LearnedPatternRuleAuthorizationService.Authorization authorization
    ) {
    }
}
