package de.regelsuche.mining;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import de.regelsuche.mining.InterestingnessIndependentReviewStudy.CandidateCase;
import de.regelsuche.mining.InterestingnessIndependentReviewStudy.StudyPlan;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Deterministic export of public blind-review packets and private assignments. */
public final class InterestingnessBlindReviewPacketExporter {
    public static final String PACKET_SCHEMA =
        "regelsuche.independent-review-blinded-packet/v1";
    public static final String PUBLIC_MANIFEST_SCHEMA =
        "regelsuche.independent-review-public-packet-manifest/v1";
    public static final String PRIVATE_MANIFEST_SCHEMA =
        "regelsuche.independent-review-private-assignment-manifest/v1";

    private static final Pattern IDENTIFIER = Pattern.compile(
        "[A-Za-z0-9][A-Za-z0-9._:-]{1,127}");
    private static final Pattern SHA256 = Pattern.compile("sha256:[0-9a-f]{64}");
    private static final ObjectMapper JSON = new ObjectMapper()
        .findAndRegisterModules()
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    public ExportBundle export(
        StudyPlan plan,
        List<ReviewAssignment> suppliedAssignments,
        Map<String, String> blindedPresentationsByHash,
        String reviewerInstructions
    ) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(suppliedAssignments, "suppliedAssignments");
        Objects.requireNonNull(blindedPresentationsByHash,
            "blindedPresentationsByHash");
        Objects.requireNonNull(reviewerInstructions, "reviewerInstructions");
        requireHash(reviewerInstructions,
            plan.reviewProtocol().reviewerInstructionsHash(),
            "reviewer instructions");

        List<ReviewAssignment> assignments = suppliedAssignments.stream()
            .map(item -> Objects.requireNonNull(item, "assignment"))
            .sorted(Comparator.comparing(ReviewAssignment::assignmentId))
            .toList();
        validateAssignments(plan, assignments);
        Map<String, String> materials = validateMaterials(
            plan, blindedPresentationsByHash);

        List<Artifact> packets = new ArrayList<>();
        List<Map<String, Object>> privateAssignments = new ArrayList<>();
        for (ReviewAssignment assignment : assignments) {
            CandidateCase candidate = plan.requireCase(assignment.caseId());
            Artifact packet = packet(
                plan, assignment, candidate, reviewerInstructions,
                materials.get(candidate.blindedPresentationHash()));
            packets.add(packet);
            privateAssignments.add(privateAssignment(
                assignment, candidate, packet));
        }
        packets.sort(Comparator.comparing(Artifact::id));
        privateAssignments.sort(Comparator.comparing(
            item -> (String) item.get("assignmentId")));

        Artifact publicManifest = publicManifest(plan, packets);
        Artifact privateManifest = privateManifest(
            plan, publicManifest, privateAssignments);
        return new ExportBundle(
            List.copyOf(packets), publicManifest, privateManifest);
    }

    public ExportReceipt exportToDirectory(
        StudyPlan plan,
        List<ReviewAssignment> assignments,
        Map<String, String> blindedPresentationsByHash,
        String reviewerInstructions,
        Path outputRoot
    ) throws IOException {
        ExportBundle bundle = export(
            plan, assignments, blindedPresentationsByHash,
            reviewerInstructions);
        Path root = outputRoot.toAbsolutePath().normalize();
        Path parent = Objects.requireNonNull(root.getParent(),
            "outputRoot parent");
        Files.createDirectories(parent);
        Path staging = parent.resolve(root.getFileName() + ".staging-" +
            bundle.publicManifest().contentHash().substring(7, 19));
        deleteRecursively(staging);
        write(staging, bundle);
        Path previous = parent.resolve(root.getFileName() + ".previous");
        deleteRecursively(previous);
        if (Files.exists(root)) {
            move(root, previous);
        }
        try {
            move(staging, root);
            deleteRecursively(previous);
        } catch (IOException failure) {
            if (Files.exists(previous) && !Files.exists(root)) {
                move(previous, root);
            }
            throw failure;
        }
        return new ExportReceipt(
            root,
            bundle.publicManifest().contentHash(),
            bundle.privateManifest().contentHash(),
            bundle.packets().size());
    }

    private static Artifact packet(
        StudyPlan plan,
        ReviewAssignment assignment,
        CandidateCase candidate,
        String instructions,
        String presentation
    ) {
        Map<String, Object> privateIdentity = map(
            "studyPlanHash", plan.contentHash(),
            "assignmentId", assignment.assignmentId(),
            "reviewerHash", assignment.reviewerHash(),
            "caseId", candidate.caseId());
        String packetId = "packet-" +
            hash(privateIdentity).substring(7, 31);
        Map<String, Object> value = map(
            "schema", PACKET_SCHEMA,
            "packetId", packetId,
            "studyPlanHash", plan.contentHash(),
            "reviewerInstructionsHash",
                plan.reviewProtocol().reviewerInstructionsHash(),
            "reviewerInstructions", instructions,
            "blindedPresentation", presentation,
            "relevanceScalePermille",
                plan.reviewProtocol().relevanceScalePermille(),
            "confidenceScalePermille",
                plan.reviewProtocol().confidenceScalePermille(),
            "rationaleCodes", plan.reviewProtocol().rationaleCodes(),
            "blindReviewRequired", true,
            "reviewCollectionStatus", "NOT_COLLECTED");
        return artifact(packetId, value);
    }

    private static Map<String, Object> privateAssignment(
        ReviewAssignment assignment,
        CandidateCase candidate,
        Artifact packet
    ) {
        Map<String, Object> value = map(
            "assignmentId", assignment.assignmentId(),
            "packetId", packet.id(),
            "packetContentHash", packet.contentHash(),
            "reviewerHash", assignment.reviewerHash(),
            "caseId", candidate.caseId(),
            "candidateId", candidate.candidateId(),
            "split", candidate.split().name(),
            "candidateFamily", candidate.candidateFamily(),
            "candidateArtifactHash", candidate.candidateArtifactHash(),
            "assessmentContentHash", candidate.assessmentContentHash(),
            "blindedPresentationHash", candidate.blindedPresentationHash());
        value.put("contentHash", hash(value));
        return Map.copyOf(value);
    }

    private static Artifact publicManifest(
        StudyPlan plan,
        List<Artifact> packets
    ) {
        List<Map<String, Object>> references = packets.stream()
            .map(packet -> map(
                "packetId", packet.id(),
                "packetContentHash", packet.contentHash()))
            .toList();
        Map<String, Object> value = map(
            "schema", PUBLIC_MANIFEST_SCHEMA,
            "studyPlanHash", plan.contentHash(),
            "reviewerInstructionsHash",
                plan.reviewProtocol().reviewerInstructionsHash(),
            "packetCount", references.size(),
            "packets", references,
            "reviewCollectionStatus", "NOT_COLLECTED",
            "empiricalConsensusStatus", "NOT_EVALUATED");
        return artifact("public-manifest", value);
    }

    private static Artifact privateManifest(
        StudyPlan plan,
        Artifact publicManifest,
        List<Map<String, Object>> assignments
    ) {
        Map<String, Object> value = map(
            "schema", PRIVATE_MANIFEST_SCHEMA,
            "studyPlanHash", plan.contentHash(),
            "publicManifestHash", publicManifest.contentHash(),
            "reviewerHashSaltCommitment",
                plan.reviewProtocol().reviewerHashSaltCommitment(),
            "assignmentCount", assignments.size(),
            "assignments", assignments,
            "privacyClassification", "PRIVATE_ACCESS_CONTROLLED",
            "reviewCollectionStatus", "NOT_COLLECTED",
            "promotionStatus", "NOT_EVALUATED",
            "publicEvidenceStatus", "NOT_EVALUATED");
        return artifact("private-assignment-manifest", value);
    }

    private static void validateAssignments(
        StudyPlan plan,
        List<ReviewAssignment> assignments
    ) {
        Set<String> ids = new HashSet<>();
        Set<String> reviewerCandidates = new HashSet<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (ReviewAssignment assignment : assignments) {
            if (!ids.add(assignment.assignmentId())) {
                throw new IllegalArgumentException(
                    "duplicate assignmentId: " + assignment.assignmentId());
            }
            CandidateCase candidate = plan.requireCase(assignment.caseId());
            if (!reviewerCandidates.add(
                    assignment.reviewerHash() + "\u0000" +
                        candidate.candidateId())) {
                throw new IllegalArgumentException(
                    "duplicate reviewer/candidate assignment");
            }
            counts.merge(candidate.caseId(), 1, Integer::sum);
        }
        int minimum = plan.reviewProtocol()
            .minimumIndependentExpertReviews();
        List<String> incomplete = plan.cases().stream()
            .filter(item -> counts.getOrDefault(item.caseId(), 0) < minimum)
            .map(CandidateCase::caseId).sorted().toList();
        if (!incomplete.isEmpty()) {
            throw new IllegalArgumentException(
                "insufficient reviewer assignments for cases: " + incomplete);
        }
    }

    private static Map<String, String> validateMaterials(
        StudyPlan plan,
        Map<String, String> supplied
    ) {
        Map<String, String> result = new LinkedHashMap<>();
        for (CandidateCase candidate : plan.cases()) {
            String expected = candidate.blindedPresentationHash();
            String material = supplied.get(expected);
            if (material == null) {
                throw new IllegalArgumentException(
                    "missing blinded presentation: " + expected);
            }
            requireHash(material, expected, "blinded presentation");
            result.put(expected, material);
        }
        if (!result.keySet().equals(supplied.keySet())) {
            throw new IllegalArgumentException(
                "unexpected blinded presentation material");
        }
        return Map.copyOf(result);
    }

    private static Artifact artifact(
        String id,
        Map<String, Object> value
    ) {
        Map<String, Object> complete = new LinkedHashMap<>(value);
        String contentHash = hash(value);
        complete.put("contentHash", contentHash);
        return new Artifact(id, Map.copyOf(complete), contentHash);
    }

    private static void write(Path root, ExportBundle bundle)
            throws IOException {
        Path publicPackets = root.resolve("public/packets");
        Path privateRoot = root.resolve("private");
        Files.createDirectories(publicPackets);
        Files.createDirectories(privateRoot);
        for (Artifact packet : bundle.packets()) {
            Files.writeString(
                publicPackets.resolve(packet.id() + ".json"),
                packet.toCanonicalJson(), StandardCharsets.UTF_8);
        }
        Files.writeString(
            root.resolve("public/manifest.json"),
            bundle.publicManifest().toCanonicalJson(),
            StandardCharsets.UTF_8);
        Files.writeString(
            privateRoot.resolve("assignment-manifest.json"),
            bundle.privateManifest().toCanonicalJson(),
            StandardCharsets.UTF_8);
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(source, target);
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (var paths = Files.walk(path)) {
            for (Path item : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(item);
            }
        }
    }

    private static void requireHash(
        String value,
        String expected,
        String context
    ) {
        String actual = InterestingnessIndependentReviewStudy.sha256(
            value.getBytes(StandardCharsets.UTF_8));
        if (!actual.equals(expected)) {
            throw new IllegalArgumentException(
                context + " hash mismatch: " + actual + " != " + expected);
        }
    }

    private static String hash(Map<String, Object> value) {
        return InterestingnessIndependentReviewStudy.sha256(
            InterestingnessIndependentReviewStudy.canonicalBytes(value));
    }

    private static Map<String, Object> map(Object... values) {
        if (values.length % 2 != 0) {
            throw new IllegalArgumentException("map requires key/value pairs");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            result.put((String) values[index], values[index + 1]);
        }
        return result;
    }

    private static String canonicalJson(Map<String, Object> value) {
        try {
            return JSON.writeValueAsString(value) + "\n";
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                "cannot serialize blind-review packet", exception);
        }
    }

    private static String identifier(String value, String field) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(
                field + " is not a valid identifier");
        }
        return value;
    }

    private static String sha256(String value, String field) {
        if (value == null || !SHA256.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " is not SHA-256");
        }
        return value;
    }

    public record ReviewAssignment(
        String assignmentId,
        String reviewerHash,
        String caseId
    ) {
        public ReviewAssignment {
            assignmentId = identifier(assignmentId, "assignmentId");
            reviewerHash = sha256(reviewerHash, "reviewerHash");
            caseId = identifier(caseId, "caseId");
        }
    }

    public record Artifact(
        String id,
        Map<String, Object> value,
        String contentHash
    ) {
        public Artifact {
            id = identifier(id, "artifact id");
            value = Map.copyOf(value);
            contentHash = sha256(contentHash, "contentHash");
            Map<String, Object> material = new LinkedHashMap<>(value);
            material.remove("contentHash");
            if (!contentHash.equals(hash(material))) {
                throw new IllegalArgumentException(
                    "artifact contentHash mismatch");
            }
        }

        public String toCanonicalJson() {
            return canonicalJson(value);
        }
    }

    public record ExportBundle(
        List<Artifact> packets,
        Artifact publicManifest,
        Artifact privateManifest
    ) {
        public ExportBundle {
            packets = List.copyOf(packets);
            Objects.requireNonNull(publicManifest, "publicManifest");
            Objects.requireNonNull(privateManifest, "privateManifest");
        }
    }

    public record ExportReceipt(
        Path outputRoot,
        String publicManifestHash,
        String privateManifestHash,
        int packetCount
    ) {
        public ExportReceipt {
            Objects.requireNonNull(outputRoot, "outputRoot");
            publicManifestHash = sha256(
                publicManifestHash, "publicManifestHash");
            privateManifestHash = sha256(
                privateManifestHash, "privateManifestHash");
            if (packetCount < 1) {
                throw new IllegalArgumentException(
                    "packetCount must be positive");
            }
        }
    }
}
