package de.regelsuche.plugin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
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

    public PluginArtifactGate(PluginTrustStore trustStore, PluginTrustPolicy policy) {
        this.verifier = new PluginArtifactVerifier(Objects.requireNonNull(trustStore, "trustStore"));
        this.policy = policy == null ? PluginTrustPolicy.WARN : policy;
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

        List<Path> artifacts = listArtifacts(source);
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
        return new GateResult(policy, verifications, admitted, blocked);
    }

    private List<Path> listArtifacts(Path sourceDirectory) {
        if (!Files.isDirectory(sourceDirectory, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        try (var stream = Files.list(sourceDirectory)) {
            return stream
                .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                .filter(path -> path.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".jar"))
                .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                .toList();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to scan plugin directory " + sourceDirectory, exception);
        }
    }

    public record GateResult(
        PluginTrustPolicy policy,
        List<PluginArtifactVerification> verifications,
        List<String> admittedArtifacts,
        List<String> blockedArtifacts
    ) {
        public GateResult {
            Objects.requireNonNull(policy, "policy");
            verifications = verifications == null ? List.of() : verifications.stream()
                .sorted(Comparator.comparing(PluginArtifactVerification::artifactFileName))
                .toList();
            admittedArtifacts = normalizeNames(admittedArtifacts);
            blockedArtifacts = normalizeNames(blockedArtifacts);
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

        public String toCanonicalJson() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("schema", "regelsuche.plugin-artifact-gate/v1");
            payload.put("policy", policy.name());
            payload.put("verifications", verifications.stream()
                .map(GateResult::verificationPayload)
                .toList());
            payload.put("admittedArtifacts", admittedArtifacts);
            payload.put("blockedArtifacts", blockedArtifacts);
            try {
                return JSON.writeValueAsString(payload) + "\n";
            } catch (JsonProcessingException exception) {
                throw new IllegalStateException("Unable to serialize plugin artifact gate result", exception);
            }
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
