package de.regelsuche.literature;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import de.regelsuche.discovery.DiscoveredTransformation;
import de.regelsuche.discovery.TransformationStep;
import de.regelsuche.json.JsonWriter;
import de.regelsuche.knowledge.RuleInventoryFingerprint;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.PatternRewriteRule;
import de.regelsuche.transform.TransformationEngine;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Reviewed bibliography kept separately from the executable inventory and proof artifacts. */
public final class RuleLiteratureCatalog {
    public static final String SCHEMA = "regelsuche.rule-literature/v1";
    private static final int MAX_BYTES = 1 << 20;
    private static final JsonMapper JSON = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
        .build();
    private static final RuleLiteratureCatalog EMPTY = new RuleLiteratureCatalog(List.of(), List.of());

    private final Map<String, Source> sources;
    private final Map<String, List<Link>> byRule;

    private RuleLiteratureCatalog(List<Source> sources, List<Link> links) {
        this.sources = sources.stream().collect(Collectors.toUnmodifiableMap(Source::id, source -> source,
            (first, duplicate) -> { throw new IllegalArgumentException("duplicate literature source id"); }));
        Set<Link> unique = new HashSet<>();
        for (Link link : links) {
            if (!unique.add(link) || !this.sources.containsKey(link.sourceId())) {
                throw new IllegalArgumentException("duplicate literature link or unknown source id");
            }
        }
        byRule = links.stream().filter(Link::released).collect(Collectors.groupingBy(Link::ruleId));
    }

    public static RuleLiteratureCatalog empty() {
        return EMPTY;
    }

    public static RuleLiteratureCatalog configured() {
        String file = System.getProperty("regelsuche.literature.catalog", System.getenv("REGELSUCHE_LITERATURE_CATALOG"));
        if (file == null || file.isBlank()) return empty();
        try {
            return read(Path.of(file));
        } catch (IOException exception) {
            throw new UncheckedIOException("cannot read configured literature catalog", exception);
        }
    }

    public static RuleLiteratureCatalog read(Path path) throws IOException {
        try (var input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("literature catalog exceeds 1 MiB");
            CatalogFile file = JSON.readValue(bytes, CatalogFile.class);
            if (file == null || !SCHEMA.equals(file.schema()) || file.sources() == null || file.links() == null) {
                throw new IllegalArgumentException("invalid literature catalog schema");
            }
            return new RuleLiteratureCatalog(file.sources(), file.links());
        } catch (com.fasterxml.jackson.core.JacksonException exception) {
            throw new IllegalArgumentException("invalid literature catalog", exception);
        }
    }

    public Numbering numbering() {
        return new Numbering();
    }

    /**
     * Declarative rule revisions are bound by their pattern and recognition policy.
     * Other engines can use explicit occurrence links.
     */
    public static Map<String, String> captureRuleRevisions(TransformationEngine engine) {
        if (!(engine instanceof AstRewriteTransformationEngine ast)
                || engine.getClass() != AstRewriteTransformationEngine.class) return Map.of();
        var revisions = new HashMap<String, String>();
        var seen = new HashSet<String>();
        for (var rule : ast.rules()) {
            if (!seen.add(rule.id())) revisions.remove(rule.id());
            else if (rule.getClass() == PatternRewriteRule.class) {
                revisions.put(rule.id(), RuleInventoryFingerprint.ruleContentHash(rule));
            }
        }
        return Map.copyOf(revisions);
    }

    public static String occurrence(DiscoveredTransformation path, TransformationStep step) {
        String json = new JsonWriter().beginObject()
            .property("schema", "regelsuche.literature-occurrence/v1")
            .property("pathId", path.id()).property("pathHash", path.canonicalHash())
            .property("index", step.index()).property("ruleId", step.ruleId())
            .property("kind", step.ruleKind().name()).property("equivalencePreserving", step.equivalencePreserving())
            .property("before", step.beforeExpression()).property("after", step.afterExpression())
            .stringArray("assumptions", step.assumptions())
            .property("revision", step.ruleRevision() == null ? "" : step.ruleRevision())
            .property("execution", step.execution() == null ? "" : step.execution().contentHash())
            .endObject().toString();
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(json.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record CatalogFile(String schema, List<Source> sources, List<Link> links) { }

    public record Source(String id, List<String> authors, String title, Integer year,
            String identifier, String version, String locator, String url) {
        public Source {
            requireText(id, "id", 128);
            authors = List.copyOf(authors);
            if (authors.isEmpty()) throw new IllegalArgumentException("literature source requires authors");
            authors.forEach(author -> requireText(author, "author", 300));
            requireText(title, "title", 1000);
            if (year != null && (year < 1 || year > 9999)) throw new IllegalArgumentException("invalid literature year");
            requireText(identifier, "identifier", 500);
            requireText(version, "version", 300);
            requireText(locator, "locator", 500);
            requireText(url, "url", 2000);
            URI uri = URI.create(url);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) {
                throw new IllegalArgumentException("literature source URL must use HTTPS");
            }
            url = uri.toASCIIString();
        }

        public String description() {
            return String.join(", ", authors) + (year == null ? "" : " (" + year + ")")
                + ". " + title + ". " + identifier + " · " + version + " · " + locator;
        }
    }

    public enum Relation {
        EXACT("gleiche Aussage"),
        EQUIVALENT("äquivalent"),
        SOURCE_SPECIAL_CASE("Quelle ist ein Spezialfall"),
        SOURCE_GENERALIZATION("Quelle ist eine Verallgemeinerung"),
        RELATED("verwandt");

        private final String label;
        Relation(String label) { this.label = label; }
        public String label() { return label; }
    }

    public enum Role {
        RULE_SOURCE("Regelquelle"),
        USED_AS_LEMMA("Verwendetes Lemma"),
        FOUND_AFTER_FREEZE("Literaturabgleich nach der Herleitung");

        private final String label;
        Role(String label) { this.label = label; }
        public String label() { return label; }
    }

    public record Link(String ruleId, String ruleRevision, String occurrence, String sourceId, Relation relation,
            Role role, List<String> assumptions, String reviewedBy, String reviewedOn, String rationale, boolean released) {
        public Link {
            requireText(ruleId, "ruleId", 300);
            requireText(sourceId, "sourceId", 128);
            Objects.requireNonNull(relation, "relation");
            Objects.requireNonNull(role, "role");
            if (ruleRevision != null) requireHash(ruleRevision);
            if (occurrence != null) requireHash(occurrence);
            if (occurrence == null && (ruleRevision == null || role != Role.RULE_SOURCE)) {
                throw new IllegalArgumentException("literature link needs an exact rule revision or bound occurrence");
            }
            assumptions = List.copyOf(assumptions);
            assumptions.forEach(assumption -> requireText(assumption, "assumption", 1000));
            requireText(reviewedBy, "reviewedBy", 300);
            requireText(reviewedOn, "reviewedOn", 10);
            LocalDate.parse(reviewedOn);
            requireText(rationale, "rationale", 2000);
        }
    }

    public record Citation(int number, Source source, Role role, Relation relation, List<String> assumptions, String rationale) {
        public Citation {
            assumptions = List.copyOf(assumptions);
        }
    }

    /** Citation numbers are local to one replay or document. */
    public final class Numbering {
        private final Map<Source, Integer> bibliography = new LinkedHashMap<>();

        public List<Citation> forStep(DiscoveredTransformation path, TransformationStep step) {
            List<Link> links = byRule.getOrDefault(step.ruleId(), List.of());
            if (links.isEmpty()) return List.of();
            String digest = links.stream().anyMatch(link -> link.occurrence() != null) ? occurrence(path, step) : null;
            List<Citation> citations = new ArrayList<>();
            for (Link link : links) {
                if (link.occurrence() != null && !link.occurrence().equals(digest)) continue;
                if (link.ruleRevision() != null && !link.ruleRevision().equals(step.ruleRevision())) continue;
                Source source = sources.get(link.sourceId());
                int number = bibliography.computeIfAbsent(source, key -> bibliography.size() + 1);
                Citation citation = new Citation(number, source, link.role(), link.relation(), link.assumptions(), link.rationale());
                if (!citations.contains(citation)) citations.add(citation);
            }
            return List.copyOf(citations);
        }

        public List<Source> bibliography() {
            return List.copyOf(bibliography.keySet());
        }
    }

    private static void requireHash(String value) {
        if (!value.matches("sha256:[0-9a-f]{64}")) throw new IllegalArgumentException("invalid literature binding digest");
    }

    private static void requireText(String value, String field, int limit) {
        if (value == null || value.isBlank() || value.length() > limit
                || value.chars().anyMatch(Character::isISOControl)
                || !StandardCharsets.UTF_8.newEncoder().canEncode(value)) {
            throw new IllegalArgumentException("invalid literature field " + field);
        }
    }
}
