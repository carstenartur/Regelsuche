package de.regelsuche.transform;

/**
 * Backwards-compatible app-level adapter name.
 *
 * <p>The GraalVM/SymPy implementation lives in
 * {@code de.regelsuche.app.transform} so the core transformation package does
 * not own infrastructure imports.</p>
 */
@Deprecated(forRemoval = false)
public final class SymPyTransformationEngine extends de.regelsuche.app.transform.SymPyTransformationEngine {
}
