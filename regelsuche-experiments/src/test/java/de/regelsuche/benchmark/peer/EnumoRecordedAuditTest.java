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
    void rechecksTheFourArmRosaAndTransferPanels() throws Exception {
        var data = ROOT.resolve(EnumoPublicComparison.HORNER_ARTIFACTS).resolve("evidence");
        for (String panel : java.util.List.of("rosa", "transfer")) {
            Path directory = data.resolve(panel);
            JsonNode report = JSON.readTree(gunzip(directory.resolve("results.json.gz")));
            assertEquals((panel.equals("rosa") ? 37 : 46) * 12, report.path("rows").size());
            long expected = java.util.stream.StreamSupport.stream(report.path("rows").spliterator(), false)
                .filter(row -> row.path("status").asText().equals("VERIFIED")).count();
            assertEquals(panel.equals("rosa") ? 72 : 30, expected);
            assertEquals(expected, EnumoRecordedAudit.verify(ROOT, report,
                gunzip(directory.resolve("responses.jsonl.gz")), Files.readAllBytes(directory.resolve("learned.rules"))));
        }
    }

    @Test
    void rechecksBothRootHornerPanelsWithAllFiveArmsAndNoProfileDrift() throws Exception {
        var data = ROOT.resolve(EnumoPublicComparison.HORNER_ARTIFACTS).resolve("evidence");
        for (String panel : java.util.List.of("root-rosa", "root-transfer")) {
            Path directory = data.resolve(panel);
            JsonNode report = JSON.readTree(gunzip(directory.resolve("results.json.gz")));
            boolean rosa = panel.equals("root-rosa");
            assertEquals((rosa ? 37 : 46) * 15, report.path("rows").size());
            assertEquals(rosa ? 90 : 39, EnumoRecordedAudit.verify(ROOT, report,
                gunzip(directory.resolve("responses.jsonl.gz")), Files.readAllBytes(directory.resolve("learned.rules"))));
        }
    }

    @Test
    void rejectsAnUnboundHornerWitnessAfterTheRawHashIsRecomputed() throws Exception {
        var directory = ROOT.resolve(EnumoPublicComparison.HORNER_ARTIFACTS).resolve("evidence/rosa");
        var report = (ObjectNode) JSON.readTree(gunzip(directory.resolve("results.json.gz")));
        String[] lines = new String(gunzip(directory.resolve("responses.jsonl.gz")), StandardCharsets.UTF_8).split("\n");
        boolean edited = false;
        for (int index = 0; index < lines.length; index++) {
            JsonNode entry = JSON.readTree(lines[index]);
            JsonNode witness = entry.path("response").path("witness");
            if (!entry.path("profile").asText().equals("REGELSUCHE_POLYNOMIAL_HORNER") || witness.isEmpty()) continue;
            ((ObjectNode) witness.get(0)).put("source", "unbound");
            lines[index] = JSON.writeValueAsString(entry);
            edited = true;
            break;
        }
        assertTrue(edited);
        byte[] forged = (String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8);
        report.put("rawResponsesSha256", hash(forged));
        var rejected = assertThrows(IllegalArgumentException.class, () -> EnumoRecordedAudit.verify(ROOT, report,
            forged, Files.readAllBytes(directory.resolve("learned.rules"))));
        assertEquals("Horner witness path binding", rejected.getMessage());
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

    @Test
    void refusesABlockedRowBeforeAnyWorkerFailure() throws Exception {
        assertMissingRawRejected(row -> {
            discardCandidate(row);
            row.remove("requestAndJudgeNanos");
            row.put("status", "BLOCKED_WORKER_UNAVAILABLE");
        });
    }

    @Test
    void refusesATimeoutShorterThanTheRequestAllowance() throws Exception {
        assertMissingRawRejected(row -> {
            discardCandidate(row);
            row.put("status", "TIMEOUT").put("requestAndJudgeNanos", 1);
        });
    }

    @Test
    void refusesLaterResponsesFromAWorkerAlreadyClosedByTimeout() throws Exception {
        assertMissingRawRejected(row -> {
            discardCandidate(row);
            row.put("status", "TIMEOUT").put("requestAndJudgeNanos", 2_000_000_000L);
        });
    }

    @Test
    void acceptsAConsistentTimeoutAndRetainsItsBlockedSuccessors() throws Exception {
        ObjectNode report = report();
        boolean first = true;
        for (JsonNode node : report.path("rows")) {
            if (!node.path("profile").asText().equals("REGELSUCHE_BASE") || node.path("repetition").asInt() != 1
                    || node.path("status").asText().equals("UNSUPPORTED_INPUT")) continue;
            ObjectNode row = (ObjectNode) node;
            discardCandidate(row);
            if (first) row.put("status", "TIMEOUT").put("requestAndJudgeNanos", 2_000_000_000L);
            else { row.put("status", "BLOCKED_WORKER_UNAVAILABLE"); row.remove("requestAndJudgeNanos"); }
            first = false;
        }
        var retained = new StringBuilder();
        for (String line : new String(raw(), StandardCharsets.UTF_8).lines().toList()) {
            JsonNode node = JSON.readTree(line).path("row");
            if (!(node.path("profile").asText().equals("REGELSUCHE_BASE") && node.path("repetition").asInt() == 1))
                retained.append(line).append('\n');
        }
        byte[] changed = retained.toString().getBytes(StandardCharsets.UTF_8);
        report.put("rawResponsesSha256", hash(changed));
        assertEquals(48, EnumoRecordedAudit.verify(ROOT, report, changed, rules()));
    }

    @Test
    void bindsActualRulesToTheAcquisitionProvenance() throws Exception {
        ObjectNode report = report();
        String[] lines = new String(rules(), StandardCharsets.UTF_8).split("\n");
        lines[0] = "(* ?a ?b) ==> 0";
        byte[] changed = (String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8);
        report.put("acquiredRulesSha256", hash(changed));
        assertThrows(IllegalArgumentException.class, () -> EnumoRecordedAudit.verify(ROOT, report, raw(), changed));
    }

    @Test
    void derivesTheBudgetDecisionInBothDirections() throws Exception {
        assertRawMutationRejected(recorded -> {
            ((ObjectNode) recorded.get("response")).put("internalWorkWithinBudget", false);
            ((ObjectNode) recorded.get("row")).put("status", "NATIVE_BUDGET_REJECTED");
        }, ignored -> { });
    }

    @Test
    void acceptsTheRecordedRejectionOfAForeignSourceEcho() throws Exception {
        assertEquals(53, auditRawMutation(EnumoRecordedAuditTest::rejectForeignSource, ignored -> { }));
    }

    @Test
    void sourceRejectionPrecedesAnActualBudgetRejection() throws Exception {
        assertEquals(53, auditRawMutation(recorded -> {
            rejectForeignSource(recorded);
            ObjectNode response = (ObjectNode) recorded.get("response");
            long extra = 16385 - response.path("totalWork").longValue();
            response.put("selectionWork", response.path("selectionWork").longValue() + extra);
            response.put("totalWork", 16385).put("internalWorkWithinBudget", false);
        }, ignored -> { }));
    }

    private static void rejectForeignSource(ObjectNode recorded) {
        ((ObjectNode) recorded.get("response")).put("input", "x1");
        ObjectNode row = (ObjectNode) recorded.get("row");
        discardCandidate(row);
        row.put("status", "SOURCE_BINDING_REJECTED");
    }

    private static void discardCandidate(ObjectNode row) {
        for (String field : java.util.List.of("output", "inputCost", "outputCost", "nativeWork")) row.remove(field);
    }

    private static void assertMissingRawRejected(java.util.function.Consumer<ObjectNode> changeRow) throws Exception {
        ObjectNode report = report();
        String[] lines = new String(raw(), StandardCharsets.UTF_8).split("\n");
        JsonNode removed = JSON.readTree(lines[0]).get("row");
        for (JsonNode row : report.path("rows")) if (sameKey(row, removed)) changeRow.accept((ObjectNode) row);
        byte[] changed = (String.join("\n", java.util.Arrays.copyOfRange(lines, 1, lines.length)) + "\n")
            .getBytes(StandardCharsets.UTF_8);
        report.put("rawResponsesSha256", hash(changed));
        assertThrows(IllegalArgumentException.class, () -> EnumoRecordedAudit.verify(ROOT, report, changed, rules()));
    }

    private static void assertRawMutationRejected(java.util.function.Consumer<ObjectNode> changeRaw,
            java.util.function.Consumer<ObjectNode> changeReport) throws Exception {
        assertThrows(IllegalArgumentException.class, () -> auditRawMutation(changeRaw, changeReport));
    }

    private static int auditRawMutation(java.util.function.Consumer<ObjectNode> changeRaw,
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
        return EnumoRecordedAudit.verify(ROOT, report, forged, rules());
    }

    private static boolean sameKey(JsonNode a, JsonNode b) {
        return a.path("profile").equals(b.path("profile")) && a.path("repetition").equals(b.path("repetition"))
            && a.path("ordinal").equals(b.path("ordinal"));
    }
    private static ObjectNode report() throws Exception { return (ObjectNode) JSON.readTree(gunzip("results.json.gz")); }
    private static byte[] raw() throws Exception { return gunzip("responses.jsonl.gz"); }
    private static byte[] rules() throws Exception { return Files.readAllBytes(DATA.resolve("learned.rules")); }
    private static byte[] gunzip(String name) throws Exception {
        return gunzip(DATA.resolve(name));
    }
    private static byte[] gunzip(Path file) throws Exception {
        try (var input = new GZIPInputStream(Files.newInputStream(file))) { return input.readAllBytes(); }
    }
    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
