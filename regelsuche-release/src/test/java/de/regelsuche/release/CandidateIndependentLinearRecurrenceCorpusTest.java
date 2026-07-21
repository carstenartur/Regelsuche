package de.regelsuche.release;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.regelsuche.discovery.domain.DiscoveryDomain.DiscoveryBudget;
import de.regelsuche.discovery.domain.DiscoveryDomain.DiscoverySeed;
import de.regelsuche.discovery.domain.DomainDiscoveryEvidence.Outcome;
import de.regelsuche.discovery.domain.DomainDiscoveryRunner;
import de.regelsuche.discovery.domain.LinearRecurrenceSequenceDomain;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Characterizes the new production adapter against the already frozen corpus. */
class CandidateIndependentLinearRecurrenceCorpusTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CHALLENGE = "finite-difference-recurrences";

    @Test
    void frozenCorpusHasFourConfirmedOneRefutedAndOneUnsupportedByThisForm()
            throws Exception {
        Map<String, Outcome> actual = executeFrozenCases();

        assertEquals(Map.of(
            "case-07", Outcome.INCONCLUSIVE,
            "case-08", Outcome.CONFIRMED,
            "case-09", Outcome.CONFIRMED,
            "case-10", Outcome.CONFIRMED,
            "case-11", Outcome.REFUTED,
            "case-12", Outcome.CONFIRMED), actual);
        assertEquals(4, actual.values().stream()
            .filter(Outcome.CONFIRMED::equals).count());
        assertEquals(1, actual.values().stream()
            .filter(Outcome.REFUTED::equals).count());
        assertEquals(1, actual.values().stream()
            .filter(Outcome.INCONCLUSIVE::equals).count());
    }

    @Test
    void evaluationIsByteDeterministicAndDoesNotAuthorizeBroaderClaims()
            throws Exception {
        byte[] first = canonicalEvidenceBundle();
        byte[] second = canonicalEvidenceBundle();

        assertArrayEquals(first, second);
        String evidence = new String(first, StandardCharsets.UTF_8);
        assertTrue(evidence.contains("LINEAR_RECURRENCE_FINITE_DATA_VALIDATION_NOT_FORMAL_PROOF"));
        assertTrue(evidence.contains("\"outcome\":\"CONFIRMED\""));
        assertTrue(evidence.contains("\"outcome\":\"REFUTED\""));
        assertFalse(evidence.contains("FORMALLY_PROVED"));
        assertFalse(evidence.contains("EXTERNALLY_NOVEL"));
    }

    private static Map<String, Outcome> executeFrozenCases() throws Exception {
        LinkedHashMap<String, Outcome> outcomes = new LinkedHashMap<>();
        for (JsonNode item : frozenCases()) {
            outcomes.put(item.path("caseId").asText(), run(item).evidence().outcome());
        }
        return Map.copyOf(outcomes);
    }

    private static byte[] canonicalEvidenceBundle() throws Exception {
        var bundle = JSON.createArrayNode();
        for (JsonNode item : frozenCases()) {
            JsonNode evidence = JSON.readTree(run(item).evidence().toCanonicalJson());
            bundle.add(evidence);
        }
        return JSON.writeValueAsBytes(bundle);
    }

    private static DomainDiscoveryRunner.RunResult<?, ?> run(JsonNode item) {
        JsonNode evaluation = item.path("evaluationInput");
        List<Long> observed = longs(evaluation.path("observedPrefix"));
        List<Long> holdout = longs(evaluation.path("holdoutContinuation"));
        int maximumOrder = evaluation.path("maximumOrder").asInt();
        var domain = new LinearRecurrenceSequenceDomain();
        var seed = DiscoverySeed.create(
            item.path("caseId").asText() + "-linear-recurrence",
            domain.domainId(),
            "observed=" + csv(observed)
                + ";holdout=" + csv(holdout)
                + ";maximumOrder=" + maximumOrder,
            "candidate-independent-frozen-evaluation/"
                + item.path("caseId").asText());
        var budget = new DiscoveryBudget(
            maximumOrder,
            Math.max(16, maximumOrder + 2),
            Math.max(16, maximumOrder + 2),
            4,
            Math.max(8, maximumOrder + 1),
            Math.max(16, observed.size()));
        var result = new DomainDiscoveryRunner().run(
            "linear-recurrence-" + item.path("caseId").asText(),
            domain,
            seed,
            budget);
        result.evidence().resources().forEach(line -> assertEquals(
            line.configured(),
            line.executed() + line.skipped() + line.remaining(),
            line.resource().name()));
        return result;
    }

    private static List<JsonNode> frozenCases() throws Exception {
        JsonNode corpus = JSON.readTree(Files.readString(
            repositoryRoot().resolve(
                "research/benchmarks/candidate-independent/case-corpus.json"),
            StandardCharsets.UTF_8));
        List<JsonNode> result = new ArrayList<>();
        for (JsonNode item : corpus.path("cases")) {
            if (CHALLENGE.equals(item.path("challengeId").asText())) {
                result.add(item);
            }
        }
        assertEquals(6, result.size());
        assertEquals(List.of(
            "case-07", "case-08", "case-09", "case-10", "case-11", "case-12"),
            result.stream().map(item -> item.path("caseId").asText()).toList());
        return List.copyOf(result);
    }

    private static List<Long> longs(JsonNode array) {
        List<Long> result = new ArrayList<>();
        array.forEach(item -> result.add(item.longValue()));
        return List.copyOf(result);
    }

    private static String csv(List<Long> values) {
        return values.stream().map(String::valueOf)
            .collect(java.util.stream.Collectors.joining(","));
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        if (Files.isRegularFile(current.resolve("settings.gradle"))) {
            return current;
        }
        Path parent = current.getParent();
        if (parent != null && Files.isRegularFile(parent.resolve("settings.gradle"))) {
            return parent;
        }
        throw new IllegalStateException("Could not locate repository root from " + current);
    }
}
