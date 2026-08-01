package de.regelsuche.evolution;

import de.regelsuche.json.JsonWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Canonical, bounded and serializable topology for an evolved rewrite program.
 *
 * <p>The plan contains only stable semantic data. Runtime transformation engines,
 * predicates and comparators are supplied by {@link EvolutionRewriteProgramCompiler}
 * after the referenced genome has passed its ordinary preflight validation.</p>
 */
public record EvolutionRewriteProgramPlan(
    String schema,
    String genomeHash,
    Node root,
    int maxNodes,
    int maxDepth,
    String alphaStructuralHash,
    String contentHash
) {
    public static final String SCHEMA = "regelsuche.evolution-rewrite-program-plan/v1";
    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9_-]{2,127}");
    private static final int ABSOLUTE_MAX_NODES = 1024;
    private static final int ABSOLUTE_MAX_DEPTH = 128;

    public EvolutionRewriteProgramPlan {
        if (!SCHEMA.equals(schema)) {
            throw new IllegalArgumentException(
                "unsupported evolution rewrite-program plan schema");
        }
        EvolutionGenome.requireSha256(genomeHash, "genomeHash");
        Objects.requireNonNull(root, "root");
        validateBounds(maxNodes, maxDepth);
        TreeFacts facts = inspect(root, maxNodes, maxDepth);
        requirePreferredGenesAreReferenced(facts);
        EvolutionGenome.requireSha256(alphaStructuralHash, "alphaStructuralHash");
        EvolutionGenome.requireSha256(contentHash, "contentHash");

        String expectedAlpha = EvolutionGenome.hash(alphaMaterial(
            root, maxNodes, maxDepth));
        if (!expectedAlpha.equals(alphaStructuralHash)) {
            throw new IllegalArgumentException(
                "alphaStructuralHash does not match program topology");
        }
        String expectedContent = EvolutionGenome.hash(canonicalPayload(
            genomeHash, root, maxNodes, maxDepth, alphaStructuralHash));
        if (!expectedContent.equals(contentHash)) {
            throw new IllegalArgumentException(
                "contentHash does not match program-plan payload");
        }
    }

    public static EvolutionRewriteProgramPlan create(
        EvolutionGenome genome,
        Node root,
        int maxNodes,
        int maxDepth
    ) {
        Objects.requireNonNull(genome, "genome");
        Objects.requireNonNull(root, "root");
        validateBounds(maxNodes, maxDepth);
        TreeFacts facts = inspect(root, maxNodes, maxDepth);
        requirePreferredGenesAreReferenced(facts);
        String alphaHash = EvolutionGenome.hash(alphaMaterial(
            root, maxNodes, maxDepth));
        String payload = canonicalPayload(
            genome.contentHash(), root, maxNodes, maxDepth, alphaHash);
        return new EvolutionRewriteProgramPlan(
            SCHEMA,
            genome.contentHash(),
            root,
            maxNodes,
            maxDepth,
            alphaHash,
            EvolutionGenome.hash(payload));
    }

    public int nodeCount() {
        return inspect(root, maxNodes, maxDepth).nodeCount();
    }

    public int actualDepth() {
        return inspect(root, maxNodes, maxDepth).actualDepth();
    }

    public List<String> referencedGeneIds() {
        return inspect(root, maxNodes, maxDepth).referencedGeneIds();
    }

    public String toCanonicalJson() {
        return render(
            genomeHash,
            root,
            maxNodes,
            maxDepth,
            alphaStructuralHash,
            contentHash);
    }

    /** Human-readable representation used in evidence and review surfaces. */
    public String toReadableProgram() {
        StringBuilder output = new StringBuilder();
        appendReadable(output, root, 0);
        return output.toString();
    }

    public sealed interface Node permits
            Source,
            Choice,
            FirstApplicable,
            Sequence,
            Repeat,
            Require,
            Prioritize,
            Prune {
        String nodeId();
    }

    /** One ordinary AST rewrite engine containing the referenced genome rules. */
    public record Source(String nodeId, List<String> geneIds) implements Node {
        public Source {
            nodeId = normalizeId(nodeId, "nodeId");
            geneIds = immutableIds(geneIds, "geneIds", true);
        }
    }

    /** Deterministic union of every alternative. */
    public record Choice(String nodeId, List<Node> alternatives) implements Node {
        public Choice {
            nodeId = normalizeId(nodeId, "nodeId");
            alternatives = immutableNodes(alternatives, "alternatives");
        }
    }

    /** Select the first alternative that yields at least one candidate. */
    public record FirstApplicable(String nodeId, List<Node> alternatives)
            implements Node {
        public FirstApplicable {
            nodeId = normalizeId(nodeId, "nodeId");
            alternatives = immutableNodes(alternatives, "alternatives");
        }
    }

    /** Feed every candidate produced by one step into the next step. */
    public record Sequence(String nodeId, List<Node> steps) implements Node {
        public Sequence {
            nodeId = normalizeId(nodeId, "nodeId");
            steps = immutableNodes(steps, "steps");
        }
    }

    /** Bounded repetition; the interpreter retains endpoints in the declared range. */
    public record Repeat(
        String nodeId,
        Node body,
        int minIterations,
        int maxIterations
    ) implements Node {
        public Repeat {
            nodeId = normalizeId(nodeId, "nodeId");
            Objects.requireNonNull(body, "body");
            if (minIterations < 1 || maxIterations < minIterations
                    || maxIterations > 1024) {
                throw new IllegalArgumentException(
                    "repeat requires 1 <= minIterations <= maxIterations <= 1024");
            }
        }
    }

    /** Hard, serializable candidate condition. */
    public record Require(
        String nodeId,
        Node body,
        Requirement requirement
    ) implements Node {
        public Require {
            nodeId = normalizeId(nodeId, "nodeId");
            Objects.requireNonNull(body, "body");
            Objects.requireNonNull(requirement, "requirement");
        }
    }

    /** Soft, serializable candidate ordering. */
    public record Prioritize(
        String nodeId,
        Node body,
        Priority priority
    ) implements Node {
        public Prioritize {
            nodeId = normalizeId(nodeId, "nodeId");
            Objects.requireNonNull(body, "body");
            Objects.requireNonNull(priority, "priority");
        }
    }

    /** Explicitly incomplete candidate truncation. */
    public record Prune(
        String nodeId,
        Node body,
        int maxCandidates,
        String reason
    ) implements Node {
        public Prune {
            nodeId = normalizeId(nodeId, "nodeId");
            Objects.requireNonNull(body, "body");
            if (maxCandidates < 1 || maxCandidates > 1_000_000) {
                throw new IllegalArgumentException(
                    "maxCandidates must be in [1,1000000]");
            }
            reason = normalizeText(reason, "reason");
        }
    }

    public enum RequirementKind {
        EQUIVALENCE_PRESERVING_BY_CONSTRUCTION,
        ASSUMPTION_FREE,
        MAX_ESTIMATED_COST_DELTA,
        MAX_PRIMITIVE_STEPS
    }

    /**
     * The threshold is zero for boolean requirements, a signed cost ceiling for
     * {@code MAX_ESTIMATED_COST_DELTA}, and a positive step ceiling for
     * {@code MAX_PRIMITIVE_STEPS}.
     */
    public record Requirement(RequirementKind kind, int threshold) {
        public Requirement {
            Objects.requireNonNull(kind, "kind");
            switch (kind) {
                case EQUIVALENCE_PRESERVING_BY_CONSTRUCTION, ASSUMPTION_FREE -> {
                    if (threshold != 0) {
                        throw new IllegalArgumentException(
                            kind + " requires threshold=0");
                    }
                }
                case MAX_ESTIMATED_COST_DELTA -> {
                    if (threshold < -1_000_000 || threshold > 1_000_000) {
                        throw new IllegalArgumentException(
                            "estimated-cost threshold is out of range");
                    }
                }
                case MAX_PRIMITIVE_STEPS -> {
                    if (threshold < 1 || threshold > 1024) {
                        throw new IllegalArgumentException(
                            "primitive-step threshold must be in [1,1024]");
                    }
                }
            }
        }

        public static Requirement equivalencePreservingByConstruction() {
            return new Requirement(
                RequirementKind.EQUIVALENCE_PRESERVING_BY_CONSTRUCTION, 0);
        }

        public static Requirement assumptionFree() {
            return new Requirement(RequirementKind.ASSUMPTION_FREE, 0);
        }

        public static Requirement maxEstimatedCostDelta(int threshold) {
            return new Requirement(
                RequirementKind.MAX_ESTIMATED_COST_DELTA, threshold);
        }

        public static Requirement maxPrimitiveSteps(int threshold) {
            return new Requirement(RequirementKind.MAX_PRIMITIVE_STEPS, threshold);
        }
    }

    public enum PriorityKind {
        ESTIMATED_COST_THEN_RULE,
        PREFERRED_GENE_ORDER
    }

    public record Priority(PriorityKind kind, List<String> preferredGeneIds) {
        public Priority {
            Objects.requireNonNull(kind, "kind");
            preferredGeneIds = immutableIds(
                preferredGeneIds, "preferredGeneIds", false);
            if (kind == PriorityKind.ESTIMATED_COST_THEN_RULE
                    && !preferredGeneIds.isEmpty()) {
                throw new IllegalArgumentException(
                    "estimated-cost priority must not declare preferred genes");
            }
            if (kind == PriorityKind.PREFERRED_GENE_ORDER
                    && preferredGeneIds.isEmpty()) {
                throw new IllegalArgumentException(
                    "preferred-gene priority requires at least one gene");
            }
        }

        public static Priority estimatedCostThenRule() {
            return new Priority(PriorityKind.ESTIMATED_COST_THEN_RULE, List.of());
        }

        public static Priority preferredGeneOrder(List<String> geneIds) {
            return new Priority(PriorityKind.PREFERRED_GENE_ORDER, geneIds);
        }
    }

    private static void validateBounds(int maxNodes, int maxDepth) {
        if (maxNodes < 1 || maxNodes > ABSOLUTE_MAX_NODES) {
            throw new IllegalArgumentException(
                "maxNodes must be in [1," + ABSOLUTE_MAX_NODES + "]");
        }
        if (maxDepth < 1 || maxDepth > ABSOLUTE_MAX_DEPTH) {
            throw new IllegalArgumentException(
                "maxDepth must be in [1," + ABSOLUTE_MAX_DEPTH + "]");
        }
    }

    private static TreeFacts inspect(Node root, int maxNodes, int maxDepth) {
        Set<Node> identities = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<Node> active = Collections.newSetFromMap(new IdentityHashMap<>());
        LinkedHashSet<String> nodeIds = new LinkedHashSet<>();
        LinkedHashSet<String> geneIds = new LinkedHashSet<>();
        List<Priority> priorities = new ArrayList<>();
        int[] count = {0};
        int[] depth = {0};
        inspectNode(
            root,
            1,
            maxNodes,
            maxDepth,
            identities,
            active,
            nodeIds,
            geneIds,
            priorities,
            count,
            depth);
        return new TreeFacts(
            count[0],
            depth[0],
            List.copyOf(geneIds),
            List.copyOf(priorities));
    }

    private static void inspectNode(
        Node node,
        int currentDepth,
        int maxNodes,
        int maxDepth,
        Set<Node> identities,
        Set<Node> active,
        Set<String> nodeIds,
        Set<String> geneIds,
        List<Priority> priorities,
        int[] count,
        int[] actualDepth
    ) {
        Objects.requireNonNull(node, "program node");
        if (!active.add(node)) {
            throw new IllegalArgumentException("program topology contains a cycle");
        }
        if (!identities.add(node)) {
            throw new IllegalArgumentException(
                "program topology reuses one node instance");
        }
        if (!nodeIds.add(node.nodeId())) {
            throw new IllegalArgumentException(
                "program node IDs must be unique: " + node.nodeId());
        }
        count[0]++;
        actualDepth[0] = Math.max(actualDepth[0], currentDepth);
        if (count[0] > maxNodes) {
            throw new IllegalArgumentException(
                "program topology exceeds maxNodes=" + maxNodes);
        }
        if (currentDepth > maxDepth) {
            throw new IllegalArgumentException(
                "program topology exceeds maxDepth=" + maxDepth);
        }

        if (node instanceof Source source) {
            geneIds.addAll(source.geneIds());
        } else if (node instanceof Prioritize prioritize) {
            priorities.add(prioritize.priority());
        }
        for (Node child : children(node)) {
            inspectNode(
                child,
                currentDepth + 1,
                maxNodes,
                maxDepth,
                identities,
                active,
                nodeIds,
                geneIds,
                priorities,
                count,
                actualDepth);
        }
        active.remove(node);
    }

    private static List<Node> children(Node node) {
        if (node instanceof Choice choice) {
            return choice.alternatives();
        }
        if (node instanceof FirstApplicable firstApplicable) {
            return firstApplicable.alternatives();
        }
        if (node instanceof Sequence sequence) {
            return sequence.steps();
        }
        if (node instanceof Repeat repeat) {
            return List.of(repeat.body());
        }
        if (node instanceof Require require) {
            return List.of(require.body());
        }
        if (node instanceof Prioritize prioritize) {
            return List.of(prioritize.body());
        }
        if (node instanceof Prune prune) {
            return List.of(prune.body());
        }
        return List.of();
    }

    private static void requirePreferredGenesAreReferenced(TreeFacts facts) {
        Set<String> referenced = Set.copyOf(facts.referencedGeneIds());
        for (Priority priority : facts.priorities()) {
            if (priority.kind() != PriorityKind.PREFERRED_GENE_ORDER) {
                continue;
            }
            for (String geneId : priority.preferredGeneIds()) {
                if (!referenced.contains(geneId)) {
                    throw new IllegalArgumentException(
                        "preferred gene is absent from program sources: " + geneId);
                }
            }
        }
    }

    private static String canonicalPayload(
        String genomeHash,
        Node root,
        int maxNodes,
        int maxDepth,
        String alphaHash
    ) {
        return render(genomeHash, root, maxNodes, maxDepth, alphaHash, null);
    }

    private static String render(
        String genomeHash,
        Node root,
        int maxNodes,
        int maxDepth,
        String alphaHash,
        String contentHash
    ) {
        JsonWriter json = new JsonWriter().beginObject()
            .property("schema", SCHEMA)
            .property("genomeHash", genomeHash)
            .property("maxNodes", maxNodes)
            .property("maxDepth", maxDepth)
            .object("root", object -> writeNode(object, root))
            .property("alphaStructuralHash", alphaHash);
        if (contentHash != null) {
            json.property("contentHash", contentHash);
        }
        return json.endObject().toString();
    }

    private static void writeNode(JsonWriter json, Node node) {
        json.property("nodeType", nodeType(node))
            .property("nodeId", node.nodeId());
        if (node instanceof Source source) {
            json.stringArray("geneIds", source.geneIds());
        } else if (node instanceof Choice choice) {
            writeChildren(json, "alternatives", choice.alternatives());
        } else if (node instanceof FirstApplicable firstApplicable) {
            writeChildren(json, "alternatives", firstApplicable.alternatives());
        } else if (node instanceof Sequence sequence) {
            writeChildren(json, "steps", sequence.steps());
        } else if (node instanceof Repeat repeat) {
            json.property("minIterations", repeat.minIterations())
                .property("maxIterations", repeat.maxIterations())
                .object("body", object -> writeNode(object, repeat.body()));
        } else if (node instanceof Require require) {
            json.object("requirement", object -> object
                    .property("kind", require.requirement().kind().name())
                    .property("threshold", require.requirement().threshold()))
                .object("body", object -> writeNode(object, require.body()));
        } else if (node instanceof Prioritize prioritize) {
            json.object("priority", object -> object
                    .property("kind", prioritize.priority().kind().name())
                    .stringArray(
                        "preferredGeneIds",
                        prioritize.priority().preferredGeneIds()))
                .object("body", object -> writeNode(object, prioritize.body()));
        } else if (node instanceof Prune prune) {
            json.property("maxCandidates", prune.maxCandidates())
                .property("reason", prune.reason())
                .object("body", object -> writeNode(object, prune.body()));
        }
    }

    private static void writeChildren(
        JsonWriter json,
        String property,
        List<Node> children
    ) {
        json.array(property, array -> children.forEach(child ->
            array.objectValue(object -> writeNode(object, child))));
    }

    private static String nodeType(Node node) {
        if (node instanceof Source) {
            return "SOURCE";
        }
        if (node instanceof Choice) {
            return "CHOICE";
        }
        if (node instanceof FirstApplicable) {
            return "FIRST_APPLICABLE";
        }
        if (node instanceof Sequence) {
            return "SEQUENCE";
        }
        if (node instanceof Repeat) {
            return "REPEAT";
        }
        if (node instanceof Require) {
            return "REQUIRE";
        }
        if (node instanceof Prioritize) {
            return "PRIORITIZE";
        }
        return "PRUNE";
    }

    private static String alphaMaterial(Node root, int maxNodes, int maxDepth) {
        List<String> stableGeneOrder = inspect(root, maxNodes, maxDepth)
            .referencedGeneIds().stream()
            .sorted()
            .toList();
        AlphaContext context = new AlphaContext(stableGeneOrder);
        return SCHEMA
            + "\nmaxNodes=" + maxNodes
            + "\nmaxDepth=" + maxDepth
            + "\nroot=" + alphaNode(root, context);
    }

    private static String alphaNode(Node node, AlphaContext context) {
        if (node instanceof Source source) {
            return "SOURCE(" + source.geneIds().stream()
                .map(context::gene)
                .toList() + ")";
        }
        if (node instanceof Choice choice) {
            return "CHOICE" + alphaChildren(choice.alternatives(), context);
        }
        if (node instanceof FirstApplicable firstApplicable) {
            return "FIRST_APPLICABLE"
                + alphaChildren(firstApplicable.alternatives(), context);
        }
        if (node instanceof Sequence sequence) {
            return "SEQUENCE" + alphaChildren(sequence.steps(), context);
        }
        if (node instanceof Repeat repeat) {
            return "REPEAT(" + repeat.minIterations() + ","
                + repeat.maxIterations() + ","
                + alphaNode(repeat.body(), context) + ")";
        }
        if (node instanceof Require require) {
            return "REQUIRE(" + require.requirement().kind().name() + ","
                + require.requirement().threshold() + ","
                + alphaNode(require.body(), context) + ")";
        }
        if (node instanceof Prioritize prioritize) {
            List<String> preferred = prioritize.priority().preferredGeneIds().stream()
                .map(context::gene)
                .toList();
            return "PRIORITIZE(" + prioritize.priority().kind().name() + ","
                + preferred + ","
                + alphaNode(prioritize.body(), context) + ")";
        }
        Prune prune = (Prune) node;
        return "PRUNE(" + prune.maxCandidates() + ","
            + alphaNode(prune.body(), context) + ")";
    }

    private static String alphaChildren(List<Node> nodes, AlphaContext context) {
        return nodes.stream().map(node -> alphaNode(node, context)).toList().toString();
    }

    private static void appendReadable(
        StringBuilder output,
        Node node,
        int indentation
    ) {
        String prefix = "  ".repeat(indentation);
        if (node instanceof Source source) {
            output.append(prefix)
                .append("source ")
                .append(source.geneIds())
                .append("\n");
            return;
        }
        if (node instanceof Repeat repeat) {
            output.append(prefix)
                .append("repeat ")
                .append(repeat.minIterations())
                .append("..")
                .append(repeat.maxIterations())
                .append(" {\n");
            appendReadable(output, repeat.body(), indentation + 1);
            output.append(prefix).append("}\n");
            return;
        }
        if (node instanceof Require require) {
            output.append(prefix)
                .append("require ")
                .append(require.requirement().kind())
                .append("(")
                .append(require.requirement().threshold())
                .append(") {\n");
            appendReadable(output, require.body(), indentation + 1);
            output.append(prefix).append("}\n");
            return;
        }
        if (node instanceof Prioritize prioritize) {
            output.append(prefix)
                .append("prioritize ")
                .append(prioritize.priority().kind())
                .append(prioritize.priority().preferredGeneIds())
                .append(" {\n");
            appendReadable(output, prioritize.body(), indentation + 1);
            output.append(prefix).append("}\n");
            return;
        }
        if (node instanceof Prune prune) {
            output.append(prefix)
                .append("prune ")
                .append(prune.maxCandidates())
                .append(" because \"")
                .append(prune.reason())
                .append("\" {\n");
            appendReadable(output, prune.body(), indentation + 1);
            output.append(prefix).append("}\n");
            return;
        }

        String keyword;
        List<Node> children;
        if (node instanceof Choice choice) {
            keyword = "choice";
            children = choice.alternatives();
        } else if (node instanceof FirstApplicable firstApplicable) {
            keyword = "firstApplicable";
            children = firstApplicable.alternatives();
        } else {
            keyword = "sequence";
            children = ((Sequence) node).steps();
        }
        output.append(prefix).append(keyword).append(" {\n");
        for (Node child : children) {
            appendReadable(output, child, indentation + 1);
        }
        output.append(prefix).append("}\n");
    }

    private static List<Node> immutableNodes(List<Node> values, String name) {
        Objects.requireNonNull(values, name);
        List<Node> copy = values.stream()
            .map(value -> Objects.requireNonNull(value, "program node"))
            .toList();
        if (copy.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        return copy;
    }

    private static List<String> immutableIds(
        List<String> values,
        String name,
        boolean requireNonEmpty
    ) {
        Objects.requireNonNull(values, name);
        List<String> copy = values.stream()
            .map(value -> normalizeId(value, name + " entry"))
            .toList();
        if (requireNonEmpty && copy.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        if (new LinkedHashSet<>(copy).size() != copy.size()) {
            throw new IllegalArgumentException(name + " must not contain duplicates");
        }
        return copy;
    }

    private static String normalizeId(String value, String name) {
        String normalized = normalizeText(value, name)
            .toLowerCase(java.util.Locale.ROOT);
        if (!ID.matcher(normalized).matches()) {
            throw new IllegalArgumentException("invalid " + name + ": " + value);
        }
        return normalized;
    }

    private static String normalizeText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim().replaceAll("\\s+", " ");
    }

    private record TreeFacts(
        int nodeCount,
        int actualDepth,
        List<String> referencedGeneIds,
        List<Priority> priorities
    ) {
    }

    private static final class AlphaContext {
        private final Map<String, String> genes = new LinkedHashMap<>();

        private AlphaContext(List<String> stableGeneOrder) {
            int index = 0;
            for (String geneId : stableGeneOrder) {
                genes.put(geneId, "G" + index++);
            }
        }

        private String gene(String geneId) {
            String alias = genes.get(geneId);
            if (alias == null) {
                throw new IllegalArgumentException(
                    "alpha context is missing referenced gene: " + geneId);
            }
            return alias;
        }
    }
}
