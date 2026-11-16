package de.regelsuche.discovery.representation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import de.regelsuche.discovery.representation.TargetFreeHeldOutContainerReproductionVerifier.ReproductionReceipt;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class TargetFreeHeldOutContainerReproductionVerifierTest {
    private static final String REVISION =
        "0123456789abcdef0123456789abcdef01234567";
    private static final String IMAGE_ID =
        "sha256:" + "1".repeat(64);
    private static final String WRAPPER_SHA =
        "84fbba45c7f4c64abc77460e1c00f541e9f960e3c7ed2538f1ede19eacd873ae";

    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void verifiesExactHostAndContainerEvidenceAndRejectsDrift(
        @TempDir Path temporary
    ) throws Exception {
        Fixture fixture = fixture(temporary);

        ReproductionReceipt receipt = verify(fixture);

        assertEquals(
            TargetFreeHeldOutContainerReproductionVerifier.EVIDENCE_STATUS,
            receipt.content().evidenceStatus());
        assertEquals(144, receipt.content().matrix().configuredRows());
        assertEquals(36, receipt.content().matrix().matchedWorkGroups());
        assertEquals(3, receipt.content().runs().size());
        assertEquals(
            TargetFreeHeldOutContainerReproductionVerifier.EXPECTED_FILES,
            receipt.content().artifacts().stream()
                .map(value -> value.path()).toList());
        assertTrue(receipt.content().runs().stream().allMatch(run ->
            run.artifactSetHash().equals(
                receipt.content().artifactSetHash())));
        assertEquals(
            receipt,
            ReproductionReceipt.fromCanonicalJson(
                receipt.toCanonicalJson()));

        Path containerPlan = fixture.container().resolve(
            TargetFreeHeldOutMatrixRunner.PLAN_FILE_NAME);
        byte[] originalPlan = Files.readAllBytes(containerPlan);
        Files.writeString(
            containerPlan,
            new String(originalPlan, StandardCharsets.UTF_8) + "\n",
            StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> verify(fixture));
        Files.write(containerPlan, originalPlan);

        Files.writeString(
            fixture.imageId(),
            "not-a-content-addressed-image\n",
            StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> verify(fixture));
        Files.writeString(
            fixture.imageId(), IMAGE_ID + "\n", StandardCharsets.UTF_8);

        Files.writeString(
            fixture.container().resolve("unexpected.json"),
            "{}",
            StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> verify(fixture));
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void rejectsIdenticalRehashedEvidenceWithUnboundRoots(
        @TempDir Path temporary
    ) throws Exception {
        Fixture fixture = fixture(temporary);
        String freezeName = TargetFreeHeldOutMatrixRunner.FREEZE_FILE_NAME;
        String qualificationName = TargetFreeHeldOutMatrixRunner.QUALIFICATION_FILE_NAME;
        String freezeJson = Files.readString(fixture.hostA().resolve(freezeName));
        String qualificationJson = Files.readString(fixture.hostA().resolve(qualificationName));
        var json = TargetFreeHeldOutMatrixRunner.JSON;

        var freeze = (ObjectNode) json.readTree(freezeJson).get("content");
        freeze.put("formationHash", IMAGE_ID);
        var reboundFreeze = FreezeArtifact.create(json.treeToValue(freeze, FreezeContent.class));
        var qualification = (ObjectNode) json.readTree(qualificationJson).get("content");
        qualification.put("candidateFreezeHash", reboundFreeze.contentHash());
        var reboundQualification = QualificationArtifact.create(
            json.treeToValue(qualification, QualificationContent.class));
        replaceInAllRuns(fixture, freezeName, reboundFreeze.toCanonicalJson());
        replaceInAllRuns(fixture, qualificationName, reboundQualification.toCanonicalJson());
        assertThrows(IllegalArgumentException.class, () -> verify(fixture));

        replaceInAllRuns(fixture, freezeName, freezeJson);
        qualification.put("candidateFreezeHash", FreezeArtifact.fromCanonicalJson(freezeJson).contentHash());
        var rows = qualification.get("rows");
        var first = (ObjectNode) rows.get(0);
        String originalWork = first.get("workLedgerHash").textValue();
        first.put("workLedgerHash", rows.get(1).get("workLedgerHash").textValue());
        reboundQualification = QualificationArtifact.create(json.treeToValue(qualification, QualificationContent.class));
        replaceInAllRuns(fixture, qualificationName, reboundQualification.toCanonicalJson());
        assertThrows(IllegalArgumentException.class, () -> verify(fixture));

        first.put("workLedgerHash", originalWork);
        var candidate = (ObjectNode) first.get("candidates").get(0);
        candidate.put("expression", candidate.get("expression").textValue() + " + 0");
        reboundQualification = QualificationArtifact.create(json.treeToValue(qualification, QualificationContent.class));
        replaceInAllRuns(fixture, qualificationName, reboundQualification.toCanonicalJson());
        assertThrows(IllegalArgumentException.class, () -> verify(fixture));
    }

    private static void replaceInAllRuns(Fixture fixture, String name, String content)
            throws IOException {
        for (Path directory : List.of(fixture.hostA(), fixture.hostB(), fixture.container())) {
            Files.writeString(directory.resolve(name), content, StandardCharsets.UTF_8);
        }
    }

    private static ReproductionReceipt verify(Fixture fixture)
            throws IOException {
        return TargetFreeHeldOutContainerReproductionVerifier.verify(
            REVISION,
            fixture.repository(),
            fixture.hostA(),
            fixture.hostB(),
            fixture.container(),
            fixture.dockerfile(),
            fixture.imageId(),
            TargetFreeHeldOutContainerReproductionVerifier.PLATFORM,
            fixture.schema());
    }

    private static Fixture fixture(Path temporary) throws Exception {
        Path repository = temporary.resolve("repository");
        Path source = temporary.resolve("source-run");
        Path hostA = temporary.resolve("host-a");
        Path hostB = temporary.resolve("host-b");
        Path container = temporary.resolve("container");
        Files.createDirectories(repository);
        TargetFreeHeldOutMatrixRunner.write(source, REVISION);
        for (Path target : List.of(hostA, hostB, container)) {
            copyArtifacts(source, target);
        }

        Path dockerfile = repository.resolve(
            TargetFreeHeldOutContainerReproductionVerifier.DOCKERFILE_PATH);
        Files.writeString(
            dockerfile,
            "FROM "
                + TargetFreeHeldOutContainerReproductionVerifier.BASE_IMAGE
                + " AS build\n"
                + "FROM build AS target-free-held-out-reproduction\n",
            StandardCharsets.UTF_8);

        Path wrapper = repository.resolve(
            "gradle/wrapper/gradle-wrapper.properties");
        Files.createDirectories(wrapper.getParent());
        Files.writeString(
            wrapper,
            "distributionSha256Sum=" + WRAPPER_SHA + "\n",
            StandardCharsets.UTF_8);

        Path schema = repository.resolve(
            TargetFreeHeldOutContainerReproductionVerifier.SCHEMA_PATH);
        Files.createDirectories(schema.getParent());
        Files.writeString(
            schema,
            "{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\","
                + "\"$id\":\""
                + TargetFreeHeldOutContainerReproductionVerifier.SCHEMA
                + "\",\"type\":\"object\"}",
            StandardCharsets.UTF_8);

        Path imageId = temporary.resolve("image-id.txt");
        Files.writeString(
            imageId, IMAGE_ID + "\n", StandardCharsets.UTF_8);
        return new Fixture(
            repository,
            hostA,
            hostB,
            container,
            dockerfile,
            imageId,
            schema);
    }

    private static void copyArtifacts(Path source, Path target)
            throws IOException {
        Files.createDirectories(target);
        for (String name
                : TargetFreeHeldOutContainerReproductionVerifier
                    .EXPECTED_FILES) {
            Files.copy(
                source.resolve(name),
                target.resolve(name),
                StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private record Fixture(
        Path repository,
        Path hostA,
        Path hostB,
        Path container,
        Path dockerfile,
        Path imageId,
        Path schema
    ) {
    }
}
