package de.regelsuche.release;

import java.nio.file.Path;
import java.util.Arrays;

/** Command-line entry point for release profile evaluation. */
public final class ReleaseReadinessMain {
    private ReleaseReadinessMain() {
    }

    public static void main(String[] args) {
        Path output = args.length > 0 && !args[0].startsWith("--")
            ? Path.of(args[0])
            : Path.of("build", "reports", "release-readiness");
        boolean requireReady = Arrays.asList(args).contains("--require-ready");
        Path hiddenRuleReport = optionPath(args, "--hidden-rule-report");
        ReleaseReadinessRunner runner = new ReleaseReadinessRunner();
        var run = runner.run(hiddenRuleReport);
        runner.write(output, run);
        System.out.println(run.contentHash());
        if (requireReady && !run.autonomousCampaignReady()) {
            throw new IllegalStateException(
                "AUTONOMOUS_CAMPAIGN release profile is BLOCKED: "
                    + run.matrix()
                        .result(ReleaseEvidenceProfile.AUTONOMOUS_CAMPAIGN)
                        .blockers());
        }
    }

    private static Path optionPath(String[] args, String option) {
        for (int index = 0; index < args.length; index++) {
            if (option.equals(args[index])) {
                if (index + 1 >= args.length
                        || args[index + 1].startsWith("--")) {
                    throw new IllegalArgumentException(
                        option + " requires a file path");
                }
                return Path.of(args[index + 1]);
            }
        }
        return null;
    }
}
