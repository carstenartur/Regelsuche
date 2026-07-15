package de.regelsuche.experiments.autopilot;

import java.nio.file.Path;

/** Command-line entry point for the complete downstream production lifecycle. */
public final class AutonomousProductionLifecycleMain {
    private AutonomousProductionLifecycleMain() {
    }

    public static void main(String[] args) {
        Path output = args.length > 0
            ? Path.of(args[0])
            : Path.of("build", "reports", "autopilot-production-lifecycle");
        int parallelism = args.length > 1
            ? Math.max(1, Integer.parseInt(args[1]))
            : Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors()));
        AutonomousProductionLifecycleRunner runner =
            new AutonomousProductionLifecycleRunner();
        var run = runner.runPinned(parallelism);
        runner.write(output, run);
        System.out.println(run.contentHash());
    }
}
