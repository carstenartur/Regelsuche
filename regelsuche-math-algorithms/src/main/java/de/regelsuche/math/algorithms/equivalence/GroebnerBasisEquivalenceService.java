package de.regelsuche.math.algorithms.equivalence;

import de.regelsuche.equivalence.PolynomialEquivalenceService;
import de.regelsuche.math.algorithms.equivalence.PolynomialArithmetic.Monomial;
import de.regelsuche.math.algorithms.equivalence.PolynomialArithmetic.Polynomial;
import de.regelsuche.math.algorithms.equivalence.PolynomialArithmetic.Term;
import de.regelsuche.validation.MathematicalAlgorithmRegistry;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Gröbner-basis service for small polynomial ideals/systems.
 *
 * <p>This class is intentionally separate from {@link PolynomialNormalFormEquivalenceService}: normal forms prove
 * direct polynomial identities, while this service computes a basis for generators and reduces a polynomial modulo
 * the generated ideal. If a concrete external backend such as JAS is requested but unavailable, the service reports
 * {@code UNAVAILABLE} instead of falling back to normal-form equivalence.
 */
public class GroebnerBasisEquivalenceService implements PolynomialEquivalenceService {
    private final PolynomialArithmetic arithmetic = new PolynomialArithmetic();
    private final MathematicalAlgorithmRegistry registry;
    private final boolean jasBackendAvailable;
    private MathematicalAlgorithmRegistry.AlgorithmExecutionResult lastResult =
        MathematicalAlgorithmRegistry.AlgorithmExecutionResult.unknown("not executed");

    public GroebnerBasisEquivalenceService(MathematicalAlgorithmRegistry registry) {
        this(registry, false);
    }

    public GroebnerBasisEquivalenceService(MathematicalAlgorithmRegistry registry, boolean jasBackendAvailable) {
        this.registry = registry;
        this.jasBackendAvailable = jasBackendAvailable;
    }

    public MathematicalAlgorithmRegistry.AlgorithmExecutionResult lastResult() {
        return lastResult;
    }

    @Override
    public boolean arePolynomiallyEquivalent(String leftPolynomial, String rightPolynomial) {
        lastResult = MathematicalAlgorithmRegistry.AlgorithmExecutionResult.unknown(
            "Gröbner equivalence requires explicit ideal generators; use reducesToZeroModuloIdeal");
        return false;
    }

    @Override
    public String evidence(String leftExpression, String rightExpression) {
        arePolynomiallyEquivalent(leftExpression, rightExpression);
        return lastResult.detail();
    }

    public boolean reducesToZeroModuloIdeal(String polynomialExpression, List<String> generatorExpressions) {
        Optional<String> remainder = normalFormModuloIdeal(polynomialExpression, generatorExpressions);
        return remainder.isPresent() && "0".equals(remainder.orElseThrow());
    }

    public Optional<String> normalFormModuloIdeal(String polynomialExpression, List<String> generatorExpressions) {
        if (!registry.isEnabled(MathematicalAlgorithmRegistry.GROEBNER_BASIS)) {
            lastResult = MathematicalAlgorithmRegistry.AlgorithmExecutionResult.disabled(
                "groebnerBasis must be enabled for ideal reduction");
            return Optional.empty();
        }
        if (registry.isEnabled(MathematicalAlgorithmRegistry.JAS_BACKEND) && !jasBackendAvailable) {
            lastResult = MathematicalAlgorithmRegistry.AlgorithmExecutionResult.unavailable(
                "jasBackend is enabled, but no JAS Gröbner adapter is available");
            return Optional.empty();
        }
        if (generatorExpressions == null || generatorExpressions.isEmpty()) {
            lastResult = MathematicalAlgorithmRegistry.AlgorithmExecutionResult.unknown(
                "ideal reduction requires at least one generator");
            return Optional.empty();
        }

        Optional<Polynomial> polynomial = arithmetic.parse(polynomialExpression);
        if (polynomial.isEmpty()) {
            lastResult = MathematicalAlgorithmRegistry.AlgorithmExecutionResult.unknown(
                "unsupported polynomial expression");
            return Optional.empty();
        }

        List<Polynomial> generators = new ArrayList<>();
        for (String generatorExpression : generatorExpressions) {
            Optional<Polynomial> generator = arithmetic.parse(generatorExpression);
            if (generator.isEmpty()) {
                lastResult = MathematicalAlgorithmRegistry.AlgorithmExecutionResult.unknown(
                    "unsupported ideal generator");
                return Optional.empty();
            }
            if (!generator.orElseThrow().isZero()) {
                generators.add(generator.orElseThrow());
            }
        }
        if (generators.isEmpty()) {
            lastResult = MathematicalAlgorithmRegistry.AlgorithmExecutionResult.unknown(
                "ideal generators reduce to zero");
            return Optional.empty();
        }

        int maxSteps = registry.find(MathematicalAlgorithmRegistry.GROEBNER_BASIS)
            .map(MathematicalAlgorithmRegistry.AlgorithmDescriptor::budget)
            .map(MathematicalAlgorithmRegistry.AlgorithmBudget::maxSteps)
            .orElse(200);
        BasisComputation basisComputation = computeBasis(generators, maxSteps);
        if (basisComputation.budgetExhausted()) {
            lastResult = new MathematicalAlgorithmRegistry.AlgorithmExecutionResult(
                MathematicalAlgorithmRegistry.ExecutionStatus.BUDGET_EXHAUSTED,
                MathematicalAlgorithmRegistry.ResultType.DIAGNOSTIC,
                "Gröbner basis computation exceeded budget",
                Map.of("capability", MathematicalAlgorithmRegistry.GROEBNER_BASIS)
            );
            return Optional.empty();
        }

        Polynomial remainder = reduce(polynomial.orElseThrow(), basisComputation.basis());
        boolean zero = remainder.isZero();
        lastResult = new MathematicalAlgorithmRegistry.AlgorithmExecutionResult(
            MathematicalAlgorithmRegistry.ExecutionStatus.SUCCESS,
            zero ? MathematicalAlgorithmRegistry.ResultType.PROOF : MathematicalAlgorithmRegistry.ResultType.REFUTATION,
            zero ? "polynomial reduces to 0 modulo Gröbner basis" : "non-zero remainder modulo Gröbner basis",
            Map.of(
                "capability", MathematicalAlgorithmRegistry.GROEBNER_BASIS,
                "backend", registry.isEnabled(MathematicalAlgorithmRegistry.JAS_BACKEND) ? "jas" : "pureJavaSmallGroebner",
                "remainder", remainder.toCanonicalString(),
                "basis", basisComputation.basis().stream().map(Polynomial::toCanonicalString).toList()
            )
        );
        return Optional.of(remainder.toCanonicalString());
    }

    private BasisComputation computeBasis(List<Polynomial> generators, int maxSteps) {
        List<Polynomial> basis = new ArrayList<>(generators);
        List<int[]> pairs = new ArrayList<>();
        for (int i = 0; i < basis.size(); i++) {
            for (int j = i + 1; j < basis.size(); j++) {
                pairs.add(new int[] {i, j});
            }
        }

        int steps = 0;
        while (!pairs.isEmpty()) {
            if (steps++ >= maxSteps) {
                return new BasisComputation(basis, true);
            }
            int[] pair = pairs.remove(0);
            Polynomial remainder = reduce(sPolynomial(basis.get(pair[0]), basis.get(pair[1])), basis);
            if (!remainder.isZero()) {
                int nextIndex = basis.size();
                basis.add(remainder);
                for (int i = 0; i < nextIndex; i++) {
                    pairs.add(new int[] {i, nextIndex});
                }
            }
        }
        return new BasisComputation(basis, false);
    }

    private Polynomial sPolynomial(Polynomial left, Polynomial right) {
        Term leftTerm = left.leadingTerm().orElseThrow();
        Term rightTerm = right.leadingTerm().orElseThrow();
        Monomial lcm = leftTerm.monomial().lcm(rightTerm.monomial());
        Monomial leftMultiplier = lcm.divideBy(leftTerm.monomial());
        Monomial rightMultiplier = lcm.divideBy(rightTerm.monomial());
        Polynomial normalizedLeft = left.multiply(leftMultiplier, BigDecimal.ONE.divide(leftTerm.coefficient(), PolynomialArithmetic.MC));
        Polynomial normalizedRight = right.multiply(rightMultiplier, BigDecimal.ONE.divide(rightTerm.coefficient(), PolynomialArithmetic.MC));
        return normalizedLeft.subtract(normalizedRight);
    }

    private Polynomial reduce(Polynomial polynomial, List<Polynomial> basis) {
        Polynomial remainder = Polynomial.constant(BigDecimal.ZERO);
        Polynomial current = polynomial;
        while (!current.isZero()) {
            Term currentLeadingTerm = current.leadingTerm().orElseThrow();
            Optional<Reduction> reduction = firstReduction(currentLeadingTerm, basis);
            if (reduction.isPresent()) {
                Reduction divisor = reduction.orElseThrow();
                current = current.subtract(divisor.polynomial().multiply(
                    currentLeadingTerm.monomial().divideBy(divisor.leadingTerm().monomial()),
                    currentLeadingTerm.coefficient().divide(divisor.leadingTerm().coefficient(), PolynomialArithmetic.MC)
                ));
            } else {
                Polynomial leadingPolynomial = Polynomial.term(currentLeadingTerm.monomial(), currentLeadingTerm.coefficient());
                remainder = remainder.add(leadingPolynomial);
                current = current.subtract(leadingPolynomial);
            }
        }
        return remainder;
    }

    private Optional<Reduction> firstReduction(Term leadingTerm, List<Polynomial> basis) {
        return basis.stream()
            .map(polynomial -> new Reduction(polynomial, polynomial.leadingTerm().orElseThrow()))
            .filter(reduction -> reduction.leadingTerm().monomial().divides(leadingTerm.monomial()))
            .findFirst();
    }

    private record Reduction(Polynomial polynomial, Term leadingTerm) {
    }

    private record BasisComputation(List<Polynomial> basis, boolean budgetExhausted) {
    }
}
