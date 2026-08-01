package de.regelsuche.evolution;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.Choice;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.FirstApplicable;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.Node;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.Prune;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.Prioritize;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.Priority;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.PriorityKind;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.Repeat;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.Require;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.Requirement;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.RequirementKind;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.Sequence;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.Source;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Strict JSON round-trip codec for canonical evolved rewrite-program plans. */
public final class EvolutionRewriteProgramPlanCodec {
    private static final ObjectMapper JSON = new ObjectMapper(
        JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public String write(EvolutionRewriteProgramPlan plan) {
        return Objects.requireNonNull(plan, "plan").toCanonicalJson();
    }

    public Path write(Path output, EvolutionRewriteProgramPlan plan) {
        Objects.requireNonNull(output, "output");
        try {
            Path normalized = output.toAbsolutePath().normalize();
            Path parent = normalized.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(normalized, write(plan), StandardCharsets.UTF_8);
            return normalized;
        } catch (IOException exception) {
            throw new IllegalStateException(
                "Unable to write evolution rewrite-program plan", exception);
        }
    }

    public EvolutionRewriteProgramPlan read(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException(
                "rewrite-program plan JSON must not be blank");
        }
        try {
            ObjectNode root = object(JSON.readTree(json), "plan");
            requireExactFields(
                root,
                Set.of(
                    "schema",
                    "genomeHash",
                    "maxNodes",
                    "maxDepth",
                    "root",
                    "alphaStructuralHash",
                    "contentHash"),
                "plan");
            return new EvolutionRewriteProgramPlan(
                text(root, "schema", "plan"),
                text(root, "genomeHash", "plan"),
                readNode(required(root, "root", "plan")),
                integer(root, "maxNodes", "plan"),
                integer(root, "maxDepth", "plan"),
                text(root, "alphaStructuralHash", "plan"),
                text(root, "contentHash", "plan"));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                "Invalid evolution rewrite-program plan JSON", exception);
        }
    }

    public EvolutionRewriteProgramPlan read(Path input) {
        Objects.requireNonNull(input, "input");
        try {
            return read(Files.readString(input, StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new IllegalArgumentException(
                "Unable to read evolution rewrite-program plan", exception);
        }
    }

    private static Node readNode(JsonNode value) {
        ObjectNode node = object(value, "program node");
        String nodeType = text(node, "nodeType", "program node");
        String nodeId = text(node, "nodeId", "program node");
        return switch (nodeType) {
            case "SOURCE" -> {
                requireExactFields(
                    node,
                    Set.of("nodeType", "nodeId", "geneIds"),
                    nodeType);
                yield new Source(nodeId, strings(node, "geneIds", nodeType));
            }
            case "CHOICE" -> {
                requireExactFields(
                    node,
                    Set.of("nodeType", "nodeId", "alternatives"),
                    nodeType);
                yield new Choice(
                    nodeId,
                    nodes(node, "alternatives", nodeType));
            }
            case "FIRST_APPLICABLE" -> {
                requireExactFields(
                    node,
                    Set.of("nodeType", "nodeId", "alternatives"),
                    nodeType);
                yield new FirstApplicable(
                    nodeId,
                    nodes(node, "alternatives", nodeType));
            }
            case "SEQUENCE" -> {
                requireExactFields(
                    node,
                    Set.of("nodeType", "nodeId", "steps"),
                    nodeType);
                yield new Sequence(nodeId, nodes(node, "steps", nodeType));
            }
            case "REPEAT" -> {
                requireExactFields(
                    node,
                    Set.of(
                        "nodeType",
                        "nodeId",
                        "minIterations",
                        "maxIterations",
                        "body"),
                    nodeType);
                yield new Repeat(
                    nodeId,
                    readNode(required(node, "body", nodeType)),
                    integer(node, "minIterations", nodeType),
                    integer(node, "maxIterations", nodeType));
            }
            case "REQUIRE" -> {
                requireExactFields(
                    node,
                    Set.of("nodeType", "nodeId", "requirement", "body"),
                    nodeType);
                ObjectNode specification = object(
                    required(node, "requirement", nodeType),
                    "requirement");
                requireExactFields(
                    specification,
                    Set.of("kind", "threshold"),
                    "requirement");
                yield new Require(
                    nodeId,
                    readNode(required(node, "body", nodeType)),
                    new Requirement(
                        enumValue(
                            RequirementKind.class,
                            text(specification, "kind", "requirement")),
                        integer(specification, "threshold", "requirement")));
            }
            case "PRIORITIZE" -> {
                requireExactFields(
                    node,
                    Set.of("nodeType", "nodeId", "priority", "body"),
                    nodeType);
                ObjectNode specification = object(
                    required(node, "priority", nodeType),
                    "priority");
                requireExactFields(
                    specification,
                    Set.of("kind", "preferredGeneIds"),
                    "priority");
                yield new Prioritize(
                    nodeId,
                    readNode(required(node, "body", nodeType)),
                    new Priority(
                        enumValue(
                            PriorityKind.class,
                            text(specification, "kind", "priority")),
                        strings(
                            specification,
                            "preferredGeneIds",
                            "priority")));
            }
            case "PRUNE" -> {
                requireExactFields(
                    node,
                    Set.of(
                        "nodeType",
                        "nodeId",
                        "maxCandidates",
                        "reason",
                        "body"),
                    nodeType);
                yield new Prune(
                    nodeId,
                    readNode(required(node, "body", nodeType)),
                    integer(node, "maxCandidates", nodeType),
                    text(node, "reason", nodeType));
            }
            default -> throw new IllegalArgumentException(
                "Unsupported program node type: " + nodeType);
        };
    }

    private static List<Node> nodes(
        ObjectNode object,
        String field,
        String context
    ) {
        JsonNode value = required(object, field, context);
        if (!value.isArray()) {
            throw new IllegalArgumentException(
                context + "." + field + " must be an array");
        }
        List<Node> result = new ArrayList<>();
        value.forEach(item -> result.add(readNode(item)));
        return List.copyOf(result);
    }

    private static List<String> strings(
        ObjectNode object,
        String field,
        String context
    ) {
        JsonNode value = required(object, field, context);
        if (!value.isArray()) {
            throw new IllegalArgumentException(
                context + "." + field + " must be an array");
        }
        List<String> result = new ArrayList<>();
        for (JsonNode item : value) {
            if (!item.isTextual()) {
                throw new IllegalArgumentException(
                    context + "." + field + " must contain strings");
            }
            result.add(item.textValue());
        }
        return List.copyOf(result);
    }

    private static JsonNode required(
        ObjectNode object,
        String field,
        String context
    ) {
        JsonNode value = object.get(field);
        if (value == null || value.isNull()) {
            throw new IllegalArgumentException(
                context + "." + field + " is required");
        }
        return value;
    }

    private static String text(
        ObjectNode object,
        String field,
        String context
    ) {
        JsonNode value = required(object, field, context);
        if (!value.isTextual()) {
            throw new IllegalArgumentException(
                context + "." + field + " must be a string");
        }
        return value.textValue();
    }

    private static int integer(
        ObjectNode object,
        String field,
        String context
    ) {
        JsonNode value = required(object, field, context);
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new IllegalArgumentException(
                context + "." + field + " must be an integer");
        }
        return value.intValue();
    }

    private static ObjectNode object(JsonNode value, String context) {
        if (!(value instanceof ObjectNode object)) {
            throw new IllegalArgumentException(context + " must be an object");
        }
        return object;
    }

    private static void requireExactFields(
        ObjectNode object,
        Set<String> expected,
        String context
    ) {
        LinkedHashSet<String> actual = new LinkedHashSet<>();
        Iterator<String> names = object.fieldNames();
        while (names.hasNext()) {
            actual.add(names.next());
        }
        if (!actual.equals(expected) && !Set.copyOf(actual).equals(expected)) {
            LinkedHashSet<String> missing = new LinkedHashSet<>(expected);
            missing.removeAll(actual);
            LinkedHashSet<String> unexpected = new LinkedHashSet<>(actual);
            unexpected.removeAll(expected);
            throw new IllegalArgumentException(
                context + " fields differ; missing=" + missing
                    + ", unexpected=" + unexpected);
        }
    }

    private static <E extends Enum<E>> E enumValue(
        Class<E> type,
        String value
    ) {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                "Unsupported " + type.getSimpleName() + ": " + value,
                exception);
        }
    }
}
