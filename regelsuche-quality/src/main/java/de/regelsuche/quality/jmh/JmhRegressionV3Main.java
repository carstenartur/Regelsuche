package de.regelsuche.quality.jmh;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Java/JUnit-friendly entrypoint for replaying the exact v3 JMH regression
 * policy without a host Python interpreter.
 *
 * <p>This is a parallel qualification candidate, not yet the live CI
 * regression authority. Non-PASSED outcomes retain their reports and fail.
 */
public final class JmhRegressionV3Main {
    private JmhRegressionV3Main() { }

    public static void main(String[] args) throws IOException {
        if (args.length != 6) {
            throw new IllegalArgumentException(
                "expected <repository root> <JMH results> <v2 thresholds> "
                    + "<v3 decision policy> <JSON output> <Markdown output>"
            );
        }
        JmhRegressionV3Verifier.Result result =
            new JmhRegressionV3Verifier().verify(
                Path.of(args[0]), Path.of(args[1]), Path.of(args[2]),
                Path.of(args[3])
            );
        result.write(Path.of(args[4]), Path.of(args[5]));
        System.out.println("jmhRegressionStatus=" + result.status());
        System.out.println("jmhRegressionBenchmarks="
            + result.json().path("benchmarkCount").asInt());
        System.out.println("jmhRegressionLowPrecision="
            + result.json().path("lowPrecisionCount").asInt());
        System.out.println("jmhRegressionInconclusive="
            + result.json().path("inconclusiveCount").asInt());
        System.out.println("jmhRegressionReport=" + args[4]);
        if (!"PASSED".equals(result.status())) {
            throw new IllegalStateException(
                "JMH regression v3 is " + result.status()
                    + "; retained report: " + args[4]
            );
        }
    }
}
