package de.regelsuche.web;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.regelsuche.assumption.*;
import de.regelsuche.solver.ir.SolverIr;
import de.regelsuche.solver.ir.StructuredAssumptionParser;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/** Exact typed premise and complement witnesses. Conflicting decisive observations use the core portfolio's fail-closed aggregation. */
final class CandidateGuardReview {
    private static final JsonMapper JSON = new JsonMapper();
    private static final AssumptionEvaluatorPortfolio PORTFOLIO = new AssumptionEvaluatorPortfolio(
        List.of(new PremiseWitness(false), new PremiseWitness(true)));

    static ObjectNode evaluate(Assumption required, List<String> historical, List<String> supplemental) {
        var premises = Stream.concat(historical.stream(), supplemental.stream()).distinct().sorted()
            .map(s -> Assumption.customPredicate(s, List.of())).toList();
        var checked = PORTFOLIO.evaluate(required, premises);
        var output = JSON.createObjectNode().put("requirement", required.expression()).put("result", checked.result().name())
            .put("conflicting", checked.conflicting()).put("evaluatorProfileHash", checked.evaluatorProfileHash());
        output.set("runPremises", JSON.valueToTree(historical));
        output.set("reviewPremises", JSON.valueToTree(supplemental));
        output.set("evidence", JSON.valueToTree(checked.evidence()));
        return output;
    }

    private record PremiseWitness(boolean complement) implements AssumptionEvaluator {
        public String id() { return complement ? "typed-complement-premise" : "typed-identical-premise"; }
        public String revision() { return "1"; }

        public AssumptionEvaluationEvidence evaluate(Assumption requirement, List<Assumption> context) {
            var parser = new StructuredAssumptionParser();
            SolverIr.Predicate expected;
            try { expected = parser.parse(List.of(requirement.expression())).getFirst(); }
            catch (IllegalArgumentException unsupported) {
                return result(AssumptionTruthValue.UNKNOWN, "Requirement outside the typed predicate fragment", "");
            }
            for (Assumption item : context) {
                SolverIr.Predicate given;
                try { given = parser.parse(List.of(item.expression())).getFirst(); }
                catch (IllegalArgumentException unsupported) { continue; }
                if (expected.left().equals(given.left()) && Objects.equals(expected.right(), given.right())
                        && given.relation() == (complement ? opposite(expected.relation()) : expected.relation())) {
                    return result(complement ? AssumptionTruthValue.FALSE : AssumptionTruthValue.TRUE, item.expression(),
                        SolverIr.sha256(item.expression()));
                }
            }
            return result(AssumptionTruthValue.UNKNOWN, "No matching typed premise in this context", "");
        }

        private AssumptionEvaluationEvidence result(AssumptionTruthValue value, String detail, String source) {
            return AssumptionEvaluationEvidence.evaluated(id(), revision(), value, detail, source);
        }

        private SolverIr.Relation opposite(SolverIr.Relation relation) {
            return switch (relation) {
                case EQUALS -> SolverIr.Relation.NOT_EQUALS;
                case NOT_EQUALS -> SolverIr.Relation.EQUALS;
                case LESS_THAN -> SolverIr.Relation.GREATER_OR_EQUAL;
                case LESS_OR_EQUAL -> SolverIr.Relation.GREATER_THAN;
                case GREATER_THAN -> SolverIr.Relation.LESS_OR_EQUAL;
                case GREATER_OR_EQUAL -> SolverIr.Relation.LESS_THAN;
                case IS_INTEGER -> null;
            };
        }
    }
}
