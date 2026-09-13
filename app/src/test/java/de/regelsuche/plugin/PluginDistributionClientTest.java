package de.regelsuche.plugin;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PluginDistributionClientTest {
    @TempDir Path directory;

    @Test
    void installsExactNetworkBytesAndRetainsCanonicalAuthorityBoundEvidence() throws Exception {
        try (var fixture = new PluginDistributionFixtures(); var transport = transport(fixture)) {
            byte[] jar = jar("first");
            var publication = publish(fixture, "1.0.0", jar, 1, "", fixture.publishers);
            var authority = new TestAuthority();
            var client = client(fixture, transport, authority);
            var installation = client.install(publication.sources(), request("1.0.0"));
            assertEquals("INSTALL", installation.operation());
            assertEquals(1, installation.artifacts().size());
            assertEquals(publication.index().contentHash(), installation.indexContentHash());
            assertEquals(publication.revision().contentHash(), installation.checkpoint().revisionHash());
            assertEquals(1L, authority.read().checkpoint().sequence());
            assertEquals(installation.contentHash(), authority.read().installationHash());
            assertArrayEquals(jar, client.readArtifact(installation.artifacts().getFirst().identityHash()));
            assertEquals(installation.toCanonicalJson(), Files.readString(generation(installation)
                .resolve("installation.json")));
            assertEquals(installation.toCanonicalJson(), client.active().orElseThrow().toCanonicalJson());
            var restarted = client(fixture, transport, authority);
            assertEquals(installation.contentHash(), restarted.active().orElseThrow().contentHash());
            assertArrayEquals(jar, restarted.readArtifact(installation.artifacts().getFirst().identityHash()));
            Path evidence = Path.of("build", "reports", "plugin-distribution", "installed");
            Files.createDirectories(evidence);
            try (var files = Files.walk(generation(installation))) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    Path target = evidence.resolve(generation(installation).relativize(file));
                    Files.createDirectories(target.getParent());
                    Files.copy(file, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
            fixture.roots.write(evidence.resolve("root-trust-store.json"));
        }
    }

    @Test
    void updateRollbackAndRemovalUseNewGenerationsWithoutRollingBackTrust() throws Exception {
        try (var fixture = new PluginDistributionFixtures(); var transport = transport(fixture)) {
            var authority = new TestAuthority();
            var client = client(fixture, transport, authority);
            var first = publish(fixture, "1.0.0", jar("first"), 1, "", fixture.publishers);
            var installed = client.install(first.sources(), request("1.0.0"));
            var second = publish(fixture, "2.0.0", jar("second"), 2,
                first.revision().contentHash(), fixture.publishers);
            var updated = client.install(second.sources(), request("2.0.0"));
            assertEquals("UPDATE", updated.operation());
            assertEquals(installed.contentHash(), updated.previousInstallationHash());
            var rolledBack = client.rollback(installed.contentHash());
            assertEquals("ROLLBACK", rolledBack.operation());
            assertEquals(installed.contentHash(), rolledBack.rollbackSourceHash());
            assertEquals(updated.checkpoint(), rolledBack.checkpoint());
            assertArrayEquals(jar("first"), client.readArtifact(rolledBack.artifacts().getFirst().identityHash()));
            var removed = client.remove();
            assertEquals("REMOVE", removed.operation());
            assertTrue(client.active().orElseThrow().artifacts().isEmpty());
            assertEquals(2L, authority.read().checkpoint().sequence());
            var restored = client.rollback(updated.contentHash());
            assertArrayEquals(jar("second"), client.readArtifact(restored.artifacts().getFirst().identityHash()));
            assertEquals(2L, restored.checkpoint().sequence());
            assertTrue(Files.isDirectory(generation(installed)));
        }
    }

    @Test
    void failedDownloadsSignaturesProvenanceAndTrustReplayPreserveActiveState() throws Exception {
        try (var fixture = new PluginDistributionFixtures(); var transport = transport(fixture)) {
            var authority = new TestAuthority();
            var client = client(fixture, transport, authority);
            var first = publish(fixture, "1.0.0", jar("first"), 1, "", fixture.publishers);
            var installed = client.install(first.sources(), request("1.0.0"));
            var expected = authority.read();
            assertThrows(SecurityException.class, () -> client.install(first.sources(), request("1.0.0")));
            assertEquals(expected, authority.read());

            for (String path : List.of("/example-2.0.0.jar", "/example-2.0.0.jar.sig.json",
                    "/example-2.0.0.jar.provenance.json", "/index.sig.json", "/trust-revision.json")) {
                var next = publish(fixture, "2.0.0", jar("second"), 2,
                    first.revision().contentHash(), fixture.publishers);
                byte[] original = fixture.responses.get(path);
                if (path.endsWith(".jar")) {
                    fixture.responses.put(path, jar("tampered"));
                } else {
                    fixture.responses.put(path, corruptSignature(original));
                }
                assertThrows(Exception.class, () -> client.install(next.sources(), request("2.0.0")), path);
                assertEquals(expected, authority.read(), path);
                assertArrayEquals(jar("first"), client.readArtifact(installed.artifacts().getFirst().identityHash()));
            }
            var next = publish(fixture, "2.0.0", jar("second"), 2,
                first.revision().contentHash(), fixture.publishers);
            fixture.responses.remove("/example-2.0.0.jar");
            assertThrows(IOException.class, () -> client.install(next.sources(), request("2.0.0")));
            assertEquals(expected, authority.read());
        }
    }

    @Test
    void rejectedCommitOrUnavailableAuthorityCannotActivateStagedBytes() throws Exception {
        try (var fixture = new PluginDistributionFixtures(); var transport = transport(fixture)) {
            var authority = new TestAuthority();
            var client = client(fixture, transport, authority);
            var first = publish(fixture, "1.0.0", jar("first"), 1, "", fixture.publishers);
            authority.reject = true;
            assertThrows(SecurityException.class, () -> client.install(first.sources(), request("1.0.0")));
            assertTrue(client.active().isEmpty());
            assertNull(authority.read().checkpoint());
            authority.reject = false;
            var installed = client.install(first.sources(), request("1.0.0"));
            var expected = authority.read();
            authority.failCommit = true;
            assertThrows(IOException.class, client::remove);
            assertEquals(expected, authority.read());
            authority.failRead = true;
            assertThrows(IOException.class, client::active);
            assertThrows(IOException.class, () -> client.readArtifact(installed.artifacts().getFirst().identityHash()));
            assertThrows(NullPointerException.class, () -> client(fixture, transport, null));
        }
    }

    @Test
    void rollbackRechecksRevocationsAndRejectsMutatedOrUnrelatedGenerations() throws Exception {
        try (var fixture = new PluginDistributionFixtures(); var transport = transport(fixture)) {
            var authority = new TestAuthority();
            var client = client(fixture, transport, authority);
            var first = publish(fixture, "1.0.0", jar("first"), 1, "", fixture.publishers);
            var installed = client.install(first.sources(), request("1.0.0"));
            var revoked = new PluginTrustStore(PluginTrustStore.SCHEMA, fixture.publishers.keys(),
                List.of(new PluginTrustStore.ArtifactRevocation(first.entry().artifactSha256(), "incident")));
            var second = publish(fixture, "2.0.0", jar("second"), 2, first.revision().contentHash(), revoked);
            client.install(second.sources(), request("2.0.0"));
            var expected = authority.read();
            assertThrows(SecurityException.class, () -> client.rollback(installed.contentHash()));
            assertEquals(expected, authority.read());
            assertThrows(SecurityException.class, () -> client.rollback("sha256:" + "f".repeat(64)));
            Path artifact = generation(installed).resolve(installed.artifacts().getFirst().path());
            Files.write(artifact, jar("modified local cache"));
            assertThrows(SecurityException.class, () -> client.rollback(installed.contentHash()));
            assertEquals(expected, authority.read());
        }
    }

    @Test
    void filesystemTamperingAndSymlinksFailClosed() throws Exception {
        try (var fixture = new PluginDistributionFixtures(); var transport = transport(fixture)) {
            var authority = new TestAuthority();
            var client = client(fixture, transport, authority);
            var first = publish(fixture, "1.0.0", jar("first"), 1, "", fixture.publishers);
            var installed = client.install(first.sources(), request("1.0.0"));
            var expected = authority.read();
            Path artifact = generation(installed).resolve(installed.artifacts().getFirst().path());
            Path outside = directory.resolve("outside.jar");
            Files.write(outside, jar("first"));
            Files.delete(artifact);
            Files.createSymbolicLink(artifact, outside);
            assertThrows(SecurityException.class, client::active);
            assertThrows(SecurityException.class, client::remove);
            assertEquals(expected, authority.read());
            assertArrayEquals(jar("first"), Files.readAllBytes(outside));
        }
    }

    @Test
    void resolvesAndInstallsTheEntireDependencyClosureOrNothing() throws Exception {
        try (var fixture = new PluginDistributionFixtures(); var transport = transport(fixture)) {
            byte[] dependencyBytes = jar("dependency");
            byte[] rootBytes = jar("root");
            var publication = publish(fixture, "1.0.0", rootBytes, 1, "", fixture.publishers);
            var template = fixture.entry("1.0.0", dependencyBytes);
            var dependency = PluginArtifactIndex.Entry.create("library-1", template.kind(), "library", "1.0.0",
                "1", "0.5.0", "", List.of(), List.of(), "library.jar", template.artifactSha256(),
                fixture.origin.resolve("/library.jar").toString(), fixture.origin.resolve("/library.jar.sig.json").toString(),
                fixture.origin.resolve("/library.provenance.json").toString(), "publisher");
            var root = publication.entry();
            var dependent = PluginArtifactIndex.Entry.create(root.artifactId(), root.kind(), root.componentId(), root.version(),
                root.apiVersion(), root.minimumCoreVersion(), "", root.capabilities(),
                List.of(new PluginArtifactIndex.Dependency(PluginArtifactIndex.ArtifactKind.JAVA_PLUGIN,
                    "library", "=1.0.0", false)), root.artifactFileName(), root.artifactSha256(), root.artifactUri(),
                root.signatureManifestUri(), root.provenanceUri(), root.publisherId());
            publishArtifact(fixture, dependency, dependencyBytes);
            publishArtifact(fixture, dependent, rootBytes);
            var sources = replaceIndex(fixture, publication, List.of(dependent, dependency));
            var authority = new TestAuthority();
            var client = client(fixture, transport, authority);
            fixture.responses.remove("/example-1.0.0.jar.sig.json");
            assertThrows(IOException.class, () -> client.install(sources, request("1.0.0")));
            assertTrue(client.active().isEmpty());
            publishArtifact(fixture, dependent, rootBytes);
            var installation = client.install(sources, request("1.0.0"));
            assertEquals(List.of("library", "example"), installation.artifacts().stream()
                .map(PluginInstallationEvidence.Artifact::componentId).toList());
            assertArrayEquals(dependencyBytes, client.readArtifact(dependency.identityHash()));
            assertArrayEquals(rootBytes, client.readArtifact(dependent.identityHash()));
        }
    }

    @Test
    void rejectsSignedChainGapsForksAndUnexpectedImmutableIndexBeforeActivation() throws Exception {
        try (var fixture = new PluginDistributionFixtures(); var transport = transport(fixture)) {
            var authority = new TestAuthority();
            var client = client(fixture, transport, authority);
            var first = publish(fixture, "1.0.0", jar("first"), 1, "", fixture.publishers);
            client.install(first.sources(), request("1.0.0"));
            var expected = authority.read();
            var gap = publish(fixture, "2.0.0", jar("second"), 3, first.revision().contentHash(), fixture.publishers);
            assertThrows(SecurityException.class, () -> client.install(gap.sources(), request("2.0.0")));
            var fork = publish(fixture, "2.0.0", jar("second"), 2, "sha256:" + "a".repeat(64), fixture.publishers);
            assertThrows(SecurityException.class, () -> client.install(fork.sources(), request("2.0.0")));
            var next = publish(fixture, "2.0.0", jar("second"), 2, first.revision().contentHash(), fixture.publishers);
            var sources = next.sources();
            var wrongIndex = new PluginDistributionClient.Sources(sources.trustStoreUri(), sources.trustRevisionUri(),
                sources.indexUri(), sources.indexSignatureUri(), sources.indexId(), "other-revision", sources.indexContentHash());
            assertThrows(SecurityException.class, () -> client.install(wrongIndex, request("2.0.0")));
            assertEquals(expected, authority.read());
        }
    }

    @Test
    void publisherAndProvenanceMustMatchTheIndexEvenWhenTheirSignaturesAreTrusted() throws Exception {
        try (var fixture = new PluginDistributionFixtures(); var transport = transport(fixture)) {
            var keys = new java.util.ArrayList<>(fixture.publishers.keys());
            keys.add(PluginDistributionFixtures.key("other-publisher", fixture.publisherKey, PluginTrustStore.KeyStatus.ACTIVE));
            var trust = new PluginTrustStore(PluginTrustStore.SCHEMA, keys, List.of());
            var publication = publish(fixture, "1.0.0", jar("first"), 1, "", trust);
            var authority = new TestAuthority();
            var client = client(fixture, transport, authority);
            var entry = publication.entry();
            var otherSignature = PluginSignatureManifest.create(entry.artifactFileName(), entry.artifactSha256(),
                "other-publisher", "key-1", PluginDistributionFixtures.sign(fixture.publisherKey,
                    PluginSignatureManifest.signedPayload(entry.artifactFileName(), entry.artifactSha256(),
                        "other-publisher", "key-1", "Ed25519")));
            put(fixture, "/example-1.0.0.jar.sig.json", otherSignature.toCanonicalJson());
            var wrongPublisher = publication;
            assertThrows(SecurityException.class, () -> client.install(wrongPublisher.sources(), request("1.0.0")));
            publication = publish(fixture, "1.0.0", jar("first"), 1, "", trust);
            put(fixture, "/example-1.0.0.jar.provenance.json", PluginArtifactProvenanceTest.signed(fixture,
                fixture.entry("9.0.0", jar("first"))).toCanonicalJson());
            var wrongProvenance = publication;
            assertThrows(SecurityException.class, () -> client.install(wrongProvenance.sources(), request("1.0.0")));
            assertTrue(client.active().isEmpty());
        }
    }

    @Test
    void aConcurrentSuccessfulRemovalMakesAnOlderStagedUpdateStale() throws Exception {
        try (var fixture = new PluginDistributionFixtures(); var transport = transport(fixture)) {
            var backing = new TestAuthority();
            var enter = new java.util.concurrent.CountDownLatch(1);
            var release = new java.util.concurrent.CountDownLatch(1);
            var blockNext = new java.util.concurrent.atomic.AtomicBoolean();
            PluginCheckpointAuthority authority = new PluginCheckpointAuthority() {
                public AcceptedState read() throws IOException { return backing.read(); }
                public boolean compareAndSet(AcceptedState expected, AcceptedState update) throws IOException {
                    if (blockNext.getAndSet(false)) {
                        enter.countDown();
                        try {
                            if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                                throw new IOException("test concurrent commit did not arrive");
                            }
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new IOException(interrupted);
                        }
                    }
                    return backing.compareAndSet(expected, update);
                }
            };
            var client = client(fixture, transport, authority);
            var first = publish(fixture, "1.0.0", jar("first"), 1, "", fixture.publishers);
            client.install(first.sources(), request("1.0.0"));
            var next = publish(fixture, "2.0.0", jar("second"), 2, first.revision().contentHash(), fixture.publishers);
            blockNext.set(true);
            try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                var update = executor.submit(() -> client.install(next.sources(), request("2.0.0")));
                assertTrue(enter.await(5, java.util.concurrent.TimeUnit.SECONDS));
                var removed = client(fixture, transport, authority).remove();
                release.countDown();
                var error = assertThrows(java.util.concurrent.ExecutionException.class, update::get);
                assertInstanceOf(SecurityException.class, error.getCause());
                assertEquals(removed.contentHash(), backing.read().installationHash());
                assertEquals(1L, backing.read().checkpoint().sequence());
                assertTrue(client.active().orElseThrow().artifacts().isEmpty());
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void finiteMetadataArtifactAndTransactionBudgetsPreventActivation() throws Exception {
        try (var fixture = new PluginDistributionFixtures(); var transport = transport(fixture)) {
            var publication = publish(fixture, "1.0.0", jar("first"), 1, "", fixture.publishers);
            var authority = new TestAuthority();
            var limits = List.of(new PluginDistributionClient.Limits(64, 65536, 1048576, 16, 64),
                new PluginDistributionClient.Limits(65536, 64, 1048576, 16, 64),
                new PluginDistributionClient.Limits(65536, 65536, 128, 16, 64));
            for (var limit : limits) {
                var client = new PluginDistributionClient(directory.resolve("packages"), "community", fixture.roots,
                    authority, transport, limit);
                assertThrows(IOException.class, () -> client.install(publication.sources(), request("1.0.0")));
                assertTrue(client.active().isEmpty());
            }
            assertNull(authority.read().checkpoint());
        }
    }

    @Test
    void installationManifestCountsTowardTheTotalLimitBeforeActivation() throws Exception {
        try (var fixture = new PluginDistributionFixtures(); var transport = transport(fixture)) {
            var publication = publish(fixture, "1.0.0", jar("first"), 1, "", fixture.publishers);
            var installed = client(fixture, transport, new TestAuthority())
                .install(publication.sources(), request("1.0.0"));
            long exactGenerationBytes = generationBytes(generation(installed));
            var authority = new TestAuthority();
            var limited = new PluginDistributionClient(directory.resolve("limited"), "community", fixture.roots,
                authority, transport, new PluginDistributionClient.Limits(65536, 65536,
                    exactGenerationBytes - 1, 16, 64));

            assertThrows(SecurityException.class, () -> limited.install(publication.sources(), request("1.0.0")));
            assertEquals(PluginCheckpointAuthority.AcceptedState.empty(), authority.read());
            assertTrue(limited.active().isEmpty());
            try (var staged = Files.list(directory.resolve("limited"))) {
                assertEquals(List.of("generations"), staged.map(path -> path.getFileName().toString()).toList());
            }
            try (var generations = Files.list(directory.resolve("limited/generations"))) {
                assertEquals(0, generations.count());
            }
        }
    }

    @Test
    void reopeningCountsTheManifestTowardTheSameTotalLimit() throws Exception {
        try (var fixture = new PluginDistributionFixtures(); var transport = transport(fixture)) {
            var publication = publish(fixture, "1.0.0", jar("first"), 1, "", fixture.publishers);
            var authority = new TestAuthority();
            var installed = client(fixture, transport, authority).install(publication.sources(), request("1.0.0"));
            var accepted = authority.read();
            long exactGenerationBytes = generationBytes(generation(installed));
            var reopened = new PluginDistributionClient(directory.resolve("packages"), "community", fixture.roots,
                authority, transport, new PluginDistributionClient.Limits(65536, 65536,
                    exactGenerationBytes - 1, 16, 64));

            assertThrows(SecurityException.class, reopened::active);
            assertThrows(SecurityException.class,
                () -> reopened.readArtifact(installed.artifacts().getFirst().identityHash()));
            assertEquals(accepted, authority.read());
            assertEquals(exactGenerationBytes, generationBytes(generation(installed)));
        }
    }

    @Test
    void theExactManifestInclusiveLimitAdmitsInstallationAndReopening() throws Exception {
        try (var fixture = new PluginDistributionFixtures(); var transport = transport(fixture)) {
            var publication = publish(fixture, "1.0.0", jar("first"), 1, "", fixture.publishers);
            var baseline = client(fixture, transport, new TestAuthority()).install(publication.sources(), request("1.0.0"));
            long exactGenerationBytes = generationBytes(generation(baseline));
            var authority = new TestAuthority();
            var limits = new PluginDistributionClient.Limits(65536, 65536, exactGenerationBytes, 16, 64);
            Path root = directory.resolve("exact-limit");
            var bounded = new PluginDistributionClient(root, "community", fixture.roots, authority, transport, limits);

            var installed = bounded.install(publication.sources(), request("1.0.0"));
            assertEquals(exactGenerationBytes,
                generationBytes(root.resolve("generations/" + installed.contentHash().substring(7))));
            var reopened = new PluginDistributionClient(root, "community", fixture.roots, authority, transport, limits);
            assertEquals(installed.contentHash(), reopened.active().orElseThrow().contentHash());
            assertArrayEquals(jar("first"), reopened.readArtifact(installed.artifacts().getFirst().identityHash()));
        }
    }

    @Test
    void canonicalGenerationHashCanBeReconstructedWithoutTheEvidenceHasher() throws Exception {
        try (var fixture = new PluginDistributionFixtures(); var transport = transport(fixture)) {
            var publication = publish(fixture, "1.0.0", jar("first"), 1, "", fixture.publishers);
            var client = client(fixture, transport, new TestAuthority());
            var installed = client.install(publication.sources(), request("1.0.0"));
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var json = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(installed.toCanonicalJson());
            json.remove("contentHash");
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256").digest(
                (mapper.writeValueAsString(json) + "\n").getBytes(StandardCharsets.UTF_8));
            assertEquals("sha256:" + java.util.HexFormat.of().formatHex(hash), installed.contentHash());
            var manifest = generation(installed).resolve("installation.json");
            Files.writeString(manifest, installed.toCanonicalJson().replace("\"operation\":\"INSTALL\"",
                "\"operation\":\"REMOVE\""));
            assertThrows(SecurityException.class, client::active);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"WHITESPACE", "PROPERTY_ORDER", "EXTRA_LF"})
    void nonCanonicalManifestBytesCannotReuseAnImmutableGeneration(String mutation) throws Exception {
        try (var fixture = new PluginDistributionFixtures(); var transport = transport(fixture)) {
            var publication = publish(fixture, "1.0.0", jar("first"), 1, "", fixture.publishers);
            var authority = new TestAuthority();
            var client = client(fixture, transport, authority);
            var installed = client.install(publication.sources(), request("1.0.0"));
            var accepted = authority.read();
            var store = new PluginInstallationStore(directory.resolve("packages"),
                new PluginDistributionClient.Limits(65536, 65536, 1024 * 1024, 16, 64));
            var original = store.load(installed.contentHash());
            String canonical = installed.toCanonicalJson();
            byte[] changed = nonCanonicalManifest(canonical, mutation).getBytes(StandardCharsets.UTF_8);
            assertFalse(java.util.Arrays.equals(canonical.getBytes(StandardCharsets.UTF_8), changed));
            assertEquals(installed, PluginInstallationEvidence.read(changed),
                "the mutation changes only stored bytes, including no semantic hash or signed fields");
            Path manifest = generation(installed).resolve("installation.json");
            Files.write(manifest, changed);

            assertThrows(SecurityException.class, client::active, mutation);
            assertThrows(SecurityException.class, () -> client(fixture, transport, authority).active(), mutation);
            assertThrows(SecurityException.class, () -> store.persist(installed, original.files()), mutation);
            assertEquals(accepted, authority.read());
            assertArrayEquals(changed, Files.readAllBytes(manifest), "a load must not silently normalize retained bytes");
        }
    }

    private static String nonCanonicalManifest(String canonical, String mutation) throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var json = mapper.readTree(canonical);
        return switch (mutation) {
            case "WHITESPACE" -> mapper.writerWithDefaultPrettyPrinter().writeValueAsString(json) + "\n";
            case "PROPERTY_ORDER" -> {
                var reversed = mapper.createObjectNode();
                for (var entry : json.properties().stream().toList().reversed()) {
                    reversed.set(entry.getKey(), entry.getValue());
                }
                yield mapper.writeValueAsString(reversed) + "\n";
            }
            case "EXTRA_LF" -> canonical + "\n";
            default -> throw new IllegalArgumentException("unknown public test mutation");
        };
    }

    private static byte[] corruptSignature(byte[] original) {
        String value = new String(original, StandardCharsets.UTF_8);
        var matcher = java.util.regex.Pattern.compile("\"signatureBase64\":\"([^\"]+)\"").matcher(value);
        assertTrue(matcher.find());
        int start = matcher.start(1);
        return (value.substring(0, start) + (value.charAt(start) == 'A' ? 'B' : 'A')
            + value.substring(start + 1)).getBytes(StandardCharsets.UTF_8);
    }

    private static void publishArtifact(PluginDistributionFixtures fixture,
            PluginArtifactIndex.Entry entry, byte[] bytes) throws Exception {
        fixture.responses.put(java.net.URI.create(entry.artifactUri()).getPath(), bytes);
        var signature = PluginSignatureManifest.create(entry.artifactFileName(), entry.artifactSha256(),
            entry.publisherId(), "key-1", PluginDistributionFixtures.sign(fixture.publisherKey,
                PluginSignatureManifest.signedPayload(entry.artifactFileName(), entry.artifactSha256(),
                    entry.publisherId(), "key-1", "Ed25519")));
        put(fixture, java.net.URI.create(entry.signatureManifestUri()).getPath(), signature.toCanonicalJson());
        put(fixture, java.net.URI.create(entry.provenanceUri()).getPath(),
            PluginArtifactProvenanceTest.signed(fixture, entry).toCanonicalJson());
    }

    private static PluginDistributionClient.Sources replaceIndex(PluginDistributionFixtures fixture,
            Publication publication, List<PluginArtifactIndex.Entry> entries) throws Exception {
        var old = publication.index();
        var index = PluginArtifactIndex.create(old.indexId(), old.revision(), old.curatorId(), entries);
        var signature = PluginArtifactIndexSignature.create(index.indexId(), index.revision(), index.contentHash(),
            "curator", "key-1", PluginDistributionFixtures.sign(fixture.curatorKey,
                PluginArtifactIndexSignature.signedPayload(index.indexId(), index.revision(), index.contentHash(),
                    "curator", "key-1", "Ed25519")));
        put(fixture, "/index.json", index.toCanonicalJson());
        put(fixture, "/index.sig.json", signature.toCanonicalJson());
        var sources = publication.sources();
        return new PluginDistributionClient.Sources(sources.trustStoreUri(), sources.trustRevisionUri(),
            sources.indexUri(), sources.indexSignatureUri(), index.indexId(), index.revision(), index.contentHash());
    }

    private Path generation(PluginInstallationEvidence installation) {
        return directory.resolve("packages/generations/" + installation.contentHash().substring(7));
    }

    private static long generationBytes(Path generation) throws IOException {
        long bytes = 0;
        try (var paths = Files.walk(generation)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                bytes = Math.addExact(bytes, Files.size(path));
            }
        }
        return bytes;
    }

    private PluginDistributionClient client(PluginDistributionFixtures fixture,
            PluginDistributionTransport transport, PluginCheckpointAuthority authority) throws IOException {
        return new PluginDistributionClient(directory.resolve("packages"), "community", fixture.roots,
            authority, transport, new PluginDistributionClient.Limits(65536, 65536, 1024 * 1024, 16, 64));
    }

    private static PluginDistributionTransport transport(PluginDistributionFixtures fixture) {
        return new PluginDistributionTransport(Set.of(fixture.origin), Duration.ofSeconds(2),
            Duration.ofSeconds(3), false, fixture.tls);
    }

    private static PluginArtifactResolver.ResolutionRequest request(String version) {
        return PluginArtifactResolver.ResolutionRequest.exact("installation", PluginArtifactIndex.ArtifactKind.JAVA_PLUGIN,
            "example", version, "0.5.0", "1", List.of("algebra"));
    }

    static Publication publish(PluginDistributionFixtures fixture, String version, byte[] bytes,
            long sequence, String previous, PluginTrustStore store) throws Exception {
        var entry = fixture.entry(version, bytes);
        var index = PluginArtifactIndex.create("community-index", "revision-" + sequence, "curator", List.of(entry));
        var signature = PluginSignatureManifest.create(entry.artifactFileName(), entry.artifactSha256(),
            "publisher", "key-1", PluginDistributionFixtures.sign(fixture.publisherKey,
                PluginSignatureManifest.signedPayload(entry.artifactFileName(), entry.artifactSha256(),
                    "publisher", "key-1", "Ed25519")));
        var indexSignature = PluginArtifactIndexSignature.create(index.indexId(), index.revision(), index.contentHash(),
            "curator", "key-1", PluginDistributionFixtures.sign(fixture.curatorKey,
                PluginArtifactIndexSignature.signedPayload(index.indexId(), index.revision(), index.contentHash(),
                    "curator", "key-1", "Ed25519")));
        String storeHash = PluginDistributionFixtures.hash(store.toCanonicalJson());
        var revision = PluginTrustStoreRevision.create("community", sequence, previous, storeHash,
            "authority", "key-1", PluginDistributionFixtures.sign(fixture.rootKey,
                PluginTrustStoreRevision.signedPayload("community", sequence, previous, storeHash,
                    "authority", "key-1", "Ed25519")));
        fixture.responses.put("/" + entry.artifactFileName(), bytes);
        put(fixture, "/" + entry.artifactFileName() + ".sig.json", signature.toCanonicalJson());
        put(fixture, "/" + entry.artifactFileName() + ".provenance.json",
            PluginArtifactProvenanceTest.signed(fixture, entry).toCanonicalJson());
        put(fixture, "/trust-store.json", store.toCanonicalJson());
        put(fixture, "/trust-revision.json", revision.toCanonicalJson());
        put(fixture, "/index.json", index.toCanonicalJson());
        put(fixture, "/index.sig.json", indexSignature.toCanonicalJson());
        var sources = new PluginDistributionClient.Sources(fixture.origin.resolve("/trust-store.json"),
            fixture.origin.resolve("/trust-revision.json"), fixture.origin.resolve("/index.json"),
            fixture.origin.resolve("/index.sig.json"), index.indexId(), index.revision(), index.contentHash());
        return new Publication(entry, index, revision, sources);
    }

    private static void put(PluginDistributionFixtures fixture, String path, String value) {
        fixture.responses.put(path, value.getBytes(StandardCharsets.UTF_8));
    }

    static byte[] jar(String value) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (var jar = new JarOutputStream(bytes)) {
            ZipEntry entry = new ZipEntry("content.txt");
            entry.setTime(0);
            jar.putNextEntry(entry);
            jar.write(value.getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        return bytes.toByteArray();
    }

    record Publication(PluginArtifactIndex.Entry entry, PluginArtifactIndex index,
        PluginTrustStoreRevision revision, PluginDistributionClient.Sources sources) { }

    /** Test-only model of the externally trusted, durable, linearizable authority contract. */
    static final class TestAuthority implements PluginCheckpointAuthority {
        private AcceptedState state = AcceptedState.empty();
        boolean reject;
        boolean failCommit;
        boolean failRead;

        @Override
        public synchronized AcceptedState read() throws IOException {
            if (failRead) {
                throw new IOException("authority unavailable");
            }
            return state;
        }

        @Override
        public synchronized boolean compareAndSet(AcceptedState expected, AcceptedState update) throws IOException {
            if (failCommit) {
                throw new IOException("commit rejected before mutation");
            }
            if (reject || !state.equals(expected)) {
                return false;
            }
            state = update;
            return true;
        }
    }
}
