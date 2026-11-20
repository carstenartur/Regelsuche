package de.regelsuche.benchmark;

import static org.junit.jupiter.api.Assertions.assertAll;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.regelsuche.quality.jmh.JmhAllocationReportMain;
import de.regelsuche.quality.jmh.JmhPublicationMain;
import de.regelsuche.quality.jmh.JmhRegressionV3Main;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.profile.GCProfiler;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

/** The measurement JVM uses the product classpath and its own uninstrumented forks. */
class ApplicationBenchmarksIT {
    private static final Path ROOT = Path.of(System.getProperty("regelsuche.repositoryRoot"));
    private static final Path OUTPUT = Path.of(System.getProperty("regelsuche.jmh.output"));
    private static final Path POLICY = ROOT.resolve("config/quality/jmh-regression-policy-more-warmup-v1.json");
    private static final String ALL = "de\\.regelsuche\\.benchmark\\..*";
    private static final String ALLOCATION = ALL + "\\.(directAstRewriteSource|preparedAstRewriteSource|"
        + "referenceFixedWorkSearch|preparedFixedWorkSearch|referenceTargetedSearch|preparedTargetedSearch)$";

    @Test
    void measureLatencyAndApplyBothPublishedGates() throws Exception {
        Path raw = OUTPUT.resolve("latency/result.json");
        Files.createDirectories(raw.getParent());
        Files.deleteIfExists(raw);
        JsonNode execution = new ObjectMapper().readTree(POLICY.toFile()).required("execution");
        var options = new OptionsBuilder().include(ALL).mode(Mode.AverageTime)
            .forks(execution.required("forks").intValue())
            .warmupIterations(execution.required("warmupIterations").intValue())
            .measurementIterations(execution.required("measurementIterations").intValue())
            .warmupTime(TimeValue.fromString(execution.required("warmupTime").textValue()))
            .measurementTime(TimeValue.fromString(execution.required("measurementTime").textValue()))
            .shouldFailOnError(true).jvmArgs().resultFormat(ResultFormatType.JSON).result(raw.toString());
        new Runner(options.build()).run();

        assertAll("independent decisions over the original latency measurement",
            () -> JmhRegressionV3Main.main(new String[]{ROOT.toString(), raw.toString(), POLICY.toString(),
                ROOT.resolve("config/quality/jmh-regression-decision-policy-more-warmup-v1.json").toString(),
                OUTPUT.resolve("jmh-regression-report.json").toString(), OUTPUT.resolve("jmh-regression-report.md").toString()}),
            () -> JmhPublicationMain.main(new String[]{raw.toString(),
                ROOT.resolve("config/quality/jmh-baseline-more-warmup-v1.json").toString(),
                OUTPUT.resolve("badge.json").toString(), OUTPUT.resolve("index.html").toString()}));

    }

    @Test
    void measureNormalizedAllocationForAllSixDeclaredBenchmarks() throws Exception {
        Path raw = OUTPUT.resolve("allocation/result.json");
        Files.createDirectories(raw.getParent());
        Files.deleteIfExists(raw);
        var options = new OptionsBuilder().include(ALLOCATION).mode(Mode.AverageTime)
            .timeUnit(TimeUnit.MILLISECONDS).forks(1).warmupIterations(2).measurementIterations(3)
            .warmupTime(TimeValue.seconds(1)).measurementTime(TimeValue.seconds(1)).addProfiler(GCProfiler.class)
            .shouldFailOnError(true).jvmArgs().resultFormat(ResultFormatType.JSON).result(raw.toString());
        new Runner(options.build()).run();
        JmhAllocationReportMain.main(new String[]{raw.toString(), OUTPUT.resolve("allocation.json").toString()});
    }
}
