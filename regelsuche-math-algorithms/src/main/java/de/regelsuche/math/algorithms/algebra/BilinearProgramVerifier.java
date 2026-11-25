package de.regelsuche.math.algorithms.algebra;

import de.regelsuche.math.algorithms.algebra.BilinearProgram.Cost;
import de.regelsuche.math.algorithms.algebra.BilinearProgram.Rejection;
import de.regelsuche.math.algorithms.algebra.BilinearProgram.Request;
import de.regelsuche.math.algorithms.algebra.BilinearProgram.Result;
import de.regelsuche.math.algorithms.certificates.CertificateWork;
import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.scalar.ExactRational;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Independent coefficient expansion and exact execution of certified U/V/W programs. */
public final class BilinearProgramVerifier {
    private BilinearProgramVerifier() {}

    public static Result<Cost> verify(Request request, BilinearProgram program,
                                      IterationLimits limits, BooleanSupplier cancellation) {
        return BilinearProgram.run(limits, cancellation,
            work -> check(request, program, request.checkInputs(work), work));
    }

    static Cost check(Request request, BilinearProgram program, long sourceCells, CertificateWork work) {
        var tensor = request.tensor();
        int rank = program.u().size();
        if (rank > request.maximumProducts()) throw new Rejection("PRODUCT_LIMIT");
        if (program.v().size() != rank || program.w().size() != tensor.outputs().size()) {
            throw new Rejection("PROGRAM_SHAPE");
        }
        work.cells(sourceCells + certificateCells(request, rank));
        checkRows(program.u(), tensor.leftSize(), work);
        checkRows(program.v(), tensor.rightSize(), work);
        checkRows(program.w(), rank, work);
        checkDictionary(program.u(), request.leftForms(), work);
        checkDictionary(program.v(), request.rightForms(), work);
        for (int k = 0; k < tensor.outputs().size(); k++) {
            for (int i = 0; i < tensor.leftSize(); i++) {
                for (int j = 0; j < tensor.rightSize(); j++) {
                    work.charge(1);
                    var sum = ExactRational.ZERO;
                    for (int p = 0; p < rank; p++) {
                        var uv = work.mul(program.u().get(p).get(i), program.v().get(p).get(j));
                        sum = work.add(sum, work.mul(program.w().get(k).get(p), uv));
                    }
                    if (!sum.equals(tensor.outputs().get(k).get(i * tensor.rightSize() + j))) {
                        throw new Rejection("COEFFICIENT_MISMATCH");
                    }
                }
            }
        }
        return BilinearProgramSchedule.cost(program, work);
    }

    private static long certificateCells(Request request, int rank) {
        var tensor = request.tensor();
        return rank * (2L + tensor.leftSize() + tensor.rightSize())
            + tensor.outputs().size() * (1L + rank);
    }

    private static void checkRows(List<List<ExactRational>> rows, int width, CertificateWork work) {
        for (var row : rows) {
            work.charge(1);
            if (row.size() != width) throw new Rejection("PROGRAM_SHAPE");
            for (var value : row) work.check(value);
        }
    }

    private static void checkDictionary(List<List<ExactRational>> forms, List<List<ExactRational>> dictionary,
                                        CertificateWork work) {
        for (var form : forms) {
            boolean found = false;
            for (var candidate : dictionary) {
                work.charge(form.size());
                if (candidate.equals(form)) {
                    found = true;
                    break;
                }
            }
            if (!found) throw new Rejection("FORM_OUTSIDE_DICTIONARY");
        }
    }

    /** Reuse a supplied witness with a width profile after checking every source coefficient. */
    public static Result<BilinearIntegerSchedule.Plan> scheduleInteger(Request request, BilinearProgram program,
            BilinearIntegerSchedule.Profile profile, IterationLimits limits, BooleanSupplier cancellation) {
        return BilinearProgram.run(limits, cancellation, work -> {
            if (profile.leftBits().size() != request.tensor().leftSize()
                    || profile.rightBits().size() != request.tensor().rightSize()) {
                throw new Rejection("PROFILE_SHAPE");
            }
            long source = request.checkInputs(work) + profile.retainedCells();
            check(request, program, source, work);
            long retained = source + certificateCells(request, program.u().size());
            return BilinearIntegerSchedule.plan(program, profile, retained, work);
        });
    }

    /** Verify against the source, then execute with the same resource account. */
    public static Result<List<ExactRational>> evaluate(Request request, BilinearProgram program,
            List<ExactRational> x, List<ExactRational> y, IterationLimits limits, BooleanSupplier cancellation) {
        return BilinearProgram.run(limits, cancellation, work -> {
            var tensor = request.tensor();
            long inputCells = (long) tensor.leftSize() + tensor.rightSize();
            work.cells(inputCells);
            work.charge(inputCells);
            if (x.size() != tensor.leftSize() || y.size() != tensor.rightSize()) {
                throw new IllegalArgumentException("INPUT_VECTOR_SHAPE");
            }
            var left = List.copyOf(x);
            var right = List.copyOf(y);
            if (left.size() != tensor.leftSize() || right.size() != tensor.rightSize()) {
                throw new IllegalArgumentException("INPUT_VECTOR_SHAPE");
            }
            long source = request.checkInputs(work);
            int rank = program.u().size();
            check(request, program, source, work);
            work.cells(source + certificateCells(request, rank) + inputCells
                + rank + tensor.outputs().size() + 3L);
            for (var value : left) work.check(value);
            for (var value : right) work.check(value);
            var products = new ArrayList<ExactRational>(rank);
            for (int p = 0; p < rank; p++) {
                var a = BilinearProgramSchedule.dot(program.u().get(p), left, work);
                var b = BilinearProgramSchedule.dot(program.v().get(p), right, work);
                products.add(work.mul(a, b));
            }
            var values = new ArrayList<ExactRational>(program.w().size());
            for (var weights : program.w()) values.add(BilinearProgramSchedule.dot(weights, products, work));
            return List.copyOf(values);
        });
    }
}
