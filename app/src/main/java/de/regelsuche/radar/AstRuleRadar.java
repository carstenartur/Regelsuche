package de.regelsuche.radar;

import de.regelsuche.validation.CandidateProofStatus;
import de.regelsuche.knowledge.RuleProfile;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Canonical, UI-neutral data contract for the position-aware AST rule radar.
 *
 * <p>The contract deliberately distinguishes the tree inside one expression
 * state from the graph between complete expression states. A candidate is one
 * concrete rule application at one stable AST path, including its bindings,
 * assumptions, origin and advertised successor expression.</p>
 */
public final class AstRuleRadar {
    private AstRuleRadar() {
    }

    public enum RuleOrigin {
        CORE,
        KNOWLEDGE_PACK,
        RULE_FILE,
        PLUGIN,
        LEARNED_MACRO
    }

    public enum CandidateOutcome {
        AVAILABLE,
        SELECTED,
        APPLIED,
        PRUNED_BUDGET,
        PRUNED_DUPLICATE,
        PRUNED_KNOWN_BETTER,
        REJECTED_ASSUMPTION,
        REJECTED_VALIDATION,
        FAILED_APPLICATION
    }

    /** Frozen rule/search context used to make candidate identities reproducible. */
    public record Context(
        RuleProfile knowledgeProfile,
        Set<String> enabledPacks,
        Set<String> disabledPacks,
        boolean includePlugins,
        boolean includeLearnedMacros,
        CandidateProofStatus minMacroProofStatus,
        String searchProfile,
        String goalExpression,
        int maxCandidatesPerPosition,
        int maxCandidatesTotal,
        List<String> assumptions,
        boolean includeRejectedCandidates,
        String selectedCandidateId,
        Map<String, CandidateOutcome> outcomeByCandidateId
    ) {
        private static final int DEFAULT_PER_POSITION = 24;
        private static final int DEFAULT_TOTAL = 240;
        private static final int MAX_PER_POSITION = 200;
        private static final int MAX_TOTAL = 2_000;

        public Context {
            knowledgeProfile = knowledgeProfile == null ? RuleProfile.CORE : knowledgeProfile;
            enabledPacks = enabledPacks == null ? Set.of() : Set.copyOf(enabledPacks);
            disabledPacks = disabledPacks == null ? Set.of() : Set.copyOf(disabledPacks);
            minMacroProofStatus = minMacroProofStatus == null
                ? CandidateProofStatus.VALIDATED_BY_EXAMPLES
                : minMacroProofStatus;
            searchProfile = normalize(searchProfile, "DISCOVERY");
            goalExpression = normalize(goalExpression, "");
            maxCandidatesPerPosition = bounded(maxCandidatesPerPosition, DEFAULT_PER_POSITION, MAX_PER_POSITION);
            maxCandidatesTotal = bounded(maxCandidatesTotal, DEFAULT_TOTAL, MAX_TOTAL);
            assumptions = assumptions == null ? List.of() : assumptions.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .sorted()
                .toList();
            selectedCandidateId = normalize(selectedCandidateId, "");
            outcomeByCandidateId = outcomeByCandidateId == null ? Map.of() : Map.copyOf(outcomeByCandidateId);
        }

        public static Context defaults() {
            return new Context(
                RuleProfile.CORE,
                Set.of(),
                Set.of(),
                true,
                true,
                CandidateProofStatus.VALIDATED_BY_EXAMPLES,
                "DISCOVERY",
                "",
                DEFAULT_PER_POSITION,
                DEFAULT_TOTAL,
                List.of(),
                true,
                "",
                Map.of()
            );
        }

        private static int bounded(int value, int fallback, int maximum) {
            int effective = value <= 0 ? fallback : value;
            return Math.min(effective, maximum);
        }

        private static String normalize(String value, String fallback) {
            return value == null || value.isBlank() ? fallback : value.trim();
        }
    }

    public record Diagnostic(String code, String message, String pathKey) {
        public Diagnostic {
            code = safe(code);
            message = safe(message);
            pathKey = safe(pathKey);
        }
    }

    public record Truncation(
        boolean truncated,
        int generatedCandidateCount,
        int returnedCandidateCount,
        int omittedCandidateCount,
        Map<String, Integer> omittedByPath
    ) {
        public Truncation {
            omittedByPath = omittedByPath == null ? Map.of() : Map.copyOf(omittedByPath);
        }
    }

    public record Binding(String name, String value, String kind) {
        public Binding {
            name = safe(name);
            value = safe(value);
            kind = safe(kind);
        }
    }

    /** Flat deterministic AST node; clients can reconstruct the tree via parent/child path keys. */
    public record AstNode(
        String pathKey,
        String parentPathKey,
        List<String> childPathKeys,
        String nodeKind,
        String label,
        String subtree,
        int depth,
        int preorderIndex,
        List<String> candidateIds,
        int candidateCount,
        int omittedCandidateCount
    ) {
        public AstNode {
            pathKey = safe(pathKey);
            parentPathKey = safe(parentPathKey);
            childPathKeys = childPathKeys == null ? List.of() : List.copyOf(childPathKeys);
            nodeKind = safe(nodeKind);
            label = safe(label);
            subtree = safe(subtree);
            candidateIds = candidateIds == null ? List.of() : List.copyOf(candidateIds);
        }
    }

    public record AtomicStep(
        int index,
        String beforeExpression,
        String afterExpression,
        String ruleId,
        String ruleKind,
        List<String> assumptions
    ) {
        public AtomicStep {
            beforeExpression = safe(beforeExpression);
            afterExpression = safe(afterExpression);
            ruleId = safe(ruleId);
            ruleKind = safe(ruleKind);
            assumptions = assumptions == null ? List.of() : List.copyOf(assumptions);
        }
    }

    /** Evidence retained for a learned macro rather than an opaque one-step shortcut. */
    public record MacroEvidence(
        String reusableRuleId,
        List<String> supportingPathIds,
        List<AtomicStep> atomicSteps,
        double confidenceScore,
        int occurrenceCount,
        double compressionRatio
    ) {
        public MacroEvidence {
            reusableRuleId = safe(reusableRuleId);
            supportingPathIds = supportingPathIds == null ? List.of() : List.copyOf(supportingPathIds);
            atomicSteps = atomicSteps == null ? List.of() : List.copyOf(atomicSteps);
        }
    }

    /** One concrete, position-bound rewrite candidate. */
    public record ApplicableMove(
        String candidateId,
        String pathKey,
        String ruleId,
        String displayName,
        RuleOrigin origin,
        String sourceReference,
        String license,
        String ruleKind,
        List<Binding> bindings,
        List<String> assumptions,
        String subtreeBefore,
        String subtreeAfter,
        String expressionBefore,
        String expressionAfter,
        boolean applicable,
        String validationStatus,
        boolean equivalencePreserving,
        boolean mayIncreaseComplexity,
        int estimatedCostDelta,
        CandidateOutcome outcome,
        boolean selected,
        MacroEvidence macroEvidence,
        String orderingKey
    ) {
        public ApplicableMove {
            candidateId = safe(candidateId);
            pathKey = safe(pathKey);
            ruleId = safe(ruleId);
            displayName = normalize(displayName, ruleId);
            origin = origin == null ? RuleOrigin.CORE : origin;
            sourceReference = safe(sourceReference);
            license = safe(license);
            ruleKind = safe(ruleKind);
            bindings = bindings == null ? List.of() : List.copyOf(bindings);
            assumptions = assumptions == null ? List.of() : List.copyOf(assumptions);
            subtreeBefore = safe(subtreeBefore);
            subtreeAfter = safe(subtreeAfter);
            expressionBefore = safe(expressionBefore);
            expressionAfter = safe(expressionAfter);
            validationStatus = safe(validationStatus);
            outcome = outcome == null ? CandidateOutcome.AVAILABLE : outcome;
            orderingKey = safe(orderingKey);
        }
    }

    public record Snapshot(
        String schema,
        String expression,
        String canonicalExpression,
        Context context,
        List<AstNode> nodes,
        List<ApplicableMove> candidates,
        Truncation truncation,
        List<Diagnostic> diagnostics
    ) {
        public Snapshot {
            schema = normalize(schema, "regelsuche.ast-rule-radar/v1");
            expression = safe(expression);
            canonicalExpression = safe(canonicalExpression);
            context = context == null ? Context.defaults() : context;
            nodes = nodes == null ? List.of() : List.copyOf(nodes);
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
            truncation = truncation == null
                ? new Truncation(false, candidates.size(), candidates.size(), 0, Map.of())
                : truncation;
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        }

        public boolean valid() {
            return diagnostics.stream().noneMatch(diagnostic -> "INVALID_EXPRESSION".equals(diagnostic.code()));
        }
    }

    public record SearchState(
        String stateId,
        String expression,
        String canonicalExpression,
        int depth,
        boolean target
    ) {
        public SearchState {
            stateId = safe(stateId);
            expression = safe(expression);
            canonicalExpression = safe(canonicalExpression);
        }
    }

    public record SearchEdge(
        String edgeId,
        String fromStateId,
        String toStateId,
        String fromExpression,
        String toExpression,
        String candidateId,
        String pathKey,
        String ruleId,
        RuleOrigin origin,
        CandidateOutcome outcome
    ) {
        public SearchEdge {
            edgeId = safe(edgeId);
            fromStateId = safe(fromStateId);
            toStateId = safe(toStateId);
            fromExpression = safe(fromExpression);
            toExpression = safe(toExpression);
            candidateId = safe(candidateId);
            pathKey = safe(pathKey);
            ruleId = safe(ruleId);
            origin = origin == null ? RuleOrigin.CORE : origin;
            outcome = outcome == null ? CandidateOutcome.APPLIED : outcome;
        }
    }

    public record SearchEvent(
        long sequence,
        String stateId,
        String expression,
        String candidateId,
        String pathKey,
        String ruleId,
        CandidateOutcome outcome,
        String detail
    ) {
        public SearchEvent {
            stateId = safe(stateId);
            expression = safe(expression);
            candidateId = safe(candidateId);
            pathKey = safe(pathKey);
            ruleId = safe(ruleId);
            outcome = outcome == null ? CandidateOutcome.AVAILABLE : outcome;
            detail = safe(detail);
        }
    }

    public record SearchResult(
        String schema,
        String startExpression,
        String targetExpression,
        boolean targetReached,
        String terminalStateId,
        int exploredStateCount,
        int generatedCandidateCount,
        List<SearchState> states,
        List<SearchEdge> edges,
        List<SearchEvent> events,
        Map<String, CandidateOutcome> finalOutcomeByCandidateId,
        List<Diagnostic> diagnostics
    ) {
        public SearchResult {
            schema = normalize(schema, "regelsuche.ast-rule-radar-search/v1");
            startExpression = safe(startExpression);
            targetExpression = safe(targetExpression);
            terminalStateId = safe(terminalStateId);
            states = states == null ? List.of() : List.copyOf(states);
            edges = edges == null ? List.of() : List.copyOf(edges);
            events = events == null ? List.of() : List.copyOf(events);
            finalOutcomeByCandidateId = finalOutcomeByCandidateId == null
                ? Map.of()
                : Map.copyOf(finalOutcomeByCandidateId);
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
