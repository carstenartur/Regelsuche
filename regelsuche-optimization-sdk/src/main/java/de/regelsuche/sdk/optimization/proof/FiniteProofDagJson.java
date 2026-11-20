package de.regelsuche.sdk.optimization.proof;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.util.StdConverter;
import de.regelsuche.math.algorithms.modular.CongruenceCoverage;
import de.regelsuche.math.algorithms.proof.FiniteProofDag.*;
import java.io.IOException;
import java.io.Writer;
import java.math.BigInteger;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Versioned, integer-preserving proof transport. Decoding alone never validates a claim. */
public final class FiniteProofDagJson {
    private static final int MAX_CHARACTERS = 2_000_000;
    private static final JsonFactory FACTORY = JsonFactory.builder()
        .streamReadConstraints(StreamReadConstraints.builder().maxNumberLength(20_000).maxNestingDepth(32).build())
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .build();
    private static final ObjectMapper MAPPER = JsonMapper.builder(FACTORY)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
            DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
        .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
        .addMixIn(Node.class, NodeKinds.class)
        .addMixIn(CongruenceCoverage.Certificate.class, ExactShape.class)
        .build();

    private FiniteProofDagJson() {}

    public static String write(Proof proof) throws JsonProcessingException {
        var output = new PacketWriter();
        try {
            MAPPER.writeValue(output, new Packet(1, proof));
        } catch (IOException failure) {
            // Jackson may wrap a writer failure with the path to the current node.
            if (output.exceededLimit) throw new IllegalArgumentException("PROOF_TRANSPORT_SIZE_LIMIT");
            if (failure instanceof JsonProcessingException json) throw json;
            throw JsonMappingException.fromUnexpectedIOE(failure);
        }
        return output.text.toString();
    }

    public static Proof read(String json) throws JsonProcessingException {
        var packet = MAPPER.readValue(checkedSize(json), Packet.class);
        if (packet == null || packet.version() != 1) throw new IllegalArgumentException("INVALID_PROOF_VERSION");
        return packet.proof();
    }

    private static String checkedSize(String text) {
        if (text.length() > MAX_CHARACTERS) throw new IllegalArgumentException("PROOF_TRANSPORT_SIZE_LIMIT");
        return text;
    }

    /** Count UTF-16 code units before retaining each chunk from the JSON generator. */
    private static final class PacketWriter extends Writer {
        private final StringBuilder text = new StringBuilder();
        private boolean exceededLimit;

        @Override public void write(char[] chunk, int offset, int length) throws IOException {
            checkRoom(length);
            text.append(chunk, offset, length);
        }

        @Override public void write(String chunk, int offset, int length) throws IOException {
            checkRoom(length);
            text.append(chunk, offset, offset + length);
        }

        private void checkRoom(int length) throws IOException {
            if (length > MAX_CHARACTERS - text.length()) {
                exceededLimit = true;
                throw new IOException("PROOF_TRANSPORT_SIZE_LIMIT");
            }
        }

        @Override public void flush() {}
        @Override public void close() {}
    }

    private record Packet(int version, Proof proof) {
        private Packet { Objects.requireNonNull(proof); }
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = ExactLeaf.class, name = "exact"),
        @JsonSubTypes.Type(value = HunterLeaf.class, name = "hunter"),
        @JsonSubTypes.Type(value = HallLeaf.class, name = "hall"),
        @JsonSubTypes.Type(value = Maximum.class, name = "maximum"),
        @JsonSubTypes.Type(value = Exists.class, name = "exists")
    })
    private interface NodeKinds {}

    @JsonSerialize(converter = ToExactFields.class)
    @JsonDeserialize(converter = FromExactFields.class)
    private interface ExactShape {}
    private record ExactFields(CongruenceCoverage.Request source, List<CongruenceCoverage.Term> terms,
            BigInteger coveredCount, BigInteger leastIndex) {}
    private static final class ToExactFields extends StdConverter<CongruenceCoverage.Certificate, ExactFields> {
        @Override public ExactFields convert(CongruenceCoverage.Certificate certificate) {
            return new ExactFields(certificate.source(), certificate.terms(), certificate.coveredCount(),
                certificate.leastIndex().orElse(null));
        }
    }
    private static final class FromExactFields extends StdConverter<ExactFields, CongruenceCoverage.Certificate> {
        @Override public CongruenceCoverage.Certificate convert(ExactFields fields) {
            return new CongruenceCoverage.Certificate(fields.source(), fields.terms(), fields.coveredCount(),
                Optional.ofNullable(fields.leastIndex()));
        }
    }
}
