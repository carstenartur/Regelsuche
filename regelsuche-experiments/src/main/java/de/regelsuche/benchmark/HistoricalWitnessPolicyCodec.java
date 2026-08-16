package de.regelsuche.benchmark;

import de.regelsuche.json.JsonReader;
import de.regelsuche.json.JsonWriter;
import de.regelsuche.util.AtomicJsonFile;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

final class HistoricalWitnessPolicyCodec {
    private static final String EVIDENCE_STATUS =
        "EXECUTED_TARGET_BLIND_POLICY_COMPARISON";
    private static final String SCALAR_POLICY =
        DiscoveryExperimentRunner.HistoricalWitnessPruningDiagnostic.SEARCH_POLICY;
    private static final String DIVERSITY_POLICY =
        "STRUCTURAL_DIVERSITY_TARGET_BLIND";
    private static final long MAX_INPUT_BYTES = 32L * 1024L * 1024L;

    private HistoricalWitnessPolicyCodec() {
    }

    static Inputs load(
        HistoricalRediscoveryCorpus.Corpus corpus,
        Path atlasDirectory,
        Path pruningPath
    ) {
        java.util.Objects.requireNonNull(corpus, "corpus");
        HistoricalRediscoveryRunArtifact.VerifiedRun verified =
            HistoricalRediscoveryRunArtifact.verify(atlasDirectory);
        HistoricalRediscoveryRunArtifact.Manifest manifest = verified.manifest();
        requireEqual(corpus.schema(), manifest.corpusSchema(), "corpus schema");
        requireEqual(corpus.contentSha256(), manifest.corpusSha256(),
            "corpus hash");
        requireEqual(corpus.inventoryRevision(), manifest.inventoryRevision(),
            "inventory revision");
        requireEqual(corpus.claimBoundary(), manifest.claimBoundary(),
            "claim boundary");
        if (corpus.cases().size() != manifest.caseCount()) {
            throw new IllegalArgumentException("atlas case count differs");
        }

        Path atlasPath = verified.directory().resolve(
            HistoricalRediscoveryRunArtifact.ArtifactRole.ATLAS_JSON.fileName());
        String atlasJson = readUtf8(atlasPath, "atlas");
        Map<String, Object> atlasRoot = readObject(atlasJson, "atlas");
        requireEqual(HistoricalRediscoveryAtlas.SCHEMA,
            text(atlasRoot, "schema"), "atlas schema");
        requireEqual(corpus.schema(), text(atlasRoot, "corpusSchema"),
            "atlas corpus schema");
        requireEqual(corpus.contentSha256(), text(atlasRoot, "corpusSha256"),
            "atlas corpus hash");
        requireEqual(corpus.inventoryRevision(),
            text(atlasRoot, "inventoryRevision"), "atlas inventory revision");
        Map<String, Map<String, Object>> atlasCases = cases(
            atlasRoot, corpus, "atlas");

        String pruningJson = readUtf8(pruningPath, "pruning diagnostic");
        Map<String, Object> pruningRoot = readObject(
            pruningJson, "pruning diagnostic");
        requireEqual(
            DiscoveryExperimentRunner.HistoricalWitnessPruningDiagnostic.SCHEMA,
            text(pruningRoot, "schema"), "pruning schema");
        requireEqual(corpus.schema(), text(pruningRoot, "corpusSchema"),
            "pruning corpus schema");
        requireEqual(corpus.contentSha256(),
            text(pruningRoot, "corpusSha256"), "pruning corpus hash");
        requireEqual(HistoricalRediscoveryAtlas.SCHEMA,
            text(pruningRoot, "atlasSchema"), "pruning atlas schema");
        String atlasHash = sha256(atlasJson);
        requireEqual(atlasHash, text(pruningRoot, "atlasSha256"),
            "pruning atlas hash");
        requireEqual(corpus.inventoryRevision(),
            text(pruningRoot, "inventoryRevision"),
            "pruning inventory revision");
        requireEqual(SCALAR_POLICY, text(pruningRoot, "searchPolicy"),
            "scalar policy");
        String pruningHash = verifyFinalContentHash(pruningJson, pruningRoot);
        Map<String, Map<String, Object>> pruningCases = cases(
            pruningRoot, corpus, "pruning diagnostic");
        verifyScalarWork(atlasCases, pruningCases);

        return new Inputs(
            atlasCases,
            pruningCases,
            manifest.contentHash(),
            atlasHash,
            pruningHash);
    }

    static HistoricalWitnessPolicyComparison.Result write(
        HistoricalRediscoveryCorpus.Corpus corpus,
        Inputs inputs,
        List<HistoricalWitnessPolicyComparison.Comparison> comparisons,
        Path outputDirectory
    ) {
        validateComparisons(corpus, comparisons);
        String contentHash = sha256(render(corpus, inputs, comparisons, null));
        String json = render(corpus, inputs, comparisons, contentHash);
        Path directory = java.util.Objects.requireNonNull(
            outputDirectory, "outputDirectory").toAbsolutePath().normalize();
        Path output = directory.resolve(HistoricalWitnessPolicyComparison.FILE_NAME);
        try {
            Files.createDirectories(directory);
            AtomicJsonFile.writeUtf8(output, json);
            if (!json.equals(Files.readString(output, StandardCharsets.UTF_8))) {
                throw new IllegalStateException(
                    "written witness-policy comparison differs from report");
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(
                "Could not write witness-policy comparison", exception);
        }
        return new HistoricalWitnessPolicyComparison.Result(
            output, contentHash, comparisons);
    }

    private static String render(
        HistoricalRediscoveryCorpus.Corpus corpus,
        Inputs inputs,
        List<HistoricalWitnessPolicyComparison.Comparison> comparisons,
        String contentHash
    ) {
        Map<String, Integer> statusCounts = new TreeMap<>();
        comparisons.forEach(value ->
            statusCounts.merge(value.status(), 1, Integer::sum));
        int compared = (int) comparisons.stream()
            .filter(HistoricalWitnessPolicyComparison.Comparison::compared)
            .count();
        int positive = (int) comparisons.stream()
            .filter(value -> value.prefixGain() != null
                && value.prefixGain() > 0)
            .count();
        int reached = (int) comparisons.stream()
            .filter(HistoricalWitnessPolicyComparison.Comparison::diversityReached)
            .count();
        int regressions = statusCounts.getOrDefault(
            "DIVERSITY_SHORTER_PREFIX", 0);

        JsonWriter writer = new JsonWriter().beginObject();
        writer.property("schema", HistoricalWitnessPolicyComparison.SCHEMA);
        writer.property("evidenceStatus", EVIDENCE_STATUS);
        writer.property("corpusSchema", corpus.schema());
        writer.property("corpusSha256", corpus.contentSha256());
        writer.property("atlasRunHash", inputs.atlasRunHash());
        writer.property("atlasSha256", inputs.atlasSha256());
        writer.property("scalarDiagnosticHash", inputs.pruningHash());
        writer.property("inventoryRevision", corpus.inventoryRevision());
        writer.property("scalarPolicy", SCALAR_POLICY);
        writer.property("diversityPolicy", DIVERSITY_POLICY);
        writer.property("budgetPolicy",
            HistoricalWitnessPolicyComparison.BUDGET_POLICY);
        writer.property("claimBoundary",
            HistoricalWitnessPolicyComparison.CLAIM_BOUNDARY);
        writer.array("cases", array -> comparisons.forEach(value ->
            array.objectValue(object -> writeComparison(object, value))));
        writer.object("summary", summary -> {
            summary.property("caseCount", comparisons.size());
            summary.property("comparedCaseCount", compared);
            summary.property("positivePrefixGainCount", positive);
            summary.property("diversityReachedCount", reached);
            summary.property("regressionCount", regressions);
            summary.object("statusCounts", counts ->
                statusCounts.forEach(counts::property));
        });
        if (contentHash != null) {
            writer.property("contentHash", contentHash);
        }
        return writer.endObject().toString();
    }

    private static void writeComparison(
        JsonWriter writer,
        HistoricalWitnessPolicyComparison.Comparison value
    ) {
        writer.property("id", value.id());
        writer.property("status", value.status());
        writer.property("oracleStatus", value.oracleStatus());
        writer.property("witnessStepCount", value.witnessStepCount());
        writer.property("scalarPrefixLength", value.scalarPrefixLength());
        if (value.diversityPrefixLength() == null) {
            writer.nullProperty("diversityPrefixLength");
            writer.nullProperty("prefixGain");
        } else {
            writer.property("diversityPrefixLength",
                value.diversityPrefixLength());
            writer.property("prefixGain", value.prefixGain());
        }
        writer.property("scalarFirstLossReason",
            value.scalarFirstLossReason());
        writer.property("diversityReached", value.diversityReached());
        writer.object("scalarWork", object ->
            writeWork(object, value.scalarWork()));
        writer.object("diversityWork", object ->
            writeWork(object, value.diversityWork()));
        writer.object("declaredBudget", budget -> value.declaredBudget()
            .entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> budget.property(
                entry.getKey(), entry.getValue())));
    }

    private static void writeWork(
        JsonWriter writer,
        HistoricalWitnessPolicyComparison.Work value
    ) {
        writer.property("exploredStates", value.exploredStates());
        writer.property("engineCalls", value.engineCalls());
        writer.property("generatedTransformations",
            value.generatedTransformations());
    }

    private static Map<String, Map<String, Object>> cases(
        Map<String, Object> root,
        HistoricalRediscoveryCorpus.Corpus corpus,
        String label
    ) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (Object raw : list(root, "cases")) {
            Map<String, Object> value = object(raw, label + " case");
            String id = text(value, "id");
            if (result.put(id, value) != null) {
                throw new IllegalArgumentException(
                    "duplicate " + label + " case " + id);
            }
        }
        List<String> expected = corpus.cases().stream()
            .map(HistoricalRediscoveryCorpus.Case::id)
            .sorted()
            .toList();
        List<String> actual = result.keySet().stream().sorted().toList();
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException(
                label + " case membership differs from corpus");
        }
        return Map.copyOf(result);
    }

    private static void verifyScalarWork(
        Map<String, Map<String, Object>> atlasCases,
        Map<String, Map<String, Object>> pruningCases
    ) {
        for (Map.Entry<String, Map<String, Object>> entry
                : atlasCases.entrySet()) {
            Map<String, Object> scalar = object(
                object(entry.getValue(), "production"), "scalar");
            Map<String, Object> pruning = pruningCases.get(entry.getKey());
            List<Long> atlasWork = List.of(
                number(scalar, "exploredStates"),
                number(scalar, "engineCalls"),
                number(scalar, "generatedTransformations"));
            List<Long> pruningWork = List.of(
                number(pruning, "searchExploredStates"),
                number(pruning, "engineCalls"),
                number(pruning, "generatedTransformations"));
            if (!atlasWork.equals(pruningWork)) {
                throw new IllegalArgumentException(
                    "scalar work differs for " + entry.getKey());
            }
        }
    }

    private static void validateComparisons(
        HistoricalRediscoveryCorpus.Corpus corpus,
        List<HistoricalWitnessPolicyComparison.Comparison> comparisons
    ) {
        List<HistoricalWitnessPolicyComparison.Comparison> retained =
            List.copyOf(java.util.Objects.requireNonNull(
                comparisons, "comparisons"));
        List<String> expected = corpus.cases().stream()
            .map(HistoricalRediscoveryCorpus.Case::id)
            .sorted()
            .toList();
        List<String> actual = retained.stream()
            .map(HistoricalWitnessPolicyComparison.Comparison::id)
            .toList();
        if (!expected.equals(actual)
                || new LinkedHashSet<>(actual).size() != actual.size()) {
            throw new IllegalArgumentException(
                "comparison cases must balance the corpus in canonical order");
        }
    }

    private static String verifyFinalContentHash(
        String json,
        Map<String, Object> root
    ) {
        String contentHash = text(root, "contentHash");
        int marker = json.lastIndexOf(",\"contentHash\":");
        if (marker < 0 || !json.endsWith("}")) {
            throw new IllegalArgumentException(
                "contentHash must be the final canonical property");
        }
        String suffix = json.substring(marker);
        if (!suffix.matches(
                ",\"contentHash\":\"sha256:[0-9a-f]{64}\"}")) {
            throw new IllegalArgumentException(
                "contentHash must be the final canonical property");
        }
        requireEqual(contentHash, sha256(json.substring(0, marker) + "}"),
            "pruning content hash");
        return contentHash;
    }

    private static Map<String, Object> readObject(
        String json,
        String label
    ) {
        return object(new JsonReader(json).readObject(), label);
    }

    private static String readUtf8(Path path, String label) {
        Path normalized = java.util.Objects.requireNonNull(path, "path")
            .toAbsolutePath().normalize();
        if (Files.isSymbolicLink(normalized)
                || !Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException(label + " must be a regular file");
        }
        try {
            long length = Files.size(normalized);
            if (length < 1L || length > MAX_INPUT_BYTES) {
                throw new IllegalArgumentException(
                    label + " size is outside the bounded range");
            }
            byte[] bytes = Files.readAllBytes(normalized);
            if (bytes.length != length) {
                throw new IllegalArgumentException(
                    label + " changed while being read");
            }
            try {
                return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
            } catch (CharacterCodingException exception) {
                throw new IllegalArgumentException(
                    label + " is not valid UTF-8", exception);
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not read " + label, exception);
        }
    }

    private static Map<String, Object> object(Object raw, String label) {
        if (!(raw instanceof Map<?, ?> values)) {
            throw new IllegalArgumentException(label + " must be an object");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (!(key instanceof String text)) {
                throw new IllegalArgumentException(
                    label + " key must be text");
            }
            if (result.put(text, value) != null) {
                throw new IllegalArgumentException(
                    label + " contains duplicate key " + text);
            }
        });
        return result;
    }

    private static Map<String, Object> object(
        Map<String, Object> values,
        String key
    ) {
        return object(values.get(key), key);
    }

    private static List<?> list(Map<String, Object> values, String key) {
        Object raw = values.get(key);
        if (!(raw instanceof List<?> result)) {
            throw new IllegalArgumentException(key + " must be an array");
        }
        return result;
    }

    private static String text(Map<String, Object> values, String key) {
        Object raw = values.get(key);
        if (!(raw instanceof String result) || result.isBlank()) {
            throw new IllegalArgumentException(key + " must be text");
        }
        return result;
    }

    private static long number(Map<String, Object> values, String key) {
        Object raw = values.get(key);
        if (!(raw instanceof Number value)
                || !Double.isFinite(value.doubleValue())
                || value.doubleValue() != value.longValue()) {
            throw new IllegalArgumentException(key + " must be an integer");
        }
        return value.longValue();
    }

    private static void requireEqual(
        Object expected,
        Object actual,
        String label
    ) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new IllegalArgumentException(
                label + " differs: expected=" + expected
                    + ", actual=" + actual);
        }
    }

    private static String sha256(String value) {
        try {
            return "sha256:" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(
                    value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    record Inputs(
        Map<String, Map<String, Object>> atlasCases,
        Map<String, Map<String, Object>> pruningCases,
        String atlasRunHash,
        String atlasSha256,
        String pruningHash
    ) {
        Inputs {
            atlasCases = Map.copyOf(atlasCases);
            pruningCases = Map.copyOf(pruningCases);
        }
    }
}
