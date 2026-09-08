package de.regelsuche.representation;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Shared typed formation and concrete principal replay for representation preparation. */
public final class RepresentationPreparation {
    private RepresentationPreparation() { }

    /**
     * Typed entry point for representation principals. Neither the source nor
     * its relation is coerced into scalar Expr equality. The same finite budget
     * covers bridge formation and concrete downstream replay; independent audit
     * verification reruns each retained stage with its original bounded budget.
     */
    public static <S, T, C, R, D> Outcome<T, C, R, D> analyze(
        S source,
        RepresentationBridge<S, T, C> bridge,
        Set<RepresentationBridge.Relation> acceptedRelations,
        RepresentationBridge<T, R, D> principal,
        RepresentationBridge.Budget budget
    ) {
        var formation = bridge.analyze(source, budget);
        if (!formation.represented()) {
            return new Outcome<>(formation, Optional.empty(), false, formation.detailCode());
        }
        if (!acceptedRelations.contains(formation.relation().orElseThrow()) || !bridge.verify(source, formation)) {
            return new Outcome<>(formation, Optional.empty(), false, "RELATION_OR_FORMATION_REJECTED");
        }
        T represented = formation.representation().orElseThrow();
        var replay = principal.analyze(represented,
            new RepresentationBridge.Budget(formation.work().remainingWorkUnits()));
        boolean accepted = replay.represented() && principal.verify(represented, replay);
        return new Outcome<>(formation, Optional.of(replay), accepted,
            accepted ? "TYPED_REPRESENTATION_AND_PRINCIPAL_VERIFIED" : replay.detailCode());
    }

    public record Outcome<T, C, R, D>(
        RepresentationBridge.Result<T, C> formation,
        Optional<RepresentationBridge.Result<R, D>> replay,
        boolean accepted,
        String detailCode
    ) {
        public Outcome {
            Objects.requireNonNull(formation, "formation");
            Objects.requireNonNull(replay, "replay");
            Objects.requireNonNull(detailCode, "detailCode");
            if (accepted && (!formation.represented() || replay.isEmpty() || !replay.orElseThrow().represented())) {
                throw new IllegalArgumentException("accepted preparation requires formation and concrete replay");
            }
        }

        public RepresentationBridge.WorkLedger work() {
            int consumed = formation.work().consumedWorkUnits()
                + replay.map(result -> result.work().consumedWorkUnits()).orElse(0);
            return RepresentationBridge.WorkLedger.of(
                formation.work().configuredWorkUnits(), consumed);
        }
    }

}
