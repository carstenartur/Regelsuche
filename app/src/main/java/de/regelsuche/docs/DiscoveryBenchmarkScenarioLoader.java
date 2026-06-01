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
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Enumeration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

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
        return listScenarioResources(directory).stream()
                .sorted(Comparator.naturalOrder())
                .map(resource -> load(directory + "/" + resource))
                .toList();
    }

    private List<String> listScenarioResources(String directory) {
        try {
            ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
            Enumeration<URL> resources = classLoader.getResources(directory);
            LinkedHashSet<String> names = new LinkedHashSet<>();
            while (resources.hasMoreElements()) {
                URL url = resources.nextElement();
                if ("jar".equals(url.getProtocol())) {
                    names.addAll(listFromJar(directory, url));
                } else {
                    names.addAll(listFromFile(url, directory));
                }
            }
            if (names.isEmpty() && classLoader instanceof URLClassLoader urlClassLoader) {
                for (URL url : urlClassLoader.getURLs()) {
                    if (url.getPath().endsWith(".jar")) {
                        names.addAll(listFromJarFile(Path.of(url.toURI()), directory));
                    }
                }
            }
            if (names.isEmpty()) {
                throw new IllegalArgumentException("Scenario directory not found: " + directory);
            }
            return List.copyOf(names);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        } catch (URISyntaxException exception) {
            throw new IllegalStateException("Unable to list scenario resources for " + directory, exception);
        }
    }

    private List<String> listFromFile(URL url, String directory) {
        try {
            return Files.list(Path.of(url.toURI()))
                    .filter(path -> path.getFileName().toString().endsWith(".yaml"))
                    .map(path -> path.getFileName().toString())
                    .toList();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        } catch (URISyntaxException exception) {
            throw new IllegalStateException("Scenario directory is not addressable as a file: " + directory, exception);
        }
    }

    private List<String> listFromJar(String directory, URL url) throws IOException {
        JarURLConnection connection = (JarURLConnection) url.openConnection();
        String entryName = connection.getEntryName() == null ? directory : connection.getEntryName();
        String prefix = entryName.endsWith("/") ? entryName : entryName + "/";
        LinkedHashSet<String> names = new LinkedHashSet<>();
        try (JarFile jar = connection.getJarFile()) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!entry.isDirectory() && name.startsWith(prefix) && name.endsWith(".yaml")) {
                    String relative = name.substring(prefix.length());
                    if (!relative.contains("/")) {
                        names.add(relative);
                    }
                }
            }
        }
        return List.copyOf(names);
    }

    private List<String> listFromJarFile(Path jarPath, String directory) throws IOException {
        String prefix = directory.endsWith("/") ? directory : directory + "/";
        LinkedHashSet<String> names = new LinkedHashSet<>();
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!entry.isDirectory() && name.startsWith(prefix) && name.endsWith(".yaml")) {
                    String relative = name.substring(prefix.length());
                    if (!relative.contains("/")) {
                        names.add(relative);
                    }
                }
            }
        }
        return List.copyOf(names);
    }

    List<ScenarioRulePack> loadRulePacks(DiscoveryBenchmarkScenario scenario) {
        List<ScenarioRulePack> packs = new ArrayList<>();
        for (String packId : scenario.enabledRulePacks()) {
            String path = "discovery-scenario-rules/" + packId + ".yaml";
            try (InputStream input = Thread.currentThread().getContextClassLoader().getResourceAsStream(path)) {
                if (input == null) {
                    continue;
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
                yaml.enabledOperators,
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
        public List<String> enabledOperators = List.of();
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
