package de.regelsuche.sdk.optimization.power;

import java.math.BigInteger;
import java.util.*;

/** A proposed multiplication schedule. Node 0 is the identity, node 1 is the base;
 * step i produces node i+2. Only FixedPowerVerifier establishes the claimed exponent.
 */
public record FixedPowerPlan(BigInteger exponent,List<Multiplication> multiplications,int resultIndex) {
    public static final int MAX_STEPS=8192, MAX_EXPONENT_BITS=4096;
    public record Multiplication(int left,int right) { }
    public FixedPowerPlan {
        boundedExponent(exponent);
        Objects.requireNonNull(multiplications);
        if(multiplications.size()>MAX_STEPS) throw new IllegalArgumentException("POWER_STEP_LIMIT");
        // Do not trust size(), toArray(), or bulk collection copying. A custom list
        // can advertise a small size while enumerating an unbounded number of steps.
        var bounded=new ArrayList<Multiplication>();
        var iterator=multiplications.iterator();
        while(iterator.hasNext()) {
            if(bounded.size()==MAX_STEPS) throw new IllegalArgumentException("POWER_STEP_LIMIT");
            bounded.add(Objects.requireNonNull(iterator.next()));
        }
        multiplications=List.copyOf(bounded);
    }
    static void boundedExponent(BigInteger exponent) {
        if(exponent==null || exponent.getClass()!=BigInteger.class || exponent.signum()<0 || exponent.bitLength()>MAX_EXPONENT_BITS)
            throw new IllegalArgumentException("POWER_EXPONENT_LIMIT");
    }
}
