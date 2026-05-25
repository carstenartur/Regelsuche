package de.regelsuche.validation;

import de.regelsuche.transform.PatternRewriteRule;
import java.util.List;

public interface CriticalPairService {
    CriticalPairReport analyzeCriticalPairs(List<PatternRewriteRule> rules);

    record CriticalPair(String sourceExpression, String leftBranch, String rightBranch, String leftRuleId, String rightRuleId,
                        String overlapPath) {
    }

    record CriticalPairReport(List<CriticalPair> criticalPairs, MathematicalAlgorithmRegistry.AlgorithmExecutionResult result) {
    }
}
