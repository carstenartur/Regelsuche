package de.regelsuche.math.algorithms.linalg;

import de.regelsuche.ast.Equation;
import de.regelsuche.math.algorithms.equivalence.Polynomial;
import de.regelsuche.representation.RepresentationBridge;
import de.regelsuche.representation.RepresentationPreparation;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import static de.regelsuche.representation.RepresentationBridge.*;

/** Product-independent bounded preparation of source- and catalog-backed representations. */
public final class MatrixPreparation {
    public static final String SCHEMA = "regelsuche.matrix-preparation/v1";
    public static final int DEFAULT_WORK = 200_000;
    public static final int MAX_WORK = 1_000_000;

    public enum Profile {
        RECOGNITION_ONLY_V1,
        SAFE_PREPARED_REPRESENTATION_V1,
        EXPERIMENTAL_OPERATOR_V1
    }

    public record NamedMatrix(String name, List<List<String>> entries, List<List<String>> inverseWitness) {
        public NamedMatrix {
            name = MatrixPreparation.name(name);
            entries = rows(entries);
            inverseWitness = inverseWitness == null || inverseWitness.isEmpty() ? List.of() : rows(inverseWitness);
        }

        private static List<List<String>> rows(List<List<String>> rows) {
            if (rows == null || rows.isEmpty() || rows.size() > ExactMatrixAlgebra.MAX_DIMENSION) {
                throw new IllegalArgumentException("catalog matrix needs 1..16 rows");
            }
            int columns = rows.getFirst().size();
            if (columns < 1 || columns > ExactMatrixAlgebra.MAX_DIMENSION
                    || rows.stream().anyMatch(row -> row.size() != columns)) {
                throw new IllegalArgumentException("catalog matrix needs 1..16 equally sized columns");
            }
            return rows.stream().map(row -> row.stream().map(MatrixPreparation::text).toList()).toList();
        }
    }

    public record Request(String equations, List<String> unknowns, Profile profile, int maxWorkUnits,
            List<NamedMatrix> catalog, List<String> operatorExpressions,
            String eigenvalueParameter, boolean nonZeroVector,
            String matrixExpression, List<String> rightHandSide) {
        public Request {
            equations = equations == null ? "" : equations.trim();
            matrixExpression = matrixExpression == null ? "" : matrixExpression.trim();
            if (equations.isBlank() == matrixExpression.isBlank()) {
                throw new IllegalArgumentException("supply either scalar equations or a matrix equation");
            }
            if (equations.length() > 65_536) {
                throw new IllegalArgumentException("equation-system text is too large");
            }
            unknowns = unknowns.stream().map(MatrixPreparation::name).toList();
            if (unknowns.isEmpty() || unknowns.size() > ExactMatrixAlgebra.MAX_DIMENSION
                    || unknowns.stream().distinct().count() != unknowns.size()) {
                throw new IllegalArgumentException("declare 1..16 distinct ordered coordinates");
            }
            Objects.requireNonNull(profile, "profile");
            if (maxWorkUnits < 0 || maxWorkUnits > MAX_WORK) {
                throw new IllegalArgumentException("work budget must be in 0..1000000");
            }
            catalog = List.copyOf(catalog);
            operatorExpressions = operatorExpressions.stream().map(MatrixPreparation::text).toList();
            if (catalog.size() > 8 || operatorExpressions.size() > 8
                    || catalog.stream().map(NamedMatrix::name).distinct().count() != catalog.size()) {
                throw new IllegalArgumentException("retain at most eight distinct catalog matrices and eight operator expressions");
            }
            eigenvalueParameter = eigenvalueParameter == null || eigenvalueParameter.isBlank()
                ? "" : name(eigenvalueParameter);
            if (unknowns.contains(eigenvalueParameter)) {
                throw new IllegalArgumentException("eigenvalue parameter cannot be a coordinate");
            }
            rightHandSide = rightHandSide.stream().map(MatrixPreparation::text).toList();
            if (rightHandSide.size() > ExactMatrixAlgebra.MAX_DIMENSION
                    || (matrixExpression.isBlank() && !rightHandSide.isEmpty())) {
                throw new IllegalArgumentException("RHS entries belong to the explicit matrix equation only");
            }
            if (!matrixExpression.isBlank()) {
                matrixExpression = text(matrixExpression);
                if (rightHandSide.isEmpty()) {
                    throw new IllegalArgumentException("matrix equation requires an explicit right-hand side");
                }
            }
        }

        public static Request scalar(String source, List<String> unknowns, Profile profile, int budget) {
            return new Request(source, unknowns, profile, budget, List.of(), List.of(), "", false, "", List.of());
        }
    }

    static String name(String value) {
        if (value == null || !value.matches("[A-Za-z_][A-Za-z0-9_]{0,63}")) {
            throw new IllegalArgumentException("use a declared identifier of at most 64 characters");
        }
        return value;
    }

    static String text(String value) {
        if (value == null || value.isBlank() || value.length() > 4096) {
            throw new IllegalArgumentException("expression must contain 1..4096 characters");
        }
        int depth = 0;
        int operators = 0;
        for (char character : value.toCharArray()) {
            if (character == '(' && ++depth > ExactMatrixAlgebra.MAX_DEPTH) {
                throw new IllegalArgumentException("expression nesting exceeds the matrix profile");
            }
            if (character == ')') {
                depth--;
            }
            if ("+-*/^".indexOf(character) >= 0 && ++operators > 256) {
                throw new IllegalArgumentException("expression operator count exceeds the matrix profile");
            }
        }
        return value.trim();
    }

    public record Attempt(String origin, ExactMatrixExpression expression, List<String> provenance,
            RepresentationPreparation.Outcome<MatrixRepresentationBridge.View, MatrixRepresentationBridge.Certificate,
                List<Polynomial>, MatrixRepresentationBridge.Certificate> outcome) {
        public Attempt {
            provenance = List.copyOf(provenance);
            Objects.requireNonNull(outcome, "outcome");
        }
    }

    public record Analysis(Request request, Status status, String detailCode, List<Equation> equations,
            Optional<Result<SymbolicLinearSystem, SymbolicLinearSystemRepresentationBridge.Certificate>> formation,
            Optional<Result<ExactLinearSystemBlockDecomposition, ExactLinearSystemBlockDecomposer.Certificate>> blocks,
            List<Attempt> attempts,
            Optional<Result<EigenproblemRepresentation, EigenproblemRepresentationBridge.Certificate>> eigenproblem,
            Optional<BoundedCharacteristicPolynomialSolver.Result> characteristicPolynomial,
            Optional<ExactLinearSystem> exactSystem, Optional<ExactRrefSolver.Result> rowReduction, WorkLedger work) {
        public Analysis {
            equations = List.copyOf(equations);
            attempts = List.copyOf(attempts);
        }

        public long acceptedCount() {
            return attempts.stream().filter(attempt -> attempt.outcome().accepted()).count();
        }
    }

    public Analysis analyze(Request request) {
        State state = new State(request);
        try {
            state.execute();
        } catch (ExactMatrixAlgebra.Exhausted exception) {
            state.status = Status.BUDGET_INCONCLUSIVE;
            state.detail = "PREPARATION_WORK_EXHAUSTED";
        } catch (ExactMatrixAlgebra.Unsupported exception) {
            state.status = Status.DOMAIN_UNSUPPORTED;
            state.detail = exception.getMessage();
        }
        return state.result();
    }

    public boolean verify(Analysis retained) {
        return retained != null && analyze(retained.request()).equals(retained);
    }

    private static final class State {
        private final Request request;
        private final ExactMatrixAlgebra.Work work;
        private final MatrixPreparationSources sources;
        private List<Equation> equations = List.of();
        private Result<SymbolicLinearSystem, SymbolicLinearSystemRepresentationBridge.Certificate> formation;
        private Result<ExactLinearSystemBlockDecomposition, ExactLinearSystemBlockDecomposer.Certificate> blocks;
        private final List<Attempt> attempts = new ArrayList<>();
        private Result<EigenproblemRepresentation, EigenproblemRepresentationBridge.Certificate> eigenproblem;
        private BoundedCharacteristicPolynomialSolver.Result characteristic;
        private ExactLinearSystem exact;
        private ExactRrefSolver.Result reduction;
        private Status status = Status.REPRESENTED;
        private String detail = "BOUNDED_REPRESENTATION_PREPARATION_COMPLETE";

        private State(Request request) {
            this.request = Objects.requireNonNull(request, "request");
            work = new ExactMatrixAlgebra.Work(request.maxWorkUnits());
            sources = new MatrixPreparationSources(request, work);
        }

        private void execute() {
            if (!request.operatorExpressions().isEmpty() && request.profile() != Profile.EXPERIMENTAL_OPERATOR_V1) {
                throw new ExactMatrixAlgebra.Unsupported("OPERATOR_RECIPES_REQUIRE_EXPERIMENTAL_PROFILE");
            }
            equations = sources.equations();
            SymbolicLinearSystemRepresentationBridge bridge = new SymbolicLinearSystemRepresentationBridge();
            var source = new SymbolicLinearSystemRepresentationBridge.Source(equations, request.unknowns());
            formation = bridge.analyze(source, budget());
            consume(formation.work());
            if (!formation.represented()) {
                status = formation.status();
                detail = formation.detailCode();
                return;
            }
            if (!bridge.verify(source, formation)) {
                throw new ExactMatrixAlgebra.Unsupported("SOURCE_FORMATION_REPLAY_REJECTED");
            }
            SymbolicLinearSystem system = formation.representation().orElseThrow();
            recognizeEigenproblem(system);
            if (request.profile() == Profile.RECOGNITION_ONLY_V1) {
                return;
            }
            exactConsequences(system);
            sources.propose(system, equations, Optional.ofNullable(blocks)
                .flatMap(Result::representation), this::accept);
            solve();
        }

        private void accept(MatrixPreparationSources.Proposal proposal) {
            var system = formation.representation().orElseThrow();
            MatrixRepresentationBridge.Obligation obligation = new MatrixRepresentationBridge.Obligation(
                system.coefficients(), system.rowOrigins().stream().map(SymbolicLinearSystem.RowOrigin::sourceEquation).toList(),
                system.unknowns(), formation.certificate().orElseThrow().contentHash(), proposal.expression(), proposal.provenance());
            var outcome = RepresentationPreparation.analyze(obligation, new MatrixRepresentationBridge(),
                Set.of(MatrixRepresentationBridge.RELATION), new MatrixRepresentationBridge.VectorReplay(), budget());
            consume(outcome.work());
            attempts.add(new Attempt(proposal.origin(), proposal.expression(), proposal.provenance(), outcome));
            boolean exhausted = outcome.formation().status() == Status.BUDGET_INCONCLUSIVE
                || outcome.replay().map(r -> r.status() == Status.BUDGET_INCONCLUSIVE).orElse(false);
            if (exhausted) {
                throw new ExactMatrixAlgebra.Exhausted();
            }
        }

        private void recognizeEigenproblem(SymbolicLinearSystem system) {
            if (request.eigenvalueParameter().isBlank()) {
                return;
            }
            EigenproblemRepresentationBridge bridge = new EigenproblemRepresentationBridge();
            var source = new EigenproblemRepresentationBridge.Source(system, request.eigenvalueParameter(),
                request.nonZeroVector(), EigenproblemRepresentation.ModelDomain.GENERIC_LINEAR_ALGEBRA, Set.of());
            eigenproblem = bridge.analyze(source, budget());
            consume(eigenproblem.work());
            if (eigenproblem.status() == Status.BUDGET_INCONCLUSIVE) {
                throw new ExactMatrixAlgebra.Exhausted();
            }
            if (eigenproblem.represented() && !bridge.verify(source, eigenproblem)) {
                throw new ExactMatrixAlgebra.Unsupported("EIGENPROBLEM_REPLAY_REJECTED");
            }
        }

        private void solve() {
            if (exact != null) {
                ExactRrefSolver solver = new ExactRrefSolver();
                reduction = solver.solve(exact, budget());
                consume(reduction.work());
                if (reduction.status() == ExactRrefSolver.Status.BUDGET_INCONCLUSIVE) {
                    throw new ExactMatrixAlgebra.Exhausted();
                }
                if (reduction.status() == ExactRrefSolver.Status.SOLVED && !solver.verify(exact, reduction)) {
                    throw new ExactMatrixAlgebra.Unsupported("EXACT_RREF_REPLAY_REJECTED");
                }
            }
            if (eigenproblem != null && eigenproblem.represented()) {
                BoundedCharacteristicPolynomialSolver solver = new BoundedCharacteristicPolynomialSolver();
                characteristic = solver.solve(eigenproblem.representation().orElseThrow(),
                    new BoundedCharacteristicPolynomialSolver.Budget(work.remaining()));
                consume(characteristic.work());
                if (characteristic.status() == BoundedCharacteristicPolynomialSolver.Status.BUDGET_INCONCLUSIVE) {
                    throw new ExactMatrixAlgebra.Exhausted();
                }
                if (characteristic.characteristicPolynomial().isPresent()
                        && !solver.verify(eigenproblem.representation().orElseThrow(), characteristic)) {
                    throw new ExactMatrixAlgebra.Unsupported("CHARACTERISTIC_POLYNOMIAL_REPLAY_REJECTED");
                }
            }
        }

        private void exactConsequences(SymbolicLinearSystem system) {
            if (!system.scalarParameters().isEmpty()) {
                return;
            }
            LinearSystemRepresentationBridge bridge = new LinearSystemRepresentationBridge();
            var rationalSource = sources.rationalEquations(system);
            var rational = bridge.analyze(rationalSource, budget());
            consume(rational.work());
            if (!rational.represented()) {
                if (rational.status() == Status.BUDGET_INCONCLUSIVE) {
                    throw new ExactMatrixAlgebra.Exhausted();
                }
                return;
            }
            if (!bridge.verify(rationalSource, rational)) {
                throw new ExactMatrixAlgebra.Unsupported("RATIONAL_FORMATION_REPLAY_REJECTED");
            }
            var recognized = rational.representation().orElseThrow();
            var columns = system.unknowns().stream().map(recognized.variables()::indexOf).toList();
            exact = new ExactLinearSystem(new ExactLinearSystem.ExactMatrix(recognized.coefficients().rows().stream()
                    .map(row -> columns.stream().map(row::get).toList()).toList()),
                system.unknowns(), recognized.rightHandSide(),
                system.rowOrigins().stream().map(row -> new ExactLinearSystem.RowOrigin(row.sourceIndex(), row.sourceEquation())).toList(),
                recognized.coefficientRank(), recognized.augmentedRank(), recognized.solutionClassification());
            blocks = new ExactLinearSystemBlockDecomposer().analyze(exact, budget());
            consume(blocks.work());
            if (blocks.status() == Status.BUDGET_INCONCLUSIVE) {
                throw new ExactMatrixAlgebra.Exhausted();
            }
            sources.retainRationalOrder(exact.variables());
        }

        private Budget budget() {
            return new Budget(work.remaining());
        }

        private void consume(WorkLedger ledger) {
            work.consume(ledger.consumedWorkUnits());
        }

        private Analysis result() {
            return new Analysis(request, status, detail, equations, Optional.ofNullable(formation),
                Optional.ofNullable(blocks), attempts, Optional.ofNullable(eigenproblem),
                Optional.ofNullable(characteristic), Optional.ofNullable(exact), Optional.ofNullable(reduction), work.ledger());
        }
    }
}
