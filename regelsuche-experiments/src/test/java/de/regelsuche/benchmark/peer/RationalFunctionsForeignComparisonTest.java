package de.regelsuche.benchmark.peer;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RationalFunctionsForeignComparisonTest {
    @TempDir Path directory;

    @Test
    void foreignReportRetainsDomainsUnsupportedRowsAndHonestArithmeticCosts() throws Exception {
        var corpora = new LinkedHashMap<String, String>();
        corpora.put("research/benchmarks/enumo-public-v1/rosa.fpcore", """
            (FPCore (x) :name "cancelled factor"
              (let ([denominator (- x 1)]) (/ (- (* x x) 1) denominator)))
            (FPCore (x) :name "undefined" (/ x (- x x)))
            """);
        corpora.put("research/benchmarks/enumo-horner-v2/transfer/fptaylor-extra.fpcore", """
            (FPCore (x) :name "unsupported root" (sqrt x))
            """);
        corpora.put("research/benchmarks/enumo-horner-v2/transfer/hamming-ch3.fpcore", """
            (FPCore (x) :name "linear" (+ x 1))
            (FPCore (x) :name "expanded power" (* (* x x) (* x x)))
            """);
        for (var corpus : corpora.entrySet()) {
            Path file = directory.resolve(corpus.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, corpus.getValue());
        }
        Path output = directory.resolve("reports/foreign.json");
        RationalFunctionsForeignComparison.main(new String[]{directory.toString(), output.toString()});
        JsonNode report = new ObjectMapper().readTree(output.toFile());
        assertEquals(3, report.required("sources").size());
        var corpusPaths = List.copyOf(corpora.keySet());
        for (int i = 0; i < corpusPaths.size(); i++) {
            JsonNode source = report.required("sources").get(i);
            assertEquals(corpusPaths.get(i), source.required("path").asText());
            assertEquals(hash(corpora.get(corpusPaths.get(i))), source.required("sha256").asText());
            assertEquals(i == 1 ? 1 : 2, source.required("programs").asInt());
            assertEquals(corpora.get(corpusPaths.get(i)), Files.readString(directory.resolve(corpusPaths.get(i))));
        }

        JsonNode rows = report.required("rows");
        assertEquals(5, rows.size());
        var expectedNames = List.of("cancelled factor", "undefined", "unsupported root", "linear", "expanded power");
        var expectedCorpora = List.of(corpusPaths.get(0), corpusPaths.get(0), corpusPaths.get(1), corpusPaths.get(2), corpusPaths.get(2));
        var expectedOrdinals = List.of(1, 2, 1, 1, 2);
        for (int i = 0; i < rows.size(); i++) {
            assertEquals(expectedNames.get(i), rows.get(i).required("name").asText());
            assertEquals(expectedCorpora.get(i), rows.get(i).required("corpus").asText());
            assertEquals(expectedOrdinals.get(i).intValue(), rows.get(i).required("ordinal").asInt());
        }
        JsonNode cancellation = rows.get(0);
        assertEquals("UNSUPPORTED", cancellation.required("polynomialStatus").asText());
        assertEquals("(((x*x)-1)/(x-1))", cancellation.required("source").asText());
        assertEquals("x + 1", cancellation.required("candidate").asText());
        assertEquals(1, cancellation.required("requiredConditions").size());
        assertEquals("x - 1 != 0", cancellation.required("requiredConditions").get(0).asText());
        assertEquals(4, cancellation.required("sourceOperations").asInt());
        assertEquals(1, cancellation.required("candidateOperations").asInt());
        for (int index : List.of(1, 2)) {
            JsonNode rejected = rows.get(index);
            assertEquals("UNSUPPORTED", rejected.required("status").asText());
            assertFalse(rejected.required("detail").asText().isBlank());
            assertEquals("", rejected.required("source").asText());
            for (String field : List.of("candidate", "proposalStatus", "independentlyReplayed", "work", "replayWork"))
                assertFalse(rejected.has(field), field);
        }
        assertEquals(0, rows.get(3).required("requiredConditions").size());
        assertEquals(1, rows.get(3).required("sourceOperations").asInt());
        assertEquals(1, rows.get(3).required("candidateOperations").asInt());
        assertEquals("x^4", rows.get(4).required("candidate").asText());
        // Both four factors and their power spelling require three multiplications.
        assertEquals(3, rows.get(4).required("sourceOperations").asInt());
        assertEquals(3, rows.get(4).required("candidateOperations").asInt());
        for (int index : List.of(0, 3, 4)) {
            JsonNode accepted = rows.get(index);
            assertEquals("SUPPORTED", accepted.required("status").asText());
            assertEquals("VERIFIED", accepted.required("proposalStatus").asText());
            assertTrue(accepted.required("independentlyReplayed").asBoolean());
            assertEquals(hash(accepted.required("source").asText()), accepted.required("sourceSha256").asText());
            long work = accepted.required("work").asLong(), replay = accepted.required("replayWork").asLong();
            assertTrue(work > 0 && replay > 0);
            assertEquals(work + replay, accepted.required("totalWorkIncludingReplay").asLong());
        }
    }

    private static String hash(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }
}
