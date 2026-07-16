package de.regelsuche.solver.portfolio;

import de.regelsuche.solver.ir.SolverIr;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/** Deterministic invocation, cost and timeout limits. */
public record PortfolioBudget(
    int maxInvocations,
    long totalCostUnits,
    long defaultTimeoutMillis,
    Map<String, BackendLimit> backendLimits
) {
    public PortfolioBudget {
        if (maxInvocations <= 0) {
            throw new IllegalArgumentException("maxInvocations must be positive");
        }
        if (totalCostUnits <= 0L) {
            throw new IllegalArgumentException("totalCostUnits must be positive");
        }
        if (defaultTimeoutMillis <= 0L) {
            throw new IllegalArgumentException("defaultTimeoutMillis must be positive");
        }
        backendLimits = backendLimits == null
            ? Map.of()
            : Collections.unmodifiableMap(new TreeMap<>(backendLimits));
    }

    public BackendLimit limitFor(BackendCapabilityProfile profile) {
        return backendLimits.getOrDefault(
            profile.backendId(),
            new BackendLimit(Long.MAX_VALUE, defaultTimeoutMillis));
    }

    public String configurationHash() {
        return SolverIr.sha256(
            "maxInvocations=" + maxInvocations
                + "\ntotalCostUnits=" + totalCostUnits
                + "\ndefaultTimeoutMillis=" + defaultTimeoutMillis
                + "\nbackendLimits=" + backendLimits);
    }

    public static PortfolioBudget standard() {
        return new PortfolioBudget(8, 500L, 20_000L, Map.of());
    }

    public record BackendLimit(long maxCostUnits, long timeoutMillis) {
        public BackendLimit {
            if (maxCostUnits <= 0L || timeoutMillis <= 0L) {
                throw new IllegalArgumentException(
                    "backend maxCostUnits and timeoutMillis must be positive");
            }
        }
    }
}
