package de.regelsuche.quality.jmh;

import java.nio.file.Path;

/** Checkout-local command-line adapter for deterministic JMH history output. */
public final class JmhHistoryMain {
    private JmhHistoryMain() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 3) {
            throw new IllegalArgumentException(
                "expected <history-policy> <regression-policy> <output-directory>"
            );
        }
        JmhHistory history = new JmhHistoryLoader().load(
            Path.of(arguments[0]),
            Path.of(arguments[1])
        );
        Path output = Path.of(arguments[2]);
        new JmhHistoryReportWriter().write(history, output);
        System.out.println("jmhHistoryStatus=PASSED");
        System.out.println("jmhHistorySnapshots=" + history.snapshots().size());
        System.out.println("jmhHistoryBenchmarks=" + history.benchmarks().size());
        System.out.println("jmhHistoryReport=" + output.resolve("history.json"));
    }
}
