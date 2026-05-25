package de.regelsuche.validation;

import de.regelsuche.transform.PatternRewriteRule;
import java.util.List;

public interface CompletionService {
    CompletionReport analyzeCompletion(List<PatternRewriteRule> rules);

    record CompletionCandidate(String fromExpression, String toExpression, String reason) {
    }

    record CompletionReport(
        boolean confluent,
        List<CriticalPairService.CriticalPair> criticalPairs,
        List<CompletionCandidate> completionCandidates,
        MathematicalAlgorithmRegistry.AlgorithmExecutionResult result
    ) {
    }
}
