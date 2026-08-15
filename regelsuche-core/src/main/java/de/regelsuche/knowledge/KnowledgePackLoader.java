package de.regelsuche.knowledge;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import de.regelsuche.knowledge.KnowledgePack.KnownStructureDefinition;
import de.regelsuche.knowledge.KnowledgePack.KnownStructureEvidence;
import de.regelsuche.knowledge.KnowledgePack.KnownStructureMetadata;
import de.regelsuche.transform.ExprMatcher;
import de.regelsuche.transform.PatternExpr;
import de.regelsuche.transform.PatternRewriteRule;
import de.regelsuche.transform.RecognitionProfile;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

public class KnowledgePackLoader {
    private static final ObjectMapper YAML =
        new ObjectMapper(new YAMLFactory()).findAndRegisterModules();

    public List<KnowledgePack> loadClasspathPacks() {
        List<KnowledgePack> packs = new ArrayList<>();
        try {
            var resources = Thread.currentThread().getContextClassLoader()
                .getResources("rules/packs");
            while (resources.hasMoreElements()) {
                URL resource = resources.nextElement();
                if ("file".equals(resource.getProtocol())) {
                    packs.addAll(loadAll(Path.of(resource.toURI())));
                } else if ("jar".equals(resource.getProtocol())) {
                    URI jarUri = resource.toURI();
                    try (FileSystem jarFs =
                            FileSystems.newFileSystem(jarUri, Map.of())) {
                        String spec = jarUri.getSchemeSpecificPart();
                        int bang = spec.lastIndexOf('!');
                        String entryPath = bang >= 0
                            ? spec.substring(bang + 1)
                            : "/rules/packs";
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
                    .filter(path -> path.getFileName().toString()
                        .endsWith(".rules.yaml"))
                    .sorted(Comparator.comparing(Path::toString))
                    .map(this::load)
                    .toList();
        } catch (IOException ex) {
            throw new IllegalStateException(
                "Unable to load knowledge packs from " + baseDirectory, ex);
        }
    }

    public KnowledgePack load(Path path) {
        try (InputStream in = Files.newInputStream(path)) {
            PackYaml yaml = YAML.readValue(in, PackYaml.class);
            validatePack(yaml, path);
            List<String> packCategories = nonNull(yaml.categories);
            List<PatternRewriteRule> rules = loadRules(
                yaml, path, packCategories);
            List<KnownStructureDefinition> knownStructures =
                loadKnownStructures(yaml, path);
            return new KnowledgePack(
                yaml.packId,
                yaml.displayName,
                yaml.sourceProject,
                yaml.license,
                yaml.sourceUrl,
                yaml.sourceVersion,
                yaml.sourceReference,
                yaml.enabledByDefault,
                yaml.maturity,
                tier(yaml, path),
                packCategories,
                rules,
                knownStructures
            );
        } catch (IOException ex) {
            throw new IllegalArgumentException(
                "Invalid knowledge pack YAML: " + path, ex);
        }
    }

    private List<PatternRewriteRule> loadRules(
        PackYaml yaml,
        Path path,
        List<String> packCategories
    ) {
        List<PatternRewriteRule> rules = new ArrayList<>();
        for (RuleYaml ruleYaml : nonNull(yaml.rules)) {
            validateRule(yaml, ruleYaml, path);
            String ruleId = require(ruleYaml.id, "rule id", path);
            List<String> ruleCategories = ruleYaml.categories == null
                ? packCategories
                : nonNull(ruleYaml.categories);
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
                searchEffects(ruleYaml),
                validationExamples(ruleYaml.validation),
                counterExamples(ruleYaml.validation)
            );
            rules.add(new PatternRewriteRule(
                ruleId,
                KnowledgePatternParser.parse(
                    require(ruleYaml.rule.from, "rule.from", path)),
                KnowledgePatternParser.parse(
                    require(ruleYaml.rule.to, "rule.to", path)),
                descriptor
            ));
        }
        return List.copyOf(rules);
    }

    private List<KnownStructureDefinition> loadKnownStructures(
        PackYaml yaml,
        Path path
    ) {
        List<KnownStructureDefinition> definitions = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (KnownStructureYaml structure : nonNull(yaml.knownStructures)) {
            validateKnownStructure(yaml, structure, path);
            String id = require(structure.id, "knownStructure.id", path);
            if (!ids.add(id)) {
                throw new IllegalArgumentException(
                    "Duplicate known-structure id " + id + " in " + path);
            }
            KnownStructureMetadata metadata = new KnownStructureMetadata(
                firstNonBlank(structure.sourceProject, yaml.sourceProject),
                firstNonBlank(structure.license, yaml.license),
                firstNonBlank(structure.sourceUrl, yaml.sourceUrl),
                firstNonBlank(structure.sourceVersion, yaml.sourceVersion),
                firstNonBlank(
                    structure.sourceReference, yaml.sourceReference),
                require(
                    structure.translationNotes,
                    "knownStructure.translationNotes",
                    path
                ),
                nonNull(structure.enabledRulePackIds),
                nonNull(structure.compatibleBackendIds),
                structure.minimumEvidence
            );
            PatternExpr pattern = KnowledgePatternParser.parse(require(
                structure.pattern, "knownStructure.pattern", path));
            ExprMatcher matcher = ExprMatcher.pattern(
                pattern,
                structure.recognition.profile()
            );
            definitions.add(new KnownStructureDefinition(
                id,
                require(structure.domainId, "knownStructure.domainId", path),
                matcher,
                nonNull(structure.requiredAssumptions),
                nonNull(structure.consequenceIds),
                metadata
            ));
        }
        return List.copyOf(definitions);
    }

    private static RuleTier tier(PackYaml yaml, Path path) {
        if (yaml.tier == null || yaml.tier.isBlank()) {
            return RuleTier.FIRST_PARTY;
        }
        try {
            return RuleTier.fromId(yaml.tier);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                "Invalid tier in " + path + ": " + yaml.tier, ex);
        }
    }

    private void validatePack(PackYaml yaml, Path path) {
        Objects.requireNonNull(
            yaml, "Knowledge pack YAML must not be empty");
        require(yaml.packId, "packId", path);
        require(yaml.displayName, "displayName", path);
        require(yaml.sourceProject, "sourceProject", path);
        require(yaml.license, "license", path);
        require(yaml.sourceUrl, "sourceUrl", path);
        require(yaml.sourceVersion, "sourceVersion", path);
        require(yaml.sourceReference, "sourceReference", path);
        if (yaml.maturity == null) {
            throw new IllegalArgumentException(
                "Knowledge pack is missing maturity in " + path);
        }
        if (nonNull(yaml.rules).isEmpty()
                && nonNull(yaml.knownStructures).isEmpty()) {
            throw new IllegalArgumentException(
                "Knowledge pack must declare at least one rule or known "
                    + "structure: " + path);
        }
    }

    private void validateRule(PackYaml pack, RuleYaml rule, Path path) {
        require(rule.id, "rule id", path);
        if (rule.rule == null) {
            throw new IllegalArgumentException(
                "Rule " + rule.id
                    + " is missing rule.from/rule.to in " + path);
        }
        require(rule.rule.from, "rule.from", path);
        require(rule.rule.to, "rule.to", path);
        require(firstNonBlank(rule.originProject, pack.sourceProject),
            "originProject", path);
        require(firstNonBlank(rule.license, pack.license), "license", path);
        if (rule.derivationType == null) {
            throw new IllegalArgumentException(
                "Rule " + rule.id + " is missing derivationType in " + path);
        }
        if (rule.status == null) {
            throw new IllegalArgumentException(
                "Rule " + rule.id + " is missing status in " + path);
        }
        if (rule.status == RuleStatus.CANDIDATE
                && searchEffects(rule).contains(SearchEffect.BRIDGING)) {
            rule.status = RuleStatus.DISCOVERY_CANDIDATE;
        }
        require(firstNonBlank(rule.sourceVersion, pack.sourceVersion),
            "sourceVersion", path);
        require(firstNonBlank(rule.sourceReference, pack.sourceReference),
            "sourceReference", path);
        if (rule.status == RuleStatus.VALIDATED
                && validationExamples(rule.validation).isEmpty()) {
            throw new IllegalArgumentException(
                "Rule " + rule.id
                    + " is missing validation.examples in " + path);
        }
    }

    private void validateKnownStructure(
        PackYaml pack,
        KnownStructureYaml structure,
        Path path
    ) {
        require(structure.id, "knownStructure.id", path);
        require(structure.domainId, "knownStructure.domainId", path);
        require(structure.pattern, "knownStructure.pattern", path);
        if (structure.recognition == null) {
            throw new IllegalArgumentException(
                "Known structure " + structure.id
                    + " is missing recognition in " + path);
        }
        if (structure.minimumEvidence == null) {
            throw new IllegalArgumentException(
                "Known structure " + structure.id
                    + " is missing minimumEvidence in " + path);
        }
        if (nonNull(structure.consequenceIds).isEmpty()) {
            throw new IllegalArgumentException(
                "Known structure " + structure.id
                    + " must declare consequenceIds in " + path);
        }
        require(firstNonBlank(structure.sourceProject, pack.sourceProject),
            "knownStructure.sourceProject", path);
        require(firstNonBlank(structure.license, pack.license),
            "knownStructure.license", path);
        require(firstNonBlank(structure.sourceUrl, pack.sourceUrl),
            "knownStructure.sourceUrl", path);
        require(firstNonBlank(structure.sourceVersion, pack.sourceVersion),
            "knownStructure.sourceVersion", path);
        require(firstNonBlank(structure.sourceReference, pack.sourceReference),
            "knownStructure.sourceReference", path);
        require(structure.translationNotes,
            "knownStructure.translationNotes", path);
    }

    private static List<ValidationExample> validationExamples(
        ValidationYaml validation
    ) {
        if (validation == null || validation.examples == null) {
            return List.of();
        }
        return validation.examples.stream()
                .map(example -> new ValidationExample(
                    example.from, example.to))
                .toList();
    }

    private static List<ValidationExample> counterExamples(
        ValidationYaml validation
    ) {
        if (validation == null || validation.counterexamples == null) {
            return List.of();
        }
        return validation.counterexamples.stream()
                .map(example -> new ValidationExample(
                    example.from, example.to))
                .toList();
    }

    private static String require(String value, String field, Path path) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                "Missing required field " + field + " in " + path);
        }
        return value;
    }

    private static String firstNonBlank(String first, String fallback) {
        return first == null || first.isBlank() ? fallback : first;
    }

    private static <T> List<T> nonNull(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private static List<SearchEffect> searchEffects(RuleYaml rule) {
        return rule.searchEffects == null || rule.searchEffects.isEmpty()
                ? List.of()
                : List.copyOf(rule.searchEffects);
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
        public KnowledgePackMaturity maturity;
        public String tier;
        public List<String> categories;
        public List<RuleYaml> rules;
        public List<KnownStructureYaml> knownStructures;
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
        public List<SearchEffect> searchEffects;
        public List<String> categories;
        public RuleBodyYaml rule;
        public ValidationYaml validation;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class KnownStructureYaml {
        public String id;
        public String domainId;
        public String pattern;
        public RecognitionYaml recognition;
        public List<String> requiredAssumptions;
        public List<String> consequenceIds;
        public List<String> enabledRulePackIds;
        public List<String> compatibleBackendIds;
        public KnownStructureEvidence minimumEvidence;
        public String sourceProject;
        public String license;
        public String sourceUrl;
        public String sourceVersion;
        public String sourceReference;
        public String translationNotes;
    }

    public enum RecognitionYaml {
        EXACT {
            @Override
            RecognitionProfile profile() {
                return RecognitionProfile.exact();
            }
        },
        ARITHMETIC_AC {
            @Override
            RecognitionProfile profile() {
                return RecognitionProfile.arithmeticAc();
            }
        },
        ALGEBRAIC_AC {
            @Override
            RecognitionProfile profile() {
                return RecognitionProfile.algebraicAc();
            }
        };

        abstract RecognitionProfile profile();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RuleBodyYaml {
        public String from;
        public String to;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ValidationYaml {
        public List<ValidationExampleYaml> examples;
        public List<ValidationExampleYaml> counterexamples;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ValidationExampleYaml {
        public String from;
        public String to;
    }
}
