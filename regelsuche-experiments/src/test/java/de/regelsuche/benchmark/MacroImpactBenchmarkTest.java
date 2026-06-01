package de.regelsuche.benchmark;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class MacroImpactBenchmarkTest {
    @Test
    void reportsMacroReuseBridgeUsageAndSearchReduction() {
        DiscoveryBenchmarkResult withoutMacro = new DiscoveryBenchmarkRunner().run(new DiscoveryBenchmarkCase(
                "without-macro", "input", "target",
                List.of(List.of("input", "sophie_germain_bridge", "middle", "target"), List.of("input", "alt", "target")),
                Set.of(DiscoveryExpectation.BRIDGE_REQUIRED),
                List.of("sophie_germain_bridge"),
                Set.of(),
                Set.of()));
        DiscoveryBenchmarkResult withMacro = new DiscoveryBenchmarkRunner().run(new DiscoveryBenchmarkCase(
                "with-macro", "input", "target",
                List.of(List.of("input", "macro_sophie", "target")),
                Set.of(DiscoveryExpectation.MACRO_REUSE_REQUIRED),
                List.of(),
                Set.of("macro_sophie"),
                Set.of("macro_sophie")));

        assertThat(withoutMacro.bridgeCount()).isEqualTo(1);
        assertThat(withMacro.macroReuseCount()).isEqualTo(1);
        assertThat(withMacro.statesExplored()).isLessThan(withoutMacro.statesExplored());
    }
}
