package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.transform.RewriteKind;
import de.regelsuche.validation.CounterexampleSearchService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LearnedPatternRuleAuthorizationServiceTest {
    private static final String REVISION =
        "0123456789abcdef0123456789abcdef01234567";
    private static final String OTHER_REVISION =
        "89abcdef0123456789abcdef0123456789abcdef";
    private static final Instant ISSUED = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant AS_OF = Instant.parse("2026-09-10T00:00:00Z");
    private static final Instant EXPIRES = Instant.parse("2026-10-01T00:00:00Z");

    @Test
    void authorizesOnlyAfterNativeEvidenceAndCounterexampleReplayPass(
        @TempDir Path temporary
    ) throws IOException {
        Fixture fixture = fixture(temporary, REVISION, ISSUED, EXPIRES);

        LearnedPatternRuleAuthorizationService.Authorization authorization =
            new LearnedPatternRuleAuthorizationService().authorize(
                fixture.genome(),
                "difference-squares",
                REVISION,
                fixture.files(),
                AS_OF);

        assertTrue(authorization.promotion().proof().proved());
        assertTrue(authorization.finalTestEvaluation().qualificationEligible());
        assertEquals(
            CounterexampleSearchService.Status.NO_COUNTEREXAMPLE_FOUND,
            authorization.counterexampleEvidence().status());
        assertEquals(
            fixture.validation().contentHash(),
            authorization.receipt().semanticValidationHash());
        assertEquals(
            fixture.counterexample().contentHash(),
            authorization.receipt().counterexampleSearchHash());
        assertEquals(
            fixture.holdout().contentHash(),
            authorization.receipt().holdoutEvaluationHash());
        assertEquals(
            fixture.split().contentHash(),
            authorization.receipt().leakageAuditHash());
        assertEquals(EXPIRES, authorization.receipt().validUntil());
        authorization.receipt().requireUsableAt(
            AS_OF,
            REVISION,
            authorization.receipt().promotedRuleHash());
    }

    @Test
    void rejectsExpiredFutureAndRevisionMismatchedBundles(@TempDir Path temporary)
            throws IOException {
        Fixture expired = fixture(
            temporary.resolve("expired"),
            REVISION,
            ISSUED,
            Instant.parse("2026-09-09T23:59:59Z"));
        IllegalArgumentException expiredFailure = assertThrows(
            IllegalArgumentException.class,
            () -> new LearnedPatternRuleAuthorizationService().authorize(
                expired.genome(), "difference-squares", REVISION,
                expired.files(), AS_OF));
        assertTrue(expiredFailure.getMessage().contains("not valid"));

        Fixture future = fixture(
            temporary.resolve("future"),
            REVISION,
            Instant.parse("2026-09-11T00:00:00Z"),
            EXPIRES);
        assertThrows(IllegalArgumentException.class,
            () -> new LearnedPatternRuleAuthorizationService().authorize(
                future.genome(), "difference-squares", REVISION,
                future.files(), AS_OF));

        Fixture wrongRevision = fixture(
            temporary.resolve("revision"), OTHER_REVISION, ISSUED, EXPIRES);
        IllegalArgumentException revisionFailure = assertThrows(
            IllegalArgumentException.class,
            () -> new LearnedPatternRuleAuthorizationService().authorize(
                wrongRevision.genome(), "difference-squares", REVISION,
                wrongRevision.files(), AS_OF));
        assertTrue(revisionFailure.getMessage().contains("subject/revision"));
    }

    @Test
    void rejectsAConsistentEvidenceChainForAnotherGenome(@TempDir Path temporary)
            throws IOException {
        Fixture fixture = fixture(temporary, REVISION, ISSUED, EXPIRES);
        EvolutionGenome another = genome(fixture.split(), "?A+0", "?A");
        EvolutionValidationSelection validation = validation(
            fixture.split(), another);
        EvolutionFinalTestEvaluation holdout = holdout(
            fixture.split(), validation, false);
        var service = new LearnedPatternRuleAuthorizationService();
        var counterexample = service.evaluateCounterexamples(
            fixture.genome(), "difference-squares", REVISION);
        var bundle = LearnedPatternRuleAuthorizationService.EvidenceBundle.create(
            fixture.genome(), "difference-squares", REVISION,
            ISSUED, EXPIRES, fixture.split(), validation, holdout,
            counterexample);
        var files = write(
            temporary.resolve("other-genome"), fixture.split(), validation,
            holdout, counterexample, bundle);

        IllegalArgumentException failure = assertThrows(
            IllegalArgumentException.class,
            () -> service.authorize(
                fixture.genome(), "difference-squares", REVISION,
                files, AS_OF));
        assertTrue(failure.getMessage().contains("VALIDATION did not select"));
    }

    @Test
    void rejectsSplitWhoseTrainScopeDoesNotMatchGenome(@TempDir Path temporary)
            throws IOException {
        Fixture fixture = fixture(temporary, REVISION, ISSUED, EXPIRES);
        EvolutionSplitManifest otherSplit = split("other-corpus");
        EvolutionValidationSelection validation = validation(
            otherSplit, fixture.genome());
        EvolutionFinalTestEvaluation holdout = holdout(
            otherSplit, validation, false);
        var service = new LearnedPatternRuleAuthorizationService();
        var counterexample = service.evaluateCounterexamples(
            fixture.genome(), "difference-squares", REVISION);
        var bundle = LearnedPatternRuleAuthorizationService.EvidenceBundle.create(
            fixture.genome(), "difference-squares", REVISION,
            ISSUED, EXPIRES, otherSplit, validation, holdout,
            counterexample);
        var files = write(
            temporary.resolve("scope"), otherSplit, validation,
            holdout, counterexample, bundle);

        IllegalArgumentException failure = assertThrows(
            IllegalArgumentException.class,
            () -> service.authorize(
                fixture.genome(), "difference-squares", REVISION,
                files, AS_OF));
        assertTrue(failure.getMessage().contains("TRAIN scope differs"));
    }

    @Test
    void rejectsFinalTestWithQualityBlockers(@TempDir Path temporary)
            throws IOException {
        Fixture fixture = fixture(temporary, REVISION, ISSUED, EXPIRES);
        EvolutionFinalTestEvaluation blocked = holdout(
            fixture.split(), fixture.validation(), true);
        var bundle = LearnedPatternRuleAuthorizationService.EvidenceBundle.create(
            fixture.genome(), "difference-squares", REVISION,
            ISSUED, EXPIRES, fixture.split(), fixture.validation(), blocked,
            fixture.counterexample());
        var files = write(
            temporary.resolve("blocked"), fixture.split(), fixture.validation(),
            blocked, fixture.counterexample(), bundle);

        IllegalArgumentException failure = assertThrows(
            IllegalArgumentException.class,
            () -> new LearnedPatternRuleAuthorizationService().authorize(
                fixture.genome(), "difference-squares", REVISION,
                files, AS_OF));
        assertTrue(failure.getMessage().contains("FINAL TEST contains"));
    }

    @Test
    void rejectsCounterexampleEvidenceThatDoesNotReplay(@TempDir Path temporary)
            throws IOException {
        EvolutionSplitManifest split = split("replay");
        EvolutionGenome genome = genome(split, "?A^2-?B^2", "(?A-?B)*(?A+?B)");
        CounterexampleSearchService fake = (hypothesis, budget) ->
            new CounterexampleSearchService.CounterexampleSearchResult(
                CounterexampleSearchService.Status.NO_COUNTEREXAMPLE_FOUND,
                Optional.empty(),
                List.of(),
                List.of("fake-source"),
                "fabricated no-refutation result",
                List.of());
        var fabricator = new LearnedPatternRuleAuthorizationService(
            new LearnedPatternRulePromoter(), fake,
            new EvolutionStudyContractCodec());
        var counterexample = fabricator.evaluateCounterexamples(
            genome, "difference-squares", REVISION);
        EvolutionValidationSelection validation = validation(split, genome);
        EvolutionFinalTestEvaluation holdout = holdout(split, validation, false);
        var bundle = LearnedPatternRuleAuthorizationService.EvidenceBundle.create(
            genome, "difference-squares", REVISION,
            ISSUED, EXPIRES, split, validation, holdout, counterexample);
        var files = write(
            temporary, split, validation, holdout, counterexample, bundle);

        IllegalArgumentException failure = assertThrows(
            IllegalArgumentException.class,
            () -> new LearnedPatternRuleAuthorizationService().authorize(
                genome, "difference-squares", REVISION, files, AS_OF));
        assertTrue(failure.getMessage().contains("deterministic replay"));
    }

    @Test
    void strictBundleAndCounterexampleCodecsRejectTampering(@TempDir Path temporary)
            throws IOException {
        Fixture fixture = fixture(temporary, REVISION, ISSUED, EXPIRES);
        String bundle = fixture.bundle().toCanonicalJson().trim();
        String duplicate = bundle.substring(0, bundle.length() - 1)
            + ",\"schema\":\""
            + LearnedPatternRuleAuthorizationService.BUNDLE_SCHEMA
            + "\"}";
        assertThrows(IllegalArgumentException.class,
            () -> LearnedPatternRuleAuthorizationService.EvidenceBundle
                .fromCanonicalJson(duplicate));

        String counterexample = fixture.counterexample().toCanonicalJson().trim();
        String unknown = counterexample.substring(0, counterexample.length() - 1)
            + ",\"unknown\":true}";
        assertThrows(IllegalArgumentException.class,
            () -> LearnedPatternRuleAuthorizationService.CounterexampleEvidence
                .fromCanonicalJson(unknown));

        Files.delete(fixture.files().finalTestEvaluation());
        assertThrows(IllegalArgumentException.class,
            () -> new LearnedPatternRuleAuthorizationService().authorize(
                fixture.genome(), "difference-squares", REVISION,
                fixture.files(), AS_OF));
    }

    @Test
    void authorizationReceiptExpiresAndIsBoundToRuleAndRevision(
        @TempDir Path temporary
    ) throws IOException {
        Fixture fixture = fixture(temporary, REVISION, ISSUED, EXPIRES);
        var authorization = new LearnedPatternRuleAuthorizationService().authorize(
            fixture.genome(), "difference-squares", REVISION,
            fixture.files(), AS_OF);

        assertThrows(IllegalArgumentException.class,
            () -> authorization.receipt().requireUsableAt(
                EXPIRES, REVISION,
                authorization.receipt().promotedRuleHash()));
        assertThrows(IllegalArgumentException.class,
            () -> authorization.receipt().requireUsableAt(
                AS_OF, OTHER_REVISION,
                authorization.receipt().promotedRuleHash()));
        assertThrows(IllegalArgumentException.class,
            () -> authorization.receipt().requireUsableAt(
                AS_OF, REVISION, hash("another-rule")));
    }

    private static Fixture fixture(
        Path directory,
        String revision,
        Instant issuedAt,
        Instant expiresAt
    ) throws IOException {
        EvolutionSplitManifest split = split("fixture");
        EvolutionGenome genome = genome(
            split, "?A^2-?B^2", "(?A-?B)*(?A+?B)");
        EvolutionValidationSelection validation = validation(split, genome);
        EvolutionFinalTestEvaluation holdout = holdout(split, validation, false);
        var service = new LearnedPatternRuleAuthorizationService();
        var counterexample = service.evaluateCounterexamples(
            genome, "difference-squares", revision);
        var bundle = LearnedPatternRuleAuthorizationService.EvidenceBundle.create(
            genome, "difference-squares", revision,
            issuedAt, expiresAt, split, validation, holdout, counterexample);
        var files = write(
            directory, split, validation, holdout, counterexample, bundle);
        return new Fixture(
            split, genome, validation, holdout, counterexample, bundle, files);
    }

    private static EvolutionSplitManifest split(String suffix) {
        return EvolutionSplitManifest.create(
            "authorization-study",
            hash("corpus-" + suffix),
            hash("features"),
            List.of(caseReference("train", "train-family", suffix)),
            List.of(caseReference("validation", "validation-family", suffix)),
            List.of(caseReference("final", "final-family", suffix)));
    }

    private static EvolutionSplitManifest.CaseReference caseReference(
        String id,
        String family,
        String suffix
    ) {
        return new EvolutionSplitManifest.CaseReference(
            id,
            family,
            hash(id + "-exact-" + suffix),
            hash(id + "-alpha-" + suffix),
            hash(id + "-input-" + suffix),
            hash(id + "-target-" + suffix));
    }

    private static EvolutionGenome genome(
        EvolutionSplitManifest split,
        String source,
        String target
    ) {
        return EvolutionGenome.create(
            EvolutionGenome.Objective.OPEN_TARGET_OPERATOR,
            split.trainingScope(),
            List.of(new EvolutionGenome.RewriteGene(
                "difference-squares",
                source,
                target,
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
                "validation", "validation-family", false, true,
                EvolutionCorrectnessStatus.NOT_EVALUATED,
                EvolutionCorrectnessStatus.CONFIRMED,
                "FRONTIER_EXHAUSTED", "TARGET_REACHED", -1, 2,
                10, 10, 5, 5, true, false, false, false);
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
            hash("validation-suite"),
            List.of("validation"),
            List.of(candidate));
    }

    private static EvolutionFinalTestEvaluation holdout(
        EvolutionSplitManifest split,
        EvolutionValidationSelection validation,
        boolean reachabilityRegression
    ) {
        EvolutionFinalTestSuite suite = EvolutionFinalTestSuite.create(
            validation.studyPlanHash(),
            split.contentHash(),
            hash("baseline"),
            List.of(new EvolutionFinalTestSuite.CaseDefinition(
                "final", "final-family", hash("final-material"))));
        EvolutionFinalTestReservation reservation =
            EvolutionFinalTestReservation.create(validation, suite);
        EvolutionFinalTestMeasurement baseline = measurement(
            reachabilityRegression,
            reachabilityRegression
                ? EvolutionCorrectnessStatus.CONFIRMED
                : EvolutionCorrectnessStatus.NOT_EVALUATED);
        EvolutionFinalTestMeasurement selected = measurement(
            !reachabilityRegression,
            !reachabilityRegression
                ? EvolutionCorrectnessStatus.CONFIRMED
                : EvolutionCorrectnessStatus.NOT_EVALUATED);
        EvolutionFinalTestCaseEvidence evidence =
            EvolutionFinalTestCaseEvidence.create(
                suite.cases().getFirst(), baseline, selected);
        return EvolutionFinalTestEvaluation.create(
            reservation, suite, List.of(evidence));
    }

    private static EvolutionFinalTestMeasurement measurement(
        boolean reached,
        EvolutionCorrectnessStatus correctness
    ) {
        return new EvolutionFinalTestMeasurement(
            EvolutionFinalTestMeasurement.Status.COMPLETED,
            reached,
            reached ? "TARGET_REACHED" : "FRONTIER_EXHAUSTED",
            reached ? 2 : -1,
            10,
            5,
            correctness,
            reached ? hash("result-" + correctness) : "");
    }

    private static LearnedPatternRuleAuthorizationService.EvidenceFiles write(
        Path directory,
        EvolutionSplitManifest split,
        EvolutionValidationSelection validation,
        EvolutionFinalTestEvaluation holdout,
        LearnedPatternRuleAuthorizationService.CounterexampleEvidence counterexample,
        LearnedPatternRuleAuthorizationService.EvidenceBundle bundle
    ) throws IOException {
        Files.createDirectories(directory);
        Path bundleFile = directory.resolve("authorization-bundle.json");
        Path splitFile = directory.resolve("split-manifest.json");
        Path validationFile = directory.resolve("validation-selection.json");
        Path holdoutFile = directory.resolve("final-test-evaluation.json");
        Path counterexampleFile = directory.resolve("counterexample-evidence.json");
        Files.writeString(
            bundleFile, bundle.toCanonicalJson(), StandardCharsets.UTF_8);
        Files.writeString(
            splitFile, split.toCanonicalJson(), StandardCharsets.UTF_8);
        Files.writeString(
            validationFile, validation.toCanonicalJson(), StandardCharsets.UTF_8);
        Files.writeString(
            holdoutFile, holdout.toCanonicalJson(), StandardCharsets.UTF_8);
        Files.writeString(
            counterexampleFile, counterexample.toCanonicalJson(),
            StandardCharsets.UTF_8);
        return new LearnedPatternRuleAuthorizationService.EvidenceFiles(
            bundleFile, splitFile, validationFile, holdoutFile,
            counterexampleFile);
    }

    private static String hash(String value) {
        return EvolutionGenome.hash(value);
    }

    private record Fixture(
        EvolutionSplitManifest split,
        EvolutionGenome genome,
        EvolutionValidationSelection validation,
        EvolutionFinalTestEvaluation holdout,
        LearnedPatternRuleAuthorizationService.CounterexampleEvidence counterexample,
        LearnedPatternRuleAuthorizationService.EvidenceBundle bundle,
        LearnedPatternRuleAuthorizationService.EvidenceFiles files
    ) {
    }
}
