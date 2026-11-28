package de.regelsuche.math.algorithms.iteration;

import de.regelsuche.ast.iteration.TableXorReduction;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

/** Order-preserving prefix peeling of an increasing input index under a stable signed int bound. */
public final class OrderedInputPrefix {
    /** Limits the number of straight-line bodies proposed or inspected in one schedule. */
    public static final int MAX_WIDTH = 16;

    private OrderedInputPrefix() {}

    /**
     * When count >= prefixThreshold, execute the listed indices in order. Then execute the
     * original unit-stride loop up to count (exclusive) starting at
     * count >= tailThreshold ? fastTailStart : fallbackTailStart.
     */
    public record Schedule(int prefixThreshold, List<Integer> prefixIndices, int tailThreshold,
                           int fastTailStart, int fallbackTailStart) {
        public Schedule {
            prefixIndices = List.copyOf(prefixIndices);
        }
    }

    /** The caller supplies the desired width as a code-size/performance choice. */
    public static Optional<Schedule> plan(TableXorReduction source, int width) {
        if (width < 1 || width > MAX_WIDTH) return Optional.empty();
        var schedule = new Schedule(width, IntStream.range(0, width).boxed().toList(), width, width, 0);
        return verify(source, schedule) ? Optional.of(schedule) : Optional.empty();
    }

    /**
     * For count below the common threshold, only the original loop from zero runs (empty for
     * a negative count). Otherwise the prefix visits [0,width) and the tail visits [width,count)
     * in order. This proof concerns the index trace. A source adapter must independently verify
     * the guard, loop syntax, stable count binding, and every body's ordered operations.
     */
    public static boolean verify(TableXorReduction source, Schedule schedule) {
        if (source == null || schedule == null
                || source.storage() != TableXorReduction.Storage.ZERO_XOR_REDUCE_THEN_STORE
                || source.order().getLast() != TableXorReduction.Axis.INPUT) {
            return false;
        }
        int width = schedule.prefixIndices().size();
        if (width < 1 || width > MAX_WIDTH || schedule.prefixThreshold() != width
                || schedule.tailThreshold() != width || schedule.fastTailStart() != width
                || schedule.fallbackTailStart() != 0) {
            return false;
        }
        for (int position = 0; position < width; position++) {
            if (schedule.prefixIndices().get(position) != position) return false;
        }
        return true;
    }
}
