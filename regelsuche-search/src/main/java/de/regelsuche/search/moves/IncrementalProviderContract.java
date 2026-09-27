package de.regelsuche.search.moves;

import de.regelsuche.transform.ExecutionWork;
import de.regelsuche.transform.Transformation;
import de.regelsuche.transform.TransformationCursor;
import de.regelsuche.transform.TransformationWorkMetrics;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.HashSet;
import java.util.Set;

/** Versioned execution admission, never mathematical authority. Native v1 records remain frozen. */
public final class IncrementalProviderContract {
    private IncrementalProviderContract() {}
    public static final String REVISION = "regelsuche.incremental-provider/v2";
    public static final String PREPAID_REVISION = "regelsuche.incremental-provider/v3-prepaid";
    public static final String PREPAID_WORK_REVISION = "regelsuche.prepaid-application-work/v1";
    public enum ApplicationPhase { SUBSTITUTION_DOMAIN, INSTANTIATION, TARGET_DOMAIN, EVIDENCE }
    public enum Kind { NATIVE_RULES, REGISTERED_SCHEMA, TYPED_PRIMITIVE_BATCH }
    public enum Transport { PARSER_TEXT, TYPED_AST_JSON }
    public enum Mathematics { PRIMITIVE, EXACT, MIXED }
    public enum Status { OPEN, READY, EXHAUSTED, LIMIT, INCONCLUSIVE, FAILED, CLOSED }
    public enum Operation { OPEN, ADMISSION, PULL, MATCH, LOAD, ABORT, CLOSE }

    public record Definition(String revision, String providerId, Kind kind, String modelRevision,
            String semanticsRevision, Transport transport, Mathematics mathematics,
            TransformationCursor.Definition nativeDefinition) {
        public Definition {
            if (!REVISION.equals(revision) && !PREPAID_REVISION.equals(revision)) throw new IllegalArgumentException("unsupported provider revision");
            for (var text : List.of(providerId, modelRevision, semanticsRevision))
                if (text.isBlank()) throw new IllegalArgumentException("blank provider binding");
            Objects.requireNonNull(kind); Objects.requireNonNull(transport); Objects.requireNonNull(mathematics);
            if ((kind == Kind.NATIVE_RULES) != (nativeDefinition != null))
                throw new IllegalArgumentException("only native providers carry native rule definitions");
            if (PREPAID_REVISION.equals(revision) && (kind != Kind.REGISTERED_SCHEMA || mathematics == Mathematics.PRIMITIVE))
                throw new IllegalArgumentException("prepaid applications require registered exact mathematics");
        }
    }

    /** Observational settlement metadata. Every charged unit stays paid after completion or abandonment. */
    public record PrepaidApplications(String revision, long chargedUnits, Map<String, Long> phaseCalls, Map<String, Long> phaseWork,
            ExecutionWork completedMathematics, long openApplications, long abandonedApplications) {
        public PrepaidApplications {
            if (!PREPAID_WORK_REVISION.equals(revision) || chargedUnits < 0 || openApplications < 0 || abandonedApplications < 0)
                throw new IllegalArgumentException("invalid prepaid application receipt");
            phaseCalls = java.util.Collections.unmodifiableMap(new TreeMap<>(phaseCalls));
            phaseCalls.forEach((phase, calls) -> {
                ApplicationPhase.valueOf(phase);
                if (calls < 0) throw new IllegalArgumentException("negative prepaid phase count");
            });
            phaseWork = java.util.Collections.unmodifiableMap(new TreeMap<>(phaseWork));
            if (!phaseWork.keySet().equals(phaseCalls.keySet()) || phaseWork.values().stream().anyMatch(units -> units < 0)
                    || phaseWork.values().stream().reduce(0L, Math::addExact) != chargedUnits)
                throw new IllegalArgumentException("prepaid phase breakdown differs from charged work");
            Objects.requireNonNull(completedMathematics);
            if (completedMathematics.primitiveRewrites() != 0 || completedMathematics.exactTheoryWorkUnits() > chargedUnits)
                throw new IllegalArgumentException("completed application lacks prepayment");
        }
        long paidUnits() { return Math.addExact(chargedUnits, phaseCalls.values().stream().reduce(0L, Math::addExact)); }
    }
    /**
     * Cumulative full mathematics is diagnostic. Use metrics() for paid work: prepaid units and phase events
     * are already billed mechanically and their completed mathematics must not be added a second time.
     */
    public record Work(Map<String, Long> operations, ExecutionWork mathematics,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            PrepaidApplications prepaidApplications) {
        /** Frozen v2 shape/formula; serializers omit the absent prepaid extension. */
        public Work(Map<String, Long> operations, ExecutionWork mathematics) { this(operations, mathematics, null); }
        public Work {
            operations = java.util.Collections.unmodifiableMap(new TreeMap<>(operations));
            Objects.requireNonNull(mathematics);
            operations.forEach((name, units) -> {
                Operation.valueOf(name);
                if (units < 0) throw new IllegalArgumentException("negative incremental work");
            });
            if (prepaidApplications != null) unpaidMathematics(mathematics, prepaidApplications);
        }
        public long units(Operation operation) { return operations.getOrDefault(operation.name(), 0L); }
        public TransformationWorkMetrics metrics() {
            long mechanical = operations.values().stream().reduce(0L, Math::addExact);
            var billable = mathematics;
            if (prepaidApplications != null) {
                mechanical = Math.addExact(mechanical, prepaidApplications.paidUnits());
                billable = unpaidMathematics(mathematics, prepaidApplications);
            }
            return TransformationWorkMetrics.ZERO.withDelegatedMechanicalWork(mechanical).withCandidateWork(billable);
        }
        private static ExecutionWork unpaidMathematics(ExecutionWork full, PrepaidApplications prepaid) {
            var settled = prepaid.completedMathematics();
            return new ExecutionWork(full.primitiveRewrites(),
                Math.subtractExact(full.exactTheorySteps(), settled.exactTheorySteps()),
                Math.subtractExact(full.exactTheoryWorkUnits(), settled.exactTheoryWorkUnits()));
        }
    }
    public record Snapshot(Definition definition, Status status, boolean closed, boolean resumable,
            boolean accountingComplete, Work work, long emittedCandidates, String detailCode) {
        public boolean complete() { return status == Status.EXHAUSTED && accountingComplete; }
    }
    public interface Cursor extends AutoCloseable {
        /** One candidate at most. LIMIT is resumable on the same cursor; all atomic overruns remain paid. */
        Optional<Transformation> next(long allowance);
        Snapshot snapshot();
        @Override void close();
    }
    /** A registered implementation must charge delegated work before returning OR throwing. */
    public interface Source extends AutoCloseable {
        Optional<Transformation> next(long allowance);
        Status status();
        @Override default void close() {}
    }
    public static final class Meter {
        private final Map<String, Long> operations = new TreeMap<>();
        private ExecutionWork mathematics = ExecutionWork.ZERO;
        private final boolean prepaidSupported;
        private final Set<PrepaidApplication> pending = new HashSet<>();
        private final Map<String, Long> phaseCalls = new TreeMap<>();
        private final Map<String, Long> phaseWork = new TreeMap<>();
        private long prepaidUnits, abandoned;
        private ExecutionWork prepaidCompleted = ExecutionWork.ZERO;
        public Meter() { this(REVISION); }
        public Meter(String revision) {
            if (!REVISION.equals(revision) && !PREPAID_REVISION.equals(revision)) throw new IllegalArgumentException("unsupported meter revision");
            prepaidSupported = PREPAID_REVISION.equals(revision);
        }
        public PrepaidApplication beginPrepaidApplication() {
            if (!prepaidSupported) throw new IllegalStateException("prepaid applications require provider v3");
            var application = new PrepaidApplication(this);
            pending.add(application);
            return application;
        }
        /** Charge after each actually executed bounded phase, also in failure paths. One phase event is one additional unit. */
        public void prepay(PrepaidApplication application, ApplicationPhase phase, long units) {
            requirePending(application);
            if (units < 0) throw new IllegalArgumentException("negative application work");
            Objects.requireNonNull(phase);
            long nextUnits = Math.addExact(prepaidUnits, units);
            long ticketUnits = Math.addExact(application.units, units);
            long calls = Math.addExact(phaseCalls.getOrDefault(phase.name(), 0L), 1);
            // Calculate every fallible arithmetic operation before changing either the account or ticket.
            var nextCalls = new TreeMap<>(phaseCalls); nextCalls.put(phase.name(), calls);
            var nextWork = new TreeMap<>(phaseWork);
            nextWork.merge(phase.name(), units, Math::addExact);
            var next = new PrepaidApplications(PREPAID_WORK_REVISION, nextUnits, nextCalls, nextWork, prepaidCompleted, pending.size(), abandoned);
            new Work(operations, mathematics, next).metrics().totalWorkUnitsV2();
            prepaidUnits = nextUnits; application.units = ticketUnits; phaseCalls.put(phase.name(), calls);
            phaseWork.put(phase.name(), nextWork.get(phase.name()));
        }
        /** Reconcile one completed candidate's exact receipt, without removing or recharging its prepayment. */
        public void complete(PrepaidApplication application, ExecutionWork work) {
            requirePending(application); Objects.requireNonNull(work);
            if (work.primitiveRewrites() != 0 || work.exactTheorySteps() != 1 || work.exactTheoryWorkUnits() != application.units)
                throw new IllegalArgumentException("completed candidate differs from its exact prepayment");
            var nextMathematics = mathematics.plus(work);
            var nextCompleted = prepaidCompleted.plus(work);
            mathematics = nextMathematics; prepaidCompleted = nextCompleted;
            pending.remove(application); application.finished = true;
        }
        public void abandon(PrepaidApplication application) {
            requirePending(application);
            long nextAbandoned = Math.addExact(abandoned, 1);
            pending.remove(application); application.finished = true; abandoned = nextAbandoned;
        }
        private void requirePending(PrepaidApplication application) {
            if (application == null || application.owner != this) throw new IllegalArgumentException("foreign application payment ticket");
            if (application.finished) throw new IllegalStateException("application payment already settled");
        }
        void abandonPending() { for (var application : List.copyOf(pending)) abandon(application); }
        public void charge(Operation operation, long units) {
            if (units < 0) throw new IllegalArgumentException("negative incremental work");
            if (prepaidSupported) {
                var next = new TreeMap<>(operations);
                next.merge(Objects.requireNonNull(operation).name(), units, Math::addExact);
                new Work(next, mathematics, prepaidReceipt()).metrics().totalWorkUnitsV2();
            }
            operations.merge(Objects.requireNonNull(operation).name(), units, Math::addExact);
        }
        public void charge(ExecutionWork work) {
            var next = mathematics.plus(Objects.requireNonNull(work));
            if (prepaidSupported) new Work(operations, next, prepaidReceipt()).metrics().totalWorkUnitsV2();
            mathematics = next;
        }
        private PrepaidApplications prepaidReceipt() {
            return new PrepaidApplications(PREPAID_WORK_REVISION, prepaidUnits, phaseCalls, phaseWork, prepaidCompleted, pending.size(), abandoned);
        }
        public Work work() {
            return new Work(operations, mathematics, prepaidSupported ? prepaidReceipt() : null);
        }
    }
    /** Opaque meter-owned payment handle; never a mathematical capability. */
    public static final class PrepaidApplication {
        private final Meter owner;
        private long units;
        private boolean finished;
        private PrepaidApplication(Meter owner) { this.owner = owner; }
    }
    @FunctionalInterface public interface Factory {
        Source open(MoveState state, MoveContext context, Meter meter);
    }
    public record Registration(Definition definition, Factory factory) {
        public Registration {
            Objects.requireNonNull(definition); Objects.requireNonNull(factory);
            if (definition.kind() != Kind.REGISTERED_SCHEMA) throw new IllegalArgumentException("registration requires schema kind");
        }
    }
    /** Immutable explicit allowlist. Matching a registration binds code/configuration, not a proof. */
    public static final class Registry {
        private final Map<String, Registration> registrations;
        public Registry(List<Registration> registrations) {
            var entries = new TreeMap<String, Registration>();
            for (var entry : List.copyOf(registrations)) {
                if (entries.putIfAbsent(entry.definition().providerId(), entry) != null)
                    throw new IllegalArgumentException("duplicate provider registration");
            }
            this.registrations = Map.copyOf(entries);
        }
        Factory require(Definition definition) {
            var entry = registrations.get(definition.providerId());
            if (entry == null || !entry.definition().equals(definition))
                throw new IllegalArgumentException("provider model/semantics/transport is not explicitly registered");
            return entry.factory();
        }
    }
}
