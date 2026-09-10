package de.regelsuche.evolution;

import de.regelsuche.evolution.EvolutionRewriteProgramPlan.Choice;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.Prune;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.Prioritize;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.Priority;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.Repeat;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.Require;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.Requirement;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.Sequence;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.Source;
import de.regelsuche.transform.RewriteKind;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/** Writes one deterministic learned-program authorization evidence bundle. */
public final class LearnedRewriteProgramAuthorizationEvidenceFixtureWriter {
    private static final String REVISION =
        "0123456789abcdef0123456789abcdef01234567";
    private static final Instant ISSUED = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant AUTHORIZED = Instant.parse("2026-09-10T00:00:00Z");
    private static final Instant EXPIRES = Instant.parse("2026-10-01T00:00:00Z");

    private LearnedRewriteProgramAuthorizationEvidenceFixtureWriter() {
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
        LeafAuthorization multiply = authorizeLeaf(
            output.resolve("leaf-mul-one"),
            genome,
            split,
            validation,
            finalTest,
            "mul-one");
        LeafAuthorization add = authorizeLeaf(
            output.resolve("leaf-add-zero"),
            genome,
            split,
            validation,
            finalTest,
            "add-zero");

        EvolutionRewriteProgramPlan plan = plan(genome);
        EvolutionRewriteProgramCandidate candidate =
            EvolutionRewriteProgramCandidate.create(genome, plan);
        LearnedRewriteProgramAuthorizationService service =
            new LearnedRewriteProgramAuthorizationService();
        List<LearnedPatternRuleAuthorizationService.Authorization> leaves =
            List.of(multiply.authorization(), add.authorization());
        LearnedRewriteProgramReplayEvidence replay = service.evaluateReplay(
            candidate,
            leaves,
            List.of(new LearnedRewriteProgramReplayEvidence.ReplayInput(
                "composite-normalization",
                "(x * 1) + 0")),
            REVISION,
            AUTHORIZED);
        var authorization = service.authorize(
            candidate,
            leaves,
            replay,
            REVISION,
            AUTHORIZED);

        write(output.resolve("genome.json"), genome.toCanonicalJson());
        write(output.resolve("program-plan.json"), plan.toCanonicalJson());
        write(output.resolve("program-candidate.json"), candidate.toCanonicalJson());
        write(output.resolve("program-replay-evidence.json"), replay.toCanonicalJson());
        write(
            output.resolve("program-authorization-receipt.json"),
            authorization.receipt().toCanonicalJson());
        write(
            output.resolve("leaf-mul-one-authorization-receipt.json"),
            multiply.authorization().receipt().toCanonicalJson());
        write(
            output.resolve("leaf-add-zero-authorization-receipt.json"),
            add.authorization().receipt().toCanonicalJson());

        System.out.println("learnedRewriteProgramAuthorizationFixture=" + output);
        System.out.println(
            "learnedRewriteProgramAuthorizationReceipt="
                + authorization.receipt().contentHash());
    }

    private static LeafAuthorization authorizeLeaf(
        Path output,
        EvolutionGenome genome,
        EvolutionSplitManifest split,
        EvolutionValidationSelection validation,
        EvolutionFinalTestEvaluation finalTest,
        String geneId
    ) throws IOException {
        Files.createDirectories(output);
        LearnedPatternRuleAuthorizationService service =
            new LearnedPatternRuleAuthorizationService();
        LearnedPatternCounterexampleEvidence counterexample =
            service.evaluateCounterexamples(genome, geneId, REVISION);
        LearnedPatternAuthorizationBundle bundle =
            LearnedPatternAuthorizationBundle.create(
                genome,
                geneId,
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
        var authorization = service.authorize(
            genome,
            geneId,
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
        return new LeafAuthorization(authorization);
    }

    private static EvolutionRewriteProgramPlan plan(EvolutionGenome genome) {
        return EvolutionRewriteProgramPlan.create(
            genome,
            new Prune(
                "keep_best",
                new Prioritize(
                    "cheap_first",
                    new Require(
                        "bounded_steps",
                        new Choice(
                            "routes",
                            List.of(
                                new Sequence(
                                    "two_step_normalization",
                                    List.of(
                                        new Source(
                                            "multiply_identity",
                                            List.of("mul-one")),
                                        new Source(
                                            "additive_identity",
                                            List.of("add-zero")))),
                                new Repeat(
                                    "repeat_additive_identity",
                                    new Source(
                                        "repeated_additive_identity",
                                        List.of("add-zero")),
                                    1,
                                    2))),
                        Requirement.maxPrimitiveSteps(2)),
                    Priority.estimatedCostThenRule()),
                1,
                "retain best deterministic candidate"),
            12,
            8);
    }

    private static EvolutionSplitManifest split() {
        return EvolutionSplitManifest.create(
            "program-authorization-evidence",
            hash("program-auth-corpus"),
            hash("program-auth-features"),
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
            List.of(
                gene("mul-one", "?A*1", "?A"),
                gene("add-zero", "?A+0", "?A")),
            List.of(new EvolutionGenome.FeatureWeight(
                EvolutionGenome.FitnessSignal.UNSEEN_TRAIN_CASES_SOLVED,
                1_000)),
            EvolutionGenome.GuardPolicy.strictDefault(),
            EvolutionGenome.ResourceBudget.conservativeDefault(),
            List.of("symbolic-polynomial"),
            List.of());
    }

    private static EvolutionGenome.RewriteGene gene(
        String id,
        String source,
        String target
    ) {
        return new EvolutionGenome.RewriteGene(
            id,
            source,
            target,
            RewriteKind.SIMPLIFY,
            true,
            -2,
            4,
            4,
            List.of(),
            List.of(
                EvolutionGenome.EvidenceObligation.SEMANTIC_VALIDATION,
                EvolutionGenome.EvidenceObligation.COUNTEREXAMPLE_SEARCH,
                EvolutionGenome.EvidenceObligation.PROOF_OR_CERTIFICATE,
                EvolutionGenome.EvidenceObligation.HOLDOUT_EVALUATION));
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
            hash("program-auth-study-plan"),
            split.contentHash(),
            hash("program-auth-train-population"),
            hash("program-auth-validation-suite"),
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
            hash("program-auth-baseline"),
            List.of(new EvolutionFinalTestSuite.CaseDefinition(
                "final",
                "final-family",
                hash("program-auth-final-material"))));
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
                    hash("program-auth-selected-result")));
        return EvolutionFinalTestEvaluation.create(
            reservation,
            suite,
            List.of(evidence));
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

    private static Path write(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
        return path;
    }

    private static String hash(String value) {
        return EvolutionGenome.hash(value);
    }

    private record LeafAuthorization(
        LearnedPatternRuleAuthorizationService.Authorization authorization
    ) {
    }
}
