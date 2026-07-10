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
        List<DiscoveryOperatorProvider.DiscoveryOperatorDefinition> definitions = provider.operators();
        if (definitions == null) {
            throw new IllegalArgumentException("Provider '" + provider.id() + "' returned null operators");
        }
        for (DiscoveryOperatorProvider.DiscoveryOperatorDefinition definition : definitions) {
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

    public String operatorIdForRule(String ruleId) {
        if (ruleId == null || ruleId.isBlank()) {
            return "";
        }
        for (Map.Entry<String, DiscoveryOperatorProvider.DiscoveryOperatorDefinition> entry : operatorsById.entrySet()) {
            if (entry.getValue().producedRuleIds().contains(ruleId)) {
                return entry.getKey();
            }
        }
        return "";
    }

    public List<HypothesisOperator> operatorsFor(OperatorProfile profile) {
        if (profile == null || profile.enabledOperatorIds().isEmpty()) {
            return List.of();
        }
        List<HypothesisOperator> operators = new ArrayList<>();
        for (String operatorId : profile.enabledOperatorIds()) {
            if (operatorId == null || operatorId.isBlank()) {
                continue;
            }
            DiscoveryOperatorProvider.DiscoveryOperatorDefinition definition = operatorsById.get(operatorId);
            String resolvedId = operatorId;
            if (definition == null) {
                // Also accept rule IDs (e.g. "hypothesis_*") in addition to definition IDs
                String defId = operatorIdForRule(operatorId);
                if (!defId.isBlank()) {
                    definition = operatorsById.get(defId);
                    resolvedId = defId;
                }
            }
            // Check the disabled set after resolution so that disabling by definition ID
            // also suppresses operators looked up via their rule ID, and vice versa.
            if (disabledOperatorIds.contains(operatorId) || disabledOperatorIds.contains(resolvedId)) {
                continue;
            }
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
