package de.regelsuche.discovery.domain;

import de.regelsuche.discovery.domain.DiscoveryDomain.CounterexampleGenerator;
import de.regelsuche.discovery.domain.DiscoveryDomain.CounterexampleResult;
import de.regelsuche.solver.ir.ScopedProofDag;
import de.regelsuche.solver.ir.SolverIr.*;
import de.regelsuche.solver.portfolio.LeanSourceRenderer;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Countermodels over constant interpretations for propositions and Nat predicates. Unknown values stay open. */
final class ScopedTruthAssignments implements CounterexampleGenerator<ScopedProofDag> {
    private enum Truth {
        TRUE, FALSE, UNKNOWN;
        Truth negate() { return switch(this) { case TRUE -> FALSE; case FALSE -> TRUE; case UNKNOWN -> UNKNOWN; }; }
        Truth and(Truth b) { return this==FALSE || b==FALSE ? FALSE : this==TRUE && b==TRUE ? TRUE : UNKNOWN; }
        Truth or(Truth b) { return negate().and(b.negate()).negate(); }
    }

    @Override public String id() { return "scoped-constant-models/v1"; }

    @Override public CounterexampleResult search(ScopedProofDag candidate, int attemptBudget) {
        var request = candidate.obligation();
        if (attemptBudget < 1) return CounterexampleResult.inconclusive(0,"No constant interpretation budget",Map.of());
        if (request.goal().relation()!=Relation.HOLDS || !new LeanSourceRenderer().render(request).supported())
            return CounterexampleResult.unsupported("Constant interpretations require a supported scoped proposition");
        var variables = request.declarations().stream().filter(d -> d.sort()==Sort.PROPOSITION || d.sort()==Sort.NAT_PREDICATE).toList();
        int limit = Math.min(attemptBudget, 1 << Math.min(8, variables.size()));
        int classified = 0;
        for (int ordinal=0;ordinal<limit;ordinal++) {
            var assignment = interpretation(variables,ordinal);
            Truth counterexample = check(request,assignment);
            if (counterexample==Truth.TRUE) return CounterexampleResult.found(ordinal+1, assignment.toString(),Map.of("family",id()));
            if (counterexample==Truth.FALSE) classified++;
        }
        var metrics = Map.of("family",id(),"classified",Integer.toString(classified),"partial",Integer.toString(limit-classified));
        return classified==0 ? CounterexampleResult.inconclusive(limit,"No interpretation could decide the counterexample condition",metrics)
            : CounterexampleResult.noneFound(limit,metrics);
    }

    private static Map<String,Truth> interpretation(List<SymbolDeclaration> variables,int ordinal) {
        var assignment = new TreeMap<String,Truth>();
        for (int i=0;i<variables.size();i++) assignment.put(variables.get(i).name(),
            i<8 && ((ordinal>>i)&1)==1 ? Truth.TRUE : Truth.FALSE);
        return assignment;
    }
    private static Truth check(Obligation q, Map<String,Truth> model) {
        Truth result = truth(q.goal().left(),model).negate();
        for (var h:q.assumptions()) result = result.and(h.relation()==Relation.HOLDS ? truth(h.left(),model) : Truth.UNKNOWN);
        return result;
    }
    private static Truth truth(Expression e, Map<String,Truth> model) {
        if (e instanceof Symbol s) return model.getOrDefault(s.name(),Truth.UNKNOWN);
        // Unknown bound terms range over their full inhabited Lean sorts. A definite result is uniform.
        if (e instanceof Binder b && (b.kind()==BinderKind.FORALL || b.kind()==BinderKind.EXISTS)) return truth(b.body(),model);
        if (!(e instanceof Call c)) return Truth.UNKNOWN;
        var args = c.arguments();
        return switch(c.function()) {
            case "not" -> truth(args.getFirst(),model).negate();
            case "and" -> truth(args.getFirst(),model).and(truth(args.getLast(),model));
            case "or" -> truth(args.getFirst(),model).or(truth(args.getLast(),model));
            case "implies" -> truth(args.getFirst(),model).negate().or(truth(args.getLast(),model));
            case "at" -> args.getFirst() instanceof Symbol p ? model.getOrDefault(p.name(),Truth.UNKNOWN) : Truth.UNKNOWN;
            case "eq" -> equality(args.getFirst(),args.getLast(),model);
            case "ne" -> equality(args.getFirst(),args.getLast(),model).negate();
            default -> Truth.UNKNOWN;
        };
    }
    private static Truth equality(Expression left,Expression right,Map<String,Truth> model) {
        if (left.equals(right)) return Truth.TRUE;
        var a=truth(left,model); var b=truth(right,model);
        if (a==Truth.UNKNOWN || b==Truth.UNKNOWN) return Truth.UNKNOWN;
        return a==b ? Truth.TRUE : Truth.FALSE;
    }
}
