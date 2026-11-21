package de.regelsuche.benchmarks;

import de.regelsuche.solver.ir.CoreExpressionIrAdapter;
import de.regelsuche.solver.ir.ExistentialIntroduction.Statement;
import de.regelsuche.solver.ir.SolverIr;
import de.regelsuche.solver.ir.SolverIr.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Disjoint source generators committed in the scoped-proof-transfer-v1 protocol. */
final class ScopedProofPartitions {
    private static final CoreExpressionIrAdapter TERMS = new CoreExpressionIrAdapter();
    enum Split { TRAIN, VALIDATION, FINAL }
    record Case(String id, Obligation source, String binder, Obligation receiver) {
        boolean witness() { return !binder.isEmpty(); }
    }

    static List<Case> generate(Split split) {
        var cases = new ArrayList<Case>();
        for (int family = 0; family < 2; family++) {
            String prefix = split.name().toLowerCase(Locale.ROOT) + family;
            String a = prefix + "a", b = prefix + "b", w = prefix + "w";
            var operands = operands(split, family, a, b);
            String A = "(" + operands.get(0) + ")", B = "(" + operands.get(1) + ")";
            var forms = List.of(List.of(w + "+" + A, B), List.of(w + "-" + A, B), List.of(A + "-" + w, B),
                List.of(w + "+" + w, w + "+" + w + "+1"));
            for (int i = 0; i < forms.size(); i++) {
                var sides = (cases.size() & 1) == 0 ? forms.get(i) : forms.get(i).reversed();
                String id = prefix + "-w" + i;
                var goal = new Goal(Relation.EQUALS, TERMS.parse(sides.get(0)), TERMS.parse(sides.get(1)));
                var declarations = List.of(real(a), real(b), real(w));
                var source = Obligation.create(id, declarations, List.of(Theory.REAL_ARITHMETIC), List.of(), goal,
                    RequestedEvidence.SYMBOLIC_CERTIFICATE, provenance(id));
                cases.add(new Case(id, source, w, new Statement(source, w).toScopedObligation()));
            }
        }
        for (int i = 0; i < 3; i++) cases.add(induction(split, i));
        return List.copyOf(cases);
    }

    private static List<String> operands(Split split, int family, String a, String b) {
        return switch (split) {
            case TRAIN -> family == 0 ? List.of(a, b) : List.of(a + "+2", b + "-3");
            case VALIDATION -> family == 0 ? List.of(a + "*" + b, b + "+5") : List.of(a + "-" + b, "(" + a + "+" + b + ")*7");
            case FINAL -> family == 0 ? List.of("(" + a + "+" + b + ")*(" + a + "-" + b + ")", a + "*" + a + "+" + b + "*" + b + "+11")
                : List.of(a + "*" + a + "-" + b, "(" + a + "+" + b + ")*13-" + b);
        };
    }

    private static Case induction(Split split, int family) {
        String id = split.name().toLowerCase(Locale.ROOT) + "ind" + family;
        String p = id + (family == 1 ? "z" : "a"), q = id + "m", r = id + "n";
        var declarations = new ArrayList<SymbolDeclaration>();
        var premises = new ArrayList<Predicate>();
        declarations.add(new SymbolDeclaration(p, Sort.NAT_PREDICATE));
        premises.add(transition("own-step", p, p));
        if (family < 2) premises.add(Predicate.holds("base", at(p, app("nat", new Literal("0")))));
        if (split != Split.TRAIN || family == 1) declarations.add(new SymbolDeclaration(q, Sort.NAT_PREDICATE));
        if (split != Split.TRAIN) premises.add(transition("unmatched", q, q));
        if (split == Split.FINAL) {
            declarations.add(new SymbolDeclaration(r, Sort.NAT_PREDICATE));
            premises.add(transition("crossed", q, r));
        }
        // Only the observer receives the designated recipient statement.
        var source = Obligation.create(id, declarations, List.of(Theory.SCOPED_ANALYSIS), premises,
            new Goal(Relation.EQUALS, new Literal("0"), new Literal("0")), RequestedEvidence.FORMAL_PROOF, provenance(id));
        var receiver = Obligation.create(id, declarations, source.theories(), premises,
            Goal.holds(all(at(p, new Bound(0)))), source.requestedEvidence(), source.provenance());
        return new Case(id, source, "", receiver);
    }

    private static Predicate transition(String id, String from, String to) {
        var n = new Bound(0);
        return Predicate.holds(id, all(app("implies", at(from,n), at(to,app("succ",n)))));
    }

    static Call app(String name, Expression... args) { return new Call(name, List.of(args)); }
    private static Expression at(String p, Expression n) { return app("at", new Symbol(p), n); }
    private static Binder all(Expression p) { return new Binder(BinderKind.FORALL, Sort.NATURAL, p); }
    private static SymbolDeclaration real(String name) { return new SymbolDeclaration(name, Sort.REAL); }
    private static SourceProvenance provenance(String id) {
        return new SourceProvenance("scoped-proof-transfer-v1", id, SolverIr.sha256("scoped-proof-transfer-v1/" + id));
    }
    private ScopedProofPartitions() { }
}
