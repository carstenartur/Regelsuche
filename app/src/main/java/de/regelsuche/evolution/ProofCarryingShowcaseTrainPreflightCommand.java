package de.regelsuche.evolution;

import de.regelsuche.evolution.EvolutionStudyPlan.FitnessComponent;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Set;

/**
 * Checkout-local TRAIN-only characterization for a future showcase authority.
 *
 * <p>The command deliberately stops at terminal selection evidence. It creates
 * no candidate freeze, clock boundary, public randomness, seed, or FINAL TEST
 * material. The consumed v1 authority must never be reconstructed by invoking
 * this command after the fact.</p>
 */
public final class ProofCarryingShowcaseTrainPreflightCommand {
    public static final String STATUS =
        "TRAIN_PREFLIGHT_COMPLETE_FINAL_TEST_UNSEEN";

    private ProofCarryingShowcaseTrainPreflightCommand() {
    }

    public static void main(String[] arguments) {
        if (arguments.length != 2) {
            throw new IllegalArgumentException(
                "usage: <showcase-plan.json> <output-directory>");
        }
        WrittenPreflight result = execute(
            Path.of(arguments[0]), Path.of(arguments[1]));
        System.out.println("showcaseTrainPreflightStatus=" + STATUS);
        System.out.println(
            "showcaseTrainPreflightSelectionStatus="
                + result.selectionStatus());
        System.out.println(
            "showcaseTrainPreflightSelectionHash="
                + result.selectionEvidenceHash());
        System.out.println(
            "showcaseTrainPreflightOutput=" + result.outputDirectory());
    }

    public static WrittenPreflight execute(
        Path showcasePlanPath,
        Path outputDirectory
    ) {
        ProofCarryingShowcasePlan showcasePlan =
            ProofCarryingShowcasePlan.read(showcasePlanPath);
        var configuration =
            ProofCarryingShowcaseTrainAndFreezeCommand.configuration(showcasePlan);
        Set<FitnessComponent> requiredComponents = Set.copyOf(
            configuration.study().fitnessWeights().stream()
                .map(value -> value.component())
                .toList());
        var evaluator = RewriteProgramFitnessComposition
            .exactRationalTrainEvaluator(
                configuration.trainSuite(), requiredComponents);
        var retained =
            new RetainedProtocolBoundEvolutionRewriteProgramPopulationRunner()
                .run(
                    configuration.study(),
                    configuration.splitManifest(),
                    configuration.trainSuite(),
                    configuration.seeds(),
                    configuration.mutationCatalog(),
                    evaluator);
        var selection = ProofCarryingShowcaseTrainSelectionEvidence.create(
            showcasePlan,
            retained,
            configuration.study(),
            configuration.seeds());
        return write(
            outputDirectory,
            showcasePlan,
            configuration,
            retained,
            selection);
    }

    private static WrittenPreflight write(
        Path outputDirectory,
        ProofCarryingShowcasePlan showcasePlan,
        ProofCarryingShowcaseTrainAndFreezeCommand.TrainConfiguration configuration,
        ProtocolBoundRetainedEvolutionRewriteProgramPopulationRun retained,
        ProofCarryingShowcaseTrainSelectionEvidence selection
    ) {
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
            EvolutionRewriteProgramCandidate seed = configuration.seeds().get(index);
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
        Path output = ProofCarryingShowcaseTrainAndFreezeCommand.publishAtomically(
            outputDirectory,
            artifacts,
            "train-selection-evidence.json",
            selection.toCanonicalJson());
        return new WrittenPreflight(
            output,
            selection.status(),
            selection.contentHash());
    }

    public record WrittenPreflight(
        Path outputDirectory,
        String selectionStatus,
        String selectionEvidenceHash
    ) {
        public WrittenPreflight {
            if (outputDirectory == null) {
                throw new IllegalArgumentException(
                    "preflight output directory is required");
            }
            if (!ProofCarryingShowcaseTrainSelectionEvidence.ELIGIBLE.equals(
                    selectionStatus)
                    && !ProofCarryingShowcaseTrainSelectionEvidence.NONE.equals(
                        selectionStatus)) {
                throw new IllegalArgumentException(
                    "invalid preflight selection status");
            }
            EvolutionGenome.requireSha256(
                selectionEvidenceHash, "selectionEvidenceHash");
        }
    }
}