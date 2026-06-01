package de.regelsuche.docs;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import de.regelsuche.benchmark.DiscoveryExpectation;
import de.regelsuche.knowledge.SearchEffect;
import de.regelsuche.transform.RewriteKind;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class DiscoveryBenchmarkScenarioLoader {
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory()).findAndRegisterModules();

    public DiscoveryBenchmarkScenario load(String resourceName) {
        String path = resourceName.startsWith("/") ? resourceName.substring(1) : resourceName;
        try (InputStream input = Thread.currentThread().getContextClassLoader().getResourceAsStream(path)) {
            if (input == null) {
                throw new IllegalArgumentException("Scenario resource not found: " + path);
            }
            ScenarioYaml yaml = YAML.readValue(input, ScenarioYaml.class);
            DiscoveryBenchmarkScenario scenario = toScenario(yaml);
            validate(scenario);
            return scenario;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    public List<DiscoveryBenchmarkScenario> loadAll(String resourceDirectory) {
        String directory = resourceDirectory.endsWith("/") ? resourceDirectory.substring(0, resourceDirectory.length() - 1) : resourceDirectory;
        URL url = Thread.currentThread().getContextClassLoader().getResource(directory);
        if (url == null) {
            throw new IllegalArgumentException("Scenario directory not found: " + directory);
        }
        try {
            return Files.list(Path.of(url.toURI()))
                    .filter(path -> path.getFileName().toString().endsWith(".yaml"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .map(path -> load(directory + "/" + path.getFileName()))
                    .toList();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        } catch (URISyntaxException exception) {
            throw new IllegalStateException("Scenario directory is not addressable as a file: " + directory, exception);
        }
    }

    List<ScenarioRulePack> loadRulePacks(DiscoveryBenchmarkScenario scenario) {
        List<ScenarioRulePack> packs = new ArrayList<>();
        for (String packId : scenario.enabledRulePacks()) {
            String path = "discovery-scenario-rules/" + packId + ".yaml";
            try (InputStream input = Thread.currentThread().getContextClassLoader().getResourceAsStream(path)) {
                if (input == null) {
                    throw new IllegalArgumentException("Referenced rule pack does not exist: " + packId);
                }
                RulePackYaml yaml = YAML.readValue(input, RulePackYaml.class);
                ScenarioRulePack pack = toRulePack(yaml);
                if (!packId.equals(pack.id())) {
                    throw new IllegalArgumentException("Rule pack id mismatch for " + packId + ": " + pack.id());
                }
                packs.add(pack);
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }
        return List.copyOf(packs);
    }

    private DiscoveryBenchmarkScenario toScenario(ScenarioYaml yaml) {
        return new DiscoveryBenchmarkScenario(
                yaml.id,
                yaml.displayName,
                yaml.inputExpression,
                yaml.targetExpression,
                yaml.expectations == null ? List.of() : yaml.expectations.stream().map(DiscoveryExpectation::valueOf).toList(),
                yaml.enabledRulePacks,
                yaml.requiredBridgeEffects == null ? List.of() : yaml.requiredBridgeEffects.stream().map(SearchEffect::fromExternal).toList(),
                yaml.requiredRuleFamilies,
                yaml.requiredBridgeRules,
                yaml.macroLearning == null ? null : new DiscoveryBenchmarkScenario.MacroLearning(
                        yaml.macroLearning.enabled,
                        yaml.macroLearning.reuseInputExpression,
                        yaml.macroLearning.expectedMacroRule),
                yaml.budgets == null ? null : new DiscoveryBenchmarkScenario.Budgets(
                        yaml.budgets.maxDepth,
                        yaml.budgets.maxStates,
                        yaml.budgets.timeoutMillis),
                yaml.gallery == null ? null : new DiscoveryBenchmarkScenario.Gallery(
                        yaml.gallery.generateSvg,
                        yaml.gallery.preferredPathCount,
                        yaml.gallery.minVisibleNodes));
    }

    private ScenarioRulePack toRulePack(RulePackYaml yaml) {
        List<ScenarioRule> rules = yaml.rules == null ? List.of() : yaml.rules.stream()
                .map(rule -> new ScenarioRule(
                        rule.id,
                        rule.from,
                        rule.to,
                        rule.kind == null ? RewriteKind.NORMALIZE : RewriteKind.valueOf(rule.kind.toUpperCase(Locale.ROOT)),
                        rule.costDelta,
                        rule.effects == null ? List.of() : rule.effects.stream().map(SearchEffect::fromExternal).toList(),
                        rule.family))
                .toList();
        return new ScenarioRulePack(yaml.id, rules);
    }

    private void validate(DiscoveryBenchmarkScenario scenario) {
        requireText(scenario.id(), "id");
        requireText(scenario.inputExpression(), "inputExpression");
        requireText(scenario.targetExpression(), "targetExpression");
        if (scenario.expectations().isEmpty()) {
            throw new IllegalArgumentException("Scenario must declare expectations: " + scenario.id());
        }
        if (scenario.budgets() == null || scenario.budgets().maxDepth() <= 0 || scenario.budgets().maxStates() <= 0) {
            throw new IllegalArgumentException("Scenario must declare positive budgets: " + scenario.id());
        }
        if (scenario.enabledRulePacks().isEmpty()) {
            throw new IllegalArgumentException("Scenario must declare enabledRulePacks: " + scenario.id());
        }
        if (scenario.macroLearning().enabled() && !hasText(scenario.macroLearning().expectedMacroRule())) {
            throw new IllegalArgumentException("Macro learning scenario must declare expectedMacroRule: " + scenario.id());
        }
        loadRulePacks(scenario);
    }

    private void requireText(String value, String field) {
        if (!hasText(value)) {
            throw new IllegalArgumentException("Scenario must declare " + field);
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class ScenarioYaml {
        public String id;
        public String displayName;
        public String inputExpression;
        public String targetExpression;
        public List<String> expectations = List.of();
        public List<String> enabledRulePacks = List.of();
        public List<String> requiredBridgeEffects = List.of();
        public List<String> requiredRuleFamilies = List.of();
        public List<String> requiredBridgeRules = List.of();
        public MacroLearningYaml macroLearning;
        public BudgetsYaml budgets;
        public GalleryYaml gallery;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class MacroLearningYaml {
        public boolean enabled;
        public String reuseInputExpression;
        public String expectedMacroRule;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class BudgetsYaml {
        public int maxDepth;
        public int maxStates;
        public long timeoutMillis;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class GalleryYaml {
        public boolean generateSvg;
        public int preferredPathCount;
        public int minVisibleNodes;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class RulePackYaml {
        public String id;
        public List<RuleYaml> rules = List.of();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class RuleYaml {
        public String id;
        public String from;
        public String to;
        public String kind;
        public int costDelta;
        public List<String> effects = List.of();
        public String family;
    }
}
