package de.regelsuche.evolution;

import de.regelsuche.search.moves.*;
import de.regelsuche.transform.Transformation;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import static de.regelsuche.search.moves.IncrementalProviderContract.*;

/** P03 executable RED seam: deliberately delegates to the existing eager implementation. */
public final class CheckedSchemaMatcherPlan {
    public static final String REVISION = "regelsuche.checked-schema-matcher-plan/v1";
    private final MoveProvider eager;
    private final Definition definition;

    private CheckedSchemaMatcherPlan(CheckedLearnedSchemaModel model, int maximum,
            Map<String, Double> utilities, Set<String> included) {
        var providers = model.providers(maximum, utilities, included);
        if (providers.isEmpty()) throw new IllegalArgumentException("empty prepared schema set");
        eager = providers.getFirst();
        definition = new Definition(IncrementalProviderContract.REVISION, eager.descriptor().id(),
            Kind.REGISTERED_SCHEMA, SchematicProofPlan.hash(model.toCanonicalJson()),
            CheckedLearnedSchemaModel.CHECKER_REVISION + ";" + REVISION,
            Transport.TYPED_AST_JSON, Mathematics.EXACT, null);
    }

    public static CheckedSchemaMatcherPlan prepare(CheckedLearnedSchemaModel model, int maximum,
            Map<String, Double> utilities, Set<String> included) {
        return new CheckedSchemaMatcherPlan(model, maximum, utilities, included);
    }
    public long compilationWork() { return 1; }
    public RegisteredIncrementalMoveProvider provider() {
        var registration = new Registration(definition, (state, context, meter) -> new Source() {
            private List<Transformation> candidates;
            private int index;
            private boolean complete;
            @Override public Optional<Transformation> next(long allowance) {
                if (candidates == null) {
                    var batch = eager.candidates(state, context);
                    meter.charge(Operation.LOAD, batch.work().totalWorkUnits());
                    meter.charge(batch.work().candidateWork());
                    candidates = batch.moves().stream().map(SearchMove::transformation).toList();
                    complete = batch.complete();
                }
                return index < candidates.size() ? Optional.of(candidates.get(index++)) : Optional.empty();
            }
            @Override public Status status() {
                return candidates != null && index >= candidates.size()
                    ? (complete ? Status.EXHAUSTED : Status.INCONCLUSIVE) : Status.READY;
            }
        });
        return new RegisteredIncrementalMoveProvider(eager.descriptor(), definition,
            new Registry(List.of(registration)));
    }
}
