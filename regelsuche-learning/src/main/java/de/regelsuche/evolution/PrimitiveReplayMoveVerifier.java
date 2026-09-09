package de.regelsuche.evolution;

import de.regelsuche.search.moves.MoveContext;
import de.regelsuche.search.moves.MoveState;
import de.regelsuche.search.moves.MoveVerifier;
import de.regelsuche.search.moves.SearchMove;
import de.regelsuche.transform.MeasuredTransformationEngine;
import de.regelsuche.transform.MeasuredTransformationEngines;
import de.regelsuche.transform.PreparedAstRewriteTransformationEngine;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Re-executes every bound primitive and independently checks exact polynomial identity. */
public final class PrimitiveReplayMoveVerifier implements MoveVerifier {
    private final Map<String, MeasuredTransformationEngine> primitives;
    private final ExactPolynomialPatternIdentityVerifier exact = new ExactPolynomialPatternIdentityVerifier();
    public PrimitiveReplayMoveVerifier(EvolutionGenome inventory) {
        primitives = new EvolutionGenomeCompiler().compile(inventory).rules().stream().collect(Collectors.toUnmodifiableMap(
            rule -> rule.id(), rule -> MeasuredTransformationEngines.counting(
                new PreparedAstRewriteTransformationEngine(List.of(rule), Integer.MAX_VALUE, Integer.MAX_VALUE))));
    }
    @Override public Verification verify(MoveState source, SearchMove move, MoveContext context) {
        long work = 1;
        var receipts = new ArrayList<String>();
        if (!move.assumptions().isEmpty() || move.transformation().exactTheoryStepCount() != 0
                || move.primitiveExpansion().isEmpty()
                || !move.primitiveExpansion().equals(SearchMove.primitiveLeaves(move.transformation())))
            return new Verification(false, work, receipts, "UNSUPPORTED_OR_MISSING_BOUND_PRIMITIVE_PROOF");
        move.provenance().requireSource(source.expression());
        String current = source.expression();
        for (var leaf : move.primitiveExpansion()) {
            var engine = primitives.get(leaf.rule());
            if (engine == null || leaf.primitiveStepCount() != 1 || !leaf.assumptions().isEmpty())
                return new Verification(false, work, receipts, "NOT_A_FROZEN_PRIMITIVE");
            var batch = engine.transformMeasured(current);
            work = Math.addExact(work, batch.workMetrics().totalWorkUnits() + batch.transformations().size());
            if (batch.transformations().stream().noneMatch(candidate -> candidate.transformedExpression().equals(leaf.transformedExpression())
                    && candidate.primitiveRuleIds().equals(leaf.primitiveRuleIds()) && candidate.assumptions().equals(leaf.assumptions())))
                return new Verification(false, work, receipts, "PRIMITIVE_REPLAY_MISMATCH");
            var proof = exact.verify(EvolutionGenomeCompiler.parsePattern(current), EvolutionGenomeCompiler.parsePattern(leaf.transformedExpression()));
            work = Math.addExact(work, (long) proof.visitedNodes() + proof.generatedTerms());
            if (!proof.proved()) return new Verification(false, work, receipts, proof.detailCode());
            receipts.add(proof.proofHash()); current = leaf.transformedExpression();
        }
        boolean bound = current.equals(move.transformation().transformedExpression());
        return new Verification(bound, work, receipts, bound ? "FROZEN_PRIMITIVE_REPLAY_AND_EXACT_IDENTITY" : "UNBOUND_ENDPOINT");
    }
}
