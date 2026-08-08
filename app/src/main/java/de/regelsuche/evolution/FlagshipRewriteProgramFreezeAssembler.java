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
import de.regelsuche.evolution.EvolutionRewriteProgramBaselineAblationPlan.Track;
import de.regelsuche.evolution.EvolutionRewriteProgramBaselineAblationPlan.TrackKind;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.FirstApplicable;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.Priority;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.Requirement;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.Source;
import de.regelsuche.evolution.EvolutionStudyPlan.FitnessComponent;
import de.regelsuche.evolution.EvolutionStudyPlan.FitnessWeight;
import de.regelsuche.evolution.EvolutionStudyPlan.PopulationPolicy;
import de.regelsuche.evolution.EvolutionStudyPlan.StudyBudget;
import de.regelsuche.json.JsonWriter;
import de.regelsuche.transform.RewriteKind;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Productive, pre-execution assembler for the flagship rewrite-program study.
 *
 * <p>The trusted sealing path consumes two already validated private reveal
 * bundles. The reproducibility path consumes only their published commitments
 * and hash-only split references. Both paths emit byte-identical public plans
 * and the final {@code FROZEN_NOT_RUN} receipt; neither writes concrete held-
 * out expressions or assumptions.</p>
 */
public final class FlagshipRewriteProgramFreezeAssembler {
    public static final String FREEZE_ID = "flagship_rewrite_program_freeze_v1";
    public static final String STATIC_CONTRACT_SCHEMA =
        "regelsuche.flagship-rewrite-program-static-contract/v1";

    private static final List<EvidenceObligation> OBLIGATIONS =
        Arrays.asList(EvidenceObligation.values());

    private FlagshipRewriteProgramFreezeAssembler() {
    }

    public static FreezeInputs assemble(
        String repositoryCommit,
        EvolutionRewriteProgramHeldOutRevealBundle validation,
        EvolutionRewriteProgramHeldOutRevealBundle finalTest
    ) {
        Objects.requireNonNull(validation, "validation");
        Objects.requireNonNull(finalTest, "finalTest");
        return assemble(
            repositoryCommit,
            validation.commitment(),
            EvolutionRewriteProgramHeldOutSplitReferences.create(validation),
            finalTest.commitment(),
            EvolutionRewriteProgramHeldOutSplitReferences.create(finalTest));
    }

    public static FreezeInputs assemble(
        String repositoryCommit,
        EvolutionRewriteProgramHeldOutCommitment validationCommitment,
        EvolutionRewriteProgramHeldOutSplitReferences validationReferences,
        EvolutionRewriteProgramHeldOutCommitment finalTestCommitment,
        EvolutionRewriteProgramHeldOutSplitReferences finalTestReferences
    ) {
        EvolutionSplitManifest manifest =
            FlagshipRewriteProgramSplitManifest.create(
                validationCommitment,
                validationReferences,
                finalTestCommitment,
                finalTestReferences);
        EvolutionRewriteProgramTrainSuite train =
            FlagshipRewriteProgramTrainCorpus.create();
        EvolutionRewriteProgramEvaluationProtocol protocol =
            EvolutionRewriteProgramEvaluationProtocol
                .informationParityExactRationalV1();
        EvolutionGenome genome = genome(manifest);
        List<EvolutionRewriteProgramCandidate> seeds = seeds(genome);
        MutationCatalog mutationCatalog = mutationCatalog(genome);
        EvolutionRewriteProgramStudyPlan study =
            EvolutionRewriteProgramStudyPlan.create(
                manifest.studyId(),
                manifest,
                train,
                protocol,
                mutationCatalog,
                seeds,
                Arrays.asList(EvolutionRewriteProgramMutationKind.values()),
                new PopulationPolicy(24, 12, 4, 8, 3, 4, 20260802L),
                fitnessWeights(),
                new StudyBudget(4_096, 4_096, 24, 3, 16));
        EvolutionRewriteProgramAcceptanceThresholds thresholds =
            EvolutionRewriteProgramAcceptanceThresholds.create(
                2,
                2,
                1,
                true,
                100,
                3,
                0,
                24,
                12,
                2);

        String primitiveInventoryJson = primitiveInventoryJson(genome.rewrites());
        String programGrammarJson = programGrammarJson();
        String mutationCatalogJson = mutationCatalogJson(mutationCatalog);
        String workBudgetPolicyJson = workBudgetPolicyJson(train);
        String schemaBundleJson = schemaBundleJson();
        String primitiveInventoryHash = contentHash(primitiveInventoryJson);
        String programGrammarHash = contentHash(programGrammarJson);
        String workBudgetPolicyHash = contentHash(workBudgetPolicyJson);
        String schemaBundleHash = contentHash(schemaBundleJson);

        EvolutionRewriteProgramBaselineAblationPlan baselineAblation =
            baselineAblationPlan(
                manifest,
                protocol,
                primitiveInventoryHash,
                programGrammarHash,
                mutationCatalog.contentHash(),
                workBudgetPolicyHash,
                seeds.getLast().contentHash());
        EvolutionRewriteProgramPerformancePlan performance =
            EvolutionRewriteProgramPerformancePlan.create(
                EvolutionGenome.hash(
                    "regelsuche.jmh-benchmark-revision/v1\n"
                        + "allocation-and-search-baseline=5b904d6d1d0d1488353a2d28445c9ec9d3e5fc51"),
                EvolutionGenome.hash(
                    "regelsuche.jmh-benchmark-suite/v2\n"
                        + "primary-benchmark-count=29\n"
                        + "units=us/op,ms/op"),
                EvolutionGenome.hash(
                    "regelsuche.flagship-runtime-environment/v1\n"
                        + "jdk=21\nos=ubuntu-22.04\njmh=1.36"),
                2,
                3,
                1,
                1_000,
                250);

        EvolutionRewriteProgramFreezeReceipt receipt =
            EvolutionRewriteProgramFreezeReceipt.create(
                FREEZE_ID,
                repositoryCommit,
                manifest,
                train,
                validationCommitment,
                finalTestCommitment,
                protocol,
                study,
                thresholds,
                primitiveInventoryHash,
                programGrammarHash,
                baselineAblation.contentHash(),
                performance,
                schemaBundleHash);

        receipt.requireInputs(
            manifest,
            train,
            validationCommitment,
            finalTestCommitment,
            protocol,
            study,
            thresholds,
            primitiveInventoryHash,
            programGrammarHash,
            baselineAblation.contentHash(),
            performance,
            schemaBundleHash);
        baselineAblation.requireInputs(
            manifest,
            protocol,
            primitiveInventoryHash,
            programGrammarHash,
            mutationCatalog.contentHash(),
            workBudgetPolicyHash);
        study.requireInputs(
            manifest,
            train,
            protocol,
            mutationCatalog,
            seeds);

        return new FreezeInputs(
            manifest,
            train,
            validationCommitment,
            finalTestCommitment,
            validationReferences,
            finalTestReferences,
            protocol,
            genome,
            seeds,
            mutationCatalog,
            study,
            thresholds,
            baselineAblation,
            performance,
            primitiveInventoryJson,
            programGrammarJson,
            mutationCatalogJson,
            workBudgetPolicyJson,
            schemaBundleJson,
            receipt);
    }

    public static WrittenFreeze write(
        Path outputDirectory,
        String repositoryCommit,
        Path validationPrivateBundle,
        Path finalTestPrivateBundle
    ) {
        EvolutionRewriteProgramHeldOutRevealCodec codec =
            new EvolutionRewriteProgramHeldOutRevealCodec();
        return write(
            outputDirectory,
            assemble(
                repositoryCommit,
                codec.readPrivate(validationPrivateBundle),
                codec.readPrivate(finalTestPrivateBundle)));
    }

    /**
     * Reconstructs and writes the complete public freeze from already-published
     * commitments and hash-only split references. No private reveal bundle is
     * opened by this path.
     */
    public static WrittenFreeze writePublic(
        Path outputDirectory,
        String repositoryCommit,
        Path validationCommitment,
        Path validationReferences,
        Path finalTestCommitment,
        Path finalTestReferences
    ) {
        EvolutionRewriteProgramHeldOutPublicCodec codec =
            new EvolutionRewriteProgramHeldOutPublicCodec();
        return write(
            outputDirectory,
            assemble(
                repositoryCommit,
                codec.readCommitment(validationCommitment),
                codec.readSplitReferences(validationReferences),
                codec.readCommitment(finalTestCommitment),
                codec.readSplitReferences(finalTestReferences)));
    }

    private static WrittenFreeze write(
        Path outputDirectory,
        FreezeInputs inputs
    ) {
        Path output = Objects.requireNonNull(outputDirectory, "outputDirectory")
            .toAbsolutePath()
            .normalize();
        LinkedHashMap<String, String> artifacts = new LinkedHashMap<>();
        artifacts.put("evolution-split-manifest.json",
            inputs.manifest().toCanonicalJson());
        artifacts.put("train-suite.json", inputs.train().toCanonicalJson());
        artifacts.put("validation-commitment.json",
            inputs.validationCommitment().toCanonicalJson());
        artifacts.put("validation-split-references.json",
            inputs.validationReferences().toCanonicalJson());
        artifacts.put("final-test-commitment.json",
            inputs.finalTestCommitment().toCanonicalJson());
        artifacts.put("final-test-split-references.json",
            inputs.finalTestReferences().toCanonicalJson());
        artifacts.put("evaluation-protocol.json",
            inputs.protocol().toCanonicalJson());
        artifacts.put("seed-genome.json", inputs.genome().toCanonicalJson());
        for (int index = 0; index < inputs.seeds().size(); index++) {
            EvolutionRewriteProgramCandidate candidate = inputs.seeds().get(index);
            artifacts.put("seed-candidate-" + (index + 1) + ".json",
                candidate.toCanonicalJson());
            artifacts.put("seed-plan-" + (index + 1) + ".json",
                candidate.plan().toCanonicalJson());
        }
        artifacts.put("population-study-plan.json",
            inputs.study().toCanonicalJson());
        artifacts.put("acceptance-thresholds.json",
            inputs.thresholds().toCanonicalJson());
        artifacts.put("primitive-inventory.json",
            inputs.primitiveInventoryJson());
        artifacts.put("program-grammar.json", inputs.programGrammarJson());
        artifacts.put("mutation-catalog.json", inputs.mutationCatalogJson());
        artifacts.put("work-budget-policy.json",
            inputs.workBudgetPolicyJson());
        artifacts.put("baseline-ablation-plan.json",
            inputs.baselineAblation().toCanonicalJson());
        artifacts.put("performance-plan.json",
            inputs.performance().toCanonicalJson());
        artifacts.put("schema-bundle.json", inputs.schemaBundleJson());

        try {
            Files.createDirectories(output);
            for (Map.Entry<String, String> artifact : artifacts.entrySet()) {
                writeAtomic(output.resolve(artifact.getKey()), artifact.getValue());
            }
            Path receipt = output.resolve("freeze-receipt.json");
            writeAtomic(receipt, inputs.receipt().toCanonicalJson());
            return new WrittenFreeze(
                output,
                artifacts.keySet().stream().map(output::resolve).toList(),
                receipt,
                inputs.receipt().contentHash());
        } catch (IOException exception) {
            throw new IllegalStateException(
                "unable to write flagship freeze artifacts", exception);
        }
    }

    public static void main(String[] arguments) {
        WrittenFreeze result;
        if (arguments.length == 4) {
            result = write(
                Path.of(arguments[3]),
                arguments[0],
                Path.of(arguments[1]),
                Path.of(arguments[2]));
        } else if (arguments.length == 7
                && "--public".equals(arguments[0])) {
            result = writePublic(
                Path.of(arguments[6]),
                arguments[1],
                Path.of(arguments[2]),
                Path.of(arguments[3]),
                Path.of(arguments[4]),
                Path.of(arguments[5]));
        } else {
            throw new IllegalArgumentException(
                "usage: <repository-commit> <validation-private.json> "
                    + "<final-test-private.json> <public-output-directory> "
                    + "or --public <repository-commit> "
                    + "<validation-commitment.json> "
                    + "<validation-split-references.json> "
                    + "<final-test-commitment.json> "
                    + "<final-test-split-references.json> "
                    + "<public-output-directory>");
        }
        System.out.println("flagshipFreezeStatus=FROZEN_NOT_RUN");
        System.out.println("flagshipFreezeReceiptHash=" + result.receiptHash());
        System.out.println("flagshipFreezeOutput=" + result.outputDirectory());
    }

    private static EvolutionGenome genome(EvolutionSplitManifest manifest) {
        return EvolutionGenome.create(
            Objective.OPEN_TARGET_OPERATOR,
            manifest.trainingScope(),
            primitiveGenes(),
            List.of(
                new FeatureWeight(
                    FitnessSignal.UNSEEN_TRAIN_CASES_SOLVED, 500),
                new FeatureWeight(FitnessSignal.COUNTEREXAMPLE_RISK, -500)),
            GuardPolicy.strictDefault(),
            new ResourceBudget(24, 512, 16, 12, 40),
            List.of("core.ast-rewrite", "math.rational-normal-form"),
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
                            List.of("factor_difference_of_squares")))),
                24,
                12);
        List<EvolutionRewriteProgramCandidate> seeds = List.of(
            EvolutionRewriteProgramCandidate.create(genome, sourcePlan),
            EvolutionRewriteProgramCandidate.create(genome, firstApplicablePlan));
        EvolutionRewriteProgramCompiler compiler =
            new EvolutionRewriteProgramCompiler();
        seeds.forEach(candidate ->
            compiler.compile(candidate.genome(), candidate.plan()));
        return seeds;
    }

    private static MutationCatalog mutationCatalog(EvolutionGenome genome) {
        List<String> geneIds = genome.rewrites().stream()
            .map(RewriteGene::geneId)
            .toList();
        return new MutationCatalog(
            List.of(new RepeatBounds(1, 2), new RepeatBounds(1, 3)),
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
            new FitnessWeight(FitnessComponent.TRAIN_CASES_NEWLY_SOLVED, 350),
            new FitnessWeight(FitnessComponent.TRAIN_PATH_LENGTH_REDUCTION, 150),
            new FitnessWeight(
                FitnessComponent.TRAIN_EXPLORED_STATE_REDUCTION, 100),
            new FitnessWeight(FitnessComponent.SUPPORT, 100),
            new FitnessWeight(FitnessComponent.STRUCTURAL_DIVERSITY, 150),
            new FitnessWeight(FitnessComponent.ASSUMPTION_SIMPLICITY, 50),
            new FitnessWeight(FitnessComponent.CANDIDATE_COMPLEXITY, 50),
            new FitnessWeight(FitnessComponent.COUNTEREXAMPLE_RISK, 50));
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

    private static EvolutionRewriteProgramBaselineAblationPlan
            baselineAblationPlan(
        EvolutionSplitManifest manifest,
        EvolutionRewriteProgramEvaluationProtocol protocol,
        String primitiveInventoryHash,
        String programGrammarHash,
        String mutationCatalogHash,
        String workBudgetPolicyHash,
        String handWrittenProgramHash
    ) {
        return EvolutionRewriteProgramBaselineAblationPlan.create(
            "flagship_baseline_ablation_v1",
            manifest.studyId(),
            manifest.contentHash(),
            protocol.contentHash(),
            primitiveInventoryHash,
            programGrammarHash,
            mutationCatalogHash,
            workBudgetPolicyHash,
            Arrays.stream(TrackKind.values())
                .map(kind -> track(kind, handWrittenProgramHash))
                .toList());
    }

    private static Track track(
        TrackKind kind,
        String handWrittenProgramHash
    ) {
        String id = switch (kind) {
            case FIXED_PRIMITIVE_BEST_FIRST -> "fixed_primitive_best_first";
            case EQUALITY_SATURATION_SHARED_FRAGMENT ->
                "equality_saturation_shared_fragment";
            case RANDOMIZED_VALID_PROGRAM -> "randomized_valid_program";
            case MUTATION_ONLY_NO_TOPOLOGY -> "mutation_only_no_topology";
            case HAND_WRITTEN_PROGRAM -> "preregistered_hand_written_program";
            case NO_COMPOSITION_ABLATION -> "no_composition_ablation";
            case FIXED_GUARD_ABLATION -> "fixed_guard_ablation";
            case FLATTENED_PROGRAM_OUTER_SEARCH ->
                "flattened_program_outer_search";
        };
        return new Track(
            kind,
            id,
            EvolutionGenome.hash(
                "regelsuche.flagship-baseline-implementation/v1\nkind="
                    + kind.name()),
            EvolutionGenome.hash(
                "regelsuche.flagship-baseline-configuration/v1\nkind="
                    + kind.name()),
            kind == TrackKind.RANDOMIZED_VALID_PROGRAM ? 20260802L : null,
            kind == TrackKind.HAND_WRITTEN_PROGRAM
                ? handWrittenProgramHash
                : null);
    }

    private static String primitiveInventoryJson(List<RewriteGene> genes) {
        JsonWriter json = new JsonWriter().beginObject()
            .property("schema", STATIC_CONTRACT_SCHEMA)
            .property("contract", "PRIMITIVE_INVENTORY")
            .array("genes", array -> genes.stream()
                .sorted(Comparator.comparing(RewriteGene::geneId))
                .forEach(gene -> array.objectValue(object -> object
                    .property("geneId", gene.geneId())
                    .property("sourcePattern", gene.sourcePattern())
                    .property("targetPattern", gene.targetPattern())
                    .property("kind", gene.kind().name())
                    .property("reversible", gene.reversible())
                    .property("estimatedCostDelta",
                        gene.estimatedCostDelta())
                    .property("maxApplicationsPerPath",
                        gene.maxApplicationsPerPath())
                    .property("maxAstGrowth", gene.maxAstGrowth())
                    .stringArray("assumptions", gene.assumptions().stream()
                        .map(item -> item.kind().name() + ":" + item.expression())
                        .toList())
                    .stringArray("evidenceObligations",
                        gene.evidenceObligations().stream()
                            .map(Enum::name).toList()))));
        return withHash(json.endObject().toString());
    }

    private static String programGrammarJson() {
        JsonWriter json = new JsonWriter().beginObject()
            .property("schema", STATIC_CONTRACT_SCHEMA)
            .property("contract", "PROGRAM_GRAMMAR")
            .stringArray("nodes", List.of(
                "SOURCE",
                "CHOICE",
                "FIRST_APPLICABLE",
                "SEQUENCE",
                "REPEAT",
                "REQUIRE",
                "PRIORITIZE",
                "PRUNE"))
            .property("maximumNodes", 24)
            .property("maximumDepth", 12)
            .property("maximumRepeatIterations", 3)
            .property("hiddenPruning", "FORBIDDEN");
        return withHash(json.endObject().toString());
    }

    private static String mutationCatalogJson(MutationCatalog catalog) {
        JsonWriter json = new JsonWriter().beginObject()
            .property("schema", STATIC_CONTRACT_SCHEMA)
            .property("contract", "MUTATION_CATALOG")
            .property("catalogHash", catalog.contentHash())
            .stringArray("mutationKinds",
                Arrays.stream(EvolutionRewriteProgramMutationKind.values())
                    .map(Enum::name).sorted().toList())
            .stringArray("sourceGeneIds", catalog.sourceGeneIds())
            .stringArray("repeatBounds", catalog.repeatBounds().stream()
                .map(value -> value.minIterations() + ".."
                    + value.maxIterations())
                .toList())
            .stringArray("requirements", catalog.requirements().stream()
                .map(value -> value.kind().name() + ":" + value.threshold())
                .toList())
            .stringArray("priorities", catalog.priorities().stream()
                .map(value -> value.kind().name() + ":"
                    + value.preferredGeneIds())
                .toList())
            .array("pruneLimits", array -> catalog.pruneLimits().forEach(
                array::numberValue));
        return withHash(json.endObject().toString());
    }

    private static String workBudgetPolicyJson(
        EvolutionRewriteProgramTrainSuite train
    ) {
        var budget = train.primitiveWorkBudget();
        JsonWriter json = new JsonWriter().beginObject()
            .property("schema", STATIC_CONTRACT_SCHEMA)
            .property("contract", "MATCHED_WORK_POLICY")
            .property("trainSuiteHash", train.contentHash())
            .property("maximumDepth", budget.maximumDepth())
            .property("maximumVisitedExpressions",
                budget.maximumVisitedExpressions())
            .property("maximumCandidatesPerState",
                budget.maximumCandidatesPerState())
            .property("maximumExpandingSteps",
                budget.maximumExpandingSteps())
            .property("totalPrimitiveWorkUnits",
                budget.totalPrimitiveWorkUnits())
            .property("authority",
                "CANONICAL_PRIMITIVE_AND_TOTAL_WORK_LEDGER");
        return withHash(json.endObject().toString());
    }

    private static String schemaBundleJson() {
        List<String> schemas = List.of(
            EvolutionSplitManifest.SCHEMA,
            EvolutionRewriteProgramTrainSuite.SCHEMA,
            EvolutionRewriteProgramHeldOutCommitment.SCHEMA,
            EvolutionRewriteProgramHeldOutSplitReferences.SCHEMA,
            EvolutionRewriteProgramEvaluationProtocol.SCHEMA,
            EvolutionGenome.SCHEMA,
            EvolutionRewriteProgramPlan.SCHEMA,
            EvolutionRewriteProgramCandidate.SCHEMA,
            EvolutionRewriteProgramStudyPlan.SCHEMA,
            EvolutionRewriteProgramAcceptanceThresholds.SCHEMA,
            EvolutionRewriteProgramBaselineAblationPlan.SCHEMA,
            EvolutionRewriteProgramPerformancePlan.SCHEMA,
            EvolutionRewriteProgramFreezeReceipt.SCHEMA,
            STATIC_CONTRACT_SCHEMA);
        JsonWriter json = new JsonWriter().beginObject()
            .property("schema", STATIC_CONTRACT_SCHEMA)
            .property("contract", "SCHEMA_BUNDLE")
            .stringArray("schemas", schemas.stream().sorted().toList());
        return withHash(json.endObject().toString());
    }

    private static String withHash(String unhashedJson) {
        String hash = EvolutionGenome.hash(unhashedJson);
        if (!unhashedJson.endsWith("}")) {
            throw new IllegalArgumentException("canonical JSON object required");
        }
        return unhashedJson.substring(0, unhashedJson.length() - 1)
            + ",\"contentHash\":\"" + hash + "\"}";
    }

    private static String contentHash(String canonicalJson) {
        int marker = canonicalJson.lastIndexOf(",\"contentHash\":\"");
        if (marker < 0 || !canonicalJson.endsWith("\"}")) {
            throw new IllegalArgumentException(
                "static contract lacks terminal contentHash");
        }
        return canonicalJson.substring(
            marker + ",\"contentHash\":\"".length(),
            canonicalJson.length() - 2);
    }

    private static void writeAtomic(Path output, String content)
            throws IOException {
        Path parent = output.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temporary = Files.createTempFile(
            parent == null ? Path.of(".") : parent,
            ".regelsuche-freeze-",
            ".tmp");
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            try {
                Files.move(
                    temporary,
                    output,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(
                    temporary,
                    output,
                    StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public record FreezeInputs(
        EvolutionSplitManifest manifest,
        EvolutionRewriteProgramTrainSuite train,
        EvolutionRewriteProgramHeldOutCommitment validationCommitment,
        EvolutionRewriteProgramHeldOutCommitment finalTestCommitment,
        EvolutionRewriteProgramHeldOutSplitReferences validationReferences,
        EvolutionRewriteProgramHeldOutSplitReferences finalTestReferences,
        EvolutionRewriteProgramEvaluationProtocol protocol,
        EvolutionGenome genome,
        List<EvolutionRewriteProgramCandidate> seeds,
        MutationCatalog mutationCatalog,
        EvolutionRewriteProgramStudyPlan study,
        EvolutionRewriteProgramAcceptanceThresholds thresholds,
        EvolutionRewriteProgramBaselineAblationPlan baselineAblation,
        EvolutionRewriteProgramPerformancePlan performance,
        String primitiveInventoryJson,
        String programGrammarJson,
        String mutationCatalogJson,
        String workBudgetPolicyJson,
        String schemaBundleJson,
        EvolutionRewriteProgramFreezeReceipt receipt
    ) {
        public FreezeInputs {
            Objects.requireNonNull(manifest, "manifest");
            Objects.requireNonNull(train, "train");
            Objects.requireNonNull(validationCommitment, "validationCommitment");
            Objects.requireNonNull(finalTestCommitment, "finalTestCommitment");
            Objects.requireNonNull(validationReferences, "validationReferences");
            Objects.requireNonNull(finalTestReferences, "finalTestReferences");
            Objects.requireNonNull(protocol, "protocol");
            Objects.requireNonNull(genome, "genome");
            seeds = List.copyOf(Objects.requireNonNull(seeds, "seeds"));
            Objects.requireNonNull(mutationCatalog, "mutationCatalog");
            Objects.requireNonNull(study, "study");
            Objects.requireNonNull(thresholds, "thresholds");
            Objects.requireNonNull(baselineAblation, "baselineAblation");
            Objects.requireNonNull(performance, "performance");
            Objects.requireNonNull(primitiveInventoryJson,
                "primitiveInventoryJson");
            Objects.requireNonNull(programGrammarJson, "programGrammarJson");
            Objects.requireNonNull(mutationCatalogJson, "mutationCatalogJson");
            Objects.requireNonNull(workBudgetPolicyJson, "workBudgetPolicyJson");
            Objects.requireNonNull(schemaBundleJson, "schemaBundleJson");
            Objects.requireNonNull(receipt, "receipt");
        }
    }

    public record WrittenFreeze(
        Path outputDirectory,
        List<Path> prerequisiteArtifacts,
        Path receiptPath,
        String receiptHash
    ) {
        public WrittenFreeze {
            outputDirectory = outputDirectory.toAbsolutePath().normalize();
            prerequisiteArtifacts = prerequisiteArtifacts.stream()
                .map(path -> path.toAbsolutePath().normalize())
                .toList();
            receiptPath = receiptPath.toAbsolutePath().normalize();
            EvolutionGenome.requireSha256(receiptHash, "receiptHash");
        }
    }
}
