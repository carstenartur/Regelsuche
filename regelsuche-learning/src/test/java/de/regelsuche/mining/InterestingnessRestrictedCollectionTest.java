package de.regelsuche.mining;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.regelsuche.mining.InterestingnessIndependentReviewIntake.ReviewOrigin;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InterestingnessRestrictedCollectionTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path temp;
    private Path vault;
    private Path exchange;
    private Path planFile;
    private Path policy;
    private String registration;
    private List<JsonNode> assignments;

    private void prepare() throws Exception {
        var plan = IndependentReviewProtocolFixtures.plan();
        exchange = temp.resolve("exchange");
        vault = temp.resolve("vault");
        planFile = temp.resolve("plan.json");
        policy = temp.resolve("draft.md");
        Files.writeString(planFile, plan.toCanonicalJson());
        Files.writeString(policy, "Synthetic policy draft.");
        new InterestingnessBlindReviewPacketExporter().exportToDirectory(plan,
            InterestingnessBlindReviewPacketExporterTest.assignments(plan),
            InterestingnessBlindReviewPacketExporterTest.materials(plan), "f", exchange);
        assignments = new ArrayList<>();
        JSON.readTree(exchange.resolve("private/assignment-manifest.json").toFile())
            .required("assignments").forEach(assignments::add);
    }

    private void start() throws Exception {
        prepare();
        registration = InterestingnessRestrictedCollection.init(vault, planFile, exchange, policy,
            ReviewOrigin.DEVELOPMENT_FIXTURE);
    }

    private ObjectNode form(int slot) {
        JsonNode assignment = assignments.get(slot);
        var form = JSON.createObjectNode();
        form.put("registrationHash", registration);
        form.put("packetId", assignment.required("packetId").asText());
        form.put("packetContentHash", assignment.required("packetContentHash").asText());
        form.put("revision", 1);
        form.put("disposition", "RATED");
        form.put("blind", true);
        form.put("independent", true);
        form.put("relevancePermille", 750);
        form.put("confidencePermille", 500);
        form.putArray("rationaleCodes").add("technical-interest");
        form.put("optionalProse", "Private phrase only");
        return form;
    }

    private String submit(int slot, ObjectNode form) throws Exception {
        return InterestingnessRestrictedCollection.submit(vault,
            assignments.get(slot).required("reviewerHash").asText(), form.toString());
    }

    @Test void freezeKeepsRosterAndOnlyOpaqueReceiptsLeaveCustody() throws Exception {
        start();
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(vault)));
        assertThrows(IllegalStateException.class, () -> InterestingnessRestrictedCollection.audit(vault));
        String rated = submit(0, form(0));
        assertTrue(rated.matches("sha256:[0-9a-f]{64}"));
        var abstain = form(1);
        abstain.put("disposition", "ABSTAINED");
        abstain.remove(List.of("relevancePermille", "confidencePermille", "rationaleCodes"));
        submit(1, abstain);
        var excluded = form(2);
        excluded.put("disposition", "EXCLUDED");
        excluded.put("reason", "WITHDRAWN");
        excluded.remove(List.of("relevancePermille", "confidencePermille", "rationaleCodes"));
        submit(2, excluded);

        String receipt = InterestingnessRestrictedCollection.freeze(vault);
        assertEquals(receipt, InterestingnessRestrictedCollection.freeze(vault));
        assertThrows(IllegalStateException.class, () -> submit(3, form(3)));
        var audit = JSON.readTree(InterestingnessRestrictedCollection.audit(vault));
        assertEquals(receipt, audit.required("freezeHash").asText());
        assertEquals(registration, audit.required("registrationHash").asText());
        var slots = audit.required("slots");
        assertEquals(8, slots.size());
        assertEquals("RATED", slots.get(0).required("disposition").asText());
        assertEquals(rated, slots.get(0).required("receiptHash").asText());
        assertEquals("ABSTAINED", slots.get(1).required("disposition").asText());
        assertEquals("WITHDRAWN", slots.get(2).required("reason").asText());
        assertEquals("MISSING", slots.get(3).required("disposition").asText());
        assertEquals(1, audit.required("intake").required("decisions").size());
        assertEquals("DEVELOPMENT_ONLY", audit.required("intake").required("evidenceStatus").asText());
        for (var status : audit.required("intake").required("candidateStatuses")) {
            assertEquals(0, status.required("countedExpertReviews").asInt());
        }
        assertFalse(audit.toString().contains("Private phrase only"));
        assertTrue(Files.readString(vault.resolve("prose").resolve(rated.substring(7))).contains("Private phrase only"));
    }

    @Test void refusedRequestsDoNotReserveTheSlotOrLeakLabels() throws Exception {
        start();
        for (var bad : List.of(form(0).put("blind", false), form(0).put("independent", false),
                form(0).put("revision", 2), form(0).put("registrationHash", IndependentReviewProtocolFixtures.hash('x')),
                form(0).put("packetContentHash", IndependentReviewProtocolFixtures.hash('x')),
                form(0).put("relevancePermille", 10), form(0).put("confidencePermille", 10),
                form(0).put("disposition", "ABSTAINED"), form(0).put("candidateId", "chosen"),
                form(0).put("blind", "true"))) {
            assertThrows(IllegalArgumentException.class, () -> submit(0, bad));
        }
        var badCode = form(0);
        badCode.putArray("rationaleCodes").add("invented-code");
        assertThrows(IllegalArgumentException.class, () -> submit(0, badCode));
        assertThrows(IllegalArgumentException.class, () -> submit(1, form(0)));
        submit(0, form(0));
        assertThrows(IllegalArgumentException.class, () -> submit(0, form(0)));
    }

    @Test void concurrentSubmittersCanOnlyClaimOneSlot() throws Exception {
        start();
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> attempt = () -> {
                try {
                    submit(0, form(0));
                    return true;
                } catch (IllegalArgumentException duplicate) {
                    return false;
                }
            };
            var results = pool.invokeAll(List.of(attempt, attempt));
            assertNotEquals(results.get(0).get(), results.get(1).get());
        }
        InterestingnessRestrictedCollection.freeze(vault);
        assertEquals(1, JSON.readTree(InterestingnessRestrictedCollection.audit(vault))
            .required("intake").required("decisions").size());
    }

    @Test void externalCustodyRequiresQualificationAndDoesNotCountNonBlindExclusions() throws Exception {
        prepare();
        registration = InterestingnessRestrictedCollection.init(vault, planFile, exchange, policy, ReviewOrigin.EXTERNAL_EXPERT);
        assertThrows(IllegalArgumentException.class, () -> submit(0, form(0)));
        var conflict = form(0).put("disposition", "EXCLUDED").put("reason", "EXPOSURE")
            .put("blind", false).put("independent", false);
        conflict.remove(List.of("relevancePermille", "confidencePermille", "rationaleCodes"));
        submit(0, conflict);
        InterestingnessRestrictedCollection.freeze(vault);
        var audit = JSON.readTree(InterestingnessRestrictedCollection.audit(vault));
        assertEquals(0, audit.required("intake").required("decisions").size());
        assertEquals("EXPOSURE", audit.required("slots").get(0).required("reason").asText());
        assertFalse(audit.required("slots").get(0).required("blind").asBoolean());
    }

    @Test void aDisagreementSurvivesAlongsideTheOriginalFixtureReceipts() throws Exception {
        start();
        var first = form(0).put("relevancePermille", 0);
        var second = form(1).put("relevancePermille", 1000);
        submit(0, first);
        submit(1, second);
        InterestingnessRestrictedCollection.freeze(vault);
        var audit = JSON.readTree(InterestingnessRestrictedCollection.audit(vault));
        assertEquals(0, audit.required("slots").get(0).required("label").required("relevancePermille").asInt());
        assertEquals(1000, audit.required("slots").get(1).required("label").required("relevancePermille").asInt());
        for (var decision : audit.required("intake").required("decisions")) {
            assertEquals("DEVELOPMENT_ONLY", decision.required("outcome").asText());
        }
    }

    @Test void correctionBindsPredecessorAndCarriesTheOtherReceiptsForward() throws Exception {
        start();
        submit(0, form(0));
        submit(1, form(1));
        String first = InterestingnessRestrictedCollection.freeze(vault);
        var before = JSON.readTree(InterestingnessRestrictedCollection.audit(vault));
        assertThrows(IllegalArgumentException.class, () ->
            InterestingnessRestrictedCollection.revise(vault, IndependentReviewProtocolFixtures.hash('x'), "TRANSCRIPTION"));
        InterestingnessRestrictedCollection.revise(vault, first, "WITHDRAWAL");
        assertThrows(IllegalStateException.class, () -> InterestingnessRestrictedCollection.audit(vault));
        var withdrawal = form(0).put("revision", 2).put("disposition", "EXCLUDED").put("reason", "WITHDRAWN");
        withdrawal.remove(List.of("rationaleCodes", "confidencePermille", "relevancePermille", "optionalProse"));
        submit(0, withdrawal);
        assertThrows(IllegalArgumentException.class, () -> submit(0, withdrawal));
        String second = InterestingnessRestrictedCollection.freeze(vault);
        var after = JSON.readTree(InterestingnessRestrictedCollection.audit(vault));
        assertNotEquals(first, second);
        assertEquals(first, after.required("priorFreezeHash").asText());
        assertEquals(before.required("intake").required("labeledEvaluationHash"),
            after.required("intake").required("priorLabeledEvaluationHash"));
        assertEquals(before.required("intake").required("predictiveCorpusHash"),
            after.required("intake").required("predictiveCorpusHash"));
        assertEquals(before.required("slots").get(1), after.required("slots").get(1));
        assertEquals("EXCLUDED", after.required("slots").get(0).required("disposition").asText());
        assertEquals("WITHDRAWAL", after.required("correctionReason").asText());
        assertEquals(before, JSON.readTree(InterestingnessRestrictedCollection.audit(vault, 1)));
    }

    @Test void rejectsModifiedPacketsAndDifferentRegistrationInputs() throws Exception {
        prepare();
        var packet = exchange.resolve("public/packets/" + assignments.get(0).required("packetId").asText() + ".json");
        String original = Files.readString(packet);
        Files.writeString(packet, original.replace("technical-interest", "invented-code"));
        assertThrows(IllegalArgumentException.class, () -> InterestingnessRestrictedCollection.init(
            vault, planFile, exchange, policy, ReviewOrigin.DEVELOPMENT_FIXTURE));
        assertFalse(Files.exists(vault));
        Files.writeString(packet, original);
        String first = InterestingnessRestrictedCollection.init(vault, planFile, exchange, policy,
            ReviewOrigin.DEVELOPMENT_FIXTURE);
        Files.writeString(policy, "Different draft.");
        assertNotEquals(first, InterestingnessRestrictedCollection.init(temp.resolve("other"), planFile, exchange, policy,
            ReviewOrigin.DEVELOPMENT_FIXTURE));
    }
}
