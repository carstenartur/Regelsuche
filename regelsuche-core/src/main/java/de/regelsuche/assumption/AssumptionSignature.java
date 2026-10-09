package de.regelsuche.assumption;

import de.regelsuche.retention.RetainedGraph;
import de.regelsuche.retention.RetainedOperation;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Normalized, comparable signature for a set of assumptions.
 */
public record AssumptionSignature(List<String> normalizedAssumptions, String fingerprint) implements RetainedGraph.View {
    @Override public void retainedReferences(RetainedGraph.Visitor visitor) {
        visitor.reference(normalizedAssumptions);
        visitor.reference(fingerprint);
    }

    public AssumptionSignature(List<String> normalizedAssumptions, String fingerprint) {
        this.normalizedAssumptions = normalizedAssumptions == null ? List.of() : List.copyOf(normalizedAssumptions);
        this.fingerprint = fingerprint == null ? "" : fingerprint;
        long copyWork = normalizedAssumptions != null && this.normalizedAssumptions != normalizedAssumptions
            ? this.normalizedAssumptions.size() + 1L : 0;
        // The fully initialized record and both lists exist before a completed-copy debit.
        var retained = RetainedOperation.retainCompleted(copyWork + 1, normalizedAssumptions, fingerprint, this);
        if (retained != null) retained.close();
    }

    public static AssumptionSignature ofExpressions(Collection<String> assumptions) {
        if (assumptions == null || assumptions.isEmpty()) {
            return new AssumptionSignature(List.of(), "");
        }
        TreeSet<String> normalized = new TreeSet<>();
        Object[] pending = new Object[4];
        var retained = RetainedOperation.retainCompleted(3, assumptions, normalized, pending);
        Throwable primary = null;
        try {
            for (String assumption : assumptions) {
                RetainedOperation.work(1);
                if (assumption != null) add(normalized, pending, normalizeExpression(assumption));
            }
            return finish(normalized, pending);
        } catch (RuntimeException | Error failure) {
            primary = failure;
            observeFailure(failure);
            throw failure;
        } finally { close(retained, primary); }
    }

    public static AssumptionSignature ofAssumptions(Collection<Assumption> assumptions) {
        if (assumptions == null || assumptions.isEmpty()) {
            return new AssumptionSignature(List.of(), "");
        }
        TreeSet<String> normalized = new TreeSet<>();
        Object[] pending = new Object[4];
        var retained = RetainedOperation.retainCompleted(3, assumptions, normalized, pending);
        Throwable primary = null;
        try {
            for (Assumption assumption : assumptions) {
                RetainedOperation.work(1);
                if (assumption == null) continue;
                pending[0] = normalizeExpression(assumption.expression());
                completed(1);
                pending[1] = assumption.kind() + "|" + pending[0];
                completed(((String) pending[1]).length() + 1L);
                add(normalized, pending, (String) pending[1]);
                pending[1] = null;
            }
            return finish(normalized, pending);
        } catch (RuntimeException | Error failure) {
            primary = failure;
            observeFailure(failure);
            throw failure;
        } finally { close(retained, primary); }
    }

    public static AssumptionSignature merge(AssumptionSignature left, AssumptionSignature right) {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        TreeSet<String> merged = new TreeSet<>(left.normalizedAssumptions());
        Object[] pending = new Object[4];
        var retained = RetainedOperation.retainCompleted(left.normalizedAssumptions().size() + 3L, left, right, merged, pending);
        Throwable primary = null;
        try {
            merged.addAll(right.normalizedAssumptions());
            completed(right.normalizedAssumptions().size() + 1L);
            return finish(merged, pending);
        } catch (RuntimeException | Error failure) {
            primary = failure;
            observeFailure(failure);
            throw failure;
        } finally { close(retained, primary); }
    }

    private static void add(TreeSet<String> normalized, Object[] pending, String canonical) {
        pending[0] = canonical;
        completed(canonical.length() + 1L);
        if (!canonical.isBlank()) {
            normalized.add(canonical);
            completed(1);
        }
        pending[0] = null;
    }

    private static AssumptionSignature finish(TreeSet<String> normalized, Object[] pending) {
        List<String> list = List.copyOf(normalized);
        pending[1] = list;
        completed(list.size() + 1L);
        pending[2] = String.join(";", list);
        completed(((String) pending[2]).length() + 1L);
        pending[3] = new AssumptionSignature(list, (String) pending[2]);
        RetainedOperation.checkpoint();
        return (AssumptionSignature) pending[3];
    }

    /**
     * Normalize common textual variants of assumptions.
     */
    public static String normalizeExpression(String expression) {
        if (expression == null) {
            return "";
        }
        // Current expression, left/right operands, and the next completed string.
        String[] text = { expression, null, null, null };
        var retained = RetainedOperation.retainCompleted(1, expression, text);
        Throwable primary = null;
        try {
            update(text, 0, text[0].trim());
            update(text, 0, text[0].replace("≠", "!="));
            update(text, 0, text[0].replaceAll("\\s+", " "));
            update(text, 0, text[0].replaceAll("^not\\((.*)=0\\)$", "$1 != 0"));
            update(text, 0, text[0].replaceAll("\\s*!=\\s*", " != "));
            update(text, 0, text[0].replaceAll("\\s*>=\\s*", " >= "));
            update(text, 0, text[0].replaceAll("\\s*<=\\s*", " <= "));
            update(text, 0, text[0].replaceAll("\\s*>(?!=)\\s*", " > "));
            update(text, 0, text[0].replaceAll("\\s*<(?!=)\\s*", " < "));
            update(text, 0, text[0].trim());
            int notEquals = text[0].indexOf(" != ");
            RetainedOperation.work(text[0].length() + 1L);
            if (notEquals >= 0) {
                update(text, 1, text[0].substring(0, notEquals));
                update(text, 1, text[1].trim());
                update(text, 2, text[0].substring(notEquals + 4));
                update(text, 2, text[2].trim());
                stripOuterParens(text, 1);
                stripOuterParens(text, 2);
                if (isZero(text[1]) && !text[2].isBlank()) update(text, 0, text[2] + " != 0");
                else if (isZero(text[2]) && !text[1].isBlank()) update(text, 0, text[1] + " != 0");
            }
            return text[0];
        } catch (RuntimeException | Error failure) {
            primary = failure;
            observeFailure(failure);
            throw failure;
        } finally { close(retained, primary); }
    }

    private static boolean isZero(String value) {
        return value.equals("0") || value.equals("0.0");
    }

    private static void stripOuterParens(String[] text, int index) {
        while (text[index].startsWith("(") && text[index].endsWith(")") && text[index].length() > 1) {
            update(text, index, text[index].substring(1, text[index].length() - 1));
            update(text, index, text[index].trim());
        }
    }

    private static void update(String[] text, int index, String next) {
        text[3] = next;
        completed((text[index] == null ? 0L : text[index].length()) + next.length() + 1L);
        text[index] = next;
        text[3] = null;
    }

    private static void completed(long work) {
        RetainedOperation.work(work);
        RetainedOperation.checkpoint();
    }

    private static void observeFailure(Throwable failure) {
        try { RetainedOperation.checkpoint(); }
        catch (RuntimeException | Error observation) {
            if (observation != failure) failure.addSuppressed(observation);
        }
    }

    private static void close(RetainedOperation.Frame retained, Throwable primary) {
        try { if (retained != null) retained.close(); }
        catch (RuntimeException | Error cleanup) {
            if (primary == null) throw cleanup;
            if (cleanup != primary) primary.addSuppressed(cleanup);
        }
    }
}
