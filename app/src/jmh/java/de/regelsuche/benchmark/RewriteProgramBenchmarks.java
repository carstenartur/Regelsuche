package de.regelsuche.benchmark;

import static de.regelsuche.search.program.RewritePrograms.byEstimatedCostThenRule;
import static de.regelsuche.search.program.RewritePrograms.choice;
import static de.regelsuche.search.program.RewritePrograms.equivalencePreserving;
import static de.regelsuche.search.program.RewritePrograms.firstApplicable;
import static de.regelsuche.search.program.RewritePrograms.prioritize;
import static de.regelsuche.search.program.RewritePrograms.prune;
import static de.regelsuche.search.program.RewritePrograms.repeat;
import static de.regelsuche.search.program.RewritePrograms.require;
import static de.regelsuche.search.program.RewritePrograms.sequence;
import static de.regelsuche.search.program.RewritePrograms.source;

import de.regelsuche.search.program.ProgrammedTransformationEngine;
import de.regelsuche.search.program.RewriteExecution;
import de.regelsuche.search.program.RewriteProgram;
import de.regelsuche.search.program.RewriteProgramInterpreter;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.transform.Transformation;
import de.regelsuche.transform.TransformationEngine;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/**
 * Baseline measurements for the Java-internal rewrite-program interpreter.
 *
 * <p>The synthetic cases isolate topology dispatch and intermediate candidate
 * composition. The adapter cases additionally include conversion back into the
 * ordinary {@code TransformationEngine} boundary used by production search.
 * The AST cases compare the same real transformation engine directly, through
 * the interpreter and through the production adapter, so program overhead can
 * be separated from parsing, matching and rewrite construction. All published
 * series use milliseconds per operation; smaller is better.</p>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
public class RewriteProgramBenchmarks {
    private static final String SINGLE_INPUT = "x+0";
    private static final String SEQUENCE_INPUT = "x+0";
    private static final String REPEAT_INPUT = "x+0+0+0";
    private static final String AST_INPUT =
        "((x + 1) * (x + 2)) + (x * (x + 3))";

    private RewriteProgramInterpreter interpreter;
    private TransformationEngine syntheticSingleEngine;
    private TransformationEngine astEngine;
    private TransformationEngine programmedSingleEngine;
    private TransformationEngine programmedAstEngine;
    private RewriteProgram singleSource;
    private RewriteProgram sequenceProgram;
    private RewriteProgram choiceProgram;
    private RewriteProgram firstApplicableProgram;
    private RewriteProgram repeatProgram;
    private RewriteProgram requireProgram;
    private RewriteProgram prioritizeProgram;
    private RewriteProgram pruneProgram;
    private RewriteProgram nestedProgram;
    private RewriteProgram astProgram;

    @Setup
    public void setup() {
        interpreter = new RewriteProgramInterpreter();

        syntheticSingleEngine = exactRewrite(
            "remove_add_zero", "x+0", "x", -1);
        TransformationEngine firstSequenceStep = exactRewrite(
            "normalize_before_cleanup", "x+0", "x*1", 0);
        TransformationEngine secondSequenceStep = exactRewrite(
            "remove_multiply_one", "x*1", "x", -1);
        TransformationEngine alternativeEngine = exactRewrite(
            "commute_zero", "x+0", "0+x", 1);
        TransformationEngine emptyEngine = expression -> List.of();
        TransformationEngine repeatEngine = trailingZeroRewrite();

        singleSource = source("single-source", syntheticSingleEngine);
        sequenceProgram = sequence(
            "two-step-sequence",
            source("normalize", firstSequenceStep),
            source("cleanup", secondSequenceStep));
        choiceProgram = choice(
            "two-alternative-choice",
            source("remove-zero", syntheticSingleEngine),
            source("commute-zero", alternativeEngine));
        firstApplicableProgram = firstApplicable(
            "second-alternative-applies",
            source("empty", emptyEngine),
            source("remove-zero", syntheticSingleEngine));
        repeatProgram = repeat(
            "remove-three-trailing-zeroes",
            1,
            3,
            source("remove-one-zero", repeatEngine));
        requireProgram = require(
            "equivalence-filter",
            choiceProgram,
            "equivalence preserving by construction",
            equivalencePreserving());
        prioritizeProgram = prioritize(
            "estimated-cost-order",
            choiceProgram,
            "estimated cost, then rule id",
            byEstimatedCostThenRule());
        pruneProgram = prune(
            "retain-one",
            prioritizeProgram,
            1,
            "benchmark candidate ceiling");
        nestedProgram = prune(
            "nested-bounded-program",
            prioritize(
                "nested-priority",
                require(
                    "nested-require",
                    firstApplicable(
                        "nested-first-applicable",
                        source("nested-empty", emptyEngine),
                        choice(
                            "nested-choice",
                            sequence(
                                "nested-sequence",
                                source("nested-normalize", firstSequenceStep),
                                source("nested-cleanup", secondSequenceStep)),
                            source("nested-alternative", alternativeEngine))),
                    "equivalence preserving by construction",
                    equivalencePreserving()),
                "estimated cost, then rule id",
                byEstimatedCostThenRule()),
            1,
            "benchmark candidate ceiling");

        astEngine = new AstRewriteTransformationEngine();
        astProgram = source("ordinary-ast-rules", astEngine);
        programmedSingleEngine = new ProgrammedTransformationEngine(singleSource);
        programmedAstEngine = new ProgrammedTransformationEngine(astProgram);
    }

    @Benchmark
    public int directSyntheticSource() {
        return syntheticSingleEngine.transform(SINGLE_INPUT).size();
    }

    @Benchmark
    public int interpretedSingleSource() {
        return candidateCount(singleSource, SINGLE_INPUT);
    }

    @Benchmark
    public int programmedSingleSource() {
        return programmedSingleEngine.transform(SINGLE_INPUT).size();
    }

    @Benchmark
    public int interpretedSequenceTwoSteps() {
        return candidateCount(sequenceProgram, SEQUENCE_INPUT);
    }

    @Benchmark
    public int interpretedChoiceTwoAlternatives() {
        return candidateCount(choiceProgram, SINGLE_INPUT);
    }

    @Benchmark
    public int interpretedFirstApplicableSecondAlternative() {
        return candidateCount(firstApplicableProgram, SINGLE_INPUT);
    }

    @Benchmark
    public int interpretedRepeatThreeIterations() {
        return candidateCount(repeatProgram, REPEAT_INPUT);
    }

    @Benchmark
    public int interpretedRequireFilter() {
        return candidateCount(requireProgram, SINGLE_INPUT);
    }

    @Benchmark
    public int interpretedPrioritizeTwoCandidates() {
        return candidateCount(prioritizeProgram, SINGLE_INPUT);
    }

    @Benchmark
    public int interpretedPruneTwoCandidatesToOne() {
        return candidateCount(pruneProgram, SINGLE_INPUT);
    }

    @Benchmark
    public int interpretedNestedControlFlow() {
        return candidateCount(nestedProgram, SEQUENCE_INPUT);
    }

    @Benchmark
    public int directAstRewriteSource() {
        return astEngine.transform(AST_INPUT).size();
    }

    @Benchmark
    public int interpretedAstRewriteSource() {
        return candidateCount(astProgram, AST_INPUT);
    }

    @Benchmark
    public int programmedAstRewriteSource() {
        return programmedAstEngine.transform(AST_INPUT).size();
    }

    private int candidateCount(RewriteProgram program, String input) {
        RewriteExecution result = interpreter.execute(program, input);
        return result.candidates().size() + (result.complete() ? 0 : 1_000_000);
    }

    private static TransformationEngine exactRewrite(
        String ruleId,
        String expectedInput,
        String output,
        int estimatedCostDelta
    ) {
        return expression -> expression.equals(expectedInput)
            ? List.of(transformation(
                ruleId, expression, output, estimatedCostDelta))
            : List.of();
    }

    private static TransformationEngine trailingZeroRewrite() {
        return expression -> expression.endsWith("+0")
            ? List.of(transformation(
                "remove_trailing_zero",
                expression,
                expression.substring(0, expression.length() - 2),
                -1))
            : List.of();
    }

    private static Transformation transformation(
        String ruleId,
        String input,
        String output,
        int estimatedCostDelta
    ) {
        return new Transformation(
            ruleId,
            output,
            RewriteKind.SIMPLIFY,
            estimatedCostDelta > 0,
            estimatedCostDelta,
            true,
            ruleId + ":" + input + "->" + output);
    }
}
