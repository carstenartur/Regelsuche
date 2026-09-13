package de.regelsuche.evolution;

import de.regelsuche.evolution.EvolutionRewriteProgramHeldOutCommitment.Split;
import de.regelsuche.evolution.EvolutionRewriteProgramTrainSuite.PrimitiveWorkBudget;
import de.regelsuche.evolution.RetainedEvolutionRewriteProgramPopulationRun.RetainedCandidate;
import de.regelsuche.search.strategy.WorkBudgetBestFirstSearchStrategy.Budget;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Complete terminal population × bounded configuration matrix frozen before reveal. */
public record EvolutionRewriteProgramValidationPlan(
    String schema,
    String studyPlanHash,
    String splitManifestHash,
    String trainPopulationRunHash,
    String retainedTrainRunHash,
    EvolutionRewriteProgramHeldOutCommitment commitment,
    List<EvolutionSplitManifest.CaseReference> cases,
    List<Configuration> configurations,
    String evaluatorProtocol,
    String contentHash
) {
    public static final String SCHEMA = "regelsuche.evolution-rewrite-program-validation-plan/v1";
    public static final String NATIVE_PROTOCOL =
        "PAIRED_ORDINARY_PLUS_GENOME_VS_PLUS_PROGRAM_PRIMITIVE_WORK_EXACT_RATIONAL_V1";

    public EvolutionRewriteProgramValidationPlan {
        if (!SCHEMA.equals(schema) || !NATIVE_PROTOCOL.equals(evaluatorProtocol)) {
            throw new IllegalArgumentException("unsupported program VALIDATION protocol");
        }
        EvolutionGenome.requireSha256(studyPlanHash, "studyPlanHash");
        EvolutionGenome.requireSha256(splitManifestHash, "splitManifestHash");
        EvolutionGenome.requireSha256(trainPopulationRunHash, "trainPopulationRunHash");
        EvolutionGenome.requireSha256(retainedTrainRunHash, "retainedTrainRunHash");
        Objects.requireNonNull(commitment, "commitment");
        if (commitment.split() != Split.VALIDATION) {
            throw new IllegalArgumentException("only committed VALIDATION may be evaluated");
        }
        cases = List.copyOf(Objects.requireNonNull(cases, "cases"));
        if (!cases.stream().map(EvolutionSplitManifest.CaseReference::caseId).toList()
                .equals(commitment.cases().stream().map(
                    EvolutionRewriteProgramHeldOutCommitment.CaseCommitment::caseId).toList())) {
            throw new IllegalArgumentException("case order differs from VALIDATION commitment");
        }
        for (int index = 0; index < cases.size(); index++) {
            var reference = cases.get(index);
            var committed = commitment.cases().get(index);
            if (!EvolutionRewriteProgramHeldOutCommitment.familyCommitment(reference.familyId())
                    .equals(committed.familyCommitmentHash())
                    || !reference.inputHash().equals(committed.inputHash())
                    || !reference.hiddenTargetHash().equals(committed.targetHash())
                    || !reference.exactSignatureHash().equals(committed.exactSignatureHash())
                    || !reference.alphaSignatureHash().equals(committed.alphaSignatureHash())) {
                throw new IllegalArgumentException("case material differs from VALIDATION commitment");
            }
        }
        configurations = canonicalConfigurations(configurations);
        EvolutionProgramValidationJson.requireHash(contentHash, material(studyPlanHash,
            splitManifestHash, trainPopulationRunHash, retainedTrainRunHash, commitment, cases, configurations));
    }

    public static EvolutionRewriteProgramValidationPlan create(
        EvolutionRewriteProgramStudyPlan study,
        EvolutionSplitManifest manifest,
        ProtocolBoundRetainedEvolutionRewriteProgramPopulationRun train,
        EvolutionRewriteProgramHeldOutCommitment commitment,
        List<PrimitiveWorkBudget> budgets
    ) {
        Objects.requireNonNull(study, "study");
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(train, "train");
        Objects.requireNonNull(commitment, "commitment").requireMatches(manifest);
        var run = train.retainedPopulation().populationRun();
        var protocol = EvolutionRewriteProgramEvaluationProtocol.informationParityExactRationalV1();
        if (!study.studyId().equals(manifest.studyId())
                || !study.splitManifestHash().equals(manifest.contentHash())
                || !study.contentHash().equals(run.studyPlanHash())
                || !study.trainEvaluationProtocolHash().equals(train.evaluationProtocolHash())
                || !protocol.contentHash().equals(train.evaluationProtocolHash())
                || !protocol.implementationClass().equals(train.evaluatorImplementationClass())
                || study.finalTestPolicy() != EvolutionRewriteProgramStudyPlan.FinalTestPolicy
                    .ONE_TIME_AFTER_FROZEN_VALIDATION_SELECTION) {
            throw new IllegalArgumentException("combined TRAIN study, split or evaluator identity mismatch");
        }
        Objects.requireNonNull(budgets, "budgets");
        if (budgets.isEmpty() || budgets.stream().distinct().count() != budgets.size()) {
            throw new IllegalArgumentException("VALIDATION requires unique bounded search configurations");
        }
        if ((long) budgets.size() * train.retainedPopulation().finalCandidates().size()
                > study.budget().maxValidationEvaluations()) {
            throw new IllegalArgumentException("complete VALIDATION matrix exceeds frozen evaluation budget");
        }
        List<Configuration> matrix = new ArrayList<>();
        for (RetainedCandidate retained : train.retainedPopulation().finalCandidates()) {
            var candidate = retained.candidate();
            if (!candidate.genome().trainingScope().equals(manifest.trainingScope())) {
                throw new IllegalArgumentException("terminal candidate differs from frozen TRAIN scope");
            }
            for (PrimitiveWorkBudget budget : budgets) {
                matrix.add(Configuration.create(retained, budget));
            }
        }
        List<Configuration> configurations = canonicalConfigurations(matrix);
        List<EvolutionSplitManifest.CaseReference> cases = manifest.validationCases().stream()
            .sorted(Comparator.comparing(EvolutionSplitManifest.CaseReference::caseId)).toList();
        return new EvolutionRewriteProgramValidationPlan(SCHEMA, study.contentHash(), manifest.contentHash(),
            run.contentHash(), train.contentHash(), commitment, cases, configurations, NATIVE_PROTOCOL,
            EvolutionProgramValidationJson.hash(material(study.contentHash(), manifest.contentHash(),
                run.contentHash(), train.contentHash(), commitment, cases, configurations)));
    }

    /** Reconstruct against external TRAIN roots; a self-rehashed reduced matrix is insufficient. */
    public void requireInputs(EvolutionRewriteProgramStudyPlan study, EvolutionSplitManifest manifest,
        ProtocolBoundRetainedEvolutionRewriteProgramPopulationRun train) {
        var expected = create(study, manifest, train, commitment,
            configurations.stream().map(Configuration::budget).distinct().toList());
        if (!equals(expected)) {
            throw new IllegalArgumentException("VALIDATION plan differs from complete terminal population");
        }
    }

    public String runIdentity() {
        return EvolutionProgramValidationJson.hash(EvolutionProgramValidationJson.material(
            "regelsuche.evolution-rewrite-program-validation-attempt/v1",
            "studyPlanHash", studyPlanHash, "splitManifestHash", splitManifestHash));
    }

    public String toCanonicalJson() { return EvolutionProgramValidationJson.write(this); }
    public static EvolutionRewriteProgramValidationPlan fromCanonicalJson(String json) {
        return EvolutionProgramValidationJson.read(json, EvolutionRewriteProgramValidationPlan.class);
    }

    private static List<Configuration> canonicalConfigurations(List<Configuration> values) {
        var result = Objects.requireNonNull(values, "configurations").stream()
            .sorted(Comparator.comparing(Configuration::contentHash)).toList();
        if (result.isEmpty() || result.stream().map(Configuration::contentHash).distinct().count() != result.size()) {
            throw new IllegalArgumentException("VALIDATION requires a nonempty unique complete configuration matrix");
        }
        return result;
    }

    private static Map<String, Object> material(String study, String split, String run, String retained,
        EvolutionRewriteProgramHeldOutCommitment commitment,
        List<EvolutionSplitManifest.CaseReference> cases, List<Configuration> configurations) {
        return EvolutionProgramValidationJson.material(SCHEMA, "studyPlanHash", study, "splitManifestHash", split,
            "trainPopulationRunHash", run, "retainedTrainRunHash", retained, "commitment", commitment,
            "cases", cases, "configurations", configurations, "evaluatorProtocol", NATIVE_PROTOCOL);
    }

    public record Configuration(RetainedCandidate retainedCandidate, PrimitiveWorkBudget budget, String contentHash) {
        private static final String CONFIGURATION_SCHEMA =
            "regelsuche.evolution-rewrite-program-validation-configuration/v1";

        public Configuration {
            Objects.requireNonNull(retainedCandidate, "retainedCandidate");
            Objects.requireNonNull(budget, "budget");
            if (budget.maxCandidatesPerState() < retainedCandidate.candidate().genome().budget().maxCandidatesPerState()) {
                throw new IllegalArgumentException("candidate work budget hides visible genome/program sources");
            }
            EvolutionProgramValidationJson.requireHash(contentHash, material(retainedCandidate, budget));
        }

        static Configuration create(RetainedCandidate candidate, PrimitiveWorkBudget budget) {
            return new Configuration(candidate, budget, EvolutionProgramValidationJson.hash(material(candidate, budget)));
        }

        public EvolutionRewriteProgramCandidate candidate() { return retainedCandidate.candidate(); }

        Budget effectiveBudget() {
            var genomeBudget = candidate().genome().budget();
            return Budget.primitive(Math.min(budget.maxPrimitiveSteps(), genomeBudget.maxApplicationsPerPath()),
                budget.maxExploredStates(),
                Math.min(budget.maxCandidatesPerState(), genomeBudget.maxCandidatesPerState()),
                Math.min(budget.maxExpandingSteps(), genomeBudget.maxApplicationsPerPath()), budget.maxWorkUnits());
        }

        private static Map<String, Object> material(RetainedCandidate candidate, PrimitiveWorkBudget budget) {
            return EvolutionProgramValidationJson.material(CONFIGURATION_SCHEMA,
                "retainedCandidate", candidate, "budget", budget, "evaluatorProtocol", NATIVE_PROTOCOL);
        }
    }
}
