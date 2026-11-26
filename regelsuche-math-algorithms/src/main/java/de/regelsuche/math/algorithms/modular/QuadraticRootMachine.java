package de.regelsuche.math.algorithms.modular;

import de.regelsuche.math.algorithms.certificates.CertificateWork;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import static de.regelsuche.math.algorithms.modular.QuadraticRootProgram.*;

/** One instruction semantics for concrete evaluation and symbolic interpretation. */
final class QuadraticRootMachine {
    private QuadraticRootMachine() {}
    interface Field<T> {
        T constant(BigInteger literal);
        T add(T a, T b);
        T negate(T a);
        T multiply(T a, T b);
        T power(T a, BigInteger e);
        boolean equal(T a, T b);
        int character(T a);
        int cells(T a);
        default void registers(long count, CertificateWork work) { work.cells(count); }
    }

    static void validate(QuadraticRootProgram program, BigInteger modulus, CertificateWork work) {
        int n = validate(program.prefix(), INPUTS, modulus, work);
        reference(program.guardLeft(), n);
        reference(program.guardRight(), n);
        for (var arm : List.of(program.equal(), program.unequal())) {
            int end = validate(arm.steps(), n, modulus, work);
            reference(arm.real(), end);
            reference(arm.imaginary(), end);
        }
    }

    private static int validate(List<Step> steps, int n, BigInteger p, CertificateWork work) {
        work.cells(n + steps.size());
        for (var step : steps) {
            work.charge(1L + SharedPowerVerifier.words(step.literal()));
            if (step.left() != -1) reference(step.left(), n);
            if (step.right() != -1) reference(step.right(), n);
            if (step.op() == Op.CONSTANT && step.literal().compareTo(p) >= 0) throw new IllegalArgumentException("NONCANONICAL_CONSTANT");
            n++;
        }
        return n;
    }

    private static void reference(int ref, int bound) {
        if (ref < 0 || ref >= bound) throw new IllegalArgumentException("INVALID_FIELD_REFERENCE");
    }

    record Pair<T>(T real, T imaginary) {}
    record Prefix<T>(List<T> registers, boolean equal) {}

    static <T> Prefix<T> prefix(QuadraticRootProgram program, List<T> inputs, Field<T> field, CertificateWork work) {
        var values = run(program.prefix(), inputs, field, work);
        return new Prefix<>(values, field.equal(values.get(program.guardLeft()), values.get(program.guardRight())));
    }

    static <T> Pair<T> exit(Arm arm, Prefix<T> prefix, Field<T> field, CertificateWork work) {
        var values = run(arm.steps(), prefix.registers(), field, work);
        var real = values.get(arm.real());
        var imaginary = values.get(arm.imaginary());
        if (arm.normalizeRealCharacter() && field.character(real) < 0) {
            return new Pair<>(field.negate(real), field.negate(imaginary));
        }
        return new Pair<>(real, imaginary);
    }

    private static <T> List<T> run(List<Step> steps, List<T> initial, Field<T> field, CertificateWork work) {
        var values = new ArrayList<>(initial);
        long cells = values.stream().mapToLong(field::cells).sum();
        field.registers(cells, work);
        for (var step : steps) {
            work.charge(1);
            T a = step.left() < 0 ? null : values.get(step.left());
            T b = step.right() < 0 ? null : values.get(step.right());
            T result = switch (step.op()) {
                case CONSTANT -> field.constant(step.literal());
                case ADD -> field.add(a, b);
                case SUBTRACT -> field.add(a, field.negate(b));
                case MULTIPLY -> field.multiply(a, b);
                case SQUARE -> field.multiply(a, a);
                case NEGATE -> field.negate(a);
                case POWER -> field.power(a, step.literal());
                case INVERSE -> field.power(a, BigInteger.ONE.negate());
            };
            cells += field.cells(result);
            field.registers(cells, work);
            values.add(result);
        }
        return values;
    }
}
