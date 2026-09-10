package de.regelsuche.inventory;

import de.regelsuche.search.moves.*;
import java.util.List;
import java.util.TreeSet;

/** Persists observed search use without changing scoped minimality, confidence or mathematical authorization. */
public final class RuleUtilityFeedback {
    private RuleUtilityFeedback() {}
    public static RuleUtilityEvidence update(RuleUtilityEvidence prior, String ruleId, List<MoveSearch.Result> observations, MoveContext.Phase phase) {
        return update(prior, ruleId, observations, phase, ignored -> {});
    }
    public static RuleUtilityEvidence update(RuleUtilityEvidence prior, String ruleId, List<MoveSearch.Result> observations,
            MoveContext.Phase phase, java.util.function.LongConsumer workObserver) {
        if (phase != MoveContext.Phase.TRAIN) throw new IllegalArgumentException("utility feedback requires TRAIN");
        java.util.Objects.requireNonNull(workObserver, "workObserver");
        long applications = 0, failures = 0, duplicates = 0, successes = 0, deadEnds = 0, direct = 0;
        long inspected = 0;
        var capabilities = new TreeSet<>(prior.capabilitiesUnlocked());
        for (var result : observations) {
            for (var event : result.events()) {
                inspected++;
                if (!event.move().ruleId().equals(ruleId)) continue;
                applications++; direct = Math.addExact(direct, event.move().applicationCost());
                if (event.decision() == MoveSearch.Decision.DUPLICATE) duplicates++;
                if (event.decision() == MoveSearch.Decision.PROOF_REJECTED || event.decision() == MoveSearch.Decision.ASSUMPTION_REJECTED) failures++;
            }
            for (var step : result.witness()) if (step.move().ruleId().equals(ruleId)) {
                successes++; capabilities.addAll(step.move().capabilityDelta());
            }
            deadEnds += result.deadEndStates().stream().filter(state -> state.previousRule().equals(ruleId)).count();
            inspected = Math.addExact(inspected, (long) result.witness().size() + result.deadEndStates().size());
        }
        workObserver.accept(inspected);
        return new RuleUtilityEvidence(prior.schema(), prior.observedPathSteps(), prior.bestKnownPrimitiveSteps(), prior.boundedMinimumProved(),
            prior.macroSearchDepth(), applications == 0 ? prior.directApplicationWork() : direct / applications, prior.proofReplayWork(),
            Math.addExact(prior.successfulApplications(), successes), Math.addExact(prior.failedApplications(), failures),
            Math.addExact(prior.duplicateSuccessors(), duplicates), Math.addExact(prior.deadEnds(), deadEnds), Math.addExact(prior.applicabilityCount(), applications),
            List.copyOf(capabilities), prior.firstReachabilityGains(), prior.confidence(), Math.addExact(prior.evidenceCount(), applications), prior.reference());
    }
}
