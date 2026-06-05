package de.regelsuche.moves.hypothesis;

import de.regelsuche.ast.Expr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.moves.RewriteMoveKind;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Finds factors shared by several summands, the prerequisite for a factoring
 * move. The shared factor may itself be arbitrarily complex.
 *
 * <ul>
 *   <li>{@code x*(y+1) + z*(y+1)} → factor {@code y + 1}</li>
 *   <li>{@code p*((a+b)^2+1) + q*((a+b)^2+1)} → factor {@code (a + b)^2 + 1}</li>
 * </ul>
 */
public final class CommonFactorHypothesisGenerator implements ParameterHypothesisGenerator {

    @Override
    public String id() {
        return "common-factor";
    }

    @Override
    public List<ParameterHypothesis> propose(ParameterContext context) {
        if (!context.allows(RewriteMoveKind.FACTOR) || context.inputAst().isEmpty()) {
            return List.of();
        }
        List<HypothesisExpressions.SignedTerm> summands =
                HypothesisExpressions.additiveTerms(context.inputAst().get());
        if (summands.size() < 2) {
            return List.of();
        }

        // Canonical factor set per summand, preserving first-seen order for the
        // eventual intersection ordering.
        List<Set<String>> factorSets = new ArrayList<>();
        Map<String, Integer> occurrenceCount = new LinkedHashMap<>();
        for (HypothesisExpressions.SignedTerm summand : summands) {
            Set<String> factors = new LinkedHashSet<>();
            for (Expr factor : HypothesisExpressions.multiplicativeFactors(summand.expr())) {
                if (isTrivialFactor(factor)) {
                    continue;
                }
                String canonical = HypothesisExpressions.format(factor);
                if (factors.add(canonical)) {
                    occurrenceCount.merge(canonical, 1, Integer::sum);
                }
            }
            factorSets.add(factors);
        }

        List<ParameterHypothesis> result = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : occurrenceCount.entrySet()) {
            if (entry.getValue() < summands.size()) {
                continue; // not present in every summand
            }
            String canonical = entry.getKey();
            result.add(new ParameterHypothesis(
                    RewriteMoveKind.FACTOR,
                    "factor",
                    canonical,
                    canonical,
                    HypothesisSource.COMMON_FACTOR,
                    0.8,
                    "shared factor across " + summands.size() + " summands",
                    List.of("summands=" + summands.size())));
        }
        result.sort(ParameterHypothesis.CANONICAL_ORDER);
        return List.copyOf(result);
    }

    private boolean isTrivialFactor(Expr factor) {
        return factor instanceof NumberExpr;
    }
}
