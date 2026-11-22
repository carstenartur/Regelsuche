package de.regelsuche.measure;

import java.util.function.Consumer;

/**
 * Bootstrap-visible callback for an explicitly selected original JDK StringBuilder operation.
 * The callback has no dependency on the application class loader. Observation never recurses.
 */
public final class FrozenStringPrimitives {
    private static volatile Thread owner;
    private static Consumer<String> observer;
    private static boolean observing;

    private FrozenStringPrimitives() { }

    public static synchronized void begin(Consumer<String> sink) {
        if (owner != null || sink == null) throw new IllegalStateException("string receiver unavailable");
        observer = sink;
        owner = Thread.currentThread();
    }

    public static synchronized void end() {
        if (owner != Thread.currentThread()) throw new IllegalStateException("foreign string receiver");
        owner = null;
        observer = null;
        observing = false;
    }

    private static boolean active() {
        return owner == Thread.currentThread() && !observing;
    }

    public static void event(String label) {
        if (active()) {
            observing = true;
            try {
                observer.accept(label);
            } finally {
                observing = false;
            }
        }
    }

    public static void arraycopy(Object source, int srcStart, Object target, int dstStart, int size) {
        if (!active()) {
            System.arraycopy(source, srcStart, target, dstStart, size);
            return;
        }
        if (!(source instanceof byte[] from) || !(target instanceof byte[] to) || from == to
                || srcStart < 0 || dstStart < 0 || size < 0 || srcStart > from.length - size || dstStart > to.length - size) {
            event("gap.jdk.arraycopy-shape");
            throw new IllegalStateException("uncovered array copy");
        }
        for (int i = 0; i < size; i++) {
            event("jdk.arraycopy.source-byte");
            byte value = from[srcStart + i];
            event("jdk.arraycopy.target-byte");
            to[dstStart + i] = value;
        }
    }
}
