package de.regelsuche.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.plugin.PluginArtifactVerification.Status;
import de.regelsuche.plugin.PluginTrustStore.ArtifactRevocation;
import de.regelsuche.plugin.PluginTrustStore.KeyStatus;
import de.regelsuche.plugin.PluginTrustStore.PublisherKey;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PluginArtifactTrustTest {
    private static final String PRIVATE_KEY_BASE64 =
        "MC4CAQAwBQYDK2VwBCIEIMiheLtWUyHyTH5K4ObomNdyw8wYX2pjwLsDNyYAO86v";
    private static final String PUBLIC_KEY_BASE64 =
        "MCowBQYDK2VwAyEAH0nHmwIVDzgfBwZMgSccK3RZqKbROm8/OPg5KGpDcZk=";
    private static final String PUBLISHER_ID = "org.example.publisher";
    private static final String KEY_ID = "release-2026";

    @Test
    void requireVerifiedLoadsOnlyTrustedArtifactBeforeClassLoading(@TempDir Path tempDir) throws Exception {
        Path plugins = tempDir.resolve("plugins");
        Path artifact = compilePluginJar(plugins, "TrustedExamplePlugin", "trusted-example");
        KeyPair keyPair = fixedKeyPair();
        writeSignature(artifact, keyPair.getPrivate());
        Path trustStore = writeTrustStore(
            tempDir.resolve("trust-store.json"),
            KeyStatus.ACTIVE,
            List.of()
        );

        PluginRuntimeConfig runtimeConfig = new PluginRuntimeConfig(
            plugins,
            tempDir.resolve("rules"),
            false,
            Set.of(),
            Set.of()
        );
        try (TrustedPluginRuntime runtime = TrustedPluginRuntime.open(
            runtimeConfig,
            new PluginArtifactTrustConfig(trustStore, PluginTrustPolicy.REQUIRE_VERIFIED)
        )) {
            PluginArtifactGate.GateResult gate = runtime.gateResult();
            assertEquals(List.of("trusted-example.jar"), gate.admittedArtifacts());
            assertTrue(gate.blockedArtifacts().isEmpty());
            PluginArtifactVerification verification = gate.verifications().getFirst();
            assertEquals(Status.VERIFIED_TRUSTED, verification.status());
            assertTrue(verification.signaturePresent());
            assertTrue(verification.signatureVerified());
            assertTrue(verification.trusted());
            assertEquals(PUBLISHER_ID, verification.publisherId());
            assertEquals(KEY_ID, verification.keyId());
            assertTrue(runtime.loadedPlugins().stream()
                .anyMatch(plugin -> plugin.id().equals("trusted-example")));
            assertTrue(runtime.diagnostics().stream()
                .anyMatch(item -> item.message().contains("trustStatus=VERIFIED_TRUSTED")));
        }
    }

    @Test
    void strictPolicyBlocksUnsignedAndTamperedArtifacts(@TempDir Path tempDir) throws Exception {
        Path plugins = tempDir.resolve("plugins");
        Path tampered = compilePluginJar(plugins, "TamperedExamplePlugin", "tampered-example");
        writeSignature(tampered, fixedKeyPair().getPrivate());
        Files.write(tampered, new byte[] {0x00}, StandardOpenOption.APPEND);
        compilePluginJar(plugins, "UnsignedExamplePlugin", "unsigned-example");
        Path trustStore = writeTrustStore(
            tempDir.resolve("trust-store.json"),
            KeyStatus.ACTIVE,
            List.of()
        );

        try (TrustedPluginRuntime runtime = TrustedPluginRuntime.open(
            new PluginRuntimeConfig(plugins, tempDir.resolve("rules"), false, Set.of(), Set.of()),
            new PluginArtifactTrustConfig(trustStore, PluginTrustPolicy.REQUIRE_VERIFIED)
        )) {
            assertTrue(runtime.gateResult().admittedArtifacts().isEmpty());
            assertEquals(
                List.of("tampered-example.jar", "unsigned-example.jar"),
                runtime.gateResult().blockedArtifacts()
            );
            assertTrue(runtime.loadedPlugins().isEmpty());
            assertEquals(
                Status.ARTIFACT_HASH_MISMATCH,
                verification(runtime, "tampered-example.jar").status()
            );
            assertEquals(
                Status.MISSING_SIGNATURE_MANIFEST,
                verification(runtime, "unsigned-example.jar").status()
            );
        }
    }

    @Test
    void warnPolicyAdmitsUnsignedArtifactButRetainsDecision(@TempDir Path tempDir) throws Exception {
        Path plugins = tempDir.resolve("plugins");
        compilePluginJar(plugins, "WarningExamplePlugin", "warning-example");

        try (TrustedPluginRuntime runtime = TrustedPluginRuntime.open(
            new PluginRuntimeConfig(plugins, tempDir.resolve("rules"), false, Set.of(), Set.of()),
            new PluginArtifactTrustConfig(tempDir.resolve("missing-trust-store.json"), PluginTrustPolicy.WARN)
        )) {
            assertEquals(List.of("warning-example.jar"), runtime.gateResult().admittedArtifacts());
            PluginArtifactVerification verification = verification(runtime, "warning-example.jar");
            assertEquals(Status.MISSING_SIGNATURE_MANIFEST, verification.status());
            assertFalse(verification.trusted());
            assertEquals(List.of("MISSING_SIGNATURE_MANIFEST"), verification.warnings());
            assertTrue(runtime.loadedPlugins().stream()
                .anyMatch(plugin -> plugin.id().equals("warning-example")));
        }
    }

    @Test
    void revokedKeyAndArtifactAreRejectedExplicitly(@TempDir Path tempDir) throws Exception {
        Path plugins = tempDir.resolve("plugins");
        Path artifact = compilePluginJar(plugins, "RevokedExamplePlugin", "revoked-example");
        writeSignature(artifact, fixedKeyPair().getPrivate());
        String artifactHash = PluginArtifactVerifier.sha256(Files.readAllBytes(artifact));

        Path revokedKeyStore = writeTrustStore(
            tempDir.resolve("revoked-key-store.json"),
            KeyStatus.REVOKED,
            List.of()
        );
        PluginArtifactVerification revokedKey = new PluginArtifactVerifier(
            PluginTrustStore.load(revokedKeyStore)
        ).verify(artifact);
        assertEquals(Status.REVOKED_KEY, revokedKey.status());
        assertFalse(revokedKey.trusted());

        Path revokedArtifactStore = writeTrustStore(
            tempDir.resolve("revoked-artifact-store.json"),
            KeyStatus.ACTIVE,
            List.of(new ArtifactRevocation(artifactHash, "publisher incident response"))
        );
        PluginArtifactVerification revokedArtifact = new PluginArtifactVerifier(
            PluginTrustStore.load(revokedArtifactStore)
        ).verify(artifact);
        assertEquals(Status.REVOKED_ARTIFACT, revokedArtifact.status());
        assertFalse(revokedArtifact.trusted());
    }

    @Test
    void trustStoreParsingIsStrictAndVerificationEvidenceIsTamperEvident(@TempDir Path tempDir) throws Exception {
        Path invalid = tempDir.resolve("invalid.json");
        Files.writeString(invalid, """
            {"schema":"regelsuche.plugin-trust-store/v1","keys":[],"revokedArtifacts":[],"unknown":true}
            """);
        assertThrows(IllegalArgumentException.class, () -> PluginTrustStore.load(invalid));

        PluginArtifactVerification verification = PluginArtifactVerification.create(
            "demo.jar",
            "sha256:" + "1".repeat(64),
            "demo.jar.sig.json",
            Status.VERIFIED_TRUSTED,
            true,
            true,
            true,
            PUBLISHER_ID,
            KEY_ID,
            List.of()
        );
        assertTrue(verification.toCanonicalJson().contains(verification.contentHash()));
        assertThrows(IllegalArgumentException.class, () -> new PluginArtifactVerification(
            verification.schema(),
            verification.artifactFileName(),
            verification.artifactSha256(),
            verification.manifestFileName(),
            verification.status(),
            verification.signaturePresent(),
            verification.signatureVerified(),
            verification.trusted(),
            verification.publisherId(),
            verification.keyId(),
            verification.warnings(),
            "sha256:" + "0".repeat(64)
        ));
    }

    private PluginArtifactVerification verification(TrustedPluginRuntime runtime, String fileName) {
        return runtime.gateResult().verifications().stream()
            .filter(item -> item.artifactFileName().equals(fileName))
            .findFirst()
            .orElseThrow();
    }

    private Path writeTrustStore(
        Path path,
        KeyStatus keyStatus,
        List<ArtifactRevocation> revocations
    ) {
        PublisherKey key = new PublisherKey(
            PUBLISHER_ID,
            KEY_ID,
            PluginSignatureManifest.ALGORITHM,
            PUBLIC_KEY_BASE64,
            keyStatus,
            keyStatus == KeyStatus.RETIRED ? "release-2027" : ""
        );
        return new PluginTrustStore(
            PluginTrustStore.SCHEMA,
            List.of(key),
            revocations
        ).write(path);
    }

    private void writeSignature(Path artifact, PrivateKey privateKey) throws Exception {
        String artifactHash = PluginArtifactVerifier.sha256(Files.readAllBytes(artifact));
        byte[] payload = PluginSignatureManifest.signedPayload(
            artifact.getFileName().toString(),
            artifactHash,
            PUBLISHER_ID,
            KEY_ID,
            PluginSignatureManifest.ALGORITHM
        );
        Signature signer = Signature.getInstance(PluginSignatureManifest.ALGORITHM);
        signer.initSign(privateKey);
        signer.update(payload);
        String signature = Base64.getEncoder().encodeToString(signer.sign());
        PluginSignatureManifest.create(
            artifact.getFileName().toString(),
            artifactHash,
            PUBLISHER_ID,
            KEY_ID,
            signature
        ).write(PluginSignatureManifest.sidecarFor(artifact));
    }

    private KeyPair fixedKeyPair() throws Exception {
        KeyFactory factory = KeyFactory.getInstance(PluginSignatureManifest.ALGORITHM);
        PrivateKey privateKey = factory.generatePrivate(new PKCS8EncodedKeySpec(
            Base64.getDecoder().decode(PRIVATE_KEY_BASE64)));
        PublicKey publicKey = factory.generatePublic(new X509EncodedKeySpec(
            Base64.getDecoder().decode(PUBLIC_KEY_BASE64)));
        return new KeyPair(publicKey, privateKey);
    }

    private Path compilePluginJar(Path pluginsDirectory, String className, String pluginId) throws Exception {
        Files.createDirectories(pluginsDirectory);
        Path workspace = pluginsDirectory.getParent().resolve("compile-" + pluginId);
        Path sourceRoot = workspace.resolve("src");
        Path classesRoot = workspace.resolve("classes");
        Path source = sourceRoot.resolve("external").resolve(className + ".java");
        Files.createDirectories(source.getParent());
        Files.createDirectories(classesRoot);
        Files.writeString(source, """
            package external;

            import de.regelsuche.plugin.RegelsuchePlugin;

            public final class %s implements RegelsuchePlugin {
                public String id() { return "%s"; }
                public String name() { return "%s"; }
                public String version() { return "1.0.0"; }
                public String provenance() { return "https://example.test/%s/releases/1.0.0"; }
            }
            """.formatted(className, pluginId, className, pluginId), StandardCharsets.UTF_8);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "tests require a full JDK");
        int exitCode = compiler.run(
            null,
            null,
            null,
            "-classpath",
            System.getProperty("java.class.path"),
            "-d",
            classesRoot.toString(),
            source.toString()
        );
        assertEquals(0, exitCode, "dynamic plugin compilation failed");

        Path artifact = pluginsDirectory.resolve(pluginId + ".jar");
        try (OutputStream file = Files.newOutputStream(artifact);
             JarOutputStream jar = new JarOutputStream(file)) {
            try (var files = Files.walk(classesRoot)) {
                for (Path classFile : files
                    .filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(Path::toString))
                    .toList()) {
                    String entryName = classesRoot.relativize(classFile)
                        .toString()
                        .replace('\\', '/');
                    JarEntry entry = new JarEntry(entryName);
                    entry.setTime(0L);
                    jar.putNextEntry(entry);
                    jar.write(Files.readAllBytes(classFile));
                    jar.closeEntry();
                }
            }
            JarEntry service = new JarEntry(
                "META-INF/services/de.regelsuche.plugin.RegelsuchePlugin");
            service.setTime(0L);
            jar.putNextEntry(service);
            jar.write(("external." + className + "\n").getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        return artifact;
    }
}
