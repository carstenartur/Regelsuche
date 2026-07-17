package de.regelsuche.plugin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Materializes only policy-admitted plugin bytes into a private staging
 * directory. The staged bytes are the exact snapshot that was verified.
 */
public final class PluginArtifactGate {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    private final PluginArtifactVerifier verifier;
    private final PluginTrustPolicy policy;
    private final String trustStoreHash;

    public PluginArtifactGate(PluginTrustStore trustStore, PluginTrustPolicy policy) {
        PluginTrustStore effectiveTrustStore = Objects.requireNonNull(trustStore, "trustStore");
        this.verifier = new PluginArtifactVerifier(effectiveTrustStore);
        this.policy = policy == null ? PluginTrustPolicy.WARN : policy;
        this.trustStoreHash = PluginArtifactVerifier.sha256(
            effectiveTrustStore.toCanonicalJson().getBytes(StandardCharsets.UTF_8));
    }

    public GateResult materialize(Path sourceDirectory, Path stagingDirectory) {
        Objects.requireNonNull(sourceDirectory, "sourceDirectory");
        Objects.requireNonNull(stagingDirectory, "stagingDirectory");
        Path source = sourceDirectory.toAbsolutePath().normalize();
        Path staging = stagingDirectory.toAbsolutePath().normalize();
        if (source.equals(staging)) {
            throw new IllegalArgumentException("source and staging directories must differ");
        }
        try {
            Files.createDirectories(staging);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to create plugin staging directory", exception);
        }

        List<Path> artifacts = listArtifactEntries(source);
        List<PluginArtifactVerification> verifications = new ArrayList<>();
        List<String> admitted = new ArrayList<>();
        List<String> blocked = new ArrayList<>();
        for (Path artifact : artifacts) {
            PluginArtifactVerifier.ArtifactSnapshot snapshot = verifier.snapshot(artifact);
            PluginArtifactVerification verification = snapshot.verification();
            verifications.add(verification);
            if (verification.permittedBy(policy)) {
                Path target = staging.resolve(verification.artifactFileName());
                try {
                    Files.write(
                        target,
                        snapshot.artifactBytes(),
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE
                    );
                } catch (IOException exception) {
                    throw new IllegalStateException("Unable to materialize verified plugin artifact "
                        + verification.artifactFileName(), exception);
                }
                admitted.add(verification.artifactFileName());
            } else {
                blocked.add(verification.artifactFileName());
            }
        }
        return GateResult.create(policy, trustStoreHash, verifications, admitted, blocked);
    }

    /**
     * Lists every direct entry whose name claims to be a JAR, including
     * symlinks, directories and unreadable entries. The verifier classifies
     * non-regular entries as {@code UNREADABLE}; they must not disappear from
     * the admission ledger merely because they are unsafe to open.
     */
    private List<Path> listArtifactEntries(Path sourceDirectory) {
        if (!Files.isDirectory(sourceDirectory)) {
            return List.of();
        }
        try (var stream = Files.list(sourceDirectory)) {
            return stream
                .filter(path -> path.getFileName().toString()
                    .toLowerCase(java.util.Locale.ROOT)
                    .endsWith(".jar"))
                .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                .toList();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to scan plugin directory " + sourceDirectory, exception);
        }
    }

    public record GateResult(
        String schema,
        PluginTrustPolicy policy,
        String trustStoreHash,
        List<PluginArtifactVerification> verifications,
        List<String> admittedArtifacts,
        List<String> blockedArtifacts,
        String contentHash
    ) {
        public static final String SCHEMA = "regelsuche.plugin-artifact-gate/v1";

        public GateResult {
            if (!SCHEMA.equals(schema)) {
                throw new IllegalArgumentException("unsupported plugin artifact gate schema");
            }
            Objects.requireNonNull(policy, "policy");
            trustStoreHash = PluginSignatureManifest.requireSha256(
                trustStoreHash, "trustStoreHash");
            verifications = normalizeVerifications(verifications);
            admittedArtifacts = normalizeNames(admittedArtifacts);
            blockedArtifacts = normalizeNames(blockedArtifacts);
            validateAccounting(verifications, admittedArtifacts, blockedArtifacts);
            String expectedHash = contentHash(
                policy,
                trustStoreHash,
                verifications,
                admittedArtifacts,
                blockedArtifacts
            );
            if (!expectedHash.equals(contentHash)) {
                throw new IllegalArgumentException("plugin artifact gate contentHash mismatch");
            }
        }

        public static GateResult create(
            PluginTrustPolicy policy,
            String trustStoreHash,
            List<PluginArtifactVerification> verifications,
            List<String> admittedArtifacts,
            List<String> blockedArtifacts
        ) {
            Objects.requireNonNull(policy, "policy");
            String normalizedTrustStoreHash = PluginSignatureManifest.requireSha256(
                trustStoreHash, "trustStoreHash");
            List<PluginArtifactVerification> normalizedVerifications =
                normalizeVerifications(verifications);
            List<String> normalizedAdmitted = normalizeNames(admittedArtifacts);
            List<String> normalizedBlocked = normalizeNames(blockedArtifacts);
            validateAccounting(
                normalizedVerifications,
                normalizedAdmitted,
                normalizedBlocked
            );
            return new GateResult(
                SCHEMA,
                policy,
                normalizedTrustStoreHash,
                normalizedVerifications,
                normalizedAdmitted,
                normalizedBlocked,
                contentHash(
                    policy,
                    normalizedTrustStoreHash,
                    normalizedVerifications,
                    normalizedAdmitted,
                    normalizedBlocked
                )
            );
        }

        public String toCanonicalJson() {
            Map<String, Object> payload = basePayload(
                policy,
                trustStoreHash,
                verifications,
                admittedArtifacts,
                blockedArtifacts
            );
            payload.put("contentHash", contentHash);
            try {
                return JSON.writeValueAsString(payload) + "\n";
            } catch (JsonProcessingException exception) {
                throw new IllegalStateException("Unable to serialize plugin artifact gate result", exception);
            }
        }

        private static String contentHash(
            PluginTrustPolicy policy,
            String trustStoreHash,
            List<PluginArtifactVerification> verifications,
            List<String> admittedArtifacts,
            List<String> blockedArtifacts
        ) {
            try {
                return PluginArtifactVerifier.sha256(JSON.writeValueAsBytes(basePayload(
                    policy,
                    trustStoreHash,
                    verifications,
                    admittedArtifacts,
                    blockedArtifacts
                )));
            } catch (JsonProcessingException exception) {
                throw new IllegalStateException("Unable to hash plugin artifact gate result", exception);
            }
        }

        private static Map<String, Object> basePayload(
            PluginTrustPolicy policy,
            String trustStoreHash,
            List<PluginArtifactVerification> verifications,
            List<String> admittedArtifacts,
            List<String> blockedArtifacts
        ) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("schema", SCHEMA);
            payload.put("policy", policy.name());
            payload.put("trustStoreHash", trustStoreHash);
            payload.put("verifications", verifications.stream()
                .map(GateResult::verificationPayload)
                .toList());
            payload.put("admittedArtifacts", admittedArtifacts);
            payload.put("blockedArtifacts", blockedArtifacts);
            return payload;
        }

        private static Map<String, Object> verificationPayload(PluginArtifactVerification verification) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("schema", verification.schema());
            payload.put("artifactFileName", verification.artifactFileName());
            payload.put("artifactSha256", verification.artifactSha256());
            payload.put("manifestFileName", verification.manifestFileName());
            payload.put("status", verification.status().name());
            payload.put("signaturePresent", verification.signaturePresent());
            payload.put("signatureVerified", verification.signatureVerified());
            payload.put("trusted", verification.trusted());
            payload.put("publisherId", verification.publisherId());
            payload.put("keyId", verification.keyId());
            payload.put("warnings", verification.warnings());
            payload.put("contentHash", verification.contentHash());
            return payload;
        }

        private static List<PluginArtifactVerification> normalizeVerifications(
            List<PluginArtifactVerification> values
        ) {
            return values == null ? List.of() : values.stream()
                .map(value -> Objects.requireNonNull(value, "verification"))
                .sorted(Comparator.comparing(PluginArtifactVerification::artifactFileName))
                .toList();
        }

        private static void validateAccounting(
            List<PluginArtifactVerification> verifications,
            List<String> admittedArtifacts,
            List<String> blockedArtifacts
        ) {
            if (admittedArtifacts.stream().anyMatch(blockedArtifacts::contains)) {
                throw new IllegalArgumentException("an artifact cannot be both admitted and blocked");
            }
            List<String> accounted = new ArrayList<>(admittedArtifacts);
            accounted.addAll(blockedArtifacts);
            List<String> expected = verifications.stream()
                .map(PluginArtifactVerification::artifactFileName)
                .sorted()
                .toList();
            if (!accounted.stream().sorted().toList().equals(expected)) {
                throw new IllegalArgumentException("gate result does not account for every verified artifact");
            }
        }

        private static List<String> normalizeNames(List<String> values) {
            if (values == null) {
                return List.of();
            }
            return values.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .distinct()
                .sorted()
                .toList();
        }
    }
}
