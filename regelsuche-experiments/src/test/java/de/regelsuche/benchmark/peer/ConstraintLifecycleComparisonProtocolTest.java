package de.regelsuche.benchmark.peer;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConstraintLifecycleComparisonProtocolTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String REVISION = "0123456789abcdef0123456789abcdef01234567";
    private static final Set<String> ARMS = Set.of(
        "FIXED_MIN_FILL", "FIXED_MIN_DEGREE", "INDEXED_MIN_FILL", "COSTED_LEARNED", "FRESH_SEARCH");
    @TempDir Path directory;

    @Test
    void lifecycleReportPreservesIndependentTruthFreezeAndPaidAcquisition() throws Exception {
        JsonNode report = run(protocol(200_000));
        assertEquals(REVISION, report.required("sourceRevision").asText());
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(Files.readAllBytes(directory.resolve("protocol.json")))),
            report.required("protocolSha256").asText());
        assertEquals(report.required("modelSha256BeforeValidation"), report.required("modelSha256AfterFinal"));
        assertEquals(1, report.required("training").size());
        long trainingWork = report.required("trainingWork").asLong();
        assertTrue(trainingWork > 0);

        var queryArms = new HashMap<String, Set<String>>();
        var measuredWork = new HashMap<String, Long>();
        assertEquals(15, report.required("rows").size());
        for (JsonNode row : report.required("rows")) {
            String split = row.required("split").asText(), arm = row.required("arm").asText();
            assertTrue(ARMS.contains(arm));
            String expected = split.equals("validation") ? "4" : "9";
            // A triangle permits empty/singleton sets; a 4-vertex star permits 8 leaf sets or its center.
            assertEquals(split.equals("validation") ? "complete:3:0" : "star:4:0", row.required("case").asText());
            assertEquals(expected, row.required("expectedCount").asText());
            assertEquals(expected, row.required("count").asText());
            assertTrue(row.required("independentCountMatches").asBoolean());
            String query = split + ":" + row.required("round").asInt();
            assertTrue(queryArms.computeIfAbsent(query, ignored -> new HashSet<>()).add(arm));
            long work = row.required("work").asLong();
            assertTrue(work > 0 && work <= 200_000);
            measuredWork.merge(split + ":" + arm, work, Long::sum);
        }
        assertEquals(Set.of("validation:0", "final:0", "final:1"), queryArms.keySet());
        queryArms.values().forEach(arms -> assertEquals(ARMS, arms));
        assertEquals(ARMS, report.required("totals").properties().stream()
            .map(Map.Entry::getKey).collect(java.util.stream.Collectors.toSet()));
        for (String arm : ARMS) {
            JsonNode total = report.required("totals").required(arm);
            long validation = measuredWork.get("validation:" + arm), finals = measuredWork.get("final:" + arm);
            assertEquals(validation, total.required("validationWork").asLong());
            assertEquals(finals, total.required("finalWork").asLong());
            assertEquals(validation + finals + (arm.equals("COSTED_LEARNED") ? trainingWork : 0),
                total.required("lifecycleWork").asLong());
            assertEquals(3, total.required("successfulQueries").asInt());
        }
    }

    @Test
    void exhaustedQueriesRemainVisibleWithoutExceedingTheirBudget() throws Exception {
        JsonNode report = run(protocol(1));
        assertEquals(15, report.required("rows").size());
        for (JsonNode row : report.required("rows")) {
            assertEquals("UNAVAILABLE", row.required("count").asText());
            assertFalse(row.required("independentCountMatches").asBoolean());
            assertEquals(row.required("split").asText().equals("validation") ? "4" : "9",
                row.required("expectedCount").asText());
            assertTrue(row.required("work").asLong() >= 0 && row.required("work").asLong() <= 1);
            assertFalse(row.required("reason").asText().isBlank());
        }
        for (String arm : ARMS) assertEquals(0, report.required("totals").required(arm)
            .required("successfulQueries").asInt());
    }

    @Test
    void rejectsFamilyLeakageBeforePublishingAReport() throws Exception {
        ObjectNode protocol = protocol(200_000);
        ((ObjectNode) protocol.required("validation").get(0)).put("family", "path");
        assertThrows(IllegalArgumentException.class, () -> run(protocol));
        assertFalse(Files.exists(directory.resolve("nested/result.json")));
    }

    private JsonNode run(ObjectNode protocol) throws Exception {
        Path input = directory.resolve("protocol.json"), output = directory.resolve("nested/result.json");
        JSON.writeValue(input.toFile(), protocol);
        ConstraintLifecycleComparison.main(new String[]{input.toString(), output.toString(), REVISION});
        return JSON.readTree(output.toFile());
    }

    private static ObjectNode protocol(long queryWork) throws Exception {
        ObjectNode protocol = (ObjectNode) JSON.readTree("""
            {
              "schema": "regelsuche.constraint-lifecycle-protocol/v1",
              "trainingMaximumWork": 2000000,
              "perTrainingTrialWork": 10000,
              "maximumStates": 20000,
              "timeoutMillis": 30000,
              "maximumProbeWork": 10000,
              "finalRounds": 2,
              "training": [{"family":"path","a":3,"b":0}],
              "validation": [{"family":"complete","a":3,"b":0}],
              "final": [{"family":"star","a":4,"b":0}]
            }
            """);
        return protocol.put("queryMaximumWork", queryWork);
    }
}
