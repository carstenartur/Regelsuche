package de.regelsuche.evolution;

import de.regelsuche.evolution.EvolutionRewriteProgramPlan.Choice;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.FirstApplicable;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.Node;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.Prune;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.Prioritize;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.Priority;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.Repeat;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.Require;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.Requirement;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.Sequence;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.Source;
import de.regelsuche.json.JsonWriter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Deterministic, bounded mutation enumeration for executable rewrite-program
 * topology. Every proposal is retained as accepted or rejected evidence.
 */
public final class DeterministicRewriteProgramMutator {
    public static final String SCHEMA =
        "regelsuche.evolution-rewrite-program-mutation-batch/v1";
    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9_-]{2,127}");

    private final EvolutionRewriteProgramCompiler compiler;

    public DeterministicRewriteProgramMutator() {
        this(new EvolutionRewriteProgramCompiler());
    }

    public DeterministicRewriteProgramMutator(
        EvolutionRewriteProgramCompiler compiler
    ) {
        this.compiler = Objects.requireNonNull(compiler, "compiler");
    }

    /**
     * Historical rotated-prefix behavior. This method is intentionally kept as
     * the exact legacy execution path bound by {@code ROTATED_PREFIX_V1}.
     */
    public MutationBatch mutate(
        EvolutionGenome genome,
        EvolutionRewriteProgramPlan parent,
        MutationCatalog catalog,
        long seed,
        MutationLimits limits
    ) {
        Objects.requireNonNull(genome, "genome");
        Objects.requireNonNull(parent, "parent");
        Objects.requireNonNull(catalog, "catalog");
        Objects.requireNonNull(limits, "limits");
        compiler.compile(genome, parent);
        validateCatalogAgainstGenome(genome, catalog);

        List<Proposal> proposals = generate(genome, parent, catalog).stream()
            .sorted(Comparator.comparing(Proposal::key))
            .toList();
        List<Proposal> ordered = rotate(proposals, seed).stream()
            .limit(limits.maxProposals())
            .toList();

        List<MutationAttempt> attempts = new ArrayList<>();
        List<EvolutionRewriteProgramPlan> accepted = new ArrayList<>();
        Set<String> structuralHashes = new LinkedHashSet<>();
        structuralHashes.add(parent.alphaStructuralHash());

        for (int index = 0; index < ordered.size(); index++) {
            Evaluation evaluation = evaluate(
                genome,
                parent,
                ordered.get(index),
                index + 1,
                structuralHashes,
                accepted.size() < limits.maxAccepted());
            attempts.add(evaluation.attempt());
            if (evaluation.child() != null
                    && evaluation.attempt().status() == MutationStatus.ACCEPTED) {
                accepted.add(evaluation.child());
                structuralHashes.add(evaluation.child().alphaStructuralHash());
            }
        }
        return MutationBatch.create(
            genome,
            parent,
            catalog,
            seed,
            limits,
            attempts,
            accepted);
    }

    /**
     * Deterministic v2 scheduler that prevents a mutation kind from monopolizing
     * the bounded accepted-offspring prefix.
     *
     * <p>The proposal surface and initial ordering are exactly the same as the
     * legacy path: key ascending followed by the same global seed rotation and
     * the same {@code maxProposals} window. Filtering a cyclic global rotation
     * by mutation kind also preserves a cyclic, seed-derived ordering within
     * every represented stratum.</p>
     *
     * <p>Every proposal is preflighted in that fixed order. Among otherwise
     * valid and preregistered proposals, the first pass gives each newly seen
     * mutation kind one accepted slot while capacity remains. A second pass
     * fills spare capacity in the original rotated proposal order. If capacity
     * is smaller than the number of represented valid kinds, the kinds whose
     * first valid proposal occurs earliest in that frozen order win. No
     * proposal, blocker or capacity rejection is omitted from the returned
     * batch.</p>
     */
    public MutationBatch mutateStratifiedByMutationKind(
        EvolutionGenome genome,
        EvolutionRewriteProgramPlan parent,
        MutationCatalog catalog,
        long seed,
        MutationLimits limits,
        Set<EvolutionRewriteProgramMutationKind> permittedKinds
    ) {
        Objects.requireNonNull(genome, "genome");
        Objects.requireNonNull(parent, "parent");
        Objects.requireNonNull(catalog, "catalog");
        Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(permittedKinds, "permittedKinds");
        if (permittedKinds.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(
                "permittedKinds must not contain null");
        }
        Set<EvolutionRewriteProgramMutationKind> permitted = Set.copyOf(
            permittedKinds);
        compiler.compile(genome, parent);
        validateCatalogAgainstGenome(genome, catalog);

        List<Proposal> proposals = generate(genome, parent, catalog).stream()
            .sorted(Comparator.comparing(Proposal::key))
            .toList();
        List<Proposal> ordered = rotate(proposals, seed).stream()
            .limit(limits.maxProposals())
            .toList();

        List<Evaluation> evaluated = new ArrayList<>();
        Set<String> structuralHashes = new LinkedHashSet<>();
        structuralHashes.add(parent.alphaStructuralHash());
        for (int index = 0; index < ordered.size(); index++) {
            Evaluation evaluation = evaluate(
                genome,
                parent,
                ordered.get(index),
                index + 1,
                structuralHashes,
                true);
            evaluated.add(evaluation);
            if (evaluation.child() != null
                    && evaluation.attempt().status() == MutationStatus.ACCEPTED) {
                structuralHashes.add(evaluation.child().alphaStructuralHash());
            }
        }

        Set<Integer> selectedOrdinals = new LinkedHashSet<>();
        Set<EvolutionRewriteProgramMutationKind> represented =
            new LinkedHashSet<>();
        for (Evaluation evaluation : evaluated) {
            MutationAttempt attempt = evaluation.attempt();
            if (selectedOrdinals.size() >= limits.maxAccepted()) {
                break;
            }
            if (evaluation.child() != null
                    && attempt.status() == MutationStatus.ACCEPTED
                    && permitted.contains(attempt.kind())
                    && represented.add(attempt.kind())) {
                selectedOrdinals.add(attempt.ordinal());
            }
        }
        for (Evaluation evaluation : evaluated) {
            MutationAttempt attempt = evaluation.attempt();
            if (selectedOrdinals.size() >= limits.maxAccepted()) {
                break;
            }
            if (evaluation.child() != null
                    && attempt.status() == MutationStatus.ACCEPTED
                    && permitted.contains(attempt.kind())) {
                selectedOrdinals.add(attempt.ordinal());
            }
        }

        List<MutationAttempt> attempts = new ArrayList<>();
        List<EvolutionRewriteProgramPlan> accepted = new ArrayList<>();
        for (Evaluation evaluation : evaluated) {
            MutationAttempt attempt = evaluation.attempt();
            if (evaluation.child() == null
                    || attempt.status() == MutationStatus.REJECTED) {
                attempts.add(attempt);
                continue;
            }
            if (!permitted.contains(attempt.kind())) {
                attempts.add(rejectedFromValid(
                    attempt,
                    "MUTATION_KIND_NOT_PREREGISTERED:" + attempt.kind()));
                continue;
            }
            if (!selectedOrdinals.contains(attempt.ordinal())) {
                attempts.add(rejectedFromValid(
                    attempt,
                    "ACCEPTED_BUDGET_EXHAUSTED:maxAccepted"));
                continue;
            }
            attempts.add(attempt);
            accepted.add(evaluation.child());
        }

        return MutationBatch.create(
            genome,
            parent,
            catalog,
            seed,
            limits,
            attempts,
            accepted);
    }

    private static MutationAttempt rejectedFromValid(
        MutationAttempt attempt,
        String blocker
    ) {
        return new MutationAttempt(
            attempt.ordinal(),
            attempt.kind(),
            attempt.proposalKey(),
            MutationStatus.REJECTED,
            attempt.childPlanHash(),
            attempt.childAlphaStructuralHash(),
            List.of(blocker));
    }

    private Evaluation evaluate(
        EvolutionGenome genome,
        EvolutionRewriteProgramPlan parent,
        Proposal proposal,
        int ordinal,
        Set<String> structuralHashes,
        boolean acceptanceCapacity
    ) {
        EvolutionRewriteProgramPlan child;
        try {
            child = proposal.factory().get();
        } catch (RuntimeException exception) {
            return new Evaluation(
                new MutationAttempt(
                    ordinal,
                    proposal.kind(),
                    proposal.key(),
                    MutationStatus.REJECTED,
                    null,
                    null,
                    List.of("CONSTRUCTION_FAILED:" + stableMessage(exception))),
                null);
        }

        List<String> blockers = new ArrayList<>();
        try {
            compiler.compile(genome, child);
        } catch (RuntimeException exception) {
            blockers.add("PROGRAM_PREFLIGHT_FAILED:" + stableMessage(exception));
        }
        if (child.contentHash().equals(parent.contentHash())) {
            blockers.add("IDENTITY_MUTATION:contentHash");
        }
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
        return new Evaluation(
            new MutationAttempt(
                ordinal,
                proposal.kind(),
                proposal.key(),
                status,
                child.contentHash(),
                child.alphaStructuralHash(),
                blockers),
            status == MutationStatus.ACCEPTED ? child : null);
    }

    private static List<Proposal> generate(
        EvolutionGenome genome,
        EvolutionRewriteProgramPlan parent,
        MutationCatalog catalog
    ) {
        List<Proposal> proposals = new ArrayList<>();
        List<Node> nodes = flatten(parent.root());
        for (Node target : nodes) {
            addRepeatProposals(genome, parent, catalog, target, proposals);
            addRequirementProposals(genome, parent, catalog, target, proposals);
            addPriorityProposals(genome, parent, catalog, target, proposals);
            addPruneProposals(genome, parent, catalog, target, proposals);
            addSourceProposals(genome, parent, catalog, target, proposals);
            addSwapProposals(genome, parent, target, proposals);
            addRemovalProposal(genome, parent, target, proposals);
            addConversionProposal(genome, parent, target, proposals);
        }
        return proposals;
    }

    private static void addRepeatProposals(
        EvolutionGenome genome,
        EvolutionRewriteProgramPlan parent,
        MutationCatalog catalog,
        Node target,
        List<Proposal> proposals
    ) {
        for (RepeatBounds bounds : catalog.repeatBounds()) {
            String key = "wrap-repeat:" + target.nodeId() + ":"
                + bounds.minIterations() + ":" + bounds.maxIterations();
            proposals.add(proposal(
                genome,
                parent,
                EvolutionRewriteProgramMutationKind.WRAP_REPEAT,
                key,
                target.nodeId(),
                current -> new Repeat(
                    generatedId("repeat", key),
                    current,
                    bounds.minIterations(),
                    bounds.maxIterations())));
        }
    }

    private static void addRequirementProposals(
        EvolutionGenome genome,
        EvolutionRewriteProgramPlan parent,
        MutationCatalog catalog,
        Node target,
        List<Proposal> proposals
    ) {
        for (Requirement requirement : catalog.requirements()) {
            String key = "wrap-require:" + target.nodeId() + ":"
                + requirement.kind().name() + ":" + requirement.threshold();
            proposals.add(proposal(
                genome,
                parent,
                EvolutionRewriteProgramMutationKind.WRAP_REQUIRE,
                key,
                target.nodeId(),
                current -> new Require(
                    generatedId("require", key),
                    current,
                    requirement)));
        }
    }

    private static void addPriorityProposals(
        EvolutionGenome genome,
        EvolutionRewriteProgramPlan parent,
        MutationCatalog catalog,
        Node target,
        List<Proposal> proposals
    ) {
        for (Priority priority : catalog.priorities()) {
            String key = "wrap-priority:" + target.nodeId() + ":"
                + priority.kind().name() + ":" + priority.preferredGeneIds();
            proposals.add(proposal(
                genome,
                parent,
                EvolutionRewriteProgramMutationKind.WRAP_PRIORITY,
                key,
                target.nodeId(),
                current -> new Prioritize(
                    generatedId("priority", key),
                    current,
                    priority)));
        }
    }

    private static void addPruneProposals(
        EvolutionGenome genome,
        EvolutionRewriteProgramPlan parent,
        MutationCatalog catalog,
        Node target,
        List<Proposal> proposals
    ) {
        for (int maxCandidates : catalog.pruneLimits()) {
            String key = "wrap-prune:" + target.nodeId() + ":" + maxCandidates;
            proposals.add(proposal(
                genome,
                parent,
                EvolutionRewriteProgramMutationKind.WRAP_PRUNE,
                key,
                target.nodeId(),
                current -> new Prune(
                    generatedId("prune", key),
                    current,
                    maxCandidates,
                    "evolution mutation candidate bound")));
        }
    }

    private static void addSourceProposals(
        EvolutionGenome genome,
        EvolutionRewriteProgramPlan parent,
        MutationCatalog catalog,
        Node target,
        List<Proposal> proposals
    ) {
        for (String geneId : catalog.sourceGeneIds()) {
            String prependKey = "prepend-source:" + target.nodeId() + ":" + geneId;
            proposals.add(proposal(
                genome,
                parent,
                EvolutionRewriteProgramMutationKind.PREPEND_SOURCE,
                prependKey,
                target.nodeId(),
                current -> new Sequence(
                    generatedId("sequence", prependKey),
                    List.of(
                        new Source(
                            generatedId("source", prependKey),
                            List.of(geneId)),
                        current))));

            String appendKey = "append-source:" + target.nodeId() + ":" + geneId;
            proposals.add(proposal(
                genome,
                parent,
                EvolutionRewriteProgramMutationKind.APPEND_SOURCE,
                appendKey,
                target.nodeId(),
                current -> new Sequence(
                    generatedId("sequence", appendKey),
                    List.of(
                        current,
                        new Source(
                            generatedId("source", appendKey),
                            List.of(geneId))))));
        }
    }

    private static void addSwapProposals(
        EvolutionGenome genome,
        EvolutionRewriteProgramPlan parent,
        Node target,
        List<Proposal> proposals
    ) {
        List<Node> ordered = orderedChildren(target);
        for (int index = 0; index + 1 < ordered.size(); index++) {
            final int position = index;
            String key = "swap-adjacent:" + target.nodeId() + ":" + index;
            proposals.add(proposal(
                genome,
                parent,
                EvolutionRewriteProgramMutationKind.SWAP_ADJACENT_CHILDREN,
                key,
                target.nodeId(),
                current -> withOrderedChildren(
                    current,
                    swapped(orderedChildren(current), position))));
        }
    }

    private static void addRemovalProposal(
        EvolutionGenome genome,
        EvolutionRewriteProgramPlan parent,
        Node target,
        List<Proposal> proposals
    ) {
        Node body = wrapperBody(target);
        if (body == null) {
            return;
        }
        String key = "remove-wrapper:" + target.nodeId();
        proposals.add(proposal(
            genome,
            parent,
            EvolutionRewriteProgramMutationKind.REMOVE_WRAPPER,
            key,
            target.nodeId(),
            ignored -> body));
    }

    private static void addConversionProposal(
        EvolutionGenome genome,
        EvolutionRewriteProgramPlan parent,
        Node target,
        List<Proposal> proposals
    ) {
        if (target instanceof Choice choice) {
            String key = "choice-to-first:" + target.nodeId();
            proposals.add(proposal(
                genome,
                parent,
                EvolutionRewriteProgramMutationKind.CHOICE_TO_FIRST_APPLICABLE,
                key,
                target.nodeId(),
                ignored -> new FirstApplicable(
                    choice.nodeId(), choice.alternatives())));
        } else if (target instanceof FirstApplicable firstApplicable) {
            String key = "first-to-choice:" + target.nodeId();
            proposals.add(proposal(
                genome,
                parent,
                EvolutionRewriteProgramMutationKind.FIRST_APPLICABLE_TO_CHOICE,
                key,
                target.nodeId(),
                ignored -> new Choice(
                    firstApplicable.nodeId(), firstApplicable.alternatives())));
        }
    }

    private static Proposal proposal(
        EvolutionGenome genome,
        EvolutionRewriteProgramPlan parent,
        EvolutionRewriteProgramMutationKind kind,
        String key,
        String targetNodeId,
        Function<Node, Node> mutation
    ) {
        return new Proposal(
            kind,
            key,
            () -> EvolutionRewriteProgramPlan.create(
                genome,
                replace(parent.root(), targetNodeId, mutation),
                parent.maxNodes(),
                parent.maxDepth()));
    }

    private static Node replace(
        Node current,
        String targetNodeId,
        Function<Node, Node> mutation
    ) {
        if (current.nodeId().equals(targetNodeId)) {
            return Objects.requireNonNull(mutation.apply(current), "mutated node");
        }
        if (current instanceof Choice choice) {
            return new Choice(
                choice.nodeId(),
                replaceChildren(choice.alternatives(), targetNodeId, mutation));
        }
        if (current instanceof FirstApplicable firstApplicable) {
            return new FirstApplicable(
                firstApplicable.nodeId(),
                replaceChildren(
                    firstApplicable.alternatives(), targetNodeId, mutation));
        }
        if (current instanceof Sequence sequence) {
            return new Sequence(
                sequence.nodeId(),
                replaceChildren(sequence.steps(), targetNodeId, mutation));
        }
        if (current instanceof Repeat repeat) {
            return new Repeat(
                repeat.nodeId(),
                replace(repeat.body(), targetNodeId, mutation),
                repeat.minIterations(),
                repeat.maxIterations());
        }
        if (current instanceof Require require) {
            return new Require(
                require.nodeId(),
                replace(require.body(), targetNodeId, mutation),
                require.requirement());
        }
        if (current instanceof Prioritize prioritize) {
            return new Prioritize(
                prioritize.nodeId(),
                replace(prioritize.body(), targetNodeId, mutation),
                prioritize.priority());
        }
        if (current instanceof Prune prune) {
            return new Prune(
                prune.nodeId(),
                replace(prune.body(), targetNodeId, mutation),
                prune.maxCandidates(),
                prune.reason());
        }
        return current;
    }

    private static List<Node> replaceChildren(
        List<Node> children,
        String targetNodeId,
        Function<Node, Node> mutation
    ) {
        return children.stream()
            .map(child -> replace(child, targetNodeId, mutation))
            .toList();
    }

    private static List<Node> flatten(Node root) {
        List<Node> result = new ArrayList<>();
        flatten(root, result);
        return List.copyOf(result);
    }

    private static void flatten(Node node, List<Node> output) {
        output.add(node);
        for (Node child : children(node)) {
            flatten(child, output);
        }
    }

    private static List<Node> children(Node node) {
        List<Node> ordered = orderedChildren(node);
        if (!ordered.isEmpty()) {
            return ordered;
        }
        Node body = wrapperBody(node);
        return body == null ? List.of() : List.of(body);
    }

    private static List<Node> orderedChildren(Node node) {
        if (node instanceof Choice choice) {
            return choice.alternatives();
        }
        if (node instanceof FirstApplicable firstApplicable) {
            return firstApplicable.alternatives();
        }
        if (node instanceof Sequence sequence) {
            return sequence.steps();
        }
        return List.of();
    }

    private static Node wrapperBody(Node node) {
        if (node instanceof Repeat repeat) {
            return repeat.body();
        }
        if (node instanceof Require require) {
            return require.body();
        }
        if (node instanceof Prioritize prioritize) {
            return prioritize.body();
        }
        if (node instanceof Prune prune) {
            return prune.body();
        }
        return null;
    }

    private static Node withOrderedChildren(Node node, List<Node> children) {
        if (node instanceof Choice choice) {
            return new Choice(choice.nodeId(), children);
        }
        if (node instanceof FirstApplicable firstApplicable) {
            return new FirstApplicable(firstApplicable.nodeId(), children);
        }
        if (node instanceof Sequence sequence) {
            return new Sequence(sequence.nodeId(), children);
        }
        throw new IllegalArgumentException(
            "node does not have swappable ordered children");
    }

    private static List<Node> swapped(List<Node> values, int index) {
        List<Node> copy = new ArrayList<>(values);
        Node left = copy.get(index);
        copy.set(index, copy.get(index + 1));
        copy.set(index + 1, left);
        return List.copyOf(copy);
    }

    private static String generatedId(String role, String key) {
        return "mut_" + role + "_"
            + EvolutionGenome.hash(key).substring("sha256:".length(), 19);
    }

    private static List<Proposal> rotate(List<Proposal> values, long seed) {
        if (values.isEmpty()) {
            return List.of();
        }
        int offset = (int) Math.floorMod(seed, values.size());
        List<Proposal> result = new ArrayList<>(values.size());
        result.addAll(values.subList(offset, values.size()));
        result.addAll(values.subList(0, offset));
        return List.copyOf(result);
    }

    private static void validateCatalogAgainstGenome(
        EvolutionGenome genome,
        MutationCatalog catalog
    ) {
        Set<String> geneIds = genome.rewrites().stream()
            .map(EvolutionGenome.RewriteGene::geneId)
            .collect(java.util.stream.Collectors.toSet());
        for (String geneId : catalog.sourceGeneIds()) {
            if (!geneIds.contains(geneId)) {
                throw new IllegalArgumentException(
                    "mutation catalog contains unknown source gene: " + geneId);
            }
        }
        for (Priority priority : catalog.priorities()) {
            for (String geneId : priority.preferredGeneIds()) {
                if (!geneIds.contains(geneId)) {
                    throw new IllegalArgumentException(
                        "mutation catalog contains unknown preferred gene: "
                            + geneId);
                }
            }
        }
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

    public record RepeatBounds(int minIterations, int maxIterations) {
        public RepeatBounds {
            if (minIterations < 1 || maxIterations < minIterations
                    || maxIterations > 1024) {
                throw new IllegalArgumentException(
                    "repeat bounds require 1 <= min <= max <= 1024");
            }
        }
    }

    public record MutationLimits(int maxProposals, int maxAccepted) {
        public MutationLimits {
            if (maxProposals < 1 || maxProposals > 100_000) {
                throw new IllegalArgumentException(
                    "maxProposals must be in [1,100000]");
            }
            if (maxAccepted < 1 || maxAccepted > maxProposals) {
                throw new IllegalArgumentException(
                    "maxAccepted must be in [1,maxProposals]");
            }
        }
    }

    public record MutationCatalog(
        List<RepeatBounds> repeatBounds,
        List<Requirement> requirements,
        List<Priority> priorities,
        List<Integer> pruneLimits,
        List<String> sourceGeneIds
    ) {
        public MutationCatalog {
            repeatBounds = normalized(
                repeatBounds,
                Comparator.comparingInt(RepeatBounds::minIterations)
                    .thenComparingInt(RepeatBounds::maxIterations));
            requirements = normalized(
                requirements,
                Comparator.comparing((Requirement value) -> value.kind().name())
                    .thenComparingInt(Requirement::threshold));
            priorities = normalized(
                priorities,
                Comparator.comparing((Priority value) -> value.kind().name())
                    .thenComparing(value -> value.preferredGeneIds().toString()));
            pruneLimits = pruneLimits == null
                ? List.of()
                : pruneLimits.stream()
                    .filter(Objects::nonNull)
                    .peek(value -> {
                        if (value < 1 || value > 1_000_000) {
                            throw new IllegalArgumentException(
                                "prune limit must be in [1,1000000]");
                        }
                    })
                    .distinct()
                    .sorted()
                    .toList();
            sourceGeneIds = sourceGeneIds == null
                ? List.of()
                : sourceGeneIds.stream()
                    .filter(Objects::nonNull)
                    .map(value -> value.trim().toLowerCase(java.util.Locale.ROOT))
                    .peek(value -> {
                        if (!ID.matcher(value).matches()) {
                            throw new IllegalArgumentException(
                                "invalid source gene ID: " + value);
                        }
                    })
                    .distinct()
                    .sorted()
                    .toList();
        }

        public String contentHash() {
            return EvolutionGenome.hash(canonicalMaterial());
        }

        private String canonicalMaterial() {
            return "repeat=" + repeatBounds
                + "\nrequirements=" + requirements
                + "\npriorities=" + priorities
                + "\nprune=" + pruneLimits
                + "\nsources=" + sourceGeneIds;
        }

        private static <T> List<T> normalized(
            List<T> values,
            Comparator<T> comparator
        ) {
            if (values == null) {
                return List.of();
            }
            return values.stream()
                .filter(Objects::nonNull)
                .distinct()
                .sorted(comparator)
                .toList();
        }
    }

    public record MutationAttempt(
        int ordinal,
        EvolutionRewriteProgramMutationKind kind,
        String proposalKey,
        MutationStatus status,
        String childPlanHash,
        String childAlphaStructuralHash,
        List<String> blockers
    ) {
        public MutationAttempt {
            if (ordinal < 1) {
                throw new IllegalArgumentException("ordinal must be positive");
            }
            Objects.requireNonNull(kind, "kind");
            requireText(proposalKey, "proposalKey");
            Objects.requireNonNull(status, "status");
            blockers = blockers == null
                ? List.of()
                : blockers.stream()
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(value -> !value.isEmpty())
                    .distinct()
                    .sorted()
                    .toList();
            if (childPlanHash != null) {
                EvolutionGenome.requireSha256(childPlanHash, "childPlanHash");
            }
            if (childAlphaStructuralHash != null) {
                EvolutionGenome.requireSha256(
                    childAlphaStructuralHash, "childAlphaStructuralHash");
            }
            if (status == MutationStatus.ACCEPTED) {
                if (childPlanHash == null || childAlphaStructuralHash == null
                        || !blockers.isEmpty()) {
                    throw new IllegalArgumentException(
                        "accepted mutation requires hashes and no blockers");
                }
            } else if (blockers.isEmpty()) {
                throw new IllegalArgumentException(
                    "rejected mutation requires at least one blocker");
            }
        }
    }

    public record MutationBatch(
        String schema,
        String genomeHash,
        String parentPlanHash,
        String parentAlphaStructuralHash,
        String catalogHash,
        long seed,
        MutationLimits limits,
        List<MutationAttempt> attempts,
        List<EvolutionRewriteProgramPlan> acceptedPlans,
        String contentHash
    ) {
        public MutationBatch {
            if (!SCHEMA.equals(schema)) {
                throw new IllegalArgumentException(
                    "unsupported rewrite-program mutation batch schema");
            }
            EvolutionGenome.requireSha256(genomeHash, "genomeHash");
            EvolutionGenome.requireSha256(parentPlanHash, "parentPlanHash");
            EvolutionGenome.requireSha256(
                parentAlphaStructuralHash, "parentAlphaStructuralHash");
            EvolutionGenome.requireSha256(catalogHash, "catalogHash");
            Objects.requireNonNull(limits, "limits");
            attempts = List.copyOf(attempts);
            acceptedPlans = List.copyOf(acceptedPlans);
            EvolutionGenome.requireSha256(contentHash, "contentHash");
            String expected = EvolutionGenome.hash(render(
                genomeHash,
                parentPlanHash,
                parentAlphaStructuralHash,
                catalogHash,
                seed,
                limits,
                attempts,
                acceptedPlans,
                null));
            if (!expected.equals(contentHash)) {
                throw new IllegalArgumentException(
                    "mutation batch contentHash does not match payload");
            }
            List<String> acceptedAttemptHashes = attempts.stream()
                .filter(attempt -> attempt.status() == MutationStatus.ACCEPTED)
                .map(MutationAttempt::childPlanHash)
                .toList();
            List<String> acceptedPlanHashes = acceptedPlans.stream()
                .map(EvolutionRewriteProgramPlan::contentHash)
                .toList();
            if (!acceptedAttemptHashes.equals(acceptedPlanHashes)) {
                throw new IllegalArgumentException(
                    "accepted attempts and retained plans differ");
            }
        }

        private static MutationBatch create(
            EvolutionGenome genome,
            EvolutionRewriteProgramPlan parent,
            MutationCatalog catalog,
            long seed,
            MutationLimits limits,
            List<MutationAttempt> attempts,
            List<EvolutionRewriteProgramPlan> acceptedPlans
        ) {
            String payload = render(
                genome.contentHash(),
                parent.contentHash(),
                parent.alphaStructuralHash(),
                catalog.contentHash(),
                seed,
                limits,
                attempts,
                acceptedPlans,
                null);
            return new MutationBatch(
                SCHEMA,
                genome.contentHash(),
                parent.contentHash(),
                parent.alphaStructuralHash(),
                catalog.contentHash(),
                seed,
                limits,
                attempts,
                acceptedPlans,
                EvolutionGenome.hash(payload));
        }

        public String toCanonicalJson() {
            return render(
                genomeHash,
                parentPlanHash,
                parentAlphaStructuralHash,
                catalogHash,
                seed,
                limits,
                attempts,
                acceptedPlans,
                contentHash);
        }

        public int acceptedCount() {
            return acceptedPlans.size();
        }

        public int rejectedCount() {
            return attempts.size() - acceptedPlans.size();
        }

        private static String render(
            String genomeHash,
            String parentPlanHash,
            String parentAlphaStructuralHash,
            String catalogHash,
            long seed,
            MutationLimits limits,
            List<MutationAttempt> attempts,
            List<EvolutionRewriteProgramPlan> acceptedPlans,
            String contentHash
        ) {
            JsonWriter json = new JsonWriter().beginObject()
                .property("schema", SCHEMA)
                .property("genomeHash", genomeHash)
                .property("parentPlanHash", parentPlanHash)
                .property("parentAlphaStructuralHash", parentAlphaStructuralHash)
                .property("catalogHash", catalogHash)
                .property("seed", seed)
                .object("limits", object -> object
                    .property("maxProposals", limits.maxProposals())
                    .property("maxAccepted", limits.maxAccepted()))
                .array("attempts", array -> attempts.forEach(attempt ->
                    array.objectValue(object -> {
                        object.property("ordinal", attempt.ordinal())
                            .property("kind", attempt.kind().name())
                            .property("proposalKey", attempt.proposalKey())
                            .property("status", attempt.status().name());
                        if (attempt.childPlanHash() == null) {
                            object.nullProperty("childPlanHash");
                        } else {
                            object.property(
                                "childPlanHash", attempt.childPlanHash());
                        }
                        if (attempt.childAlphaStructuralHash() == null) {
                            object.nullProperty("childAlphaStructuralHash");
                        } else {
                            object.property(
                                "childAlphaStructuralHash",
                                attempt.childAlphaStructuralHash());
                        }
                        object.stringArray("blockers", attempt.blockers());
                    })))
                .stringArray(
                    "acceptedPlanHashes",
                    acceptedPlans.stream()
                        .map(EvolutionRewriteProgramPlan::contentHash)
                        .toList())
                .stringArray(
                    "acceptedAlphaStructuralHashes",
                    acceptedPlans.stream()
                        .map(EvolutionRewriteProgramPlan::alphaStructuralHash)
                        .toList());
            if (contentHash != null) {
                json.property("contentHash", contentHash);
            }
            return json.endObject().toString();
        }
    }

    private record Proposal(
        EvolutionRewriteProgramMutationKind kind,
        String key,
        Supplier<EvolutionRewriteProgramPlan> factory
    ) {
    }

    private record Evaluation(
        MutationAttempt attempt,
        EvolutionRewriteProgramPlan child
    ) {
    }

    private static <T> List<T> append(List<T> values, T value) {
        List<T> result = new ArrayList<>(values);
        result.add(value);
        return List.copyOf(result);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
