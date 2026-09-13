package de.regelsuche.evolution;

import de.regelsuche.evolution.EvolutionRewriteProgramValidationEvidence.CandidateEvidence;
import de.regelsuche.evolution.EvolutionRewriteProgramValidationEvidence.PairedCase;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Real combined TRAIN → one-shot VALIDATION → conservative downstream handoff entry point. */
public final class EvolutionRewriteProgramValidationRunner {
    public EvolutionRewriteProgramValidationSelection executeOnce(EvolutionRewriteProgramValidationPlan plan,
        EvolutionRewriteProgramStudyPlan study, EvolutionSplitManifest manifest,
        ProtocolBoundRetainedEvolutionRewriteProgramPopulationRun train,
        Path privateReveal, FileEvolutionRewriteProgramValidationAttemptStore store) throws IOException {
        Objects.requireNonNull(privateReveal, "privateReveal");
        return executeOnce(plan, study, manifest, train,
            () -> new EvolutionRewriteProgramHeldOutRevealCodec().readPrivate(privateReveal), store);
    }

    public EvolutionRewriteProgramValidationSelection executeOnce(EvolutionRewriteProgramValidationPlan plan,
        EvolutionRewriteProgramStudyPlan study, EvolutionSplitManifest manifest,
        ProtocolBoundRetainedEvolutionRewriteProgramPopulationRun train,
        RevealLoader loader, FileEvolutionRewriteProgramValidationAttemptStore store) throws IOException {
        Objects.requireNonNull(plan, "plan").requireInputs(study, manifest, train);
        Objects.requireNonNull(loader, "loader");
        var reservation = Objects.requireNonNull(store, "store").reserve(plan);
        var authorization = EvolutionRewriteProgramHeldOutRevealAuthorization.validation(
            study, manifest, train, reservation);
        EvolutionRewriteProgramHeldOutRevealBundle.OpenedReveal opened = null;
        String revealFailure = "";
        try {
            opened = Objects.requireNonNull(loader.load(), "loaded reveal").open(authorization, plan.commitment());
        } catch (IOException | RuntimeException exception) {
            revealFailure = "REVEAL_FAILED:" + exception.getClass().getSimpleName();
        }
        var evaluator = new NativeEvolutionRewriteProgramValidationEvaluator();
        List<CandidateEvidence> candidates = new ArrayList<>();
        for (var configuration : plan.configurations()) {
            List<PairedCase> cases;
            if (opened == null) {
                String reason = revealFailure;
                cases = plan.cases().stream().map(item -> PairedCase.unavailable(item, reason)).toList();
            } else {
                cases = evaluator.evaluate(configuration, opened.cases());
            }
            candidates.add(CandidateEvidence.create(configuration, cases));
        }
        var selection = EvolutionRewriteProgramValidationSelection.create(plan, candidates);
        store.writeSelection(selection);
        return selection;
    }

    /** The loader receives no case values or authorization until the study is durably reserved. */
    @FunctionalInterface
    public interface RevealLoader {
        EvolutionRewriteProgramHeldOutRevealBundle load() throws IOException;
    }
}
