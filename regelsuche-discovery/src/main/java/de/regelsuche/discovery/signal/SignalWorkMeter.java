package de.regelsuche.discovery.signal;

import de.regelsuche.scalar.ExactRational;

/** Local, non-shared arithmetic instrumentation. */
final class SignalWorkMeter {
    private long integers;
    private long rationals;
    private long cells;
    private long bits;
    private int maxBits;

    void integer() { integers++; }
    void cells(int count) { cells += count; }

    ExactRational add(ExactRational a, ExactRational b) {
        operands(a, b);
        return a.add(b);
    }

    ExactRational subtract(ExactRational a, ExactRational b) {
        operands(a, b);
        return a.subtract(b);
    }

    ExactRational multiply(ExactRational a, ExactRational b) {
        operands(a, b);
        return a.multiply(b);
    }

    ExactRational divide(ExactRational a, ExactRational b) {
        operands(a, b);
        return a.divide(b);
    }

    private void operands(ExactRational a, ExactRational b) {
        rationals++;
        for (var value : new ExactRational[] {a, b}) {
            int size = value.numerator().abs().bitLength() + value.denominator().bitLength();
            bits += size;
            maxBits = Math.max(maxBits, size);
        }
    }

    SignalWork snapshot() {
        return new SignalWork(integers, rationals, cells, bits, maxBits);
    }
}
