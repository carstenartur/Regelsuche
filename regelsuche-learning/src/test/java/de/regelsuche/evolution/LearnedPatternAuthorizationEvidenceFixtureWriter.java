package de.regelsuche.evolution;

import de.regelsuche.transform.RewriteKind;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/** Writes one deterministic, independently verifiable authorization evidence bundle. */
public final class LearnedPatternAuthorizationEvidenceFixtureWriter {
    private static final String REVISION =
        "0123456789abcdef0123456789abcdef01234567";
    private static final Instant ISSUED = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant AUTHORIZED = Instant.parse("2026-09-10T00:00:00Z");
    private static final Instant EXPIRES = Instant.parse("2026-10-01T00:00:00Z");

    private LearnedPatternAuthorizationEvidenceFixtureWriter() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            throw new IllegalArgumentException("usage: <output-directory>");
        }
        Path output = Path.of(args[0]).toAbsolutePath().normalize();
        reset(output);

        EvolutionSplitManifest split = split();
        EvolutionGenome genome = genome(split);
        EvolutionValidationSelection validation = validation(split, genome);
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

        Path bundleFile = output.resolve("authorization-bundle.json");
        Path splitFile = output.resolve("split-manifest.json");
        Path validationFile = output.resolve("validation-selection.json");
        Path finalFile = output.resolve("final-test-evaluation.json");
        Path counterexampleFile = output.resolve("counterexample-evidence.json");
        write(bundleFile, bundle.toCanonicalJson());
        write(splitFile, split.toCanonicalJson());
        write(validationFile, validation.toCanonicalJson());
        write(finalFile, finalTest.toCanonicalJson());
        write(counterexampleFile, counterexample.toCanonicalJson());

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
        write(
            output.resolve("authorization-receipt.json"),
            authorization.receipt().toCanonicalJson());

        System.out.println("learnedPatternAuthorizationFixture=" + output);
        System.out.println(
            "learnedPatternAuthorizationReceipt="
                + authorization.receipt().contentHash());
    }

    private static EvolutionSplitManifest split() {
        return EvolutionSplitManifest.create(
            "authorization-fixture",
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
                "final", "final-family", hash("fixture-final-material"))));
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
            reservation, suite, List.of(evidence));
    }

    private static void reset(Path output) throws IOException {
        if (Files.exists(output)) {
            try (var paths = Files.walk(output)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    if (!path.equals(output)) {
                        Files.delete(path);
                    }
                }
            }
        }
        Files.createDirectories(output);
    }

    private static void write(Path path, String content) throws IOException {
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    private static String hash(String value) {
        return EvolutionGenome.hash(value);
    }
}
