package de.regelsuche.search.moves;

import static de.regelsuche.search.moves.IncrementalProviderContract.*;
import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.transform.ExecutionWork;
import de.regelsuche.transform.Transformation;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PrepaidApplicationWorkTest {
    @Test void delegatedFailureAndCloseKeepPartialPaymentsAndNeverAuthorizeAnEmission() {
        var old = IncrementalProviderContractTest.definition("prepaid-test", Transport.PARSER_TEXT);
        var definition = new Definition(PREPAID_REVISION, old.providerId(), old.kind(), old.modelRevision(),
            old.semanticsRevision(), old.transport(), old.mathematics(), null);
        var registry = new Registry(List.of(new Registration(definition, (state, context, meter) -> new Source() {
            @Override public Optional<Transformation> next(long allowance) {
                var ticket = meter.beginPrepaidApplication();
                meter.prepay(ticket, ApplicationPhase.SUBSTITUTION_DOMAIN, 11);
                throw new IllegalStateException("failure after observable partial work");
            }
            @Override public Status status() { return Status.READY; }
        })));
        var cursor = new RegisteredIncrementalMoveProvider(IncrementalProviderContractTest.descriptor(), definition, registry)
            .openSession(MoveState.root("a + 0"), IncrementalProviderContractTest.CONTEXT);
        assertTrue(cursor.next(1000).isEmpty());
        assertEquals(Status.FAILED, cursor.snapshot().status()); assertFalse(cursor.snapshot().accountingComplete());
        assertEquals(ExecutionWork.ZERO, cursor.snapshot().work().mathematics());
        assertEquals(16, cursor.snapshot().work().metrics().totalWorkUnitsV2(), "admission/open/pull/abort4 + prepaid11 + phase1");
        cursor.close(); assertEquals(17, cursor.snapshot().work().metrics().totalWorkUnitsV2());
        var closed = cursor.snapshot(); cursor.close(); assertEquals(closed, cursor.snapshot());
    }
    @Test void actualPartialWorkIsPaidImmediatelyAndCompletedMathematicsIsNeverPaidTwice() {
        var meter = new Meter(PREPAID_REVISION);
        meter.charge(Operation.LOAD, 3);
        meter.charge(new ExecutionWork(2, 1, 7));
        var ticket = meter.beginPrepaidApplication();
        meter.prepay(ticket, ApplicationPhase.SUBSTITUTION_DOMAIN, 5);
        assertEquals(new ExecutionWork(2, 1, 7), meter.work().mathematics(), "partial payment is no completed theorem");
        assertEquals(18, meter.work().metrics().totalWorkUnitsV2(), "load3 + ordinary math9 + partial5 + phase event1");
        meter.prepay(ticket, ApplicationPhase.INSTANTIATION, 4);
        meter.prepay(ticket, ApplicationPhase.TARGET_DOMAIN, 6);
        meter.prepay(ticket, ApplicationPhase.EVIDENCE, 0);
        long prepaid = meter.work().metrics().totalWorkUnitsV2();
        meter.complete(ticket, new ExecutionWork(0, 1, 15));
        assertEquals(prepaid, meter.work().metrics().totalWorkUnitsV2());
        assertEquals(new ExecutionWork(2, 2, 22), meter.work().mathematics());
        assertEquals(new ExecutionWork(2, 1, 7), meter.work().metrics().candidateWork(), "only nonprepaid mathematics is billed here");
        meter.charge(new ExecutionWork(1, 1, 4));
        assertEquals(prepaid + 5, meter.work().metrics().totalWorkUnitsV2());
    }

    @Test void invalidSettlementWrongOwnerAndDuplicateFinishNeverPartlyMutateTheAccount() {
        var meter = new Meter(PREPAID_REVISION);
        var other = new Meter(PREPAID_REVISION);
        var ticket = meter.beginPrepaidApplication();
        meter.prepay(ticket, ApplicationPhase.INSTANTIATION, 9);
        var paid = meter.work();
        assertThrows(IllegalArgumentException.class, () -> meter.complete(ticket, new ExecutionWork(0, 1, 10)));
        assertThrows(IllegalArgumentException.class, () -> meter.complete(ticket, new ExecutionWork(1, 1, 9)));
        assertThrows(IllegalArgumentException.class, () -> meter.complete(ticket, new ExecutionWork(0, 2, 9)));
        assertThrows(IllegalArgumentException.class, () -> other.prepay(ticket, ApplicationPhase.TARGET_DOMAIN, 1));
        assertThrows(IllegalArgumentException.class, () -> other.complete(ticket, new ExecutionWork(0, 1, 9)));
        assertThrows(IllegalArgumentException.class, () -> other.abandon(ticket));
        assertThrows(IllegalArgumentException.class, () -> meter.prepay(ticket, ApplicationPhase.TARGET_DOMAIN, -1));
        assertEquals(paid, meter.work());
        meter.complete(ticket, new ExecutionWork(0, 1, 9));
        var settled = meter.work();
        assertThrows(IllegalStateException.class, () -> meter.complete(ticket, new ExecutionWork(0, 1, 9)));
        assertThrows(IllegalStateException.class, () -> meter.prepay(ticket, ApplicationPhase.EVIDENCE, 1));
        assertThrows(IllegalStateException.class, () -> meter.abandon(ticket));
        assertEquals(settled, meter.work());
        assertThrows(IllegalStateException.class, () -> new Meter().beginPrepaidApplication());
    }

    @Test void failedAndAbandonedApplicationsRetainAllWorkWithoutMathematicalAuthority() {
        var meter = new Meter(PREPAID_REVISION);
        var first = meter.beginPrepaidApplication();
        meter.prepay(first, ApplicationPhase.TARGET_DOMAIN, 512);
        meter.abandon(first);
        assertEquals(513, meter.work().metrics().totalWorkUnitsV2());
        assertEquals(ExecutionWork.ZERO, meter.work().mathematics());
        var next = meter.beginPrepaidApplication();
        meter.prepay(next, ApplicationPhase.INSTANTIATION, 3);
        meter.complete(next, new ExecutionWork(0, 1, 3));
        assertEquals(517, meter.work().metrics().totalWorkUnitsV2());
        assertEquals(new ExecutionWork(0, 1, 3), meter.work().mathematics());
    }

    @Test void overflowAndInvalidCompletionLeaveTicketsAndCumulativeTotalsUntouched() {
        var meter = new Meter(PREPAID_REVISION);
        var ticket = meter.beginPrepaidApplication();
        meter.prepay(ticket, ApplicationPhase.SUBSTITUTION_DOMAIN, 1);
        var before = meter.work();
        assertThrows(ArithmeticException.class, () -> meter.prepay(ticket, ApplicationPhase.INSTANTIATION, Long.MAX_VALUE));
        assertEquals(before, meter.work());
        assertThrows(ArithmeticException.class, () -> meter.charge(new ExecutionWork(0, Long.MAX_VALUE, Long.MAX_VALUE)));
        assertEquals(before, meter.work());
        meter.abandon(ticket);
        assertEquals(before.mathematics(), meter.work().mathematics());
    }

    @Test void aggregatePaidTotalOverflowCannotMutateAnOtherwiseValidPrepaidAccount() {
        var meter = new Meter(PREPAID_REVISION);
        meter.charge(Operation.LOAD, Long.MAX_VALUE - 2);
        var ticket = meter.beginPrepaidApplication();
        meter.prepay(ticket, ApplicationPhase.SUBSTITUTION_DOMAIN, 1);
        assertEquals(Long.MAX_VALUE, meter.work().metrics().totalWorkUnitsV2());
        var before = meter.work();
        assertThrows(ArithmeticException.class, () -> meter.prepay(ticket, ApplicationPhase.EVIDENCE, 0));
        assertThrows(ArithmeticException.class, () -> meter.charge(Operation.PULL, 1));
        assertEquals(before, meter.work());
        meter.complete(ticket, new ExecutionWork(0, 1, 1));
        assertEquals(Long.MAX_VALUE, meter.work().metrics().totalWorkUnitsV2());
    }

    @Test void legacyWorkSerializationAndProjectionRemainExact() throws Exception {
        var meter = new Meter();
        meter.charge(Operation.MATCH, 3); meter.charge(new ExecutionWork(0, 1, 5));
        assertEquals(8, meter.work().metrics().totalWorkUnitsV2());
        assertEquals(new ExecutionWork(0, 1, 5), meter.work().metrics().candidateWork());
        var json = new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(meter.work());
        assertFalse(json.has("prepaidApplications"));
        assertEquals(2, json.size());
    }
}
