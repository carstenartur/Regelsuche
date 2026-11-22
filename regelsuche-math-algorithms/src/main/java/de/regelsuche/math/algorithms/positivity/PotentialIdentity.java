package de.regelsuche.math.algorithms.positivity;

import de.regelsuche.math.algorithms.certificates.CertificateWork;
import de.regelsuche.math.algorithms.equivalence.Monomial;
import de.regelsuche.math.algorithms.equivalence.Polynomial;
import de.regelsuche.scalar.ExactRational;
import java.util.Map;

/** Replay V(T) + delta + claimed = V. This does not call the search's substitution routine. */
final class PotentialIdentity {
    private PotentialIdentity() {}

    static boolean matches(PolynomialPotential.Request request, Polynomial potential, Polynomial claimed,
            CertificateWork work) {
        SosPolynomials.polynomialSize(claimed, work);
        var left = SosPolynomials.coefficients(claimed, work);
        var constant = Monomial.constant();
        left.add(constant, request.minimumDecrease(), work);
        for (var term : potential.terms().entrySet()) {
            Map<Monomial, ExactRational> image = Map.of(constant, term.getValue().exactValue());
            for (var power : term.getKey().powers().entrySet()) {
                var source = request.transition().get(power.getKey());
                var factor = source == null ? Map.of(Monomial.variable(power.getKey()), ExactRational.ONE)
                    : SosPolynomials.coefficients(source, work).terms;
                // Left-to-right powering reconstructs each substituted factor separately.
                var raised = factor;
                for (int bit = Integer.highestOneBit(power.getValue()) >>> 1; bit != 0; bit >>>= 1) {
                    raised = SosPolynomials.product(raised, raised, work);
                    if ((power.getValue() & bit) != 0) raised = SosPolynomials.product(raised, factor, work);
                }
                image = SosPolynomials.product(image, raised, work);
            }
            for (var entry : image.entrySet()) left.add(entry.getKey(), entry.getValue(), work);
        }
        if (left.terms.size() != potential.termCount()) return false;
        for (var term : potential.terms().entrySet()) {
            if (!work.check(term.getValue().exactValue()).equals(left.terms.get(term.getKey()))) return false;
        }
        work.charge(0);
        return true;
    }
}
