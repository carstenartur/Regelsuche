package de.regelsuche.docs;

import de.regelsuche.transform.HypothesisOperator;
import java.util.List;
import java.util.function.Supplier;

public interface DiscoveryOperatorProvider {
    String id();

    List<DiscoveryOperatorDefinition> operators();

    record DiscoveryOperatorDefinition(String id, Supplier<HypothesisOperator> factory, List<String> producedRuleIds) {
        public DiscoveryOperatorDefinition {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("Operator id must not be blank");
            }
            if (factory == null) {
                throw new IllegalArgumentException("Operator factory must not be null");
            }
            producedRuleIds = producedRuleIds == null ? List.of() : List.copyOf(producedRuleIds);
        }
    }
}
