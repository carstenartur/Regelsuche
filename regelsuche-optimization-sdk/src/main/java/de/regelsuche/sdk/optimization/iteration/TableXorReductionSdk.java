package de.regelsuche.sdk.optimization.iteration;

import de.regelsuche.ast.iteration.TableXorReduction;
import de.regelsuche.math.algorithms.iteration.BinaryByteFieldTable;
import de.regelsuche.math.algorithms.iteration.OrderedInputPrefix;
import de.regelsuche.math.algorithms.iteration.TableXorContraction;
import java.util.List;
import java.util.Optional;

/** Portable representation, reference evaluation and optional field qualification of byte table reductions. */
public final class TableXorReductionSdk {
    private TableXorReductionSdk() {}

    public static TableXorReduction describe(List<TableXorReduction.Axis> order, TableXorReduction.Storage storage) {
        return new TableXorReduction(order, storage);
    }

    public static byte[][] evaluate(TableXorReduction reduction, byte[][] table, byte[][] coefficients,
                                    byte[][] inputs, int inputCount, int outputCount, int offset, int byteCount) {
        return TableXorContraction.evaluate(reduction, table, coefficients, inputs,
                inputCount, outputCount, offset, byteCount);
    }

    public static Optional<OrderedInputPrefix.Schedule> orderedPrefixPlan(TableXorReduction reduction, int width) {
        return OrderedInputPrefix.plan(reduction, width);
    }

    public static boolean verifyOrderedPrefix(TableXorReduction reduction, OrderedInputPrefix.Schedule schedule) {
        return OrderedInputPrefix.verify(reduction, schedule);
    }

    public static Optional<BinaryByteFieldTable.Verified> verifyBinaryField(byte[][] table) {
        return BinaryByteFieldTable.verify(table);
    }
}
