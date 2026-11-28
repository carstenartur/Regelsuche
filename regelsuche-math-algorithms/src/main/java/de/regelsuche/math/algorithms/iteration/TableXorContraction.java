package de.regelsuche.math.algorithms.iteration;

import de.regelsuche.ast.iteration.TableXorReduction;
import java.util.Objects;

/** Exact reference semantics of a byte table contraction on stable input arrays. */
public final class TableXorContraction {
    private TableXorContraction() {}

    /**
     * Computes fresh output rows. Column zero of each result corresponds to the source
     * offset. Input count zero is defined for reductions with an explicit zero initializer.
     */
    public static byte[][] evaluate(TableXorReduction reduction, byte[][] table,
                                    byte[][] coefficients, byte[][] inputs,
                                    int inputCount, int outputCount, int offset, int byteCount) {
        Objects.requireNonNull(reduction, "reduction");
        if (inputCount < 0 || inputCount > inputs.length
                || outputCount < 0 || outputCount > coefficients.length
                || offset < 0 || byteCount < 0 || byteCount > Integer.MAX_VALUE - offset) {
            throw new IllegalArgumentException("invalid contraction dimensions");
        }
        if (inputCount == 0 && reduction.storage() == TableXorReduction.Storage.FIRST_STORE_THEN_XOR) {
            throw new IllegalArgumentException("first-input initialization requires an input");
        }
        if (table.length != 256) throw new IllegalArgumentException("expected 256 table rows");
        for (byte[] row : table) {
            if (row == null || row.length != 256) throw new IllegalArgumentException("expected 256 columns");
        }
        for (int input = 0; input < inputCount; input++) {
            if (inputs[input] == null || inputs[input].length < offset + byteCount) {
                throw new IllegalArgumentException("input row shorter than requested window");
            }
        }
        for (int output = 0; output < outputCount; output++) {
            if (coefficients[output] == null || coefficients[output].length < inputCount) {
                throw new IllegalArgumentException("coefficient row shorter than requested input count");
            }
        }
        var result = new byte[outputCount][byteCount];
        for (int output = 0; output < outputCount; output++) {
            for (int position = 0; position < byteCount; position++) {
                byte sum = 0;
                for (int input = 0; input < inputCount; input++) {
                    int coefficient = Byte.toUnsignedInt(coefficients[output][input]);
                    int value = Byte.toUnsignedInt(inputs[input][offset + position]);
                    sum ^= table[coefficient][value];
                }
                result[output][position] = sum;
            }
        }
        return result;
    }
}
