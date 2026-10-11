package de.regelsuche.proof;

import de.regelsuche.assumption.Assumption;
import de.regelsuche.solver.ir.CoreExpressionIrAdapter;
import de.regelsuche.solver.ir.SolverIr;
import de.regelsuche.solver.ir.SolverIr.*;
import de.regelsuche.solver.ir.SolverObligationFactory;
import de.regelsuche.solver.ir.StructuredAssumptionParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;

/** Legacy app inputs are translated once into the authoritative typed obligation. */
final class ProofObligationAdapter {
    private ProofObligationAdapter() { }

    static Obligation equality(String left, String right, List<Assumption> supplied) {
        List<Assumption> assumptions = List.copyOf(Objects.requireNonNull(supplied));
        List<String> predicates = new ArrayList<>();
        List<String> realDeclarations = new ArrayList<>();
        StructuredAssumptionParser parser = new StructuredAssumptionParser();
        for (Assumption assumption : assumptions) {
            switch (assumption.kind()) {
                case REAL -> realDeclarations.add(domainSymbol(assumption, "R"));
                case INTEGER -> predicates.add(domainSymbol(assumption, "Z") + " is integer");
                case NATURAL -> {
                    String name = domainSymbol(assumption, "N");
                    predicates.add(name + " is integer"); predicates.add(name + " >= 0");
                }
                case NON_ZERO, POSITIVE, NON_NEGATIVE -> {
                    var parsed = parser.parse(List.of(assumption.expression()));
                    Relation expected = switch (assumption.kind()) {
                        case NON_ZERO -> Relation.NOT_EQUALS;
                        case POSITIVE -> Relation.GREATER_THAN;
                        case NON_NEGATIVE -> Relation.GREATER_OR_EQUAL;
                        default -> throw new IllegalArgumentException();
                    };
                    if (parsed.size() != 1 || parsed.get(0).relation() != expected
                            || !parsed.get(0).right().equals(new Literal("0")))
                        throw new IllegalArgumentException("assumption kind and predicate disagree");
                    predicates.add(assumption.expression());
                }
                case CUSTOM_PREDICATE, CUSTOM -> {
                    // Only explicitly parseable comparisons, never a named True fallback.
                    parser.parse(List.of(assumption.expression()));
                    predicates.add(assumption.expression());
                }
                default -> throw new IllegalArgumentException("unsupported assumption: " + assumption.kind());
            }
        }
        Obligation original = new SolverObligationFactory().equality("app-proof-request", left, right,
            predicates, RequestedEvidence.FORMAL_PROOF,
            new SourceProvenance("application-proof-request", "typed-bridge/v1",
                SolverIr.sha256(left + "\n" + right + "\n" + assumptions)));
        TreeMap<String, SymbolDeclaration> declarations = new TreeMap<>();
        original.declarations().forEach(d -> declarations.put(d.name(), d));
        realDeclarations.forEach(name -> declarations.putIfAbsent(name, new SymbolDeclaration(name, Sort.REAL)));
        return Obligation.create(original.obligationId(), List.copyOf(declarations.values()),
            original.theories(), original.assumptions(), original.goal(), original.requestedEvidence(),
            original.provenance());
    }

    private static String domainSymbol(Assumption a, String domain) {
        if (a.symbols().size() != 1) throw new IllegalArgumentException("one domain symbol required");
        String name = a.symbols().get(0);
        if (!(new CoreExpressionIrAdapter().parse(name) instanceof Symbol)
                || !a.expression().equals(name + " ∈ " + domain))
            throw new IllegalArgumentException("unsupported domain predicate");
        return name;
    }
}
