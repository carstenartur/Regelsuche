package de.regelsuche.evolution;

import de.regelsuche.json.JsonWriter;
import de.regelsuche.transform.PatternExpr;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Hard preflight gate for genomes and individual mutation steps. */
public final class EvolutionGenomeValidator {
    public static final String SCHEMA = "regelsuche.evolution-preflight/v1";

    public ValidationReport validate(EvolutionGenome genome) {
        Objects.requireNonNull(genome, "genome");
        List<ValidationBlocker> blockers = new ArrayList<>();
        validateGuardPolicy(genome, blockers);
        validateProgramBudget(genome, blockers);
        validateFeatures(genome, blockers);
        validateRewrites(genome, blockers);
        return report(genome, blockers);
    }

    public ValidationReport validateMutation(
        EvolutionGenome parent,
        EvolutionGenome child,
        EvolutionMutationKind kind
    ) {
        Objects.requireNonNull(parent, "parent");
        Objects.requireNonNull(child, "child");
        Objects.requireNonNull(kind, "kind");
        List<ValidationBlocker> blockers = new ArrayList<>(validate(child).blockers());

        if (parent.contentHash().equals(child.contentHash())) {
            blockers.add(blocker(
                BlockerCode.IDENTITY_MUTATION,
                "mutation",
                "child content hash equals parent"));
        }
        if (parent.alphaStructuralHash().equals(child.alphaStructuralHash())) {
            blockers.add(blocker(
                BlockerCode.ALPHA_EQUIVALENT_MUTATION,
                "mutation",
                "child is alpha-equivalent to parent"));
        }
        if (!child.seedGenomeHashes().contains(parent.contentHash())) {
            blockers.add(blocker(
                BlockerCode.MISSING_PARENT_LINEAGE,
                "seedGenomeHashes",
                "parent content hash is not recorded"));
        }
        requireUnchanged(parent.objective(), child.objective(),
            BlockerCode.OBJECTIVE_CHANGED, "objective", blockers);
        requireUnchanged(parent.trainingScope(), child.trainingScope(),
            BlockerCode.TRAINING_SCOPE_CHANGED, "trainingScope", blockers);
        requireUnchanged(parent.guardPolicy(), child.guardPolicy(),
            BlockerCode.GUARD_POLICY_CHANGED, "guardPolicy", blockers);
        requireUnchanged(parent.budget(), child.budget(),
            BlockerCode.BUDGET_CHANGED, "budget", blockers);
        requireUnchanged(parent.requiredCapabilities(), child.requiredCapabilities(),
            BlockerCode.CAPABILITY_SET_CHANGED, "requiredCapabilities", blockers);

        validateMutationShape(parent, child, kind, blockers);
        validateNoGuardWeakening(parent, child, kind, blockers);
        return report(child, blockers);
    }

    private static void validateGuardPolicy(
        EvolutionGenome genome,
        List<ValidationBlocker> blockers
    ) {
        EvolutionGenome.GuardPolicy policy = genome.guardPolicy();
        if (!policy.rejectCycles()) {
            blockers.add(blocker(BlockerCode.CYCLE_GUARD_DISABLED,
                "guardPolicy.rejectCycles", "cycle rejection is mandatory"));
        }
        if (!policy.rejectUnboundedGrowth()) {
            blockers.add(blocker(BlockerCode.GROWTH_GUARD_DISABLED,
                "guardPolicy.rejectUnboundedGrowth", "growth rejection is mandatory"));
        }
        if (!policy.requireApplicabilityChecks()) {
            blockers.add(blocker(BlockerCode.APPLICABILITY_GUARD_DISABLED,
                "guardPolicy.requireApplicabilityChecks",
                "applicability checks are mandatory"));
        }
        if (!policy.enforceDuplicateSuppression()) {
            blockers.add(blocker(BlockerCode.DUPLICATE_GUARD_DISABLED,
                "guardPolicy.enforceDuplicateSuppression",
                "duplicate suppression is mandatory"));
        }
        if (!policy.deterministicTieBreaking()) {
            blockers.add(blocker(BlockerCode.NONDETERMINISTIC_TIE_BREAKING,
                "guardPolicy.deterministicTieBreaking",
                "deterministic tie-breaking is mandatory"));
        }
    }

    private static void validateProgramBudget(
        EvolutionGenome genome,
        List<ValidationBlocker> blockers
    ) {
        if (genome.rewrites().isEmpty()) {
            blockers.add(blocker(BlockerCode.EMPTY_PROGRAM,
                "rewrites", "at least one executable rewrite is required"));
        }
        if (genome.rewrites().size() > genome.budget().maxProgramLength()) {
            blockers.add(blocker(BlockerCode.PROGRAM_LENGTH_EXCEEDED,
                "rewrites",
                genome.rewrites().size() + ">" + genome.budget().maxProgramLength()));
        }
    }

    private static void validateFeatures(
        EvolutionGenome genome,
        List<ValidationBlocker> blockers
    ) {
        if (genome.rankingFeatures().isEmpty()) {
            blockers.add(blocker(BlockerCode.EMPTY_FITNESS,
                "rankingFeatures", "at least one decomposable signal is required"));
        }
        if (genome.objective() == EvolutionGenome.Objective.OPEN_TARGET_OPERATOR) {
            genome.rankingFeatures().stream()
                .filter(feature -> feature.signal().targetDirected())
                .forEach(feature -> blockers.add(blocker(
                    BlockerCode.TARGET_SIGNAL_IN_OPEN_TARGET_GENOME,
                    "rankingFeatures." + feature.signal().name(),
                    "target-directed signal is forbidden for open-target evolution")));
        }
        genome.rankingFeatures().stream()
            .filter(feature -> Integer.signum(feature.weightPermille())
                != feature.signal().expectedSign())
            .forEach(feature -> blockers.add(blocker(
                BlockerCode.INVALID_FEATURE_DIRECTION,
                "rankingFeatures." + feature.signal().name(),
                "weight sign contradicts declared signal direction")));
    }

    private static void validateRewrites(
        EvolutionGenome genome,
        List<ValidationBlocker> blockers
    ) {
        Map<String, ParsedRewrite> parsed = new LinkedHashMap<>();
        for (EvolutionGenome.RewriteGene gene : genome.rewrites()) {
            ParsedRewrite item = parse(gene, blockers);
            if (item != null) {
                parsed.put(gene.geneId(), item);
                validateRewrite(genome, gene, item, blockers);
            }
        }
        validateDuplicatesAndCycles(parsed, blockers);
    }

    private static ParsedRewrite parse(
        EvolutionGenome.RewriteGene gene,
        List<ValidationBlocker> blockers
    ) {
        PatternExpr source;
        PatternExpr target;
        try {
            source = EvolutionGenomeCompiler.parsePattern(gene.sourcePattern());
        } catch (IllegalArgumentException exception) {
            blockers.add(blocker(BlockerCode.UNPARSABLE_SOURCE,
                gene.geneId() + ".sourcePattern", exception.getMessage()));
            return null;
        }
        try {
            target = EvolutionGenomeCompiler.parsePattern(gene.targetPattern());
        } catch (IllegalArgumentException exception) {
            blockers.add(blocker(BlockerCode.UNPARSABLE_TARGET,
                gene.geneId() + ".targetPattern", exception.getMessage()));
            return null;
        }
        return new ParsedRewrite(gene, source, target,
            alphaPair(gene.sourcePattern(), gene.targetPattern()));
    }

    private static void validateRewrite(
        EvolutionGenome genome,
        EvolutionGenome.RewriteGene gene,
        ParsedRewrite parsed,
        List<ValidationBlocker> blockers
    ) {
        Set<String> sourcePlaceholders = EvolutionGenome.placeholders(gene.sourcePattern());
        Set<String> targetPlaceholders = EvolutionGenome.placeholders(gene.targetPattern());
        for (String placeholder : targetPlaceholders) {
            if (!sourcePlaceholders.contains(placeholder)) {
                blockers.add(blocker(BlockerCode.UNBOUND_TARGET_PLACEHOLDER,
                    gene.geneId() + ".targetPattern", placeholder));
            }
        }
        for (EvolutionGenome.AssumptionTemplate assumption : gene.assumptions()) {
            if (assumption.kind() == de.regelsuche.assumption.Assumption.Kind.UNKNOWN
                    || assumption.kind() == de.regelsuche.assumption.Assumption.Kind.DOMAIN
                    || assumption.kind() == de.regelsuche.assumption.Assumption.Kind.CUSTOM) {
                blockers.add(blocker(BlockerCode.UNSUPPORTED_ASSUMPTION_KIND,
                    gene.geneId() + ".assumptions", assumption.kind().name()));
            }
            Set<String> used = new LinkedHashSet<>(
                EvolutionGenome.placeholders(assumption.expression()));
            assumption.symbols().forEach(symbol ->
                used.addAll(EvolutionGenome.placeholders(symbol)));
            for (String placeholder : used) {
                if (!sourcePlaceholders.contains(placeholder)) {
                    blockers.add(blocker(BlockerCode.UNBOUND_ASSUMPTION_PLACEHOLDER,
                        gene.geneId() + ".assumptions", placeholder));
                }
            }
        }
        if (parsed.source().equals(parsed.target())) {
            blockers.add(blocker(BlockerCode.IDENTITY_REWRITE,
                gene.geneId(), "source and target patterns are identical"));
        }

        int sourceNodes = EvolutionGenomeCompiler.nodeCount(parsed.source());
        int targetNodes = EvolutionGenomeCompiler.nodeCount(parsed.target());
        if (sourceNodes > genome.budget().maxAstNodes()
                || targetNodes > genome.budget().maxAstNodes()) {
            blockers.add(blocker(BlockerCode.AST_NODE_BUDGET_EXCEEDED,
                gene.geneId(),
                Math.max(sourceNodes, targetNodes) + ">" + genome.budget().maxAstNodes()));
        }
        int growth = targetNodes - sourceNodes;
        if (growth > gene.maxAstGrowth()) {
            blockers.add(blocker(BlockerCode.GENE_GROWTH_EXCEEDED,
                gene.geneId(), growth + ">" + gene.maxAstGrowth()));
        }
        if (growth > genome.budget().maxAstGrowthPerStep()) {
            blockers.add(blocker(BlockerCode.PROGRAM_GROWTH_EXCEEDED,
                gene.geneId(), growth + ">" + genome.budget().maxAstGrowthPerStep()));
        }
        if (gene.maxApplicationsPerPath() > genome.budget().maxApplicationsPerPath()) {
            blockers.add(blocker(BlockerCode.APPLICATION_BUDGET_EXCEEDED,
                gene.geneId(),
                gene.maxApplicationsPerPath() + ">"
                    + genome.budget().maxApplicationsPerPath()));
        }
        requireObligation(gene, EvolutionGenome.EvidenceObligation.SEMANTIC_VALIDATION,
            blockers);
        requireObligation(gene, EvolutionGenome.EvidenceObligation.COUNTEREXAMPLE_SEARCH,
            blockers);
        requireObligation(gene, EvolutionGenome.EvidenceObligation.PROOF_OR_CERTIFICATE,
            blockers);
        requireObligation(gene, EvolutionGenome.EvidenceObligation.HOLDOUT_EVALUATION,
            blockers);
    }

    private static void requireObligation(
        EvolutionGenome.RewriteGene gene,
        EvolutionGenome.EvidenceObligation obligation,
        List<ValidationBlocker> blockers
    ) {
        if (!gene.evidenceObligations().contains(obligation)) {
            blockers.add(blocker(BlockerCode.MISSING_EVIDENCE_OBLIGATION,
                gene.geneId() + ".evidenceObligations", obligation.name()));
        }
    }

    private static void validateDuplicatesAndCycles(
        Map<String, ParsedRewrite> parsed,
        List<ValidationBlocker> blockers
    ) {
        Map<String, String> pairOwners = new LinkedHashMap<>();
        for (ParsedRewrite rewrite : parsed.values()) {
            String previous = pairOwners.putIfAbsent(rewrite.alphaPair(), rewrite.gene().geneId());
            if (previous != null) {
                blockers.add(blocker(BlockerCode.DUPLICATE_STRUCTURAL_REWRITE,
                    rewrite.gene().geneId(), "alpha-equivalent to " + previous));
            }
        }
        List<ParsedRewrite> items = List.copyOf(parsed.values());
        Map<String, List<CycleEdge>> graph = new LinkedHashMap<>();
        for (ParsedRewrite item : items) {
            graph.computeIfAbsent(item.alphaSource(), ignored -> new ArrayList<>())
                .add(new CycleEdge(item.alphaTarget(), item.gene().geneId()));
        }
        graph.values().forEach(edges -> edges.sort(
            Comparator.comparing(CycleEdge::geneId)
                .thenComparing(CycleEdge::target)));
        for (ParsedRewrite item : items) {
            List<String> returnPath = findPath(
                item.alphaTarget(),
                item.alphaSource(),
                graph,
                new LinkedHashSet<>(),
                items.size());
            if (returnPath != null) {
                List<String> cycle = new ArrayList<>();
                cycle.add(item.gene().geneId());
                cycle.addAll(returnPath);
                blockers.add(blocker(BlockerCode.REWRITE_CYCLE,
                    item.gene().geneId(), "cycle=" + cycle));
            }
        }
    }

    private static List<String> findPath(
        String current,
        String target,
        Map<String, List<CycleEdge>> graph,
        Set<String> visited,
        int remainingDepth
    ) {
        if (current.equals(target)) {
            return List.of();
        }
        if (remainingDepth <= 0 || !visited.add(current)) {
            return null;
        }
        for (CycleEdge edge : graph.getOrDefault(current, List.of())) {
            List<String> suffix = findPath(
                edge.target(),
                target,
                graph,
                new LinkedHashSet<>(visited),
                remainingDepth - 1);
            if (suffix != null) {
                List<String> result = new ArrayList<>();
                result.add(edge.geneId());
                result.addAll(suffix);
                return result;
            }
        }
        return null;
    }

    private static void validateMutationShape(
        EvolutionGenome parent,
        EvolutionGenome child,
        EvolutionMutationKind kind,
        List<ValidationBlocker> blockers
    ) {
        int rewriteDelta = child.rewrites().size() - parent.rewrites().size();
        int featureDelta = child.rankingFeatures().size() - parent.rankingFeatures().size();
        int assumptionDelta = totalAssumptions(child) - totalAssumptions(parent);

        switch (kind) {
            case COMPOSE_REWRITES -> {
                requireDelta(rewriteDelta, 1, "rewrites", blockers);
                requireDelta(featureDelta, 0, "rankingFeatures", blockers);
            }
            case ADD_ASSUMPTION -> {
                requireDelta(rewriteDelta, 0, "rewrites", blockers);
                requireDelta(assumptionDelta, 1, "assumptions", blockers);
                requireDelta(featureDelta, 0, "rankingFeatures", blockers);
            }
            case REMOVE_ASSUMPTION -> {
                requireDelta(rewriteDelta, 0, "rewrites", blockers);
                requireDelta(assumptionDelta, -1, "assumptions", blockers);
                requireDelta(featureDelta, 0, "rankingFeatures", blockers);
            }
            case ADD_RANKING_FEATURE -> {
                requireDelta(rewriteDelta, 0, "rewrites", blockers);
                requireDelta(featureDelta, 1, "rankingFeatures", blockers);
            }
            case REMOVE_RANKING_FEATURE -> {
                requireDelta(rewriteDelta, 0, "rewrites", blockers);
                requireDelta(featureDelta, -1, "rankingFeatures", blockers);
            }
            case GENERALIZE_PLACEHOLDER, SPECIALIZE_PLACEHOLDER, REVERSE_REWRITE -> {
                requireDelta(rewriteDelta, 0, "rewrites", blockers);
                requireDelta(featureDelta, 0, "rankingFeatures", blockers);
                if (changedGeneCount(parent, child) != 1) {
                    blockers.add(blocker(BlockerCode.INVALID_MUTATION_SHAPE,
                        "rewrites", "exactly one rewrite gene must change"));
                }
            }
        }
    }

    private static void validateNoGuardWeakening(
        EvolutionGenome parent,
        EvolutionGenome child,
        EvolutionMutationKind kind,
        List<ValidationBlocker> blockers
    ) {
        Map<String, EvolutionGenome.RewriteGene> children = child.rewrites().stream()
            .collect(java.util.stream.Collectors.toMap(
                EvolutionGenome.RewriteGene::geneId,
                value -> value));
        for (EvolutionGenome.RewriteGene parentGene : parent.rewrites()) {
            EvolutionGenome.RewriteGene childGene = children.get(parentGene.geneId());
            if (childGene == null) {
                continue;
            }
            Set<EvolutionGenome.AssumptionTemplate> removed = new LinkedHashSet<>(
                parentGene.assumptions());
            removed.removeAll(childGene.assumptions());
            if (!removed.isEmpty()) {
                blockers.add(blocker(BlockerCode.GUARD_WEAKENING_REQUIRES_CERTIFICATE,
                    parentGene.geneId() + ".assumptions",
                    "removed=" + removed.stream()
                        .map(EvolutionGenome.AssumptionTemplate::canonicalMaterial)
                        .sorted()
                        .toList()
                        + ", mutation=" + kind.name()));
            }
        }
    }

    private static int totalAssumptions(EvolutionGenome genome) {
        return genome.rewrites().stream()
            .mapToInt(gene -> gene.assumptions().size())
            .sum();
    }

    private static int changedGeneCount(
        EvolutionGenome parent,
        EvolutionGenome child
    ) {
        Map<String, EvolutionGenome.RewriteGene> parentGenes = parent.rewrites().stream()
            .collect(java.util.stream.Collectors.toMap(
                EvolutionGenome.RewriteGene::geneId,
                value -> value));
        Map<String, EvolutionGenome.RewriteGene> childGenes = child.rewrites().stream()
            .collect(java.util.stream.Collectors.toMap(
                EvolutionGenome.RewriteGene::geneId,
                value -> value));
        Set<String> ids = new LinkedHashSet<>(parentGenes.keySet());
        ids.addAll(childGenes.keySet());
        int changed = 0;
        for (String id : ids) {
            if (!Objects.equals(parentGenes.get(id), childGenes.get(id))) {
                changed++;
            }
        }
        return changed;
    }

    private static void requireDelta(
        int actual,
        int expected,
        String location,
        List<ValidationBlocker> blockers
    ) {
        if (actual != expected) {
            blockers.add(blocker(BlockerCode.INVALID_MUTATION_SHAPE,
                location, "delta=" + actual + ", expected=" + expected));
        }
    }

    private static void requireUnchanged(
        Object parent,
        Object child,
        BlockerCode code,
        String location,
        List<ValidationBlocker> blockers
    ) {
        if (!Objects.equals(parent, child)) {
            blockers.add(blocker(code, location, "field cannot change in v1 mutation"));
        }
    }

    private static AlphaPair alphaPair(String source, String target) {
        Map<String, String> mapping = new LinkedHashMap<>();
        java.util.function.UnaryOperator<String> alpha = token -> mapping.computeIfAbsent(
            token,
            ignored -> "?P" + mapping.size());
        String normalizedSource = EvolutionGenome.transformPlaceholders(source, alpha);
        String normalizedTarget = EvolutionGenome.transformPlaceholders(target, alpha);
        PatternExpr sourcePattern = EvolutionGenomeCompiler.parsePattern(normalizedSource);
        PatternExpr targetPattern = EvolutionGenomeCompiler.parsePattern(normalizedTarget);
        return new AlphaPair(
            EvolutionGenomeCompiler.renderPattern(sourcePattern),
            EvolutionGenomeCompiler.renderPattern(targetPattern));
    }

    private static ValidationReport report(
        EvolutionGenome genome,
        List<ValidationBlocker> values
    ) {
        List<ValidationBlocker> blockers = values.stream()
            .filter(Objects::nonNull)
            .distinct()
            .sorted(Comparator
                .comparing((ValidationBlocker item) -> item.code().name())
                .thenComparing(ValidationBlocker::location)
                .thenComparing(ValidationBlocker::detail))
            .toList();
        ValidationStatus status = blockers.isEmpty()
            ? ValidationStatus.ACCEPTED
            : ValidationStatus.REJECTED;
        String payload = renderReport(
            genome.contentHash(),
            genome.alphaStructuralHash(),
            status,
            blockers,
            null);
        return new ValidationReport(
            SCHEMA,
            genome.contentHash(),
            genome.alphaStructuralHash(),
            status,
            blockers,
            EvolutionGenome.hash(payload));
    }

    private static String renderReport(
        String genomeHash,
        String alphaHash,
        ValidationStatus status,
        List<ValidationBlocker> blockers,
        String contentHash
    ) {
        JsonWriter json = new JsonWriter().beginObject()
            .property("schema", SCHEMA)
            .property("genomeHash", genomeHash)
            .property("alphaStructuralHash", alphaHash)
            .property("status", status.name())
            .array("blockers", array -> blockers.forEach(item ->
                array.objectValue(object -> object
                    .property("code", item.code().name())
                    .property("location", item.location())
                    .property("detail", item.detail()))));
        if (contentHash != null) {
            json.property("contentHash", contentHash);
        }
        return json.endObject().toString();
    }

    private static ValidationBlocker blocker(
        BlockerCode code,
        String location,
        String detail
    ) {
        return new ValidationBlocker(code, location, detail);
    }

    public enum ValidationStatus {
        ACCEPTED,
        REJECTED
    }

    public enum BlockerCode {
        EMPTY_PROGRAM,
        PROGRAM_LENGTH_EXCEEDED,
        EMPTY_FITNESS,
        TARGET_SIGNAL_IN_OPEN_TARGET_GENOME,
        INVALID_FEATURE_DIRECTION,
        CYCLE_GUARD_DISABLED,
        GROWTH_GUARD_DISABLED,
        APPLICABILITY_GUARD_DISABLED,
        DUPLICATE_GUARD_DISABLED,
        NONDETERMINISTIC_TIE_BREAKING,
        UNPARSABLE_SOURCE,
        UNPARSABLE_TARGET,
        UNBOUND_TARGET_PLACEHOLDER,
        UNBOUND_ASSUMPTION_PLACEHOLDER,
        UNSUPPORTED_ASSUMPTION_KIND,
        IDENTITY_REWRITE,
        AST_NODE_BUDGET_EXCEEDED,
        GENE_GROWTH_EXCEEDED,
        PROGRAM_GROWTH_EXCEEDED,
        APPLICATION_BUDGET_EXCEEDED,
        MISSING_EVIDENCE_OBLIGATION,
        DUPLICATE_STRUCTURAL_REWRITE,
        REWRITE_CYCLE,
        IDENTITY_MUTATION,
        ALPHA_EQUIVALENT_MUTATION,
        MISSING_PARENT_LINEAGE,
        OBJECTIVE_CHANGED,
        TRAINING_SCOPE_CHANGED,
        GUARD_POLICY_CHANGED,
        BUDGET_CHANGED,
        CAPABILITY_SET_CHANGED,
        INVALID_MUTATION_SHAPE,
        GUARD_WEAKENING_REQUIRES_CERTIFICATE
    }

    public record ValidationBlocker(
        BlockerCode code,
        String location,
        String detail
    ) {
        public ValidationBlocker {
            Objects.requireNonNull(code, "code");
            if (location == null || location.isBlank()) {
                throw new IllegalArgumentException("location must not be blank");
            }
            if (detail == null || detail.isBlank()) {
                throw new IllegalArgumentException("detail must not be blank");
            }
        }
    }

    public record ValidationReport(
        String schema,
        String genomeHash,
        String alphaStructuralHash,
        ValidationStatus status,
        List<ValidationBlocker> blockers,
        String contentHash
    ) {
        public ValidationReport {
            if (!SCHEMA.equals(schema)) {
                throw new IllegalArgumentException("unsupported preflight schema");
            }
            EvolutionGenome.requireSha256(genomeHash, "genomeHash");
            EvolutionGenome.requireSha256(alphaStructuralHash, "alphaStructuralHash");
            Objects.requireNonNull(status, "status");
            blockers = blockers == null
                ? List.of()
                : blockers.stream().filter(Objects::nonNull).distinct()
                    .sorted(Comparator
                        .comparing((ValidationBlocker item) -> item.code().name())
                        .thenComparing(ValidationBlocker::location)
                        .thenComparing(ValidationBlocker::detail))
                    .toList();
            EvolutionGenome.requireSha256(contentHash, "contentHash");
            String expected = EvolutionGenome.hash(renderReport(
                genomeHash,
                alphaStructuralHash,
                status,
                blockers,
                null));
            if (!expected.equals(contentHash)) {
                throw new IllegalArgumentException("preflight contentHash mismatch");
            }
        }

        public boolean accepted() {
            return status == ValidationStatus.ACCEPTED;
        }

        public List<BlockerCode> blockerCodes() {
            return blockers.stream().map(ValidationBlocker::code).distinct().toList();
        }

        public String toCanonicalJson() {
            return renderReport(
                genomeHash,
                alphaStructuralHash,
                status,
                blockers,
                contentHash);
        }
    }

    private record CycleEdge(String target, String geneId) {
    }

    private record AlphaPair(String source, String target) {
        private String material() {
            return source + "->" + target;
        }
    }

    private record ParsedRewrite(
        EvolutionGenome.RewriteGene gene,
        PatternExpr source,
        PatternExpr target,
        AlphaPair normalizedPair
    ) {
        private String alphaSource() {
            return normalizedPair.source();
        }

        private String alphaTarget() {
            return normalizedPair.target();
        }

        private String alphaPair() {
            return normalizedPair.material();
        }
    }
}
