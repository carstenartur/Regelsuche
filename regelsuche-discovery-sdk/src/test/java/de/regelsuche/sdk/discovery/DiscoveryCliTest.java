package de.regelsuche.sdk.discovery;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.sdk.discovery.cli.DiscoveryCli;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiscoveryCliTest {
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();
    private int execute(String... args) {
        return DiscoveryCli.execute(args, new PrintStream(output), getClass().getClassLoader());
    }
    @Test void listsClassesAndExplicitlySelectedDomain() {
        assertEquals(0, execute("list"));
        assertTrue(output.toString().contains(TestDiscoveryDomainProvider.class.getName()));
        assertEquals(0, execute("list", TestDiscoveryDomainProvider.class.getName()));
        assertTrue(output.toString().contains("sdk-multiplier-search@v1"));
        assertTrue(output.toString().contains("artifact=sha256:"));
        assertEquals(1, execute());
        assertTrue(output.toString().contains("Usage: list [provider.class]"));
        assertTrue(output.toString().contains("From the Regelsuche app: domains list [provider.class]"));
        assertEquals(1, execute("list", "missing.Provider"));
    }
    @Test void retainsEvidenceForConfirmedAndBudgetExhaustedRuns(@TempDir Path temp) throws Exception {
        Path seed = Files.writeString(temp.resolve("seed.txt"), "sequence");
        Path result = temp.resolve("confirmed.json");
        String provider = TestDiscoveryDomainProvider.class.getName();
        assertEquals(0, execute("run", provider, "sdk-multiplier-search@v1", "cli-test", seed.toString(), result.toString()));
        assertTrue(Files.readString(result).contains("sdk.provider.artifactSha256"));
        assertEquals(1, execute("run", provider, "sdk-multiplier-search@v1", "cli-test", seed.toString(), result.toString()));
        Path exhausted = temp.resolve("exhausted.json");
        assertEquals(3, execute("run", provider, "sdk-multiplier-search@v1", "cli-test", seed.toString(), exhausted.toString(), "tiny"));
        assertTrue(Files.readString(exhausted).contains("BUDGET_EXHAUSTED"));
        assertEquals(1, execute("run", provider, "sdk-multiplier-search@v1", "cli-test", seed.toString(), "unused", "unbounded"));
        assertEquals(1, execute("run", provider, "missing@v1", "cli-test", seed.toString(), "unused"));
        assertEquals(1, execute("run", provider, "missing", "cli-test", seed.toString(), "unused"));
        assertEquals(1, execute("run", provider, "sdk-multiplier-search@v1", "cli-test", "missing-seed", "unused"));
    }
    @Test void rejectsOversizedAndMalformedUtf8Seeds(@TempDir Path temp) throws Exception {
        Path seed = Files.writeString(temp.resolve("large.txt"), "x".repeat(1024 * 1024 + 1));
        Path result = temp.resolve("result.json");
        String provider = TestDiscoveryDomainProvider.class.getName();
        assertEquals(1, execute("run", provider, "sdk-multiplier-search@v1", "cli-test", seed.toString(), result.toString()));
        assertFalse(Files.exists(result));
        Files.write(seed, new byte[]{(byte) 0xff});
        assertEquals(1, execute("run", provider, "sdk-multiplier-search@v1", "cli-test", seed.toString(), result.toString()));
        assertFalse(Files.exists(result));
    }
}
