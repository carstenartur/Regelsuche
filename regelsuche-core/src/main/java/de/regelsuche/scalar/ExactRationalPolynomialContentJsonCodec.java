package de.regelsuche.scalar;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.regelsuche.json.JsonWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Strict JSON codec for rational-polynomial content evidence v1. */
public final class ExactRationalPolynomialContentJsonCodec {
    /**
     * Covers all 65 source coefficients plus two 65-entry vectors at the v1
     * 262,144-bit intermediate ceiling, with bounded structural headroom.
     */
    public static final int MAX_JSON_CHARACTERS = 16_000_000;

    private static final Set<String> ROOT_FIELDS = Set.of(
        "domainId",
        "status",
        "detailCode",
        "sourceCoefficients",
        "budget",
        "normalization",
        "work",
        "certificateHash");
    private static final Set<String> BUDGET_FIELDS = Set.of(
        "maxDegree",
        "maxCoefficientBits",
        "maxIntermediateBits",
        "maxArithmeticSteps");
    private static final Set<String> NORMALIZATION_FIELDS = Set.of(
        "denominatorClearingFactor",
        "integralCoefficientsAscending",
        "integerContent",
        "primitiveCoefficientsAscending",
        "scalar");
    private static final Set<String> WORK_FIELDS = Set.of(
        "coefficientsVisited",
        "gcdOperations",
        "lcmOperations",
        "multiplications",
        "divisions",
        "signAdjustments",
        "reconstructionChecks",
        "totalSteps");
    private static final ObjectMapper MAPPER = new ObjectMapper(
        JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build());

    private final ExactRationalPolynomialContentVerifier verifier;

    public ExactRationalPolynomialContentJsonCodec() {
        this(new ExactRationalPolynomialContentVerifier());
    }

    ExactRationalPolynomialContentJsonCodec(
        ExactRationalPolynomialContentVerifier verifier
    ) {
        this.verifier = Objects.requireNonNull(verifier, "verifier");
    }

    public String write(
        ExactRationalPolynomialContentEvidence evidence
    ) {
        Objects.requireNonNull(evidence, "evidence");
        ExactRationalPolynomialContentVerifier.SerializedEvidence value =
            evidence.serialized();
        JsonWriter writer = new JsonWriter()
            .beginObject()
            .property("domainId", value.domainId())
            .property("status", value.status().name())
            .property("detailCode", value.detailCode())
            .stringArray(
                "sourceCoefficients",
                value.sourceCoefficients())
            .object("budget", nested -> nested
                .property(
                    "maxDegree",
                    value.budget().maxDegree())
                .property(
                    "maxCoefficientBits",
                    value.budget().maxCoefficientBits())
                .property(
                    "maxIntermediateBits",
                    value.budget().maxIntermediateBits())
                .property(
                    "maxArithmeticSteps",
                    value.budget().maxArithmeticSteps()));
        if (value.normalization().isPresent()) {
            var result = value.normalization().orElseThrow();
            writer.object("normalization", nested -> nested
                .property(
                    "denominatorClearingFactor",
                    result.denominatorClearingFactor())
                .stringArray(
                    "integralCoefficientsAscending",
                    result.integralCoefficientsAscending())
                .property(
                    "integerContent",
                    result.integerContent())
                .stringArray(
                    "primitiveCoefficientsAscending",
                    result.primitiveCoefficientsAscending())
                .property("scalar", result.scalar()));
        } else {
            writer.nullProperty("normalization");
        }
        String json = writer
            .object("work", nested -> writeWork(nested, value.work()))
            .property("certificateHash", value.certificateHash())
            .endObject()
            .toString();
        if (json.length() > MAX_JSON_CHARACTERS) {
            throw new IllegalStateException(
                "issued content evidence exceeds the v1 JSON size envelope");
        }
        return json;
    }

    public DecodedEvidence readAndVerify(String json) {
        JsonNode root = parseObject(json);
        requireExactFields(root, ROOT_FIELDS, "evidence");
        JsonNode budgetNode = requiredObject(root, "budget");
        JsonNode workNode = requiredObject(root, "work");
        requireExactFields(budgetNode, BUDGET_FIELDS, "budget");
        requireExactFields(workNode, WORK_FIELDS, "work");

        var budget = new ExactRationalPolynomialContentNormalizer.Budget(
            integer(budgetNode, "maxDegree"),
            integer(budgetNode, "maxCoefficientBits"),
            integer(budgetNode, "maxIntermediateBits"),
            integer(budgetNode, "maxArithmeticSteps"));
        var work = new ExactRationalPolynomialContentEvidence.WorkLedger(
            integer(workNode, "coefficientsVisited"),
            integer(workNode, "gcdOperations"),
            integer(workNode, "lcmOperations"),
            integer(workNode, "multiplications"),
            integer(workNode, "divisions"),
            integer(workNode, "signAdjustments"),
            integer(workNode, "reconstructionChecks"),
            integer(workNode, "totalSteps"));
        if (work.totalSteps() > budget.maxArithmeticSteps()) {
            throw new IllegalArgumentException(
                "content work exceeds its declared budget");
        }

        var serialized = new ExactRationalPolynomialContentVerifier
            .SerializedEvidence(
                text(root, "domainId"),
                parseStatus(text(root, "status")),
                text(root, "detailCode"),
                stringArray(root, "sourceCoefficients"),
                budget,
                readNormalization(root.get("normalization")),
                work,
                text(root, "certificateHash"));
        var verification = verifier.verify(serialized);
        if (!verification.verified()) {
            throw new IllegalArgumentException(
                "content evidence verification failed: "
                    + verification.detailCode());
        }
        return new DecodedEvidence(serialized, verification);
    }

    private Optional<ExactRationalPolynomialContentVerifier
            .SerializedNormalization> readNormalization(JsonNode node) {
        if (node == null) {
            throw new IllegalArgumentException(
                "missing required field: normalization");
        }
        if (node.isNull()) {
            return Optional.empty();
        }
        if (!node.isObject()) {
            throw new IllegalArgumentException(
                "normalization must be an object or null");
        }
        requireExactFields(
            node,
            NORMALIZATION_FIELDS,
            "normalization");
        return Optional.of(
            new ExactRationalPolynomialContentVerifier
                .SerializedNormalization(
                    text(node, "denominatorClearingFactor"),
                    stringArray(
                        node,
                        "integralCoefficientsAscending"),
                    text(node, "integerContent"),
                    stringArray(
                        node,
                        "primitiveCoefficientsAscending"),
                    text(node, "scalar")));
    }

    private static void writeWork(
        JsonWriter writer,
        ExactRationalPolynomialContentEvidence.WorkLedger work
    ) {
        writer
            .property(
                "coefficientsVisited",
                work.coefficientsVisited())
            .property("gcdOperations", work.gcdOperations())
            .property("lcmOperations", work.lcmOperations())
            .property("multiplications", work.multiplications())
            .property("divisions", work.divisions())
            .property("signAdjustments", work.signAdjustments())
            .property(
                "reconstructionChecks",
                work.reconstructionChecks())
            .property("totalSteps", work.totalSteps());
    }

    private JsonNode parseObject(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException(
                "content evidence JSON must not be blank");
        }
        if (json.length() > MAX_JSON_CHARACTERS) {
            throw new IllegalArgumentException(
                "content evidence JSON exceeds its size limit");
        }
        try (JsonParser parser = MAPPER.getFactory().createParser(json)) {
            JsonNode root = MAPPER.readTree(parser);
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException(
                    "content evidence JSON must be an object");
            }
            if (parser.nextToken() != null) {
                throw new IllegalArgumentException(
                    "content evidence JSON has trailing content");
            }
            return root;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                "invalid content evidence JSON",
                exception);
        } catch (IOException exception) {
            throw new IllegalStateException(
                "failed to close content evidence parser",
                exception);
        }
    }

    private static void requireExactFields(
        JsonNode node,
        Set<String> expected,
        String objectName
    ) {
        Set<String> actual = new HashSet<>();
        Iterator<String> names = node.fieldNames();
        names.forEachRemaining(actual::add);
        if (!actual.equals(expected)) {
            throw new IllegalArgumentException(
                objectName + " fields differ from the v1 contract");
        }
    }

    private static JsonNode requiredObject(
        JsonNode node,
        String field
    ) {
        JsonNode value = node.get(field);
        if (value == null || !value.isObject()) {
            throw new IllegalArgumentException(
                field + " must be an object");
        }
        return value;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) {
            throw new IllegalArgumentException(
                field + " must be a string");
        }
        return value.textValue();
    }

    private static int integer(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isInt()) {
            throw new IllegalArgumentException(
                field + " must be a 32-bit integer");
        }
        return value.intValue();
    }

    private static List<String> stringArray(
        JsonNode node,
        String field
    ) {
        JsonNode value = node.get(field);
        if (value == null || !value.isArray()) {
            throw new IllegalArgumentException(
                field + " must be an array");
        }
        List<String> result = new ArrayList<>();
        for (JsonNode item : value) {
            if (!item.isTextual()) {
                throw new IllegalArgumentException(
                    field + " entries must be strings");
            }
            result.add(item.textValue());
        }
        return List.copyOf(result);
    }

    private static ExactRationalPolynomialContentNormalizer.Status
            parseStatus(String value) {
        try {
            return ExactRationalPolynomialContentNormalizer.Status.valueOf(
                value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                "unsupported content evidence status: " + value,
                exception);
        }
    }

    public record DecodedEvidence(
        ExactRationalPolynomialContentVerifier.SerializedEvidence evidence,
        ExactRationalPolynomialContentVerifier.Verification verification
    ) {
        public DecodedEvidence {
            Objects.requireNonNull(evidence, "evidence");
            Objects.requireNonNull(verification, "verification");
            if (!verification.verified()) {
                throw new IllegalArgumentException(
                    "decoded content evidence must be verified");
            }
        }
    }
}
