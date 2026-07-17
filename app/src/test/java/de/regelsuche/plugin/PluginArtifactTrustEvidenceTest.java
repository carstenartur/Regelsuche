package de.regelsuche.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.plugin.PluginTrustStore.KeyStatus;
import de.regelsuche.plugin.PluginTrustStore.PublisherKey;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PluginArtifactTrustEvidenceTest {
    private static final String PRIVATE_KEY_BASE64 =
        "MC4CAQAwBQYDK2VwBCIEIMiheLtWUyHyTH5K4ObomNdyw8wYX2pjwLsDNyYAO86v";
    private static final String PUBLIC_KEY_BASE64 =
        "MCowBQYDK2VwAyEAH0nHmwIVDzgfBwZMgSccK3RZqKbROm8/OPg5KGpDcZk=";
    private static final String PUBLISHER_ID = "org.regelsuche.reference";
    private static final String KEY_ID = "reference-2026";

    @Test
    void writesByteIdenticalCanonicalReferenceEvidence(@TempDir Path tempDir) throws Exception {
        ReferenceBundle first = createReferenceBundle(tempDir.resolve("run-a"));
        ReferenceBundle second = createReferenceBundle(tempDir.resolve("run-b"));

        assertEquals(first.gateResult().toCanonicalJson(), second.gateResult().toCanonicalJson());
        assertEquals(first.verification().toCanonicalJson(), second.verification().toCanonicalJson());
        assertEquals(first.trustStore().toCanonicalJson(), second.trustStore().toCanonicalJson());
        assertEquals(first.signatureManifest().toCanonicalJson(), second.signatureManifest().toCanonicalJson());
        assertTrue(first.verification().trusted());

        Path output = Path.of("build", "reports", "plugin-artifact-trust");
        deleteRecursively(output);
        Files.createDirectories(output);
        Files.writeString(
            output.resolve("gate-result.json"),
            first.gateResult().toCanonicalJson(),
            StandardCharsets.UTF_8
        );
        Files.writeString(
            output.resolve("verification.json"),
            first.verification().toCanonicalJson(),
            StandardCharsets.UTF_8
        );
        first.trustStore().write(output.resolve("trust-store.json"));
        first.signatureManifest().write(output.resolve("signature-manifest.json"));
    }

    private ReferenceBundle createReferenceBundle(Path root) throws Exception {
        Path plugins = root.resolve("plugins");
        Path staging = root.resolve("staging");
        Files.createDirectories(plugins);
        Path artifact = writeDeterministicJar(plugins.resolve("reference-plugin.jar"));
        String artifactHash = PluginArtifactVerifier.sha256(Files.readAllBytes(artifact));

        PluginSignatureManifest manifest = signManifest(
            artifact.getFileName().toString(), artifactHash);
        manifest.write(PluginSignatureManifest.sidecarFor(artifact));

        PublisherKey publisherKey = new PublisherKey(
            PUBLISHER_ID,
            KEY_ID,
            PluginSignatureManifest.ALGORITHM,
            PUBLIC_KEY_BASE64,
            KeyStatus.ACTIVE,
            ""
        );
        PluginTrustStore trustStore = new PluginTrustStore(
            PluginTrustStore.SCHEMA,
            List.of(publisherKey),
            List.of()
        );
        PluginArtifactGate.GateResult gate = new PluginArtifactGate(
            trustStore,
            PluginTrustPolicy.REQUIRE_VERIFIED
        ).materialize(plugins, staging);
        assertEquals(List.of("reference-plugin.jar"), gate.admittedArtifacts());
        assertTrue(gate.blockedArtifacts().isEmpty());
        return new ReferenceBundle(
            gate,
            gate.verifications().getFirst(),
            trustStore,
            manifest
        );
    }

    private PluginSignatureManifest signManifest(
        String artifactFileName,
        String artifactHash
    ) throws Exception {
        byte[] payload = PluginSignatureManifest.signedPayload(
            artifactFileName,
            artifactHash,
            PUBLISHER_ID,
            KEY_ID,
            PluginSignatureManifest.ALGORITHM
        );
        PrivateKey privateKey = KeyFactory.getInstance(PluginSignatureManifest.ALGORITHM)
            .generatePrivate(new PKCS8EncodedKeySpec(
                Base64.getDecoder().decode(PRIVATE_KEY_BASE64)));
        Signature signer = Signature.getInstance(PluginSignatureManifest.ALGORITHM);
        signer.initSign(privateKey);
        signer.update(payload);
        return PluginSignatureManifest.create(
            artifactFileName,
            artifactHash,
            PUBLISHER_ID,
            KEY_ID,
            Base64.getEncoder().encodeToString(signer.sign())
        );
    }

    private Path writeDeterministicJar(Path artifact) throws IOException {
        try (OutputStream output = Files.newOutputStream(artifact);
             JarOutputStream jar = new JarOutputStream(output)) {
            writeEntry(jar, "META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n\n");
            writeEntry(
                jar,
                "META-INF/services/de.regelsuche.plugin.RegelsuchePlugin",
                "org.regelsuche.reference.ReferencePlugin\n"
            );
            writeEntry(
                jar,
                "org/regelsuche/reference/reference.txt",
                "deterministic signed plugin artifact\n"
            );
        }
        return artifact;
    }

    private void writeEntry(JarOutputStream jar, String name, String content) throws IOException {
        JarEntry entry = new JarEntry(name);
        entry.setTime(0L);
        jar.putNextEntry(entry);
        jar.write(content.getBytes(StandardCharsets.UTF_8));
        jar.closeEntry();
    }

    private void deleteRecursively(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private record ReferenceBundle(
        PluginArtifactGate.GateResult gateResult,
        PluginArtifactVerification verification,
        PluginTrustStore trustStore,
        PluginSignatureManifest signatureManifest
    ) {
    }
}
