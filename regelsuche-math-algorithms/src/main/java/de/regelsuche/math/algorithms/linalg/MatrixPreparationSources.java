package de.regelsuche.math.algorithms.linalg;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Equation;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.math.algorithms.equivalence.Polynomial;
import de.regelsuche.math.algorithms.equivalence.Rational;
import de.regelsuche.math.algorithms.linalg.SymbolicLinearSystem.PolynomialMatrix;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.representation.RepresentationBridge;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import static de.regelsuche.math.algorithms.linalg.ExactMatrixExpression.*;

/** Deterministic, source-seeded residuals. There is no unrestricted matrix factorizer. */
final class MatrixPreparationSources {
    record Proposal(String origin, ExactMatrixExpression expression, List<String> provenance) { }
    private record Declared(Matrix matrix, Optional<PolynomialMatrix> inverse) { }
    private record Occurrences(Expr expression, List<String> paths) { }
    private final MatrixPreparation.Request request;
    private final ExactMatrixAlgebra.Work work;
    private final ExactMatrixAlgebra algebra;
    private final ExpressionParser parser = new ExpressionParser();
    private final Map<String, Declared> catalog = new LinkedHashMap<>();
    private List<String> rationalOrder = List.of();

    MatrixPreparationSources(MatrixPreparation.Request request, ExactMatrixAlgebra.Work work) {
        this.request = request;
        this.work = work;
        algebra = new ExactMatrixAlgebra(work);
    }

    List<Equation> equations() {
        for (MatrixPreparation.NamedMatrix declaration : request.catalog()) {
            PolynomialMatrix value = parseMatrix(declaration.entries());
            Optional<PolynomialMatrix> inverse = declaration.inverseWitness().isEmpty()
                ? Optional.empty() : Optional.of(parseMatrix(declaration.inverseWitness()));
            catalog.put(declaration.name(), new Declared(new Matrix(declaration.name(), value), inverse));
        }
        if (!request.matrixExpression().isBlank()) {
            ExactMatrixExpression expression = operator(request.matrixExpression());
            if (request.profile() != MatrixPreparation.Profile.EXPERIMENTAL_OPERATOR_V1 && containsInverse(expression)) {
                throw new ExactMatrixAlgebra.Unsupported("INVERSE_REQUIRES_EXPERIMENTAL_PROFILE");
            }
            return fromMatrix(algebra.evaluate(expression), request.unknowns(), request.rightHandSide());
        }
        List<Equation> equations = new ArrayList<>();
        for (String row : request.equations().split("[;\\n]+")) {
            if (!row.isBlank()) {
                if (equations.size() >= ExactMatrixAlgebra.MAX_DIMENSION) {
                    throw new ExactMatrixAlgebra.Unsupported("SOURCE_ROW_LIMIT");
                }
                Equation equation = parser.parseEquation(MatrixPreparation.text(row));
                // Bound expansion before entering the existing versioned symbolic recognizer.
                algebra.scalar(equation.left());
                algebra.scalar(equation.right());
                equations.add(equation);
            }
        }
        if (equations.isEmpty()) {
            throw new IllegalArgumentException("equation-system input is empty");
        }
        return List.copyOf(equations);
    }

    private PolynomialMatrix parseMatrix(List<List<String>> entries) {
        PolynomialMatrix matrix = algebra.matrix(entries.size(), entries.getFirst().size(),
            (row, column) -> algebra.scalar(parser.parseTerm(entries.get(row).get(column))));
        for (List<Polynomial> row : matrix.entries()) {
            for (Polynomial entry : row) {
                for (String parameter : entry.variables()) {
                    work.consume(1);
                    ExactMatrixAlgebra.require(!request.unknowns().contains(parameter),
                        "CATALOG_COEFFICIENT_USES_VECTOR_COORDINATE");
                }
            }
        }
        return matrix;
    }

    private List<Equation> fromMatrix(PolynomialMatrix matrix, List<String> coordinates, List<String> rhs) {
        ExactMatrixAlgebra.require(matrix.columns() == coordinates.size() && matrix.rows() == rhs.size(),
            "MATRIX_EQUATION_DIMENSION_MISMATCH");
        List<Equation> equations = new ArrayList<>();
        for (int row = 0; row < matrix.rows(); row++) {
            List<String> terms = new ArrayList<>();
            for (int column = 0; column < matrix.columns(); column++) {
                work.consume(1);
                terms.add("(" + matrix.get(row, column).toCanonicalString() + ")*" + coordinates.get(column));
            }
            Expr right = parser.parseTerm(rhs.get(row));
            algebra.scalar(right);
            equations.add(new Equation(parser.parseTerm(String.join(" + ", terms)), right));
        }
        return List.copyOf(equations);
    }

    List<Equation> rationalEquations(SymbolicLinearSystem system) {
        return fromMatrix(system.coefficients(), system.unknowns(), system.rightHandSide().values().stream()
            .map(Polynomial::toCanonicalString).toList());
    }

    void retainRationalOrder(List<String> coordinates) {
        rationalOrder = List.copyOf(coordinates);
    }

    void propose(SymbolicLinearSystem system, List<Equation> equations,
            Optional<ExactLinearSystemBlockDecomposition> decomposition, Consumer<Proposal> accept) {
        accept.accept(new Proposal("DIRECT_MATRIX_REPRESENTATION", new Matrix("A", system.coefficients()), List.of("source formation")));
        sourceProduct(system, equations).ifPresent(accept);
        sourceIdentity(system, equations).ifPresent(accept);
        decomposition.flatMap(blocks -> blocks(system, blocks)).ifPresent(accept);
        if (!request.matrixExpression().isBlank()) {
            accept.accept(new Proposal("DECLARED_MATRIX_EQUATION", operator(request.matrixExpression()),
                List.of("matrix equation: " + request.matrixExpression())));
        }
        experimental(accept);
        for (Declared left : catalog.values()) {
            accept.accept(new Proposal("VISIBLE_CATALOG_MATRIX", left.matrix(), List.of("catalog: " + left.matrix().name())));
            if (left.matrix().value().rows() == left.matrix().value().columns()) {
                accept.accept(new Proposal("VISIBLE_IDENTITY_PLUS_OPERATOR",
                    new Sum(new Identity(left.matrix().value().rows()), left.matrix()),
                    List.of("catalog: " + left.matrix().name())));
            }
            for (Declared right : catalog.values()) {
                work.consume(1);
                accept.accept(new Proposal("VISIBLE_ORDERED_FACTORS", new Product(left.matrix(), right.matrix()),
                    List.of("left catalog factor: " + left.matrix().name(), "right catalog factor: " + right.matrix().name())));
            }
        }
    }

    private void experimental(Consumer<Proposal> accept) {
        if (!request.operatorExpressions().isEmpty()
                && request.profile() != MatrixPreparation.Profile.EXPERIMENTAL_OPERATOR_V1) {
            throw new ExactMatrixAlgebra.Unsupported("OPERATOR_RECIPES_REQUIRE_EXPERIMENTAL_PROFILE");
        }
        for (String recipe : request.operatorExpressions()) {
            ExactMatrixExpression expression = operator(recipe);
            // A cancellation cannot launder a false inverse witness in its original expression.
            algebra.evaluate(expression);
            accept.accept(new Proposal("DECLARED_OPERATOR_EXPRESSION", expression, List.of("operator recipe: " + recipe)));
            for (MatrixOperatorRules.Rewrite rewrite : MatrixOperatorRules.prepare(expression, algebra, work)) {
                accept.accept(new Proposal(rewrite.rule(), rewrite.expression(),
                    List.of("operator recipe: " + recipe, "guarded ordered rule: " + rewrite.rule())));
            }
        }
    }

    private ExactMatrixExpression operator(String input) {
        return operator(parser.parseTerm(MatrixPreparation.text(input)), 0);
    }

    private ExactMatrixExpression operator(Expr expression, int depth) {
        work.consume(1);
        ExactMatrixAlgebra.require(depth <= ExactMatrixAlgebra.MAX_DEPTH, "OPERATOR_DEPTH_LIMIT");
        return switch (expression) {
            case VariableExpr variable -> {
                Declared entry = catalog.get(variable.name());
                ExactMatrixAlgebra.require(entry != null, "MATRIX_NOT_IN_VISIBLE_CATALOG");
                yield entry.matrix();
            }
            case BinaryExpr binary -> switch (binary.operator()) {
                case MUL -> new Product(operator(binary.left(), depth + 1), operator(binary.right(), depth + 1));
                case ADD -> new Sum(operator(binary.left(), depth + 1), operator(binary.right(), depth + 1));
                default -> throw new ExactMatrixAlgebra.Unsupported("SCALAR_RULE_OUTSIDE_ORDERED_MATRIX_CONTRACT");
            };
            case FunctionExpr function -> operatorFunction(function, depth);
            default -> throw new ExactMatrixAlgebra.Unsupported("MATRIX_OPERATOR_SYNTAX_UNSUPPORTED");
        };
    }

    private ExactMatrixExpression operatorFunction(FunctionExpr function, int depth) {
        ExactMatrixAlgebra.require(function.arguments().size() == 1, "OPERATOR_ARITY_MISMATCH");
        if (function.name().equalsIgnoreCase("I") && function.argument() instanceof NumberExpr number) {
            Rational dimension = Rational.fromExact(number.value());
            ExactMatrixAlgebra.require(dimension.denominator().equals(java.math.BigInteger.ONE)
                && dimension.numerator().signum() > 0
                && dimension.numerator().compareTo(java.math.BigInteger.valueOf(ExactMatrixAlgebra.MAX_DIMENSION)) <= 0,
                "IDENTITY_DIMENSION_LIMIT");
            return new Identity(dimension.numerator().intValueExact());
        }
        if (function.name().equals("inverse") && function.argument() instanceof VariableExpr variable) {
            Declared declaration = catalog.get(variable.name());
            ExactMatrixAlgebra.require(declaration != null && declaration.inverse().isPresent(), "EXACT_INVERSE_WITNESS_REQUIRED");
            return new Inverse(operator(function.argument(), depth + 1), declaration.inverse().orElseThrow());
        }
        throw new ExactMatrixAlgebra.Unsupported("OPERATOR_FUNCTION_UNSUPPORTED");
    }

    private static boolean containsInverse(ExactMatrixExpression expression) {
        return switch (expression) {
            case Inverse ignored -> true;
            case Product p -> containsInverse(p.left()) || containsInverse(p.right());
            case Sum s -> containsInverse(s.left()) || containsInverse(s.right());
            default -> false;
        };
    }

    private Optional<Proposal> sourceIdentity(SymbolicLinearSystem system, List<Equation> equations) {
        if (system.equationCount() != system.unknownCount()) {
            return Optional.empty();
        }
        List<String> origins = new ArrayList<>();
        for (int row = 0; row < equations.size(); row++) {
            var coordinate = new VariableExpr(system.unknowns().get(row));
            if (!positiveCoordinate(equations.get(row).left(), coordinate, 1)
                    && !positiveCoordinate(equations.get(row).right(), coordinate, -1)) {
                return Optional.empty();
            }
            origins.add("source row " + row + ": explicit +" + coordinate.name());
        }
        PolynomialMatrix residual = algebra.matrix(system.equationCount(), system.unknownCount(), (r, c) ->
            algebra.add(system.coefficients().get(r, c), Polynomial.constant(r.equals(c) ? Rational.NEGATIVE_ONE : Rational.ZERO)));
        if (residual.entries().stream().flatMap(List::stream).allMatch(Polynomial::isZero)) {
            return Optional.empty();
        }
        return Optional.of(new Proposal("SOURCE_IDENTITY_PLUS_OPERATOR",
            new Sum(new Identity(system.unknownCount()), new Matrix("source residual", residual)), origins));
    }

    private boolean positiveCoordinate(Expr expression, VariableExpr coordinate, int sign) {
        work.consume(1);
        if (expression.equals(coordinate)) {
            return sign == 1;
        }
        if (expression instanceof BinaryExpr binary) {
            if (binary.operator() == BinaryOperator.ADD || binary.operator() == BinaryOperator.SUB) {
                return positiveCoordinate(binary.left(), coordinate, sign)
                    || positiveCoordinate(binary.right(), coordinate, binary.operator() == BinaryOperator.SUB ? -sign : sign);
            }
        }
        return false;
    }

    private Optional<Proposal> blocks(SymbolicLinearSystem system, ExactLinearSystemBlockDecomposition decomposition) {
        if (decomposition.components().stream().anyMatch(c -> c.sourceRowIndices().isEmpty() || c.sourceColumnIndices().isEmpty())) {
            return Optional.empty();
        }
        List<ExactMatrixExpression> blocks = new ArrayList<>();
        List<Integer> columns = decomposition.columnPermutation().stream()
            .map(index -> system.unknowns().indexOf(rationalOrder.get(index))).toList();
        for (var component : decomposition.components()) {
            List<Integer> mapped = component.sourceColumnIndices().stream()
                .map(index -> system.unknowns().indexOf(rationalOrder.get(index))).toList();
            PolynomialMatrix block = algebra.matrix(component.sourceRowIndices().size(), mapped.size(), (r, c) ->
                system.coefficients().get(component.sourceRowIndices().get(r), mapped.get(c)));
            blocks.add(new Matrix("block " + blocks.size(), block));
        }
        return Optional.of(new Proposal("CERTIFIED_BLOCK_DIAGONAL_EXPOSURE",
            new Mapped(new BlockDiagonal(blocks), decomposition.rowPermutation(), columns),
            List.of("certified row and coordinate component partition")));
    }

    private Optional<Proposal> sourceProduct(SymbolicLinearSystem system, List<Equation> equations) {
        Map<String, Occurrences> occurrences = new LinkedHashMap<>();
        for (int row = 0; row < equations.size(); row++) {
            collect(equations.get(row).left(), "row[" + row + "].left", occurrences);
            collect(equations.get(row).right(), "row[" + row + "].right", occurrences);
        }
        Map<String, String> aliases = new LinkedHashMap<>();
        List<List<Polynomial>> innerRows = new ArrayList<>();
        List<String> provenance = new ArrayList<>();
        for (Map.Entry<String, Occurrences> entry : occurrences.entrySet()) {
            if (entry.getValue().paths().size() < 2 || aliases.size() + system.unknownCount() >= ExactMatrixAlgebra.MAX_DIMENSION) {
                continue;
            }
            var inner = symbolic(List.of(new Equation(entry.getValue().expression(), new NumberExpr(0))), system.unknowns());
            if (!inner.represented() || !inner.representation().orElseThrow().homogeneous()) {
                continue;
            }
            String alias = freshAlias(aliases.size(), system);
            aliases.put(entry.getKey(), alias);
            innerRows.add(inner.representation().orElseThrow().coefficients().entries().getFirst());
            provenance.add(alias + " = " + entry.getKey() + " at " + entry.getValue().paths());
        }
        if (aliases.isEmpty()) {
            return Optional.empty();
        }
        List<String> outerUnknowns = new ArrayList<>(aliases.values());
        outerUnknowns.addAll(system.unknowns());
        List<Equation> outerEquations = equations.stream().map(e -> new Equation(
            replace(e.left(), aliases), replace(e.right(), aliases))).toList();
        var outer = symbolic(outerEquations, outerUnknowns);
        if (!outer.represented() || !outer.representation().orElseThrow().rightHandSide().equals(system.rightHandSide())) {
            return Optional.empty();
        }
        PolynomialMatrix outerMatrix = outer.representation().orElseThrow().coefficients();
        innerRows.addAll(algebra.identity(system.unknownCount()).entries());
        List<Integer> active = new ArrayList<>();
        for (int column = 0; column < outerMatrix.columns(); column++) {
            int index = column;
            if (outerMatrix.entries().stream().anyMatch(row -> !row.get(index).isZero())) {
                active.add(index);
            }
        }
        if (active.stream().noneMatch(index -> index < aliases.size())) {
            return Optional.empty();
        }
        PolynomialMatrix left = algebra.matrix(system.equationCount(), active.size(), (r, c) -> outerMatrix.get(r, active.get(c)));
        PolynomialMatrix right = algebra.matrix(active.size(), system.unknownCount(), (r, c) -> innerRows.get(active.get(r)).get(c));
        provenance.add("intermediate order: " + active.stream().map(outerUnknowns::get).toList());
        return Optional.of(new Proposal("REPEATED_SOURCE_LINEAR_FORMS",
            new Product(new Matrix("F", left), new Matrix("G", right)), provenance));
    }

    private void collect(Expr expression, String path, Map<String, Occurrences> occurrences) {
        work.consume(1);
        if (expression instanceof BinaryExpr binary) {
            if ((binary.operator() == BinaryOperator.ADD || binary.operator() == BinaryOperator.SUB)
                    && coordinateOccurrences(expression) > 1) {
                occurrences.computeIfAbsent(ExpressionFormatter.format(expression), ignored ->
                    new Occurrences(expression, new ArrayList<>())).paths().add(path);
            }
            collect(binary.left(), path + ".left", occurrences);
            collect(binary.right(), path + ".right", occurrences);
        }
    }

    private int coordinateOccurrences(Expr expression) {
        work.consume(1);
        return switch (expression) {
            case VariableExpr v -> request.unknowns().contains(v.name()) ? 1 : 0;
            case BinaryExpr b -> coordinateOccurrences(b.left()) + coordinateOccurrences(b.right());
            default -> 0;
        };
    }

    private Expr replace(Expr expression, Map<String, String> aliases) {
        work.consume(1);
        String alias = aliases.get(ExpressionFormatter.format(expression));
        if (alias != null) {
            return new VariableExpr(alias);
        }
        return expression instanceof BinaryExpr b
            ? new BinaryExpr(replace(b.left(), aliases), b.operator(), replace(b.right(), aliases)) : expression;
    }

    private String freshAlias(int index, SymbolicLinearSystem system) {
        String alias = "_representation_" + index;
        while (system.unknowns().contains(alias) || system.scalarParameters().contains(alias)) {
            alias += "_";
        }
        return alias;
    }

    private RepresentationBridge.Result<SymbolicLinearSystem, SymbolicLinearSystemRepresentationBridge.Certificate>
            symbolic(List<Equation> equations, List<String> unknowns) {
        var result = new SymbolicLinearSystemRepresentationBridge().analyze(
            new SymbolicLinearSystemRepresentationBridge.Source(equations, unknowns),
            new RepresentationBridge.Budget(work.remaining()));
        work.consume(result.work().consumedWorkUnits());
        if (result.status() == RepresentationBridge.Status.BUDGET_INCONCLUSIVE) {
            throw new ExactMatrixAlgebra.Exhausted();
        }
        return result;
    }
}
