package de.regelsuche.inventory;

import static de.regelsuche.inventory.LifecycleWorkAccount.Phase.*;
import static de.regelsuche.inventory.WorkReplacementTypedExecution.charge;
import de.regelsuche.evolution.*;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.search.moves.TypedSourceOnlySearch;
import de.regelsuche.search.program.CompiledAstReplayCodec;
import java.util.List;

/** Audited adapters to the existing learner, policy selector and theorem restore. */
public final class WorkReplacementLearning {
    private static final CompiledAstReplayCodec CODEC = new CompiledAstReplayCodec();
    private WorkReplacementLearning() {}
    public record Acquired(TraceRewriteStrategyLearner.FrozenStrategy formation, TypedLearnedMoveInventory inventory,
            CheckedLearnedSchemaModel model) {}
    public static Acquired acquire(WorkReplacementManifest manifest, EvolutionGenome genome,
            List<TraceRewriteStrategyLearner.Input> inputs, TraceRewriteStrategyLearner.Limits limits,
            WorkReplacementExperiment.Journal journal, String prefix) {
        manifest.requireTrainingSources(inputs.stream().map(input -> identity(input.expression())).toList());
        var formation = new TraceRewriteStrategyLearner().learn(genome, inputs, limits);
        String formationJson = formation.toCanonicalJson();
        long search = Math.addExact(formation.trainingSearchWorkUnits(), formation.trainingPrimitiveWorkUnits());
        charge(journal, prefix + "/train", TRAINING_SEARCH, search, TraceRewriteStrategyLearner.REVISION, formationJson);
        long proof = java.util.stream.LongStream.of(formation.trainingReplayWorkUnits(), formation.trainingReplayPrimitiveWorkUnits(),
            formation.trainingExactWorkUnits(), formation.trainingMinimalityWorkUnits(), formation.trainingReferenceSupplementaryWorkUnits())
            .reduce(0, Math::addExact);
        charge(journal, prefix + "/formation", RULE_FORMATION_PROOF, proof, TraceRewriteStrategyLearner.REVISION, formationJson);
        var inventory = formation.typedMoves();
        String genomeJson = genome.toCanonicalJson();
        charge(journal, prefix + "/compile", COMPILATION, inventory.providers().size(), "compiled-provider-count/v1", genomeJson);
        var model = inventory.checkedSchemas();
        String modelJson = model.toCanonicalJson();
        charge(journal, prefix + "/schemas", RULE_FORMATION_PROOF, model.formationWork(), CheckedLearnedSchemaModel.REVISION, modelJson);
        materialized(journal, prefix + "/formation-output", formationJson);
        materialized(journal, prefix + "/genome-output", genomeJson);
        materialized(journal, prefix + "/model-output", modelJson);
        return new Acquired(formation, inventory, model);
    }
    public static TypedSourcePolicySelection.Frozen select(WorkReplacementManifest manifest,
            List<TypedPolicySelection.TrainingTask> tasks, List<TypedSourcePolicySelection.Profile> profiles,
            TypedSourceOnlySearch.Objective objective, WorkReplacementExperiment.Journal journal, String prefix) {
        manifest.requireTrainingSources(tasks.stream().map(task -> CODEC.encodeExpression(task.problem().source())).toList());
        var quality = manifest.quality();
        var selected = quality.mode() == WorkReplacementManifest.QualityMode.SUFFICIENT_QUALITY_MIN_WORK
            ? TypedSourcePolicySelection.trainUntil(tasks, profiles, objective, quality.maximumScore(), quality.continuation())
            : new TypedPolicySelection().trainSourceOnly(tasks, profiles, objective);
        String raw = selected.toCanonicalJson();
        charge(journal, prefix + "/selection", SELECTION_TRAINING, selected.trainingWork(),
            selected.revision(), raw);
        materialized(journal, prefix + "/selection-output", raw);
        if (!selected.accountingComplete()) journal.incomplete();
        return selected;
    }
    public static CheckedLearnedSchemaModel restore(String json, String inventoryHash,
            WorkReplacementExperiment.Journal journal, String prefix) {
        var restored = CheckedLearnedSchemaModel.load(json, inventoryHash);
        charge(journal, prefix + "/restore", RESTORE_REPROOF, restored.loadWork(), CheckedLearnedSchemaModel.REVISION, json);
        return restored;
    }
    public static TypedLearnedMoveInventory primitives(EvolutionGenome genome, WorkReplacementExperiment.Journal journal, String prefix) {
        var inventory = TypedLearnedMoveInventory.primitives(genome);
        String genomeJson = genome.toCanonicalJson();
        charge(journal, prefix + "/compile", COMPILATION, inventory.primitiveProviders().size(), "compiled-provider-count/v1", genomeJson);
        materialized(journal, prefix + "/genome-output", genomeJson);
        return inventory;
    }
    private static void materialized(WorkReplacementExperiment.Journal journal, String prefix, String text) {
        charge(journal, prefix, OUTPUT, text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length, "utf8-materialized-bytes/v1", "");
    }
    public static String identity(String expression) { return CODEC.encodeExpression(new ExpressionParser().parseTerm(expression)); }
}
