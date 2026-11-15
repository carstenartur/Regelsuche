package de.regelsuche.math.algorithms.linalg;

import de.regelsuche.ast.Equation;
import de.regelsuche.math.algorithms.equivalence.Monomial;
import de.regelsuche.math.algorithms.equivalence.Polynomial;
import de.regelsuche.math.algorithms.equivalence.Rational;
import de.regelsuche.math.algorithms.linalg.ExactLinearSystem.ExactMatrix;
import de.regelsuche.math.algorithms.linalg.ExactLinearSystem.ExactVector;
import de.regelsuche.math.algorithms.linalg.RecurrenceInvariantSupport.Work;
import de.regelsuche.math.algorithms.linalg.RecurrenceInvariantVerifier.VerifiedLaw;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.representation.RepresentationBridge;
import de.regelsuche.representation.RepresentationBridge.Budget;
import de.regelsuche.representation.RepresentationBridge.WorkLedger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Synthesizes degree-one/two laws of an explicitly supplied affine rational recurrence.
 * Coefficient equations use the existing matrix bridge and exact RREF solver.
 * No historical formula, sample sequence or desired answer enters candidate formation.
 */
public final class RecurrenceInvariantSynthesis {
    public enum Evolution {
        FIXED(1), ALTERNATING(-1);
        private final int multiplier;
        Evolution(int multiplier) { this.multiplier = multiplier; }
        public int multiplier() { return multiplier; }
    }
    /** UNSUPPORTED: a valid ansatz direction exceeds the proposal/verifier representation guards. */
    public enum Status { COMPLETE, BUDGET_INCONCLUSIVE, UNSUPPORTED }
    static final int MAX_PROPOSAL_LENGTH = 4096;

    /** x(n+1) = linear*x(n) + offset over Q; coordinates are named x0, x1, x2. */
    public record Transition(ExactMatrix linear, ExactVector offset) {
        public Transition {
            Objects.requireNonNull(linear, "linear"); Objects.requireNonNull(offset, "offset");
            if (linear.rowCount() < 1 || linear.rowCount() > 3 || linear.rowCount() != linear.columns()
                    || offset.dimension() != linear.rowCount())
                throw new IllegalArgumentException("one to three coordinates and a square affine transition are required");
            linear.rows().forEach(row -> row.forEach(RecurrenceInvariantSupport::requireBounded));
            offset.values().forEach(RecurrenceInvariantSupport::requireBounded);
        }
    }

    public record Request(Transition transition, List<Rational> initialState, int degree, Budget budget) {
        public Request {
            Objects.requireNonNull(transition, "transition"); Objects.requireNonNull(budget, "budget");
            initialState = checkedInitial(transition, initialState);
            if (degree < 1 || degree > 2) throw new IllegalArgumentException("degree must be one or two");
        }
    }

    /** Untrusted, persistable proposal: it must be checked again against the caller's transition. */
    public record Proposal(String polynomial, Evolution evolution) {
        public Proposal {
            Objects.requireNonNull(polynomial, "polynomial"); Objects.requireNonNull(evolution, "evolution");
            if (polynomial.isBlank() || polynomial.length() > MAX_PROPOSAL_LENGTH)
                throw new IllegalArgumentException("bounded nonempty polynomial text is required");
        }
    }

    /** COMPLETE describes only the declared degree/sign ansatz, never all possible identities. */
    public record Report(Status status, List<VerifiedLaw> laws, WorkLedger work) {
        public Report {
            Objects.requireNonNull(status, "status"); Objects.requireNonNull(work, "work");
            laws = List.copyOf(laws);
            if (status != Status.COMPLETE && !laws.isEmpty())
                throw new IllegalArgumentException("incomplete searches do not publish a complete law set");
        }
    }

    /** A first-law result, or a bounded miss after searching the entire declared ansatz. */
    record FirstLawReport(Status status, Optional<VerifiedLaw> law, boolean usedSupport, WorkLedger work) {}

    public Report solve(Request request) {
        Objects.requireNonNull(request, "request");
        var work = new Work(request.budget().maxWorkUnits());
        var laws = new ArrayList<VerifiedLaw>();
        try {
            int dimension = request.transition().linear().columns();
            var basis = RecurrenceInvariantSupport.basis(dimension, request.degree());
            work.add(basis.size());
            var images = RecurrenceInvariantSupport.images(request.transition(), work);
            var blocks = coefficientBlocks(request, basis, work);
            for (Evolution evolution : Evolution.values()) {
                for (var block : blocks) {
                    solveSign(request, block, block, images, evolution, laws, false, work);
                }
            }
            return new Report(Status.COMPLETE, laws, work.ledger());
        } catch (RecurrenceInvariantSupport.Exhausted exhausted) {
            return new Report(Status.BUDGET_INCONCLUSIVE, List.of(), work.ledger());
        } catch (Unsupported unsupported) {
            return new Report(Status.UNSUPPORTED, List.of(), work.ledger());
        }
    }

    FirstLawReport findFirst(Request request) {
        return findFirst(request, List.of());
    }

    /** Tries untrusted coefficient-free support first and pays for a miss before ordinary search. */
    FirstLawReport findFirst(Request request, List<Monomial> support) {
        return findFirst(request, support, false);
    }

    /** First-law control that partitions the ansatz by degree for homogeneous transitions. */
    FirstLawReport findFirstWithHomogeneousBlocks(Request request, List<Monomial> support) {
        return findFirst(request, support, true);
    }

    private FirstLawReport findFirst(Request request, List<Monomial> support, boolean separateDegrees) {
        var work = new Work(request.budget().maxWorkUnits());
        var laws = new ArrayList<VerifiedLaw>();
        try {
            var basis = RecurrenceInvariantSupport.basis(request.transition().linear().columns(), request.degree());
            work.add(basis.size());
            var images = RecurrenceInvariantSupport.images(request.transition(), work);
            boolean homogeneous = separateDegrees && isHomogeneous(request.transition(), work);
            if (!support.isEmpty()) {
                var columns = supportColumns(basis, support, work);
                if (!columns.isEmpty()) {
                    try {
                        solveFirstInBasis(request, basis, columns, images, homogeneous, laws, work);
                    } catch (Unsupported unsupported) {
                        // The restricted attempt can fail its representation guard independently of the full search.
                    }
                    if (!laws.isEmpty()) {
                        return new FirstLawReport(Status.COMPLETE, Optional.of(laws.getFirst()), true, work.ledger());
                    }
                }
            }
            solveFirstInBasis(request, basis, basis, images, homogeneous, laws, work);
            return new FirstLawReport(Status.COMPLETE, laws.stream().findFirst(), false, work.ledger());
        } catch (RecurrenceInvariantSupport.Exhausted exhausted) {
            return new FirstLawReport(Status.BUDGET_INCONCLUSIVE, Optional.empty(), false, work.ledger());
        } catch (Unsupported unsupported) {
            return new FirstLawReport(Status.UNSUPPORTED, Optional.empty(), false, work.ledger());
        }
    }

    private static boolean isHomogeneous(Transition transition, Work work) {
        var offset = transition.offset().values();
        work.add(offset.size());
        return offset.stream().allMatch(Rational::isZero);
    }

    private static List<List<Monomial>> coefficientBlocks(Request request, List<Monomial> basis, Work work) {
        if (!isHomogeneous(request.transition(), work)) return List.of(basis);
        // Linear substitutions preserve total degree. The constant block cannot yield a law.
        var blocks = new ArrayList<List<Monomial>>(request.degree());
        for (int degree = 1; degree <= request.degree(); degree++) {
            blocks.add(monomialsOfDegree(basis, degree, work));
        }
        return blocks;
    }

    private static List<Monomial> monomialsOfDegree(List<Monomial> basis, int degree, Work work) {
        work.add(basis.size());
        return basis.stream().filter(monomial -> monomial.totalDegree() == degree).toList();
    }

    private static void solveFirstInBasis(Request request, List<Monomial> basis, List<Monomial> columns,
            List<String> images, boolean homogeneous, List<VerifiedLaw> laws, Work work) {
        if (!homogeneous) {
            solveSigns(request, basis, columns, images, laws, true, work);
            return;
        }
        // Linear substitutions preserve total degree, so each nonconstant degree is an independent block.
        boolean restricted = columns != basis;
        if (restricted) work.add(columns.size());
        var support = restricted ? new HashSet<>(columns) : null;
        for (int degree = request.degree(); degree >= 1; degree--) {
            var rows = monomialsOfDegree(basis, degree, work);
            if (support != null) work.add(rows.size());
            var selected = support == null ? rows : rows.stream().filter(support::contains).toList();
            if (!selected.isEmpty()) {
                solveSigns(request, rows, selected, images, laws, true, work);
                if (!laws.isEmpty()) return;
            }
        }
    }

    private static List<Monomial> supportColumns(List<Monomial> basis, List<Monomial> support, Work work) {
        work.add(1);
        if (support.size() >= basis.size()) return List.of();
        work.add((long) support.size() + basis.size());
        var offered = new HashSet<>(support);
        var ordered = basis.stream().filter(offered::contains).toList();
        return ordered.size() == support.size() ? ordered : List.of();
    }

    private static void solveSigns(Request request, List<Monomial> rows, List<Monomial> columns, List<String> images,
            List<VerifiedLaw> laws, boolean firstOnly, Work work) {
        for (Evolution evolution : Evolution.values()) {
            solveSign(request, rows, columns, images, evolution, laws, firstOnly, work);
            if (firstOnly && !laws.isEmpty()) return;
        }
    }

    private static void solveSign(Request request, List<Monomial> rows, List<Monomial> columns, List<String> images,
            Evolution evolution, List<VerifiedLaw> laws, boolean firstOnly, Work work) {
        var equations = coefficientEquations(rows, columns, images, evolution, work);
        var matrix = new LinearSystemRepresentationBridge().analyze(equations, new Budget(work.remaining()));
        work.add(matrix.work().consumedWorkUnits());
        if (matrix.status() == RepresentationBridge.Status.BUDGET_INCONCLUSIVE)
            throw new RecurrenceInvariantSupport.Exhausted();
        var system = matrix.representation().orElseThrow(() -> new IllegalStateException("affine coefficient bridge failed"));
        var reduced = new ExactRrefSolver().solve(system, new Budget(work.remaining()));
        work.add(reduced.work().consumedWorkUnits());
        if (reduced.status() == ExactRrefSolver.Status.BUDGET_INCONCLUSIVE)
            throw new RecurrenceInvariantSupport.Exhausted();
        var reduction = reduced.reduction().orElseThrow(() -> new IllegalStateException("coefficient reduction failed"));
        for (var vector : reduction.nullspaceBasis()) {
            var polynomial = reconstruct(columns, system.variables(), vector, work);
            if (polynomial.totalDegree() == 0) continue;
            String text = polynomial.toCanonicalString();
            // Keep the representation guard; never silently drop a direction while claiming COMPLETE.
            if (text.length() > MAX_PROPOSAL_LENGTH) throw new Unsupported();
            admit(request, new Proposal(text, evolution), laws, work);
            if (firstOnly) return;
        }
    }

    private static List<Equation> coefficientEquations(List<Monomial> rows, List<Monomial> coefficientBasis,
            List<String> images,
            Evolution evolution, Work work) {
        var columns = new ArrayList<Polynomial>();
        var original = RecurrenceInvariantSupport.variables(images.size());
        for (var monomial : coefficientBasis) {
            String before = RecurrenceInvariantSupport.monomial(monomial, original, work);
            String after = RecurrenceInvariantSupport.monomial(monomial, images, work);
            columns.add(RecurrenceInvariantSupport.project("(" + after + ")-(" + evolution.multiplier() + ")*(" + before + ")", work));
        }
        var equations = new ArrayList<Equation>();
        var parser = new ExpressionParser();
        for (var monomial : rows) {
            var left = new StringBuilder("0");
            for (int column = 0; column < coefficientBasis.size(); column++) {
                work.add(1);
                Rational coefficient = columns.get(column).terms().getOrDefault(monomial, Rational.ZERO);
                // Keep zero*coefficient-variable: the bridge must retain unconstrained columns.
                left.append("+(").append(coefficient).append(")*k").append(column);
            }
            work.add(left.length());
            equations.add(new Equation(parser.parseExactTerm(left.toString()).expression(), parser.parseExactTerm("0").expression()));
        }
        return List.copyOf(equations);
    }

    private static Polynomial reconstruct(List<Monomial> basis, List<String> variables, ExactVector vector, Work work) {
        var coefficients = new ArrayList<Rational>();
        for (int i = 0; i < basis.size(); i++) {
            work.add(variables.size());
            coefficients.add(vector.get(variables.indexOf("k" + i)));
        }
        Rational first = coefficients.stream().filter(value -> !value.isZero()).findFirst().orElseThrow();
        var result = Polynomial.zero();
        for (int i = 0; i < basis.size(); i++) {
            work.add(1L + result.termCount());
            result = result.add(Polynomial.term(basis.get(i), coefficients.get(i).divide(first)));
        }
        return result;
    }

    private static void admit(Request request, Proposal proposal, List<VerifiedLaw> laws, Work work) {
        var checked = new RecurrenceInvariantVerifier().verify(request.transition(), proposal,
            request.initialState(), new Budget(work.remaining()));
        work.add(checked.work().consumedWorkUnits());
        if (checked.outcome() == RecurrenceInvariantVerifier.Outcome.BUDGET_INCONCLUSIVE)
            throw new RecurrenceInvariantSupport.Exhausted();
        if (checked.outcome() == RecurrenceInvariantVerifier.Outcome.UNSUPPORTED) throw new Unsupported();
        laws.add(checked.law().orElseThrow(() -> new IllegalStateException("synthesized law failed independent identity check")));
    }

    private static final class Unsupported extends RuntimeException { }

    static List<Rational> checkedInitial(Transition source, List<Rational> initial) {
        Objects.requireNonNull(initial, "initialState");
        if (initial.size() != source.linear().columns()) throw new IllegalArgumentException("initial-state dimension mismatch");
        var values = List.copyOf(initial);
        values.forEach(RecurrenceInvariantSupport::requireBounded);
        return values;
    }
}
