package de.regelsuche.solver.ir;

import de.regelsuche.solver.ir.SolverIr.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Forms bounded three-node induction plans from declared natural predicates and two exact premise leaves. */
public final class NaturalInduction {
    private NaturalInduction() { }

    public record Plan(Obligation conclusion, String predicate, Predicate base, Predicate step) { }

    /**
     * Enumerate declared predicates in the supplied context; the requested endpoint is never read.
     * A declaration contributes one candidate only if its zero case and its successor step are present.
     * A plan carries no solver status or authority to confirm an external obligation.
     */
    public static List<Plan> discover(Obligation context) {
        if (!bounded(context)) return List.of();
        var result = new ArrayList<Plan>();
        for (var declaration : context.declarations()) propose(context, declaration).ifPresent(result::add);
        return List.copyOf(result);
    }

    /** Try one declared predicate without reading an endpoint. */
    public static Optional<Plan> propose(Obligation context, SymbolDeclaration declaration) {
        if (!bounded(context) || declaration.sort() != Sort.NAT_PREDICATE
                || !context.declarations().contains(declaration)) return Optional.empty();
        String p = declaration.name();
        var index = new Bound(0);
        var body = at(p, index);
        Predicate base = hypothesis(context, at(p, call("nat", new Literal("0"))));
        Predicate step = hypothesis(context, all(call("implies", body, at(p, call("succ", index)))));
        if (base == null || step == null) return Optional.empty();
        var conclusion = Obligation.create(context.obligationId(), context.declarations(), context.theories(),
            context.assumptions(), Goal.holds(all(body)), context.requestedEvidence(), context.provenance());
        return Optional.of(new Plan(conclusion, p, base, step));
    }

    private static boolean bounded(Obligation context) {
        return SolverIr.SCOPED_OBLIGATION_SCHEMA.equals(context.schema())
            && context.declarations().size() <= 128 && context.assumptions().size() <= 128;
    }

    /** Reconstruct the inference in the recipient context and match its exact conclusion and leaf identities. */
    public static boolean matches(Obligation expected, Plan candidate) {
        return candidate != null && expected.equals(candidate.conclusion()) && discover(expected).contains(candidate);
    }

    private static Predicate hypothesis(Obligation context, Expression proposition) {
        return context.assumptions().stream().filter(p -> p.relation() == Relation.HOLDS && p.left().equals(proposition))
            .findFirst().orElse(null);
    }
    private static Binder all(Expression body) { return new Binder(BinderKind.FORALL, Sort.NATURAL, body); }
    private static Call at(String p, Expression n) { return call("at", new Symbol(p), n); }
    private static Call call(String name, Expression... arguments) { return new Call(name, List.of(arguments)); }
}
