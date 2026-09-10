package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.search.moves.*;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(240)
class LearnedSchedulingStudyTest {
    private static LearnedSchedulingStudy.Report report;
    @BeforeAll static void evaluateFrozenProtocol() throws Exception {
        report = LearnedSchedulingStudy.run();
        LearnedSchedulingArtifacts.write(report, Path.of("build/reports/learned-scheduling"));
    }
    @Test void retainsEveryPreregisteredRowAndOnlyAcceptsBudgetedIndependentlyReplayedWitnesses() {
        assertEquals(LearnedSchedulingProtocol.cases().size() * LearnedSchedulingProtocol.WORK_BUDGETS.size()
            * LearnedSchedulingProtocol.configurations().size(), report.rows().size());
        assertFalse(report.model().traces().isEmpty());
        assertTrue(report.model().knowledge().trainingExactWorkUnits() > report.model().knowledge().trainingExactAuditCalls());
        assertTrue(report.model().knowledge().trainingReferenceSupplementaryWorkUnits() > 0);
        assertEquals(report.model().trainingWorkComponents().values().stream().mapToLong(Long::longValue).sum(), report.model().trainingWorkUnits());
        for (String component : List.of("activityTraining", "historyTraining", "utilityTraining"))
            assertTrue(report.model().trainingWorkComponents().get(component) > 0, component);
        for (var trace : report.model().traces()) {
            var entry = report.model().activity().rules().get(trace.id());
            assertEquals(entry.applications(), trace.utility().applicabilityCount());
            assertEquals(entry.successes(), trace.utility().successfulApplications());
            assertEquals(trace.utility(), de.regelsuche.inventory.RuleUtilityEvidence.fromJson(trace.utility().toCanonicalJson()));
            assertThrows(IllegalArgumentException.class, () -> de.regelsuche.inventory.RuleUtilityFeedback.update(trace.utility(), trace.id(),
                List.of(), MoveContext.Phase.FROZEN_EVALUATION));
        }
        var verifier = new PrimitiveReplayMoveVerifier(report.model().knowledge().inventory());
        for (var row : report.rows()) {
            if (!row.reached()) continue;
            assertTrue(row.result().metrics().totalWork() <= row.budget());
            var example = LearnedSchedulingProtocol.cases().stream().filter(value -> value.id().equals(row.caseId())).findFirst().orElseThrow();
            String current = LearnedSchedulingModel.format(example.source());
            for (var step : row.result().witness()) {
                assertEquals(current, step.source().expression());
                var verification = verifier.verify(step.source(), step.move(), MoveContext.frozen(example.target()));
                assertTrue(verification.accepted(), verification.reason()); assertEquals(verification, step.verification());
                assertTrue(step.move().assumptions().isEmpty()); current = step.target().expression();
            }
            assertEquals(LearnedSchedulingModel.format(example.target()), current);
            assertEquals(row.result().witness().size(), row.result().metrics().firstHitDepth());
            for (var event : row.result().events()) assertEquals(event.move().transformation().transformedExpression(), event.target().expression());
        }
        assertTrue(report.rows().stream().filter(row -> row.caseId().equals("residual-control")).noneMatch(LearnedSchedulingStudy.Row::reached));
        assertTrue(report.rows().stream().filter(row -> row.caseId().equals("unsupported-control")).allMatch(row -> row.result() == null));
    }
    @Test void referenceClaimsRequireCompleteClosuresAndNaiveRankedUseIdenticalFrozenMathematics() {
        for (var reference : report.references()) if (reference.inclusion()) {
            assertTrue(reference.base().completeBoundedRelation()); assertTrue(reference.learned().completeBoundedRelation());
            assertTrue(reference.learned().reachedStates().stream().map(MoveState::expression).collect(java.util.stream.Collectors.toSet())
                .containsAll(reference.base().reachedStates().stream().map(MoveState::expression).toList()));
        }
        var naive = LearnedSchedulingProtocol.configurations().get(1); var ranked = LearnedSchedulingProtocol.configurations().get(2);
        assertEquals(report.model().providers(naive).stream().map(provider -> provider.descriptor().id()).toList(),
            report.model().providers(ranked).stream().map(provider -> provider.descriptor().id()).toList());
        var context = MoveContext.frozen("unused"); var state = MoveState.root(LearnedSchedulingModel.format("(x+y)*(x-y)+y*y"));
        for (int i = 0; i < report.model().providers(naive).size(); i++) assertEquals(
            report.model().providers(naive).get(i).candidates(state, context).moves().stream().map(SearchMove::transformation).toList(),
            report.model().providers(ranked).get(i).candidates(state, context).moves().stream().map(SearchMove::transformation).toList());
        var training = TraceStrategyTransferExample.trainingInputs().getFirst();
        assertThrows(IllegalArgumentException.class, () -> LearnedSchedulingStudy.rejectOverlap(report.model(),
            List.of(new LearnedSchedulingProtocol.Case("leak", training.expression(), "x", true))));
    }
    @Test void repeatedFrozenRunHasIdenticalEvidenceWithoutChangingModel() {
        String before = LearnedSchedulingArtifacts.modelJson(report.model());
        var example = LearnedSchedulingProtocol.cases().get(1); var configuration = LearnedSchedulingProtocol.configurations().get(2);
        var first = LearnedSchedulingStudy.runCase(report.model(), example, configuration, 2048);
        var second = LearnedSchedulingStudy.runCase(report.model(), example, configuration, 2048);
        assertEquals(LearnedSchedulingArtifacts.resultJson(first.result()), LearnedSchedulingArtifacts.resultJson(second.result()));
        assertEquals(before, LearnedSchedulingArtifacts.modelJson(report.model()));
        assertTrue(LearnedSchedulingArtifacts.summaryJson(report).contains("\"productionQualified\":false"));
    }
    @Test void failedMacroStillChargesItsExecutedPrimitivePrefix() {
        var primitives = report.model().providers(LearnedSchedulingProtocol.configurations().getFirst());
        var square = primitives.stream().filter(provider -> provider.descriptor().id().endsWith("_square-product")).findFirst().orElseThrow();
        var cancel = primitives.stream().filter(provider -> provider.descriptor().id().endsWith("_cancel-addend")).findFirst().orElseThrow();
        var descriptor = new MoveProvider.Descriptor("failed-prefix", "test", SearchMove.SourceKind.LEARNED,
            SearchMove.ProofStrength.REPLAYABLE, List.of(), SearchMove.ValueEvidence.UNKNOWN, "fixture");
        var provider = new PrimitiveSequenceMoveProvider(descriptor, List.of(square, cancel));
        var batch = provider.candidates(MoveState.root("x*x"), MoveContext.frozen("unused"));
        assertTrue(batch.moves().isEmpty()); assertTrue(batch.work().candidateWork().primitiveRewrites() > 0);
        var result = new MoveSearch().search(new MoveSearch.Problem("x*x", MoveContext.frozen("unused"), List.of(provider),
            MovePriorityPolicy.INVENTORY_ORDER, new PrimitiveReplayMoveVerifier(report.model().knowledge().inventory()), state -> 0,
            MoveSearch.Mode.FAST, MoveSearch.Scheduling.STAGED, new MoveSearch.Budget(9, 9, 0, 10, 1000)));
        assertEquals(batch.work().candidateWork().primitiveRewrites(), result.metrics().primitiveWork());
        assertEquals(0, result.metrics().generatedSuccessors());
    }
}
