package de.regelsuche.search.moves;

import static de.regelsuche.search.moves.IncrementalProviderContract.*;
import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.retention.RetainedGraph;
import de.regelsuche.retention.RetainedOperation;
import de.regelsuche.transform.ExecutionWork;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ManagedProviderCursorProgressTest {
    private static final ExecutionWork CANDIDATE_WORK = new ExecutionWork(1, 0, 0);

    @Test void unitAllowancesAdvanceEveryTransportAndPrepaymentProfileWithoutDoubleCharging() {
        for (Definition definition : definitions()) {
            var tinySource = new Source();
            var generousSource = new Source();
            try (var tiny = new ManagedProviderCursor<>(definition, tinySource);
                    var generous = new ManagedProviderCursor<>(definition, generousSource)) {
                assertEquals(List.of("candidate"), drain(tiny, 1));
                assertEquals(List.of("candidate"), drain(generous, 100));
                assertEquals(generous.snapshot(), tiny.snapshot(), definition.revision());
                assertEquals(3, tinySource.pulls);
                assertEquals(1, tinySource.opens);
                assertEquals(1, tinySource.received);
                assertEquals(3, tiny.snapshot().work().units(Operation.PULL));
                assertEquals(21, tiny.snapshot().work().metrics().totalWorkUnitsV2());
            }
        }
    }

    @Test void zeroAndNegativeAllowancesCannotConsumeOrRechargeAnAlreadyPaidPull() {
        var source = new Source();
        try (var cursor = new ManagedProviderCursor<>(definitions().getFirst(), source)) {
            reserveFirstPull(cursor);
            var reserved = cursor.snapshot();
            assertTrue(cursor.next(0).isEmpty());
            assertThrows(IllegalArgumentException.class, () -> cursor.next(-1));
            assertEquals(reserved, cursor.snapshot());
            assertEquals(0, source.pulls);
            assertTrue(cursor.next(1).isEmpty());
            assertEquals(1, source.pulls);
            assertEquals(1, cursor.snapshot().work().units(Operation.PULL));
            assertEquals(8, cursor.snapshot().work().metrics().totalWorkUnitsV2(),
                "admission, open, one pull and five actual source units stay paid");
        }
    }

    @Test void closingAnAlreadyPaidPullDoesNotInvokeTheSourceOrRefundTheCharge() {
        var source = new Source();
        var cursor = new ManagedProviderCursor<>(definitions().getFirst(), source);
        reserveFirstPull(cursor);
        cursor.close();
        var closed = cursor.snapshot();
        assertTrue(closed.closed());
        assertEquals(Status.CLOSED, closed.status());
        assertEquals(0, source.pulls);
        assertEquals(1, source.closes);
        assertEquals(1, closed.work().units(Operation.PULL));
        assertEquals(4, closed.work().metrics().totalWorkUnitsV2());
        assertEquals(ExecutionWork.ZERO, closed.work().mathematics());
        cursor.close();
        assertTrue(cursor.next(1).isEmpty());
        assertEquals(closed, cursor.snapshot());
        assertEquals(1, source.closes);
    }

    @Test void aFailedResumedPullKeepsItsPaidPrefixAndCannotBeRetried() {
        var source = new Source();
        source.failOnPull = true;
        try (var cursor = new ManagedProviderCursor<>(definitions().getFirst(), source)) {
            reserveFirstPull(cursor);
            assertTrue(cursor.next(1).isEmpty());
            var failed = cursor.snapshot();
            assertEquals(Status.FAILED, failed.status());
            assertFalse(failed.accountingComplete());
            assertEquals(1, source.pulls);
            assertEquals(1, failed.work().units(Operation.PULL));
            assertEquals(5, failed.work().units(Operation.MATCH));
            assertEquals(1, failed.work().units(Operation.ABORT));
            assertEquals(9, failed.work().metrics().totalWorkUnitsV2());
            assertTrue(cursor.next(100).isEmpty());
            assertEquals(failed, cursor.snapshot());
        }
    }

    @Test void missingAssumptionsStillPreventOpeningOrDelegationAtUnitAllowance() {
        var source = new Source();
        source.permitted = false;
        try (var cursor = new ManagedProviderCursor<>(definitions().getFirst(), source)) {
            assertTrue(cursor.next(0).isEmpty());
            assertEquals(0, cursor.snapshot().work().metrics().totalWorkUnitsV2());
            assertTrue(cursor.next(1).isEmpty());
            assertEquals(Status.EXHAUSTED, cursor.snapshot().status());
            assertEquals("ASSUMPTIONS_NOT_CARRIED", cursor.snapshot().detailCode());
            assertEquals(0, source.opens);
            assertEquals(0, source.pulls);
            assertEquals(1, cursor.snapshot().work().metrics().totalWorkUnitsV2());
        }
    }

    @Test void nativeObservationOverrunsStayPaidWithoutStarvingAResumedUnitPull() {
        var source = new Source();
        source.nativeWork = true;
        var observed = new Observation();
        try (var operation = RetainedOperation.open(observed)) {
            long before = observed.work;
            var cursor = new ManagedProviderCursor<>(definitions().getLast(), source);
            try {
                reserveFirstPull(cursor);
                assertEquals(7, observed.work - before);
                assertEquals(0, source.pulls);
                assertEquals(List.of("candidate"), drain(cursor, 1));
                assertEquals(3, source.pulls);
                assertEquals(16, observed.work - before);
                assertEquals(3, cursor.snapshot().work().units(Operation.PULL));
                assertEquals(21, cursor.snapshot().work().metrics().totalWorkUnitsV2());
            } finally { cursor.close(); }
            assertEquals(19, observed.work - before);
            assertEquals(22, cursor.snapshot().work().metrics().totalWorkUnitsV2());
        }
    }

    private static List<String> drain(ManagedProviderCursor<String> cursor, long allowance) {
        var results = new ArrayList<String>();
        for (int attempt = 0; attempt < 12 && cursor.snapshot().status() != Status.EXHAUSTED; attempt++) {
            cursor.next(allowance).ifPresent(results::add);
            assertNotEquals(Status.FAILED, cursor.snapshot().status(), cursor.snapshot().detailCode());
        }
        assertEquals(Status.EXHAUSTED, cursor.snapshot().status(), "positive allowance must make finite progress");
        assertTrue(cursor.snapshot().accountingComplete());
        return results;
    }

    private static void reserveFirstPull(ManagedProviderCursor<String> cursor) {
        for (int phase = 0; phase < 3; phase++) assertTrue(cursor.next(1).isEmpty());
        assertEquals(Status.LIMIT, cursor.snapshot().status());
        assertEquals(1, cursor.snapshot().work().units(Operation.ADMISSION));
        assertEquals(1, cursor.snapshot().work().units(Operation.OPEN));
        assertEquals(1, cursor.snapshot().work().units(Operation.PULL));
    }

    private static List<Definition> definitions() {
        return List.of(definition(REVISION, Transport.PARSER_TEXT), definition(REVISION, Transport.TYPED_AST_JSON),
            definition(PREPAID_REVISION, Transport.TYPED_AST_JSON), definition(NATIVE_REVISION, Transport.NATIVE_EXPR_V1),
            definition(NATIVE_PREPAID_REVISION, Transport.NATIVE_EXPR_V1));
    }

    private static Definition definition(String revision, Transport transport) {
        return new Definition(revision, "progress", Kind.REGISTERED_SCHEMA, "model", "semantics", transport, Mathematics.MIXED, null);
    }

    private static final class Source implements ManagedProviderCursor.Binding<String>, ObjectSource<String> {
        private Meter meter;
        private int opens, pulls, closes;
        private long received;
        private boolean permitted = true, failOnPull, nativeWork;
        @Override public boolean carries() { return permitted; }
        @Override public ObjectSource<String> open(Meter meter) {
            this.meter = meter; opens++;
            if (nativeWork) RetainedOperation.work(7);
            return this;
        }
        @Override public Optional<String> next(long allowance) {
            assertTrue(allowance > 0);
            received = allowance; pulls++;
            meter.charge(Operation.MATCH, 5);
            if (nativeWork) RetainedOperation.work(3);
            if (failOnPull) throw new IllegalStateException("paid source failure");
            if (pulls < 3) return Optional.empty();
            meter.charge(CANDIDATE_WORK);
            return Optional.of("candidate");
        }
        @Override public Status status() { return pulls < 3 ? Status.LIMIT : Status.EXHAUSTED; }
        @Override public void requireSource(String candidate) { assertEquals("candidate", candidate); }
        @Override public ExecutionWork work(String candidate) { return CANDIDATE_WORK; }
        @Override public void close() { closes++; if (nativeWork) RetainedOperation.work(3); }
        @Override public void retainedReferences(RetainedGraph.Visitor visitor) { visitor.reference(meter); }
    }

    private static final class Observation implements RetainedOperation.Sink {
        private long work;
        @Override public void executionWork(long units) { work = Math.addExact(work, units); }
        @Override public void validationWork(long units) { work = Math.addExact(work, units); }
        @Override public long observedWork() { return work; }
        @Override public void checkpoint() { }
        @Override public void retainedReferences(RetainedGraph.Visitor visitor) { }
    }
}
