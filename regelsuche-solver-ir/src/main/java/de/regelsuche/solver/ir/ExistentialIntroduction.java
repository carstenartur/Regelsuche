package de.regelsuche.solver.ir;

import de.regelsuche.solver.ir.SolverIr.Binary;
import de.regelsuche.solver.ir.SolverIr.Expression;
import de.regelsuche.solver.ir.SolverIr.Goal;
import de.regelsuche.solver.ir.SolverIr.Literal;
import de.regelsuche.solver.ir.SolverIr.Obligation;
import de.regelsuche.solver.ir.SolverIr.Predicate;
import de.regelsuche.solver.ir.SolverIr.Relation;
import de.regelsuche.solver.ir.SolverIr.RequestedEvidence;
import de.regelsuche.solver.ir.SolverIr.ResultStatus;
import de.regelsuche.solver.ir.SolverIr.Sort;
import de.regelsuche.solver.ir.SolverIr.Symbol;
import de.regelsuche.solver.ir.SolverIr.SymbolDeclaration;
import de.regelsuche.solver.ir.SolverIr.Theory;
import java.util.List;
import java.util.Objects;

/** Two-node proof of a single existential equality over total real ring terms. */
public final class ExistentialIntroduction {
    private ExistentialIntroduction() {}

    /** The indicated real declaration is bound in the goal only. All others are parameters. */
    public record Statement(Obligation body, String binder) {
        public Statement {
            Objects.requireNonNull(body, "body");
            if (!body.declarations().contains(new SymbolDeclaration(binder, Sort.REAL))
                    || body.declarations().stream().anyMatch(declaration -> declaration.sort() != Sort.REAL)
                    || !body.theories().equals(List.of(Theory.REAL_ARITHMETIC))
                    || body.requestedEvidence() != RequestedEvidence.SYMBOLIC_CERTIFICATE
                    || body.goal().relation() != Relation.EQUALS) {
                throw new IllegalArgumentException("expected a declared real binder and a real equality");
            }
            RingTerm.check(body.goal().left());
            RingTerm.check(body.goal().right());
            for (Predicate premise : body.assumptions()) {
                if (premise.relation() != Relation.EQUALS || premise.referencedSymbols().contains(binder)) {
                    throw new IllegalArgumentException("premises must be equalities in the outer scope");
                }
                RingTerm.check(premise.left());
                RingTerm.check(premise.right());
            }
        }

        private List<SymbolDeclaration> parameters() {
            return body.declarations().stream().filter(declaration -> !declaration.name().equals(binder)).toList();
        }

        private boolean sameStatement(Statement other) {
            if (equals(other)) return true;
            if (!parameters().equals(other.parameters())) return false;
            // Equal outer contexts make the receiver's binder fresh in both scopes.
            var declarations = body.declarations();
            var renamed = other.substitutedGoal(new Symbol(binder));
            var candidate = Obligation.create(other.body.obligationId(), declarations, other.body.theories(),
                other.body.assumptions(), renamed, other.body.requestedEvidence(), other.body.provenance());
            return body.equals(candidate);
        }

        private Goal substitutedGoal(Expression replacement) {
            return new Goal(Relation.EQUALS, substitute(body.goal().left(), binder, replacement),
                substitute(body.goal().right(), binder, replacement));
        }

        /** Instantiate only the body, once, with a total term from the outer context. */
        public Obligation instantiate(Expression witness) {
            RingTerm.check(witness);
            var outer = parameters();
            if (!outer.stream().map(SymbolDeclaration::name).toList().containsAll(witness.referencedSymbols())) {
                throw new IllegalArgumentException("witness refers outside the outer context");
            }
            Goal goal = substitutedGoal(witness);
            RingTerm.check(goal.left());
            RingTerm.check(goal.right());
            return Obligation.create(body.obligationId(), outer, body.theories(), body.assumptions(), goal,
                body.requestedEvidence(), body.provenance());
        }
    }

    public sealed interface Leaf permits NormalForm, Hypothesis {
        Obligation instance();
    }

    public record NormalForm(Obligation instance) implements Leaf {
        public NormalForm { Objects.requireNonNull(instance, "instance"); }
    }

    public record Hypothesis(Obligation instance, String premiseId) implements Leaf {
        public Hypothesis {
            Objects.requireNonNull(instance, "instance");
            Objects.requireNonNull(premiseId, "premiseId");
        }
    }

    /** The introduction root points to its one independently replayable premise node. */
    public record Proof(Statement conclusion, Expression witness, Leaf premise) {
        public Proof {
            Objects.requireNonNull(conclusion, "conclusion");
            Objects.requireNonNull(witness, "witness");
            Objects.requireNonNull(premise, "premise");
        }
    }

    /** Check the proof against the receiver's statement and reconstruct the premise from that statement. */
    public static boolean replay(Statement expected, Proof proof) {
        if (expected == null || proof == null || !expected.sameStatement(proof.conclusion())) return false;
        Obligation instance;
        try {
            instance = expected.instantiate(proof.witness());
        } catch (IllegalArgumentException exception) {
            return false;
        }
        if (!instance.equals(proof.premise().instance())) return false;
        return switch (proof.premise()) {
            case Hypothesis h -> instance.assumptions().stream().anyMatch(p -> matches(h, p, instance.goal()));
            case NormalForm ignored -> new PolynomialNormalFormSolverBackend().execute(instance)
                .result().status() == ResultStatus.CONFIRMED;
        };
    }

    private static boolean matches(Hypothesis leaf, Predicate premise, Goal goal) {
        return premise.id().equals(leaf.premiseId()) && premise.relation() == goal.relation()
            && premise.left().equals(goal.left()) && premise.right().equals(goal.right());
    }

    private static Expression substitute(Expression term, String binder, Expression witness) {
        return switch (term) {
            case Symbol symbol -> symbol.name().equals(binder) ? witness : symbol;
            case Binary binary -> new Binary(binary.operator(), substitute(binary.left(), binder, witness),
                substitute(binary.right(), binder, witness));
            default -> term; // Already checked as a literal, symbol or ring operation.
        };
    }

    /** Preflight before substitution and before expansion by the polynomial leaf. */
    private static final class RingTerm {
        private static final int MAX_NODES = 63;
        private static final int MAX_MONOMIALS = 256;
        private int nodes;

        private static void check(Expression term) {
            new RingTerm().monomialBound(term);
        }

        private int monomialBound(Expression term) {
            if (++nodes > MAX_NODES) throw new IllegalArgumentException("ring term exceeds node bound");
            if (term instanceof Symbol) return 1;
            if (term instanceof Literal literal && literal.value().length() <= 64) return 1;
            if (!(term instanceof Binary binary)) throw new IllegalArgumentException("unsupported ring term");
            int left = monomialBound(binary.left());
            int right = monomialBound(binary.right());
            int bound = switch (binary.operator()) {
                case ADD, SUBTRACT -> left + right;
                case MULTIPLY -> left * right;
                default -> throw new IllegalArgumentException("unsupported ring operator");
            };
            if (bound > MAX_MONOMIALS) throw new IllegalArgumentException("ring term exceeds expansion bound");
            return bound;
        }
    }
}
