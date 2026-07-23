package de.regelsuche.mining;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.mining.InterestingnessBlindReviewPacketExporter.ExportBundle;
import de.regelsuche.mining.InterestingnessBlindReviewPacketExporter.ReviewAssignment;
import de.regelsuche.mining.InterestingnessIndependentReviewStudy.CandidateCase;
import de.regelsuche.mining.InterestingnessIndependentReviewStudy.StudyPlan;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InterestingnessBlindReviewPacketExporterTest {
    private final InterestingnessBlindReviewPacketExporter exporter =
        new InterestingnessBlindReviewPacketExporter();

    @Test
    void publicPacketsAreDeterministicAndContainNoPrivateIdentity() {
        StudyPlan plan = IndependentReviewProtocolFixtures.plan();
        ExportBundle first = exporter.export(
            plan, assignments(plan), materials(plan), "f");
        ExportBundle second = exporter.export(
            plan, reversed(assignments(plan)), materials(plan), "f");

        assertEquals(first.publicManifest().toCanonicalJson(),
            second.publicManifest().toCanonicalJson());
        assertEquals(first.privateManifest().toCanonicalJson(),
            second.privateManifest().toCanonicalJson());
        assertEquals(8, first.packets().size());
        assertEquals(8, first.packets().stream()
            .map(item -> item.id()).distinct().count());
        for (var packet : first.packets()) {
            String json = packet.toCanonicalJson();
            assertFalse(json.contains("caseId"));
            assertFalse(json.contains("candidateId"));
            assertFalse(json.contains("reviewerHash"));
            assertFalse(json.contains("blindedPresentationHash"));
            assertFalse(json.contains("CALIBRATION"));
            assertFalse(json.contains("TEST"));
            assertFalse(json.contains("family-"));
            assertTrue(json.contains("NOT_COLLECTED"));
        }
        assertFalse(first.publicManifest().toCanonicalJson()
            .contains("reviewerHash"));
        assertTrue(first.privateManifest().toCanonicalJson()
            .contains("reviewerHash"));
        assertTrue(first.privateManifest().toCanonicalJson()
            .contains("candidateId"));
    }

    @Test
    void rejectsIncompleteDuplicateAndMismatchedInputs() {
        StudyPlan plan = IndependentReviewProtocolFixtures.plan();
        assertThrows(IllegalArgumentException.class, () ->
            exporter.export(
                plan,
                assignments(plan).stream()
                    .filter(item -> !item.assignmentId().endsWith("-2"))
                    .toList(),
                materials(plan),
                "f"));

        List<ReviewAssignment> duplicate = new ArrayList<>(assignments(plan));
        duplicate.set(1, new ReviewAssignment(
            "different-assignment",
            duplicate.getFirst().reviewerHash(),
            duplicate.getFirst().caseId()));
        assertThrows(IllegalArgumentException.class, () ->
            exporter.export(plan, duplicate, materials(plan), "f"));

        Map<String, String> mismatched = new LinkedHashMap<>(materials(plan));
        mismatched.put(plan.cases().getFirst().blindedPresentationHash(),
            "wrong");
        assertThrows(IllegalArgumentException.class, () ->
            exporter.export(plan, assignments(plan), mismatched, "f"));
    }

    @Test
    void directoryExportSeparatesPublicAndPrivateAndRemovesStaleFiles(
        @TempDir Path temp
    ) throws IOException {
        StudyPlan plan = IndependentReviewProtocolFixtures.plan();
        Path output = temp.resolve("review-export");
        exporter.exportToDirectory(
            plan, assignments(plan), materials(plan), "f", output);
        Path stale = output.resolve("public/packets/stale.json");
        Files.writeString(stale, "stale");

        var receipt = exporter.exportToDirectory(
            plan, reversed(assignments(plan)), materials(plan), "f", output);

        assertEquals(8, receipt.packetCount());
        assertFalse(Files.exists(stale));
        assertTrue(Files.isRegularFile(output.resolve("public/manifest.json")));
        assertTrue(Files.isRegularFile(
            output.resolve("private/assignment-manifest.json")));
        try (var files = Files.list(output.resolve("public/packets"))) {
            assertEquals(8, files.count());
        }
    }

    private static List<ReviewAssignment> assignments(StudyPlan plan) {
        List<ReviewAssignment> result = new ArrayList<>();
        int candidate = 0;
        for (CandidateCase item : plan.cases()) {
            result.add(new ReviewAssignment(
                "assignment-" + candidate + "-1",
                IndependentReviewProtocolFixtures.hash(
                    (char) ('1' + candidate * 2)),
                item.caseId()));
            result.add(new ReviewAssignment(
                "assignment-" + candidate + "-2",
                IndependentReviewProtocolFixtures.hash(
                    (char) ('2' + candidate * 2)),
                item.caseId()));
            candidate++;
        }
        return List.copyOf(result);
    }

    private static List<ReviewAssignment> reversed(
        List<ReviewAssignment> assignments
    ) {
        return assignments.stream()
            .sorted(Comparator.comparing(
                ReviewAssignment::assignmentId).reversed())
            .toList();
    }

    private static Map<String, String> materials(StudyPlan plan) {
        Map<String, String> result = new LinkedHashMap<>();
        char marker = 'm';
        for (CandidateCase item : plan.cases()) {
            result.put(item.blindedPresentationHash(),
                String.valueOf(marker++));
        }
        return Map.copyOf(result);
    }
}
