package de.regelsuche.benchmark;

import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.egraph.EClassId;
import de.regelsuche.egraph.EGraph;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.TransformationEngine;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Micro-benchmarks for hot paths exercised by every search run.
 *
 * <p>These benchmarks cover three independent layers:
 * <ul>
 *   <li>{@link ExpressionCanonicalizer} – called for every visited state.</li>
 *   <li>{@link EGraph#rebuild()} – congruence closure after rule application.</li>
 *   <li>{@link AstRewriteTransformationEngine#transform(String)} – atomic
 *       rewrite expansion.</li>
 * </ul>
 *
 * <p>Run locally: {@code ./gradlew :app:jmh}. Results land in
 * {@code app/build/reports/jmh/result.json} and are auto-published by CI.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
public class CoreBenchmarks {

    private ExpressionCanonicalizer canonicalizer;
    private TransformationEngine engine;
    private ExpressionParser parser;
    private String binomialExpression;
    private String mediumExpression;

    @Setup
    public void setup() {
        canonicalizer = new ExpressionCanonicalizer();
        engine = new AstRewriteTransformationEngine();
        parser = new ExpressionParser();
        binomialExpression = "(a + b) * (a + b)";
        mediumExpression = "((x + 1) * (x + 2)) + (x * (x + 3))";
    }

    @Benchmark
    public String canonicalizeBinomial() {
        return canonicalizer.canonicalize(binomialExpression);
    }

    @Benchmark
    public String canonicalizeMedium() {
        return canonicalizer.canonicalize(mediumExpression);
    }

    @Benchmark
    public int rewriteApplyAllBinomial() {
        return engine.transform(binomialExpression).size();
    }

    @Benchmark
    public int rewriteApplyAllMedium() {
        return engine.transform(mediumExpression).size();
    }

    @Benchmark
    public int egraphRebuildSmall() {
        EGraph graph = new EGraph();
        EClassId left = graph.addExpression(parser.parseTerm("a + b"));
        EClassId right = graph.addExpression(parser.parseTerm("b + a"));
        graph.union(left, right);
        graph.rebuild();
        return graph.classCount();
    }

    @Benchmark
    public int egraphAddAndRebuildMedium() {
        EGraph graph = new EGraph();
        List<String> exprs = List.of(
            "(a + b) * c",
            "c * (a + b)",
            "(a * c) + (b * c)",
            "(b * c) + (a * c)"
        );
        EClassId first = null;
        for (String expr : exprs) {
            EClassId id = graph.addExpression(parser.parseTerm(expr));
            if (first == null) {
                first = id;
            } else {
                graph.union(first, id);
            }
        }
        graph.rebuild();
        return graph.classCount();
    }
}
