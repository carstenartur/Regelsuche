package de.regelsuche.experiments.autopilot;

import java.nio.file.Path;

/** Command-line entry point for the pinned target-free generation slice. */
public final class AutonomousProductionGenerationMain {
    private AutonomousProductionGenerationMain() {
    }

    public static void main(String[] args) {
        Path output = args.length > 0
            ? Path.of(args[0])
            : Path.of("build", "reports", "autopilot-production-generation");
        int parallelism = args.length > 1
            ? Math.max(1, Integer.parseInt(args[1]))
            : Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors()));
        AutonomousProductionGenerationRunner runner =
            new AutonomousProductionGenerationRunner();
        var run = runner.runPinned(parallelism);
        runner.write(output, run);
        System.out.println(run.contentHash());
    }
}
