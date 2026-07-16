package de.regelsuche.solver.portfolio;

/** Runtime availability is telemetry and is deliberately excluded from cache identity. */
public enum BackendAvailability {
    AVAILABLE,
    UNAVAILABLE,
    DISABLED
}
