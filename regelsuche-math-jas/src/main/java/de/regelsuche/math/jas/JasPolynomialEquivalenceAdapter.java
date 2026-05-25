package de.regelsuche.math.jas;

import de.regelsuche.equivalence.PolynomialEquivalenceService;

/**
 * Optional adapter placeholder for future JAS integration.
 *
 * <p>The adapter stays isolated in its own module so heavy CAS dependencies are
 * never pulled into default runtime paths.
 */
public class JasPolynomialEquivalenceAdapter implements PolynomialEquivalenceService {
    @Override
    public boolean arePolynomiallyEquivalent(String leftPolynomial, String rightPolynomial) {
        return false;
    }

    @Override
    public String evidence(String leftExpression, String rightExpression) {
        return "JAS backend not configured";
    }
}
