package de.regelsuche.equivalence;

/**
 * Specialised {@link EquivalenceService} port for polynomial expressions.
 *
 * <p>Introduced as part of Teil 0 of the Discovery Epic (issue #41,
 * "Interfaces zuerst"): polynomial-specific equivalence checks (canonical
 * expansion, Gröbner-basis ideal reduction, etc.) require a dedicated
 * interface so search, validation and discovery features can depend on
 * the port without pulling in a concrete CAS implementation.
 *
 * <p>The default {@link #areEquivalent} delegates to the polynomial
 * specific check so the type can be used wherever an {@link
 * EquivalenceService} is expected.
 */
public interface PolynomialEquivalenceService extends EquivalenceService {

    /**
     * @return whether {@code leftPolynomial} and {@code rightPolynomial} are
     *     equal as polynomials over the implementation's coefficient ring.
     */
    boolean arePolynomiallyEquivalent(String leftPolynomial, String rightPolynomial);

    @Override
    default boolean areEquivalent(String leftExpression, String rightExpression) {
        return arePolynomiallyEquivalent(leftExpression, rightExpression);
    }
}
