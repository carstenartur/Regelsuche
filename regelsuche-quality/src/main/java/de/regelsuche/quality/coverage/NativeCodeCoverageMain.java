package de.regelsuche.quality.coverage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Converts measured coverage and binds native uploads to the actual producer and PR/default-branch source. */
public final class NativeCodeCoverageMain {
    private static final ObjectMapper JSON = new ObjectMapper();
    private NativeCodeCoverageMain() { }

    public static void main(String[] arguments) throws Exception {
        System.exit(run(arguments, System.getenv(), new NativeCodeCoverageClient()));
    }

    static int run(String[] arguments, Map<String, String> env, NativeCodeCoverageClient client) throws Exception {
        Map<String, String> options = new LinkedHashMap<>();
        Set<String> names = Set.of("--root", "--gradle-source", "--sympy-source", "--output");
        for (int i = 0; i < arguments.length; i += 2) {
            if (i + 1 == arguments.length || !names.contains(arguments[i]) || options.putIfAbsent(arguments[i], arguments[i + 1]) != null)
                throw new IllegalArgumentException("Expected distinct --root, --gradle-source, --sympy-source and --output arguments");
        }
        Path root = Path.of(required(options, "--root")).toAbsolutePath().normalize();
        Path output = root.resolve(required(options, "--output")).normalize();
        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("schema", "regelsuche.quality.native-coverage/v1");
        String token = env.getOrDefault("GITHUB_TOKEN", "");
        try {
            String eventName = required(env, "GITHUB_EVENT_NAME");
            receipt.put("event", eventName);
            if (!eventName.equals("pull_request") && !eventName.equals("push"))
                return finish(output, receipt, "SKIPPED", "Unsupported coverage event: " + eventName, token, 0);
            String repository = required(env, "GITHUB_REPOSITORY");
            receipt.put("repository", repository);
            JsonNode event = JSON.readTree(Path.of(required(env, "GITHUB_EVENT_PATH")).toFile());
            if (event == null || !repository.equalsIgnoreCase(event.path("repository").path("full_name").asText()))
                throw new IOException("Event repository does not match the upload repository");
            Long pr = null;
            String ref = null;
            String commit;
            if (eventName.equals("pull_request")) {
                JsonNode request = event.path("pull_request");
                String headRepository = request.path("head").path("repo").path("full_name").asText();
                if (headRepository.isBlank()) throw new IOException("Missing PR head repository");
                if (!repository.equalsIgnoreCase(headRepository))
                    return finish(output, receipt, "SKIPPED", "Fork PR coverage cannot be uploaded", token, 0);
                if (!request.path("number").isIntegralNumber() || !request.path("number").canConvertToLong() || request.path("number").asLong() <= 0)
                    throw new IOException("Missing or invalid PR number");
                pr = request.path("number").asLong();
                commit = hash(request.path("head").path("sha").asText());
                receipt.put("pullRequest", pr);
            } else {
                String branch = event.path("repository").path("default_branch").asText();
                if (branch.isBlank()) throw new IOException("Missing default branch");
                ref = required(env, "GITHUB_REF");
                if (!ref.equals("refs/heads/" + branch))
                    return finish(output, receipt, "SKIPPED", "Push is not for the default branch", token, 0);
                commit = hash(required(env, "GITHUB_SHA"));
                receipt.put("ref", ref);
            }
            required(env, "GITHUB_TOKEN");
            String measuredCommit = git(root, "rev-parse", "--verify", "HEAD");
            String measuredTree = git(root, "rev-parse", "--verify", "HEAD^{tree}");
            if (!measuredCommit.equals(hash(required(env, "GITHUB_SHA")))) throw new IOException("Checkout does not match measured workflow commit");
            for (String producer : List.of("--gradle-source", "--sympy-source")) {
                List<String> source = Files.readAllLines(root.resolve(required(options, producer)));
                if (!source.equals(List.of(measuredCommit, measuredTree))) throw new IOException("Coverage producer source mismatch: " + producer);
                receipt.put(producer.substring(2), source);
            }
            String headTree = git(root, "rev-parse", "--verify", commit + "^{tree}");
            if (!measuredTree.equals(headTree)) throw new IOException("Measured tree differs from upload head tree");
            receipt.put("measuredCommit", measuredCommit); receipt.put("measuredTree", measuredTree);
            receipt.put("commit", commit); receipt.put("headTree", headTree);
            verifyInputs(root);
            var exported = JacocoCoberturaExporter.export(root, root.resolve("config/quality/coverage-policy.json"),
                root.resolve("build/reports/quality/coverage-report.json"), output);
            verifyInputs(root);
            receipt.put("reportSha256", exported.reportSha256()); receipt.put("coveredLines", exported.coveredLines());
            receipt.put("validLines", exported.validLines()); receipt.put("manifest", exported.manifest().toString());
            var uploaded = client.upload(URI.create(env.getOrDefault("GITHUB_API_URL", "https://api.github.com")), repository,
                token, commit, pr, ref, Files.readAllBytes(exported.report()));
            receipt.put("upload", uploaded);
            return finish(output, receipt, uploaded.outcome(), uploaded.detail(), token, uploaded.outcome().equals("PROCESSED") ? 0 : 1);
        } catch (Exception exception) {
            if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
            return finish(output, receipt, "FAILED", exception.getMessage(), token, 1);
        }
    }

    private static int finish(Path output, Map<String, Object> receipt, String outcome, String detail, String token, int exit) throws IOException {
        receipt.put("outcome", outcome); receipt.put("nativeProcessingSucceeded", outcome.equals("PROCESSED"));
        if (detail != null) receipt.put(exit == 0 ? "reason" : "error", detail);
        String json = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(receipt);
        if (!token.isEmpty()) {
            String encodedToken = JSON.writeValueAsString(token);
            json = json.replace(encodedToken.substring(1, encodedToken.length() - 1), "[redacted]");
            if (detail != null) detail = detail.replace(token, "[redacted]");
        }
        Files.createDirectories(output);
        Files.writeString(output.resolve("native-coverage-outcome.json"), json + "\n");
        if (exit != 0) System.err.println("Native coverage " + outcome + ": " + detail);
        return exit;
    }

    private static String required(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing native coverage input: " + key);
        return value;
    }
    private static void verifyInputs(Path root) throws IOException, InterruptedException {
        git(root, "diff", "--quiet", "HEAD", "--", "*.java", "config");
        if (!git(root, "ls-files", "--others", "--", ":(glob)*/src/main/java/**").isEmpty())
            throw new IOException("Untracked Java inputs are absent from measured source");
    }
    private static String hash(String value) throws IOException {
        if (!value.matches("[0-9a-f]{40}")) throw new IOException("Invalid source commit SHA");
        return value;
    }
    private static String git(Path root, String... arguments) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("git", "-C", root.toString())); command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try {
            if (!process.waitFor(15, TimeUnit.SECONDS)) throw new IOException("Timed out resolving coverage source");
            String text = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            if (process.exitValue() != 0) throw new IOException(arguments[0].equals("diff")
                ? "Tracked Java/config inputs differ from measured source" : "Cannot resolve coverage source: " + text);
            return text;
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }
}
