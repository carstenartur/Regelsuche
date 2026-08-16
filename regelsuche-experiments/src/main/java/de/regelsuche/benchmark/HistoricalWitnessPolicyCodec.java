package de.regelsuche.benchmark;

import de.regelsuche.benchmark.HistoricalRediscoveryCorpus.Case;
import de.regelsuche.benchmark.HistoricalRediscoveryCorpus.Corpus;
import de.regelsuche.benchmark.HistoricalWitnessPolicyComparison.Comparison;
import de.regelsuche.benchmark.HistoricalWitnessPolicyComparison.Work;
import de.regelsuche.json.JsonReader;
import de.regelsuche.json.JsonWriter;
import de.regelsuche.util.AtomicJsonFile;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** Strict persisted-evidence boundary for the witness policy comparison. */
final class HistoricalWitnessPolicyCodec {
    private HistoricalWitnessPolicyCodec() {
    }

    static Inputs load(
        Corpus corpus,
        Path atlasDirectory,
        Path pruningPath
    ) {
        HistoricalRediscoveryRunArtifact.VerifiedRun verified =
            HistoricalRediscoveryRunArtifact.verify(atlasDirectory);
        require(corpus.contentSha256(), verified.manifest().corpusSha256(),
            "manifest corpus hash");
        require(corpus.inventoryRevision(), verified.manifest().inventoryRevision(),
            "manifest inventory");
        if (corpus.cases().size() != verified.manifest().caseCount()) {
            throw new IllegalArgumentException("manifest case count differs");
        }
        String atlasJson = read(verified.directory().resolve(
            HistoricalRediscoveryRunArtifact.ArtifactRole.ATLAS_JSON.fileName()));
        String pruningJson = read(pruningPath);
        Map<String, Object> atlas = new JsonReader(atlasJson).readObject();
        Map<String, Object> pruning = new JsonReader(pruningJson).readObject();
        require(HistoricalRediscoveryAtlas.SCHEMA,
            text(atlas, "schema"), "atlas schema");
        require(corpus.contentSha256(),
            text(atlas, "corpusSha256"), "atlas corpus hash");
        require(DiscoveryExperimentRunner.HistoricalWitnessPruningDiagnostic
                .SCHEMA,
            text(pruning, "schema"), "pruning schema");
        require(corpus.contentSha256(),
            text(pruning, "corpusSha256"), "pruning corpus hash");
        require(sha256(atlasJson),
            text(pruning, "atlasSha256"), "pruning atlas hash");
        String pruningHash = text(pruning, "contentHash");
        int marker = pruningJson.lastIndexOf(",\"contentHash\":");
        if (marker < 0 || !pruningJson.endsWith("}")) {
            throw new IllegalArgumentException(
                "pruning contentHash must be final");
        }
        require(pruningHash,
            sha256(pruningJson.substring(0, marker) + "}"),
            "pruning content hash");
        Map<String, Map<String, Object>> atlasCases = cases(atlas);
        Map<String, Map<String, Object>> pruningCases = cases(pruning);
        Set<String> expected = corpus.cases().stream()
            .map(Case::id).collect(Collectors.toSet());
        if (!expected.equals(atlasCases.keySet())
                || !expected.equals(pruningCases.keySet())) {
            throw new IllegalArgumentException(
                "comparison case membership differs from corpus");
        }
        return new Inputs(
            verified.manifest(), atlasJson, pruningHash,
            atlasCases, pruningCases);
    }

    static Result write(
        Corpus corpus,
        Inputs inputs,
        List<Comparison> cases,
        Path outputDirectory
    ) {
        String hash = sha256(render(corpus, inputs, cases, null));
        String json = render(corpus, inputs, cases, hash);
        Path output = outputDirectory.toAbsolutePath().normalize()
            .resolve(HistoricalWitnessPolicyComparison.FILE_NAME);
        try {
            Files.createDirectories(output.getParent());
            AtomicJsonFile.writeUtf8(output, json);
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not write policy comparison",
                exception);
        }
        return new Result(output, hash, cases);
    }

    private static String render(
        Corpus corpus,
        Inputs inputs,
        List<Comparison> cases,
        String contentHash
    ) {
        Map<String, Integer> counts = new TreeMap<>();
        cases.forEach(value -> counts.merge(value.status(), 1, Integer::sum));
        JsonWriter writer = new JsonWriter().beginObject();
        writer.property("schema", HistoricalWitnessPolicyComparison.SCHEMA);
        writer.property("evidenceStatus",
            "EXECUTED_TARGET_BLIND_POLICY_COMPARISON");
        writer.property("corpusSchema", corpus.schema());
        writer.property("corpusSha256", corpus.contentSha256());
        writer.property("atlasRunHash", inputs.manifest().contentHash());
        writer.property("atlasSha256", sha256(inputs.atlasJson()));
        writer.property("pruningDiagnosticHash", inputs.pruningHash());
        writer.property("inventoryRevision", corpus.inventoryRevision());
        writer.property("scalarPolicy",
            DiscoveryExperimentRunner.HistoricalWitnessPruningDiagnostic
                .SEARCH_POLICY);
        writer.property("diversityPolicy",
            "STRUCTURAL_DIVERSITY_TARGET_BLIND");
        writer.property("budgetPolicy",
            HistoricalWitnessPolicyComparison.BUDGET_POLICY);
        writer.property("claimBoundary",
            HistoricalWitnessPolicyComparison.CLAIM_BOUNDARY);
        writer.array("cases", array -> cases.forEach(value ->
            array.objectValue(object -> writeCase(object, value))));
        writer.object("summary", summary -> {
            summary.property("caseCount", cases.size());
            summary.property("comparedCaseCount", cases.stream()
                .filter(Comparison::compared).count());
            summary.property("positivePrefixGainCount", cases.stream()
                .filter(value -> value.prefixGain() != null
                    && value.prefixGain() > 0).count());
            summary.property("diversityReachedCount", cases.stream()
                .filter(Comparison::diversityReached).count());
            summary.object("statusCounts", status -> counts.forEach(
                (key, value) -> status.property(key, value)));
        });
        if (contentHash != null) {
            writer.property("contentHash", contentHash);
        }
        return writer.endObject().toString();
    }

    private static void writeCase(JsonWriter writer, Comparison value) {
        writer.property("id", value.id());
        writer.property("status", value.status());
        writer.property("oracleStatus", value.oracleStatus());
        writer.property("witnessStepCount", value.witnessStepCount());
        writer.property("scalarPrefixLength", value.scalarPrefixLength());
        nullable(writer, "diversityPrefixLength",
            value.diversityPrefixLength());
        nullable(writer, "prefixGain", value.prefixGain());
        writer.property("scalarFirstLossReason", value.scalarFirstLossReason());
        writer.property("diversityReached", value.diversityReached());
        writer.object("scalarWork", object ->
            writeWork(object, value.scalarWork()));
        writer.object("diversityWork", object ->
            writeWork(object, value.diversityWork()));
        writer.object("declaredBudget", object -> value.declaredBudget()
            .forEach((key, number) -> object.property(key, number)));
    }

    private static void writeWork(JsonWriter writer, Work value) {
        writer.property("exploredStates", value.exploredStates());
        writer.property("engineCalls", value.engineCalls());
        writer.property("generatedTransformations",
            value.generatedTransformations());
    }

    private static void nullable(JsonWriter writer, String key, Integer value) {
        if (value == null) {
            writer.nullProperty(key);
        } else {
            writer.property(key, value);
        }
    }

    private static Map<String, Map<String, Object>> cases(
        Map<String, Object> root
    ) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (Object raw : list(root, "cases")) {
            Map<String, Object> value = object(raw, "case");
            String id = text(value, "id");
            if (result.put(id, value) != null) {
                throw new IllegalArgumentException("duplicate case " + id);
            }
        }
        return Map.copyOf(result);
    }

    private static Map<String, Object> object(Object raw, String label) {
        if (!(raw instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(label + " must be an object");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, value) -> result.put((String) key, value));
        return result;
    }

    private static List<?> list(Map<String, Object> value, String key) {
        if (!(value.get(key) instanceof List<?> result)) {
            throw new IllegalArgumentException(key + " must be an array");
        }
        return result;
    }

    private static String text(Map<String, Object> value, String key) {
        if (!(value.get(key) instanceof String result) || result.isBlank()) {
            throw new IllegalArgumentException(key + " must be text");
        }
        return result;
    }

    private static String read(Path path) {
        try {
            return Files.readString(path.toAbsolutePath().normalize(),
                StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not read " + path, exception);
        }
    }

    private static void require(Object expected, Object actual, String label) {
        if (!Objects.equals(expected, actual)) {
            throw new IllegalArgumentException(label + " differs");
        }
    }

    private static String sha256(String value) {
        try {
            return "sha256:" + java.util.HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(
                    value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    record Inputs(
        HistoricalRediscoveryRunArtifact.Manifest manifest,
        String atlasJson,
        String pruningHash,
        Map<String, Map<String, Object>> atlasCases,
        Map<String, Map<String, Object>> pruningCases
    ) {
    }

    record Result(Path path, String contentHash, List<Comparison> cases) {
        Result {
            path = path.toAbsolutePath().normalize();
            cases = List.copyOf(cases);
        }
    }
}
