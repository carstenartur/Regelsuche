package de.regelsuche.plugin.example;

import de.regelsuche.plugin.ExamplePackage;
import de.regelsuche.plugin.ExampleRegistry;
import de.regelsuche.plugin.Heuristic;
import de.regelsuche.plugin.HeuristicRegistry;
import de.regelsuche.plugin.PluginDependency;
import de.regelsuche.plugin.RegelsuchePlugin;
import de.regelsuche.plugin.SearchStrategy;
import de.regelsuche.plugin.SearchStrategyRegistry;
import java.util.List;
import java.util.Set;

public final class DiscoveryOperatorsPlugin implements RegelsuchePlugin {
    @Override
    public String id() {
        return "discovery-operators-pack";
    }

    @Override
    public String name() {
        return "Discovery Operators Pack";
    }

    @Override
    public String version() {
        return "1.0.0";
    }

    @Override
    public String minimumCoreVersion() {
        return "1.0.0";
    }

    @Override
    public Set<String> capabilities() {
        return Set.of("search-strategies", "heuristics", "examples");
    }

    @Override
    public List<PluginDependency> dependencies() {
        return List.of(new PluginDependency("algebra-core", ">=1.0.0", false));
    }

    @Override
    public String provenance() {
        return "https://github.com/carstenartur/Regelsuche/tree/main/app/src/main/java/de/regelsuche/plugin/example";
    }

    @Override
    public String signature() {
        return "demo-classpath-signature";
    }

    @Override
    public void registerSearchStrategies(SearchStrategyRegistry registry) {
        registry.register(new SearchStrategy() {
            @Override
            public String id() {
                return "discovery-operator-bias";
            }

            @Override
            public String name() {
                return "Discovery operator bias";
            }

            @Override
            public String description() {
                return "Priorisiert Operatoren mit hohem Discovery-Wert.";
            }

            @Override
            public List<String> tags() {
                return List.of("discovery", "operators");
            }
        }, id());
    }

    @Override
    public void registerHeuristics(HeuristicRegistry registry) {
        registry.register(new Heuristic() {
            @Override
            public String id() {
                return "discovery-operator-heuristic";
            }

            @Override
            public int score(String expression) {
                return expression.contains("/") ? 8 : 2;
            }

            @Override
            public List<String> tags() {
                return List.of("discovery", "operators");
            }
        }, id());
    }

    @Override
    public void registerExamples(ExampleRegistry registry) {
        registry.register(new ExamplePackage(
            "discovery-operators-examples",
            "Discovery operators",
            List.of(new ExamplePackage.ExampleEntry(
                "fraction-operator-hint",
                "a/b + c/b",
                "(a + c)/b"
            )),
            List.of("discovery", "operators")
        ), id());
    }
}
