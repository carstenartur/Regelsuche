package de.regelsuche.sdk.optimization;

import de.regelsuche.search.program.ComputationBackend;
import java.math.BigInteger;

/** Declared and executed Java numeric kinds are represented separately in the source trace. */
public enum NumericKind {
    BIG_INTEGER(0, true, BigInteger.class), BYTE(8, true, Byte.class), SHORT(16, true, Short.class),
    CHAR(16, false, Character.class), INT(32, true, Integer.class), LONG(64, true, Long.class),
    FLOAT(32, true, Float.class), DOUBLE(64, true, Double.class);
    private final int bits;
    private final boolean signed;
    private final ComputationBackend.Type type;
    NumericKind(int bits, boolean signed, Class<?> runtimeClass) {
        this.bits = bits; this.signed = signed;
        type = new ComputationBackend.Type("java." + name().toLowerCase(java.util.Locale.ROOT), runtimeClass);
    }
    public int bits() { return bits; }
    public boolean signed() { return signed; }
    public boolean floatingPoint() { return this == FLOAT || this == DOUBLE; }
    public boolean integral() { return this != BIG_INTEGER && !floatingPoint(); }
    public ComputationBackend.Type type() { return type; }
    public static NumericKind fromType(ComputationBackend.Type type) {
        for (var kind : values()) if (kind.type.equals(type)) return kind;
        throw new IllegalArgumentException("UNKNOWN_NUMERIC_TYPE");
    }
}
