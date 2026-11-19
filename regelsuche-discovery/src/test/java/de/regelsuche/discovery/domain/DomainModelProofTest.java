package de.regelsuche.discovery.domain;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.regelsuche.discovery.domain.DiscoveryDomain.DiscoveryBudget;
import de.regelsuche.discovery.domain.DiscoveryDomain.DiscoverySeed;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DomainModelProofTest {
    @TempDir Path directory;
    private int exports;

    @Test void bothRetainedDomainsProduceCheckedModelsWithExactFutureValues() {
        for (boolean recurrence : new boolean[] {false, true}) {
            var workspace = workspace(DomainDownstreamValidationTest.source(recurrence, false));
            String original = workspace.toCanonicalJson();
            var receipt = DomainModelProof.prove(workspace);
            var json = read(receipt);
            assertEquals(DomainModelProof.Status.PROVED_MODEL, receipt.status());
            assertEquals(DomainDownstreamValidation.sourceBinding(workspace), json.path("source"));
            assertEquals("CONFIRMED_FINITE_DATA", json.at("/finiteCheck/status").asText());
            assertEquals("IDENTICAL_CANONICAL_EVIDENCE", json.at("/replay/status").asText());
            assertEquals("EXPLICIT_AFFINE_MODEL", json.at("/proof/scope").asText());
            assertEquals("NOT_ESTABLISHED", json.path("originalSequenceProofStatus").asText());
            assertEquals("NOT_EVALUATED", json.path("formalProofStatus").asText());
            assertEquals("NOT_EVALUATED", json.path("externalNoveltyStatus").asText());
            assertEquals("NOT_EVALUATED", json.path("promotionStatus").asText());
            assertEquals("NOT_EVALUATED", json.path("publicEvidenceStatus").asText());
            assertEquals(recurrence ? List.of("1", "1") : List.of("1", "-3", "3"),
                texts(json.at("/proof/coefficientsOldestFirst")));
            assertEquals(recurrence ? "1500520536206896083277" : "10201", query(json, 100).path("value").asText());
            assertEquals("MODEL_PREDICTION", query(json, 100).path("role").asText());
            assertTrue(query(json, 100).path("matchesRetained").isNull());
            assertTrue(query(json, 0).path("matchesRetained").asBoolean());
            assertEquals(original, workspace.toCanonicalJson());
            assertEquals(receipt.toCanonicalJson(), DomainModelProof.prove(workspace).toCanonicalJson());
            DomainModelProof.requireRetained(workspace, receipt.contentHash(), bytes(receipt));
            assertBalanced(json);
        }
    }

    @Test void companionProjectionPreservesAsymmetricCoefficientOrder() {
        var workspace = workspace(recurrence("observed=1,4,11,26,57,120;holdout=247,502,1013;maximumOrder=3"));
        var json = read(DomainModelProof.prove(workspace));
        assertEquals("PROVED_MODEL", json.path("status").asText());
        assertEquals(List.of("2", "-5", "4"), texts(json.at("/model/linear/2")));
        assertEquals(List.of("2", "-5", "4"), texts(json.at("/proof/coefficientsOldestFirst")));
        assertEquals(List.of("1", "4", "11"), texts(json.at("/model/initialState")));
        assertEquals(BigInteger.TWO.pow(102).subtract(BigInteger.valueOf(103)).toString(),
            query(json, 100).path("value").asText());
    }

    @Test void rationalCoefficientsRemainExactWhenFutureTermsAreNotIntegers() {
        var json = read(DomainModelProof.prove(workspace(recurrence(
            "observed=64,32,16,8;holdout=4,2,1;maximumOrder=2"))));
        assertEquals("PROVED_MODEL", json.path("status").asText());
        assertEquals(List.of("1/2"), texts(json.at("/model/linear/0")));
        assertEquals(List.of("1/2"), texts(json.at("/proof/coefficientsOldestFirst")));
        assertEquals("1/" + BigInteger.TWO.pow(94), query(json, 100).path("value").asText());
    }

    @Test void indexOneHundredIsNotDuplicatedOrMislabelledWhenAlreadyRetained() {
        String holdout = String.join(",", java.util.Collections.nCopies(98, "1"));
        var json = read(DomainModelProof.prove(workspace(differences("observed=1,1,1;holdout=" + holdout))));
        assertEquals("PROVED_MODEL", json.path("status").asText());
        assertEquals(101, json.at("/proof/queries").size());
        assertEquals("RETAINED_MODEL_TERM", query(json, 100).path("role").asText());
        assertTrue(query(json, 100).path("matchesRetained").asBoolean());
        assertEquals(1, json.at("/proof/queries").valueStream().filter(row -> row.path("index").asInt() == 100).count());
    }

    @Test void constantAffineAndCubicDifferencesKeepTheirExactOffsetAndStart() {
        var payloads = List.of("observed=5,5,5;holdout=5,5",
            "observed=3,7,11,15;holdout=19,23",
            "observed=1,8,27,64,125,216;holdout=343,512,729");
        var offsets = List.of(List.of("0"), List.of("4"), List.of("0", "0", "6"));
        var initial = List.of(List.of("5"), List.of("3"), List.of("1", "7", "12"));
        var future = List.of("5", "403", "1030301");
        for (int index = 0; index < payloads.size(); index++) {
            var json = read(DomainModelProof.prove(workspace(differences(payloads.get(index)))));
            assertEquals("PROVED_MODEL", json.path("status").asText());
            assertEquals(offsets.get(index), texts(json.at("/model/offset")));
            assertEquals(initial.get(index), texts(json.at("/model/initialState")));
            assertEquals(future.get(index), query(json, 100).path("value").asText());
        }
    }

    @Test void fourthOrderModelIsUnsupportedBeforeCallingTheProofBackend() {
        var sources = List.of(differences("observed=1,16,81,256,625,1296;holdout=2401,4096"),
            recurrence("observed=1,8,27,64,125,216,343,512;holdout=729,1000;maximumOrder=4"));
        for (var source : sources) {
            assertEquals(DomainDiscoveryEvidence.Outcome.CONFIRMED, source.outcome());
            var json = read(DomainModelProof.prove(workspace(source)));
            assertEquals("UNSUPPORTED", json.path("status").asText());
            assertTrue(json.path("model").isNull());
            assertTrue(json.path("proof").isNull());
            assertEquals("NOT_RUN", json.at("/work/preparation/status").asText());
            assertEquals(0, json.at("/work/preparation/executed").asInt());
        }
    }

    @Test void everyAdmissionAndProofPhaseCanStopWithoutPublishingAProof() {
        var workspace = workspace(differences("observed=1,4,9,16;holdout=25,36"));
        var defaults = DomainModelProof.Budget.defaults();
        var blocked = DomainModelProof.prove(workspace, new DomainModelProof.Budget(0, 512,
            defaults.finiteCheck(), 200_000, 200_000, 200_000));
        assertEquals(DomainModelProof.Status.INCONCLUSIVE, blocked.status());
        assertEquals(0, read(blocked).at("/replay/admission/executed").asInt());
        var partial = DomainModelProof.prove(workspace, new DomainModelProof.Budget(1, 512,
            new DomainFiniteDataCheck.Budget(2, 1000, 1024), 200_000, 200_000, 200_000));
        assertEquals(DomainModelProof.Status.FINITE_CHECK_NOT_CONFIRMED, partial.status());
        assertEquals("INCONCLUSIVE", read(partial).at("/finiteCheck/status").asText());
        assertTrue(read(partial).path("proof").isNull());
        for (int phase = 0; phase < 3; phase++) {
            var budget = new DomainModelProof.Budget(1, 512, defaults.finiteCheck(),
                phase == 0 ? 0 : 200_000, phase == 1 ? 0 : 200_000, phase == 2 ? 0 : 200_000);
            var receipt = DomainModelProof.prove(workspace, budget);
            var json = read(receipt);
            assertEquals(DomainModelProof.Status.INCONCLUSIVE, receipt.status());
            assertTrue(json.path("model").isObject());
            assertTrue(json.path("proof").isNull());
            assertEquals("BUDGET_INCONCLUSIVE", json.at("/work/" + List.of("preparation", "binding", "queries").get(phase)
                + "/status").asText());
            DomainModelProof.requireRetained(workspace, receipt.contentHash(), bytes(receipt));
            assertBalanced(json);
        }
        assertThrows(IllegalArgumentException.class, () -> new DomainModelProof.Budget(1, 512,
            defaults.finiteCheck(), 200_001, 10, 10));
    }

    @Test void missingCandidateAndFalseRehashedSourceKeepDistinctOutcomes() {
        var refuted = workspace(DomainDownstreamValidationTest.source(false, true));
        var absent = DomainModelProof.prove(refuted);
        assertEquals(DomainModelProof.Status.NOT_EVALUATED_NO_SELECTED_CANDIDATE, absent.status());
        assertTrue(read(absent).path("finiteCheck").isNull());
        assertTrue(read(absent).path("proof").isNull());
        DomainModelProof.requireRetained(refuted, absent.contentHash(), bytes(absent));

        var source = DomainDownstreamValidationTest.source(false, false);
        var states = new ArrayList<>(source.states());
        var first = states.getFirst();
        states.set(0, new DomainDiscoveryEvidence.StateTrace(first.sequence(), first.stateHash(), first.canonicalState(),
            first.depth(), first.objectiveScore() + 1, first.candidateReady(), first.parentStateHash(),
            first.actionId(), first.objectiveMetrics()));
        var altered = new DomainDiscoveryEvidence(source.campaignId(), source.descriptor(), source.seed(), source.budget(),
            source.outcome(), states, source.transitions(), source.candidateAttempts(), source.resources(),
            source.selectedCandidateHash(), source.certificate(), source.domainEvidence());
        var mismatch = read(DomainModelProof.prove(workspace(altered)));
        assertEquals("REPLAY_MISMATCH", mismatch.path("status").asText());
        assertEquals("MISMATCH", mismatch.at("/replay/status").asText());
        assertTrue(mismatch.path("proof").isNull());
        assertEquals(5, mismatch.at("/replay/resources").size());
    }

    @Test void aReplayThatThrowsDoesNotInventReturnedWorkOrMathematicalEvidence() {
        var source = DomainDownstreamValidationTest.source(false, false);
        var malformedSeed = DiscoverySeed.create(source.seed().seedId(), source.seed().domainId(),
            "malformed-sequence-payload", source.seed().sourceReference());
        var malformed = new DomainDiscoveryEvidence(source.campaignId(), source.descriptor(), malformedSeed, source.budget(),
            source.outcome(), source.states(), source.transitions(), source.candidateAttempts(), source.resources(),
            source.selectedCandidateHash(), source.certificate(), source.domainEvidence());
        var workspace = workspace(malformed);
        var receipt = DomainModelProof.prove(workspace);
        var json = read(receipt);
        assertEquals(DomainModelProof.Status.TECHNICAL_FAILURE, receipt.status());
        assertEquals("TECHNICAL_FAILURE", json.at("/replay/status").asText());
        assertEquals(1, json.at("/replay/admission/executed").asInt());
        assertTrue(json.at("/replay/resources").isNull());
        assertTrue(json.at("/replay/actualEvidenceHash").isNull());
        assertTrue(json.path("model").isNull());
        assertTrue(json.path("proof").isNull());
        assertTrue(json.path("finiteCheck").isNull());
        DomainModelProof.requireRetained(workspace, receipt.contentHash(), bytes(receipt));
    }

    @Test void retainedProofRecomputesModelAndMathRatherThanTrustingRehashedClaims() {
        var workspace = workspace(differences("observed=1,4,9,16;holdout=25,36"));
        var receipt = DomainModelProof.prove(workspace);
        var foreign = workspace(differences("observed=2,5,10,17;holdout=26,37"));
        assertThrows(IllegalArgumentException.class,
            () -> DomainModelProof.requireRetained(foreign, receipt.contentHash(), bytes(receipt)));
        assertThrows(IllegalArgumentException.class,
            () -> DomainModelProof.requireRetained(workspace, DomainCanonical.sha256("foreign"), bytes(receipt)));
        for (String damage : List.of("model", "proof", "work", "claim", "budget")) {
            ObjectNode changed = (ObjectNode) read(receipt).deepCopy();
            switch (damage) {
                case "model" -> ((com.fasterxml.jackson.databind.node.ArrayNode) changed.at("/model/offset")).set(1,
                    DomainExportWorkspace.JSON.getNodeFactory().textNode("3"));
                case "proof" -> ((ObjectNode) query(changed, 100)).put("value", "10202");
                case "work" -> ((ObjectNode) changed.at("/work/preparation")).put("executed", 0)
                    .put("remaining", changed.at("/work/preparation/configured").asInt());
                case "claim" -> changed.put("originalSequenceProofStatus", "PROVED");
                case "budget" -> ((ObjectNode) changed.path("budget")).put("maxPreparationWorkUnits", 200_001);
                default -> fail(damage);
            }
            // All reported hashes and balances are self-consistent; actual source/proof recomputation must reject them.
            rehash((ObjectNode) changed.path("model"));
            ((ObjectNode) changed.path("proof")).put("modelHash", changed.at("/model/contentHash").asText());
            rehash((ObjectNode) changed.path("proof"));
            String hash = rehash(changed);
            assertThrows(IllegalArgumentException.class, () -> DomainModelProof.requireRetained(workspace, hash,
                DomainExportWorkspace.write(changed).getBytes(StandardCharsets.UTF_8)), damage);
        }
        assertThrows(IllegalArgumentException.class, () -> DomainModelProof.requireRetained(workspace,
            receipt.contentHash(), (" " + receipt.toCanonicalJson()).getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class, () -> DomainModelProof.requireRetained(workspace,
            receipt.contentHash(), "{\"schema\":1,\"schema\":2}".getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class, () -> DomainModelProof.requireRetained(workspace,
            receipt.contentHash(), new byte[1_048_577]));
    }

    private DomainExportWorkspace workspace(DomainDiscoveryEvidence source) {
        Path export = directory.resolve("export-" + exports++);
        new DomainDiscoveryExport().write(export, source);
        return DomainExportWorkspace.fromVerified(new DomainDiscoveryExportVerifier().requireVerified(export));
    }

    private static DomainDiscoveryEvidence differences(String payload) {
        return run(new FiniteDifferenceSequenceDomain(), payload);
    }

    private static DomainDiscoveryEvidence recurrence(String payload) {
        return run(new LinearRecurrenceSequenceDomain(), payload);
    }

    private static <S, C, K> DomainDiscoveryEvidence run(DiscoveryDomain<S, C, K> domain, String payload) {
        return new DomainDiscoveryRunner().run("model-proof-control", domain,
            DiscoverySeed.create("model-proof-source", domain.domainId(), payload, "unit control"),
            new DiscoveryBudget(8, 32, 32, 8, 8, 64)).evidence();
    }

    private static JsonNode read(DomainModelProof.Receipt receipt) {
        return DomainExportWorkspace.read(receipt.toCanonicalJson());
    }

    private static byte[] bytes(DomainModelProof.Receipt receipt) {
        return receipt.toCanonicalJson().getBytes(StandardCharsets.UTF_8);
    }

    private static JsonNode query(JsonNode receipt, int index) {
        return receipt.at("/proof/queries").valueStream().filter(row -> row.path("index").asInt() == index)
            .findFirst().orElseThrow();
    }

    private static List<String> texts(JsonNode array) { return array.valueStream().map(JsonNode::asText).toList(); }

    private static String rehash(ObjectNode value) {
        value.remove("contentHash");
        String hash = DomainCanonical.sha256(DomainExportWorkspace.write(value));
        value.put("contentHash", hash);
        return hash;
    }

    private static void assertBalanced(JsonNode receipt) {
        for (String phase : List.of("preparation", "binding", "queries")) {
            JsonNode work = receipt.at("/work/" + phase);
            assertEquals(work.path("configured").asInt(), work.path("executed").asInt()
                + work.path("skipped").asInt() + work.path("remaining").asInt());
        }
        assertEquals("UNAVAILABLE", receipt.at("/work/totalWork").asText());
    }
}
