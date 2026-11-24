package de.regelsuche.math.algorithms.iteration;

import de.regelsuche.ast.iteration.AffineReduction;
import de.regelsuche.ast.iteration.AffineReduction.Operator;
import de.regelsuche.math.algorithms.iteration.AffineDomains.Clause;
import de.regelsuche.math.algorithms.iteration.AffineReductionPlan.Evaluation;
import de.regelsuche.math.algorithms.iteration.IterationPolynomial.Unavailable;
import de.regelsuche.math.algorithms.iteration.IterationPolynomial.Work;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Direct, unscheduled evaluation of all source-domain reduction points. */
public final class AffineReductionReference {
    private AffineReductionReference() {}

    public static Evaluation evaluate(AffineReduction source, int parameter, List<BigInteger> input,
            IterationLimits limits, BooleanSupplier cancellation) {
        var work = new Work(limits, cancellation, 1);
        try {
            var shape = AffineReductionPlan.shape(source, parameter, work);
            var data = AffineReductionPlan.checkedInput(input, shape, work);
            var values = new ArrayList<BigInteger>();
            for (var coordinate : shape.outputCoordinates()) {
                var domain = fixOutput(source, parameter, coordinate, work);
                values.add(evaluateDomain(source, domain, data, work));
            }
            return new Evaluation(shape, values, work.used());
        } catch (Unavailable failure) {
            throw new IllegalArgumentException(failure.getMessage(), failure);
        }
    }

    private static List<Clause> fixOutput(AffineReduction source, int parameter, List<Integer> output, Work work) {
        var result = new ArrayList<Clause>();
        for (var form : source.reductions()) {
            work.charge(1L + output.size());
            long constant = form.constant() + (long) form.parameter() * parameter;
            for (int axis = 0; axis < output.size(); axis++)
                constant += (long) form.coordinates().get(axis) * output.get(axis);
            result.add(new Clause(constant, form.coordinates().get(output.size()),
                source.reductionRank() == 2 ? form.coordinates().getLast() : 0));
        }
        return result;
    }

    private static BigInteger evaluateDomain(AffineReduction source, List<Clause> domain, List<BigInteger> data, Work work) {
        var first = AffineDomains.firstRange(domain, work);
        BigInteger result = null;
        for (int i = first.lower(); i <= first.upper(); i++) {
            work.charge(1);
            if (source.reductionRank() == 1) result = accumulate(result, data, i, source.operator(), work);
            else {
                var second = AffineDomains.secondRange(domain, i, work);
                for (int j = second.lower(); j <= second.upper(); j++) {
                    int index = source.inputIndex() == 0 ? i : j;
                    result = accumulate(result, data, index, source.operator(), work);
                }
            }
        }
        if (result == null) throw new IllegalArgumentException("UNDEFINED_EMPTY_AFFINE_REDUCTION");
        return result;
    }

    private static BigInteger accumulate(BigInteger result, List<BigInteger> input, int index, Operator operator, Work work) {
        if (index < 0 || index >= input.size()) throw new IllegalArgumentException("AFFINE_ACCESS_OUTSIDE_INPUT");
        var value = input.get(index);
        work.runtimeInteger(value);
        if (result == null) return value;
        if (operator == Operator.MAX) return result.max(value);
        var sum = result.add(value);
        work.runtimeInteger(sum);
        return sum;
    }
}
