package de.regelsuche.mining;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.regelsuche.mining.InterestingnessBlindReviewPacketExporter.ReviewAssignment;
import de.regelsuche.mining.InterestingnessIndependentReviewIntake.IntakeOutcome;
import de.regelsuche.mining.InterestingnessIndependentReviewIntake.ReviewOrigin;
import de.regelsuche.mining.InterestingnessIndependentReviewIntake.ReviewSubmission;
import de.regelsuche.mining.InterestingnessIndependentReviewStudy.StudyPlan;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Custodian-operated, single-host POSIX intake. The only outward-facing values are commitments. */
public final class InterestingnessRestrictedCollection {
    private static final ObjectMapper JSON = new ObjectMapper()
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Set<String> FIELDS = Set.of("registrationHash", "packetId", "packetContentHash",
        "revision", "disposition", "blind", "independent", "relevancePermille", "confidencePermille",
        "rationaleCodes", "qualificationEvidenceHash", "optionalProse", "reason");
    private static final Set<String> EXCLUSION_REASONS = Set.of("WITHDRAWN", "CONFLICT", "EXPOSURE", "INELIGIBLE");
    private static final String STATE = "collection.json";
    private static final int MAX_FORM_BYTES = 64 * 1024;

    private InterestingnessRestrictedCollection() { }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) throw new IllegalArgumentException(
            "init VAULT PLAN EXPORT POLICY ORIGIN | submit VAULT AUTHENTICATED_REVIEWER_HASH | freeze VAULT | audit VAULT");
        Path root = Path.of(args[1]);
        String result;
        try {
            result = run(args, root);
        } catch (IOException | RuntimeException failure) {
            System.err.println("Restricted review command failed. Check the custodian inputs and vault state.");
            System.exit(1);
            return;
        }
        System.out.println(result);
    }

    private static String run(String[] args, Path root) throws IOException {
        return switch (args[0]) {
            case "init" -> {
                require(args.length == 6, "init requires vault, plan, export, policy and origin");
                yield init(root, Path.of(args[2]), Path.of(args[3]), Path.of(args[4]), ReviewOrigin.valueOf(args[5]));
            }
            case "submit" -> {
                require(args.length == 3, "submit requires vault and authenticated reviewer hash");
                var bytes = System.in.readNBytes(MAX_FORM_BYTES + 1);
                require(bytes.length <= MAX_FORM_BYTES, "form too large");
                yield submit(root, args[2], new String(bytes, StandardCharsets.UTF_8));
            }
            case "freeze" -> {
                require(args.length == 2, "freeze requires vault");
                yield freeze(root);
            }
            case "audit" -> {
                require(args.length == 2, "audit requires vault");
                yield audit(root);
            }
            default -> throw new IllegalArgumentException("unknown restricted collection command");
        };
    }

    public static synchronized String init(Path root, Path planFile, Path export, Path policy,
            ReviewOrigin origin) throws IOException {
        StudyPlan plan = JSON.treeToValue(read(planFile), StudyPlan.class);
        var privateFile = export.resolve("private/assignment-manifest.json");
        var supplied = read(privateFile);
        List<ReviewAssignment> assignments = new ArrayList<>();
        var presentations = new HashMap<String, String>();
        var packets = new HashMap<String, JsonNode>();
        String instructions = null;
        for (JsonNode row : supplied.required("assignments")) {
            String packetId = text(row, "packetId");
            require(packetId.matches("packet-[0-9a-f]{24}"), "invalid packet ID");
            var packet = read(export.resolve("public/packets").resolve(packetId + ".json"));
            require(packets.put(packetId, packet) == null, "duplicate packet");
            assignments.add(new ReviewAssignment(text(row, "assignmentId"), text(row, "reviewerHash"), text(row, "caseId")));
            presentations.put(text(row, "blindedPresentationHash"), text(packet, "blindedPresentation"));
            instructions = text(packet, "reviewerInstructions");
        }
        require(instructions != null, "empty export");
        var expected = new InterestingnessBlindReviewPacketExporter().export(plan, assignments, presentations, instructions);
        require(JSON.readTree(expected.privateManifest().toCanonicalJson()).equals(supplied), "private manifest mismatch");
        require(JSON.readTree(expected.publicManifest().toCanonicalJson()).equals(read(export.resolve("public/manifest.json"))),
            "public manifest mismatch");
        for (var packet : expected.packets()) {
            require(JSON.readTree(packet.toCanonicalJson()).equals(packets.get(packet.id())), "packet mismatch");
        }
        byte[] policyBytes = Files.readAllBytes(policy);
        require(policyBytes.length > 0, "empty policy");
        var registration = JSON.createObjectNode();
        registration.put("schema", "regelsuche.restricted-review-registration/v1");
        registration.put("studyPlanHash", plan.contentHash());
        registration.put("policyHash", InterestingnessIndependentReviewStudy.sha256(policyBytes));
        registration.put("reviewerInstructionsHash", plan.reviewProtocol().reviewerInstructionsHash());
        registration.put("publicManifestHash", expected.publicManifest().contentHash());
        registration.put("privateAssignmentManifestHash", expected.privateManifest().contentHash());
        registration.put("origin", origin.name());
        registration.put("revision", 1);
        String registrationHash = digest(registration);
        var state = JSON.createObjectNode();
        state.set("registration", registration);
        state.put("registrationHash", registrationHash);
        state.set("plan", JSON.readTree(plan.toCanonicalJson()));
        state.set("assignments", supplied.required("assignments"));
        state.putObject("receipts");

        Path target = root.toAbsolutePath().normalize();
        Path parent = target.getParent();
        require(parent != null && Files.isDirectory(parent) && !Files.exists(target, LinkOption.NOFOLLOW_LINKS),
            "vault must be new and have an existing trusted parent");
        var privateMode = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"));
        Path stage = Files.createTempDirectory(parent, ".review-collection-", privateMode);
        try {
            Files.createDirectory(stage.resolve("prose"), privateMode);
            Files.createFile(stage.resolve("lock"));
            Files.write(stage.resolve("policy"), policyBytes);
            writeAtomically(stage.resolve(STATE), state);
            Files.move(stage, target, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            if (Files.exists(stage)) {
                try (var paths = Files.walk(stage)) {
                    for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
                }
            }
        }
        return registrationHash;
    }

    /** reviewerHash comes from the custodian-authenticated delivery channel, never the form. */
    public static String submit(Path root, String reviewerHash, String formJson) throws IOException {
        require(formJson.getBytes(StandardCharsets.UTF_8).length <= MAX_FORM_BYTES, "form too large");
        var form = JSON.readTree(formJson);
        require(form != null && form.isObject(), "expected one submission object");
        form.fieldNames().forEachRemaining(key -> require(FIELDS.contains(key), "unexpected form field"));
        return locked(root, state -> {
            if (state.has("frozen")) throw new IllegalStateException("collection is frozen");
            require(text(form, "registrationHash").equals(text(state, "registrationHash"))
                && integer(form, "revision") == 1, "registration or revision mismatch");
            String packetId = text(form, "packetId");
            JsonNode assignment = null;
            for (var row : state.required("assignments")) {
                if (packetId.equals(text(row, "packetId"))) assignment = row;
            }
            require(assignment != null && reviewerHash.equals(text(assignment, "reviewerHash"))
                && text(form, "packetContentHash").equals(text(assignment, "packetContentHash")), "assignment mismatch");
            var receipts = (ObjectNode) state.required("receipts");
            require(!receipts.has(packetId), "assignment already submitted");
            String disposition = text(form, "disposition");
            require(Set.of("RATED", "ABSTAINED", "EXCLUDED").contains(disposition), "unknown disposition");
            boolean blind = bool(form, "blind");
            boolean independent = bool(form, "independent");
            if (!disposition.equals("EXCLUDED")) require(blind && independent, "blind independent review required");
            if (disposition.equals("EXCLUDED")) {
                require(EXCLUSION_REASONS.contains(text(form, "reason")), "unknown exclusion reason");
            } else {
                require(!form.has("reason"), "reason is for exclusions only");
            }
            var receipt = JSON.createObjectNode();
            receipt.put("registrationHash", text(state, "registrationHash"));
            receipt.put("assignmentHash", text(assignment, "contentHash"));
            receipt.put("packetContentHash", text(assignment, "packetContentHash"));
            receipt.put("revision", 1);
            receipt.put("blind", blind);
            receipt.put("independent", independent);
            receipt.put("disposition", disposition);
            if (form.has("reason")) receipt.put("reason", text(form, "reason"));
            if (disposition.equals("RATED")) {
                StudyPlan plan = JSON.treeToValue(state.required("plan"), StudyPlan.class);
                var origin = ReviewOrigin.valueOf(text(state.required("registration"), "origin"));
                var label = new ReviewSubmission(packetId, plan.contentHash(), text(assignment, "caseId"),
                    text(assignment, "candidateId"), reviewerHash, origin, blind, integer(form, "relevancePermille"),
                    integer(form, "confidencePermille"), codes(form),
                    form.has("qualificationEvidenceHash") ? text(form, "qualificationEvidenceHash") : "", digest(receipt));
                var decision = new InterestingnessIndependentReviewIntake().evaluate(plan, List.of(label), 1, "").decisions().getFirst();
                require(decision.outcome() != IntakeOutcome.REJECTED, "submission violates frozen protocol: " + decision.blockers());
                receipt.set("label", JSON.valueToTree(label));
            } else {
                require(!form.has("relevancePermille") && !form.has("confidencePermille")
                    && !form.has("rationaleCodes") && !form.has("qualificationEvidenceHash"), "non-rating contains labels");
            }
            String prose = form.has("optionalProse") ? text(form, "optionalProse") : "";
            if (!prose.isEmpty()) receipt.put("proseHash", InterestingnessIndependentReviewStudy.sha256(
                prose.getBytes(StandardCharsets.UTF_8)));
            String receiptHash = digest(receipt);
            if (!prose.isEmpty()) {
                Files.writeString(root.resolve("prose").resolve(receiptHash.substring(7)), prose);
            }
            receipt.put("receiptHash", receiptHash);
            receipts.set(packetId, receipt);
            writeAtomically(root.resolve(STATE), state);
            return receiptHash;
        });
    }

    public static String freeze(Path root) throws IOException {
        return locked(root, state -> {
            if (state.has("frozen")) return text(state.required("frozen"), "freezeHash");
            var frozen = JSON.createObjectNode();
            frozen.put("schema", "regelsuche.restricted-review-freeze/v1");
            frozen.put("registrationHash", text(state, "registrationHash"));
            frozen.set("registration", state.required("registration"));
            frozen.set("historicalExposures", state.required("plan").required("historicalExposures"));
            var slots = frozen.putArray("slots");
            var submissions = new ArrayList<ReviewSubmission>();
            for (var assignment : state.required("assignments")) {
                var slot = slots.addObject();
                slot.set("assignment", assignment);
                JsonNode receipt = state.required("receipts").get(text(assignment, "packetId"));
                if (receipt == null) {
                    slot.put("disposition", "MISSING");
                } else {
                    var material = ((ObjectNode) receipt).deepCopy();
                    material.remove("receiptHash");
                    require(digest(material).equals(text(receipt, "receiptHash")), "receipt hash mismatch");
                    slot.setAll((ObjectNode) receipt);
                    if (receipt.has("label")) submissions.add(JSON.treeToValue(receipt.required("label"), ReviewSubmission.class));
                }
            }
            StudyPlan plan = JSON.treeToValue(state.required("plan"), StudyPlan.class);
            var intake = new InterestingnessIndependentReviewIntake().evaluate(plan, submissions, 1, "");
            frozen.set("intake", JSON.readTree(intake.toCanonicalJson()));
            String freezeHash = digest(frozen);
            frozen.put("freezeHash", freezeHash);
            state.set("frozen", frozen);
            writeAtomically(root.resolve(STATE), state);
            return freezeHash;
        });
    }

    /** The audit contains private pseudonymous assignments. It is available only after freeze. */
    public static String audit(Path root) throws IOException {
        return locked(root, state -> {
            if (!state.has("frozen")) throw new IllegalStateException("collection is open");
            ObjectNode frozen = ((ObjectNode) state.required("frozen")).deepCopy();
            var claimed = frozen.remove("freezeHash");
            require(digest(frozen).equals(claimed.asText()), "freeze hash mismatch");
            return state.required("frozen").toString();
        });
    }

    @FunctionalInterface
    private interface Operation {
        String apply(ObjectNode state) throws IOException;
    }

    private static synchronized String locked(Path root, Operation operation) throws IOException {
        require(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
            && Files.getPosixFilePermissions(root).equals(PosixFilePermissions.fromString("rwx------")),
            "owner-only vault required");
        try (var channel = FileChannel.open(root.resolve("lock"), StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
             var lock = channel.lock()) {
            return operation.apply((ObjectNode) read(root.resolve(STATE)));
        }
    }

    private static List<String> codes(JsonNode form) {
        JsonNode values = form.required("rationaleCodes");
        require(values.isArray(), "rationaleCodes must be an array");
        var result = new ArrayList<String>();
        var seen = new HashSet<String>();
        for (var value : values) {
            require(value.isTextual() && seen.add(value.textValue()), "invalid or duplicate rationale code");
            result.add(value.textValue());
        }
        return result;
    }

    private static int integer(JsonNode node, String key) {
        var value = node.required(key);
        require(value.isIntegralNumber() && value.canConvertToInt(), "expected integer " + key);
        return value.intValue();
    }

    private static boolean bool(JsonNode node, String key) {
        var value = node.required(key);
        require(value.isBoolean(), "expected boolean " + key);
        return value.booleanValue();
    }

    private static String text(JsonNode node, String key) {
        var value = node.required(key);
        require(value.isTextual(), "expected text " + key);
        return value.textValue();
    }

    private static String digest(JsonNode value) {
        return InterestingnessIndependentReviewStudy.sha256(value.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static JsonNode read(Path path) throws IOException {
        require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS), "expected regular input file");
        return JSON.readTree(Files.readString(path));
    }

    private static void writeAtomically(Path target, JsonNode data) throws IOException {
        Path temporary = Files.createTempFile(target.getParent(), ".review-", ".tmp");
        try {
            Files.writeString(temporary, data.toString());
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
