package de.regelsuche.evolution;

import de.regelsuche.math.algorithms.equivalence.ExactPolynomialAnalysis;
import de.regelsuche.polynomial.BigIntegerDomain;
import de.regelsuche.polynomial.BinaryQuarticFactorizationEngine;
import de.regelsuche.polynomial.FactorizationRequest;
import de.regelsuche.polynomial.FactorizationVerifier;
import de.regelsuche.polynomial.Monomial;
import de.regelsuche.polynomial.PolynomialRing;
import de.regelsuche.polynomial.PolynomialVariable;
import de.regelsuche.polynomial.SparsePolynomial;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.function.LongConsumer;

/** Bounded adapter to the existing verified integer binary-quartic engine. */
final class QuarticConstruction {
    private static final long MAX_ENGINE_AND_CHECK_WORK = 16_384;
    private static final int MAX_CANDIDATES = 8;
    private static final FactorizationRequest.StructuralLimits LIMITS =
        new FactorizationRequest.StructuralLimits(2, 4, 5, 4_096);

    private QuarticConstruction() { }

    static List<String> generate(String source, long remaining, LongConsumer work) {
        if (remaining <= 0) return List.of();
        long[] projectionWork = {0};
        LongConsumer chargeProjection = units -> {
            projectionWork[0] = Math.addExact(projectionWork[0], units);
            work.accept(units);
        };
        var input = ExactPolynomialAnalysis.withProductWork(chargeProjection).normalForm(source);
        var variables = input.variables().stream().sorted().toList();
        chargeProjection.accept(input.termCount() + variables.size());
        if (variables.size() != 2 || input.totalDegree() != 4) return List.of();
        var ring = new PolynomialRing<>(BigIntegerDomain.INSTANCE,
            variables.stream().map(PolynomialVariable::new).toList(),
            PolynomialRing.MonomialOrder.GRADED_LEXICOGRAPHIC);
        var terms = new HashMap<Monomial, BigInteger>();
        for (var entry : input.terms().entrySet()) {
            var coefficient = entry.getValue();
            var monomial = entry.getKey();
            chargeProjection.accept(coefficient.numerator().toString().length() + 1L);
            if (!coefficient.denominator().equals(BigInteger.ONE) || monomial.totalDegree() != 4) return List.of();
            terms.put(Monomial.of(monomial.exponentOf(variables.get(0)), monomial.exponentOf(variables.get(1))),
                coefficient.numerator());
        }
        var polynomial = new SparsePolynomial<>(ring, terms);
        // Projection is atomic and paid even when it exhausts the allowance.
        // Do not start another bounded call after the allowance is already spent.
        if (projectionWork[0] >= remaining) return List.of();
        long engineLimit = Math.min(remaining - projectionWork[0], MAX_ENGINE_AND_CHECK_WORK);
        var request = FactorizationRequest.verifiedDecomposition(polynomial, LIMITS, MAX_CANDIDATES, engineLimit);
        var report = FactorizationVerifier.execute(new BinaryQuarticFactorizationEngine(), request);
        work.accept(report.work().totalWorkUnits());
        var results = new ArrayList<String>();
        for (var candidate : report.candidates()) {
            if (candidate.factors().size() != 2 || !candidate.unresolvedRemainder().isOne()
                    || candidate.factors().stream().anyMatch(factor -> factor.multiplicity() != 1)) continue;
            String first = render(candidate.factors().get(0).polynomial(), work);
            String second = render(candidate.factors().get(1).polynomial(), work);
            String result = "((" + candidate.unit() + ")*(" + first + "))*(" + second + ")";
            work.accept(result.length());
            results.add(result);
        }
        return List.copyOf(results);
    }

    private static String render(SparsePolynomial<BigInteger> polynomial, LongConsumer work) {
        var result = new StringBuilder();
        var variables = polynomial.ring().variables();
        for (var term : polynomial.terms().entrySet()) {
            if (!result.isEmpty()) result.append('+');
            result.append('(').append(term.getValue()).append(')');
            for (int i = 0; i < variables.size(); i++) {
                int exponent = term.getKey().exponent(i);
                if (exponent > 0) result.append('*').append(variables.get(i).id()).append('^').append(exponent);
            }
            work.accept(1);
        }
        work.accept(result.length());
        return result.toString();
    }
}
