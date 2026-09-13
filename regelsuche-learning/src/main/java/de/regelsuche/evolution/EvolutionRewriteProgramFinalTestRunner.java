package de.regelsuche.evolution;

import de.regelsuche.evolution.EvolutionRewriteProgramValidationEvidence.PairedCase;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** Explicit one-shot FINAL TEST adapter for the complete retained combined-program selection. */
public final class EvolutionRewriteProgramFinalTestRunner {
    public EvolutionRewriteProgramFinalTestEvaluation executeOnce(EvolutionRewriteProgramFinalTestPlan plan,
        EvolutionRewriteProgramStudyPlan study, EvolutionSplitManifest manifest,
        ProtocolBoundRetainedEvolutionRewriteProgramPopulationRun train,
        FileEvolutionRewriteProgramValidationAttemptStore validationStore, Path privateReveal,
        FileEvolutionRewriteProgramFinalTestAttemptStore store) throws IOException {
        Objects.requireNonNull(privateReveal, "privateReveal");
        return executeOnce(plan, study, manifest, train, validationStore,
            () -> new EvolutionRewriteProgramHeldOutRevealCodec().readPrivate(privateReveal), store);
    }

    public EvolutionRewriteProgramFinalTestEvaluation executeOnce(EvolutionRewriteProgramFinalTestPlan plan,
        EvolutionRewriteProgramStudyPlan study, EvolutionSplitManifest manifest,
        ProtocolBoundRetainedEvolutionRewriteProgramPopulationRun train,
        FileEvolutionRewriteProgramValidationAttemptStore validationStore, RevealLoader loader,
        FileEvolutionRewriteProgramFinalTestAttemptStore store) throws IOException {
        Objects.requireNonNull(plan, "plan").requireInputs(study, manifest, train);
        Objects.requireNonNull(loader, "loader");
        var reservation = Objects.requireNonNull(store, "store").reserve(plan, validationStore);
        var authorization = EvolutionRewriteProgramHeldOutRevealAuthorization.finalTest(study, manifest, train, reservation);
        EvolutionRewriteProgramHeldOutRevealBundle.OpenedReveal opened = null;
        String revealFailure = "";
        try {
            opened = Objects.requireNonNull(loader.load(), "loaded reveal").open(authorization, plan.commitment());
        } catch (IOException | RuntimeException exception) {
            revealFailure = "REVEAL_FAILED:" + exception.getClass().getSimpleName();
        }
        List<PairedCase> cases;
        if (opened == null) {
            String failure = revealFailure;
            cases = plan.cases().stream().map(item -> PairedCase.unavailable(item, failure)).toList();
        } else {
            // The same native paired search and correctness implementation; no TRAIN-labelled proxy evidence.
            cases = new NativeEvolutionRewriteProgramValidationEvaluator().evaluate(plan.selectedConfiguration(), opened.cases());
        }
        var evaluation = EvolutionRewriteProgramFinalTestEvaluation.create(plan, cases);
        store.writeEvaluation(reservation, evaluation);
        return evaluation;
    }

    @FunctionalInterface
    public interface RevealLoader {
        EvolutionRewriteProgramHeldOutRevealBundle load() throws IOException;
    }
}
