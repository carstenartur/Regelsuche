package de.regelsuche.evolution;

import de.regelsuche.evolution.EvolutionGenome.EvidenceObligation;
import de.regelsuche.evolution.EvolutionGenome.FeatureWeight;
import de.regelsuche.evolution.EvolutionGenome.FitnessSignal;
import de.regelsuche.evolution.EvolutionGenome.GuardPolicy;
import de.regelsuche.evolution.EvolutionGenome.Objective;
import de.regelsuche.evolution.EvolutionGenome.ResourceBudget;
import de.regelsuche.evolution.EvolutionGenome.RewriteGene;
import de.regelsuche.evolution.EvolutionStudyPlan.FitnessComponent;
import de.regelsuche.evolution.EvolutionStudyPlan.FitnessWeight;
import de.regelsuche.evolution.EvolutionStudyPlan.PopulationPolicy;
import de.regelsuche.evolution.EvolutionStudyPlan.StudyBudget;
import de.regelsuche.transform.RewriteKind;
import java.nio.file.Path;
import java.util.List;

/** Writes the preregistered, not-yet-executed v1 population-study contracts. */
public final class EvolutionStudyPlanExample {
    public static final String STUDY_ID = "evolution_population_study_v1";

    private EvolutionStudyPlanExample() {
    }

    public static void main(String[] arguments) {
        Path output = arguments.length == 0
            ? Path.of("build", "reports", "evolution-study-plan")
            : Path.of(arguments[0]);
        write(output);
    }

    public static WrittenContracts write(Path output) {
        EvolutionSplitManifest split = splitManifest();
        EvolutionStudyPlan plan = EvolutionStudyPlan.create(
            STUDY_ID,
            Objective.OPEN_TARGET_OPERATOR,
            split,
            List.of(seedGenome(split)),
            List.of(
                EvolutionMutationKind.ADD_ASSUMPTION,
                EvolutionMutationKind.ADD_RANKING_FEATURE,
                EvolutionMutationKind.COMPOSE_REWRITES,
                EvolutionMutationKind.GENERALIZE_PLACEHOLDER,
                EvolutionMutationKind.REMOVE_ASSUMPTION,
                EvolutionMutationKind.REMOVE_RANKING_FEATURE,
                EvolutionMutationKind.REVERSE_REWRITE,
                EvolutionMutationKind.SPECIALIZE_PLACEHOLDER),
            new PopulationPolicy(
                24,
                8,
                4,
                8,
                3,
                4,
                20260719L),
            List.of(
                new FitnessWeight(FitnessComponent.TRAIN_CASES_NEWLY_SOLVED, 300),
                new FitnessWeight(FitnessComponent.TRAIN_PATH_LENGTH_REDUCTION, 150),
                new FitnessWeight(FitnessComponent.TRAIN_EXPLORED_STATE_REDUCTION, 100),
                new FitnessWeight(FitnessComponent.SUPPORT, 100),
                new FitnessWeight(FitnessComponent.STRUCTURAL_DIVERSITY, 150),
                new FitnessWeight(FitnessComponent.PROJECT_NOVELTY, 50),
                new FitnessWeight(FitnessComponent.ASSUMPTION_SIMPLICITY, 50),
                new FitnessWeight(FitnessComponent.CANDIDATE_COMPLEXITY, 50),
                new FitnessWeight(FitnessComponent.COUNTEREXAMPLE_RISK, 25),
                new FitnessWeight(FitnessComponent.PROOF_COST_PROXY, 25)),
            new StudyBudget(
                4096,
                3072,
                768,
                3,
                16));

        EvolutionStudyContractCodec codec = new EvolutionStudyContractCodec();
        Path manifestPath = output.resolve("evolution-split-manifest.json");
        Path planPath = output.resolve("evolution-study-plan.json");
        codec.write(manifestPath, split);
        codec.write(planPath, plan);
        codec.readSplitManifest(manifestPath);
        codec.readStudyPlan(planPath);
        System.out.println("evolution-study-status=" + plan.status());
        System.out.println("evolution-split-manifest=" + manifestPath);
        System.out.println("evolution-study-plan=" + planPath);
        return new WrittenContracts(split, plan, manifestPath, planPath);
    }

    public static EvolutionSplitManifest splitManifest() {
        return EvolutionSplitManifest.create(
            STUDY_ID,
            hash("evolution-population-corpus-v1"),
            hash("evolution-fitness-feature-schema-v1"),
            List.of(
                caseRef("train_additive_identity", "additive_identity", "train-additive"),
                caseRef("train_common_factor", "common_factor", "train-factor"),
                caseRef("train_square_difference", "square_difference", "train-square")),
            List.of(
                caseRef("validation_complete_square", "complete_square", "validation-square"),
                caseRef("validation_symmetric_factor", "symmetric_factor", "validation-symmetric")),
            List.of(
                caseRef("test_composite_gap", "composite_gap", "test-gap"),
                caseRef("test_mixed_degree", "mixed_degree", "test-degree"),
                caseRef("test_parameterized_identity", "parameterized_identity", "test-parameter")));
    }

    private static EvolutionGenome seedGenome(EvolutionSplitManifest split) {
        RewriteGene gene = new RewriteGene(
            "remove_additive_zero",
            "?x+0",
            "?x",
            RewriteKind.SIMPLIFY,
            true,
            -2,
            4,
            4,
            List.of(),
            List.of(
                EvidenceObligation.SEMANTIC_VALIDATION,
                EvidenceObligation.COUNTEREXAMPLE_SEARCH,
                EvidenceObligation.PROOF_OR_CERTIFICATE,
                EvidenceObligation.NOVELTY_REVIEW,
                EvidenceObligation.HOLDOUT_EVALUATION));
        return EvolutionGenome.create(
            Objective.OPEN_TARGET_OPERATOR,
            split.trainingScope(),
            List.of(gene),
            List.of(
                new FeatureWeight(FitnessSignal.UNSEEN_TRAIN_CASES_SOLVED, 500),
                new FeatureWeight(FitnessSignal.COUNTEREXAMPLE_RISK, -500)),
            GuardPolicy.strictDefault(),
            new ResourceBudget(16, 128, 12, 32, 80),
            List.of("core.ast-rewrite"),
            List.of());
    }

    private static EvolutionSplitManifest.CaseReference caseRef(
        String caseId,
        String familyId,
        String identity
    ) {
        return new EvolutionSplitManifest.CaseReference(
            caseId,
            familyId,
            hash(identity + "-exact-signature"),
            hash(identity + "-alpha-signature"),
            hash(identity + "-input"),
            hash(identity + "-hidden-target"));
    }

    private static String hash(String value) {
        return EvolutionGenome.hash(value);
    }

    public record WrittenContracts(
        EvolutionSplitManifest splitManifest,
        EvolutionStudyPlan studyPlan,
        Path splitManifestPath,
        Path studyPlanPath
    ) {
    }
}
