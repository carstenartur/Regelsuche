package de.regelsuche.scalar;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.regelsuche.json.JsonWriter;
import java.io.IOException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Objects;
import java.util.Set;

/** Strict canonical JSON codec for exact-rational parse evidence. */
public final class ExactRationalEvidenceJsonCodec {
    public static final int MAX_JSON_CHARACTERS = 16_384;

    private static final Set<String> ROOT_FIELDS = Set.of(
        "domainId",
        "status",
        "detailCode",
        "sourceLiteral",
        "limits",
        "canonicalValue",
        "valueId",
        "certificateHash");
    private static final Set<String> LIMIT_FIELDS = Set.of(
        "maxLiteralCharacters",
        "maxDigits",
        "maxDecimalScale");
    private static final ObjectMapper MAPPER = new ObjectMapper(
        JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build());

    private final ExactRationalEvidenceVerifier verifier;

    public ExactRationalEvidenceJsonCodec() {
        this(new ExactRationalEvidenceVerifier());
    }

    ExactRationalEvidenceJsonCodec(
        ExactRationalEvidenceVerifier verifier
    ) {
        this.verifier = Objects.requireNonNull(verifier, "verifier");
    }

    public String write(ExactRationalParseEvidence evidence) {
        Objects.requireNonNull(evidence, "evidence");
        ExactRationalEvidenceVerifier.SerializedEvidence value =
            evidence.serialized();
        return new JsonWriter()
            .beginObject()
            .property("domainId", value.domainId())
            .property("status", value.status().name())
            .property("detailCode", value.detailCode())
            .property("sourceLiteral", value.sourceLiteral())
            .object("limits", writer -> writer
                .property(
                    "maxLiteralCharacters",
                    value.limits().maxLiteralCharacters())
                .property("maxDigits", value.limits().maxDigits())
                .property(
                    "maxDecimalScale",
                    value.limits().maxDecimalScale()))
            .property("canonicalValue", value.canonicalValue())
            .property("valueId", value.valueId())
            .property("certificateHash", value.certificateHash())
            .endObject()
            .toString();
    }

    public DecodedEvidence readAndVerify(String json) {
        JsonNode root = parseObject(json);
        requireExactFields(root, ROOT_FIELDS, "evidence");
        JsonNode limitsNode = requiredObject(root, "limits");
        requireExactFields(limitsNode, LIMIT_FIELDS, "limits");

        ExactRationalEvidenceVerifier.SerializedEvidence serialized =
            new ExactRationalEvidenceVerifier.SerializedEvidence(
                text(root, "domainId"),
                parseStatus(text(root, "status")),
                text(root, "detailCode"),
                text(root, "sourceLiteral"),
                new ExactRationalDomain.Limits(
                    integer(limitsNode, "maxLiteralCharacters"),
                    integer(limitsNode, "maxDigits"),
                    integer(limitsNode, "maxDecimalScale")),
                text(root, "canonicalValue"),
                text(root, "valueId"),
                text(root, "certificateHash"));
        ExactRationalEvidenceVerifier.Verification verification =
            verifier.verify(serialized);
        if (!verification.verified()) {
            throw new IllegalArgumentException(
                "exact rational evidence verification failed: "
                    + verification.detailCode());
        }
        return new DecodedEvidence(serialized, verification);
    }

    private JsonNode parseObject(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException(
                "exact rational evidence JSON must not be blank");
        }
        if (json.length() > MAX_JSON_CHARACTERS) {
            throw new IllegalArgumentException(
                "exact rational evidence JSON exceeds its size limit");
        }
        try (JsonParser parser = MAPPER.createParser(json)) {
            JsonNode root = MAPPER.readTree(parser);
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException(
                    "exact rational evidence JSON must be an object");
            }
            if (parser.nextToken() != null) {
                throw new IllegalArgumentException(
                    "exact rational evidence JSON has trailing content");
            }
            return root;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                "invalid exact rational evidence JSON",
                exception);
        } catch (IOException exception) {
            throw new IllegalStateException(
                "failed to close exact rational JSON parser",
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

    private static ExactRationalDomain.Status parseStatus(
        String value
    ) {
        try {
            return ExactRationalDomain.Status.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                "unsupported exact rational evidence status: " + value,
                exception);
        }
    }

    public record DecodedEvidence(
        ExactRationalEvidenceVerifier.SerializedEvidence evidence,
        ExactRationalEvidenceVerifier.Verification verification
    ) {
        public DecodedEvidence {
            Objects.requireNonNull(evidence, "evidence");
            Objects.requireNonNull(verification, "verification");
            if (!verification.verified()) {
                throw new IllegalArgumentException(
                    "decoded evidence must be semantically verified");
            }
        }
    }
}
