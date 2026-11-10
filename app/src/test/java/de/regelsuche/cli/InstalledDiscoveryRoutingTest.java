package de.regelsuche.cli;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InstalledDiscoveryRoutingTest {
    @Test void installedPrefixReachesConfigurationFailureAndLegacyRoutesRemain(@TempDir Path directory) throws Exception {
        var output = new ByteArrayOutputStream();
        var router = new CliRouter(new PrintStream(output),new de.regelsuche.graph.InMemoryExpressionGraphStore(),
            new de.regelsuche.inventory.InMemoryRuleInventoryRepository(),
            new de.regelsuche.export.DefaultTransformationExportService(),true);
        Path invalid=Files.writeString(directory.resolve("config.json"),"{}");
        assertEquals(1,router.run(new String[]{"domains","installed","--config",invalid.toString(),"list"}));
        assertTrue(output.toString().contains("Installed discovery failed: CONFIGURATION"));
        output.reset();
        assertEquals(0,router.run(new String[]{"domains","list"}));
        assertFalse(output.toString().contains("Installed discovery"));
        output.reset();
        assertEquals(1,router.run(new String[]{"domains","run","missing.Provider","missing@v1","campaign","missing","unused"}));
        assertTrue(output.toString().contains("Discovery request failed:"));
        assertFalse(output.toString().contains("Installed discovery"));
    }
}
