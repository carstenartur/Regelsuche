package de.regelsuche.evolution;

import de.regelsuche.json.JsonWriter;
import de.regelsuche.transform.PatternExpr;
import de.regelsuche.transform.RewriteKind;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministic, bounded mutation enumeration with complete lineage records. */
public final class DeterministicGenomeMutator {
    public static final String SCHEMA = "regelsuche.evolution-mutation-batch/v1";
    private static final Pattern NUMBER = Pattern.compile(
        "(?<![A-Za-z0-9_.])(?:\\d+(?:\\.\\d+)?|\\.\\d+)(?![A-Za-z0-9_.])");

    private final EvolutionGenomeValidator validator;

    public DeterministicGenomeMutator() {
        this(new EvolutionGenomeValidator());
    }

    public DeterministicGenomeMutator(EvolutionGenomeValidator validator) {
        this.validator = Objects.requireNonNull(validator, "validator");
    }

    public MutationBatch mutate(
        EvolutionGenome parent,
        MutationCatalog catalog,
        long seed,
        MutationLimits limits
    ) {
        Objects.requireNonNull(parent, "parent");
        Objects.requireNonNull(catalog, "catalog");
        Objects.requireNonNull(limits, "limits");
        EvolutionGenomeValidator.ValidationReport parentReport = validator.validate(parent);
        if (!parentReport.accepted()) {
            throw new IllegalArgumentException(
                "parent genome failed preflight: " + parentReport.blockerCodes());
        }

        List<Proposal> proposals = generate(parent, catalog).stream()
            .sorted(Comparator.comparing(Proposal::key))
            .toList();
        List<Proposal> ordered = rotate(proposals, seed).stream()
            .limit(limits.maxProposals())
            .toList();

        List<MutationAttempt> attempts = new ArrayList<>();
        List<EvolutionGenome> accepted = new ArrayList<>();
        Set<String> structuralHashes = new LinkedHashSet<>();
        structuralHashes.add(parent.alphaStructuralHash());

        for (int index = 0; index < ordered.size(); index++) {
            Proposal proposal = ordered.get(index);
            MutationAttempt attempt = evaluate(
                parent,
                proposal,
                index + 1,
                structuralHashes,
                accepted.size() < limits.maxAccepted());
            attempts.add(attempt);
            if (attempt.status() == MutationStatus.ACCEPTED) {
                EvolutionGenome child = proposal.factory().get();
                accepted.add(child);
                structuralHashes.add(child.alphaStructuralHash());
            }
        }
        return MutationBatch.create(parent, seed, limits, attempts, accepted);
    }

    private MutationAttempt evaluate(
        EvolutionGenome parent,
        Proposal proposal,
        int ordinal,
        Set<String> structuralHashes,
        boolean acceptanceCapacity
    ) {
        EvolutionGenome child;
        try {
            child = proposal.factory().get();
        } catch (RuntimeException exception) {
            return new MutationAttempt(
                ordinal,
                proposal.kind(),
                proposal.key(),
                MutationStatus.REJECTED,
                null,
                null,
                List.of("CONSTRUCTION_FAILED:" + stableMessage(exception)));
        }
        EvolutionGenomeValidator.ValidationReport report =
            validator.validateMutation(parent, child, proposal.kind());
        List<String> blockers = report.blockers().stream()
            .map(item -> item.code().name() + ":" + item.location() + ":" + item.detail())
            .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        if (structuralHashes.contains(child.alphaStructuralHash())) {
            blockers.add("STRUCTURAL_DIVERSITY_DUPLICATE:alphaStructuralHash");
        }
        if (!acceptanceCapacity) {
            blockers.add("ACCEPTED_BUDGET_EXHAUSTED:maxAccepted");
        }
        blockers = blockers.stream().distinct().sorted().toList();
        MutationStatus status = blockers.isEmpty()
            ? MutationStatus.ACCEPTED
            : MutationStatus.REJECTED;
        return new MutationAttempt(
            ordinal,
            proposal.kind(),
            proposal.key(),
            status,
            child.contentHash(),
            child.alphaStructuralHash(),
            blockers);
    }

    private static List<Proposal> generate(
        EvolutionGenome parent,
        MutationCatalog catalog
    ) {
        List<Proposal> proposals = new ArrayList<>();
        addReverseProposals(parent, proposals);
        addGeneralizationProposals(parent, proposals);
        addSpecializationProposals(parent, catalog, proposals);
        addCompositionProposals(parent, proposals);
        addAssumptionProposals(parent, catalog, proposals);
        addFeatureProposals(parent, catalog, proposals);
        return proposals;
    }

    private static void addReverseProposals(
        EvolutionGenome parent,
        List<Proposal> proposals
    ) {
        for (EvolutionGenome.RewriteGene gene : parent.rewrites()) {
            if (!gene.reversible()) {
                continue;
            }
            String key = "reverse:" + gene.geneId();
            proposals.add(new Proposal(
                EvolutionMutationKind.REVERSE_REWRITE,
                key,
                () -> parent.withRewrites(replaceGene(
                    parent.rewrites(),
                    gene.withPatterns(gene.targetPattern(), gene.sourcePattern())))));
        }
    }

    private static void addGeneralizationProposals(
        EvolutionGenome parent,
        List<Proposal> proposals
    ) {
        for (EvolutionGenome.RewriteGene gene : parent.rewrites()) {
            Set<String> numbers = new TreeSet<>();
            collectNumbers(gene.sourcePattern(), numbers);
            collectNumbers(gene.targetPattern(), numbers);
            for (String number : numbers) {
                String placeholder = nextPlaceholder(gene, "K");
                String key = "generalize:" + gene.geneId() + ":" + number;
                proposals.add(new Proposal(
                    EvolutionMutationKind.GENERALIZE_PLACEHOLDER,
                    key,
                    () -> parent.withRewrites(replaceGene(
                        parent.rewrites(),
                        mutateNumber(gene, number, placeholder)))));
            }
        }
    }

    private static void addSpecializationProposals(
        EvolutionGenome parent,
        MutationCatalog catalog,
        List<Proposal> proposals
    ) {
        for (EvolutionGenome.RewriteGene gene : parent.rewrites()) {
            List<String> placeholders = EvolutionGenome.placeholders(gene.sourcePattern())
                .stream().sorted().toList();
            for (String placeholder : placeholders) {
                for (int constant : catalog.specializationConstants()) {
                    String key = "specialize:" + gene.geneId() + ":"
                        + placeholder.substring(1) + ":" + constant;
                    proposals.add(new Proposal(
                        EvolutionMutationKind.SPECIALIZE_PLACEHOLDER,
                        key,
                        () -> parent.withRewrites(replaceGene(
                            parent.rewrites(),
                            replacePlaceholder(gene, placeholder, Integer.toString(constant))))));
                }
            }
        }
    }

    private static void addCompositionProposals(
        EvolutionGenome parent,
        List<Proposal> proposals
    ) {
        for (EvolutionGenome.RewriteGene first : parent.rewrites()) {
            for (EvolutionGenome.RewriteGene second : parent.rewrites()) {
                if (first.geneId().equals(second.geneId())) {
                    continue;
                }
                Composition composition = compose(first, second);
                if (composition == null) {
                    continue;
                }
                String key = "compose:" + first.geneId() + ":" + second.geneId();
                proposals.add(new Proposal(
                    EvolutionMutationKind.COMPOSE_REWRITES,
                    key,
                    () -> {
                        List<EvolutionGenome.RewriteGene> genes =
                            new ArrayList<>(parent.rewrites());
                        genes.add(composition.gene());
                        return parent.withRewrites(genes);
                    }));
            }
        }
    }

    private static void addAssumptionProposals(
        EvolutionGenome parent,
        MutationCatalog catalog,
        List<Proposal> proposals
    ) {
        for (EvolutionGenome.RewriteGene gene : parent.rewrites()) {
            Set<String> sourcePlaceholders = EvolutionGenome.placeholders(gene.sourcePattern());
            for (EvolutionGenome.AssumptionTemplate assumption : catalog.assumptions()) {
                Set<String> used = new LinkedHashSet<>(
                    EvolutionGenome.placeholders(assumption.expression()));
                assumption.symbols().forEach(symbol ->
                    used.addAll(EvolutionGenome.placeholders(symbol)));
                if (!sourcePlaceholders.containsAll(used)
                        || gene.assumptions().contains(assumption)) {
                    continue;
                }
                String key = "add-assumption:" + gene.geneId() + ":"
                    + EvolutionGenome.hash(assumption.canonicalMaterial()).substring(7, 19);
                proposals.add(new Proposal(
                    EvolutionMutationKind.ADD_ASSUMPTION,
                    key,
                    () -> parent.withRewrites(replaceGene(
                        parent.rewrites(),
                        gene.withAssumptions(append(gene.assumptions(), assumption))))));
            }
            for (EvolutionGenome.AssumptionTemplate assumption : gene.assumptions()) {
                String key = "remove-assumption:" + gene.geneId() + ":"
                    + EvolutionGenome.hash(assumption.canonicalMaterial()).substring(7, 19);
                proposals.add(new Proposal(
                    EvolutionMutationKind.REMOVE_ASSUMPTION,
                    key,
                    () -> parent.withRewrites(replaceGene(
                        parent.rewrites(),
                        gene.withAssumptions(remove(gene.assumptions(), assumption))))));
            }
        }
    }

    private static void addFeatureProposals(
        EvolutionGenome parent,
        MutationCatalog catalog,
        List<Proposal> proposals
    ) {
        Set<EvolutionGenome.FitnessSignal> present = parent.rankingFeatures().stream()
            .map(EvolutionGenome.FeatureWeight::signal)
            .collect(java.util.stream.Collectors.toSet());
        for (EvolutionGenome.FeatureWeight feature : catalog.rankingFeatures()) {
            if (present.contains(feature.signal())) {
                continue;
            }
            String key = "add-feature:" + feature.signal().name();
            proposals.add(new Proposal(
                EvolutionMutationKind.ADD_RANKING_FEATURE,
                key,
                () -> parent.withRankingFeatures(
                    append(parent.rankingFeatures(), feature))));
        }
        if (parent.rankingFeatures().size() > 1) {
            for (EvolutionGenome.FeatureWeight feature : parent.rankingFeatures()) {
                String key = "remove-feature:" + feature.signal().name();
                proposals.add(new Proposal(
                    EvolutionMutationKind.REMOVE_RANKING_FEATURE,
                    key,
                    () -> parent.withRankingFeatures(
                        remove(parent.rankingFeatures(), feature))));
            }
        }
    }

    private static EvolutionGenome.RewriteGene mutateNumber(
        EvolutionGenome.RewriteGene gene,
        String number,
        String placeholder
    ) {
        String source = replaceNumber(gene.sourcePattern(), number, placeholder);
        String target = replaceNumber(gene.targetPattern(), number, placeholder);
        List<EvolutionGenome.AssumptionTemplate> assumptions = gene.assumptions().stream()
            .map(item -> new EvolutionGenome.AssumptionTemplate(
                item.kind(),
                replaceNumber(item.expression(), number, placeholder),
                item.symbols().stream()
                    .map(symbol -> replaceNumber(symbol, number, placeholder))
                    .toList()))
            .toList();
        return new EvolutionGenome.RewriteGene(
            gene.geneId(),
            source,
            target,
            gene.kind(),
            gene.reversible(),
            gene.estimatedCostDelta(),
            gene.maxApplicationsPerPath(),
            gene.maxAstGrowth(),
            assumptions,
            gene.evidenceObligations());
    }

    private static EvolutionGenome.RewriteGene replacePlaceholder(
        EvolutionGenome.RewriteGene gene,
        String placeholder,
        String replacement
    ) {
        java.util.function.UnaryOperator<String> replace = token ->
            token.equals(placeholder) ? replacement : token;
        List<EvolutionGenome.AssumptionTemplate> assumptions = gene.assumptions().stream()
            .map(item -> new EvolutionGenome.AssumptionTemplate(
                item.kind(),
                EvolutionGenome.transformPlaceholders(item.expression(), replace),
                item.symbols().stream()
                    .map(symbol -> EvolutionGenome.transformPlaceholders(symbol, replace))
                    .toList()))
            .toList();
        return new EvolutionGenome.RewriteGene(
            gene.geneId(),
            EvolutionGenome.transformPlaceholders(gene.sourcePattern(), replace),
            EvolutionGenome.transformPlaceholders(gene.targetPattern(), replace),
            gene.kind(),
            gene.reversible(),
            gene.estimatedCostDelta(),
            gene.maxApplicationsPerPath(),
            gene.maxAstGrowth(),
            assumptions,
            gene.evidenceObligations());
    }

    private static Composition compose(
        EvolutionGenome.RewriteGene first,
        EvolutionGenome.RewriteGene second
    ) {
        PatternExpr intermediate;
        PatternExpr secondSource;
        PatternExpr secondTarget;
        try {
            intermediate = EvolutionGenomeCompiler.parsePattern(first.targetPattern());
            secondSource = EvolutionGenomeCompiler.parsePattern(second.sourcePattern());
            secondTarget = EvolutionGenomeCompiler.parsePattern(second.targetPattern());
        } catch (IllegalArgumentException exception) {
            return null;
        }
        Map<String, PatternExpr> bindings = new LinkedHashMap<>();
        if (!unify(secondSource, intermediate, bindings)) {
            return null;
        }
        PatternExpr composedTarget;
        try {
            composedTarget = instantiate(secondTarget, bindings);
        } catch (IllegalArgumentException exception) {
            return null;
        }
        List<EvolutionGenome.AssumptionTemplate> assumptions = new ArrayList<>(
            first.assumptions());
        for (EvolutionGenome.AssumptionTemplate assumption : second.assumptions()) {
            assumptions.add(instantiate(assumption, bindings));
        }
        List<EvolutionGenome.EvidenceObligation> obligations = new ArrayList<>(
            first.evidenceObligations());
        obligations.addAll(second.evidenceObligations());
        String identity = first.geneId() + "|" + second.geneId();
        String geneId = "compose_" + EvolutionGenome.hash(identity).substring(7, 23);
        EvolutionGenome.RewriteGene gene = new EvolutionGenome.RewriteGene(
            geneId,
            first.sourcePattern(),
            EvolutionGenomeCompiler.renderPattern(composedTarget),
            mergeKind(first.kind(), second.kind()),
            false,
            clamp(first.estimatedCostDelta() + second.estimatedCostDelta(), -1000, 1000),
            Math.min(first.maxApplicationsPerPath(), second.maxApplicationsPerPath()),
            Math.min(1024, first.maxAstGrowth() + second.maxAstGrowth()),
            assumptions,
            obligations);
        return new Composition(gene);
    }

    private static boolean unify(
        PatternExpr pattern,
        PatternExpr value,
        Map<String, PatternExpr> bindings
    ) {
        if (pattern instanceof PatternExpr.Placeholder placeholder) {
            PatternExpr existing = bindings.putIfAbsent(placeholder.name(), value);
            return existing == null || existing.equals(value);
        }
        if (pattern instanceof PatternExpr.LiteralNumber number) {
            return value instanceof PatternExpr.LiteralNumber other
                && number.value() == other.value();
        }
        if (pattern instanceof PatternExpr.LiteralVariable variable) {
            return value instanceof PatternExpr.LiteralVariable other
                && variable.name().equals(other.name());
        }
        if (pattern instanceof PatternExpr.Operation operation) {
            return value instanceof PatternExpr.Operation other
                && operation.operator() == other.operator()
                && unify(operation.left(), other.left(), bindings)
                && unify(operation.right(), other.right(), bindings);
        }
        PatternExpr.Function function = (PatternExpr.Function) pattern;
        if (!(value instanceof PatternExpr.Function other)
                || !function.name().equals(other.name())
                || function.arguments().size() != other.arguments().size()) {
            return false;
        }
        for (int index = 0; index < function.arguments().size(); index++) {
            if (!unify(
                    function.arguments().get(index),
                    other.arguments().get(index),
                    bindings)) {
                return false;
            }
        }
        return true;
    }

    private static PatternExpr instantiate(
        PatternExpr pattern,
        Map<String, PatternExpr> bindings
    ) {
        if (pattern instanceof PatternExpr.Placeholder placeholder) {
            PatternExpr value = bindings.get(placeholder.name());
            if (value == null) {
                throw new IllegalArgumentException(
                    "unbound composition placeholder " + placeholder.name());
            }
            return value;
        }
        if (pattern instanceof PatternExpr.Operation operation) {
            return PatternExpr.op(
                operation.operator(),
                instantiate(operation.left(), bindings),
                instantiate(operation.right(), bindings));
        }
        if (pattern instanceof PatternExpr.Function function) {
            PatternExpr[] arguments = function.arguments().stream()
                .map(argument -> instantiate(argument, bindings))
                .toArray(PatternExpr[]::new);
            return PatternExpr.fn(function.name(), arguments);
        }
        return pattern;
    }

    private static EvolutionGenome.AssumptionTemplate instantiate(
        EvolutionGenome.AssumptionTemplate assumption,
        Map<String, PatternExpr> bindings
    ) {
        java.util.function.UnaryOperator<String> replace = token -> {
            PatternExpr value = bindings.get(token.substring(1));
            if (value == null) {
                throw new IllegalArgumentException(
                    "unbound assumption placeholder " + token);
            }
            return EvolutionGenomeCompiler.renderPattern(value);
        };
        return new EvolutionGenome.AssumptionTemplate(
            assumption.kind(),
            EvolutionGenome.transformPlaceholders(assumption.expression(), replace),
            assumption.symbols().stream()
                .map(symbol -> EvolutionGenome.transformPlaceholders(symbol, replace))
                .toList());
    }

    private static RewriteKind mergeKind(RewriteKind first, RewriteKind second) {
        if (first == RewriteKind.EXPAND || second == RewriteKind.EXPAND) {
            return RewriteKind.EXPAND;
        }
        if (first == RewriteKind.FACTOR || second == RewriteKind.FACTOR) {
            return RewriteKind.FACTOR;
        }
        if (first == RewriteKind.SIMPLIFY || second == RewriteKind.SIMPLIFY) {
            return RewriteKind.SIMPLIFY;
        }
        return RewriteKind.NORMALIZE;
    }

    private static void collectNumbers(String value, Set<String> output) {
        Matcher matcher = NUMBER.matcher(value);
        while (matcher.find()) {
            output.add(matcher.group());
        }
    }

    private static String replaceNumber(
        String value,
        String number,
        String replacement
    ) {
        Matcher matcher = NUMBER.matcher(value);
        StringBuffer output = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(
                output,
                Matcher.quoteReplacement(
                    matcher.group().equals(number) ? replacement : matcher.group()));
        }
        matcher.appendTail(output);
        return output.toString();
    }

    private static String nextPlaceholder(
        EvolutionGenome.RewriteGene gene,
        String prefix
    ) {
        Set<String> used = new LinkedHashSet<>(
            EvolutionGenome.placeholders(gene.sourcePattern()));
        used.addAll(EvolutionGenome.placeholders(gene.targetPattern()));
        for (int index = 0; index < 10_000; index++) {
            String candidate = "?" + prefix + index;
            if (!used.contains(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("placeholder namespace exhausted");
    }

    private static List<EvolutionGenome.RewriteGene> replaceGene(
        List<EvolutionGenome.RewriteGene> genes,
        EvolutionGenome.RewriteGene replacement
    ) {
        return genes.stream()
            .map(gene -> gene.geneId().equals(replacement.geneId())
                ? replacement
                : gene)
            .toList();
    }

    private static <T> List<T> append(List<T> values, T value) {
        List<T> result = new ArrayList<>(values);
        result.add(value);
        return result;
    }

    private static <T> List<T> remove(List<T> values, T value) {
        List<T> result = new ArrayList<>(values);
        result.remove(value);
        return result;
    }

    private static List<Proposal> rotate(List<Proposal> values, long seed) {
        if (values.isEmpty()) {
            return List.of();
        }
        int offset = (int) Math.floorMod(mix(seed), values.size());
        List<Proposal> result = new ArrayList<>(values.size());
        result.addAll(values.subList(offset, values.size()));
        result.addAll(values.subList(0, offset));
        return result;
    }

    private static long mix(long value) {
        long mixed = value + 0x9E3779B97F4A7C15L;
        mixed = (mixed ^ (mixed >>> 30)) * 0xBF58476D1CE4E5B9L;
        mixed = (mixed ^ (mixed >>> 27)) * 0x94D049BB133111EBL;
        return mixed ^ (mixed >>> 31);
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static String stableMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return exception.getClass().getSimpleName()
            + (message == null || message.isBlank() ? "" : ":" + message);
    }

    public enum MutationStatus {
        ACCEPTED,
        REJECTED
    }

    public record MutationLimits(int maxProposals, int maxAccepted) {
        public MutationLimits {
            if (maxProposals < 1 || maxProposals > 100_000) {
                throw new IllegalArgumentException("maxProposals must be in [1,100000]");
            }
            if (maxAccepted < 1 || maxAccepted > maxProposals) {
                throw new IllegalArgumentException(
                    "maxAccepted must be in [1,maxProposals]");
            }
        }

        public static MutationLimits conservativeDefault() {
            return new MutationLimits(128, 16);
        }
    }

    public record MutationCatalog(
        List<EvolutionGenome.AssumptionTemplate> assumptions,
        List<EvolutionGenome.FeatureWeight> rankingFeatures,
        List<Integer> specializationConstants
    ) {
        public MutationCatalog {
            assumptions = assumptions == null
                ? List.of()
                : assumptions.stream().filter(Objects::nonNull)
                    .distinct()
                    .sorted(Comparator.comparing(
                        EvolutionGenome.AssumptionTemplate::canonicalMaterial))
                    .toList();
            rankingFeatures = rankingFeatures == null
                ? List.of()
                : rankingFeatures.stream().filter(Objects::nonNull)
                    .collect(java.util.stream.Collectors.toMap(
                        EvolutionGenome.FeatureWeight::signal,
                        value -> value,
                        (left, right) -> left,
                        LinkedHashMap::new))
                    .values().stream()
                    .sorted(Comparator.comparing(item -> item.signal().name()))
                    .toList();
            specializationConstants = specializationConstants == null
                ? List.of(0, 1, 2, 3)
                : specializationConstants.stream()
                    .filter(Objects::nonNull)
                    .filter(value -> value >= -1000 && value <= 1000)
                    .distinct()
                    .sorted()
                    .toList();
        }

        public static MutationCatalog empty() {
            return new MutationCatalog(List.of(), List.of(), List.of(0, 1, 2, 3));
        }
    }

    public record MutationAttempt(
        int ordinal,
        EvolutionMutationKind kind,
        String proposalKey,
        MutationStatus status,
        String childGenomeHash,
        String childAlphaStructuralHash,
        List<String> blockers
    ) {
        public MutationAttempt {
            if (ordinal < 1) {
                throw new IllegalArgumentException("ordinal must be positive");
            }
            Objects.requireNonNull(kind, "kind");
            if (proposalKey == null || proposalKey.isBlank()) {
                throw new IllegalArgumentException("proposalKey must not be blank");
            }
            Objects.requireNonNull(status, "status");
            if (childGenomeHash != null) {
                EvolutionGenome.requireSha256(childGenomeHash, "childGenomeHash");
                EvolutionGenome.requireSha256(
                    childAlphaStructuralHash,
                    "childAlphaStructuralHash");
            } else if (childAlphaStructuralHash != null) {
                throw new IllegalArgumentException(
                    "child structural hash requires child genome hash");
            }
            blockers = blockers == null
                ? List.of()
                : blockers.stream().filter(Objects::nonNull)
                    .distinct().sorted().toList();
            if (status == MutationStatus.ACCEPTED && !blockers.isEmpty()) {
                throw new IllegalArgumentException("accepted mutation cannot have blockers");
            }
            if (status == MutationStatus.REJECTED && blockers.isEmpty()) {
                throw new IllegalArgumentException("rejected mutation requires blockers");
            }
        }
    }

    public record MutationBatch(
        String schema,
        String parentGenomeHash,
        String parentAlphaStructuralHash,
        long seed,
        MutationLimits limits,
        List<MutationAttempt> attempts,
        List<EvolutionGenome> acceptedChildren,
        String contentHash
    ) {
        public MutationBatch {
            if (!SCHEMA.equals(schema)) {
                throw new IllegalArgumentException("unsupported mutation batch schema");
            }
            EvolutionGenome.requireSha256(parentGenomeHash, "parentGenomeHash");
            EvolutionGenome.requireSha256(
                parentAlphaStructuralHash,
                "parentAlphaStructuralHash");
            Objects.requireNonNull(limits, "limits");
            attempts = attempts == null
                ? List.of()
                : attempts.stream()
                    .sorted(Comparator.comparingInt(MutationAttempt::ordinal))
                    .toList();
            acceptedChildren = acceptedChildren == null
                ? List.of()
                : List.copyOf(acceptedChildren);
            if (attempts.size() > limits.maxProposals()) {
                throw new IllegalArgumentException("attempt count exceeds maxProposals");
            }
            for (int index = 0; index < attempts.size(); index++) {
                if (attempts.get(index).ordinal() != index + 1) {
                    throw new IllegalArgumentException("attempt ordinals must be contiguous");
                }
            }
            if (acceptedChildren.size() > limits.maxAccepted()) {
                throw new IllegalArgumentException("accepted child count exceeds maxAccepted");
            }
            List<String> acceptedAttemptHashes = attempts.stream()
                .filter(item -> item.status() == MutationStatus.ACCEPTED)
                .map(MutationAttempt::childGenomeHash)
                .toList();
            List<String> acceptedChildHashes = acceptedChildren.stream()
                .map(EvolutionGenome::contentHash)
                .toList();
            if (!acceptedAttemptHashes.equals(acceptedChildHashes)) {
                throw new IllegalArgumentException(
                    "accepted attempts and accepted children are inconsistent");
            }
            long distinctStructures = acceptedChildren.stream()
                .map(EvolutionGenome::alphaStructuralHash)
                .distinct()
                .count();
            if (distinctStructures != acceptedChildren.size()) {
                throw new IllegalArgumentException("accepted children must be structurally unique");
            }
            if (acceptedChildren.stream().anyMatch(child ->
                    !child.seedGenomeHashes().contains(parentGenomeHash))) {
                throw new IllegalArgumentException("accepted child is missing parent lineage");
            }
            EvolutionGenome.requireSha256(contentHash, "contentHash");
            String expected = EvolutionGenome.hash(renderBatch(
                parentGenomeHash,
                parentAlphaStructuralHash,
                seed,
                limits,
                attempts,
                acceptedChildren,
                null));
            if (!expected.equals(contentHash)) {
                throw new IllegalArgumentException("mutation batch contentHash mismatch");
            }
        }

        private static MutationBatch create(
            EvolutionGenome parent,
            long seed,
            MutationLimits limits,
            List<MutationAttempt> attempts,
            List<EvolutionGenome> accepted
        ) {
            String payload = renderBatch(
                parent.contentHash(),
                parent.alphaStructuralHash(),
                seed,
                limits,
                attempts,
                accepted,
                null);
            return new MutationBatch(
                SCHEMA,
                parent.contentHash(),
                parent.alphaStructuralHash(),
                seed,
                limits,
                attempts,
                accepted,
                EvolutionGenome.hash(payload));
        }

        public String toCanonicalJson() {
            return renderBatch(
                parentGenomeHash,
                parentAlphaStructuralHash,
                seed,
                limits,
                attempts,
                acceptedChildren,
                contentHash);
        }

        public List<String> acceptedGenomeHashes() {
            return acceptedChildren.stream().map(EvolutionGenome::contentHash).toList();
        }
    }

    private static String renderBatch(
        String parentHash,
        String parentAlphaHash,
        long seed,
        MutationLimits limits,
        List<MutationAttempt> attempts,
        List<EvolutionGenome> accepted,
        String contentHash
    ) {
        JsonWriter json = new JsonWriter().beginObject()
            .property("schema", SCHEMA)
            .property("parentGenomeHash", parentHash)
            .property("parentAlphaStructuralHash", parentAlphaHash)
            .property("seed", seed)
            .object("limits", object -> object
                .property("maxProposals", limits.maxProposals())
                .property("maxAccepted", limits.maxAccepted()))
            .array("attempts", array -> attempts.forEach(attempt ->
                array.objectValue(object -> writeAttempt(object, attempt))))
            .stringArray("acceptedGenomeHashes", accepted.stream()
                .map(EvolutionGenome::contentHash)
                .toList())
            .stringArray("acceptedAlphaStructuralHashes", accepted.stream()
                .map(EvolutionGenome::alphaStructuralHash)
                .toList());
        if (contentHash != null) {
            json.property("contentHash", contentHash);
        }
        return json.endObject().toString();
    }

    private static void writeAttempt(JsonWriter json, MutationAttempt attempt) {
        json.property("ordinal", attempt.ordinal())
            .property("kind", attempt.kind().name())
            .property("proposalKey", attempt.proposalKey())
            .property("status", attempt.status().name());
        if (attempt.childGenomeHash() == null) {
            json.nullProperty("childGenomeHash")
                .nullProperty("childAlphaStructuralHash");
        } else {
            json.property("childGenomeHash", attempt.childGenomeHash())
                .property("childAlphaStructuralHash", attempt.childAlphaStructuralHash());
        }
        json.stringArray("blockers", attempt.blockers());
    }

    private record Proposal(
        EvolutionMutationKind kind,
        String key,
        Supplier<EvolutionGenome> factory
    ) {
    }

    private record Composition(EvolutionGenome.RewriteGene gene) {
    }
}
