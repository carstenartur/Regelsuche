package de.regelsuche.evolution;

import de.regelsuche.assumption.Assumption;
import de.regelsuche.evolution.DeterministicRewriteProgramMutator.MutationCatalog;
import de.regelsuche.evolution.DeterministicRewriteProgramMutator.RepeatBounds;
import de.regelsuche.evolution.EvolutionGenome.AssumptionTemplate;
import de.regelsuche.evolution.EvolutionGenome.EvidenceObligation;
import de.regelsuche.evolution.EvolutionGenome.FeatureWeight;
import de.regelsuche.evolution.EvolutionGenome.FitnessSignal;
import de.regelsuche.evolution.EvolutionGenome.GuardPolicy;
import de.regelsuche.evolution.EvolutionGenome.Objective;
import de.regelsuche.evolution.EvolutionGenome.ResourceBudget;
import de.regelsuche.evolution.EvolutionGenome.RewriteGene;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.FirstApplicable;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.Priority;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.Requirement;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.Source;
import de.regelsuche.evolution.EvolutionStudyPlan.FitnessComponent;
import de.regelsuche.evolution.EvolutionStudyPlan.FitnessWeight;
import de.regelsuche.evolution.EvolutionStudyPlan.PopulationPolicy;
import de.regelsuche.evolution.EvolutionStudyPlan.StudyBudget;
import de.regelsuche.transform.RewriteKind;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Runs TRAIN and freezes one candidate without exposing a later stage. */
public final class ProofCarryingShowcaseTrainAndFreezeCommand {
    static final String STUDY_ID =
        "proof_carrying_showcase_train_2026_08_v1";
    static final String SUITE_ID =
        "proof_carrying_showcase_train_suite_v1";
    private static final List<EvidenceObligation> OBLIGATIONS =
        Arrays.asList(EvidenceObligation.values());

    private ProofCarryingShowcaseTrainAndFreezeCommand() {
    }

    public static void main(String[] arguments) {
        if (arguments.length != 4) {
            throw new IllegalArgumentException(
                "usage: <showcase-plan.json> <repository-commit> "
                    + "<frozen-at-unix-time> <output-directory>");
        }
        long frozenAt;
        try {
            frozenAt = Long.parseLong(arguments[2]);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                "frozen-at-unix-time must be an integer", exception);
        }
        WrittenFreeze result = execute(
            Path.of(arguments[0]),
            arguments[1],
            frozenAt,
            Path.of(arguments[3]));
        System.out.println(
            "showcaseTrainFreezeStatus="
                + ProofCarryingShowcaseCandidateFreeze.STATUS);
        System.out.println(
            "showcaseCandidateFreezeHash=" + result.candidateFreezeHash());
        System.out.println(
            "showcaseSelectedCandidateHash=" + result.selectedCandidateHash());
        System.out.println(
            "showcaseRetainedTrainRunHash=" + result.retainedTrainRunHash());
        System.out.println(
            "showcaseTrainFreezeOutput=" + result.outputDirectory());
    }

    public static WrittenFreeze execute(
        Path showcasePlanPath,
        String repositoryCommit,
        long frozenAtUnixTime,
        Path outputDirectory
    ) {
        ProofCarryingShowcasePlan showcasePlan =
            ProofCarryingShowcasePlan.read(showcasePlanPath);
        TrainConfiguration configuration = configuration(showcasePlan);
        Set<FitnessComponent> requiredComponents = Set.copyOf(
            configuration.study().fitnessWeights().stream()
                .map(FitnessWeight::component)
                .toList());
        var evaluator = RewriteProgramFitnessComposition
            .exactRationalTrainEvaluator(
                configuration.trainSuite(),
                requiredComponents);
        var retained =
            new RetainedProtocolBoundEvolutionRewriteProgramPopulationRunner()
                .run(
                    configuration.study(),
                    configuration.splitManifest(),
                    configuration.trainSuite(),
                    configuration.seeds(),
                    configuration.mutationCatalog(),
                    evaluator);
        var freeze = new ProofCarryingShowcaseCandidateFreezer().freeze(
            showcasePlan,
            retained,
            configuration.study(),
            configuration.seeds(),
            repositoryCommit,
            configuration.primitiveInventoryHash(),
            configuration.workBudgetPolicyHash(),
            frozenAtUnixTime);
        return write(
            outputDirectory,
            showcasePlan,
            configuration,
            retained,
            freeze);
    }

    static TrainConfiguration configuration(
        ProofCarryingShowcasePlan showcasePlan
    ) {
        Objects.requireNonNull(showcasePlan, "showcasePlan");
        EvolutionRewriteProgramTrainSuite trainSuite =
            FlagshipRewriteProgramTrainCorpus.create(SUITE_ID);
        EvolutionRewriteProgramEvaluationProtocol protocol =
            EvolutionRewriteProgramEvaluationProtocol
                .informationParityExactRationalV1();
        List<FitnessWeight> fitnessWeights = fitnessWeights();
        EvolutionSplitManifest splitManifest =
            EvolutionSplitManifest.createTrainOnly(
                STUDY_ID,
                EvolutionGenome.hash(
                    "regelsuche.proof-carrying-showcase-train-corpus/v1\n"
                        + "showcasePlanHash=" + showcasePlan.contentHash()
                        + "\ntrainSuiteHash=" + trainSuite.contentHash()),
                EvolutionGenome.hash(
                    "regelsuche.proof-carrying-showcase-train-features/v1\n"
                        + "evaluationProtocolHash=" + protocol.contentHash()
                        + "\nfitnessComponents=" + fitnessWeights.stream()
                            .map(value -> value.component().name())
                            .sorted()
                            .toList()),
                EvolutionRewriteProgramTrainCaseReferences.create(trainSuite));
        EvolutionGenome genome = genome(splitManifest);
        List<EvolutionRewriteProgramCandidate> seeds = seeds(genome);
        MutationCatalog mutationCatalog = mutationCatalog(genome);
        EvolutionRewriteProgramStudyPlan study =
            EvolutionRewriteProgramStudyPlan.create(
                STUDY_ID,
                splitManifest,
                trainSuite,
                protocol,
                mutationCatalog,
                seeds,
                Arrays.asList(EvolutionRewriteProgramMutationKind.values()),
                new PopulationPolicy(24, 12, 4, 8, 3, 4, 20260802L),
                fitnessWeights,
                new StudyBudget(4_096, 4_096, 1, 24, 16));
        return new TrainConfiguration(
            splitManifest,
            trainSuite,
            protocol,
            genome,
            seeds,
            mutationCatalog,
            study,
            primitiveInventoryHash(genome.rewrites()),
            workBudgetPolicyHash(trainSuite));
    }

    static Path publishAtomically(
        Path outputDirectory,
        Map<String, String> prerequisiteArtifacts,
        String finalArtifactName,
        String finalArtifactContent
    ) {
        Path output = Objects.requireNonNull(
            outputDirectory, "outputDirectory")
            .toAbsolutePath()
            .normalize();
        Objects.requireNonNull(
            prerequisiteArtifacts, "prerequisiteArtifacts");
        requireFileName(finalArtifactName);
        Objects.requireNonNull(finalArtifactContent, "finalArtifactContent");
        if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException(
                "showcase output already exists: " + output);
        }
        Path parent = output.getParent();
        if (parent == null) {
            throw new IllegalArgumentException(
                "showcase output requires a parent directory");
        }
        Path staging = null;
        try {
            Files.createDirectories(parent);
            staging = Files.createTempDirectory(
                parent,
                ".proof-carrying-showcase-train-freeze-");
            for (Map.Entry<String, String> artifact
                    : prerequisiteArtifacts.entrySet()) {
                String name = artifact.getKey();
                requireFileName(name);
                if (finalArtifactName.equals(name)) {
                    throw new IllegalArgumentException(
                        "final artifact must not be a prerequisite");
                }
                writeNew(staging.resolve(name), artifact.getValue());
            }
            writeNew(staging.resolve(finalArtifactName), finalArtifactContent);
            try {
                Files.move(
                    staging,
                    output,
                    StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                throw new IllegalStateException(
                    "showcase publication requires an atomic directory move",
                    exception);
            }
            staging = null;
            return output;
        } catch (IOException exception) {
            throw new IllegalStateException(
                "unable to publish showcase TRAIN/freeze artifacts",
                exception);
        } finally {
            if (staging != null) {
                deleteTree(staging);
            }
        }
    }

    private static WrittenFreeze write(
        Path outputDirectory,
        ProofCarryingShowcasePlan showcasePlan,
        TrainConfiguration configuration,
        ProtocolBoundRetainedEvolutionRewriteProgramPopulationRun retained,
        ProofCarryingShowcaseCandidateFreezer.FreezeBundle freeze
    ) {
        EvolutionRewriteProgramCandidate selected =
            freeze.selectedCandidate().candidate();
        LinkedHashMap<String, String> artifacts = new LinkedHashMap<>();
        artifacts.put("showcase-plan.json", showcasePlan.toCanonicalJson());
        artifacts.put(
            "train-split-manifest.json",
            configuration.splitManifest().toCanonicalJson());
        artifacts.put(
            "train-suite.json",
            configuration.trainSuite().toCanonicalJson());
        artifacts.put(
            "evaluation-protocol.json",
            configuration.protocol().toCanonicalJson());
        artifacts.put(
            "seed-genome.json",
            configuration.genome().toCanonicalJson());
        for (int index = 0; index < configuration.seeds().size(); index++) {
            EvolutionRewriteProgramCandidate seed =
                configuration.seeds().get(index);
            artifacts.put(
                "seed-candidate-" + (index + 1) + ".json",
                seed.toCanonicalJson());
            artifacts.put(
                "seed-plan-" + (index + 1) + ".json",
                seed.plan().toCanonicalJson());
        }
        artifacts.put(
            "population-study-plan.json",
            configuration.study().toCanonicalJson());
        artifacts.put(
            "protocol-bound-train-run.json",
            retained.toCanonicalJson());
        artifacts.put(
            "candidate-selection.json",
            freeze.selection().toCanonicalJson());
        artifacts.put(
            "selected-candidate.json",
            selected.toCanonicalJson());
        artifacts.put(
            "selected-program.regelsuche",
            selected.plan().toReadableProgram());
        Path output = publishAtomically(
            outputDirectory,
            artifacts,
            "candidate-freeze.json",
            freeze.candidateFreeze().toCanonicalJson());
        return new WrittenFreeze(
            output,
            output.resolve("candidate-freeze.json"),
            freeze.candidateFreeze().contentHash(),
            selected.contentHash(),
            retained.contentHash());
    }

    private static EvolutionGenome genome(
        EvolutionSplitManifest splitManifest
    ) {
        return EvolutionGenome.create(
            Objective.OPEN_TARGET_OPERATOR,
            splitManifest.trainingScope(),
            primitiveGenes(),
            List.of(
                new FeatureWeight(
                    FitnessSignal.UNSEEN_TRAIN_CASES_SOLVED,
                    500),
                new FeatureWeight(
                    FitnessSignal.COUNTEREXAMPLE_RISK,
                    -500)),
            GuardPolicy.strictDefault(),
            new ResourceBudget(24, 512, 16, 12, 40),
            List.of(
                "core.ast-rewrite",
                "math.rational-normal-form"),
            List.of());
    }

    private static List<EvolutionRewriteProgramCandidate> seeds(
        EvolutionGenome genome
    ) {
        List<String> geneIds = genome.rewrites().stream()
            .map(RewriteGene::geneId)
            .toList();
        EvolutionRewriteProgramPlan sourcePlan =
            EvolutionRewriteProgramPlan.create(
                genome,
                new Source("seed_all_primitives", geneIds),
                24,
                12);
        EvolutionRewriteProgramPlan firstApplicablePlan =
            EvolutionRewriteProgramPlan.create(
                genome,
                new FirstApplicable(
                    "seed_first_applicable",
                    List.of(
                        new Source(
                            "seed_normalization_sources",
                            List.of(
                                "remove_additive_zero",
                                "remove_multiplicative_one",
                                "scaled_linear_collection")),
                        new Source(
                            "seed_rational_sources",
                            List.of(
                                "collect_equal_denominator",
                                "subtract_mixed_denominators",
                                "cancel_common_factor",
                                "flatten_shared_denominator_division")),
                        new Source(
                            "seed_factor_sources",
                            List.of(
                                "factor_difference_of_squares")))),
                24,
                12);
        List<EvolutionRewriteProgramCandidate> result = List.of(
            EvolutionRewriteProgramCandidate.create(genome, sourcePlan),
            EvolutionRewriteProgramCandidate.create(
                genome,
                firstApplicablePlan));
        EvolutionRewriteProgramCompiler compiler =
            new EvolutionRewriteProgramCompiler();
        result.forEach(candidate ->
            compiler.compile(candidate.genome(), candidate.plan()));
        return result;
    }

    private static MutationCatalog mutationCatalog(
        EvolutionGenome genome
    ) {
        List<String> geneIds = genome.rewrites().stream()
            .map(RewriteGene::geneId)
            .toList();
        return new MutationCatalog(
            List.of(
                new RepeatBounds(1, 2),
                new RepeatBounds(1, 3)),
            List.of(
                Requirement.equivalencePreservingByConstruction(),
                Requirement.maxEstimatedCostDelta(8),
                Requirement.maxPrimitiveSteps(6)),
            List.of(
                Priority.estimatedCostThenRule(),
                Priority.preferredGeneOrder(geneIds)),
            List.of(8, 16, 40),
            geneIds);
    }

    private static List<FitnessWeight> fitnessWeights() {
        return List.of(
            new FitnessWeight(
                FitnessComponent.TRAIN_CASES_NEWLY_SOLVED,
                350),
            new FitnessWeight(
                FitnessComponent.TRAIN_PATH_LENGTH_REDUCTION,
                150),
            new FitnessWeight(
                FitnessComponent.TRAIN_EXPLORED_STATE_REDUCTION,
                100),
            new FitnessWeight(FitnessComponent.SUPPORT, 150),
            new FitnessWeight(
                FitnessComponent.ASSUMPTION_SIMPLICITY,
                100),
            new FitnessWeight(
                FitnessComponent.CANDIDATE_COMPLEXITY,
                100),
            new FitnessWeight(FitnessComponent.PROOF_COST_PROXY, 50));
    }

    private static List<RewriteGene> primitiveGenes() {
        return List.of(
            gene(
                "remove_additive_zero",
                "?a+0",
                "?a",
                RewriteKind.SIMPLIFY,
                true,
                -1,
                List.of()),
            gene(
                "remove_multiplicative_one",
                "?a*1",
                "?a",
                RewriteKind.SIMPLIFY,
                true,
                -1,
                List.of()),
            gene(
                "collect_equal_denominator",
                "?a/?d+?b/?d",
                "(?a+?b)/?d",
                RewriteKind.NORMALIZE,
                true,
                -1,
                List.of(nonZero("?d"))),
            gene(
                "subtract_mixed_denominators",
                "?a/?x-?a/?y",
                "?a*(?y-?x)/(?x*?y)",
                RewriteKind.NORMALIZE,
                true,
                2,
                List.of(nonZero("?x"), nonZero("?y"))),
            gene(
                "cancel_common_factor",
                "(?f*?a)/(?f*?b)",
                "?a/?b",
                RewriteKind.SIMPLIFY,
                false,
                -3,
                List.of(nonZero("?f"), nonZero("?b"))),
            gene(
                "flatten_shared_denominator_division",
                "(?a/?d)/(?b/?d)",
                "?a/?b",
                RewriteKind.SIMPLIFY,
                false,
                -3,
                List.of(nonZero("?d"), nonZero("?b"))),
            gene(
                "factor_difference_of_squares",
                "?a^2-?b^2",
                "(?a-?b)*(?a+?b)",
                RewriteKind.FACTOR,
                true,
                1,
                List.of()),
            gene(
                "scaled_linear_collection",
                "(2*?a+2*?b)/2",
                "?a+?b",
                RewriteKind.SIMPLIFY,
                false,
                -3,
                List.of()));
    }

    private static RewriteGene gene(
        String id,
        String source,
        String target,
        RewriteKind kind,
        boolean reversible,
        int costDelta,
        List<AssumptionTemplate> assumptions
    ) {
        return new RewriteGene(
            id,
            source,
            target,
            kind,
            reversible,
            costDelta,
            6,
            12,
            assumptions,
            OBLIGATIONS);
    }

    private static AssumptionTemplate nonZero(String expression) {
        return new AssumptionTemplate(
            Assumption.Kind.NON_ZERO,
            expression + " != 0",
            List.of(expression));
    }

    private static String primitiveInventoryHash(List<RewriteGene> genes) {
        return EvolutionGenome.hash(
            "regelsuche.proof-carrying-showcase-primitive-inventory/v1\n"
                + genes);
    }

    private static String workBudgetPolicyHash(
        EvolutionRewriteProgramTrainSuite trainSuite
    ) {
        return EvolutionGenome.hash(
            "regelsuche.proof-carrying-showcase-work-budget/v1\n"
                + trainSuite.primitiveWorkBudget());
    }

    private static void requireFileName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException(
                "artifact name must not be blank");
        }
        Path path = Path.of(name);
        if (path.isAbsolute()
                || path.getNameCount() != 1
                || ".".equals(name)
                || "..".equals(name)) {
            throw new IllegalArgumentException(
                "artifact name must be one relative file name");
        }
    }

    private static void writeNew(Path path, String content)
            throws IOException {
        Files.writeString(
            path,
            Objects.requireNonNull(content, "artifact content"),
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE);
    }

    private static void deleteTree(Path root) {
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(
                    Path file,
                    BasicFileAttributes attributes
                ) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(
                    Path directory,
                    IOException failure
                ) throws IOException {
                    if (failure != null) {
                        throw failure;
                    }
                    Files.deleteIfExists(directory);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException exception) {
            throw new IllegalStateException(
                "unable to clean showcase staging directory",
                exception);
        }
    }

    record TrainConfiguration(
        EvolutionSplitManifest splitManifest,
        EvolutionRewriteProgramTrainSuite trainSuite,
        EvolutionRewriteProgramEvaluationProtocol protocol,
        EvolutionGenome genome,
        List<EvolutionRewriteProgramCandidate> seeds,
        MutationCatalog mutationCatalog,
        EvolutionRewriteProgramStudyPlan study,
        String primitiveInventoryHash,
        String workBudgetPolicyHash
    ) {
        TrainConfiguration {
            Objects.requireNonNull(splitManifest, "splitManifest");
            Objects.requireNonNull(trainSuite, "trainSuite");
            Objects.requireNonNull(protocol, "protocol");
            Objects.requireNonNull(genome, "genome");
            seeds = List.copyOf(Objects.requireNonNull(seeds, "seeds"));
            Objects.requireNonNull(mutationCatalog, "mutationCatalog");
            Objects.requireNonNull(study, "study");
            EvolutionGenome.requireSha256(
                primitiveInventoryHash,
                "primitiveInventoryHash");
            EvolutionGenome.requireSha256(
                workBudgetPolicyHash,
                "workBudgetPolicyHash");
            if (!splitManifest.heldOutMaterializationDeferred()) {
                throw new IllegalArgumentException(
                    "showcase TRAIN configuration must defer held-out material");
            }
            if (study.finalTestPolicy()
                    != EvolutionRewriteProgramStudyPlan.FinalTestPolicy
                        .ONE_TIME_AFTER_FROZEN_TRAIN_SELECTION_AND_PUBLIC_RANDOMNESS) {
                throw new IllegalArgumentException(
                    "showcase study must bind public-randomness final testing");
            }
            study.requireInputs(
                splitManifest,
                trainSuite,
                protocol,
                mutationCatalog,
                seeds);
        }
    }

    public record WrittenFreeze(
        Path outputDirectory,
        Path candidateFreezePath,
        String candidateFreezeHash,
        String selectedCandidateHash,
        String retainedTrainRunHash
    ) {
        public WrittenFreeze {
            outputDirectory = outputDirectory
                .toAbsolutePath()
                .normalize();
            candidateFreezePath = candidateFreezePath
                .toAbsolutePath()
                .normalize();
            EvolutionGenome.requireSha256(
                candidateFreezeHash,
                "candidateFreezeHash");
            EvolutionGenome.requireSha256(
                selectedCandidateHash,
                "selectedCandidateHash");
            EvolutionGenome.requireSha256(
                retainedTrainRunHash,
                "retainedTrainRunHash");
            if (!candidateFreezePath.equals(
                    outputDirectory.resolve("candidate-freeze.json"))) {
                throw new IllegalArgumentException(
                    "candidate freeze path differs from output directory");
            }
        }
    }
}
