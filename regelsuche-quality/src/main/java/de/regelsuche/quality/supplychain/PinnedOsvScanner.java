package de.regelsuche.quality.supplychain;

import static de.regelsuche.quality.supplychain.SupplyChainJson.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
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
import java.util.Map;
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
            if (!Files.exists(binary)) {
                JsonNode release = read(boundFile(root, manifest.path("release")));
                download(root, manifest, binary, Duration.ofSeconds(120), assetApi(manifest, release));
            }
        }
        verifyBinary(manifest, binary);
        require(Files.isExecutable(binary), "pinned scanner is not executable");
        return binary;
    }

    static URI assetApi(JsonNode manifest, JsonNode release) {
        List<JsonNode> matches = new ArrayList<>();
        for (var asset : array(release, "assets")) {
            if (text(asset, "browser_download_url").equals(text(manifest, "url"))
                    && text(asset, "digest").equals(text(manifest, "sha256"))
                    && integer(asset, "size") == integer(manifest, "bytes")) matches.add(asset);
        }
        require(matches.size() == 1 && integer(matches.getFirst(), "id") > 0, "release must bind exactly one scanner asset ID");
        return URI.create("https://api.github.com/repos/google/osv-scanner/releases/assets/" + integer(matches.getFirst(), "id"));
    }

    static void download(Path root, JsonNode manifest, Path binary, Duration timeout, URI assetApi) throws Exception {
        Files.createDirectories(binary.getParent());
        Path temporary = Files.createTempFile(binary.getParent(), "download-", ".tmp");
        long deadline = System.nanoTime() + timeout.toNanos();
        URI browser = URI.create(text(manifest, "url"));
        var builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).followRedirects(HttpClient.Redirect.NORMAL);
        String proxy = System.getenv("HTTPS_PROXY");
        if ("https".equals(browser.getScheme()) && System.getProperty("https.proxyHost") == null && proxy != null && !proxy.isBlank()) {
            URI route = URI.create(proxy);
            if ("http".equals(route.getScheme()) && route.getHost() != null && route.getUserInfo() == null) {
                builder.proxy(ProxySelector.of(new InetSocketAddress(route.getHost(), route.getPort() > 0 ? route.getPort() : 80)));
            }
        }
        try (HttpClient client = builder.build()) {
            try {
                fetch(client, browser, temporary, false, integer(manifest, "bytes"), deadline);
            } catch (IOException | java.util.concurrent.ExecutionException unavailable) {
                if (assetApi == null) throw unavailable;
                try {
                    fetch(client, assetApi, temporary, true, integer(manifest, "bytes"), deadline);
                } catch (Exception fallbackFailure) {
                    fallbackFailure.addSuppressed(unavailable);
                    throw fallbackFailure;
                }
            }
            verifyBinary(manifest, temporary);
            require(temporary.toFile().setExecutable(true, true), "cannot set scanner executable permission");
            checked(root, binary);
            Files.move(temporary, binary, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void fetch(HttpClient client, URI uri, Path temporary, boolean api, long size, long deadline) throws Exception {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new java.util.concurrent.TimeoutException("scanner download deadline exceeded");
        var request = HttpRequest.newBuilder(uri).timeout(Duration.ofNanos(remaining)).GET();
        if (api) request.header("Accept", "application/octet-stream");
        // Wait for the complete body under the shared deadline, including error bodies.
        var pending = client.sendAsync(request.build(), info -> HttpResponse.BodySubscribers.limiting(
            HttpResponse.BodySubscribers.ofFile(temporary, java.nio.file.StandardOpenOption.WRITE,
                java.nio.file.StandardOpenOption.TRUNCATE_EXISTING), size));
        try {
            HttpResponse<Path> result = pending.get(remaining, TimeUnit.NANOSECONDS);
            if (result.statusCode() != 200) throw new IOException("official scanner download failed: HTTP " + result.statusCode());
        } finally {
            if (!pending.isDone()) pending.cancel(true);
        }
    }

    static void verifyBinary(JsonNode manifest, Path binary) throws IOException {
        require(Files.size(binary) == integer(manifest, "bytes") && hash(binary).equals(text(manifest, "sha256")),
            "scanner executable hash or size differs");
    }

    static ScanExecution scan(Path root, Path output, Path binary, VulnerabilityPolicy policy,
            AdvisorySnapshot snapshot, MavenScanInputs inputs) throws Exception {
        verifyBinary(policy.scanner(), binary);
        inputs.verifyFiles(root, output);
        String manifestHash = hash(canonical(snapshot.manifest()));
        String archiveHash = hash(snapshot.archive());
        String inputsHash = hash(canonical(inputs.bindings()));
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
        ProcessExecution execution = execute(root, output, command, Duration.ofSeconds(policy.timeoutSeconds()));
        verifyBinary(policy.scanner(), binary);
        inputs.verifyFiles(root, output);
        require(hash(file(root, database)).equals(text(snapshot.manifest().path("archive"), "sha256")),
            "scanner database changed during execution");
        var result = new ScanExecution(root, output, execution, manifestHash, archiveHash, inputsHash, inputs.components());
        result.verifyBindings(snapshot, inputs, output);
        return result;
    }

    static ProcessExecution execute(Path root, Path output, List<String> command, Duration timeout) throws Exception {
        Path stdout = checked(root, output.resolve("scanner.stdout.json"));
        Path stderr = checked(root, output.resolve("scanner.stderr.log"));
        ObjectNode result = JSON.createObjectNode();
        result.put("outcome", "NOT_STARTED").putNull("exitCode");
        result.put("startedAt", Instant.now().toString());
        result.put("timeoutMillis", timeout.toMillis());
        result.put("networkMode", "OSV_SCANNER_OFFLINE_LOCAL_DATABASE");
        result.set("command", JSON.valueToTree(command));
        result.put("outputLimitBytes", MAX_JSON_BYTES);
        BoundedScannerProcess.Result captured = BoundedScannerProcess.run(output, command, timeout, MAX_JSON_BYTES);
        result.put("outcome", captured.outcome());
        if (captured.exitCode() != null) result.put("exitCode", captured.exitCode());
        if (!captured.error().isEmpty()) result.put("error", captured.error());
        result.put("finishedAt", Instant.now().toString());
        retain(root, stdout, "stdout", captured.stdout(), result);
        retain(root, stderr, "stderr", captured.stderr(), result);
        write(root, output.resolve("scanner-execution.json"), canonical(result));
        return new ProcessExecution(result, captured.stdout().bytes());
    }

    private static void retain(Path root, Path path, String prefix, BoundedScannerProcess.Captured captured,
            ObjectNode receipt) throws IOException {
        write(root, path, captured.bytes());
        receipt.put(prefix + "Path", path.getFileName().toString());
        receipt.put(prefix + "Hash", hash(captured.bytes())).put(prefix + "Bytes", captured.bytes().length);
        receipt.put(prefix + "ObservedBytes", captured.observedBytes()).put(prefix + "Truncated", captured.truncated());
        if (!captured.error().isEmpty()) receipt.put(prefix + "CaptureError", captured.error());
    }

    /** Created only from an actual process; exported JSON and bytes are defensive copies. */
    static final class ProcessExecution {
        private final ObjectNode receipt;
        private final byte[] stdout;

        private ProcessExecution(ObjectNode receipt, byte[] stdout) {
            this.receipt = receipt.deepCopy();
            // Ownership transfers from the private capture path; the capture never escapes execute().
            this.stdout = stdout;
        }
        ObjectNode receipt() { return receipt.deepCopy(); }
        byte[] stdoutBytes() { return stdout.clone(); }

        private void verifyRetained(Path root, Path output) throws IOException {
            for (String prefix : List.of("stdout", "stderr")) {
                Path retained = file(root, output.resolve(text(receipt, prefix + "Path")));
                require(Files.size(retained) == integer(receipt, prefix + "Bytes")
                    && hash(retained).equals(text(receipt, prefix + "Hash")),
                    "retained scanner " + prefix + " differs from actual execution");
            }
            require(hash(file(root, output.resolve("scanner-execution.json"))).equals(hash(canonical(receipt))),
                "retained scanner receipt differs from actual execution");
        }
    }

    /** Only scan() can qualify a process for a vulnerability decision, after verifying its inputs and binary. */
    static final class ScanExecution {
        private final Path root, output;
        private final ProcessExecution process;
        private final String manifestHash, archiveHash, inputsHash;
        private final Map<String, MavenScanInputs.Component> components;

        private ScanExecution(Path root, Path output, ProcessExecution process, String manifestHash,
                String archiveHash, String inputsHash, Map<String, MavenScanInputs.Component> components) {
            this.root = root.toAbsolutePath().normalize();
            this.output = output.toAbsolutePath().normalize();
            this.process = process; this.manifestHash = manifestHash; this.archiveHash = archiveHash;
            this.inputsHash = inputsHash; this.components = Map.copyOf(components);
        }
        ObjectNode receipt() { return process.receipt(); }
        byte[] stdoutBytes() { return process.stdoutBytes(); }

        void verifyBindings(AdvisorySnapshot snapshot, MavenScanInputs inputs, Path output) throws IOException {
            require(this.output.equals(output.toAbsolutePath().normalize()), "scanner output directory differs");
            require(manifestHash.equals(hash(canonical(snapshot.manifest()))) && archiveHash.equals(hash(snapshot.archive())),
                "scanner snapshot binding differs");
            require(inputsHash.equals(hash(canonical(inputs.bindings()))) && components.equals(inputs.components()),
                "scanner input binding differs from actual execution");
            inputs.verifyFiles(root, this.output);
            process.verifyRetained(root, this.output);
        }
    }

    private static final class SetHolder {
        private static final java.util.Set<String> ARCHITECTURES = java.util.Set.of("amd64", "x86_64");
    }
}
