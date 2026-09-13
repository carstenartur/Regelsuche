package de.regelsuche.quality.supplychain;

import static de.regelsuche.quality.supplychain.SupplyChainJson.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Official executable provisioning is online; the separate scan always uses the retained local DB. */
final class PinnedOsvScanner {
    private PinnedOsvScanner() {}

    static Path obtain(Path root, JsonNode manifest, Path supplied) throws Exception {
        require(System.getProperty("os.name").toLowerCase(Locale.ROOT).equals("linux")
            && SetHolder.ARCHITECTURES.contains(System.getProperty("os.arch")),
            "this scanner pin supports Linux AMD64 only");
        Path binary;
        if (supplied != null) {
            binary = supplied.toAbsolutePath();
            require(Files.isRegularFile(binary, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(binary),
                "pre-provisioned scanner must be a regular file");
        } else {
            binary = checked(root, Path.of("build/tools/osv-scanner/" + text(manifest, "sha256").substring(7)
                + "/osv-scanner"));
            if (!Files.exists(binary)) download(root, manifest, binary, Duration.ofSeconds(120));
        }
        verifyBinary(manifest, binary);
        require(Files.isExecutable(binary), "pinned scanner is not executable");
        return binary;
    }

    static void download(Path root, JsonNode manifest, Path binary, Duration timeout) throws Exception {
        Files.createDirectories(binary.getParent());
        Path temporary = Files.createTempFile(binary.getParent(), "download-", ".tmp");
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL).build()) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(text(manifest, "url")))
                .timeout(timeout).GET().build();
            // The future completes only after the body has been written. An InputStream body handler
            // would finish at the response headers and leave a stalled body outside the deadline.
            var pending = client.sendAsync(request, responseInfo -> HttpResponse.BodySubscribers.limiting(
                HttpResponse.BodySubscribers.ofFile(temporary), integer(manifest, "bytes")));
            try {
                HttpResponse<Path> response = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                require(response.statusCode() == 200, "official scanner download failed: HTTP " + response.statusCode());
            } finally {
                if (!pending.isDone()) pending.cancel(true);
            }
            verifyBinary(manifest, temporary);
            require(temporary.toFile().setExecutable(true, true), "cannot set scanner executable permission");
            checked(root, binary);
            Files.move(temporary, binary, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    static void verifyBinary(JsonNode manifest, Path binary) throws IOException {
        require(Files.size(binary) == integer(manifest, "bytes") && hash(binary).equals(text(manifest, "sha256")),
            "scanner executable hash or size differs");
    }

    static ObjectNode scan(Path root, Path output, Path binary, VulnerabilityPolicy policy,
            AdvisorySnapshot snapshot, MavenScanInputs inputs) throws Exception {
        Path database = output.resolve("cache/osv-scalibr/Maven/all.zip");
        write(root, database, snapshot.archive());
        require(hash(database).equals(text(snapshot.manifest().path("archive"), "sha256")),
            "staged scanner database differs");
        write(root, output.resolve("osv-scanner.toml"), "# No exclusions or overrides.\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        List<String> command = new ArrayList<>(List.of(binary.toString(), "scan", "source", "--offline",
            "--offline-vulnerabilities", "--local-db-path", "cache", "--no-resolve", "--all-packages", "--all-vulns",
            "--config", "osv-scanner.toml", "--format", "json"));
        inputs.components().values().stream().sorted(java.util.Comparator.comparing(MavenScanInputs.Component::purl))
            .forEach(component -> command.addAll(List.of("-L", component.source())));
        ObjectNode execution = execute(root, output, command, Duration.ofSeconds(policy.timeoutSeconds()));
        verifyBinary(policy.scanner(), binary);
        inputs.verifyFiles(root, output);
        require(hash(file(root, database)).equals(text(snapshot.manifest().path("archive"), "sha256")),
            "scanner database changed during execution");
        return execution;
    }

    static ObjectNode execute(Path root, Path output, List<String> command, Duration timeout) throws Exception {
        Path stdout = checked(root, output.resolve("scanner.stdout.json"));
        Path stderr = checked(root, output.resolve("scanner.stderr.log"));
        ObjectNode result = JSON.createObjectNode();
        result.put("outcome", "NOT_STARTED").putNull("exitCode");
        result.put("startedAt", Instant.now().toString());
        result.put("timeoutMillis", timeout.toMillis());
        result.put("networkMode", "OSV_SCANNER_OFFLINE_LOCAL_DATABASE");
        result.set("command", JSON.valueToTree(command));
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(command).directory(output.toFile())
                .redirectOutput(stdout.toFile()).redirectError(stderr.toFile());
            // No ambient scanner configuration, proxy, token or user cache is needed by the offline subprocess.
            builder.environment().clear();
            builder.environment().put("LANG", "C.UTF-8");
            process = builder.start();
            process.getOutputStream().close();
            if (process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                result.put("outcome", "EXITED").put("exitCode", process.exitValue());
            } else {
                result.put("outcome", "TIMED_OUT");
            }
        } catch (IOException failure) {
            result.put("outcome", "START_FAILURE").put("error", failure.toString());
        } catch (InterruptedException interruption) {
            result.put("outcome", "INTERRUPTED");
            Thread.currentThread().interrupt();
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
                // The fixed offline SBOM worker starts no children. Wait using the actual Process object;
                // ProcessHandle PID namespaces are not reliable in every supported container runtime.
                boolean interrupted = Thread.interrupted();
                try {
                    require(process.waitFor(10, TimeUnit.SECONDS), "scanner did not terminate after forced destruction");
                } finally {
                    if (interrupted) Thread.currentThread().interrupt();
                }
            }
            if (process != null && !process.isAlive()) result.put("exitCode", process.exitValue());
            result.put("finishedAt", Instant.now().toString());
            for (Path path : List.of(stdout, stderr)) {
                if (!Files.exists(path)) write(root, path, new byte[0]);
                String prefix = path.equals(stdout) ? "stdout" : "stderr";
                result.put(prefix + "Path", path.getFileName().toString());
                result.put(prefix + "Hash", hash(path)).put(prefix + "Bytes", Files.size(path));
            }
            write(root, output.resolve("scanner-execution.json"), canonical(result));
        }
        return result;
    }

    private static final class SetHolder {
        private static final java.util.Set<String> ARCHITECTURES = java.util.Set.of("amd64", "x86_64");
    }
}
