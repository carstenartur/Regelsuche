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

/** Versioned execution admission, never mathematical authority. Native v1 records remain frozen. */
public final class IncrementalProviderContract {
    private IncrementalProviderContract() {}
    public static final String REVISION = "regelsuche.incremental-provider/v2";
    public enum Kind { NATIVE_RULES, REGISTERED_SCHEMA, TYPED_PRIMITIVE_BATCH }
    public enum Transport { PARSER_TEXT, TYPED_AST_JSON }
    public enum Mathematics { PRIMITIVE, EXACT, MIXED }
    public enum Status { OPEN, READY, EXHAUSTED, LIMIT, INCONCLUSIVE, FAILED, CLOSED }
    public enum Operation { OPEN, ADMISSION, PULL, MATCH, LOAD, ABORT, CLOSE }

    public record Definition(String revision, String providerId, Kind kind, String modelRevision,
            String semanticsRevision, Transport transport, Mathematics mathematics,
            TransformationCursor.Definition nativeDefinition) {
        public Definition {
            if (!REVISION.equals(revision)) throw new IllegalArgumentException("unsupported provider revision");
            for (var text : List.of(providerId, modelRevision, semanticsRevision))
                if (text.isBlank()) throw new IllegalArgumentException("blank provider binding");
            Objects.requireNonNull(kind); Objects.requireNonNull(transport); Objects.requireNonNull(mathematics);
            if ((kind == Kind.NATIVE_RULES) != (nativeDefinition != null))
                throw new IllegalArgumentException("only native providers carry native rule definitions");
        }
    }

    /** Actual cumulative mechanics and mathematics, including failed or unconsumed applications. */
    public record Work(Map<String, Long> operations, ExecutionWork mathematics) {
        public Work {
            operations = java.util.Collections.unmodifiableMap(new TreeMap<>(operations));
            Objects.requireNonNull(mathematics);
            operations.forEach((name, units) -> {
                Operation.valueOf(name);
                if (units < 0) throw new IllegalArgumentException("negative incremental work");
            });
        }
        public long units(Operation operation) { return operations.getOrDefault(operation.name(), 0L); }
        public TransformationWorkMetrics metrics() {
            long mechanical = operations.values().stream().reduce(0L, Math::addExact);
            return TransformationWorkMetrics.ZERO.withDelegatedMechanicalWork(mechanical).withCandidateWork(mathematics);
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
        public void charge(Operation operation, long units) {
            if (units < 0) throw new IllegalArgumentException("negative incremental work");
            operations.merge(Objects.requireNonNull(operation).name(), units, Math::addExact);
        }
        public void charge(ExecutionWork work) { mathematics = mathematics.plus(Objects.requireNonNull(work)); }
        public Work work() { return new Work(operations, mathematics); }
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
