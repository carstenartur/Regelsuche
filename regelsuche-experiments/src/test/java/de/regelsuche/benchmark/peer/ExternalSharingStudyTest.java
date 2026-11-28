package de.regelsuche.benchmark.peer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.discovery.representation.ExactSharingArithmetic;
import de.regelsuche.discovery.representation.ExactSharingRepresentation;
import de.regelsuche.discovery.representation.ExpressionOccurrencePath;
import de.regelsuche.discovery.representation.RepeatedStructureExtractor;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.scalar.ExactRational;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Executes the external-sharing-v1 protocol. No labels or measured costs enter selection. */
class ExternalSharingStudyTest {
    static final String REVISION = "090d81982edc0d2a597f4c15644253d4f266a6ef";
    static final SortedMap<String, String> BLOBS = new TreeMap<>(Map.of(
        "apron", "d38996006867dfa306c4b95495a0511689e1bfaa",
        "daisy", "5bd54bc41f40b4d8f6cbe4c0b8c102acda092178",
        "fptaylor-real2float", "28c6a29ae4007fe9125dbf9f5afbb1a9b15daab9",
        "fptaylor-tests", "c03af2e2126c201f2489934427fa5bf4ba7c52e4",
        "graphics", "b74cc842d8a249d461db4a88a820ff6d52d1a5fd",
        "herbie", "dd53eb7ece1bb0d9d29579479e311a6dbebc8e93",
        "ml-frameworks", "652b37b0f99f5cebfb8bd173da5922912a5345df",
        "precimonious", "b75856de73587230ac70d3fdba847b234ae5bbce",
        "rump", "626048aa4e4f466a26b206ae111d8c4240ebff00",
        "salsa", "43dcab5c9aef19f707ba85055df0f422c4a9b347"));

    record Counts(int tree, int sharing, int cse, int references, int cseCacheHits) { }
    record Choice(String identity, String normalizedSource, String definition, List<String> paths) { }
    record Rejection(String candidate, String reason) { }
    record Row(int ordinal, String name, String status, String reason, Choice selected, List<Rejection> rejected,
               List<String> outputs, Counts perSuccessfulSubstitution, boolean material) { }
    record FileResult(String file, String blob, String readerFailure, List<Row> rows, Totals totals) { }
    record Totals(int programs, int admitted, int material, long tree, long sharing, long cse) {
        static Totals of(List<Row> rows) {
            int admitted = 0, material = 0;
            long tree = 0, sharing = 0, cse = 0;
            for (var row : rows) {
                if (!row.status().equals("SUPPORTED")) continue;
                admitted++;
                if (row.material()) material++;
                if (row.perSuccessfulSubstitution() != null) {
                    tree += row.perSuccessfulSubstitution().tree();
                    sharing += row.perSuccessfulSubstitution().sharing();
                    cse += row.perSuccessfulSubstitution().cse();
                }
            }
            return new Totals(rows.size(), admitted, material, tree, sharing, cse);
        }
    }

    @Test
    void testsAllThreeArmsWithoutReadingExternalFiles() {
        String shared = "((x - 1) * (x - 1) + 3)";
        var row = admitted(1, "control", shared + " * " + shared);
        assertTrue(row.material(), row.toString());
        assertNotNull(row.selected());
        assertEquals(4, row.outputs().size());
        assertEquals(2, row.perSuccessfulSubstitution().references());
        assertTrue(row.perSuccessfulSubstitution().cse() < row.perSuccessfulSubstitution().sharing());
        var plain = admitted(2, "plain", "x + 1");
        assertNull(plain.selected());
        assertEquals(new Counts(1, 1, 1, 0, 0), plain.perSuccessfulSubstitution());
    }

    @Test
    @EnabledIfSystemProperty(named = "sharing.inputs", matches = ".+")
    void executePinnedCorpus() throws Exception {
        var directory = Path.of(System.getProperty("sharing.inputs"));
        var files = new ArrayList<FileResult>();
        var allRows = new ArrayList<Row>();
        for (var item : BLOBS.entrySet()) {
            String file = item.getKey() + ".fpcore";
            byte[] bytes = Files.readAllBytes(directory.resolve(file));
            var sha1 = MessageDigest.getInstance("SHA-1");
            sha1.update(("blob " + bytes.length + "\0").getBytes(StandardCharsets.US_ASCII));
            assertEquals(item.getValue(), HexFormat.of().formatHex(sha1.digest(bytes)), file);
            List<FpcorePolynomialCorpus.Entry> entries;
            try {
                entries = FpcorePolynomialCorpus.read(new String(bytes, StandardCharsets.UTF_8));
            } catch (IllegalArgumentException | ArithmeticException error) {
                files.add(new FileResult(file, item.getValue(), error.toString(), List.of(), Totals.of(List.of())));
                continue;
            }
            var rows = new ArrayList<Row>();
            for (var entry : entries) {
                rows.add(entry.status() == FpcorePolynomialCorpus.Status.SUPPORTED
                    ? admitted(entry.ordinal(), entry.name(), entry.source())
                    : new Row(entry.ordinal(), entry.name(), "UNSUPPORTED", entry.detail(), null, List.of(),
                        List.of(), null, false));
            }
            allRows.addAll(rows);
            files.add(new FileResult(file, item.getValue(), null, rows, Totals.of(rows)));
        }
        var json = JsonMapper.builder().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
        // One row per line keeps the checked-in ledger compact and independently diffable.
        var lines = new ArrayList<String>();
        lines.add(json.writeValueAsString(Map.of("protocol", "external-sharing-v1", "revision", REVISION)));
        for (var result : files) {
            lines.add(json.writeValueAsString(new FileResult(result.file(), result.blob(), result.readerFailure(),
                List.of(), result.totals())));
            for (var row : result.rows()) lines.add(json.writeValueAsString(Map.of("file", result.file(), "row", row)));
        }
        lines.add(json.writeValueAsString(Map.of("overall", Totals.of(allRows))));
        Files.writeString(Path.of(System.getProperty("sharing.evidence")), String.join("\n", lines) + "\n");
    }

    private static Row admitted(int ordinal, String name, String source) {
        ExactSharingRepresentation overlay = null;
        var rejected = new ArrayList<Rejection>();
        for (var candidate : new RepeatedStructureExtractor().extractMaterial(source)) {
            try {
                overlay = ExactSharingRepresentation.create(candidate);
                break;
            } catch (IllegalArgumentException error) {
                rejected.add(new Rejection(candidate.identity(), error.getMessage()));
            }
        }
        var parser = new ExpressionParser();
        var root = parser.parseExactTerm(source).expression();
        Choice choice = null;
        String reason = null;
        if (overlay != null && !root.equals(parser.parseTerm(overlay.reconstructSourceExpression()))) {
            // The historical presentation formatter can flatten an associative child.
            // Preserve the original program and record the failed reconstruction.
            reason = "NORMALIZED_OVERLAY_CHANGED_SOURCE_TREE";
            rejected.add(new Rejection(overlay.candidateIdentity(), reason));
            overlay = null;
        }
        if (overlay != null) {
            choice = new Choice(overlay.candidateIdentity(), overlay.sourceExpression(), overlay.definitionExpression(),
                overlay.referencePaths().stream().map(ExpressionOccurrencePath::canonical).toList());
        }
        var plan = overlay == null ? null : overlay.prepareArithmetic();
        var variables = new TreeSet<String>();
        collectVariables(root, variables);
        var outputs = new ArrayList<String>();
        Counts counts = null;
        for (int k = -2; k <= 1; k++) {
            var inputs = new TreeMap<String, ExactRational>();
            int index = 0;
            for (String variable : variables) inputs.put(variable, new ExactRational(
                BigInteger.valueOf((k + 1L) * (++index)), BigInteger.TWO));
            var tree = new Control(inputs, false);
            var cse = new Control(inputs, true);
            var expected = attempt(() -> tree.evaluate(root));
            var cseValue = attempt(() -> cse.evaluate(root));
            assertEquals(expected, cseValue, name);
            int operations = tree.operations, references = 0;
            if (plan != null) {
                ExactSharingArithmetic.Evaluation value = attempt(() -> plan.evaluate(inputs));
                assertEquals(expected, value == null ? null : value.value(), name);
                if (value != null) {
                    operations = value.arithmeticOperations();
                    references = value.referenceUses();
                    assertEquals(choice.paths().size(), references, name);
                }
            }
            outputs.add(expected == null ? "DOMAIN_FAILURE" : expected.canonicalText());
            if (expected != null) {
                var observed = new Counts(tree.operations, operations, cse.operations, references, cse.hits);
                if (counts != null) assertEquals(counts, observed, name);
                counts = observed;
            }
        }
        int saved = counts == null ? 0 : counts.tree() - counts.sharing();
        boolean material = !outputs.contains("DOMAIN_FAILURE") && saved >= 2 && 10L * saved >= counts.tree();
        return new Row(ordinal, name, "SUPPORTED", reason, choice, rejected, outputs, counts, material);
    }

    private static <T> T attempt(Supplier<T> execution) {
        try { return execution.get(); }
        catch (ArithmeticException error) {
            if (!"division by zero rational".equals(error.getMessage())) throw error;
            return null;
        }
    }

    private static void collectVariables(Expr expr, Set<String> names) {
        if (expr instanceof VariableExpr variable) names.add(variable.name());
        if (expr instanceof BinaryExpr binary) {
            collectVariables(binary.left(), names);
            collectVariables(binary.right(), names);
        }
    }

    /** Direct AST evaluation or full structural CSE. No dependency on the overlay compiler. */
    private static final class Control {
        final Map<String, ExactRational> inputs;
        final Map<Expr, ExactRational> cache;
        int operations, hits;

        Control(Map<String, ExactRational> inputs, boolean cse) {
            this.inputs = inputs;
            this.cache = cse ? new HashMap<>() : null;
        }

        ExactRational evaluate(Expr expr) {
            if (expr instanceof NumberExpr number) return number.value();
            if (expr instanceof VariableExpr variable) return Objects.requireNonNull(inputs.get(variable.name()));
            var binary = (BinaryExpr) expr;
            if (cache != null && cache.containsKey(binary)) {
                hits++;
                return cache.get(binary);
            }
            var left = evaluate(binary.left());
            var right = evaluate(binary.right());
            operations++;
            var value = switch (binary.operator()) {
                case ADD -> left.add(right);
                case SUB -> left.subtract(right);
                case MUL -> left.multiply(right);
                case DIV -> left.divide(right);
                default -> throw new IllegalArgumentException("Unsupported control operator " + binary.operator());
            };
            if (cache != null) cache.put(binary, value);
            return value;
        }
    }
}
