package de.regelsuche.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.export.DefaultTransformationExportService;
import de.regelsuche.graph.InMemoryExpressionGraphStore;
import de.regelsuche.inventory.InMemoryRuleInventoryRepository;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import org.junit.jupiter.api.Test;

class RulePackCliRouterTest {

    @Test
    void rulesPacksListsTiersAndRuleOrigins() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int exit = router(output).run(new String[]{"rules", "packs"});

        assertEquals(0, exit);
        String text = output.toString();
        assertTrue(text.contains("core-pack core-identities (tier=kernel, enabled, rules=8)"), () -> text);
        assertTrue(text.contains("core-pack core-factorization (tier=first-party, enabled,"), () -> text);
        assertTrue(text.contains("rule ast_square_difference_factor (pack=core-factorization, tier=first-party, enabled)"),
            () -> text);
        assertTrue(text.contains("core-pack core-exact-polynomial-division (tier=first-party, disabled, rules=1)"),
            () -> text);
    }

    @Test
    void minimalKernelProfileDisablesFirstPartyPacks() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int exit = router(output).run(new String[]{"rules", "packs", "--rule-profile", "minimal-kernel"});

        assertEquals(0, exit);
        String text = output.toString();
        assertTrue(text.contains("core-pack core-identities (tier=kernel, enabled,"), () -> text);
        assertTrue(text.contains("core-pack core-factorization (tier=first-party, disabled,"), () -> text);
    }

    @Test
    void disablePackOptionSwitchesOffASinglePack() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int exit = router(output).run(
            new String[]{"rules", "packs", "--disable-pack", "core-factorization,core-distribution"});

        assertEquals(0, exit);
        String text = output.toString();
        assertTrue(text.contains("core-pack core-factorization (tier=first-party, disabled,"), () -> text);
        assertTrue(text.contains("core-pack core-distribution (tier=first-party, disabled,"), () -> text);
        assertTrue(text.contains("core-pack core-power-rules (tier=first-party, enabled,"), () -> text);
    }

    @Test
    void explicitlyEnablingTheExperimentalPackListsItAsEnabled() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int exit = router(output).run(new String[]{
            "rules", "packs", "--enable-pack", "core-exact-polynomial-division"
        });

        assertEquals(0, exit);
        assertTrue(output.toString().contains(
            "core-pack core-exact-polynomial-division (tier=first-party, enabled, rules=1)"),
            output::toString);
    }

    @Test
    void disablingAKernelPackIsRejected() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int exit = router(output).run(
            new String[]{"rules", "packs", "--disable-pack", "core-normalization"});

        assertEquals(1, exit);
        assertTrue(output.toString().contains("Kernel rule pack cannot be disabled"), output::toString);
    }

    @Test
    void unknownRuleProfileIsRejected() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int exit = router(output).run(new String[]{"rules", "packs", "--rule-profile", "does-not-exist"});

        assertEquals(1, exit);
        assertTrue(output.toString().contains("Unknown rule profile"), output::toString);
    }

    @Test
    void unknownRulePackIsRejected() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int exit = router(output).run(new String[]{
            "rules", "packs", "--enable-pack", "does-not-exist"
        });

        assertEquals(1, exit);
        assertTrue(output.toString().contains("Unknown rule pack"), output::toString);
    }

    @Test
    void rulesListReportsInventoryManifestHash() {
        ByteArrayOutputStream fullOutput = new ByteArrayOutputStream();
        assertEquals(0, router(fullOutput).run(new String[]{"rules", "list"}));
        ByteArrayOutputStream kernelOutput = new ByteArrayOutputStream();
        assertEquals(0, router(kernelOutput).run(
            new String[]{"rules", "list", "--rule-profile", "minimal-kernel"}));

        String full = manifestLine(fullOutput.toString());
        String kernel = manifestLine(kernelOutput.toString());
        assertTrue(full.contains("profile=core"), () -> full);
        assertTrue(kernel.contains("profile=minimal-kernel"), () -> kernel);
        assertFalse(full.equals(kernel), () -> full + " / " + kernel);
    }

    private static String manifestLine(String output) {
        return output.lines()
            .filter(line -> line.startsWith("rule-inventory "))
            .findFirst()
            .orElseThrow(() -> new AssertionError("missing rule-inventory line in: " + output));
    }

    private static CliRouter router(ByteArrayOutputStream output) {
        return new CliRouter(
            new PrintStream(output),
            new InMemoryExpressionGraphStore(),
            new InMemoryRuleInventoryRepository(),
            new DefaultTransformationExportService(),
            true
        );
    }
}
