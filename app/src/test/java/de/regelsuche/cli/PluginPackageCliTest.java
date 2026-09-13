package de.regelsuche.cli;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.export.DefaultTransformationExportService;
import de.regelsuche.graph.InMemoryExpressionGraphStore;
import de.regelsuche.inventory.InMemoryRuleInventoryRepository;
import java.io.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PluginPackageCliTest {
    @TempDir Path directory;
    @Test void packageInstallRequiresExplicitConfigurationInsteadOfFallingThroughToLocalPlugins() {
        var output = new ByteArrayOutputStream();
        var router = new CliRouter(new PrintStream(output), new InMemoryExpressionGraphStore(),
            new InMemoryRuleInventoryRepository(), new DefaultTransformationExportService(), true);
        assertEquals(2, router.run(new String[]{"plugins", "package", "install", "--operation", "chosen-before-start"}));
        assertTrue(output.toString().contains("PACKAGE_CONFIGURATION_ERROR"));
    }
    @Test void packageRecoveryRejectsDuplicateOptionsBeforeOpeningAnyAuthority() {
        var output = new ByteArrayOutputStream();
        var router = new CliRouter(new PrintStream(output), new InMemoryExpressionGraphStore(),
            new InMemoryRuleInventoryRepository(), new DefaultTransformationExportService(), true);
        assertEquals(2, router.run(new String[]{"plugins", "package", "recover",
            "--operation", "one", "--operation", "two", "--config", directory.resolve("missing.json").toString()}));
        assertTrue(output.toString().contains("PACKAGE_CONFIGURATION_ERROR"));
    }
}
