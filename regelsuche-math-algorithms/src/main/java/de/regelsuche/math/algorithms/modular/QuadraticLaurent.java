package de.regelsuche.math.algorithms.modular;

import de.regelsuche.math.algorithms.certificates.CertificateWork;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;

/** Sparse Fp[H±,X±] in one character lane H^m=u, X^m=s, where m=(p-1)/2. */
final class QuadraticLaurent implements QuadraticRootMachine.Field<QuadraticLaurent.Form> {
    record Degrees(BigInteger h, BigInteger x) {}
    record Form(Map<Degrees, BigInteger> terms) {
        Form { terms = Map.copyOf(terms); }
    }

    private final BigInteger p;
    private final BigInteger m;
    private final int u;
    private final int s;
    private final CertificateWork work;
    private final long wordCost;
    private long externalCells;
    private long registerCells;
    private static final int MAX_TERMS = 32;

    QuadraticLaurent(BigInteger p, int u, int s, CertificateWork work) {
        this.p = p;
        this.m = p.shiftRight(1);
        this.u = u;
        this.s = s;
        this.work = work;
        long words = SharedPowerVerifier.words(p);
        this.wordCost = words * words;
    }

    void retain(long count) {
        externalCells = count;
        registerCells = 0;
        work.cells(count);
    }

    @Override public void registers(long count, CertificateWork budget) {
        registerCells = count;
        budget.cells(externalCells + count);
    }

    Form term(BigInteger coefficient, BigInteger h, BigInteger x) {
        work.charge(wordCost + SharedPowerVerifier.words(h) + SharedPowerVerifier.words(x));
        work.cells(externalCells + registerCells + 4);
        var hr = h.mod(m);
        var xr = x.mod(m);
        // The discarded multiples can be negative. Parity of their signed quotient gives the same sign.
        if (u < 0 && h.subtract(hr).divide(m).testBit(0)) coefficient = coefficient.negate();
        if (s < 0 && x.subtract(xr).divide(m).testBit(0)) coefficient = coefficient.negate();
        var c = coefficient.mod(p);
        return new Form(c.signum() == 0 ? Map.of() : Map.of(new Degrees(hr, xr), c));
    }

    @Override public Form constant(BigInteger c) { return term(c, BigInteger.ZERO, BigInteger.ZERO); }

    private void accumulate(Map<Degrees, BigInteger> sum, Degrees degrees, BigInteger value) {
        work.charge(wordCost);
        var c = sum.getOrDefault(degrees, BigInteger.ZERO).add(value).mod(p);
        if (c.signum() == 0) sum.remove(degrees);
        else sum.put(degrees, c);
        if (sum.size() > MAX_TERMS) throw new CertificateWork.Stop("LAURENT_TERM_LIMIT");
        work.cells(externalCells + registerCells + 3L * sum.size() + 1);
    }

    @Override public Form add(Form a, Form b) {
        var sum = new HashMap<>(a.terms());
        b.terms().forEach((degrees, value) -> accumulate(sum, degrees, value));
        return new Form(sum);
    }

    @Override public Form negate(Form a) {
        var sum = new HashMap<Degrees, BigInteger>();
        a.terms().forEach((degrees, value) -> accumulate(sum, degrees, value.negate()));
        return new Form(sum);
    }

    @Override public Form multiply(Form a, Form b) {
        work.charge(1L + (long)a.terms().size() * b.terms().size());
        var sum = new HashMap<Degrees, BigInteger>();
        for (var left : a.terms().entrySet()) for (var right : b.terms().entrySet()) {
            var reduced = term(left.getValue().multiply(right.getValue()), left.getKey().h().add(right.getKey().h()),
                    left.getKey().x().add(right.getKey().x()));
            reduced.terms().forEach((degrees, value) -> accumulate(sum, degrees, value));
        }
        return new Form(sum);
    }

    private Map.Entry<Degrees, BigInteger> unit(Form value) {
        if (value.terms().size() != 1) throw new IllegalArgumentException("UNIT_MONOMIAL_REQUIRED");
        return value.terms().entrySet().iterator().next();
    }

    @Override public Form power(Form a, BigInteger exponent) {
        var entry = unit(a);
        var c = entry.getValue();
        work.charge(wordCost * (1L + SharedPowerVerifier.words(exponent)));
        if (c.equals(p.subtract(BigInteger.ONE))) c = exponent.testBit(0) ? c : BigInteger.ONE;
        else if (!c.equals(BigInteger.ONE)) {
            work.charge(wordCost * (1L + exponent.abs().bitLength() + (exponent.signum() < 0 ? p.bitLength() : 0)));
            c = c.modPow(exponent, p);
        }
        return term(c, entry.getKey().h().multiply(exponent), entry.getKey().x().multiply(exponent));
    }

    @Override public boolean equal(Form a, Form b) {
        var difference = add(a, negate(b));
        if (difference.terms().isEmpty()) return true;
        unit(difference); // A single nonzero Laurent monomial never vanishes in this lane.
        return false;
    }

    @Override public int character(Form a) {
        var monomial = unit(a);
        var c = monomial.getValue();
        int sign = 1;
        if (c.equals(p.subtract(BigInteger.ONE))) sign = -1;
        else if (!c.equals(BigInteger.ONE)) {
            work.charge(wordCost * (1L + m.bitLength()));
            sign = c.modPow(m, p).equals(BigInteger.ONE) ? 1 : -1;
        }
        if (monomial.getKey().h().testBit(0)) sign *= u;
        if (monomial.getKey().x().testBit(0)) sign *= s;
        return sign;
    }

    @Override public int cells(Form value) { return 1 + 3 * value.terms().size(); }
}
