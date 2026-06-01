package de.regelsuche.search;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class SearchSpaceAnalyticsService {
    public SearchSpaceAnalytics analyze(List<ProofStep> transitions) {
        Set<String> states = new HashSet<>();
        Map<String, Integer> outgoing = new HashMap<>();
        Map<String, Integer> incoming = new HashMap<>();
        int macroUsage = 0;
        for (ProofStep step : transitions) {
            states.add(canonical(step.from()));
            states.add(canonical(step.to()));
            outgoing.merge(canonical(step.from()), 1, Integer::sum);
            incoming.merge(canonical(step.to()), 1, Integer::sum);
            if (step.ruleId().startsWith("macro.") || step.ruleId().contains("macro")) {
                macroUsage++;
            }
        }
        long convergent = incoming.values().stream().filter(count -> count > 1).count();
        double branching = outgoing.isEmpty() ? 0.0d : outgoing.values().stream().mapToInt(Integer::intValue).average().orElse(0.0d);
        return new SearchSpaceAnalytics(transitions.size(), states.size(), (int) convergent, macroUsage, branching);
    }

    private String canonical(String state) {
        return state == null ? "" : state.replaceAll("\\s+", "");
    }
}
