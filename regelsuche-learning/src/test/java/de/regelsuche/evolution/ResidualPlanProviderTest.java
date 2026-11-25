package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.search.moves.*;
import de.regelsuche.search.moves.IncrementalProviderContract.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ResidualPlanProviderTest {
    private static final MoveState STATE = MoveState.root("(a^2+b^2)*(c^2+d^2)");
    private static final MoveContext CONTEXT = MoveContext.frozen("unused");
    private static final int LIMIT = 100_000;

    @Test void competingPairsShareOnePreparationAndEveryReplayIsBoundAndPaid() {
        var plans = new ResidualPlanProvider(STATE, CONTEXT, ResidualPlanController.Mode.DISCOVERY, 7, LIMIT, LIMIT);
        var nativeRun = new ResidualPlanController(STATE.expression()).run(ResidualPlanController.Arm.LEARNED, 7, 0);
        try (var cursor = plans.provider().openSession(STATE, CONTEXT)) {
            assertTrue(cursor.next(0).isEmpty());
            assertEquals(0, cursor.snapshot().work().metrics().totalWorkUnitsV2());
            var actual = new HashSet<String>();
            while (!cursor.snapshot().complete()) {
                var step = cursor.next(8);
                assertNotEquals(Status.FAILED, cursor.snapshot().status(), cursor.snapshot().detailCode());
                if (step.isEmpty()) continue;
                var move = SearchMove.from(step.get(), plans.provider().descriptor(), 0);
                actual.add(move.targetExpression());
                assertTrue(plans.verify(STATE, move, CONTEXT).accepted());
                assertFalse(plans.verify(STATE, move, new MoveContext(CONTEXT.goal(), List.of("a>0"), CONTEXT.phase())).accepted());
                var forged = plans.verify(STATE, move.withCapabilityDelta(Set.of("unproved")), CONTEXT);
                assertFalse(forged.accepted());
                assertTrue(forged.work() > 1);
                assertFalse(plans.verify(MoveState.root(" " + STATE.expression()), move, CONTEXT).accepted());
                var changed = new MoveState(STATE.expression(), 0, 0, "", List.of(), Set.of("other"), 0);
                assertFalse(plans.verify(changed, move, CONTEXT).accepted());
            }
            var work = cursor.snapshot().work();
            assertTrue(actual.size() >= 2);
            assertEquals(new HashSet<>(nativeRun.candidates().stream().map(ResidualPlanController.Candidate::expression).toList()), actual);
            assertEquals(nativeRun.prepareWork(), work.units(Operation.LOAD));
            assertEquals(nativeRun.work() - nativeRun.prepareWork(), work.units(Operation.MATCH));
            assertEquals(actual.size(), work.mathematics().exactTheorySteps());
            assertEquals(work.mathematics(), work.prepaidApplications().completedMathematics());
        }
    }

    @Test void normalStagedFrontierReconstructsTheRequestedTargetFromItsExactSource() {
        var target = ResidualPlanCompiler.bind(STATE.expression(),List.of("plus-0-3","reflected-1-2"),LIMIT);
        var result = ResidualPlanController.search(STATE.expression(),MoveContext.frozen(target.expression()),
            ResidualPlanController.Mode.DISCOVERY,7,LIMIT,LIMIT,new MoveSearch.Budget(0,1,LIMIT,16,1_000_000));
        assertTrue(result.reached(),result.outcome().name());
        var witness = result.witness().getFirst();
        assertEquals(List.of(target.proofRoot()),witness.verification().receipts());
        assertEquals(target.checkWork()+1,witness.verification().work());
        assertTrue(result.accountingComplete());
    }

    @Test void aPaidIdentityAlternativeDoesNotHideTheOtherCheckedTarget() {
        var targets = new ResidualPlanController(STATE.expression()).run(ResidualPlanController.Arm.LEARNED,7,0)
            .candidates().stream().map(ResidualPlanController.Candidate::expression).toList();
        assertEquals(2,targets.size());
        for (int i = 0; i < targets.size(); i++) {
            var source = MoveState.root(targets.get(i));
            var context = MoveContext.frozen(targets.get(1-i));
            var result = ResidualPlanController.search(source.expression(),context,ResidualPlanController.Mode.DISCOVERY,
                7,LIMIT,LIMIT,new MoveSearch.Budget(0,1,LIMIT,16,1_000_000));
            assertTrue(result.reached(),result.outcome().name());
            assertTrue(result.accountingComplete());
            var plans = new ResidualPlanProvider(source,context,ResidualPlanController.Mode.DISCOVERY,7,LIMIT,LIMIT);
            try (var cursor = plans.provider().openSession(source,context)) {
                var emitted = new ArrayList<String>();
                while (!cursor.snapshot().complete()) {
                    cursor.next(LIMIT).ifPresent(move -> emitted.add(move.transformedExpression()));
                    assertTrue(cursor.snapshot().accountingComplete(),cursor.snapshot().detailCode());
                    assertNotEquals(Status.INCONCLUSIVE,cursor.snapshot().status());
                }
                assertEquals(List.of(context.goal()),emitted);
                var receipt = cursor.snapshot().work().prepaidApplications();
                assertEquals(0,receipt.openApplications());
                assertEquals(1,receipt.abandonedApplications());
                assertTrue(receipt.chargedUnits() > receipt.completedMathematics().exactTheoryWorkUnits());
            }
        }
    }

    @Test void canceledMidReplaySettlesEveryPaidPhaseAndReleasesTheOpenTicket() {
        var plans = new ResidualPlanProvider(STATE,CONTEXT,ResidualPlanController.Mode.DISCOVERY,7,LIMIT,LIMIT);
        var cursor = plans.provider().openSession(STATE,CONTEXT);
        while (cursor.snapshot().work().prepaidApplications().openApplications() == 0) {
            assertTrue(cursor.next(16).isEmpty());
            assertTrue(Set.of(Status.READY,Status.LIMIT).contains(cursor.snapshot().status()));
        }
        var open = cursor.snapshot().work().prepaidApplications();
        assertTrue(open.chargedUnits() > 0);
        cursor.close();
        var closed = cursor.snapshot().work().prepaidApplications();
        assertEquals(0,closed.openApplications());
        assertEquals(1,closed.abandonedApplications());
        assertEquals(open.chargedUnits(),closed.chargedUnits());
        assertEquals(0,cursor.snapshot().emittedCandidates());
    }

    @Test void canceledAndInsufficientReplaysLeavePaidWorkWithoutAnEdge() {
        var plans = new ResidualPlanProvider(STATE, CONTEXT, ResidualPlanController.Mode.DISCOVERY, 7, LIMIT, 100);
        var first = plans.provider().openSession(STATE, CONTEXT);
        assertTrue(first.next(4).isEmpty());
        assertTrue(first.snapshot().work().units(Operation.LOAD)>0);
        first.close();
        assertTrue(first.next(LIMIT).isEmpty());
        assertTrue(first.snapshot().closed());
        try (var cursor = plans.provider().openSession(STATE, CONTEXT)) {
            while (!cursor.snapshot().complete() && cursor.snapshot().status() != Status.INCONCLUSIVE) {
                assertTrue(cursor.next(LIMIT).isEmpty());
                assertNotEquals(Status.FAILED, cursor.snapshot().status(), cursor.snapshot().detailCode());
            }
            assertEquals(Status.INCONCLUSIVE,cursor.snapshot().status());
            assertTrue(cursor.snapshot().work().prepaidApplications().chargedUnits()>0);
            assertEquals(2,cursor.snapshot().work().prepaidApplications().abandonedApplications());
        }
    }
}
