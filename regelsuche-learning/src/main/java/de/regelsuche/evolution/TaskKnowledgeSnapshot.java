package de.regelsuche.evolution;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Bounded, strict snapshots. A content digest is identity, not proof or authenticated telemetry. */
final class TaskKnowledgeSnapshot {
    private static final int MAX_BYTES = 4_000_000;
    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final String[] COSTS = {"lessons", "formation", "preparation", "validation", "selection", "restoration", "splitChecking"};
    private TaskKnowledgeSnapshot() { }
    record Decoded(int generation, String parentId, String model,
            Map<String, CumulativeTaskKnowledge.Strategy> routes, CumulativeTaskKnowledge.Costs costs,
            CumulativeTaskKnowledge.Decision decision, List<CheckedLearnedSchemaModel.Attempt> attempts) { }

    static String encode(int generation, String parentId, CheckedLearnedSchemaModel model,
            Map<String, CumulativeTaskKnowledge.Strategy> routes, CumulativeTaskKnowledge.Costs costs,
            CumulativeTaskKnowledge.Decision decision, List<CheckedLearnedSchemaModel.Attempt> attempts) {
        var root = JSON.createObjectNode().put("revision", CumulativeTaskKnowledge.REVISION)
            .put("generation", generation).put("parentId", parentId).put("model", model.toCanonicalJson());
        var policy = root.putObject("routes");
        new TreeMap<>(routes).forEach((key, value) -> policy.put(key, value.name()));
        var work = root.putObject("costs");
        long[] values = {costs.lessons(), costs.formation(), costs.preparation(), costs.validation(), costs.selection(),
            costs.restoration(), costs.splitChecking()};
        for (int i = 0; i < COSTS.length; i++) work.put(COSTS[i], values[i]);
        root.put("decision", decision.name());
        var records = root.putArray("attempts");
        for (var attempt : attempts) {
            var value = records.addObject().put("status", attempt.status()).put("detail", attempt.detail());
            var origins = value.putArray("observationIds"); attempt.observationIds().forEach(origins::add);
        }
        String json = root.toString();
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw new IllegalArgumentException("knowledge snapshot too large");
        return json;
    }
    static Decoded decode(String json) {
        if (json == null || json.length() > MAX_BYTES || json.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
            throw new IllegalArgumentException("knowledge snapshot too large");
        try {
            JsonNode root = JSON.readTree(json);
            fields(root, Set.of("revision", "generation", "parentId", "model", "routes", "costs", "decision", "attempts"));
            if (!CumulativeTaskKnowledge.REVISION.equals(text(root, "revision"))) throw new IllegalArgumentException("unknown knowledge revision");
            int generation = Math.toIntExact(number(root, "generation"));
            var routes = new TreeMap<String, CumulativeTaskKnowledge.Strategy>();
            var policy = root.get("routes");
            if (!policy.isObject() || policy.size() > 256) throw new IllegalArgumentException("invalid routing policy");
            policy.properties().forEach(entry -> {
                if (!entry.getValue().isTextual()) throw new IllegalArgumentException("invalid strategy");
                routes.put(entry.getKey(), CumulativeTaskKnowledge.Strategy.valueOf(entry.getValue().textValue()));
            });
            var costs = root.get("costs"); fields(costs, Set.of(COSTS));
            var attempts = new ArrayList<CheckedLearnedSchemaModel.Attempt>();
            var records = root.get("attempts");
            if (!records.isArray() || records.size() > 1024) throw new IllegalArgumentException("invalid attempt history");
            for (var attempt : records) {
                fields(attempt, Set.of("observationIds", "status", "detail"));
                var origins = attempt.get("observationIds");
                if (!origins.isArray() || origins.size() > 128) throw new IllegalArgumentException("invalid observation history");
                var ids = new ArrayList<String>();
                for (var origin : origins) {
                    if (!origin.isTextual() || origin.textValue().length() > 1024) throw new IllegalArgumentException("invalid observation id");
                    ids.add(origin.textValue());
                }
                attempts.add(new CheckedLearnedSchemaModel.Attempt(ids, text(attempt, "status"), text(attempt, "detail")));
            }
            return new Decoded(generation, text(root, "parentId"), text(root, "model"), routes,
                new CumulativeTaskKnowledge.Costs(number(costs, "lessons"), number(costs, "formation"),
                    number(costs, "preparation"), number(costs, "validation"), number(costs, "selection"),
                    number(costs, "restoration"), number(costs, "splitChecking")),
                CumulativeTaskKnowledge.Decision.valueOf(text(root, "decision")), List.copyOf(attempts));
        } catch (IOException | ArithmeticException failure) {
            throw new IllegalArgumentException("invalid knowledge snapshot", failure);
        }
    }
    private static void fields(JsonNode node, Set<String> expected) {
        if (node == null || !node.isObject()) throw new IllegalArgumentException("object required");
        var names = new HashSet<String>(); node.fieldNames().forEachRemaining(names::add);
        if (!names.equals(expected)) throw new IllegalArgumentException("unexpected snapshot fields");
    }
    private static String text(JsonNode node, String key) {
        if (!node.get(key).isTextual()) throw new IllegalArgumentException("text required: " + key);
        return node.get(key).textValue();
    }
    private static long number(JsonNode node, String key) {
        var value = node.get(key);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0)
            throw new IllegalArgumentException("nonnegative integer required: " + key);
        return value.longValue();
    }
    static Path save(Path directory, String id, String json) throws IOException {
        if (Files.isSymbolicLink(directory)) throw new IOException("snapshot directory is a symbolic link");
        Files.createDirectories(directory);
        var path = directory.resolve(id.substring("sha256:".length()) + ".json");
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) { requireSame(path, json); return path; }
        Path temporary = Files.createTempFile(directory, ".knowledge-", ".tmp");
        try {
            Files.writeString(temporary, json, StandardCharsets.UTF_8);
            // Same-filesystem hard link publishes complete bytes atomically, without replacing a rival file.
            try { Files.createLink(path, temporary); }
            catch (FileAlreadyExistsException race) { requireSame(path, json); }
        } finally { Files.deleteIfExists(temporary); }
        return path;
    }
    private static void requireSame(Path path, String json) throws IOException {
        if (!read(path).equals(json)) throw new IOException("existing knowledge snapshot differs");
    }
    static String read(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("regular snapshot file required");
        byte[] bytes;
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) { bytes = input.readNBytes(MAX_BYTES + 1); }
        if (bytes.length > MAX_BYTES) throw new IOException("knowledge snapshot too large");
        var json = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        String expected = SchematicProofPlan.hash(json).substring("sha256:".length()) + ".json";
        if (!path.getFileName().toString().equals(expected)) throw new IOException("snapshot content address differs");
        return json;
    }
}
