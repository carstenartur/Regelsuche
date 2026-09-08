package de.regelsuche.benchmark.polynomial;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.benchmark.polynomial.PolynomialTheoryUtilityMeasuredExecution.MeasuredRun;
import de.regelsuche.benchmark.polynomial.PolynomialTheoryUtilityProfileAdapter.RunDescriptor;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PolynomialTheoryUtilityOnDemandRuntimeTest {
    @Test
    void positiveComponentEvidenceUsesTheSharedPrimitiveExpansionAndExactOccurrenceSource() {
        for (String caseId : List.of("z02-difference-of-squares", "nested-single-occurrence")) {
            var input = nativeInputs().stream().filter(value -> value.caseId().equals(caseId))
                .findFirst().orElseThrow();
            var formation = PolynomialTheoryUtilityCaseCorpus.load().cases().stream()
                .filter(value -> value.caseId().equals(caseId)).findFirst().orElseThrow();
            var componentInput = new PolynomialTheoryUtilityExecutionInput(input.inputId(), input.rowId(),
                input.runId(), input.caseId(), input.profileId(), input.checkpointId(), input.adapterId(),
                1_000_000, 1_000_000, 1_000_000, input.inputStatus());
            var authority = new PolynomialTheoryUtilityWorkAuthority(componentInput);
            var parsed = new de.regelsuche.parse.ExpressionParser().parseExactTerm(formation.sourceExpression());
            List<Integer> path = formation.occurrenceDepth() == 0 ? List.of() : List.of(1);
            var selector = new de.regelsuche.moves.enumerate.TreePosition(path, "pending");
            var position = new de.regelsuche.moves.enumerate.TreePosition(path,
                de.regelsuche.parse.ExpressionFormatter.format(selector.subtreeAt(parsed.expression()).orElseThrow()));
            var nested = new de.regelsuche.polynomial.ExactNestedFactorizationTransformationPipeline(authority)
                .transform(parsed, position, de.regelsuche.math.algorithms.polynomial.NativeUnivariateFactorizationEngine
                    .rationals(de.regelsuche.math.algorithms.polynomial.NativeUnivariateFactorizationPolicy
                        .boundedDefaults()), 0);
            assertTrue(nested.transformed(), nested.detailCode());
            authority.consumePrimitive(7);
            var occurrence = new PolynomialTheoryUtilityOnDemandVerifiedFactorizationAdapter.Occurrence(
                path, nested.status().name(), nested.detailCode(), nested, authority.work());
            // Observe a component result; never create a frozen CandidateResult with the larger test budget.
            var transition = PolynomialTheoryUtilityOnDemandVerifiedFactorizationAdapter.transition(
                input, formation, occurrence, 0);
            var trace = PolynomialTheoryUtilityOnDemandVerifiedFactorizationAdapter.trace(transition, nested);
            var attempt = PolynomialTheoryUtilityOnDemandVerifiedFactorizationAdapter.attempt(input, nested, transition, 0);
            assertEquals(1, trace.pathDepth());
            assertEquals(7, trace.primitiveExpansionLength());
            assertEquals(path, transition.occurrencePath());
            assertTrue(attempt.producedTransition());
            assertEquals("VERIFIED", attempt.verifierOutcome());
            assertTrue(transition.transformedRootExpression().contains(
                nested.transformation().orElseThrow().transformedExpression().orElseThrow()));
        }
    }

    @Test
    void everyFrozenNativeRowRetainsItsOccurrencesAndReproducesItsCompleteEvidence() {
        var first = execute();
        var second = execute();
        assertEquals(120, first.size());
        for (int index = 0; index < first.size(); index++) {
            var a = first.get(index);
            var b = second.get(index);
            assertEquals(a.measured(), b.measured());
            assertEquals(a.rawWork(), b.rawWork());
            assertEquals(a.projection(), b.projection());
            assertEquals(a.evidenceHash(), b.evidenceHash());
            assertEquals(a.occurrences().stream().map(value -> value.identityMaterial()).toList(),
                b.occurrences().stream().map(value -> value.identityMaterial()).toList());
            var result = a.measured().result();
            var formation = PolynomialTheoryUtilityCaseCorpus.load().cases().stream()
                .filter(value -> value.caseId().equals(result.input().caseId())).findFirst().orElseThrow();
            assertEquals(formation.reuseCount(), a.occurrences().size());
            assertEquals(result.work(), a.projection().work());
            assertTrue(result.work().mechanicalWork() <= result.input().totalMechanicalWork());
            assertTrue(result.work().factorizationWork() <= result.input().factorizationWork());
            assertTrue(a.measured().measurements().cacheEvents().isEmpty());
        }
        assertTrue(first.stream().anyMatch(value ->
            !value.measured().measurements().factorizationAttempts().isEmpty()),
            "runtime admission must reach the native engine on the frozen matrix");
        var witnessed = first.stream().filter(value ->
            !value.measured().measurements().factorizationAttempts().isEmpty()).findFirst().orElseThrow();
        assertThrows(IllegalArgumentException.class, () ->
            new PolynomialTheoryUtilityOnDemandVerifiedFactorizationAdapter.Execution(witnessed.measured(),
                de.regelsuche.polynomial.PolynomialWorkLedger.empty(), witnessed.projection(),
                witnessed.occurrences(), witnessed.evidenceHash()));
        assertThrows(IllegalArgumentException.class, () ->
            new PolynomialTheoryUtilityOnDemandVerifiedFactorizationAdapter.Execution(witnessed.measured(),
                witnessed.rawWork(), witnessed.projection(), List.of(), witnessed.evidenceHash()));
    }

    @Test
    void rejectsAReorderedInputWithoutAdvancingTheRun() {
        var inputs = nativeInputs().subList(0, 20);
        var adapter = new PolynomialTheoryUtilityOnDemandVerifiedFactorizationAdapter(value -> { });
        var run = (MeasuredRun) adapter.openRun(descriptor(inputs.getFirst()));
        var cases = PolynomialTheoryUtilityCaseCorpus.load().cases();
        assertThrows(IllegalArgumentException.class, () -> run.executeMeasured(inputs.get(1), cases.get(1)));
        for (int index = 0; index < inputs.size(); index++) {
            assertNotNull(run.executeMeasured(inputs.get(index), cases.get(index)));
        }
        run.close();
        assertThrows(IllegalStateException.class, run::close);
    }

    private static List<PolynomialTheoryUtilityOnDemandVerifiedFactorizationAdapter.Execution> execute() {
        var results = new ArrayList<PolynomialTheoryUtilityOnDemandVerifiedFactorizationAdapter.Execution>();
        var adapter = new PolynomialTheoryUtilityOnDemandVerifiedFactorizationAdapter(results::add);
        var cases = PolynomialTheoryUtilityCaseCorpus.load().cases();
        var inputs = nativeInputs();
        for (int start = 0; start < inputs.size(); start += cases.size()) {
            try (var run = (MeasuredRun) adapter.openRun(descriptor(inputs.get(start)))) {
                for (int index = 0; index < cases.size(); index++) {
                    run.executeMeasured(inputs.get(start + index), cases.get(index));
                }
            }
        }
        return results;
    }

    private static List<PolynomialTheoryUtilityExecutionInput> nativeInputs() {
        return PolynomialTheoryUtilityExecutionInputs.freeze().inputs().stream()
            .filter(value -> value.profileId().equals(
                PolynomialTheoryUtilityOnDemandVerifiedFactorizationAdapter.PROFILE_ID)).toList();
    }

    private static RunDescriptor descriptor(PolynomialTheoryUtilityExecutionInput input) {
        return new RunDescriptor(input.runId(), input.profileId(), input.checkpointId(), input.adapterId(), 20);
    }
}
