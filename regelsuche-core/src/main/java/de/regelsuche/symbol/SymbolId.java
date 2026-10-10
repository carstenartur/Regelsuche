package de.regelsuche.symbol;

import de.regelsuche.retention.RetainedOperation;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Stable symbol identity; neither a display name nor a mathematical proof. */
public record SymbolId(UUID namespace, long ordinal) {
    public static final String IDENTIFIER_PREFIX = "rsym_";
    private static final Pattern IDENTIFIER = Pattern.compile("rsym_[0-9a-f]{32}_[1-9][0-9]{0,18}");

    public SymbolId {
        Objects.requireNonNull(namespace, "namespace");
        if (ordinal < 1) {
            throw new IllegalArgumentException("symbol ordinal must be positive");
        }
    }

    public String canonicalText() {
        return namespace + ":" + ordinal;
    }

    /** Reserved identifier understood by the existing expression parser and text engines. */
    public String identifier() {
        return IDENTIFIER_PREFIX + namespace.toString().replace("-", "") + "_" + ordinal;
    }

    public static SymbolId fromCanonicalText(String text) {
        Objects.requireNonNull(text, "text");
        if (text.length() < 38 || text.length() > 56 || text.charAt(36) != ':') {
            throw new IllegalArgumentException("invalid canonical symbol ID");
        }
        if (RetainedOperation.isObserved()) return observedCanonicalText(text);
        var id = new SymbolId(UUID.fromString(text.substring(0, 36)), Long.parseLong(text.substring(37)));
        if (!id.canonicalText().equals(text)) {
            throw new IllegalArgumentException("noncanonical symbol ID");
        }
        return id;
    }

    public static SymbolId fromIdentifier(String identifier) {
        Objects.requireNonNull(identifier, "identifier");
        if (identifier.length() > 57 || !IDENTIFIER.matcher(identifier).matches()) {
            throw new IllegalArgumentException("invalid reserved symbol identifier");
        }
        if (RetainedOperation.isObserved()) return observedIdentifier(identifier);
        String hex = identifier.substring(5, 37);
        String uuid = hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-" + hex.substring(12, 16)
            + "-" + hex.substring(16, 20) + "-" + hex.substring(20);
        return fromCanonicalText(uuid + ":" + identifier.substring(38));
    }

    /** Preserve the ordinary conversion while publishing its actual completed intermediates. */
    private static SymbolId observedIdentifier(String identifier) {
        Object[] parts = new Object[10];
        var retained = RetainedOperation.retainCompleted(1, identifier, parts);
        Throwable primary = null;
        try {
            String hex = text(parts, 0, identifier.substring(5, 37));
            String first = text(parts, 1, hex.substring(0, 8));
            String second = text(parts, 2, hex.substring(8, 12));
            String third = text(parts, 3, hex.substring(12, 16));
            String fourth = text(parts, 4, hex.substring(16, 20));
            String fifth = text(parts, 5, hex.substring(20));
            String uuid = text(parts, 6, first + "-" + second + "-" + third + "-" + fourth + "-" + fifth);
            String ordinal = text(parts, 7, identifier.substring(38));
            String canonical = text(parts, 8, uuid + ":" + ordinal);
            var result = fromCanonicalText(canonical);
            parts[9] = result;
            RetainedOperation.work(1); // Handoff only; the nested conversion owns the symbol allocation.
            RetainedOperation.checkpoint();
            return result;
        } catch (RuntimeException | Error failure) {
            primary = failure; observeFailure(failure); throw failure;
        } finally {
            closeFrame(retained, primary);
        }
    }

    private static SymbolId observedCanonicalText(String canonical) {
        Object[] parts = new Object[6];
        var retained = RetainedOperation.retainCompleted(1, canonical, parts);
        Throwable primary = null;
        try {
            String uuid = text(parts, 0, canonical.substring(0, 36));
            UUID namespace = UUID.fromString(uuid);
            parts[1] = namespace;
            RetainedOperation.work(2);
            RetainedOperation.checkpoint();
            String ordinal = text(parts, 2, canonical.substring(37));
            var result = new SymbolId(namespace, Long.parseLong(ordinal));
            parts[3] = result;
            RetainedOperation.work(2);
            RetainedOperation.checkpoint();
            String namespaceText = text(parts, 4, namespace.toString());
            String normalized = text(parts, 5, namespaceText + ":" + result.ordinal());
            if (!normalized.equals(canonical)) throw new IllegalArgumentException("noncanonical symbol ID");
            return result;
        } catch (RuntimeException | Error failure) {
            primary = failure; observeFailure(failure); throw failure;
        } finally {
            closeFrame(retained, primary);
        }
    }

    private static String text(Object[] owner, int index, String value) {
        owner[index] = value;
        RetainedOperation.work(value.length() + 2L); // Completed text copy/allocation and holder write.
        RetainedOperation.checkpoint();
        return value;
    }

    private static void observeFailure(Throwable failure) {
        try { RetainedOperation.checkpoint(); }
        catch (RuntimeException | Error observation) {
            if (observation != failure) failure.addSuppressed(observation);
        }
    }

    private static void closeFrame(RetainedOperation.Frame frame, Throwable primary) {
        if (frame == null) return;
        try { frame.close(); }
        catch (RuntimeException | Error cleanup) {
            if (primary == null) throw cleanup;
            if (cleanup != primary) primary.addSuppressed(cleanup);
        }
    }
}
