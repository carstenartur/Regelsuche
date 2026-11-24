package de.regelsuche.benchmark.peer;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;

class EnumoRecordedAuditTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path ROOT = Path.of(System.getProperty("regelsuche.repositoryRoot", "..")).toAbsolutePath();
    private static final Path DATA = ROOT.resolve("research/benchmarks/enumo-public-v1/evidence");

    @Test
    void rechecksAllRetainedCandidatesAndTheWholeForeignInventory() throws Exception {
        assertEquals(54, EnumoRecordedAudit.verify(ROOT, report(), raw(), rules()));
    }

    @Test
    void refusesOmissionOfAnUnsupportedProgram() throws Exception {
        ObjectNode report = report();
        ((ArrayNode) report.get("rows")).remove(0);
        assertThrows(IllegalArgumentException.class, () -> EnumoRecordedAudit.verify(ROOT, report, raw(), rules()));
    }

    @Test
    void refusesAChangedRawResponseEvenAfterItsHashIsUpdated() throws Exception {
        ObjectNode report = report();
        String[] lines = new String(raw(), StandardCharsets.UTF_8).split("\n");
        ObjectNode response = (ObjectNode) JSON.readTree(lines[0]);
        ((ObjectNode) response.get("response")).put("output", "x1");
        lines[0] = JSON.writeValueAsString(response);
        byte[] forged = (String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8);
        report.put("rawResponsesSha256", hash(forged));
        assertThrows(IllegalArgumentException.class, () -> EnumoRecordedAudit.verify(ROOT, report, forged, rules()));
    }

    @Test
    void recomputesEquivalenceRatherThanTrustingConsistentForgedRows() throws Exception {
        ObjectNode report = report();
        String[] lines = new String(raw(), StandardCharsets.UTF_8).split("\n");
        ObjectNode response = (ObjectNode) JSON.readTree(lines[0]);
        ObjectNode row = (ObjectNode) response.get("row");
        ((ObjectNode) response.get("response")).put("output", "x1");
        row.put("output", "x1"); row.put("outputCost", 0);
        for (JsonNode recorded : report.get("rows")) {
            if (sameKey(recorded, row)) { ((ObjectNode) recorded).put("output", "x1").put("outputCost", 0); }
        }
        lines[0] = JSON.writeValueAsString(response);
        byte[] forged = (String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8);
        report.put("rawResponsesSha256", hash(forged));
        assertThrows(IllegalArgumentException.class, () -> EnumoRecordedAudit.verify(ROOT, report, forged, rules()));
    }

    @Test
    void rejectsClaimedSuccessBeyondTheCommonRequestBudget() throws Exception {
        ObjectNode report = report();
        for (JsonNode row : report.get("rows")) if (row.path("status").asText().equals("VERIFIED")) {
            ((ObjectNode) row).put("requestAndJudgeNanos", 2_000_000_001L);
            break;
        }
        assertThrows(IllegalArgumentException.class, () -> EnumoRecordedAudit.verify(ROOT, report, raw(), rules()));
    }

    @Test
    void refusesAFalseNegativeStatusWithAnOtherwiseValidRawCandidate() throws Exception {
        assertRawMutationRejected(recorded -> ((ObjectNode) recorded.get("row")).put("status", "INVALID"), ignored -> { });
    }

    @Test
    void negativeStatusCannotHideATargetOrForeignSourceRequest() throws Exception {
        assertRawMutationRejected(recorded -> {
            ((ObjectNode) recorded.get("row")).put("status", "INVALID");
            ((ObjectNode) recorded.get("request")).put("source", "x+1").put("target", "1");
        }, ignored -> { });
    }

    @Test
    void outerWallTimeCannotBeShorterThanTheRetainedWorkerInterval() throws Exception {
        assertRawMutationRejected(recorded -> ((ObjectNode) recorded.get("row")).put("requestAndJudgeNanos", 1), ignored -> { });
    }

    @Test
    void checksWorkLedgerAndProfileAgainstTheFrozenSetup() throws Exception {
        assertRawMutationRejected(recorded -> ((ObjectNode) recorded.get("response")).put("totalWork", 999999999), ignored -> { });
        assertRawMutationRejected(recorded -> ((ObjectNode) recorded.get("response")).put("profile", "EXPERT"), ignored -> { });
        assertRawMutationRejected(recorded -> ((ObjectNode) recorded.get("response")).put("modelHash", "sha256:" + "0".repeat(64)), ignored -> { });
        assertRawMutationRejected(ignored -> { }, report ->
            ((ObjectNode) report.path("setups").get(0).path("initialization")).put("profile", "EXPERT"));
    }

    @Test
    void requiresAcquisitionAndValidExecutableIdentities() throws Exception {
        assertRawMutationRejected(ignored -> { }, report ->
            ((ObjectNode) report.get("acquisition")).put("status", "FAILED").put("rules", 0));
        assertRawMutationRejected(ignored -> { }, report -> report.put("enumoBinarySha256", "invalid"));
        assertRawMutationRejected(ignored -> { }, report -> report.put("nativeWorkerClassSha256", "invalid"));
    }

    private static void assertRawMutationRejected(java.util.function.Consumer<ObjectNode> changeRaw,
            java.util.function.Consumer<ObjectNode> changeReport) throws Exception {
        ObjectNode report = report();
        String[] lines = new String(raw(), StandardCharsets.UTF_8).split("\n");
        ObjectNode recorded = (ObjectNode) JSON.readTree(lines[0]);
        changeRaw.accept(recorded);
        ArrayNode rows = (ArrayNode) report.get("rows");
        for (int i = 0; i < rows.size(); i++) if (sameKey(rows.get(i), recorded.get("row"))) {
            rows.set(i, recorded.get("row").deepCopy());
        }
        lines[0] = JSON.writeValueAsString(recorded);
        byte[] forged = (String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8);
        report.put("rawResponsesSha256", hash(forged));
        changeReport.accept(report);
        assertThrows(IllegalArgumentException.class, () -> EnumoRecordedAudit.verify(ROOT, report, forged, rules()));
    }

    private static boolean sameKey(JsonNode a, JsonNode b) {
        return a.path("profile").equals(b.path("profile")) && a.path("repetition").equals(b.path("repetition"))
            && a.path("ordinal").equals(b.path("ordinal"));
    }
    private static ObjectNode report() throws Exception { return (ObjectNode) JSON.readTree(gunzip("results.json.gz")); }
    private static byte[] raw() throws Exception { return gunzip("responses.jsonl.gz"); }
    private static byte[] rules() throws Exception { return Files.readAllBytes(DATA.resolve("learned.rules")); }
    private static byte[] gunzip(String name) throws Exception {
        try (var input = new GZIPInputStream(Files.newInputStream(DATA.resolve(name)))) { return input.readAllBytes(); }
    }
    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
