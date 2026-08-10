package de.regelsuche.quality.jmh;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

final class JmhHistoryLoader {
    static final String POLICY_SCHEMA =
        "regelsuche.quality.jmh-history-policy/v1";
    static final String SNAPSHOT_SCHEMA =
        "regelsuche.quality.jmh-history-snapshot/v1";

    private static final Pattern SHA256 =
        Pattern.compile("sha256:[0-9a-f]{64}");
    private static final Pattern REVISION =
        Pattern.compile("[0-9a-f]{40}");
    private static final Map<String, Double> UNIT_TO_MS = Map.of(
        "ns/op", 0.000001d,
        "us/op", 0.001d,
        "ms/op", 1.0d,
        "s/op", 1000.0d
    );
    private static final List<String> EXECUTION_FIELDS = List.of(
        "mode",
        "forks",
        "threads",
        "warmupIterations",
        "measurementIterations",
        "jmhVersion",
        "jdkMajor"
    );
    private static final Set<String> POLICY_FIELDS = Set.of(
        "schema",
        "claimBoundary",
        "lowerIsBetter",
        "normalizedUnit",
        "snapshots"
    );
    private static final Set<String> SNAPSHOT_SPECIFICATION_FIELDS = Set.of(
        "path",
        "sha256"
    );
    private static final Set<String> SNAPSHOT_FIELDS = Set.of(
        "schema",
        "label",
        "recordedAt",
        "sourceRevision",
        "sourceArtifactDigest",
        "jmhResultDigest",
        "execution",
        "benchmarks"
    );
    private static final Set<String> BENCHMARK_FIELDS = Set.of(
        "benchmark",
        "family",
        "unit",
        "score",
        "scoreError"
    );

    private final ObjectMapper mapper;

    JmhHistoryLoader() {
        mapper = new ObjectMapper();
        mapper.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        mapper.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    JmhHistory load(Path historyPolicyPath, Path regressionPolicyPath)
            throws IOException {
        Path policy = requireRegularFile(historyPolicyPath, "history policy");
        Path regression = requireRegularFile(
            regressionPolicyPath,
            "regression policy"
        );
        Path repositoryRoot = repositoryRoot(policy);
        JsonNode policyDocument = readObject(policy, "history policy");
        validatePolicy(policyDocument);
        Map<String, JmhHistory.BenchmarkContract> contracts =
            loadContracts(regression);
        List<JmhHistory.Snapshot> snapshots = loadSnapshots(
            repositoryRoot,
            policyDocument.path("snapshots"),
            contracts
        );
        return new JmhHistory(
            text(policyDocument, "claimBoundary", "history policy"),
            sha256(Files.readAllBytes(policy)),
            sha256(Files.readAllBytes(regression)),
            snapshots,
            contracts
        );
    }

    private void validatePolicy(JsonNode policy) {
        rejectUnknownFields(policy, POLICY_FIELDS, "history policy");
        require(
            POLICY_SCHEMA.equals(text(policy, "schema", "history policy")),
            "history policy schema must be " + POLICY_SCHEMA
        );
        require(
            "ms/op".equals(text(policy, "normalizedUnit", "history policy")),
            "history policy normalizedUnit must be ms/op"
        );
        require(
            policy.path("lowerIsBetter").isBoolean()
                && policy.path("lowerIsBetter").booleanValue(),
            "history policy must declare lowerIsBetter=true"
        );
        JsonNode snapshots = policy.path("snapshots");
        require(
            snapshots.isArray() && snapshots.size() >= 2,
            "history policy must retain at least two snapshots"
        );
    }

    private Map<String, JmhHistory.BenchmarkContract> loadContracts(
        Path regressionPolicy
    ) throws IOException {
        JsonNode regression = readObject(regressionPolicy, "regression policy");
        JsonNode benchmarks = regression.path("benchmarks");
        require(
            benchmarks.isArray() && !benchmarks.isEmpty(),
            "regression policy must declare benchmarks"
        );
        Map<String, JmhHistory.BenchmarkContract> contracts = new TreeMap<>();
        for (JsonNode benchmark : benchmarks) {
            String name = text(benchmark, "benchmark", "benchmark contract");
            String family = text(benchmark, "family", name);
            String unit = text(benchmark, "unit", name);
            require(
                UNIT_TO_MS.containsKey(unit),
                "unsupported benchmark unit for " + name + ": " + unit
            );
            require(
                contracts.put(
                    name,
                    new JmhHistory.BenchmarkContract(family, unit)
                ) == null,
                "duplicate regression benchmark: " + name
            );
        }
        return Map.copyOf(contracts);
    }

    private List<JmhHistory.Snapshot> loadSnapshots(
        Path repositoryRoot,
        JsonNode specifications,
        Map<String, JmhHistory.BenchmarkContract> contracts
    ) throws IOException {
        List<JmhHistory.Snapshot> snapshots = new ArrayList<>();
        Set<String> labels = new HashSet<>();
        Set<String> revisions = new HashSet<>();
        Map<String, String> referenceExecution = null;
        Instant previousTimestamp = null;

        for (JsonNode specification : specifications) {
            SnapshotLoad load = loadSnapshot(
                repositoryRoot,
                specification,
                contracts
            );
            JmhHistory.Snapshot snapshot = load.snapshot();
            require(
                labels.add(snapshot.label()),
                "duplicate snapshot label: " + snapshot.label()
            );
            require(
                revisions.add(snapshot.sourceRevision()),
                "duplicate snapshot sourceRevision: "
                    + snapshot.sourceRevision()
            );
            Instant timestamp = parseTimestamp(snapshot.recordedAt());
            require(
                previousTimestamp == null
                    || timestamp.isAfter(previousTimestamp),
                "snapshots must be strictly chronological"
            );
            previousTimestamp = timestamp;
            if (referenceExecution == null) {
                referenceExecution = load.execution();
            } else {
                require(
                    referenceExecution.equals(load.execution()),
                    "snapshot execution contract differs: "
                        + snapshot.snapshotPath()
                );
            }
            snapshots.add(snapshot);
        }
        return List.copyOf(snapshots);
    }

    private SnapshotLoad loadSnapshot(
        Path repositoryRoot,
        JsonNode specification,
        Map<String, JmhHistory.BenchmarkContract> contracts
    ) throws IOException {
        require(specification.isObject(), "snapshot specification must be an object");
        rejectUnknownFields(
            specification,
            SNAPSHOT_SPECIFICATION_FIELDS,
            "snapshot specification"
        );
        String relativePath = text(
            specification,
            "path",
            "snapshot specification"
        );
        String expectedDigest = text(
            specification,
            "sha256",
            relativePath
        );
        require(
            SHA256.matcher(expectedDigest).matches(),
            "snapshot sha256 is invalid: " + relativePath
        );
        Path snapshotPath = checkoutFile(repositoryRoot, relativePath);
        byte[] bytes = Files.readAllBytes(snapshotPath);
        String actualDigest = sha256(bytes);
        require(
            expectedDigest.equals(actualDigest),
            "snapshot digest mismatch for " + relativePath
        );
        JsonNode snapshot = readObject(bytes, relativePath);
        rejectUnknownFields(snapshot, SNAPSHOT_FIELDS, relativePath);
        require(
            SNAPSHOT_SCHEMA.equals(text(snapshot, "schema", relativePath)),
            "snapshot schema must be " + SNAPSHOT_SCHEMA + ": "
                + relativePath
        );
        String label = text(snapshot, "label", relativePath);
        String recordedAt = text(snapshot, "recordedAt", relativePath);
        String revision = text(snapshot, "sourceRevision", relativePath);
        require(
            REVISION.matcher(revision).matches(),
            "snapshot sourceRevision is invalid: " + relativePath
        );
        String artifactDigest = text(
            snapshot,
            "sourceArtifactDigest",
            relativePath
        );
        require(
            SHA256.matcher(artifactDigest).matches(),
            "sourceArtifactDigest is invalid: " + relativePath
        );
        if (snapshot.has("jmhResultDigest")) {
            String resultDigest = text(
                snapshot,
                "jmhResultDigest",
                relativePath
            );
            require(
                SHA256.matcher(resultDigest).matches(),
                "jmhResultDigest is invalid: " + relativePath
            );
        }
        Map<String, String> execution = execution(snapshot.path("execution"));
        Map<String, JmhHistory.Measurement> measurements = measurements(
            snapshot.path("benchmarks"),
            contracts,
            relativePath
        );
        return new SnapshotLoad(
            new JmhHistory.Snapshot(
                label,
                recordedAt,
                revision,
                artifactDigest,
                relativePath,
                actualDigest,
                measurements
            ),
            execution
        );
    }

    private Map<String, JmhHistory.Measurement> measurements(
        JsonNode entries,
        Map<String, JmhHistory.BenchmarkContract> contracts,
        String source
    ) {
        require(entries.isArray(), "snapshot benchmarks must be an array: " + source);
        Map<String, JsonNode> observed = new HashMap<>();
        for (JsonNode entry : entries) {
            require(entry.isObject(), "benchmark entry must be an object: " + source);
            rejectUnknownFields(entry, BENCHMARK_FIELDS, source + " benchmark");
            String name = text(entry, "benchmark", source);
            require(
                observed.put(name, entry) == null,
                "snapshot duplicates benchmark " + name
            );
        }
        require(
            observed.keySet().equals(contracts.keySet()),
            "snapshot benchmark inventory differs: " + source
        );
        Map<String, JmhHistory.Measurement> normalized = new TreeMap<>();
        for (Map.Entry<String, JmhHistory.BenchmarkContract> item
                : contracts.entrySet()) {
            String name = item.getKey();
            JmhHistory.BenchmarkContract contract = item.getValue();
            JsonNode entry = observed.get(name);
            require(
                contract.family().equals(text(entry, "family", name)),
                "snapshot family differs for " + name
            );
            require(
                contract.sourceUnit().equals(text(entry, "unit", name)),
                "snapshot unit differs for " + name
            );
            double factor = UNIT_TO_MS.get(contract.sourceUnit());
            normalized.put(
                name,
                new JmhHistory.Measurement(
                    number(entry, "score", name) * factor,
                    number(entry, "scoreError", name) * factor
                )
            );
        }
        return Map.copyOf(normalized);
    }

    private static Map<String, String> execution(JsonNode execution) {
        require(execution.isObject(), "snapshot execution must be an object");
        rejectUnknownFields(
            execution,
            Set.copyOf(EXECUTION_FIELDS),
            "snapshot execution"
        );
        Map<String, String> result = new TreeMap<>();
        for (String field : EXECUTION_FIELDS) {
            JsonNode value = execution.get(field);
            require(
                value != null && !value.isNull(),
                "snapshot execution is missing " + field
            );
            result.put(field, value.asText());
        }
        return Map.copyOf(result);
    }

    private JsonNode readObject(Path path, String owner) throws IOException {
        return readObject(Files.readAllBytes(path), owner);
    }

    private JsonNode readObject(byte[] bytes, String owner) throws IOException {
        JsonNode document;
        try {
            document = mapper.readTree(bytes);
        } catch (IOException exception) {
            throw new HistoryException(
                "cannot read strict JSON for " + owner,
                exception
            );
        }
        require(document != null && document.isObject(), owner + " must be an object");
        return document;
    }

    private static Path repositoryRoot(Path policy) {
        Path qualityDirectory = policy.getParent();
        require(qualityDirectory != null, "history policy has no parent directory");
        Path configDirectory = qualityDirectory.getParent();
        require(configDirectory != null, "history policy is outside config/quality");
        Path root = configDirectory.getParent();
        require(root != null, "history policy has no repository root");
        return root.toAbsolutePath().normalize();
    }

    private static Path checkoutFile(Path root, String relativePath)
            throws IOException {
        Path relative = Path.of(relativePath);
        require(!relative.isAbsolute(), "snapshot path must be relative: " + relativePath);
        require(!relative.normalize().startsWith(".."), "snapshot escapes checkout: " + relativePath);
        Path candidate = root.resolve(relative).normalize();
        require(candidate.startsWith(root), "snapshot escapes checkout: " + relativePath);
        Path current = root;
        for (Path part : root.relativize(candidate)) {
            current = current.resolve(part);
            require(
                !Files.isSymbolicLink(current),
                "snapshot path contains a symbolic component: " + relativePath
            );
        }
        Path real = requireRegularFile(candidate, "snapshot");
        require(real.startsWith(root), "snapshot escapes checkout: " + relativePath);
        return real;
    }

    private static Path requireRegularFile(Path path, String owner)
            throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        require(!Files.isSymbolicLink(absolute), owner + " must not be symbolic: " + path);
        require(
            Files.isRegularFile(absolute, LinkOption.NOFOLLOW_LINKS),
            owner + " must be a regular file: " + path
        );
        return absolute.toRealPath();
    }

    private static Instant parseTimestamp(String value) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException exception) {
            throw new HistoryException("invalid snapshot timestamp: " + value, exception);
        }
    }

    private static String text(JsonNode owner, String field, String location) {
        JsonNode value = owner.get(field);
        require(
            value != null && value.isTextual() && !value.textValue().isBlank(),
            location + "." + field + " must be a non-blank string"
        );
        return value.textValue();
    }

    private static double number(JsonNode owner, String field, String location) {
        JsonNode value = owner.get(field);
        require(value != null && value.isNumber(), location + "." + field + " must be numeric");
        double result = value.doubleValue();
        require(
            Double.isFinite(result) && result >= 0.0d,
            location + "." + field + " must be finite and non-negative"
        );
        return result;
    }

    private static void rejectUnknownFields(
        JsonNode object,
        Set<String> allowed,
        String owner
    ) {
        object.fieldNames().forEachRemaining(field -> require(
            allowed.contains(field),
            owner + " contains unknown field: " + field
        ));
    }

    static String sha256(byte[] bytes) {
        try {
            return "sha256:" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes)
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new HistoryException(message);
        }
    }

    private record SnapshotLoad(
        JmhHistory.Snapshot snapshot,
        Map<String, String> execution
    ) {
    }

    static final class HistoryException extends IllegalArgumentException {
        HistoryException(String message) {
            super(message);
        }

        HistoryException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
