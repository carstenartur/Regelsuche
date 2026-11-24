package de.regelsuche.benchmark;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.math.algorithms.iteration.OrderedReductionSearch;
import java.math.BigInteger;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

class ExternalPolyhedralComparisonTest {
    static String prefix(String name, String term) {
        return "affine " + name + " [N]->{:0<N}\ninputs X : {[i] : 0<=i<=N}\n"
            + "outputs Y : {[i] : 0<=i<=N}\nlet Y[i] = reduce(+, [j], {:0<=j<=i} : " + term + ");\n.";
    }

    @Test
    void importsTheEntireForeignPrefixSourceWithoutUsingItsFilenameOrAnswer() {
        var imported = assertDoesNotThrow(() -> ExternalPolyhedralComparison.parse(prefix("unseen_name", "X[j]")));
        var limits = new IterationLimits(100_000, 128, 5_000);
        var found = OrderedReductionSearch.search(imported.source(), limits, 4, () -> false).verified().orElseThrow();
        assertEquals(List.of(BigInteger.valueOf(2), BigInteger.valueOf(-1), BigInteger.valueOf(3)),
            found.evaluatePrefixes(List.of(List.of(BigInteger.TWO), List.of(BigInteger.valueOf(-3)),
                List.of(BigInteger.valueOf(4))), limits, () -> false).sums());
    }

    @Test
    void rejectsChangedOutputDomainsAndDivisionInsteadOfDroppingTheirSemantics() {
        assertThrows(IllegalArgumentException.class, () -> ExternalPolyhedralComparison.parse(
            prefix("changed", "X[j]").replace("outputs Y : {[i] : 0<=i<=N}", "outputs Y : {[i] : 1<=i<=N}")));
        assertThrows(IllegalArgumentException.class,
            () -> ExternalPolyhedralComparison.parse(prefix("division", "1/X[j]")));
        assertThrows(IllegalArgumentException.class,
            () -> ExternalPolyhedralComparison.parse(prefix("wrong_access", "X[i]")));
    }

    @Test
    void rejectsUndeclaredNamesBeforeIntroducingInternalPlaceholders() {
        assertAll(
            () -> assertThrows(IllegalArgumentException.class,
                () -> ExternalPolyhedralComparison.parse(prefix("bare_internal", "x0"))),
            () -> assertThrows(IllegalArgumentException.class,
                () -> ExternalPolyhedralComparison.parse(prefix("mixed_internal", "X[j]+x0"))));
        var renamedIndex = prefix("declared_index", "X[j]").replace("[j]", "[x0]").replace("<=j<=", "<=x0<=");
        assertEquals(List.of(BigInteger.valueOf(-9), BigInteger.valueOf(-1), BigInteger.valueOf(5)),
            ExternalPolyhedralComparison.oracle(ExternalPolyhedralComparison.parse(renamedIndex), 3, 0));
    }

    @Test
    void rejectsAnAlphaPrefixThatLeavesItsFirstOutputUndefined() {
        var input = prefix("pair_empty", "X[j]*X[k]").replace("[j], {:0<=j<=i}", "[j,k], {:0<=j<k<=i}");
        assertThrows(IllegalArgumentException.class, () -> ExternalPolyhedralComparison.parse(input),
            "The native mathematical zero for an empty sum must not silently complete an undefined Alpha output.");
    }

    @Test
    void independentlyEnumeratesSourcePolynomialsAndBoundsIntermediateArithmetic() {
        var imported = ExternalPolyhedralComparison.parse(prefix("polynomial", "X[j]*X[j]+3*X[j]"));
        assertEquals(List.of(BigInteger.valueOf(54), BigInteger.valueOf(142), BigInteger.valueOf(196)),
            ExternalPolyhedralComparison.oracle(imported, 3, 0));
        assertThrows(IllegalArgumentException.class, () -> ExternalPolyhedralComparison.oracle(imported, 0, 0));
        assertThrows(IllegalArgumentException.class,
            () -> ExternalPolyhedralComparison.parse(prefix("overflow", "4611686018427387904*X[j]")));
    }

    @Test
    void frozenPanelIncludesEveryExternalFileAndSeparateNegativeControls() throws Exception {
        var panel = ExternalPolyhedralComparison.protocol(checkout());
        int external = 0, admitted = 0, unsupported = 0;
        for (var entry : panel.path("cases")) {
            if (entry.path("origin").asText().equals("UPSTREAM_COMPLETE_INVENTORY")) external++;
            assertEquals(entry.path("sha256").asText(), ExternalPolyhedralComparison.hash(
                entry.path("source").asText().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            try { ExternalPolyhedralComparison.parse(entry.path("source").asText()); admitted++; }
            catch (IllegalArgumentException expected) { unsupported++; }
        }
        assertEquals(7, external);
        assertEquals(2, admitted);
        assertEquals(8, unsupported);
    }

    @Test
    void cannotQualifyALateSuccessWhenTheOperatingSystemDelaysTimeoutDelivery() {
        assertTrue(new ExternalPolyhedralComparison.Execution(0, false, 31_000_000_000L).exceeded(30));
        assertTrue(new ExternalPolyhedralComparison.Execution(124, true, 29_000_000_000L).exceeded(30));
        assertFalse(new ExternalPolyhedralComparison.Execution(0, false, 29_000_000_000L).exceeded(30));
    }

    @Test
    @EnabledIfSystemProperty(named = "externalPolyhedralHome", matches = ".+")
    void executesThePinnedOriginalAndRetainsEveryOutcome(@TempDir Path damaged) throws Exception {
        var output = Path.of(System.getProperty("externalPolyhedralOutput"));
        var report = ExternalPolyhedralComparison.run(checkout(), Path.of(System.getProperty("externalPolyhedralHome")), output);
        @SuppressWarnings("unchecked") var rows = (List<Map<String, Object>>) report.get("rows");
        assertEquals(20, rows.size());
        assertTrue(rows.stream().noneMatch(row -> "INVALID_OUTPUT".equals(row.get("judgment"))),
            "The report retains the invalid candidate; it must not be counted as verified.");
        assertEquals(2, rows.stream().filter(row -> "prefix_sum".equals(row.get("case"))
            && "VERIFIED_FINITE_OUTPUTS".equals(row.get("judgment"))).count());
        try (var paths = Files.walk(output)) {
            for (var path : paths.toList()) {
                Path target = damaged.resolve(output.relativize(path));
                if (Files.isDirectory(path)) Files.createDirectories(target); else Files.copy(path, target);
            }
        }
        Path original = damaged.resolve("prefix_sum/input.alpha");
        var originalBytes = Files.readAllBytes(original);
        Files.writeString(original, "changed source");
        assertThrows(IllegalArgumentException.class, () -> ExternalPolyhedralComparison.audit(checkout(), damaged));
        Files.write(original, originalBytes);
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var altered = json.readTree(damaged.resolve("results.json").toFile());
        for (var row : altered.path("rows")) if (row.path("case").asText().equals("prefix_sum")
                && row.path("arm").asText().equals("REGELSUCHE_ORDERED_PREFIX")) {
            ((com.fasterxml.jackson.databind.node.ArrayNode) row.path("response").path("outputs").get(0).path("values"))
                .set(0, json.getNodeFactory().textNode("999"));
        }
        json.writeValue(damaged.resolve("results.json").toFile(), altered);
        var manifest = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(damaged.resolve("manifest.json").toFile());
        manifest.put("results.json", ExternalPolyhedralComparison.hash(Files.readAllBytes(damaged.resolve("results.json"))));
        json.writeValue(damaged.resolve("manifest.json").toFile(), manifest);
        assertThrows(IllegalArgumentException.class, () -> ExternalPolyhedralComparison.audit(checkout(), damaged));
    }

    @Test
    @EnabledIfSystemProperty(named = "externalPolyhedralAudit", matches = ".+")
    void replaysRetainedSourcesAndOutputsWithoutTheOriginalCompiler() throws Exception {
        ExternalPolyhedralComparison.audit(checkout(), Path.of(System.getProperty("externalPolyhedralAudit")));
    }

    @Test
    @EnabledIfSystemProperty(named = "externalPolyhedralAudit", matches = ".+")
    void rejectsChangedOriginalStdoutEvenWithItsManifestHashUpdated(@TempDir Path damaged) throws Exception {
        copyRetainedEvidence(damaged);
        Path stdout = damaged.resolve("prefix_sum/run-2-0.stdout");
        assertEquals("-9\n-1\n", Files.readString(stdout));
        Files.writeString(stdout, "999\n-1\n");
        updateManifestHash(damaged, "prefix_sum/run-2-0.stdout");
        assertThrows(IllegalArgumentException.class, () -> ExternalPolyhedralComparison.audit(checkout(), damaged));
    }

    @Test
    @EnabledIfSystemProperty(named = "externalPolyhedralAudit", matches = ".+")
    void rejectsChangedNativeResponseEvenWithItsManifestHashUpdated(@TempDir Path damaged) throws Exception {
        copyRetainedEvidence(damaged);
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        Path response = damaged.resolve("prefix_sum/native.json");
        var altered = json.readTree(response.toFile());
        ((com.fasterxml.jackson.databind.node.ArrayNode) altered.path("outputs").get(0).path("values"))
            .set(0, json.getNodeFactory().textNode("999"));
        json.writeValue(response.toFile(), altered);
        updateManifestHash(damaged, "prefix_sum/native.json");
        assertThrows(IllegalArgumentException.class, () -> ExternalPolyhedralComparison.audit(checkout(), damaged));
    }

    @Test
    @EnabledIfSystemProperty(named = "externalPolyhedralAudit", matches = ".+")
    void requiresManifestEntriesForTheRetainedProcessOutputs(@TempDir Path damaged) throws Exception {
        copyRetainedEvidence(damaged);
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        Path path = damaged.resolve("manifest.json");
        byte[] original = Files.readAllBytes(path);
        for (String required : List.of("prefix_sum/run-2-0.stdout", "prefix_sum/native.json")) {
            var manifest = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(original);
            assertNotNull(manifest.remove(required));
            json.writeValue(path.toFile(), manifest);
            assertThrows(IllegalArgumentException.class, () -> ExternalPolyhedralComparison.audit(checkout(), damaged), required);
        }
    }

    private static void copyRetainedEvidence(Path target) throws Exception {
        Path original = Path.of(System.getProperty("externalPolyhedralAudit"));
        ExternalPolyhedralComparison.audit(checkout(), original);
        try (var paths = Files.walk(original)) {
            for (var path : paths.toList()) {
                Path copy = target.resolve(original.relativize(path));
                if (Files.isDirectory(path)) Files.createDirectories(copy); else Files.copy(path, copy);
            }
        }
    }

    private static void updateManifestHash(Path output, String changed) throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var manifest = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(output.resolve("manifest.json").toFile());
        manifest.put(changed, ExternalPolyhedralComparison.hash(Files.readAllBytes(output.resolve(changed))));
        json.writeValue(output.resolve("manifest.json").toFile(), manifest);
    }

    private static Path checkout() { return Path.of(System.getProperty("regelsuche.repositoryRoot", "..")); }
}
