package de.regelsuche.radar;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.discovery.DiscoveredTransformation;
import de.regelsuche.discovery.TransformationStep;
import de.regelsuche.graph.ExpressionGraphStore;
import de.regelsuche.ide.RuleInspectionDto;
import de.regelsuche.ide.RuleInspectionService;
import de.regelsuche.inventory.ReusableRule;
import de.regelsuche.inventory.RuleInventoryRepository;
import de.regelsuche.knowledge.KnowledgePackRegistry;
import de.regelsuche.knowledge.KnowledgePackSelection;
import de.regelsuche.knowledge.RuleDescriptor;
import de.regelsuche.mining.GoalAwareMacroMoveSelector;
import de.regelsuche.mining.PatternBinary;
import de.regelsuche.mining.PatternFunction;
import de.regelsuche.mining.PatternNumber;
import de.regelsuche.mining.PatternVariable;
import de.regelsuche.mining.RulePatternNode;
import de.regelsuche.mining.RulePatternParser;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.plugin.PatternTransformation;
import de.regelsuche.plugin.PluginRuntime;
import de.regelsuche.plugin.PluginRuntimeConfig;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.EquivalenceAwarePatternMatcher;
import de.regelsuche.transform.PatternExpr;
import de.regelsuche.transform.PatternRewriteRule;
import de.regelsuche.transform.RecognitionProfile;
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.transform.RewriteRule;
import de.regelsuche.validation.CandidateProofStatus;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static de.regelsuche.radar.AstRuleRadar.ApplicableMove;
import static de.regelsuche.radar.AstRuleRadar.AstNode;
import static de.regelsuche.radar.AstRuleRadar.AtomicStep;
import static de.regelsuche.radar.AstRuleRadar.Binding;
import static de.regelsuche.radar.AstRuleRadar.CandidateOutcome;
import static de.regelsuche.radar.AstRuleRadar.Context;
import static de.regelsuche.radar.AstRuleRadar.Diagnostic;
import static de.regelsuche.radar.AstRuleRadar.MacroEvidence;
import static de.regelsuche.radar.AstRuleRadar.RuleOrigin;
import static de.regelsuche.radar.AstRuleRadar.Snapshot;
import static de.regelsuche.radar.AstRuleRadar.Truncation;

/**
 * Produces the canonical position-aware rule-radar snapshot.
 *
 * <p>All mathematical matching and subtree replacement happens here. The web
 * client receives immutable facts and never infers bindings or constructs a
 * rewrite. Atomic rules, Knowledge Packs, rule files, plugins, parameterized
 * local moves and qualified learned macros are normalized into the same
 * {@link ApplicableMove} contract.</p>
 */
public final class AstRuleRadarService implements AutoCloseable {
    private static final String SCHEMA = "regelsuche.ast-rule-radar/v1";
    private static final double MIN_MACRO_CONFIDENCE = GoalAwareMacroMoveSelector.DEFAULT_MIN_CONFIDENCE;
    private static final double MIN_MACRO_IMPROVEMENT = GoalAwareMacroMoveSelector.DEFAULT_MIN_IMPROVEMENT;
    private static final int MIN_MACRO_OCCURRENCES = GoalAwareMacroMoveSelector.DEFAULT_MIN_OCCURRENCES;

    private final RuleInventoryRepository inventory;
    private final ExpressionGraphStore graphStore;
    private final ExpressionParser parser = new ExpressionParser();
    private final ExpressionCanonicalizer canonicalizer = new ExpressionCanonicalizer();
    private final RulePatternParser rulePatternParser = new RulePatternParser();
    private final RuleInspectionService parameterInspectionService = new RuleInspectionService();
    private final PluginRuntime pluginRuntime;

    public AstRuleRadarService(
        RuleInventoryRepository inventory,
        ExpressionGraphStore graphStore,
        PluginRuntimeConfig pluginRuntimeConfig
    ) {
        if (inventory == null || graphStore == null) {
            throw new IllegalArgumentException("inventory and graphStore are required");
        }
        this.inventory = inventory;
        this.graphStore = graphStore;
        this.pluginRuntime = new PluginRuntime(
            pluginRuntimeConfig == null ? PluginRuntimeConfig.defaults() : pluginRuntimeConfig);
    }

    public Snapshot inspect(String expression, Context requestedContext) {
        Context context = requestedContext == null ? Context.defaults() : requestedContext;
        if (expression == null || expression.isBlank()) {
            return invalid(expression, context, "expression must not be blank");
        }

        Expr root;
        try {
            root = parser.parseTerm(expression);
        } catch (RuntimeException exception) {
            return invalid(expression, context, "expression is not parseable: " + safeMessage(exception));
        }
        String formattedRoot = ExpressionFormatter.format(root);
        String canonicalRoot = canonical(formattedRoot);
        List<PositionedNode> positionedNodes = positionedNodes(root);
        List<RuleSource> ordinaryRules = ordinaryRules(context);
        List<ReusableMacro> reusableMacros = reusableMacros();
        List<ApplicableMove> generated = new ArrayList<>();
        List<Diagnostic> diagnostics = new ArrayList<>();

        for (PositionedNode positioned : positionedNodes) {
            for (RuleSource source : ordinaryRules) {
                createOrdinaryCandidate(root, formattedRoot, positioned, source, context, diagnostics)
                    .ifPresent(generated::add);
            }
            for (ReusableMacro macro : reusableMacros) {
                createMacroCandidate(root, formattedRoot, positioned, macro, context, diagnostics)
                    .ifPresent(generated::add);
            }
        }
        addParameterizedLocalMoves(formattedRoot, context, generated);

        // A single semantic application must not be duplicated because the same
        // registry entry was reachable through two extension surfaces.
        Map<String, ApplicableMove> uniqueById = new LinkedHashMap<>();
        generated.stream()
            .sorted(APPLICABLE_MOVE_ORDER)
            .forEach(candidate -> uniqueById.putIfAbsent(candidate.candidateId(), candidate));
        List<ApplicableMove> unique = List.copyOf(uniqueById.values());
        TruncatedCandidates truncated = truncate(unique, context);
        Map<String, List<String>> candidateIdsByPath = new LinkedHashMap<>();
        for (ApplicableMove candidate : truncated.returned()) {
            candidateIdsByPath.computeIfAbsent(candidate.pathKey(), ignored -> new ArrayList<>())
                .add(candidate.candidateId());
        }
        Map<String, Integer> generatedCountByPath = countByPath(unique);
        List<AstNode> astNodes = positionedNodes.stream()
            .map(positioned -> new AstNode(
                positioned.pathKey(),
                positioned.parentPathKey(),
                positioned.childPathKeys(),
                positioned.nodeKind(),
                positioned.label(),
                positioned.subtreeText(),
                positioned.depth(),
                positioned.preorderIndex(),
                candidateIdsByPath.getOrDefault(positioned.pathKey(), List.of()),
                generatedCountByPath.getOrDefault(positioned.pathKey(), 0),
                truncated.omittedByPath().getOrDefault(positioned.pathKey(), 0)
            ))
            .toList();

        Truncation truncation = new Truncation(
            truncated.omittedCount() > 0,
            unique.size(),
            truncated.returned().size(),
            truncated.omittedCount(),
            truncated.omittedByPath()
        );
        return new Snapshot(
            SCHEMA,
            formattedRoot,
            canonicalRoot,
            context,
            astNodes,
            truncated.returned(),
            truncation,
            diagnostics
        );
    }

    /** Re-resolves a candidate from the frozen expression/context before applying it. */
    public Optional<ApplicableMove> resolve(String expression, String candidateId, Context context) {
        if (candidateId == null || candidateId.isBlank()) {
            return Optional.empty();
        }
        return inspect(expression, context).candidates().stream()
            .filter(candidate -> candidateId.equals(candidate.candidateId()))
            .findFirst();
    }

    private Snapshot invalid(String expression, Context context, String message) {
        return new Snapshot(
            SCHEMA,
            expression == null ? "" : expression,
            "",
            context,
            List.of(),
            List.of(),
            new Truncation(false, 0, 0, 0, Map.of()),
            List.of(new Diagnostic("INVALID_EXPRESSION", message, ""))
        );
    }

    private List<RuleSource> ordinaryRules(Context context) {
        Map<String, RuleSource> indexed = new LinkedHashMap<>();
        for (RewriteRule rule : AstRewriteTransformationEngine.defaultRules()) {
            indexed.put(rule.id(), source(rule, RuleOrigin.CORE, "Regelsuche core", rule.descriptor().sourceReference()));
        }

        KnowledgePackSelection selection = new KnowledgePackSelection(
            context.knowledgeProfile(), context.enabledPacks(), context.disabledPacks());
        for (RewriteRule rule : new KnowledgePackRegistry().enabledRules(selection)) {
            indexed.put(rule.id(), source(rule, RuleOrigin.KNOWLEDGE_PACK,
                rule.descriptor().packId(), rule.descriptor().sourceReference()));
        }

        if (context.includePlugins()) {
            pluginRuntime.reload();
            pluginRuntime.ruleRegistry().registrations().stream()
                .filter(registration -> registration.enabled())
                .sorted(Comparator.comparing(registration -> registration.id()))
                .forEach(registration -> indexed.put(registration.id(), new RuleSource(
                    registration.rule(),
                    originForPluginSource(registration.source()),
                    registration.source(),
                    registration.explanation(),
                    registration.rule().descriptor().license(),
                    registration.rule().descriptor().sourceReference(),
                    "REGISTERED"
                )));
            pluginRuntime.transformationRegistry().registrations().stream()
                .filter(registration -> registration.enabled())
                .sorted(Comparator.comparing(registration -> registration.id()))
                .forEach(registration -> indexed.put(registration.id(), new RuleSource(
                    registration.transformation(),
                    originForPluginSource(registration.source()),
                    registration.source(),
                    registration.explanation(),
                    registration.transformation().descriptor().license(),
                    registration.transformation().descriptor().sourceReference(),
                    "REGISTERED"
                )));
            for (PatternTransformation macro : pluginRuntime.macroTransformations()) {
                indexed.putIfAbsent(macro.id(), new RuleSource(
                    macro,
                    RuleOrigin.RULE_FILE,
                    "active macro registry",
                    macro.id(),
                    macro.descriptor().license(),
                    macro.descriptor().sourceReference(),
                    "REGISTERED"
                ));
            }
        }
        return List.copyOf(indexed.values());
    }

    private RuleSource source(RewriteRule rule, RuleOrigin origin, String sourceName, String reference) {
        RuleDescriptor descriptor = rule.descriptor();
        return new RuleSource(
            rule,
            origin,
            sourceName,
            rule.id(),
            descriptor.license(),
            reference,
            rule.isEquivalencePreservingByConstruction() ? "EQUIVALENCE_PRESERVING" : "ASSUMED"
        );
    }

    private List<ReusableMacro> reusableMacros() {
        List<ReusableMacro> compiled = new ArrayList<>();
        for (ReusableRule reusable : inventory.findAll().stream()
            .sorted(Comparator.comparing(ReusableRule::id)).toList()) {
            try {
                PatternRewriteRule rule = new PatternRewriteRule(
                    macroRuleId(reusable),
                    toPatternExpr(rulePatternParser.parse(reusable.leftPattern())),
                    toPatternExpr(rulePatternParser.parse(reusable.rightPattern())),
                    RewriteKind.NORMALIZE,
                    false,
                    -Math.max(1, (int) Math.round(Math.max(1.0, reusable.averageImprovement()))),
                    true
                );
                compiled.add(new ReusableMacro(reusable, rule));
            } catch (RuntimeException ignored) {
                // Invalid legacy inventory entries are reported only when their
                // pattern would otherwise be inspected; they are never executable.
            }
        }
        return List.copyOf(compiled);
    }

    private Optional<ApplicableMove> createOrdinaryCandidate(
        Expr root,
        String formattedRoot,
        PositionedNode positioned,
        RuleSource source,
        Context context,
        List<Diagnostic> diagnostics
    ) {
        RewriteRule rule = source.rule();
        Expr subtree = positioned.expr();
        boolean matches;
        try {
            matches = rule.matches(subtree);
        } catch (RuntimeException exception) {
            diagnostics.add(new Diagnostic("MATCH_FAILURE", rule.id() + ": " + safeMessage(exception), positioned.pathKey()));
            return Optional.empty();
        }
        if (!matches) {
            return Optional.empty();
        }
        Expr rewritten;
        try {
            rewritten = rule.apply(subtree);
        } catch (RuntimeException exception) {
            diagnostics.add(new Diagnostic("APPLICATION_FAILURE", rule.id() + ": " + safeMessage(exception), positioned.pathKey()));
            return Optional.empty();
        }
        if (rewritten == null || rewritten.equals(subtree)) {
            return Optional.empty();
        }
        String subtreeAfter = ExpressionFormatter.format(rewritten);
        String expressionAfter;
        try {
            expressionAfter = ExpressionFormatter.format(replaceAt(root, positioned.path(), rewritten));
        } catch (RuntimeException exception) {
            diagnostics.add(new Diagnostic("REPLACEMENT_FAILURE", rule.id() + ": " + safeMessage(exception), positioned.pathKey()));
            return Optional.empty();
        }
        List<String> assumptions = rule.assumptions(subtree).stream()
            .map(assumption -> assumption.expression())
            .filter(value -> value != null && !value.isBlank())
            .distinct()
            .sorted()
            .toList();
        boolean assumptionsSatisfied = context.assumptions().containsAll(assumptions);
        if (!assumptionsSatisfied && !context.includeRejectedCandidates()) {
            return Optional.empty();
        }
        CandidateOutcome outcome = assumptionsSatisfied
            ? CandidateOutcome.AVAILABLE
            : CandidateOutcome.REJECTED_ASSUMPTION;
        List<Binding> bindings = bindings(rule, subtree);
        return Optional.of(candidate(
            formattedRoot,
            positioned,
            rule.id(),
            source.displayName(),
            source.origin(),
            source.sourceReference(),
            source.license(),
            rule.kind().name(),
            bindings,
            assumptions,
            subtreeAfter,
            expressionAfter,
            assumptionsSatisfied,
            source.validationStatus(),
            rule.isEquivalencePreservingByConstruction(),
            rule.mayIncreaseComplexity(),
            rule.estimatedCostDelta(),
            outcome,
            null,
            context
        ));
    }

    private Optional<ApplicableMove> createMacroCandidate(
        Expr root,
        String formattedRoot,
        PositionedNode positioned,
        ReusableMacro macro,
        Context context,
        List<Diagnostic> diagnostics
    ) {
        if (!context.includeLearnedMacros() || !inventory.isEnabled(macro.reusable().id())) {
            return Optional.empty();
        }
        PatternRewriteRule rule = macro.rule();
        if (!rule.matches(positioned.expr())) {
            return Optional.empty();
        }
        ReusableRule reusable = macro.reusable();
        boolean proofAccepted = reusable.proofStatus().atLeast(context.minMacroProofStatus());
        boolean qualityAccepted = reusable.confidenceScore() >= MIN_MACRO_CONFIDENCE
            && reusable.averageImprovement() > MIN_MACRO_IMPROVEMENT
            && reusable.occurrenceCount() >= MIN_MACRO_OCCURRENCES;
        boolean goalAccepted = new GoalAwareMacroMoveSelector(inventory).score(
            reusable, positioned.subtreeText(), context.goalExpression()) > 0.0;
        boolean assumptionsSatisfied = context.assumptions().containsAll(reusable.assumptions());
        CandidateOutcome outcome;
        if (!proofAccepted || !qualityAccepted) {
            outcome = CandidateOutcome.REJECTED_VALIDATION;
        } else if (!assumptionsSatisfied) {
            outcome = CandidateOutcome.REJECTED_ASSUMPTION;
        } else {
            outcome = CandidateOutcome.AVAILABLE;
        }
        if (!goalAccepted) {
            return Optional.empty();
        }
        boolean applicable = proofAccepted && qualityAccepted && assumptionsSatisfied;
        if (!applicable && !context.includeRejectedCandidates()) {
            return Optional.empty();
        }
        Expr rewritten;
        try {
            rewritten = rule.apply(positioned.expr());
        } catch (RuntimeException exception) {
            diagnostics.add(new Diagnostic("MACRO_APPLICATION_FAILURE",
                reusable.id() + ": " + safeMessage(exception), positioned.pathKey()));
            return Optional.empty();
        }
        String subtreeAfter = ExpressionFormatter.format(rewritten);
        String expressionAfter = ExpressionFormatter.format(replaceAt(root, positioned.path(), rewritten));
        MacroEvidence evidence = macroEvidence(reusable);
        return Optional.of(candidate(
            formattedRoot,
            positioned,
            macroRuleId(reusable),
            reusable.id(),
            RuleOrigin.LEARNED_MACRO,
            String.join(",", reusable.supportingPathIds()),
            "PROJECT",
            rule.kind().name(),
            bindings(rule, positioned.expr()),
            reusable.assumptions(),
            subtreeAfter,
            expressionAfter,
            applicable,
            reusable.proofStatus().name(),
            true,
            false,
            rule.estimatedCostDelta(),
            outcome,
            evidence,
            context
        ));
    }

    private void addParameterizedLocalMoves(String formattedRoot, Context context, List<ApplicableMove> target) {
        RuleInspectionDto inspection = parameterInspectionService.inspect(formattedRoot);
        Map<String, PositionedNode> positionedByKey = positionedNodes(parser.parseTerm(formattedRoot)).stream()
            .collect(LinkedHashMap::new, (map, node) -> map.put(node.pathKey(), node), Map::putAll);
        for (RuleInspectionDto.PositionResult position : inspection.positions()) {
            PositionedNode positioned = positionedByKey.get(position.pathKey());
            if (positioned == null) {
                continue;
            }
            for (RuleInspectionDto.RuleMatch match : position.matches()) {
                if (match.expressionAfter() == null || match.expressionAfter().isBlank()
                    || match.subtreeAfter() == null || match.subtreeAfter().isBlank()) {
                    continue;
                }
                if (!match.applicable() && !context.includeRejectedCandidates()) {
                    continue;
                }
                List<Binding> bindings = match.bindings().stream()
                    .map(binding -> new Binding(binding.name(), binding.value(), binding.kind()))
                    .sorted(Comparator.comparing(Binding::name).thenComparing(Binding::value).thenComparing(Binding::kind))
                    .toList();
                ApplicableMove candidate = candidate(
                    formattedRoot,
                    positioned,
                    match.enumeratorId(),
                    match.kind(),
                    RuleOrigin.CORE,
                    "finite parameter enumerator",
                    "PROJECT",
                    match.kind(),
                    bindings,
                    List.of(),
                    match.subtreeAfter(),
                    match.expressionAfter(),
                    match.applicable(),
                    "ASSUMED",
                    false,
                    false,
                    0,
                    match.applicable() ? CandidateOutcome.AVAILABLE : CandidateOutcome.FAILED_APPLICATION,
                    null,
                    context
                );
                target.add(candidate);
            }
        }
    }

    private ApplicableMove candidate(
        String expressionBefore,
        PositionedNode positioned,
        String ruleId,
        String displayName,
        RuleOrigin origin,
        String sourceReference,
        String license,
        String ruleKind,
        List<Binding> bindings,
        List<String> assumptions,
        String subtreeAfter,
        String expressionAfter,
        boolean applicable,
        String validationStatus,
        boolean equivalencePreserving,
        boolean mayIncreaseComplexity,
        int estimatedCostDelta,
        CandidateOutcome defaultOutcome,
        MacroEvidence macroEvidence,
        Context context
    ) {
        List<Binding> sortedBindings = bindings == null ? List.of() : bindings.stream()
            .sorted(Comparator.comparing(Binding::name).thenComparing(Binding::value).thenComparing(Binding::kind))
            .toList();
        List<String> sortedAssumptions = assumptions == null ? List.of() : assumptions.stream()
            .filter(value -> value != null && !value.isBlank())
            .map(String::trim)
            .distinct()
            .sorted()
            .toList();
        String identityMaterial = String.join("\u001f",
            canonical(expressionBefore),
            positioned.pathKey(),
            origin.name(),
            ruleId,
            bindingKey(sortedBindings),
            String.join("\u001e", sortedAssumptions),
            canonical(expressionAfter),
            contextFingerprint(context));
        String candidateId = "move:" + sha256(identityMaterial);
        CandidateOutcome outcome = context.outcomeByCandidateId().getOrDefault(candidateId, defaultOutcome);
        String orderingKey = String.format(Locale.ROOT, "%02d|%s|%s|%s|%s",
            origin.ordinal(), ruleId, bindingKey(sortedBindings), String.join("|", sortedAssumptions), canonical(expressionAfter));
        return new ApplicableMove(
            candidateId,
            positioned.pathKey(),
            ruleId,
            displayName,
            origin,
            sourceReference,
            license,
            ruleKind,
            sortedBindings,
            sortedAssumptions,
            positioned.subtreeText(),
            subtreeAfter,
            expressionBefore,
            expressionAfter,
            applicable,
            validationStatus,
            equivalencePreserving,
            mayIncreaseComplexity,
            estimatedCostDelta,
            outcome,
            candidateId.equals(context.selectedCandidateId()),
            macroEvidence,
            orderingKey
        );
    }

    private List<Binding> bindings(RewriteRule rule, Expr subtree) {
        if (!(rule instanceof PatternRewriteRule patternRule)) {
            return List.of();
        }
        Map<String, Expr> matched = new HashMap<>();
        boolean success = EquivalenceAwarePatternMatcher.match(
            patternRule.source(), subtree, matched, patternRule.recognitionProfile());
        if (!success) {
            return List.of();
        }
        return matched.entrySet().stream()
            .map(entry -> new Binding(entry.getKey(), ExpressionFormatter.format(entry.getValue()), "PATTERN"))
            .sorted(Comparator.comparing(Binding::name).thenComparing(Binding::value))
            .toList();
    }

    private MacroEvidence macroEvidence(ReusableRule reusable) {
        List<AtomicStep> steps = new ArrayList<>();
        Map<String, DiscoveredTransformation> transformationsById = graphStore.discoveredTransformations().stream()
            .collect(LinkedHashMap::new, (map, item) -> map.put(item.id(), item), Map::putAll);
        for (String pathId : reusable.supportingPathIds().stream().sorted().toList()) {
            DiscoveredTransformation transformation = transformationsById.get(pathId);
            if (transformation == null) {
                continue;
            }
            for (TransformationStep step : transformation.steps()) {
                steps.add(new AtomicStep(
                    steps.size(),
                    step.beforeExpression(),
                    step.afterExpression(),
                    step.ruleId(),
                    step.ruleKind().name(),
                    step.assumptions()
                ));
            }
        }
        double compressionRatio = steps.isEmpty() ? 1.0 : Math.max(1.0, steps.size());
        return new MacroEvidence(
            reusable.id(),
            reusable.supportingPathIds(),
            steps,
            reusable.confidenceScore(),
            reusable.occurrenceCount(),
            compressionRatio
        );
    }

    private TruncatedCandidates truncate(List<ApplicableMove> candidates, Context context) {
        Map<String, List<ApplicableMove>> byPath = new LinkedHashMap<>();
        for (ApplicableMove candidate : candidates) {
            byPath.computeIfAbsent(candidate.pathKey(), ignored -> new ArrayList<>()).add(candidate);
        }
        List<ApplicableMove> perPositionLimited = new ArrayList<>();
        Map<String, Integer> omitted = new LinkedHashMap<>();
        for (Map.Entry<String, List<ApplicableMove>> entry : byPath.entrySet()) {
            List<ApplicableMove> ordered = entry.getValue().stream().sorted(APPLICABLE_MOVE_ORDER).toList();
            int keep = Math.min(context.maxCandidatesPerPosition(), ordered.size());
            perPositionLimited.addAll(ordered.subList(0, keep));
            if (keep < ordered.size()) {
                omitted.put(entry.getKey(), ordered.size() - keep);
            }
        }
        perPositionLimited.sort(APPLICABLE_MOVE_ORDER);
        int keepTotal = Math.min(context.maxCandidatesTotal(), perPositionLimited.size());
        List<ApplicableMove> returned = new ArrayList<>(perPositionLimited.subList(0, keepTotal));
        for (ApplicableMove dropped : perPositionLimited.subList(keepTotal, perPositionLimited.size())) {
            omitted.merge(dropped.pathKey(), 1, Integer::sum);
        }
        int omittedCount = omitted.values().stream().mapToInt(Integer::intValue).sum();
        return new TruncatedCandidates(List.copyOf(returned), omittedCount, Map.copyOf(omitted));
    }

    private Map<String, Integer> countByPath(List<ApplicableMove> candidates) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (ApplicableMove candidate : candidates) {
            counts.merge(candidate.pathKey(), 1, Integer::sum);
        }
        return Map.copyOf(counts);
    }

    private List<PositionedNode> positionedNodes(Expr root) {
        List<PositionedNode> result = new ArrayList<>();
        collect(root, List.of(), "", 0, result);
        return List.copyOf(result);
    }

    private void collect(Expr expr, List<Integer> path, String parentKey, int depth, List<PositionedNode> target) {
        String pathKey = pathKey(path);
        List<Expr> children = children(expr);
        List<String> childKeys = new ArrayList<>();
        for (int index = 0; index < children.size(); index++) {
            List<Integer> childPath = new ArrayList<>(path);
            childPath.add(index);
            childKeys.add(pathKey(childPath));
        }
        target.add(new PositionedNode(
            List.copyOf(path),
            pathKey,
            parentKey,
            List.copyOf(childKeys),
            expr,
            nodeKind(expr),
            label(expr),
            ExpressionFormatter.format(expr),
            depth,
            target.size()
        ));
        for (int index = 0; index < children.size(); index++) {
            List<Integer> childPath = new ArrayList<>(path);
            childPath.add(index);
            collect(children.get(index), childPath, pathKey, depth + 1, target);
        }
    }

    private List<Expr> children(Expr expr) {
        if (expr instanceof BinaryExpr binary) {
            return List.of(binary.left(), binary.right());
        }
        if (expr instanceof FunctionExpr function) {
            return function.arguments();
        }
        return List.of();
    }

    private String nodeKind(Expr expr) {
        if (expr instanceof BinaryExpr) {
            return "BINARY_OPERATOR";
        }
        if (expr instanceof FunctionExpr) {
            return "FUNCTION";
        }
        if (expr instanceof NumberExpr) {
            return "NUMBER";
        }
        if (expr instanceof VariableExpr) {
            return "VARIABLE";
        }
        return expr.getClass().getSimpleName().toUpperCase(Locale.ROOT);
    }

    private String label(Expr expr) {
        if (expr instanceof BinaryExpr binary) {
            return binary.operator().symbol();
        }
        if (expr instanceof FunctionExpr function) {
            return function.name();
        }
        if (expr instanceof VariableExpr variable) {
            return variable.name();
        }
        return ExpressionFormatter.format(expr);
    }

    private Expr replaceAt(Expr root, List<Integer> path, Expr replacement) {
        if (path.isEmpty()) {
            return replacement;
        }
        int index = path.getFirst();
        List<Integer> tail = path.subList(1, path.size());
        if (root instanceof BinaryExpr binary) {
            return switch (index) {
                case 0 -> new BinaryExpr(replaceAt(binary.left(), tail, replacement), binary.operator(), binary.right());
                case 1 -> new BinaryExpr(binary.left(), binary.operator(), replaceAt(binary.right(), tail, replacement));
                default -> throw new IllegalArgumentException("invalid binary child index " + index);
            };
        }
        if (root instanceof FunctionExpr function) {
            if (index < 0 || index >= function.arguments().size()) {
                throw new IllegalArgumentException("invalid function child index " + index);
            }
            List<Expr> arguments = new ArrayList<>(function.arguments());
            arguments.set(index, replaceAt(arguments.get(index), tail, replacement));
            return new FunctionExpr(function.name(), arguments);
        }
        throw new IllegalArgumentException("path descends into leaf");
    }

    private PatternExpr toPatternExpr(RulePatternNode node) {
        if (node instanceof PatternNumber number) {
            return PatternExpr.num(number.value());
        }
        if (node instanceof PatternVariable variable) {
            return PatternExpr.var(variable.name());
        }
        if (node instanceof PatternFunction function) {
            PatternExpr[] arguments = function.arguments().stream()
                .map(this::toPatternExpr)
                .toArray(PatternExpr[]::new);
            return PatternExpr.fn(function.name(), arguments);
        }
        PatternBinary binary = (PatternBinary) node;
        return PatternExpr.op(binary.op(), toPatternExpr(binary.left()), toPatternExpr(binary.right()));
    }

    private String canonical(String expression) {
        if (expression == null || expression.isBlank()) {
            return "";
        }
        try {
            return canonicalizer.canonicalize(expression);
        } catch (RuntimeException exception) {
            return expression.trim().replaceAll("\\s+", " ");
        }
    }

    private RuleOrigin originForPluginSource(String source) {
        String normalized = source == null ? "" : source.toLowerCase(Locale.ROOT);
        return normalized.contains(".regelsuche") || normalized.contains(".rules")
            || normalized.contains("rule-file") || normalized.contains("rule file")
            ? RuleOrigin.RULE_FILE
            : RuleOrigin.PLUGIN;
    }

    private String contextFingerprint(Context context) {
        return String.join("|",
            context.knowledgeProfile().name(),
            String.join(",", context.enabledPacks().stream().sorted().toList()),
            String.join(",", context.disabledPacks().stream().sorted().toList()),
            Boolean.toString(context.includePlugins()),
            Boolean.toString(context.includeLearnedMacros()),
            context.minMacroProofStatus().name(),
            context.searchProfile(),
            canonical(context.goalExpression()),
            String.join(",", context.assumptions())
        );
    }

    private String bindingKey(List<Binding> bindings) {
        return bindings.stream()
            .map(binding -> binding.name() + "=" + canonical(binding.value()) + ":" + binding.kind())
            .reduce((left, right) -> left + "|" + right)
            .orElse("");
    }

    private String macroRuleId(ReusableRule reusable) {
        return reusable.id().startsWith("macro_") ? reusable.id() : "macro_" + reusable.id();
    }

    private String pathKey(List<Integer> path) {
        if (path.isEmpty()) {
            return "root";
        }
        return path.stream().map(index -> String.format(Locale.ROOT, "%03d", index))
            .reduce((left, right) -> left + "." + right).orElse("root");
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                result.append(String.format(Locale.ROOT, "%02x", item & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String safeMessage(RuntimeException exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }

    @Override
    public void close() {
        pluginRuntime.close();
    }

    private static final Comparator<ApplicableMove> APPLICABLE_MOVE_ORDER = Comparator
        .comparing(ApplicableMove::pathKey, AstRuleRadarService::comparePathKeys)
        .thenComparing(ApplicableMove::orderingKey)
        .thenComparing(ApplicableMove::candidateId);

    private static int comparePathKeys(String left, String right) {
        if ("root".equals(left)) {
            return "root".equals(right) ? 0 : -1;
        }
        if ("root".equals(right)) {
            return 1;
        }
        String[] a = left.split("\\.");
        String[] b = right.split("\\.");
        int min = Math.min(a.length, b.length);
        for (int i = 0; i < min; i++) {
            int comparison = Integer.compare(Integer.parseInt(a[i]), Integer.parseInt(b[i]));
            if (comparison != 0) {
                return comparison;
            }
        }
        return Integer.compare(a.length, b.length);
    }

    private record PositionedNode(
        List<Integer> path,
        String pathKey,
        String parentPathKey,
        List<String> childPathKeys,
        Expr expr,
        String nodeKind,
        String label,
        String subtreeText,
        int depth,
        int preorderIndex
    ) {
    }

    private record RuleSource(
        RewriteRule rule,
        RuleOrigin origin,
        String sourceName,
        String displayName,
        String license,
        String sourceReference,
        String validationStatus
    ) {
    }

    private record ReusableMacro(ReusableRule reusable, PatternRewriteRule rule) {
    }

    private record TruncatedCandidates(
        List<ApplicableMove> returned,
        int omittedCount,
        Map<String, Integer> omittedByPath
    ) {
    }
}
