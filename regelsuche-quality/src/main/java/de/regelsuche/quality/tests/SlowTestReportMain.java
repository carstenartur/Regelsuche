package de.regelsuche.quality.tests;

import java.nio.file.Path;

/** Checkout-local command adapter for deterministic slow-test evidence. */
public final class SlowTestReportMain {
    private SlowTestReportMain() {
    }

    public static void main(String[] arguments) throws Exception {
        Options options = Options.parse(arguments);
        Path root = options.root().toAbsolutePath().normalize();
        Path jsonOutput = resolve(root, options.jsonOutput());
        Path markdownOutput = resolve(root, options.markdownOutput());
        SlowTestReport report = new SlowTestReportGenerator().write(
            root,
            options.limit(),
            options.slowSeconds(),
            jsonOutput,
            markdownOutput
        );
        System.out.println("testCount=" + report.testCount());
        System.out.println("slowTestCount=" + report.slowTestCount());
        System.out.println("slowTestReport=" + jsonOutput);
    }

    private static Path resolve(Path root, Path value) {
        return (value.isAbsolute() ? value : root.resolve(value))
            .toAbsolutePath()
            .normalize();
    }

    private record Options(
        Path root,
        int limit,
        double slowSeconds,
        Path jsonOutput,
        Path markdownOutput
    ) {
        private static Options parse(String[] arguments) {
            Path root = Path.of(".");
            int limit = SlowTestReportGenerator.DEFAULT_LIMIT;
            double slowSeconds = SlowTestReportGenerator.DEFAULT_SLOW_SECONDS;
            Path jsonOutput = SlowTestReportGenerator.DEFAULT_JSON_OUTPUT;
            Path markdownOutput =
                SlowTestReportGenerator.DEFAULT_MARKDOWN_OUTPUT;

            for (int index = 0; index < arguments.length; index += 2) {
                if (index + 1 >= arguments.length) {
                    throw invalid(
                        "missing value for option " + arguments[index]
                    );
                }
                String option = arguments[index];
                String value = arguments[index + 1];
                switch (option) {
                    case "--root" -> root = Path.of(value);
                    case "--limit" -> limit = parseInt(option, value);
                    case "--slow-seconds" ->
                        slowSeconds = parseDouble(option, value);
                    case "--json-output" -> jsonOutput = Path.of(value);
                    case "--markdown-output" ->
                        markdownOutput = Path.of(value);
                    default -> throw invalid("unknown option " + option);
                }
            }
            return new Options(
                root,
                limit,
                slowSeconds,
                jsonOutput,
                markdownOutput
            );
        }

        private static int parseInt(String option, String value) {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException exception) {
                throw invalid(option + " must be an integer: " + value);
            }
        }

        private static double parseDouble(String option, String value) {
            try {
                return Double.parseDouble(value);
            } catch (NumberFormatException exception) {
                throw invalid(option + " must be numeric: " + value);
            }
        }

        private static IllegalArgumentException invalid(String message) {
            return new IllegalArgumentException(
                "slow-test report failed: " + message
            );
        }
    }
}
