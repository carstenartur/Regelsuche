package de.regelsuche.math.algorithms.polynomial;

import de.regelsuche.math.algorithms.certificates.CertificateWork;
import de.regelsuche.math.algorithms.polynomial.DynamicAlgebraEvaluation.*;
import de.regelsuche.polynomial.PolynomialRing;
import de.regelsuche.polynomial.PolynomialVariable;
import de.regelsuche.polynomial.PolynomialWorkSink;
import de.regelsuche.polynomial.PrimeField;
import de.regelsuche.polynomial.UnivariatePolynomialView;
import java.math.BigInteger;
import java.util.List;

/** Per-call budget, validated formula and shared quotient arithmetic. */
final class DynamicAlgebraDomain {
    static final List<BigInteger> ONE = List.of(BigInteger.ONE);
    final CertificateWork work;
    final List<BigInteger> modulus;
    final int nodes;
    private final PrimeField field;
    private final PolynomialRing<BigInteger> ring;
    private final PolynomialWorkSink sink;
    private final long workspace;

    DynamicAlgebraDomain(Request request, CertificateWork work) {
        this.work = work;
        var shape = inspect(request, work);
        nodes = shape.nodes;
        int prime = request.prime();
        // PrimeField.of checks odd trial divisors through sqrt(prime), at most 23170 tests.
        work.charge(1L + (long) Math.ceil(Math.sqrt(Math.max(0, prime))) / 2);
        try {
            field = PrimeField.of(prime);
        } catch (IllegalArgumentException invalidPrime) {
            throw new Invalid("CHARACTERISTIC_NOT_PRIME");
        }
        work.charge(0);
        ring = new PolynomialRing<>(field, List.of(new PolynomialVariable("T")), PolynomialRing.MonomialOrder.LEXICOGRAPHIC);
        sink = (stage, units) -> work.charge(units);
        work.cells(shape.cells + 4L * request.modulus().size());
        var raw = view(request.modulus());
        if (raw.degree() <= 0) throw new Invalid("MODULUS_MUST_BE_NONCONSTANT");
        // At most d live components. Each can retain at most one coefficient vector of width d+1
        // per formula occurrence and temporary stack frame. Reserve space before constructing them.
        long width = raw.coefficientCount();
        work.cells(width * width);
        workspace = shape.cells + 24L * shape.maxWidth + 6L * width * width * (nodes + 1L);
        work.cells(workspace);
        modulus = raw.monic(field, sink, "normalize").coefficients();
        var derivative = raw.derivative(sink, "derivative").coefficients();
        if (!gcd(modulus, derivative).equals(ONE)) throw new Invalid("MODULUS_NOT_SQUARE_FREE");
    }

    void certificateCells(long cells) { work.cells(workspace + cells); }

    static final class Shape {
        private int nodes;
        private long cells;
        private int maxWidth;
        long cells() { return cells; }
    }

    static Shape inspect(Request request, CertificateWork work) {
        var shape = new Shape();
        inspectCoefficients(request.modulus(), shape, work);
        inspectExpression(request.expression(), 1, shape, work);
        return shape;
    }

    private static void inspectCoefficients(List<BigInteger> values, Shape shape, CertificateWork work) {
        shape.cells += values.size();
        shape.maxWidth = Math.max(shape.maxWidth, values.size());
        work.cells(shape.cells);
        for (var value : values) work.check(value);
    }

    private static void inspectExpression(Expr expr, int depth, Shape shape, CertificateWork work) {
        work.charge(1);
        if (depth > 64) throw new CertificateWork.Stop("FORMULA_DEPTH_LIMIT");
        shape.nodes++;
        work.cells(++shape.cells);
        switch (expr) {
            case Literal literal -> inspectCoefficients(literal.coefficients(), shape, work);
            case Inverse inverse -> inspectExpression(inverse.operand(), depth + 1, shape, work);
            case Add add -> {
                inspectExpression(add.left(), depth + 1, shape, work);
                inspectExpression(add.right(), depth + 1, shape, work);
            }
            case Product product -> {
                inspectExpression(product.left(), depth + 1, shape, work);
                inspectExpression(product.right(), depth + 1, shape, work);
            }
            case IfZero test -> {
                inspectExpression(test.condition(), depth + 1, shape, work);
                inspectExpression(test.whenZero(), depth + 1, shape, work);
                inspectExpression(test.whenUnit(), depth + 1, shape, work);
            }
        }
    }

    private UnivariatePolynomialView<BigInteger> view(List<BigInteger> a) {
        work.charge(1L + a.size());
        for (var c : a) work.check(c);
        return UnivariatePolynomialView.of(ring, a);
    }

    List<BigInteger> add(List<BigInteger> a, List<BigInteger> b) {
        return view(a).add(view(b), sink, "add").coefficients();
    }

    List<BigInteger> multiply(List<BigInteger> a, List<BigInteger> b) {
        return view(a).multiply(view(b), sink, "multiply").coefficients();
    }

    List<BigInteger> remainder(List<BigInteger> a, List<BigInteger> mod) {
        return view(a).divideAndRemainderMonic(view(mod), sink, "reduce").remainder().coefficients();
    }

    List<BigInteger> quotient(List<BigInteger> a, List<BigInteger> factor) {
        return view(a).exactQuotient(view(factor), field, sink, "quotient").coefficients();
    }

    List<BigInteger> gcd(List<BigInteger> a, List<BigInteger> b) {
        var first = view(a);
        var second = view(b);
        while (!second.isZero()) {
            work.charge(1);
            var next = first.divideAndRemainder(second, field, sink, "gcd").remainder();
            first = second;
            second = next;
        }
        return first.monic(field, sink, "monic").coefficients();
    }

    // Track the Bézout coefficient of the operand through the core's exact quotient divisions.
    List<BigInteger> inverse(List<BigInteger> a, List<BigInteger> mod) {
        var r0 = view(mod);
        var r1 = view(a);
        var s0 = UnivariatePolynomialView.zero(ring);
        var s1 = UnivariatePolynomialView.one(ring);
        while (!r1.isZero()) {
            work.charge(1);
            var division = r0.divideAndRemainder(r1, field, sink, "bezout");
            var next = s0.subtract(division.quotient().multiply(s1, sink, "multiply"), sink, "subtract");
            r0 = r1;
            r1 = division.remainder();
            s0 = s1;
            s1 = next;
        }
        if (r0.degree() != 0) throw new IllegalStateException("EXPECTED_UNIT");
        var normalized = s0.scale(field.divide(BigInteger.ONE, r0.coefficient(0)), sink, "scale");
        return remainder(normalized.coefficients(), mod);
    }

    void canonical(List<BigInteger> a, int maxWidth) {
        work.charge(1);
        if (a.size() > maxWidth || (!a.isEmpty() && a.getLast().signum() == 0)) throw new Invalid("NONCANONICAL_POLYNOMIAL");
        for (var c : a) {
            work.check(c);
            if (c.signum() < 0 || c.compareTo(field.modulus()) >= 0) throw new Invalid("NONCANONICAL_COEFFICIENT");
        }
    }
}
