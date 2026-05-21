package de.regelsuche.scoring.cost;

/**
 * High-level intent that drives the choice of {@link CostModel} (and, via
 * {@link de.regelsuche.search.SearchProfile}, indirectly the search
 * strategy too). Surfacing this as an enum lets the UI offer a goal
 * dropdown next to the existing profile dropdown without expanding the
 * profile enum into a cross-product.
 */
public enum TransformationGoal {
    /** Minimise expression size — the historical default. */
    SIMPLIFY(new OperatorCountCost()),

    /** Prefer factored form over expanded polynomials. */
    FACTORIZE(new FactoredFormCost()),

    /** Prefer numerically stable form (Horner over expanded, etc.). */
    NUMERICALLY_STABLE(new NumericStabilityCost()),

    /** Prefer shallow, symmetric forms that ease case analysis in a proof. */
    PROOF_FRIENDLY(new SymmetryCost()),

    /**
     * Prefer school-book-style notation: small coefficients, shallow
     * nesting, no exotic functions in the result.
     */
    TEACHING_FRIENDLY(new TeachingFriendlinessCost());

    private final CostModel defaultCostModel;

    TransformationGoal(CostModel defaultCostModel) {
        this.defaultCostModel = defaultCostModel;
    }

    /** The cost model that this goal selects by default. */
    public CostModel defaultCostModel() {
        return defaultCostModel;
    }
}
