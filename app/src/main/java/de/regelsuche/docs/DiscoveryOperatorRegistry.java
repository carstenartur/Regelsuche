package de.regelsuche.docs;

import de.regelsuche.transform.HypothesisOperator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class DiscoveryOperatorRegistry {
    private final Map<String, DiscoveryOperatorProvider.DiscoveryOperatorDefinition> operatorsById = new LinkedHashMap<>();
    private final Set<String> disabledOperatorIds = new LinkedHashSet<>();

    public DiscoveryOperatorRegistry register(DiscoveryOperatorProvider provider) {
        if (provider == null) {
            throw new IllegalArgumentException("Provider must not be null");
        }
        for (DiscoveryOperatorProvider.DiscoveryOperatorDefinition definition : provider.operators()) {
            operatorsById.put(definition.id(), definition);
        }
        return this;
    }

    public DiscoveryOperatorRegistry disable(String operatorId) {
        if (operatorId != null && !operatorId.isBlank()) {
            disabledOperatorIds.add(operatorId);
        }
        return this;
    }

    public DiscoveryOperatorRegistry enable(String operatorId) {
        if (operatorId != null && !operatorId.isBlank()) {
            disabledOperatorIds.remove(operatorId);
        }
        return this;
    }

    public boolean isEnabled(String operatorId) {
        return !disabledOperatorIds.contains(operatorId);
    }

    public List<String> availableOperatorIds() {
        return List.copyOf(operatorsById.keySet());
    }

    public Set<String> operatorRuleIds() {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (DiscoveryOperatorProvider.DiscoveryOperatorDefinition definition : operatorsById.values()) {
            ids.addAll(definition.producedRuleIds());
        }
        return Set.copyOf(ids);
    }

    public List<HypothesisOperator> operatorsFor(OperatorProfile profile) {
        if (profile == null || profile.enabledOperatorIds().isEmpty()) {
            return List.of();
        }
        List<HypothesisOperator> operators = new ArrayList<>();
        for (String operatorId : profile.enabledOperatorIds()) {
            if (operatorId == null || operatorId.isBlank() || disabledOperatorIds.contains(operatorId)) {
                continue;
            }
            DiscoveryOperatorProvider.DiscoveryOperatorDefinition definition = operatorsById.get(operatorId);
            if (definition == null) {
                continue;
            }
            operators.add(definition.factory().get());
        }
        return List.copyOf(operators);
    }

    public record OperatorProfile(List<String> enabledOperatorIds) {
        public OperatorProfile {
            enabledOperatorIds = enabledOperatorIds == null ? List.of() : List.copyOf(enabledOperatorIds);
        }
    }
}
