package de.regelsuche.knowledge;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import de.regelsuche.transform.PatternRewriteRule;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

public class KnowledgePackLoader {
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory()).findAndRegisterModules();

    public List<KnowledgePack> loadClasspathPacks() {
        List<KnowledgePack> packs = new ArrayList<>();
        try {
            var resources = Thread.currentThread().getContextClassLoader().getResources("rules/packs");
            while (resources.hasMoreElements()) {
                URL resource = resources.nextElement();
                if ("file".equals(resource.getProtocol())) {
                    packs.addAll(loadAll(Path.of(resource.toURI())));
                } else if ("jar".equals(resource.getProtocol())) {
                    URI jarUri = resource.toURI();
                    try (FileSystem jarFs = FileSystems.newFileSystem(jarUri, Map.of())) {
                        String spec = jarUri.getSchemeSpecificPart();
                        int bang = spec.lastIndexOf('!');
                        String entryPath = bang >= 0 ? spec.substring(bang + 1) : "/rules/packs";
                        packs.addAll(loadAll(jarFs.getPath(entryPath)));
                    }
                }
            }
        } catch (IOException | URISyntaxException ex) {
            throw new IllegalStateException("Unable to load knowledge packs", ex);
        }
        return packs;
    }

    public List<KnowledgePack> loadAll(Path baseDirectory) {
        if (baseDirectory == null || !Files.exists(baseDirectory)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.walk(baseDirectory)) {
            return paths
                    .filter(path -> path.getFileName().toString().endsWith(".rules.yaml"))
                    .sorted(Comparator.comparing(Path::toString))
                    .map(this::load)
                    .toList();
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to load knowledge packs from " + baseDirectory, ex);
        }
    }

    public KnowledgePack load(Path path) {
        try (InputStream in = Files.newInputStream(path)) {
            PackYaml yaml = YAML.readValue(in, PackYaml.class);
            validatePack(yaml, path);
            List<String> packCategories = nonNull(yaml.categories);
            List<PatternRewriteRule> rules = new ArrayList<>();
            for (RuleYaml ruleYaml : nonNull(yaml.rules)) {
                validateRule(yaml, ruleYaml, path);
                String ruleId = require(ruleYaml.id, "rule id", path);
                List<String> ruleCategories = ruleYaml.categories == null ? packCategories : nonNull(ruleYaml.categories);
                RuleDescriptor descriptor = new RuleDescriptor(
                        ruleId,
                        yaml.packId,
                        firstNonBlank(ruleYaml.originProject, yaml.sourceProject),
                        firstNonBlank(ruleYaml.license, yaml.license),
                        firstNonBlank(ruleYaml.sourceVersion, yaml.sourceVersion),
                        firstNonBlank(ruleYaml.sourceReference, yaml.sourceReference),
                        ruleYaml.derivationType,
                        ruleYaml.status,
                        firstNonBlank(ruleYaml.riskLevel, "medium"),
                        ruleCategories,
                        validationExamples(ruleYaml.validation));
                rules.add(new PatternRewriteRule(ruleId,
                        KnowledgePatternParser.parse(require(ruleYaml.rule.from, "rule.from", path)),
                        KnowledgePatternParser.parse(require(ruleYaml.rule.to, "rule.to", path)),
                        descriptor));
            }
            return new KnowledgePack(yaml.packId, yaml.displayName, yaml.sourceProject, yaml.license,
                    yaml.sourceUrl, yaml.sourceVersion, yaml.sourceReference, yaml.enabledByDefault, packCategories, rules);
        } catch (IOException ex) {
            throw new IllegalArgumentException("Invalid knowledge pack YAML: " + path, ex);
        }
    }

    private void validatePack(PackYaml yaml, Path path) {
        Objects.requireNonNull(yaml, "Knowledge pack YAML must not be empty");
        require(yaml.packId, "packId", path);
        require(yaml.displayName, "displayName", path);
        require(yaml.sourceProject, "sourceProject", path);
        require(yaml.license, "license", path);
        require(yaml.sourceUrl, "sourceUrl", path);
        require(yaml.sourceVersion, "sourceVersion", path);
        require(yaml.sourceReference, "sourceReference", path);
        if (yaml.rules == null || yaml.rules.isEmpty()) {
            throw new IllegalArgumentException("Knowledge pack must declare at least one rule: " + path);
        }
    }

    private void validateRule(PackYaml pack, RuleYaml rule, Path path) {
        require(rule.id, "rule id", path);
        if (rule.rule == null) {
            throw new IllegalArgumentException("Rule " + rule.id + " is missing rule.from/rule.to in " + path);
        }
        require(rule.rule.from, "rule.from", path);
        require(rule.rule.to, "rule.to", path);
        require(firstNonBlank(rule.originProject, pack.sourceProject), "originProject", path);
        require(firstNonBlank(rule.license, pack.license), "license", path);
        if (rule.derivationType == null) {
            throw new IllegalArgumentException("Rule " + rule.id + " is missing derivationType in " + path);
        }
        if (rule.status == null) {
            throw new IllegalArgumentException("Rule " + rule.id + " is missing status in " + path);
        }
        require(firstNonBlank(rule.sourceVersion, pack.sourceVersion), "sourceVersion", path);
        require(firstNonBlank(rule.sourceReference, pack.sourceReference), "sourceReference", path);
        if (rule.status == RuleStatus.VALIDATED && validationExamples(rule.validation).isEmpty()) {
            throw new IllegalArgumentException("Rule " + rule.id + " is missing validation.examples in " + path);
        }
    }

    private static List<ValidationExample> validationExamples(ValidationYaml validation) {
        if (validation == null || validation.examples == null) {
            return List.of();
        }
        return validation.examples.stream()
                .map(example -> new ValidationExample(example.from, example.to))
                .toList();
    }

    private static String require(String value, String field, Path path) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required field " + field + " in " + path);
        }
        return value;
    }

    private static String firstNonBlank(String first, String fallback) {
        return first == null || first.isBlank() ? fallback : first;
    }

    private static <T> List<T> nonNull(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PackYaml {
        public String packId;
        public String displayName;
        public String sourceProject;
        public String license;
        public String sourceUrl;
        public String sourceVersion;
        public String sourceReference;
        public boolean enabledByDefault;
        public List<String> categories;
        public List<RuleYaml> rules;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RuleYaml {
        public String id;
        public String originProject;
        public String license;
        public String sourceVersion;
        public String sourceReference;
        public DerivationType derivationType;
        public RuleStatus status;
        public String riskLevel;
        public List<String> categories;
        public RuleBodyYaml rule;
        public ValidationYaml validation;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RuleBodyYaml {
        public String from;
        public String to;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ValidationYaml {
        public List<ValidationExampleYaml> examples;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ValidationExampleYaml {
        public String from;
        public String to;
    }
}
