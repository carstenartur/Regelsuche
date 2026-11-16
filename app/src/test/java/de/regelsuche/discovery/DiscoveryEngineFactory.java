package de.regelsuche.discovery;

import de.regelsuche.mining.GoalAwareMacroMoveSelector;
import de.regelsuche.mining.MacroMoveTransformationEngine;
import de.regelsuche.transform.HypothesisOperator;
import de.regelsuche.transform.HypothesisTransformationEngine;
import de.regelsuche.transform.TransformationEngine;
import java.util.List;

/** Composes discovery engines in deterministic order: base rewrite → hypothesis operators → learned macro moves. */
public final class DiscoveryEngineFactory {
    private final HypothesisOperatorRegistry registry;

    public DiscoveryEngineFactory() {
        this(new HypothesisOperatorRegistry());
    }

    public DiscoveryEngineFactory(HypothesisOperatorRegistry registry) {
        this.registry = registry == null ? new HypothesisOperatorRegistry() : registry;
    }

    public TransformationEngine create(TransformationEngine baseEngine, DiscoveryOptions options) {
        return create(baseEngine, options, null);
    }

    /** Explicit experimental scheduling entry point; the production default awaits #953/#745 qualification. */
    public de.regelsuche.search.moves.ProviderTransformationEngine createMoveEngine(
        TransformationEngine baseEngine, DiscoveryOptions options, GoalAwareMacroMoveSelector macroSelector,
        de.regelsuche.search.moves.MovePriorityPolicy policy, de.regelsuche.search.moves.MoveContext context
    ) {
        var inventory = create(baseEngine, options, macroSelector);
        return new de.regelsuche.search.moves.ProviderTransformationEngine(
            de.regelsuche.search.moves.MoveProviders.from(inventory), policy, context);
    }

    public TransformationEngine create(
        TransformationEngine baseEngine,
        DiscoveryOptions options,
        GoalAwareMacroMoveSelector macroSelector
    ) {
        if (baseEngine == null) {
            throw new IllegalArgumentException("baseEngine is required");
        }
        DiscoveryOptions resolved = options == null ? DiscoveryOptions.forProfile(DiscoveryProfile.PURE_REWRITE) : options;
        DiscoveryEngineOptions engineOptions = resolved.engine();
        TransformationEngine engine = baseEngine;
        if (engineOptions.enableHypothesisOperators()) {
            List<HypothesisOperator> operators = registry.selectOperators(resolved);
            if (!operators.isEmpty()) {
                engine = new HypothesisTransformationEngine(
                    engine,
                    operators,
                    engineOptions.maxHypothesisCandidatesPerOperator() * operators.size()
                );
            }
        }
        if (engineOptions.enableMacroReuse() && macroSelector != null) {
            engine = new MacroMoveTransformationEngine(engine, macroSelector);
        }
        return engine;
    }
}
