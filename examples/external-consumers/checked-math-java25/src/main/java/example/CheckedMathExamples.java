package example;

/** Runs all ten checked calculations using only the externally resolved SDK. */
public final class CheckedMathExamples {
    private CheckedMathExamples() {}

    public static void main(String[] args) {
        FiniteConstraintCount.main(args);
        EnergyCredits.main(args);
        SosPotential.main(args);
        BilinearSearch.main(args);
        RationalMatrixFormula.main(args);
        DynamicQuotient.main(args);
        BoundedPlanning.main(args);
        MatrixCenterIdentity.main(args);
        SpectralRepair.main(args);
        ComposedBilinearPlans.main(args);
    }
}
