package de.regelsuche.solver.ir;

import de.regelsuche.solver.ir.SolverIr.Call;
import de.regelsuche.solver.ir.SolverIr.Expression;
import de.regelsuche.solver.ir.SolverIr.Goal;
import de.regelsuche.solver.ir.SolverIr.Obligation;
import de.regelsuche.solver.ir.SolverIr.Predicate;
import de.regelsuche.solver.ir.SolverIr.Relation;
import de.regelsuche.solver.ir.SolverIr.RequestedEvidence;
import de.regelsuche.solver.ir.SolverIr.Sort;
import de.regelsuche.solver.ir.SolverIr.SourceProvenance;
import de.regelsuche.solver.ir.SolverIr.SymbolDeclaration;
import de.regelsuche.solver.ir.SolverIr.Theory;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Creates canonical bounded-algebra obligations from existing expression strings. */
public final class SolverObligationFactory {
    private final CoreExpressionIrAdapter expressions = new CoreExpressionIrAdapter();
    private final StructuredAssumptionParser assumptionParser =
        new StructuredAssumptionParser();

    public Obligation equality(
        String obligationId,
        String leftExpression,
        String rightExpression,
        List<String> assumptions,
        RequestedEvidence requestedEvidence,
        SourceProvenance provenance
    ) {
        return relation(
            obligationId,
            Relation.EQUALS,
            leftExpression,
            rightExpression,
            assumptions,
            requestedEvidence,
            provenance);
    }

    public Obligation relation(
        String obligationId,
        Relation relation,
        String leftExpression,
        String rightExpression,
        List<String> assumptions,
        RequestedEvidence requestedEvidence,
        SourceProvenance provenance
    ) {
        Expression left = expressions.parse(leftExpression);
        Expression right = expressions.parse(rightExpression);
        List<Predicate> predicates = assumptionParser.parse(assumptions);
        Goal goal = new Goal(relation, left, right);

        LinkedHashSet<String> symbols = new LinkedHashSet<>(goal.referencedSymbols());
        predicates.forEach(predicate -> symbols.addAll(predicate.referencedSymbols()));
        List<SymbolDeclaration> declarations = symbols.stream().sorted()
            .map(name -> new SymbolDeclaration(name, Sort.REAL))
            .toList();

        LinkedHashSet<Theory> theories = new LinkedHashSet<>();
        theories.add(Theory.REAL_ARITHMETIC);
        if (containsCall(left) || containsCall(right)
                || predicates.stream().anyMatch(predicate ->
                    containsCall(predicate.left())
                        || predicate.right() != null && containsCall(predicate.right()))) {
            theories.add(Theory.TRANSCENDENTAL_FUNCTIONS);
        }
        if (predicates.stream().anyMatch(predicate ->
                predicate.relation() == Relation.IS_INTEGER)) {
            theories.add(Theory.INTEGER_ARITHMETIC);
        }

        return Obligation.create(
            obligationId,
            declarations,
            new ArrayList<>(theories),
            predicates,
            goal,
            requestedEvidence,
            provenance);
    }

    private static boolean containsCall(Expression expression) {
        if (expression instanceof Call) {
            return true;
        }
        if (expression instanceof SolverIr.Binary binary) {
            return containsCall(binary.left()) || containsCall(binary.right());
        }
        return false;
    }
}
