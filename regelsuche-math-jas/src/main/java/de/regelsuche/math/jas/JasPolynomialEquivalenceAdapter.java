package de.regelsuche.math.jas;

import de.regelsuche.equivalence.PolynomialEquivalenceService;

/**
 * Optional adapter placeholder for future JAS Gröbner-basis integration.
 *
 * <p>The adapter stays isolated in its own module so heavy CAS dependencies are
 * never pulled into default runtime paths. Until a concrete JAS dependency is
 * wired in, callers must treat this backend as unavailable and must not
 * silently fall back to polynomial normal forms.
 */
public class JasPolynomialEquivalenceAdapter implements PolynomialEquivalenceService {
    @Override
    public boolean arePolynomiallyEquivalent(String leftPolynomial, String rightPolynomial) {
        throw new IllegalStateException("JAS backend not configured");
    }

    @Override
    public String evidence(String leftExpression, String rightExpression) {
        return "JAS backend not configured";
    }
}
