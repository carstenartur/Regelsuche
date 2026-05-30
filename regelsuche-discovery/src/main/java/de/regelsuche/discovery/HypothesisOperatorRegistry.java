package de.regelsuche.discovery;

import de.regelsuche.transform.ConservativeCompleteSquareHypothesisOperator;
import de.regelsuche.transform.DifferenceOfSquaresPreparationOperator;
import de.regelsuche.transform.HypothesisOperator;
import java.util.List;
import java.util.Optional;

/** Central registry for stable hypothesis operator metadata and deterministic operator selection. */
public final class HypothesisOperatorRegistry {
    private final List<HypothesisOperatorDescriptor> descriptors;

    public HypothesisOperatorRegistry() {
        this(List.of(
            new HypothesisOperatorDescriptor(
                DifferenceOfSquaresPreparationOperator.RULE_ID,
                "difference-of-squares",
                "factorization-bridge",
                DifferenceOfSquaresPreparationOperator::new,
                true,
                List.of("sophie-germain", "bridge", "factorization")
            ),
            new HypothesisOperatorDescriptor(
                ConservativeCompleteSquareHypothesisOperator.RULE_ID,
                "complete-square",
                "quadratic-bridge",
                ConservativeCompleteSquareHypothesisOperator::new,
                true,
                List.of("conservative", "quadratic", "bridge")
            )
        ));
    }

    public HypothesisOperatorRegistry(List<HypothesisOperatorDescriptor> descriptors) {
        this.descriptors = descriptors == null ? List.of() : List.copyOf(descriptors);
        long uniqueIds = this.descriptors.stream().map(HypothesisOperatorDescriptor::id).distinct().count();
        if (uniqueIds != this.descriptors.size()) {
            throw new IllegalArgumentException("hypothesis operator ids must be unique");
        }
    }

    public List<HypothesisOperatorDescriptor> all() {
        return descriptors;
    }

    public Optional<HypothesisOperatorDescriptor> byId(String id) {
        return descriptors.stream().filter(descriptor -> descriptor.id().equals(id)).findFirst();
    }

    public List<HypothesisOperatorDescriptor> enabledFor(DiscoveryOptions options) {
        DiscoveryEngineOptions engine = options == null ? DiscoveryEngineOptions.forProfile(DiscoveryProfile.PURE_REWRITE) : options.engine();
        return enabledFor(engine.profile(), engine);
    }

    public List<HypothesisOperatorDescriptor> enabledFor(DiscoveryProfile profile, DiscoveryEngineOptions options) {
        DiscoveryEngineOptions engine = options == null ? DiscoveryEngineOptions.forProfile(profile) : options;
        if (!engine.enableHypothesisOperators()) {
            return List.of();
        }
        return descriptors.stream().filter(HypothesisOperatorDescriptor::defaultEnabled).toList();
    }

    public List<String> stableIds() {
        return descriptors.stream().map(HypothesisOperatorDescriptor::id).toList();
    }

    public List<String> selectedStableIds(DiscoveryOptions options) {
        return enabledFor(options).stream().map(HypothesisOperatorDescriptor::id).toList();
    }

    public List<HypothesisOperator> selectOperators(DiscoveryOptions options) {
        DiscoveryEngineOptions engine = options == null ? DiscoveryEngineOptions.forProfile(DiscoveryProfile.PURE_REWRITE) : options.engine();
        int maxCandidates = engine.maxHypothesisCandidatesPerOperator();
        return enabledFor(options).stream().map(descriptor -> descriptor.create(maxCandidates)).toList();
    }
}
