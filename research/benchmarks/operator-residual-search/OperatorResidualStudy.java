package operatorstudy;

import de.regelsuche.math.algorithms.algebra.OperatorResidualPlans;
import de.regelsuche.math.algorithms.algebra.OperatorResidualSearch;
import de.regelsuche.math.algorithms.algebra.PreparedOperator;
import de.regelsuche.math.algorithms.equivalence.Polynomial;
import de.regelsuche.math.algorithms.equivalence.Rational;
import de.regelsuche.math.algorithms.linalg.ExactMatrixAlgebra;
import de.regelsuche.math.algorithms.linalg.SymbolicLinearSystem.PolynomialMatrix;
import de.regelsuche.scalar.ExactRational;
import de.regelsuche.sdk.optimization.CancellationToken;
import de.regelsuche.sdk.optimization.OptimizationBudget;
import de.regelsuche.sdk.optimization.algebra.OperatorPlanSdk;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.lang.management.ManagementFactory;
import java.util.function.Supplier;

/** Same rational inputs, exact output validation and separate acquisition/application arms. */
public class OperatorResidualStudy {
    public String family;

    private static final OptimizationBudget BUDGET = new OptimizationBudget(10_000_000, 500_000, 4, 30_000);
    private static final OperatorResidualSearch.Policy POLICY = OperatorResidualSearch.Policy.defaults();
    private PolynomialMatrix source;
    private PreparedOperator selected;
    private PreparedOperator direct;
    private ExactRational[][] coefficients;
    private List<List<ExactRational>> samples;
    private long acquisitionWork;
    private long verificationWork;
    private int cursor;

    public void setup() {
        int dimension = family.equals("center16") ? 16 : 8;
        var algebra = new ExactMatrixAlgebra(new ExactMatrixAlgebra.Work(1_000_000));
        var random = new Random(291843);
        var reciprocal = new Rational(BigInteger.ONE, BigInteger.valueOf(dimension));
        source = algebra.matrix(dimension, dimension, (row, column) -> Polynomial.constant(switch (family) {
            case "center8", "center16" -> (row.equals(column) ? Rational.ONE : Rational.ZERO).subtract(reciprocal);
            case "diagonal8" -> row.equals(column) ? Rational.of(row + 1L) : Rational.ZERO;
            case "dense8" -> Rational.of(random.nextInt(13) - 6);
            default -> throw new IllegalArgumentException("unknown family");
        }));
        var discovery = OperatorPlanSdk.discover(source, POLICY, BUDGET, CancellationToken.NONE);
        acquisitionWork = discovery.work();
        selected = discovery.requireValue().operator();
        direct = OperatorPlanSdk.discover(source, new OperatorResidualSearch.Policy(List.of(), 0),
            BUDGET, CancellationToken.NONE).requireValue().operator();
        var checked = OperatorPlanSdk.verify(source, selected.plan(), BUDGET, CancellationToken.NONE);
        if (checked.status() != OperatorResidualPlans.Status.VERIFIED) throw new IllegalStateException(checked.detail());
        verificationWork = checked.work();
        coefficients = new ExactRational[dimension][dimension];
        for (int i = 0; i < dimension; i++) for (int j = 0; j < dimension; j++) {
            var value = source.get(i,j);
            if (value.isZero()) coefficients[i][j] = ExactRational.ZERO;
            else {
                var coefficient = value.terms().values().iterator().next();
                coefficients[i][j] = new ExactRational(coefficient.numerator(), coefficient.denominator());
            }
        }
        var inputs = new ArrayList<List<ExactRational>>();
        for (int sample = 0; sample < 64; sample++) {
            var vector = new ArrayList<ExactRational>();
            for (int i = 0; i < dimension; i++) vector.add(ExactRational.integer(random.nextInt(2001) - 1000));
            inputs.add(List.copyOf(vector));
        }
        samples = List.copyOf(inputs);
        for (var input : samples) {
            var expected = raw(input);
            if (!expected.equals(OperatorPlanSdk.apply(selected, input, BUDGET, CancellationToken.NONE).requireValue())
                || !expected.equals(OperatorPlanSdk.apply(direct, input, BUDGET, CancellationToken.NONE).requireValue())
                || !expected.equals(specialized(input))) throw new IllegalStateException("independent replay mismatch");
        }
    }

    public Object acquire() {
        return OperatorPlanSdk.discover(source, POLICY, BUDGET, CancellationToken.NONE).requireValue();
    }

    public Object selected() {
        return OperatorPlanSdk.apply(selected, next(), BUDGET, CancellationToken.NONE).requireValue();
    }

    public Object directChecked() {
        return OperatorPlanSdk.apply(direct, next(), BUDGET, CancellationToken.NONE).requireValue();
    }

    public Object directArithmetic() { return raw(next()); }

    /** A domain-specific mean formula is an additional strong reference, not a search input. */
    public Object specializedArithmetic() { return specialized(next()); }

    private List<ExactRational> next() { return samples.get(cursor++ & 63); }

    private List<ExactRational> raw(List<ExactRational> input) {
        var output = new ExactRational[coefficients.length];
        for (int row = 0; row < coefficients.length; row++) {
            var sum = ExactRational.ZERO;
            boolean first = true;
            for (int column = 0; column < coefficients[row].length; column++) {
                var coefficient = coefficients[row][column];
                if (coefficient.isZero()) continue;
                var term = coefficient.equals(ExactRational.ONE) ? input.get(column) : coefficient.multiply(input.get(column));
                sum = first ? term : sum.add(term);
                first = false;
            }
            output[row] = sum;
        }
        return List.of(output);
    }

    private List<ExactRational> specialized(List<ExactRational> input) {
        if (!family.startsWith("center")) return raw(input);
        var sum = input.getFirst();
        for (int i = 1; i < input.size(); i++) sum = sum.add(input.get(i));
        var mean = sum.divide(ExactRational.integer(input.size()));
        var output = new ExactRational[input.size()];
        for (int i = 0; i < output.length; i++) output[i] = input.get(i).subtract(mean);
        return List.of(output);
    }

    private static volatile Object consumed;

    private void measure(String arm, int count, Supplier<Object> operation) {
        for (int round = 0; round < 5; round++)
            for (int i = 0; i < count; i++) consumed = operation.get();
        var bean = ManagementFactory.getThreadMXBean();
        var allocation = bean instanceof com.sun.management.ThreadMXBean extended ? extended : null;
        if (allocation != null && allocation.isThreadAllocatedMemorySupported()
                && !allocation.isThreadAllocatedMemoryEnabled()) allocation.setThreadAllocatedMemoryEnabled(true);
        long thread = Thread.currentThread().threadId();
        for (int round = 0; round < 7; round++) {
            long beforeBytes = allocation == null ? -1 : allocation.getThreadAllocatedBytes(thread);
            long start = System.nanoTime();
            for (int i = 0; i < count; i++) consumed = operation.get();
            long elapsed = System.nanoTime() - start;
            long afterBytes = allocation == null ? -1 : allocation.getThreadAllocatedBytes(thread);
            double bytes = beforeBytes < 0 || afterBytes < 0 ? -1 : (double) (afterBytes - beforeBytes) / count;
            System.out.println(String.join("\t", family, arm, Integer.toString(round), Integer.toString(count),
                Double.toString((double) elapsed / count), Double.toString(bytes)));
        }
    }

    public static void main(String[] args) {
        boolean selection = args.length == 0 || args[0].equals("selection");
        System.out.println(selection
            ? "family\tpredictor\tcorrection_width\tselected_ops\tdirect_ops\tacquisition_work\tfresh_verification_work\tapplication_work"
            : "family\tarm\tround\tcalls\tns_per_call\tallocated_bytes_per_call");
        var families = selection ? List.of("center8", "center16", "diagonal8", "dense8") : List.of(args[1]);
        for (var family : families) {
            var study = new OperatorResidualStudy();
            study.family = family;
            study.setup();
            if (selection) {
                var application = OperatorPlanSdk.apply(study.selected, study.samples.getFirst(), BUDGET, CancellationToken.NONE);
                System.out.println(String.join("\t", family, study.selected.predictor().name(),
                    Integer.toString(study.selected.correctionWidth()), Long.toString(study.selected.cost().total()),
                    Long.toString(study.selected.directCost().total()), Long.toString(study.acquisitionWork),
                    Long.toString(study.verificationWork), Long.toString(application.work())));
            } else {
                switch (args[2]) {
                    case "acquire" -> study.measure("acquire", 20, study::acquire);
                    case "selected" -> study.measure("selected", 2000, study::selected);
                    case "directChecked" -> study.measure("directChecked", 2000, study::directChecked);
                    case "directArithmetic" -> study.measure("directArithmetic", 2000, study::directArithmetic);
                    case "specializedArithmetic" -> study.measure("specializedArithmetic", 2000, study::specializedArithmetic);
                    default -> throw new IllegalArgumentException("unknown arm");
                }
            }
        }
    }
}
