package de.regelsuche.benchmarks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.egraph.proof.ExtractionCertificateCodec;
import de.regelsuche.egraph.proof.ExtractionProofChecker;
import de.regelsuche.egraph.proof.ProofLimits;
import de.regelsuche.json.JsonReader;
import de.regelsuche.knowledge.RuleInventoryFingerprint;
import de.regelsuche.moves.enumerate.TreePosition;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.RecordedExecution;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SharedFragmentRewriteBenchmarkTest {
    private static final String MODE = "--shared-rewrite-fragment";
    private static final String PATH = "best-first";
    private static final String EQSAT = "equality-saturation";

    @Test
    void publishesOneDeterministicReportThroughTheExistingCli(@TempDir Path directory) throws Exception {
        Path output = directory.resolve("shared-fragment.json");
        Path neighbor = Files.writeString(directory.resolve("report.json"), "retained comparison");
        ComparativeBenchmarkMain.main(new String[] {MODE, output.toString()});
        String first = Files.readString(output);
        ComparativeBenchmarkMain.main(new String[] {MODE, output.toString()});

        assertEquals(first, Files.readString(output));
        assertEquals("retained comparison", Files.readString(neighbor));
        assertThrows(IllegalArgumentException.class,
            () -> ComparativeBenchmarkMain.main(new String[] {MODE}));
        var report = new JsonReader(first).readObject();
        assertEquals("regelsuche.shared-fragment-rewrite-benchmark/v1", report.get("schema"));
        assertEquals(false, report.get("targetVisible"));
        var cases = list(report.get("cases"));
        assertEquals(9, cases.size());
        for (var benchmarkCase : cases) {
            assertEquals(List.of(PATH, EQSAT),
                list(benchmarkCase.get("outcomes")).stream().map(row -> row.get("system")).toList());
        }
    }

    @Test
    void retainsExecutableRewritePathsAndDetachedEqSatProofs(@TempDir Path directory) throws Exception {
        var report = run(directory);
        var admittedIds = (List<?>) report.get("ruleIds");
        var rules = AstRewriteTransformationEngine.allBuiltInRules().stream()
            .filter(rule -> admittedIds.contains(rule.id())).toList();
        assertEquals(RuleInventoryFingerprint.contentHash(rules), report.get("inventoryHash"));
        var parser = new ExpressionParser();
        var limits = ProofLimits.defaults();
        var row = namedCase(report, "neutral-composition");
        assertEquals(1, row.get("referenceOperatorCount"));
        var outcomes = list(row.get("outcomes"));
        var path = outcomes.getFirst();
        for (var outcome : outcomes) {
            assertEquals("REFERENCE_COST_REACHED", outcome.get("verdict"));
            assertEquals("EXACT_AST", outcome.get("translation"));
            assertEquals(1, outcome.get("operatorCount"));
            assertEquals(List.of(), outcome.get("emittedAssumptions"));
        }
        var replay = RecordedExecution.fromCanonicalJson((String) path.get("execution"));
        replay.requireEndpoints((String) row.get("source"), (String) path.get("output"));
        assertEquals(3, replay.edgeCount());
        var steps = list(new JsonReader(replay.provenance()).readObject().get("steps"));
        var paths = (List<?>) path.get("occurrencePaths");
        assertEquals(replay.edgeCount(), paths.size());
        var current = parser.parseTerm(replay.sourceExpression());
        for (int index = 0; index < steps.size(); index++) {
            var step = steps.get(index);
            var position = new TreePosition(
                ((List<?>) paths.get(index)).stream().map(Integer.class::cast).toList(), "");
            var rule = rules.stream().filter(candidate -> candidate.id().equals(step.get("rule")))
                .findFirst().orElseThrow();
            var source = position.subtreeAt(current).orElseThrow();
            assertTrue(rule.matches(source));
            var rewritten = position.replaceAt(current, rule.apply(source)).rewrittenRoot().orElseThrow();
            assertEquals(rewritten, parser.parseTerm((String) step.get("output")));
            current = rewritten;
        }
        assertEquals("PRIMITIVE_REPLAYED", path.get("proofStatus"));
        assertEquals(false, path.get("proofObjectAvailable"));

        var admitted = list(report.get("cases")).stream()
            .filter(candidate -> candidate.get("referenceOperatorCount") != null).toList();
        assertEquals(6, admitted.size());
        for (var candidate : admitted) {
            var outcome = list(candidate.get("outcomes")).getLast();
            byte[] encoded = Base64.getDecoder().decode((String) outcome.get("certificateBase64"));
            String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encoded));
            assertEquals("sha256:" + sha, outcome.get("certificateHash"));
            var proof = ExtractionCertificateCodec.decode(encoded, limits);
            var input = parser.parseTerm((String) candidate.get("source"));
            var output = parser.parseTerm((String) outcome.get("output"));
            assertEquals("DETACHED_CERTIFICATE_CHECKED", outcome.get("proofStatus"));
            assertEquals(true, outcome.get("proofObjectAvailable"));
            assertEquals(ExtractionProofChecker.Status.ACCEPTED,
                ExtractionProofChecker.check(input, output, proof, rules, limits).status());
            assertEquals(ExtractionProofChecker.Status.REJECTED,
                ExtractionProofChecker.check(input, parser.parseTerm("unrelated"), proof, rules, limits).status());
        }
    }

    @Test
    void retainsAValidEqSatOutputThatMissesTheReferenceCost(@TempDir Path directory) throws Exception {
        var outcomes = list(namedCase(run(directory), "annihilation").get("outcomes"));
        assertEquals("REFERENCE_COST_REACHED", outcomes.getFirst().get("verdict"));
        var eqsat = outcomes.getLast();
        assertEquals("REFERENCE_COST_MISSED", eqsat.get("verdict"));
        assertEquals("DETACHED_CERTIFICATE_CHECKED", eqsat.get("proofStatus"));
        assertEquals(true, eqsat.get("exactPolynomialEquality"));
    }

    @Test
    void neverQualifiesAnUnprovedFallbackAtTheReferenceCost() {
        var tight = new ProofLimits(0, 0, 0, 0, 0, 0, 0, 0);
        var report = new JsonReader(new SharedFragmentRewriteBenchmark(tight).report()).readObject();
        var outcome = list(namedCase(report, "right-nested").get("outcomes")).getLast();
        assertEquals(2, outcome.get("operatorCount"));
        assertEquals(true, outcome.get("exactPolynomialEquality"));
        assertEquals("PROOF_UNAVAILABLE", outcome.get("verdict"));
        assertEquals("LIMIT_EXCEEDED", outcome.get("producerProofStatus"));
        assertEquals(false, outcome.get("proofObjectAvailable"));
        assertFalse(outcome.containsKey("certificateBase64"));
    }

    @Test
    void retainsUnsupportedCasesAndTheirAssumptionsForBothSystems(@TempDir Path directory) throws Exception {
        var report = run(directory);
        var division = namedCase(report, "guarded-division");
        assertEquals(List.of("x != 0"), division.get("sourceAssumptions"));
        var reasons = Map.of("guarded-division", "DIVISION_OUTSIDE_FRAGMENT",
            "symbolic-power", "POWER_OUTSIDE_FRAGMENT", "unregistered-function", "FUNCTION_OUTSIDE_FRAGMENT");
        for (var entry : reasons.entrySet()) {
            for (var outcome : list(namedCase(report, entry.getKey()).get("outcomes"))) {
                assertEquals("UNSUPPORTED", outcome.get("verdict"));
                assertEquals(entry.getValue(), outcome.get("translation"));
                assertEquals("NOT_REQUESTED", outcome.get("proofStatus"));
                assertEquals(false, outcome.get("proofObjectAvailable"));
                assertFalse(outcome.containsKey("certificateBase64"));
                assertFalse(outcome.containsKey("execution"));
            }
        }
    }

    private static Map<String, Object> run(Path directory) throws Exception {
        Path output = directory.resolve("result.json");
        ComparativeBenchmarkMain.main(new String[] {MODE, output.toString()});
        return new JsonReader(Files.readString(output)).readObject();
    }

    private static Map<String, Object> namedCase(Map<String, Object> report, String id) {
        return list(report.get("cases")).stream().filter(row -> id.equals(row.get("id")))
            .findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Object value) {
        return (List<Map<String, Object>>) value;
    }
}
