package de.regelsuche.sdk.optimization;

import de.regelsuche.ast.Expr;
import de.regelsuche.math.algorithms.algebra.BilinearPairCandidates;
import java.util.List;
import java.util.Optional;

/** Java BigInteger adapter. Core proposes candidates; PolynomialProof owns acceptance. */
final class BilinearCandidates {
    private final VerificationWork work;
    private static final BilinearPairCandidates.Algebra<Expr> ALGEBRA = new BilinearPairCandidates.Algebra<>() {
        public Optional<BilinearPairCandidates.Binary<Expr>> view(Expr expression) {
            var operation = JavaExpressions.operationOf(expression).orElse(null);
            if (operation == null || JavaExpressions.resultKind(expression) != NumericKind.BIG_INTEGER)
                return Optional.empty();
            var kind = switch (operation) {
                case ADD -> BilinearPairCandidates.Operation.ADD;
                case SUBTRACT -> BilinearPairCandidates.Operation.SUBTRACT;
                case MULTIPLY -> BilinearPairCandidates.Operation.MULTIPLY;
                case MOD -> BilinearPairCandidates.Operation.MOD;
                default -> null;
            };
            var operands = JavaExpressions.operands(expression);
            return kind == null || operands.size() != 2 ? Optional.empty()
                : Optional.of(new BilinearPairCandidates.Binary<>(kind, operands.get(0), operands.get(1)));
        }
        public Expr make(BilinearPairCandidates.Operation operation, Expr left, Expr right) {
            return JavaExpressions.operation(NumericKind.BIG_INTEGER,
                NumericOperation.valueOf(operation.name()), left, right);
        }
    };

    BilinearCandidates(VerificationWork work) { this.work = work; }

    List<List<Expr>> generate(List<Expr> outputs, int maximum) {
        return BilinearPairCandidates.generate(outputs, maximum, ALGEBRA, work::charge);
    }
}
