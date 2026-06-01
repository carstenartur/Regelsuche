package de.regelsuche.benchmark;

import java.util.List;
import java.util.Set;

public record DiscoveryBenchmarkCase(
        String id,
        String input,
        String target,
        List<List<String>> candidatePaths,
        Set<DiscoveryExpectation> expectations,
        List<String> bridgeRules,
        Set<String> learnedMacros,
        Set<String> reusableMacros) {
    public DiscoveryBenchmarkCase {
        candidatePaths = candidatePaths == null ? List.of() : List.copyOf(candidatePaths);
        expectations = expectations == null ? Set.of() : Set.copyOf(expectations);
        bridgeRules = bridgeRules == null ? List.of() : List.copyOf(bridgeRules);
        learnedMacros = learnedMacros == null ? Set.of() : Set.copyOf(learnedMacros);
        reusableMacros = reusableMacros == null ? Set.of() : Set.copyOf(reusableMacros);
    }
}
