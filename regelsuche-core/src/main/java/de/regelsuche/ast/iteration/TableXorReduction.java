package de.regelsuche.ast.iteration;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;

/**
 * Byte matrix contraction Y[o,p] = XOR_i T[u8(C[o,i])][u8(X[i,p])].
 * Order lists the source axes from outermost to innermost. Storage describes how a source
 * starts and finishes its reduction. Reordering accesses in a mutable source program
 * requires separate evidence about effects, aliases and bounds.
 */
public record TableXorReduction(List<Axis> order, Storage storage) {
    public enum Axis { OUTPUT, POSITION, INPUT }
    public enum Storage { ZERO_XOR_REDUCE_THEN_STORE, FIRST_STORE_THEN_XOR }

    public TableXorReduction {
        order = List.copyOf(order);
        Objects.requireNonNull(storage, "storage");
        if (order.size() != Axis.values().length || EnumSet.copyOf(order).size() != order.size()) {
            throw new IllegalArgumentException("expected each axis exactly once");
        }
        if (storage == Storage.ZERO_XOR_REDUCE_THEN_STORE && order.getLast() != Axis.INPUT) {
            throw new IllegalArgumentException("scalar reduction requires an innermost input axis");
        }
    }
}
