package de.regelsuche.build;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MavenReleaseFollowUpTest {
    @Test void actualIsolatedCloneOnlyChangesOwnedMetadataAndPreservesReleaseEvidence(@TempDir Path temp) throws Exception {
        Path source = temp.resolve("published");
        MavenReleaseMetadataTransitionTest.fixture(source);
        Files.writeString(source.resolve(".gitignore"), "**/target/\nbuild/\n.release-follow-up/\n");
        try (var original = Git.init().setDirectory(source.toFile()).setInitialBranch("main").call()) {
            commit(original, "development");
            Path remote = temp.resolve("remote.git");
            try (var bare = Git.cloneRepository().setURI(source.toUri().toString()).setBare(true).setDirectory(remote.toFile()).call()) {
                assertTrue(bare.getRepository().isBare());
            }
            original.checkout().setName(original.getRepository().resolve("HEAD").name()).call();
            ReleaseMetadataTransition.write(source, "9.8.7", java.time.LocalDate.parse("2026-11-18"));
            var published = commit(original, "release");
            original.tag().setName("fixture-release").setAnnotated(false).call();
            Path protectedDirectory = Files.createDirectories(source.resolve("build/container-output"));
            Path receipt = protectedDirectory.resolve("receipt.json");
            Files.writeString(receipt, "{\"evidence\":\"preserve\"}\n");
            var publicMode = PosixFilePermissions.fromString("r-xr-xr-x");
            Files.setPosixFilePermissions(protectedDirectory, publicMode);
            try {
                Path clone = source.resolve(".release-follow-up");
                try (var next = Git.cloneRepository().setURI(remote.toUri().toString()).setBranch("main").setDirectory(clone.toFile()).call()) {
                    assertTrue(ReleaseFollowUp.prepare(clone, "9.8.7", "9.9.0-SNAPSHOT"));
                    assertEquals(Set.of("CITATION.cff", "CITATION.md", ".zenodo.json", "codemeta.json",
                        "release.properties", "app/src/main/resources/web/openapi/openapi.json", "pom.xml",
                        "profile/pom.xml", "profile/nested/pom.xml"), next.status().call().getModified());
                    commit(next, "next snapshot");
                    assertFalse(ReleaseFollowUp.prepare(clone, "9.8.7", "9.9.0-SNAPSHOT"));
                    assertFalse(ReleaseFollowUp.prepare(clone, "9.7.0", "9.8.0-SNAPSHOT"));
                    assertTrue(next.status().call().isClean());
                    for (String dirty : Set.of("CITATION.md", "unexpected.txt")) {
                        Path file = clone.resolve(dirty);
                        byte[] previous = Files.exists(file) ? Files.readAllBytes(file) : null;
                        Files.writeString(file, "unrelated change");
                        assertThrows(Exception.class, () -> ReleaseFollowUp.prepare(clone, "9.9.0", "10.0.0-SNAPSHOT"));
                        assertEquals("unrelated change", Files.readString(file));
                        if (previous == null) Files.delete(file); else Files.write(file, previous);
                    }
                    assertThrows(Exception.class, () -> ReleaseFollowUp.prepare(clone.resolve("profile"), "9.8.7", "9.9.0-SNAPSHOT"));
                }
                assertEquals(published, original.getRepository().resolve("HEAD"));
                assertEquals(published, original.getRepository().resolve("refs/tags/fixture-release"));
                var status = original.status().call();
                assertEquals(Set.of(), status.getUncommittedChanges());
                assertTrue(Set.of(".release-follow-up").containsAll(status.getUntracked()));
                try (var tracked = new org.eclipse.jgit.treewalk.TreeWalk(original.getRepository())) {
                    tracked.addTree(published.getTree());
                    tracked.setRecursive(true);
                    while (tracked.next()) assertArrayEquals(original.getRepository().open(tracked.getObjectId(0)).getBytes(),
                        Files.readAllBytes(source.resolve(tracked.getPathString())), tracked.getPathString());
                }
                assertEquals(publicMode, Files.getPosixFilePermissions(protectedDirectory));
                assertEquals("{\"evidence\":\"preserve\"}\n", Files.readString(receipt));
                if (!Files.isWritable(protectedDirectory))
                    assertThrows(java.nio.file.AccessDeniedException.class, () -> Files.delete(receipt));
            } finally { Files.setPosixFilePermissions(protectedDirectory, PosixFilePermissions.fromString("rwx------")); }
        }
    }

    @Test void currentWorkflowBindsTheMutableCheckoutToItsOwnMavenAuthority() throws Exception {
        Path path = MavenPomTestSupport.repositoryRoot().resolve(".github/workflows/release.yml");
        String workflow = Files.readString(path);
        verifyWorkflow(workflow);
        for (var mutation : java.util.List.of(
                "path: .release-follow-up", "working-directory: .release-follow-up", "ref: main",
                "steps.release-state.outputs.managed_release == 'true'", "steps.release-state.outputs.dry_run != 'true'",
                "-Dregelsuche.releaseMetadata.operation=follow-up", "-Dregelsuche.releaseMetadata.predecessor=\"$RELEASE_VERSION\"",
                "steps.next-development.outputs.required == 'true'")) {
            assertTrue(workflow.contains(mutation), mutation);
            int separator = mutation.indexOf(": ");
            String replacement = separator >= 0 ? mutation.substring(0, separator + 2) + "wrong-location" : "changed";
            assertThrows(AssertionError.class, () -> verifyWorkflow(workflow.replace(mutation, replacement)), mutation);
        }
    }

    private static void verifyWorkflow(String workflow) throws Exception {
        var document = new com.fasterxml.jackson.databind.ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory()).readTree(workflow);
        var steps = new java.util.HashMap<String, com.fasterxml.jackson.databind.JsonNode>();
        for (var step : document.at("/jobs/release/steps")) assertNull(steps.put(step.path("name").asText(), step));
        var checkout = steps.get("Restore authoritative main for follow-up");
        var prepare = steps.get("Prepare next development metadata");
        var publish = steps.get("Open next development metadata PR");
        assertEquals(steps.get("Checkout authoritative source").path("uses").asText(), checkout.path("uses").asText());
        assertEquals("main", checkout.at("/with/ref").asText());
        assertEquals(0, checkout.at("/with/fetch-depth").asInt(-1));
        assertEquals(".release-follow-up", checkout.at("/with/path").asText());
        assertEquals(".release-follow-up", prepare.path("working-directory").asText());
        assertEquals(".release-follow-up", publish.at("/with/path").asText());
        for (var step : java.util.List.of(checkout, prepare)) {
            assertTrue(step.path("if").asText().contains("steps.release-state.outputs.dry_run != 'true'"));
            assertTrue(step.path("if").asText().contains("steps.release-state.outputs.managed_release == 'true'"));
        }
        assertEquals("steps.next-development.outputs.required == 'true'", publish.path("if").asText());
        assertEquals(Set.of("CITATION.cff", "CITATION.md", ".zenodo.json", "codemeta.json", "release.properties",
            "app/src/main/resources/web/openapi/openapi.json", "pom.xml", "*/pom.xml"),
            Set.copyOf(publish.at("/with/add-paths").asText().lines().toList()));
        assertEquals("mvn --batch-mode --no-transfer-progress -pl maven-build-contract -am "
            + "-Dtest=MavenReleaseMetadataTransitionTest#executeRequestedTransition -Dsurefire.failIfNoSpecifiedTests=false "
            + "-Dregelsuche.releaseMetadata.operation=follow-up -Dregelsuche.releaseMetadata.predecessor=\"$RELEASE_VERSION\" "
            + "-Dregelsuche.releaseMetadata.version=\"$NEXT_VERSION\" test", prepare.path("run").asText().strip());
    }

    private static org.eclipse.jgit.revwalk.RevCommit commit(Git git, String message) throws Exception {
        git.add().addFilepattern(".").call();
        return git.commit().setAuthor("Release fixture", "fixture@example.invalid")
            .setCommitter("Release fixture", "fixture@example.invalid").setSign(false).setMessage(message).call();
    }
}
