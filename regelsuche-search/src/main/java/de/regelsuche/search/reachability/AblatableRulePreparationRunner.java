package de.regelsuche.search.reachability;

import de.regelsuche.assumption.Assumption;
import de.regelsuche.assumption.AssumptionSignature;
import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.knowledge.RuleDescriptor;
import de.regelsuche.knowledge.RuleInventoryFingerprint;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.polynomial.PolynomialWorkAuthority;
import de.regelsuche.polynomial.PolynomialWorkLedger;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.EquivalentExpressionProvider;
import de.regelsuche.transform.ExprMatcher;
import de.regelsuche.transform.PatternMatchAnalyzer;
import de.regelsuche.transform.PatternRewriteRule;
import de.regelsuche.transform.RecognitionProfile;
import de.regelsuche.transform.RewriteApplicabilitySchema;
import de.regelsuche.transform.RewriteRule;
import de.regelsuche.transform.RulePreparationPlanner;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import static de.regelsuche.search.reachability.AmplificationJson.*;

/**
 * Source-only, separately ablatable preparation authority. No target, family,
 * fixture ID or qualification label can enter this API. Historical authorities
 * and their recognition semantics are unchanged.
 */
public final class AblatableRulePreparationRunner {
    public static final String ID = "regelsuche.ablatable-rule-preparation/v1";
    public static final String WORK = "regelsuche.amplification-logical-work/v1";
    public static final String GUARDS = "regelsuche.scalar-explicit-nonzero-guards/v1";
    public enum Profile { DIRECT_ONLY, THEORY_MATCHING, SAFE_EXACT_PREPARATION, SAFE_PREPARATION_PLUS_LOCAL_BRIDGE }

    public record Budget(PatternTargetedLocalBridgeSearch.Budget bridge, int maxNodes,
                         int maxExactAttempts, long maxLogicalUnits, long maxExactWorkUnits) {
        public Budget {
            Objects.requireNonNull(bridge);
            if (maxNodes < 1 || maxNodes > 256 || maxExactAttempts < 0 || maxExactAttempts > 256 || maxLogicalUnits < 0
                || maxLogicalUnits > 10_000_000 || maxExactWorkUnits < 0 || maxExactWorkUnits > 100_000_000
                || bridge.maxDepth() > 6 || bridge.maxVisitedStates() > 128 || bridge.maxGeneratedTransitions() > 1024
                || bridge.maxSuccessorsPerState() > 128 || bridge.maxMatchSteps() > 20_000 || bridge.maxPatternBranches() > 10_000)
                throw new IllegalArgumentException("invalid bounded amplification budget");
        }
        public static Budget publicControls() {
            return new Budget(new PatternTargetedLocalBridgeSearch.Budget(3, 64, 256, 8, 256, 64, 16, 5000, 2500), 256, 32, 200_000, 2_000_000);
        }
    }

    /** Scalar complex expressions with explicit nonzero/zero declarations only. */
    public record Source(String expression, List<String> assumptions) {
        public Source {
            if (expression == null || expression.length() > 4096 || assumptions == null || assumptions.size() > 32)
                throw new IllegalArgumentException("source boundary exceeded");
            int nesting = 0;
            for (char c : expression.toCharArray()) {
                if (c == '(' && ++nesting > 48) throw new IllegalArgumentException("source nesting exceeded");
                if (c == ')') nesting--;
            }
            Expr parsed = parse(expression);
            if (nodes(parsed).size() > 256) throw new IllegalArgumentException("source node boundary exceeded");
            scalar(parsed);
            expression = format(parsed);
            assumptions = assumptions.stream().map(AblatableRulePreparationRunner::declaration).distinct().sorted().toList();
        }
    }

    public record Candidate(String principalId, String stage, String output, List<String> retainedAssumptions,
                            List<String> primitiveRuleIds, List<String> exactPreparationStepIds, int preparationDepth,
                            int sourceAstNodes, int outputAstNodes, String certificateJson, String certificateHash) {
        public Candidate {
            retainedAssumptions = List.copyOf(retainedAssumptions);
            primitiveRuleIds = List.copyOf(primitiveRuleIds);
            exactPreparationStepIds = List.copyOf(exactPreparationStepIds);
            if (primitiveRuleIds.isEmpty() || !hashBytes(certificateJson.getBytes(java.nio.charset.StandardCharsets.UTF_8)).equals(certificateHash))
                throw new IllegalArgumentException("candidate certificate identity mismatch");
        }
    }

    public record Run(Profile profile, Source source, String configurationHash, List<Candidate> candidates,
                      String canonicalJson, String contentHash) {
        public Run {
            candidates = List.copyOf(candidates);
            if (!hashBytes(canonicalJson.getBytes(java.nio.charset.StandardCharsets.UTF_8)).equals(contentHash))
                throw new IllegalArgumentException("run hash mismatch");
        }
    }

    private final List<PatternRewriteRule> principals;
    private final List<RewriteRule> preparation;
    private final String revision;
    private final Budget budget;

    public AblatableRulePreparationRunner(List<PatternRewriteRule> principals, List<? extends RewriteRule> preparation,
                                         String revision, Budget budget) {
        this.principals = List.copyOf(principals);
        this.preparation = List.copyOf(preparation);
        if (principals.isEmpty() || principals.size() > 16 || preparation.size() > 16 || !revision.matches("[0-9a-f]{40}"))
            throw new IllegalArgumentException("bounded inventory and commit revision required");
        var ids = new java.util.HashSet<String>();
        for (var rule : principals) {
            if (rule.getClass() != PatternRewriteRule.class || !rule.isEquivalencePreservingByConstruction() || !ids.add(rule.id()))
                throw new IllegalArgumentException("only trusted declarative principals are admitted");
        }
        for (var rule : preparation) {
            if (!ids.add(rule.id()) || preparationSchema(rule) == null)
                throw new IllegalArgumentException("preparer requires disjoint explicit safe schema");
        }
        this.revision = revision;
        this.budget = Objects.requireNonNull(budget);
    }

    public Map<String, Object> configuration(Profile profile) {
        return fields("schema", ID, "profile", profile, "repositoryRevision", revision, "budget", budget,
            "principalInventory", RuleInventoryFingerprint.contentHash(principals),
            "preparationInventory", RuleInventoryFingerprint.contentHash(preparation),
            "principalExecutionOrder", principals.stream().map(RewriteRule::id).toList(),
            "preparationExecutionOrder", preparation.stream().map(RewriteRule::id).toList(),
            "recognition", profile == Profile.DIRECT_ONLY ? RecognitionProfile.exact() : RecognitionProfile.arithmeticAc(),
            "exactPreparer", profile.ordinal() >= 2 ? RulePreparationPlanner.PLANNER_ID : "DISABLED",
            "exactWorkContract", RulePreparationPlanner.MEASUREMENT_CONTRACT,
            "bridge", profile.ordinal() >= 3 ? PatternTargetedLocalBridgeSearch.SEARCH_ID : "DISABLED",
            "guards", GUARDS, "work", WORK, "selection", "FIRST_VERIFIED_PER_PRINCIPAL_CHEAPEST_STAGE_AST_PREORDER",
            "domain", "SCALAR_COMPLEX_SIN_COS_RATIONAL_INTEGER_POWERS");
    }

    public Run analyze(Profile profile, Source source) {
        Objects.requireNonNull(profile); Objects.requireNonNull(source);
        var work = new Ledger(budget.maxLogicalUnits());
        var exactWork = new ExactWorkAuthority(budget.maxExactWorkUnits());
        var stages = new ArrayList<Object>();
        var candidates = new LinkedHashMap<String, Candidate>();
        var guards = new Guards(source.assumptions(), work);
        Expr root = parse(source.expression());
        work.add("sourceNodes", nodes(root).size());
        work.add("inventoryEntries", principals.size() + preparation.size());
        List<String> domainRequirements = nodes(root).stream().map(Node::expression)
            .filter(expression -> expression instanceof BinaryExpr binary && binary.operator() == de.regelsuche.ast.BinaryOperator.DIV)
            .map(expression -> format(((BinaryExpr) expression).right()) + " != 0").toList();
        String sourceDomain = work.exhausted() ? "NOT_EVALUATED" : guards.check(domainRequirements);
        if (guards.conflict() || nodes(root).size() > budget.maxNodes() || !sourceDomain.equals("TRUE")) {
            stages.add(fields("status", guards.conflict() ? "GUARD_CONFLICT" : work.exhausted() || nodes(root).size() > budget.maxNodes()
                ? "BUDGET_INCONCLUSIVE" : "GUARD_" + sourceDomain, "sourceDomainRequirements", domainRequirements));
        } else {
            runStages(profile, source, root, guards, work, exactWork, stages, candidates);
        }
        String configHash = hash(configuration(profile));
        String json = canonical(fields("schema", ID, "configurationHash", configHash, "configuration", configuration(profile),
            "source", source, "candidates", candidates.values(), "stages", stages, "work", work.observation(),
            "exactPreparationWork", exactWork.observation(),
            "innerExactArithmeticWork", "OBSERVED_TYPED_OPERATIONS_AND_OPERAND_WIDTHS_UNDER_DECLARED_EXACT_CONTRACT",
            "sourceProjectionPrincipalAndEnvelopeInternalWork", "UNAVAILABLE_NOT_IN_EXACT_PREPARATION_CONTRACT",
            "bridgeVerifierInternalWork", "UNAVAILABLE_EXISTING_AUTHORITY_EXPOSES_REPLAY_STATUS_ONLY",
            "runtimeNanos", "DIAGNOSTIC_ONLY_NOT_RETAINED", "comparativeMatchedWorkGate", "BLOCKED_UNAVAILABLE_INTERNAL_WORK"));
        return new Run(profile, source, configHash, List.copyOf(candidates.values()), json,
            hashBytes(json.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private void runStages(Profile profile, Source source, Expr root, Guards guards, Ledger work,
                           ExactWorkAuthority exactWork, List<Object> stages, Map<String, Candidate> candidates) {
        for (Profile stage : Profile.values()) {
            if (stage.ordinal() > profile.ordinal()) break;
            if (work.exhausted()) { stages.add(fields("stage", stage, "status", "BUDGET_INCONCLUSIVE")); break; }
            List<PatternRewriteRule> unresolved = principals.stream().filter(rule -> !candidates.containsKey(rule.id())).toList();
            if (unresolved.isEmpty()) break;
            if (!runStage(stage, source, root, unresolved, guards, work, exactWork, stages, candidates)) break;
        }
    }

    private boolean runStage(Profile stage, Source source, Expr root, List<PatternRewriteRule> unresolved,
                             Guards guards, Ledger work, ExactWorkAuthority exactWork,
                             List<Object> stages, Map<String, Candidate> candidates) {
        long before = work.total();
        var observations = new ArrayList<Object>();
        boolean failed = false;
        String status = "COMPLETED";
        long exactBefore = exactWork.total;
        try { switch (stage) {
            case DIRECT_ONLY, THEORY_MATCHING -> match(root, root, unresolved, stage, guards, work,
                List.of(), List.of(), observations, candidates);
            case SAFE_EXACT_PREPARATION -> status = exact(root, unresolved, guards, work, exactWork, observations, candidates);
            case SAFE_PREPARATION_PLUS_LOCAL_BRIDGE -> bridge(source, unresolved, guards, work, observations, candidates);
        }
        } catch (RuntimeException failure) {
            failed = true;
            observations.add(fields("status", "TECHNICAL_FAILURE", "failureClass", failure.getClass().getName()));
        }
        stages.add(fields("stage", stage, "chargedUnits", work.total() - before,
            "exactPreparationChargedUnits", exactWork.total - exactBefore,
            "status", failed ? "TECHNICAL_FAILURE" : work.exhausted() ? "BUDGET_INCONCLUSIVE" : status, "observations", observations));
        return !failed && status.equals("COMPLETED");
    }

    /** Fresh complete execution, including certificates and every charged counter. */
    public boolean verify(Run retained) {
        if (retained == null) return false;
        return analyze(retained.profile(), retained.source()).equals(retained);
    }

    private void match(Expr original, Expr terminal, List<PatternRewriteRule> rules, Profile stage, Guards guards,
                       Ledger work, List<String> preparationIds, List<Object> preparationEvidence,
                       List<Object> observations, Map<String, Candidate> candidates) {
        for (var rule : rules) {
            if (candidates.containsKey(rule.id()) || work.exhausted()) continue;
            matchRule(original, terminal, rule, stage, guards, work, preparationIds, preparationEvidence, observations, candidates);
        }
    }

    private void matchRule(Expr original, Expr terminal, PatternRewriteRule rule, Profile stage, Guards guards,
                           Ledger work, List<String> preparationIds, List<Object> preparationEvidence,
                           List<Object> observations, Map<String, Candidate> candidates) {
        PatternRewriteRule executor = adapted(rule, stage == Profile.DIRECT_ONLY ? RecognitionProfile.exact() : RecognitionProfile.arithmeticAc());
        var schema = RewriteApplicabilitySchema.fromPatternRule(executor);
        for (var node : nodes(terminal)) {
            if (work.exhausted()) break;
            var analysis = analyze(schema, node.expression(), work);
            if (!analysis.matched()) {
                observations.add(fields("principalId", rule.id(), "path", node.path(), "matchStatus", analysis.status(),
                    "detail", analysis.detailCode(), "evaluatedSteps", analysis.evaluatedSteps()));
                continue;
            }
            List<String> required = schema.requiredAssumptions().stream().map(template -> template.instantiate(analysis.bindings()).expression()).toList();
            String guard = guards.check(required);
            observations.add(fields("principalId", rule.id(), "path", node.path(), "terminal", format(terminal),
                "bindings", analysis.bindings(), "guard", guard, "required", required));
            if (!guard.equals("TRUE")) continue;
            work.add("principalConcreteMatch", 1);
            if (!executor.matches(node.expression())) continue;
            work.add("principalConcreteApply", 1);
            Expr local = executor.apply(node.expression());
            Expr result = replace(terminal, node.path(), local);
            if (format(result).equals(format(terminal))) continue;
            int producedNodes = nodes(result).size();
            work.add("producedAstNodes", producedNodes);
            int exactSteps = stage == Profile.SAFE_EXACT_PREPARATION ? 1 : 0;
            if (producedNodes > budget.maxNodes() || preparationIds.size() + exactSteps + 1 > budget.bridge().maxPrimitiveSteps()
                || preparationIds.size() + exactSteps > budget.bridge().maxDepth()) {
                observations.add(fields("principalId", rule.id(), "status", "BUDGET_INCONCLUSIVE", "limit", "OUTPUT_NODES_OR_TOTAL_PRIMITIVE_AND_EXACT_STEPS"));
                continue;
            }
            work.add("principalIndependentReplay", 1);
            if (!executor.matches(node.expression()) || !executor.apply(node.expression()).equals(local))
                throw new IllegalStateException("principal replay differs");
            var ids = new ArrayList<>(preparationIds); ids.add(rule.id());
            var certificate = fields("schema", "regelsuche.amplification-primitive-certificate/v1", "source", format(original),
                "terminal", format(terminal), "output", format(result), "occurrence", node.path(),
                "schemaHash", schema.contentHash(), "principalRuleHash", RuleInventoryFingerprint.ruleContentHash(rule),
                "principalBindings", analysis.bindings(), "required", required, "guards", guard,
                "retainedAssumptions", guards.source, "preparation", preparationEvidence, "primitiveRuleIds", ids,
                "exactPreparationStepIds", stage == Profile.SAFE_EXACT_PREPARATION ? List.of(RulePreparationPlanner.PREPARATION_RULE_ID) : List.of(),
                "principalExpressionBefore", format(node.expression()), "principalExpressionAfter", format(local));
            retain(rule.id(), stage, format(result), guards, ids, certificate, work, candidates);
            break;
        }
    }

    private String exact(Expr root, List<PatternRewriteRule> rules, Guards guards, Ledger work, ExactWorkAuthority exactWork,
                       List<Object> observations, Map<String, Candidate> candidates) {
        var cancel = preparation.stream().filter(rule -> rule.id().equals(RulePreparationPlanner.PRINCIPAL_RULE_ID)).findFirst();
        if (cancel.isEmpty()) { observations.add(fields("status", "UNSUPPORTED", "detail", "EXACT_PREPARER_PRINCIPAL_NOT_VISIBLE")); return "COMPLETED"; }
        // Freeze the exact native executor identity too, not merely its familiar ID.
        var nativeCancel = AstRewriteTransformationEngine.allBuiltInRules().stream().filter(rule -> rule.id().equals(cancel.get().id())).findFirst().orElseThrow();
        if (!RuleInventoryFingerprint.ruleContentHash(nativeCancel).equals(RuleInventoryFingerprint.ruleContentHash(cancel.get())))
            throw new IllegalArgumentException("exact preparer executor identity differs from native authority");
        int attempts = 0;
        var planner = new RulePreparationPlanner();
        for (var node : nodes(root)) {
            if (work.exhausted() || rules.stream().allMatch(rule -> candidates.containsKey(rule.id()))) break;
            if (!(node.expression() instanceof BinaryExpr binary) || binary.operator() != de.regelsuche.ast.BinaryOperator.DIV) continue;
            if (attempts++ >= budget.maxExactAttempts()) { observations.add(fields("status", "BUDGET_INCONCLUSIVE", "limit", "EXACT_ATTEMPTS")); return "BUDGET_INCONCLUSIVE"; }
            String guard = guards.check(List.of(format(binary.right()) + " != 0"));
            if (!guard.equals("TRUE")) { observations.add(fields("path", node.path(), "status", "GUARD_" + guard)); continue; }
            work.add("exactPlanCalls", 1);
            var before = exactWork.observation();
            var measured = planner.planObserved(node.expression(), exactWork);
            var observation = fields("path", node.path(), "plan", measuredAttempt(measured),
                "authorityBefore", before, "authorityAfterPlan", exactWork.observation());
            observations.add(observation);
            if (!measured.completed()) return measured.outcome().name();
            var attempt = measured.attempt().orElseThrow();
            work.add("exactSolverAttempts", attempt.work().consumedSolverAttempts());
            // The observed positive plan includes internal verification, using this same authority.
            if (attempt.application().isPresent()) work.add("exactInternalCertificateVerifications", 1);
            if (attempt.status().name().equals("BUDGET_INCONCLUSIVE")) return "BUDGET_INCONCLUSIVE";
            if (attempt.application().isEmpty()) continue;
            var application = attempt.application().orElseThrow();
            work.add("exactIndependentCertificateVerifications", 1);
            var verified = planner.verifyObserved(application, exactWork);
            var verification = fields("input", verified.input(), "verified", verified.verified(), "outcome", verified.outcome(),
                "completed", verified.completed(), "detailCode", verified.detailCode(), "failureClass", verified.failureClass(),
                "measurementContract", verified.measurementContract(), "work", verified.work(), "refusedCharge", verified.refusedCharge());
            observation.put("verification", verification);
            observation.put("authorityAfterVerification", exactWork.observation());
            if (!verified.completed()) return verified.outcome().name();
            if (!verified.verified()) throw new IllegalStateException("exact certificate invalid");
            var prepared = application.preparedSubtree();
            var executor = cancel.orElseThrow();
            work.add("preparerConcreteReplay", 1);
            if (!executor.matches(prepared)) throw new IllegalStateException("visible cancellation cannot replay exact terminal");
            Expr output = executor.apply(prepared);
            if (!output.equals(application.resultSubtree())) throw new IllegalStateException("exact output differs from concrete replay");
            var terminal = replace(root, node.path(), output);
            var evidence = fields("path", node.path(), "expressionBefore", format(root), "expressionAfter", format(terminal),
                "application", application, "observedPreparation", observation);
            match(root, terminal, rules, Profile.SAFE_EXACT_PREPARATION, guards, work,
                List.of(executor.id()), List.of(evidence), observations, candidates);
        }
        return "COMPLETED";
    }

    private static Map<String, Object> measuredAttempt(RulePreparationPlanner.MeasuredAttempt measured) {
        return fields("input", measured.input(), "attempt", measured.attempt(), "outcome", measured.outcome(),
            "completed", measured.completed(), "detailCode", measured.detailCode(), "failureClass", measured.failureClass(),
            "measurementContract", measured.measurementContract(), "work", measured.work(), "refusedCharge", measured.refusedCharge());
    }

    private void bridge(Source source, List<PatternRewriteRule> rules, Guards guards, Ledger work,
                        List<Object> observations, Map<String, Candidate> candidates) {
        var guarded = preparation.stream().map(rule -> (RewriteRule) new GuardedRule(rule, guards, work)).toList();
        for (var rule : rules) {
            if (work.exhausted()) break;
            var executor = adapted(rule, RecognitionProfile.arithmeticAc());
            var search = new PatternTargetedLocalBridgeSearch(executor, guarded, revision, budget.bridge());
            work.add("bridgeDispatch", 1);
            var attempt = search.analyze(source.expression(), AssumptionSignature.ofExpressions(source.assumptions()));
            work.add("bridgeLogicalWork", bridgeUnits(attempt.work()));
            observations.add(fields("principalId", rule.id(), "attempt", attempt));
            if (attempt.bridge().isEmpty()) continue;
            var bridge = attempt.bridge().orElseThrow();
            var schema = RewriteApplicabilitySchema.fromPatternRule(executor);
            var terminal = analyze(schema, parse(bridge.terminalExpression()), work);
            var required = schema.requiredAssumptions().stream().map(template -> template.instantiate(terminal.bindings()).expression()).toList();
            String guard = guards.check(required);
            if (!guard.equals("TRUE") || !guards.check(bridge.resultAssumptions().normalizedAssumptions()).equals("TRUE")) {
                observations.add(fields("principalId", rule.id(), "status", "GUARD_" + guard)); continue;
            }
            work.add("bridgeIndependentRecomputationCalls", 1);
            var recomputed = search.analyze(source.expression(), AssumptionSignature.ofExpressions(source.assumptions()));
            work.add("bridgeRecomputationLogicalWork", bridgeUnits(recomputed.work()));
            if (!attempt.equals(recomputed)) throw new IllegalStateException("bridge work or candidate recomputation differs");
            work.add("bridgeIndependentVerificationCalls", 1);
            var verified = search.verify(bridge);
            // The verifier replays the retained primitive path; its inner matcher work is not exposed.
            work.add("bridgeVerificationPrimitiveReplays", bridge.primitiveRuleIds().size());
            if (!verified.valid()) { observations.add(fields("status", "INVALID_CERTIFICATE", "detail", verified.detailCode())); continue; }
            retain(rule.id(), Profile.SAFE_PREPARATION_PLUS_LOCAL_BRIDGE, bridge.resultExpression(), guards,
                bridge.primitiveRuleIds(), fields("bridge", bridge, "verification", verified, "required", required,
                    "sourceOnlyGuardPolicy", GUARDS), work, candidates);
        }
    }

    private static void retain(String principal, Profile stage, String output, Guards guards, List<String> ids,
                               Map<String, Object> certificate, Ledger work, Map<String, Candidate> candidates) {
        if (work.exhausted()) return;
        String json = canonical(certificate);
        var exactIds = stage == Profile.SAFE_EXACT_PREPARATION ? List.of(RulePreparationPlanner.PREPARATION_RULE_ID) : List.<String>of();
        Object bridge = certificate.get("bridge");
        String source = bridge instanceof PatternTargetedLocalBridgeSearch.Bridge retained ? retained.sourceExpression() : (String) certificate.get("source");
        candidates.putIfAbsent(principal, new Candidate(principal, stage.name(), output, guards.source, ids, exactIds,
            ids.size() + exactIds.size() - 1, nodes(parse(source)).size(), nodes(parse(output)).size(), json,
            hashBytes(json.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
    }

    private PatternMatchAnalyzer.Analysis analyze(RewriteApplicabilitySchema schema, Expr expression, Ledger work) {
        work.add("matcherCalls", 1);
        var analysis = new PatternMatchAnalyzer().analyze(schema.pattern(), expression, schema.recognitionProfile(),
            new ExprMatcher.MatchOptions(EquivalentExpressionProvider.identity(), budget.bridge().maxMatchResults(),
                budget.bridge().maxMatchSteps(), budget.bridge().maxPatternBranches()));
        work.add("matcherEvaluatedSteps", analysis.evaluatedSteps());
        work.add("matcherPatternBranches", analysis.patternBranches());
        work.add("matcherStructuralComparisons", analysis.structuralComparisons());
        return analysis;
    }

    private static PatternRewriteRule adapted(PatternRewriteRule original, RecognitionProfile recognition) {
        return new PatternRewriteRule(original.id(), original.source(), original.target(), original.kind(),
            original.mayIncreaseComplexity(), original.estimatedCostDelta(), original.isEquivalencePreservingByConstruction(),
            original.descriptor(), recognition);
    }

    private static RewriteApplicabilitySchema preparationSchema(RewriteRule rule) {
        var coverage = RewriteApplicabilitySchema.coverageOf(rule);
        if (coverage.safeProfileEligible()) return coverage.schema();
        // This is a declared contract for one content-checked native authority,
        // never schema inference from a user supplied familiar rule ID.
        if (rule.id().equals(RulePreparationPlanner.PRINCIPAL_RULE_ID)) {
            var nativeRule = AstRewriteTransformationEngine.allBuiltInRules().stream()
                .filter(value -> value.id().equals(RulePreparationPlanner.PRINCIPAL_RULE_ID)).findFirst().orElseThrow();
            if (RuleInventoryFingerprint.ruleContentHash(rule).equals(RuleInventoryFingerprint.ruleContentHash(nativeRule))) {
                var a = de.regelsuche.transform.PatternExpr.var("A");
                var b = de.regelsuche.transform.PatternExpr.var("B");
                var product = de.regelsuche.transform.PatternExpr.op(de.regelsuche.ast.BinaryOperator.MUL, a, b);
                return new RewriteApplicabilitySchema("amplification-native-cancellation-nonzero/v1", rule,
                    de.regelsuche.transform.PatternExpr.op(de.regelsuche.ast.BinaryOperator.DIV, product, a),
                    RecognitionProfile.arithmeticAc(), List.of(de.regelsuche.transform.RequiredAssumptionTemplate.nonZero(a)));
            }
        }
        return null;
    }

    private final class GuardedRule implements RewriteRule {
        private final RewriteRule rule; private final Guards guards; private final Ledger work;
        GuardedRule(RewriteRule rule, Guards guards, Ledger work) { this.rule = rule; this.guards = guards; this.work = work; }
        public String id() { return rule.id(); }
        public RuleDescriptor descriptor() { return rule.descriptor(); }
        public de.regelsuche.transform.RewriteKind kind() { return rule.kind(); }
        public boolean mayIncreaseComplexity() { return rule.mayIncreaseComplexity(); }
        public int estimatedCostDelta() { return rule.estimatedCostDelta(); }
        public boolean isEquivalencePreservingByConstruction() { return rule.isEquivalencePreservingByConstruction(); }
        public boolean matches(Expr expression) {
            // The content-checked native cancellation authority has two exact
            // syntactic orientations. Its concrete divisor, not an ambiguous
            // AC placeholder binding, owns the nonzero requirement.
            if (rule.id().equals(RulePreparationPlanner.PRINCIPAL_RULE_ID)) {
                work.add("preparerMatchCalls", 1);
                if (!rule.matches(expression)) return false;
                var divisor = ((BinaryExpr) expression).right();
                return guards.check(List.of(format(divisor) + " != 0")).equals("TRUE");
            }
            var schema = preparationSchema(rule);
            var analysis = analyze(schema, expression, work);
            if (!analysis.matched()) return false;
            var required = schema.requiredAssumptions().stream().map(template -> template.instantiate(analysis.bindings()).expression()).toList();
            if (!guards.check(required).equals("TRUE")) return false;
            work.add("preparerMatchCalls", 1);
            return rule.matches(expression);
        }
        public Expr apply(Expr expression) { work.add("preparerApplyCalls", 1); return rule.apply(expression); }
        public List<Assumption> assumptions(Expr expression) {
            work.add("preparerAssumptionCalls", 1);
            var emitted = rule.assumptions(expression);
            if (!guards.check(emitted.stream().map(Assumption::expression).toList()).equals("TRUE"))
                throw new IllegalArgumentException("preparer emitted undeclared guard");
            return emitted;
        }
        public boolean mayEmitAssumptions() { return rule.mayEmitAssumptions(); }
    }

    private static final class Guards {
        final List<String> source; final Set<String> known; final Ledger work;
        Guards(List<String> source, Ledger work) { this.source = source; this.known = Set.copyOf(source); this.work = work; }
        boolean conflict() { return source.stream().anyMatch(value -> value.endsWith(" != 0") && known.contains(value.replace(" != 0", " = 0"))); }
        String check(List<String> required) {
            for (String raw : required) {
                work.add("guardChecks", 1);
                String need = declaration(raw);
                if (conflict()) { work.add("guardConflicts", 1); return "CONFLICT"; }
                // Numeric facts precede supplied declarations: 0 != 0 cannot authorize.
                Expr subject = parse(need.substring(0, need.lastIndexOf(need.contains(" != ") ? " != " : " = ")));
                if (subject instanceof NumberExpr number) {
                    boolean trueFact = need.endsWith(" != 0") != number.value().isZero();
                    if (trueFact) continue;
                    work.add("guardFalse", 1); return "FALSE";
                }
                if (known.contains(need)) continue;
                if (need.endsWith(" != 0") && known.contains(need.replace(" != 0", " = 0"))) { work.add("guardFalse", 1); return "FALSE"; }
                work.add("guardUnknown", 1); return "UNKNOWN";
            }
            return "TRUE";
        }
    }

    private static final class Ledger {
        private final Map<String, Long> counters = new TreeMap<>(); private final long limit;
        Ledger(long limit) { this.limit = limit; }
        void add(String key, long value) { counters.merge(key, value, Math::addExact); }
        long total() { return counters.values().stream().reduce(0L, Math::addExact); }
        boolean exhausted() { return total() > limit; }
        Map<String, Object> observation() { return fields("revision", WORK, "counters", counters, "chargedUnits", total(), "limit", limit,
            "convention", "LOGICAL_BATCH_ACCOUNTING_ALL_SPENT_WORK_RETAINED; bounded batch may cross retention limit"); }
    }

    /** One instance owns all plan/internal verification/independent verification charges in a Run. */
    private static final class ExactWorkAuthority implements PolynomialWorkAuthority {
        private final Map<String, Long> counters = new TreeMap<>();
        private final long limit;
        private long total;
        ExactWorkAuthority(long limit) { this.limit = limit; }
        @Override public void consume(PolynomialWorkLedger charge) {
            long units = charge.totalWorkUnits();
            if (units > limit - total) throw new PolynomialWorkAuthority.LimitReached();
            charge.stages().forEach((key, value) -> counters.merge(key, value, Math::addExact));
            total += units;
        }
        @Override public void consume(String stage, long units) {
            if (stage == null || stage.isBlank() || units < 0) throw new IllegalArgumentException("invalid exact work charge");
            if (units > limit - total) throw new PolynomialWorkAuthority.LimitReached();
            counters.merge(stage, units, Math::addExact);
            total += units;
        }
        @Override public long remainingOpaqueWorkUnits() { return limit - total; }
        Map<String, Object> observation() {
            return fields("revision", RulePreparationPlanner.MEASUREMENT_CONTRACT, "counters", Map.copyOf(counters),
                "chargedUnits", total, "limit", limit, "admission", "ATOMIC_BEFORE_OPERATION_ONE_CUMULATIVE_RUN_AUTHORITY");
        }
    }

    private static long bridgeUnits(PatternTargetedLocalBridgeSearch.Work work) {
        return (long) work.expandedStates() + work.generatedTransitions() + work.discoveredStates()
            + work.retainedTransitions() + work.duplicateTransitions() + work.analyzedCandidates();
    }
    private static String declaration(String value) {
        if (value == null || value.length() > 512) throw new IllegalArgumentException("invalid scalar guard");
        var matcher = java.util.regex.Pattern.compile("^(.+?)\\s*(!=|==|=)\\s*0$").matcher(value.trim());
        if (!matcher.matches()) throw new IllegalArgumentException("only explicit scalar zero/nonzero guards are supported");
        Expr subject = parse(matcher.group(1)); scalar(subject);
        return format(subject) + (matcher.group(2).equals("!=") ? " != 0" : " = 0");
    }
    private static Expr parse(String text) { return new ExpressionParser().parseTerm(text); }
    private static String format(Expr expression) { return ExpressionFormatter.format(expression); }
    private static void scalar(Expr expression) {
        for (var node : nodes(expression)) {
            if (node.expression() instanceof FunctionExpr function && (!List.of("sin", "cos").contains(function.name()) || function.arguments().size() != 1))
                throw new IllegalArgumentException("unsupported scalar function/domain");
            if (node.expression() instanceof BinaryExpr binary && binary.operator() == de.regelsuche.ast.BinaryOperator.POW) {
                if (!(binary.right() instanceof NumberExpr number) || !number.value().isInteger()
                    || number.value().numerator().signum() < 0 || number.value().numerator().bitLength() > 5)
                    throw new IllegalArgumentException("only bounded nonnegative integer powers supported");
            }
        }
    }
    private record Node(List<Integer> path, Expr expression) { }
    private static List<Node> nodes(Expr root) { var result = new ArrayList<Node>(); collect(root, List.of(), result); return result; }
    private static void collect(Expr expression, List<Integer> path, List<Node> result) {
        result.add(new Node(path, expression));
        var children = children(expression);
        for (int i = 0; i < children.size(); i++) { var next = new ArrayList<>(path); next.add(i); collect(children.get(i), List.copyOf(next), result); }
    }
    private static List<Expr> children(Expr expression) {
        if (expression instanceof BinaryExpr binary) return List.of(binary.left(), binary.right());
        if (expression instanceof FunctionExpr function) return function.arguments();
        return List.of();
    }
    private static Expr replace(Expr root, List<Integer> path, Expr replacement) {
        if (path.isEmpty()) return replacement;
        var children = new ArrayList<>(children(root));
        children.set(path.getFirst(), replace(children.get(path.getFirst()), path.subList(1, path.size()), replacement));
        if (root instanceof BinaryExpr binary) return new BinaryExpr(children.get(0), binary.operator(), children.get(1));
        return new FunctionExpr(((FunctionExpr) root).name(), children);
    }
}
