package de.regelsuche.benchmark;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModPowDispatchStudyTest {
    @TempDir Path tmp;

    @Test void separateTrainingAndTwoEvaluationProcessesAgreeOnEveryRetainedByte() throws Exception {
        Path docs = Path.of("..", "docs", "research");
        if (!Files.isDirectory(docs)) docs = Path.of("docs", "research");
        Path study = docs.resolve("modpow-dispatch-v4");
        String protocol = study.resolve("protocol.json").toString();
        String train = docs.resolve("modpow-transfer-v1/train.json").toString();
        String historical = docs.resolve("modpow-budget-v3/test.json").toString();
        String model = tmp.resolve("model.json").toString();
        Path receipt = tmp.resolve("acquisition.json"), first = tmp.resolve("result.json"), second = tmp.resolve("repeat.json");
        process("acquire", protocol, train, model, receipt.toString());
        String digest = ModPowTransferFiles.hash(Files.readAllBytes(receipt));
        for (var output : List.of(first, second)) {
            process("evaluate", protocol, model, receipt.toString(), digest, historical, output.toString());
        }
        byte[] observed = Files.readAllBytes(first);
        assertArrayEquals(observed, Files.readAllBytes(second));
        assertArrayEquals(Files.readAllBytes(study.resolve("acquisition.json")), Files.readAllBytes(receipt));
        try (var frozen = new GZIPInputStream(Files.newInputStream(study.resolve("result.json.gz")))) {
            assertArrayEquals(frozen.readAllBytes(), observed);
        }
        var result = ModPowTransferFiles.JSON.readTree(observed);
        assertEquals(12, result.at("/prospective/cases").size());
        assertEquals(12, result.at("/retrospective/cases").size());
        assertTrue(result.at("/acquisition/counts/primitiveMatchAttempts").asLong() > 8);
        for (var row : result.at("/prospective/cases")) {
            if (row.required("id").asText().endsWith("missing-premise")) {
                for (String arm : List.of("primitive", "exhaustive", "indexed")) {
                    assertEquals(0, row.required(arm).at("/ledger/approved").intValue());
                    assertTrue(row.required(arm).at("/ledger/replayed").intValue() > 0);
                }
            }
            if (row.required("id").asText().endsWith("no-reuse")) {
                assertEquals(0, row.required("learnedApplications").intValue());
                assertEquals(row.required("sourceDag"), row.required("optimumDag"));
            }
        }
        assertThrows(IllegalArgumentException.class, () -> ModPowDispatchStudy.main(new String[]{
            "evaluate", protocol, model, receipt.toString(), "0".repeat(64), historical, tmp.resolve("forged.json").toString()}));
        assertFalse(Files.exists(tmp.resolve("forged.json")));

    }

    private void process(String... arguments) throws Exception {
        var classpath = new LinkedHashSet<String>();
        classpath.add(System.getProperty("java.class.path"));
        for (var loader = getClass().getClassLoader(); loader != null; loader = loader.getParent()) {
            if (loader instanceof URLClassLoader urls) {
                for (var url : urls.getURLs()) {
                    if ("file".equals(url.getProtocol())) classpath.add(Path.of(url.toURI()).toString());
                }
            }
        }
        var cmd = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Xmx384m", "-cp", String.join(File.pathSeparator, classpath),
            ModPowDispatchStudy.class.getName()));
        cmd.addAll(List.of(arguments));
        Path log = Files.createTempFile(tmp, "process-", ".log");
        var child = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!child.waitFor(2, TimeUnit.MINUTES)) {
            child.destroyForcibly();
            fail("child JVM exceeded time limit: " + Files.readString(log));
        }
        assertEquals(0, child.exitValue(), () -> {
            try { return Files.readString(log); }
            catch (java.io.IOException e) { return e.toString(); }
        });
    }
}
