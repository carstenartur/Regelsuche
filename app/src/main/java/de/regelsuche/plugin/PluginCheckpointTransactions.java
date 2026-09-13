package de.regelsuche.plugin;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import de.regelsuche.plugin.PluginCheckpointAuthority.AcceptedState;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 * External, installation-scoped transaction ledger. Unlike the legacy boolean CAS,
 * a lost response can leave a submitted operation's outcome unknown.
 * Providers must commit the selected pair and immutable terminal receipt together.
 * Missing receipts are not proof of rejection: a request may still be in flight.
 */
public interface PluginCheckpointTransactions {
    Scope scope();
    AcceptedState read() throws IOException;
    Optional<Decision> lookup(String operationId) throws IOException;
    Decision submit(Operation operation) throws IOException;

    enum Outcome { COMMITTED, REJECTED, OUTCOME_UNKNOWN }

    record Scope(String installationId, String trustDomain, String rootTrustStoreHash) {
        public Scope {
            identifier(installationId);
            identifier(trustDomain);
            PluginSignatureManifest.requireSha256(rootTrustStoreHash, "rootTrustStoreHash");
        }
    }

    record Operation(String schema, Scope scope, String operationId, String intentHash,
            AcceptedState expected, AcceptedState update) {
        public static final String SCHEMA = "regelsuche.plugin-checkpoint-operation/v1";
        public static final int MAX_BYTES = 16384;
        private static final com.fasterxml.jackson.databind.ObjectMapper JSON = JsonMapper.builder(
            JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES).build();

        public Operation(Scope scope, String id, String intent, AcceptedState expected, AcceptedState update) {
            this(SCHEMA, scope, id, intent, expected, update);
        }

        public Operation {
            if (!SCHEMA.equals(schema)) throw new IllegalArgumentException("unsupported checkpoint operation");
            Objects.requireNonNull(scope, "scope");
            identifier(operationId);
            PluginSignatureManifest.requireSha256(intentHash, "intentHash");
            Objects.requireNonNull(expected, "expected");
            Objects.requireNonNull(update, "update");
            if (update.checkpoint() == null || update.installationHash().equals(expected.installationHash())
                    || !scope.trustDomain().equals(update.checkpoint().trustDomainId())
                    || expected.checkpoint() != null
                        && !scope.trustDomain().equals(expected.checkpoint().trustDomainId())) {
                throw new IllegalArgumentException("operation must bind a changed generation in the same trust domain");
            }
            long prior = expected.checkpoint() == null ? 0 : expected.checkpoint().sequence();
            boolean identicalTrust = update.checkpoint().equals(expected.checkpoint());
            if (!identicalTrust && (prior == Long.MAX_VALUE || update.checkpoint().sequence() != prior + 1)) {
                throw new IllegalArgumentException("operation trust must be unchanged or the immediate successor");
            }
        }

        public byte[] canonicalBytes() {
            return PluginDistributionJson.canonical(this).getBytes(StandardCharsets.UTF_8);
        }

        public static Operation read(byte[] bytes) {
            if (bytes == null || bytes.length > MAX_BYTES) throw new IllegalArgumentException("operation byte limit");
            try {
                Operation value = JSON.readValue(bytes, Operation.class);
                if (value == null || !Arrays.equals(bytes, value.canonicalBytes())) {
                    throw new IllegalArgumentException("operation must have exact canonical bytes");
                }
                return value;
            } catch (IOException malformed) {
                throw new IllegalArgumentException("invalid checkpoint operation", malformed);
            }
        }
    }

    /** Data from the externally authenticated authority, not a caller-supplied admission gate. */
    record Decision(Operation operation, Outcome outcome) {
        public Decision {
            Objects.requireNonNull(operation, "operation");
            Objects.requireNonNull(outcome, "outcome");
        }
    }

    static String identifier(String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}")) {
            throw new IllegalArgumentException("checkpoint identifier must contain 1..128 ASCII identifier characters");
        }
        return value;
    }
}
