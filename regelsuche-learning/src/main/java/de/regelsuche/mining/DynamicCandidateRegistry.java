package de.regelsuche.mining;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Isolated, quarantined registry for dynamically compiled hypothesis operators.
 *
 * <p>Lifecycle of a registered operator:</p>
 * <pre>
 *  CANDIDATE ──promote()──► VALIDATED
 *      └──block()──► BLOCKED
 *  VALIDATED ──block()──► BLOCKED
 * </pre>
 *
 * <p>Safety invariants:
 * <ul>
 *   <li>No operator can bypass normal rule preconditions: candidates remain
 *       in CANDIDATE state until explicit promotion after holdout validation.</li>
 *   <li>Only VALIDATED operators may be retrieved via
 *       {@link #validatedOperators()}; CANDIDATE and BLOCKED operators are
 *       not globally active.</li>
 *   <li>Promotion requires that the hypothesis passed all positive-holdout and
 *       negative-holdout checks (caller responsibility; the registry records the
 *       decision but does not re-run validation).</li>
 *   <li>Blocking is permanent: a BLOCKED operator cannot be unblocked.</li>
 *   <li>Every state transition is recorded in an immutable history log.</li>
 * </ul>
 * </p>
 *
 * <p>Duplicate registrations (same rule ID) are rejected with an
 * {@link IllegalStateException} to prevent accidental shadowing of existing
 * operators.</p>
 */
public final class DynamicCandidateRegistry {

    /** Lifecycle state of a registered operator. */
    public enum CandidateStatus {
        /**
         * Compiled and registered, but not yet validated on holdouts.
         * Not eligible for global activation.
         */
        CANDIDATE,
        /**
         * Passed positive-holdout and negative-holdout checks.
         * Eligible for global activation via {@link #validatedOperators()}.
         */
        VALIDATED,
        /**
         * Blocked due to a counterexample, failed assumption, or explicit block call.
         * Permanently ineligible for activation.
         */
        BLOCKED
    }

    /** An immutable snapshot of a registered operator and its current status. */
    public record RegistryEntry(
        DynamicPatternOperator operator,
        CandidateStatus status,
        String blockReason,
        Instant registeredAt,
        Instant lastTransitionAt
    ) {
        public RegistryEntry {
            if (operator == null) {
                throw new IllegalArgumentException("operator must not be null");
            }
            if (status == null) {
                throw new IllegalArgumentException("status must not be null");
            }
            registeredAt = registeredAt == null ? Instant.now() : registeredAt;
            lastTransitionAt = lastTransitionAt == null ? registeredAt : lastTransitionAt;
            blockReason = blockReason == null ? "" : blockReason;
        }
    }

    /** An entry in the immutable audit log of state transitions. */
    public record TransitionRecord(
        String ruleId,
        CandidateStatus fromStatus,
        CandidateStatus toStatus,
        String reason,
        Instant timestamp
    ) {}

    private final Map<String, RegistryEntry> entries = new LinkedHashMap<>();
    private final List<TransitionRecord> history = new ArrayList<>();

    /**
     * Registers a compiled {@link DynamicPatternOperator} in CANDIDATE state.
     *
     * @param operator the compiled operator to register
     * @throws IllegalStateException if an operator with the same rule ID is already registered
     */
    public synchronized void register(DynamicPatternOperator operator) {
        if (operator == null) {
            throw new IllegalArgumentException("operator must not be null");
        }
        String ruleId = operator.ruleId();
        if (entries.containsKey(ruleId)) {
            throw new IllegalStateException(
                "Operator with rule ID '" + ruleId + "' is already registered; "
                + "duplicate registrations are not allowed to prevent shadowing");
        }
        Instant now = Instant.now();
        entries.put(ruleId, new RegistryEntry(operator, CandidateStatus.CANDIDATE, null, now, now));
        history.add(new TransitionRecord(ruleId, null, CandidateStatus.CANDIDATE, "registered", now));
    }

    /**
     * Promotes a CANDIDATE operator to VALIDATED state after successful holdout checks.
     *
     * @param ruleId the rule ID of the operator to promote
     * @throws IllegalArgumentException if no operator with {@code ruleId} is registered
     * @throws IllegalStateException    if the operator is not in CANDIDATE state
     *                                  (i.e. already VALIDATED or BLOCKED)
     */
    public synchronized void promote(String ruleId) {
        RegistryEntry entry = requireEntry(ruleId);
        if (entry.status() != CandidateStatus.CANDIDATE) {
            throw new IllegalStateException(
                "Cannot promote operator '" + ruleId + "': current status is " + entry.status());
        }
        Instant now = Instant.now();
        entries.put(ruleId, new RegistryEntry(
            entry.operator(), CandidateStatus.VALIDATED, null, entry.registeredAt(), now));
        history.add(new TransitionRecord(ruleId, CandidateStatus.CANDIDATE, CandidateStatus.VALIDATED, "holdout-validated", now));
    }

    /**
     * Blocks a CANDIDATE or VALIDATED operator permanently due to a counterexample,
     * failed assumption, or explicit rejection.
     *
     * @param ruleId the rule ID of the operator to block
     * @param reason human-readable explanation for the block
     * @throws IllegalArgumentException if no operator with {@code ruleId} is registered
     * @throws IllegalStateException    if the operator is already BLOCKED
     */
    public synchronized void block(String ruleId, String reason) {
        RegistryEntry entry = requireEntry(ruleId);
        if (entry.status() == CandidateStatus.BLOCKED) {
            throw new IllegalStateException("Operator '" + ruleId + "' is already BLOCKED");
        }
        CandidateStatus previous = entry.status();
        Instant now = Instant.now();
        entries.put(ruleId, new RegistryEntry(
            entry.operator(), CandidateStatus.BLOCKED,
            reason == null ? "" : reason,
            entry.registeredAt(), now));
        history.add(new TransitionRecord(ruleId, previous, CandidateStatus.BLOCKED,
            reason == null ? "" : reason, now));
    }

    /**
     * Returns the registry entry for the given rule ID, or empty if not registered.
     */
    public synchronized Optional<RegistryEntry> entry(String ruleId) {
        return Optional.ofNullable(entries.get(ruleId));
    }

    /**
     * Returns the compiled operator for the given rule ID regardless of its status,
     * or empty if not registered.
     */
    public synchronized Optional<DynamicPatternOperator> operator(String ruleId) {
        return Optional.ofNullable(entries.get(ruleId)).map(RegistryEntry::operator);
    }

    /**
     * Returns all operators currently in CANDIDATE state (not yet validated).
     * These operators are quarantined and not globally active.
     */
    public synchronized List<DynamicPatternOperator> candidateOperators() {
        return entries.values().stream()
            .filter(e -> e.status() == CandidateStatus.CANDIDATE)
            .map(RegistryEntry::operator)
            .toList();
    }

    /**
     * Returns all operators that have been promoted to VALIDATED state.
     * These are the only operators eligible for global activation.
     */
    public synchronized List<DynamicPatternOperator> validatedOperators() {
        return entries.values().stream()
            .filter(e -> e.status() == CandidateStatus.VALIDATED)
            .map(RegistryEntry::operator)
            .toList();
    }

    /**
     * Returns all entries across all lifecycle states for audit and reporting.
     */
    public synchronized List<RegistryEntry> allEntries() {
        return List.copyOf(entries.values());
    }

    /**
     * Returns an immutable snapshot of the state-transition history log.
     */
    public synchronized List<TransitionRecord> history() {
        return Collections.unmodifiableList(new ArrayList<>(history));
    }

    /**
     * Returns {@code true} if an operator with this rule ID is registered in any state.
     */
    public synchronized boolean isRegistered(String ruleId) {
        return entries.containsKey(ruleId);
    }

    private RegistryEntry requireEntry(String ruleId) {
        RegistryEntry entry = entries.get(ruleId);
        if (entry == null) {
            throw new IllegalArgumentException("No operator registered with rule ID '" + ruleId + "'");
        }
        return entry;
    }
}
