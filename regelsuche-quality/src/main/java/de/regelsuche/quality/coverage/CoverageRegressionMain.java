package de.regelsuche.quality.coverage;

import com.fasterxml.jackson.core.json.JsonWriteFeature;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.core.util.Separators;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** CLI used by the local Gradle gate and by split CI after coverage converges. */
public final class CoverageRegressionMain {
    private static final String USAGE = "Usage: CoverageRegressionMain [--root PATH] [--policy PATH] "
        + "[--json-output PATH] [--markdown-output PATH]";
    private static final String JSON_OUTPUT = "build/reports/quality/coverage-report.json";
    private static final String MARKDOWN_OUTPUT = "build/reports/quality/coverage-report.md";
    private static final DefaultIndenter INDENT = new DefaultIndenter("  ", "\n");
    private static final ObjectWriter JSON = JsonMapper.builder()
        .enable(JsonWriteFeature.ESCAPE_NON_ASCII)
        .disable(JsonWriteFeature.WRITE_HEX_UPPER_CASE)
        .build().writer(new DefaultPrettyPrinter(Separators.createDefaultInstance()
            .withObjectFieldValueSpacing(Separators.Spacing.AFTER)
            .withObjectEmptySeparator("").withArrayEmptySeparator(""))
            .withObjectIndenter(INDENT).withArrayIndenter(INDENT));

    private CoverageRegressionMain() { }

    public static void main(String[] args) {
        int code = run(args, System.out, System.err);
        if (code != 0) {
            System.exit(code);
        }
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length == 1 && ("--help".equals(args[0]) || "-h".equals(args[0]))) {
            out.println(USAGE);
            return 0;
        }
        Options options;
        try {
            options = Options.parse(args);
        } catch (IllegalArgumentException error) {
            err.println(USAGE);
            err.println("coverage gate failed: " + error.getMessage());
            return 2;
        }
        Path root = null;
        List<Path> inputs = null;
        try {
            root = options.root().toRealPath();
            Path policy = root.resolve(options.policy()).normalize();
            List<Path> reports = CoverageRegression.reportPaths(root);
            inputs = new ArrayList<>(reports);
            inputs.add(policy);
            if (!policy.startsWith(root)) {
                throw new IOException("policy must be relative to the repository root: " + policy);
            }
            Path json = root.resolve(options.jsonOutput()).normalize();
            Path markdown = root.resolve(options.markdownOutput()).normalize();
            if (samePath(json, markdown)) {
                throw new IOException("JSON and Markdown must have distinct output paths");
            }
            requireSafeOutput(root, json, inputs);
            requireSafeOutput(root, markdown, inputs);
            CoverageRegression.Result result = CoverageRegression.evaluate(root, policy, reports);
            if (!result.violations().isEmpty()) {
                clearCanonicalReports(root, inputs, List.of(json, markdown), err);
            }
            publish(json, JSON.writeValueAsString(result.data()) + "\n", markdown, result.markdown());
            out.println("coverageStatus=" + result.status());
            out.println("aggregateLinePercent=" + result.linePercent());
            out.println("aggregateBranchPercent=" + result.branchPercent());
            out.println("coverageReport=" + json);
            for (String violation : result.violations()) {
                err.println("coverageViolation=" + violation);
            }
            return result.violations().isEmpty() ? 0 : 1;
        } catch (IOException | IllegalArgumentException error) {
            err.println("coverage gate failed: " + error.getMessage());
            if (inputs != null) {
                clearCanonicalReports(root, inputs, List.of(), err);
            }
            return 1;
        }
    }

    private static void requireSafeOutput(Path root, Path output, List<Path> inputs) throws IOException {
        if (output.equals(root.resolve(JSON_OUTPUT)) || output.equals(root.resolve(MARKDOWN_OUTPUT))) {
            for (Path parent = output; !parent.equals(root); parent = parent.getParent()) {
                if (Files.isSymbolicLink(parent)) {
                    throw new IOException("canonical coverage output uses a symbolic link: " + parent);
                }
            }
        }
        for (Path input : inputs) {
            if (samePath(output, input)) {
                throw new IOException("coverage output aliases an input: " + output);
            }
        }
    }

    private static boolean samePath(Path first, Path second) throws IOException {
        return first.equals(second) || Files.exists(first) && Files.exists(second)
            && Files.isSameFile(first, second);
    }

    private static void clearCanonicalReports(Path root, List<Path> inputs, List<Path> retained, PrintStream err) {
        for (Path output : List.of(root.resolve(JSON_OUTPUT), root.resolve(MARKDOWN_OUTPUT))) {
            if (retained.contains(output)) {
                continue;
            }
            try {
                requireSafeOutput(root, output, inputs);
                if (Files.isRegularFile(output, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(output)) {
                    Files.deleteIfExists(output);
                }
            } catch (IOException error) {
                err.println("cannot invalidate canonical coverage report: " + error.getMessage());
            }
        }
    }

    private static void publish(Path json, String jsonText, Path markdown, String markdownText) throws IOException {
        Path stagedJson = stage(json, jsonText);
        try {
            Path stagedMarkdown = stage(markdown, markdownText);
            try {
                Files.move(stagedMarkdown, markdown, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                Files.move(stagedJson, json, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(stagedMarkdown);
            }
        } finally {
            Files.deleteIfExists(stagedJson);
        }
    }

    private static Path stage(Path output, String content) throws IOException {
        Files.createDirectories(output.getParent());
        Path staged = Files.createTempFile(output.getParent(), ".coverage-", ".tmp");
        try {
            Files.writeString(staged, content);
            return staged;
        } catch (IOException error) {
            try {
                Files.deleteIfExists(staged);
            } catch (IOException cleanup) {
                error.addSuppressed(cleanup);
            }
            throw error;
        }
    }

    private record Options(Path root, Path policy, Path jsonOutput, Path markdownOutput) {
        private static final Map<String, String> DEFAULTS = Map.of(
            "--root", ".", "--policy", "config/quality/coverage-policy.json",
            "--json-output", JSON_OUTPUT, "--markdown-output", MARKDOWN_OUTPUT);

        private static Options parse(String[] args) {
            Map<String, String> values = new HashMap<>(DEFAULTS);
            for (int index = 0; index < args.length; index += 2) {
                String option = args[index];
                if (!values.containsKey(option) || index + 1 == args.length || args[index + 1].startsWith("--")) {
                    throw new IllegalArgumentException("invalid option " + option);
                }
                values.put(option, args[index + 1]);
            }
            return new Options(Path.of(values.get("--root")), Path.of(values.get("--policy")),
                Path.of(values.get("--json-output")), Path.of(values.get("--markdown-output")));
        }
    }
}
