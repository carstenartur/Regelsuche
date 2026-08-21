package de.regelsuche.benchmarks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.benchmarks.ComparativeBenchmark.Case;
import de.regelsuche.benchmarks.ComparativeBenchmark.ExpectedVerdict;
import de.regelsuche.benchmarks.ComparativeBenchmark.SystemKind;
import de.regelsuche.benchmarks.ComparativeBenchmark.Track;
import de.regelsuche.benchmarks.ComparativeBenchmarkSystems.SimplificationSystem;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class SymPyNativeOperationPortfolioTest {
    private static final List<String> EXPECTED_BACKEND_IDS = List.of(
        "sympy-cas-apart",
        "sympy-cas-cancel",
        "sympy-cas-factor",
        "sympy-cas-simplifier",
        "sympy-cas-together",
        "sympy-cas-trigsimp");

    @Test
    void defaultPortfolioKeepsEveryNativeOperationSeparate() {
        List<SimplificationSystem> systems =
            ComparativeBenchmarkRunner.defaultSimplificationSystems();

        assertEquals(8, systems.size());
        List<SimplificationSystem> external = systems.stream()
            .filter(system -> system.kind() == SystemKind.EXTERNAL_BASELINE)
            .toList();
        assertEquals(6, external.size());
        assertEquals(EXPECTED_BACKEND_IDS, external.stream()
            .map(SimplificationSystem::id)
            .sorted()
            .toList());
        assertEquals(6L, external.stream()
            .map(SimplificationSystem::implementationIdentity)
            .distinct()
            .count());
        assertEquals(6L, external.stream()
            .map(SimplificationSystem::environmentIdentity)
            .distinct()
            .count());
        assertTrue(external.stream().allMatch(system ->
            system.limitations().contains(
                "ONE_NAMED_CAS_OPERATION_PER_CONFIGURATION")
                || !system.available()));
    }

    @Test
    void operationIdentityIsBoundIntoEveryConfigurationHash() {
        List<ExternalSymPySimplificationBaseline> baselines =
            ExternalSymPySimplificationBaseline
                .detectSystemSymPyOperations();

        assertEquals(
            List.of(ExternalSymPySimplificationBaseline.Operation.values()),
            baselines.stream()
                .map(ExternalSymPySimplificationBaseline::operation)
                .toList());
        assertEquals(EXPECTED_BACKEND_IDS, baselines.stream()
            .map(ExternalSymPySimplificationBaseline::backendId)
            .sorted()
            .toList());
        assertEquals(6L, baselines.stream()
            .map(ExternalSymPySimplificationBaseline::configurationHash)
            .distinct()
            .count());
        assertEquals(
            "sympy-cas-simplifier",
            ExternalSymPySimplificationBaseline.detectSystemSymPy()
                .backendId());
        assertTrue(ExternalSymPySimplificationBaseline.SIMPLIFY_SCRIPT
            .contains("sympy.simplify(a)"));
        assertTrue(ExternalSymPySimplificationBaseline.SIMPLIFY_SCRIPT
            .contains("except NotImplementedError"));
    }

    @Test
    void multivariateApartNotApplicableRemainsAnExecutedOutput() {
        ExternalSymPySimplificationBaseline apart =
            ExternalSymPySimplificationBaseline.detectSystemSymPy(
                ExternalSymPySimplificationBaseline.Operation.APART);
        Assumptions.assumeTrue(
            apart.available(),
            "SymPy is required for native-operation characterization");
        Case benchmarkCase = Case.create(
            "apart-multivariate-not-applicable",
            Track.SIMPLIFICATION_COMPETITION,
            "multivariate-rational",
            "1 / ((a + b) * (a + c))",
            "1 / ((a + b) * (a + c))",
            List.of(),
            ExpectedVerdict.TARGET_REACHED);

        ExternalSymPySimplificationBaseline.Simplification result =
            apart.simplify(
                benchmarkCase.inputExpression(),
                SimplificationAssumptionContract.forCase(benchmarkCase));

        assertEquals(
            ExternalSymPySimplificationBaseline.Outcome.PRODUCED,
            result.outcome());
        assertFalse(result.producedExpression().isBlank());
        assertTrue(result.issues().contains(
            "NATIVE_OPERATION_NOT_APPLICABLE_RETURNS_INPUT"));
    }
}
