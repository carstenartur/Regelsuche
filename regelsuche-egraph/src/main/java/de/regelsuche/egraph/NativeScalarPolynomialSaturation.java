package de.regelsuche.egraph;

import de.regelsuche.assumption.AssumptionSignature;
import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.canonical.PolynomialNormalizer;
import de.regelsuche.json.JsonWriter;
import de.regelsuche.knowledge.RuleInventoryFingerprint;
import de.regelsuche.transform.PatternExpr;
import de.regelsuche.transform.PatternRewriteRule;
import de.regelsuche.transform.RewriteApplicabilitySchema;
import de.regelsuche.transform.RewriteRule;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** Opt-in scalar AC consumer of the existing binary e-graph. No formal proof DAG is issued. */
public final class NativeScalarPolynomialSaturation {
    public static final String CONTRACT = "regelsuche.native-scalar-polynomial-saturation/v1";
    public static final String CHECKER = "core.PolynomialNormalizer/exact-Q-normal-form-pair/v1";
    public enum Outcome { FIX_POINT_FOR_ADMITTED_FRAGMENT, ROUND_LIMIT_INCONCLUSIVE, MATCH_INCONCLUSIVE,
        BUDGET_INCONCLUSIVE, UNSUPPORTED_SOURCE, UNSUPPORTED_CONTEXT, REJECTED_REWRITE, CHECK_INCONCLUSIVE, TECHNICAL_FAILURE }
    public enum CheckStatus { EQUAL, NOT_EQUAL, UNAVAILABLE, BUDGET_INCONCLUSIVE, TECHNICAL_FAILURE }

    /** Every variable must be explicitly declared a commuting Q scalar. Facts never authorize unions. */
    public record ScalarContext(List<String> rationalVariables, List<String> assumptions) {
        public ScalarContext {
            rationalVariables = List.copyOf(rationalVariables); assumptions = List.copyOf(assumptions);
            if (rationalVariables.size() > 64 || assumptions.size() > 64
                    || rationalVariables.stream().anyMatch(value -> value.length() > 128)
                    || assumptions.stream().anyMatch(value -> value.length() > 256)) throw new IllegalArgumentException("context bound exceeded");
        }
        public String canonicalJson() {
            return ScalarPolynomialAcMatcher.exactJson(new JsonWriter().beginObject().property("schema", CONTRACT + "/context")
                .property("domain", "COMMUTATIVE_Q_SCALARS").stringArray("rationalVariables", rationalVariables)
                .stringArray("assumptions", assumptions)
                .property("assumptionSignature", AssumptionSignature.ofExpressions(assumptions).fingerprint()).endObject().toString());
        }
        public String contentHash() { return ScalarPolynomialAcMatcher.hash(canonicalJson()); }
    }
    public record Budget(int maxRounds, int maxGraphNodes, int maxExpressionNodes, ScalarPolynomialAcMatcher.Limits matching) {
        public Budget {
            Objects.requireNonNull(matching);
            if (maxRounds < 1 || maxRounds > 16 || maxGraphNodes < 1 || maxGraphNodes > 4096
                    || maxExpressionNodes < 1 || maxExpressionNodes > 256) throw new IllegalArgumentException("invalid scalar saturation bounds");
        }
        public static Budget defaults() { return new Budget(4, 512, 128, ScalarPolynomialAcMatcher.Limits.defaults()); }
        void write(JsonWriter json) {
            json.property("maxRounds", maxRounds).property("maxGraphNodes", maxGraphNodes).property("maxExpressionNodes", maxExpressionNodes)
                .property("maxAcOperands", matching.maxAcOperands()).property("maxResultsPerQuery", matching.maxResults())
                .property("maxSharedLogicalUnits", matching.maxWorkUnits()).property("maxExpressionDepth", 32);
        }
    }
    public record RuleEntry(String ruleId, String ruleHash, boolean admitted, String detailCode,
            Optional<ScalarPolynomialAcMatcher.Plan> sourcePlan, Optional<ScalarPolynomialAcMatcher.Plan> targetPlan) {
        void write(JsonWriter json) {
            json.property("ruleId", ruleId).property("ruleHash", ruleHash).property("admitted", admitted).property("detailCode", detailCode);
            if (ruleHash == null) json.property("ruleHashStatus", "UNAVAILABLE_BEFORE_RULE_ADMISSION");
            json.property("sourcePlan", sourcePlan.map(ScalarPolynomialAcMatcher.Plan::canonicalJson).orElse(null))
                .property("targetPlan", targetPlan.map(ScalarPolynomialAcMatcher.Plan::canonicalJson).orElse(null));
        }
    }
    public record Round(int number, long graphVersion, List<ScalarPolynomialAcMatcher.Result> queries, boolean complete) {
        public Round { queries = List.copyOf(queries); }
        void write(JsonWriter json) {
            json.property("number", number).property("graphVersion", graphVersion).property("complete", complete)
                .array("queries", values -> queries.forEach(query -> values.objectValue(query::write)));
        }
    }
    public record Check(Expr left, Expr right, Optional<Expr> leftNormalForm, Optional<Expr> rightNormalForm,
            String contextHash, CheckStatus status, String detailCode) {
        void write(JsonWriter json) {
            json.property("checker", CHECKER).property("left", left.toString()).property("right", right.toString())
                .property("leftNormalForm", leftNormalForm.map(Object::toString).orElse(null))
                .property("rightNormalForm", rightNormalForm.map(Object::toString).orElse(null))
                .property("contextHash", contextHash).property("status", status.name()).property("detailCode", detailCode);
        }
    }
    public record Application(String ruleId, String ruleHash, String sourcePlanHash, ScalarPolynomialAcMatcher.Match match,
            Optional<Check> primitiveCheck, Optional<Check> contextCheck, boolean unionApplied, String detailCode) {
        void write(JsonWriter json) {
            json.property("ruleId", ruleId).property("ruleHash", ruleHash).property("sourcePlanHash", sourcePlanHash).object("match", match::write)
                .property("unionApplied", unionApplied).property("detailCode", detailCode);
            if (primitiveCheck.isPresent()) json.object("primitiveCheck", primitiveCheck.orElseThrow()::write); else json.nullProperty("primitiveCheck");
            if (contextCheck.isPresent()) json.object("contextCheck", contextCheck.orElseThrow()::write); else json.nullProperty("contextCheck");
        }
    }

    /** Privately issued observations. Its content hash is an identity, not a proof certificate. */
    public static final class Result {
        private final Expr source; private final ScalarContext context; private final Budget budget;
        private final List<RuleEntry> rules; private final List<Round> rounds; private final List<Application> applications;
        private final Optional<Expr> expression; private final Optional<Check> finalCheck;
        private final Outcome outcome; private final String detailCode, contextHash, canonicalJson, contentHash;
        private final ScalarPolynomialAcMatcher.Work work;
        private final int expectedRuleCount;
        private Result(Execution run) {
            source = run.source; context = run.context; budget = run.budget; contextHash = run.contextHash;
            rules = List.copyOf(run.entries); rounds = List.copyOf(run.rounds); applications = List.copyOf(run.applications);
            expression = Optional.ofNullable(run.released); finalCheck = Optional.ofNullable(run.finalCheck);
            outcome = run.outcome; detailCode = run.detail; work = run.work.snapshot();
            expectedRuleCount = run.requested.size();
            var json = new JsonWriter().beginObject().property("schema", CONTRACT).property("matcher", ScalarPolynomialAcMatcher.CONTRACT)
                .property("fragment", ScalarPolynomialAcMatcher.FRAGMENT).property("source", run.sourceReceipt)
                .property("sourceReceiptStatus", run.sourceReceipt == null ? "UNAVAILABLE_BEFORE_INPUT_ADMISSION" : "RETAINED")
                .property("context", context.canonicalJson()).property("contextHash", contextHash).object("budget", budget::write)
                .stringArray("requestedRuleHashesInOrder", run.requestedRuleHashes)
                .array("rules", values -> rules.forEach(rule -> values.objectValue(rule::write)))
                .array("rounds", values -> rounds.forEach(round -> values.objectValue(round::write)))
                .array("applications", values -> applications.forEach(application -> values.objectValue(application::write)))
                .property("outcome", outcome.name()).property("detailCode", detailCode)
                .property("admittedFragmentFixedPoint", admittedFragmentFixedPoint()).property("requestedInventoryComplete", requestedInventoryComplete())
                .property("expression", expression.map(Object::toString).orElse(null));
            if (finalCheck.isPresent()) json.object("finalCheck", finalCheck.orElseThrow()::write); else json.nullProperty("finalCheck");
            if (run.requestedRuleHashes.contains(null))
                json.property("requestedRuleHashesStatus", "INCOMPLETE_BEFORE_RULE_ADMISSION");
            canonicalJson = ScalarPolynomialAcMatcher.exactJson(json.property("directUnions", directUnions()).property("graphNodes", run.graph.nodeCount())
                .property("formalProofDag", formalProofDagStatus()).property("normalizerInternalWork", "UNAVAILABLE")
                .property("primitiveExecutorInternalWork", "UNAVAILABLE")
                .property("graphExtractionRebuildInternalWork", "UNAVAILABLE").property("canonicalEncodingHashInternalWork", "UNAVAILABLE")
                .property("totalWork", totalWorkStatus()).object("logicalWork", work::write).endObject().toString());
            contentHash = ScalarPolynomialAcMatcher.hash(canonicalJson);
        }
        public Expr source() { return source; }
        public ScalarContext context() { return context; }
        public String contextHash() { return contextHash; }
        public Budget budget() { return budget; }
        public List<RuleEntry> rules() { return rules; }
        public List<Round> rounds() { return rounds; }
        public List<Application> applications() { return applications; }
        public Optional<Expr> expression() { return expression; }
        public Optional<Check> finalCheck() { return finalCheck; }
        public Outcome outcome() { return outcome; }
        public String detailCode() { return detailCode; }
        public ScalarPolynomialAcMatcher.Work work() { return work; }
        public boolean admittedFragmentFixedPoint() { return outcome == Outcome.FIX_POINT_FOR_ADMITTED_FRAGMENT; }
        public boolean requestedInventoryComplete() { return rules.size() == expectedRuleCount && rules.stream().allMatch(RuleEntry::admitted); }
        public long directUnions() { return applications.stream().filter(Application::unionApplied).count(); }
        public String formalProofDagStatus() { return "NOT_PRODUCED"; }
        public String totalWorkStatus() { return "UNAVAILABLE"; }
        public String canonicalJson() { return canonicalJson; }
        public String contentHash() { return contentHash; }
    }

    private NativeScalarPolynomialSaturation() { }
    static Result run(List<RewriteRule> rules, Expr source, ScalarContext context, Budget budget) {
        if (rules.size() > 32) throw new IllegalArgumentException("at most 32 rules in this bounded fragment");
        return new Execution(rules, Objects.requireNonNull(source), Objects.requireNonNull(context), Objects.requireNonNull(budget)).run();
    }
    private record Admitted(PatternRewriteRule rule, RuleEntry entry) { }
    private record Pending(Admitted rule, ScalarPolynomialAcMatcher.Match match) { }
    private record NodeAt(Expr expression, int depth) { }
    private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z_0-9]*");
    private static final Pattern FACT = Pattern.compile("([A-Za-z_][A-Za-z_0-9]*)\\s*(!=|=)\\s*0");

    private static String boundedRuleHash(RewriteRule rule) {
        if (rule instanceof PatternRewriteRule pattern) {
            try {
                ScalarPolynomialAcMatcher.admitPatternEncoding(pattern.source());
                ScalarPolynomialAcMatcher.admitPatternEncoding(pattern.target());
            } catch (IllegalArgumentException unsupported) { return null; }
        }
        return RuleInventoryFingerprint.ruleContentHash(rule);
    }

    private static final class Halt extends RuntimeException {
        final Outcome outcome; final String detail;
        Halt(Outcome outcome, String detail) { super(detail); this.outcome = outcome; this.detail = detail; }
    }
    private static final class Execution {
        final List<RewriteRule> requested; final Expr source; final ScalarContext context; final Budget budget;
        final ScalarPolynomialAcMatcher.Authority work; final String contextHash;
        final List<String> requestedRuleHashes;
        final EGraph graph = new EGraph(); final List<RuleEntry> entries = new ArrayList<>();
        final List<Admitted> admitted = new ArrayList<>(); final List<Round> rounds = new ArrayList<>();
        final List<Application> applications = new ArrayList<>();
        Outcome outcome = Outcome.ROUND_LIMIT_INCONCLUSIVE; String detail = "ROUND_LIMIT";
        String sourceReceipt; Expr released; Check finalCheck; EClassId root;
        Execution(List<RewriteRule> rules, Expr source, ScalarContext context, Budget budget) {
            this.requested = rules; this.source = source; this.context = context; this.budget = budget;
            work = new ScalarPolynomialAcMatcher.Authority(budget.matching().maxWorkUnits()); contextHash = context.contentHash();
            requestedRuleHashes = rules.stream().map(NativeScalarPolynomialSaturation::boundedRuleHash).toList();
        }
        Result run() {
            try {
                boundedTree(source); sourceReceipt = source.toString();
                validateContext(); validatePolynomial(source); compileRules();
                admitGraphNodes(source); work.charge("graphInsertDispatches", 1); root = graph.addExpression(source);
                for (int round = 1; round <= budget.maxRounds(); round++) {
                    if (!round(round)) break;
                }
            } catch (Halt halt) { outcome = halt.outcome; detail = halt.detail; }
            catch (ScalarPolynomialAcMatcher.Stop stop) { outcome = Outcome.BUDGET_INCONCLUSIVE; detail = stop.detail; }
            catch (RuntimeException failure) { outcome = Outcome.TECHNICAL_FAILURE; detail = failure.getClass().getName(); }
            if (root != null && outcome != Outcome.TECHNICAL_FAILURE) {
                try {
                    Expr extracted = extract(root); finalCheck = check(source, extracted);
                    if (finalCheck.status() == CheckStatus.EQUAL) released = extracted;
                    else { outcome = checkOutcome(finalCheck); detail = "FINAL_" + finalCheck.detailCode(); }
                } catch (ScalarPolynomialAcMatcher.Stop stop) { outcome = Outcome.BUDGET_INCONCLUSIVE; detail = stop.detail; }
                catch (RuntimeException failure) { outcome = Outcome.TECHNICAL_FAILURE; detail = failure.getClass().getName(); }
            }
            return new Result(this);
        }
        private void validateContext() {
            var variables = new HashSet<String>();
            for (var variable : context.rationalVariables()) {
                work.charge("contextEntriesChecked", 1);
                if (!NAME.matcher(variable).matches() || !variables.add(variable)) throw new Halt(Outcome.UNSUPPORTED_CONTEXT, "INVALID_SCALAR_DECLARATIONS");
            }
            var facts = new TreeMap<String, String>();
            for (var raw : context.assumptions()) {
                work.charge("contextEntriesChecked", 1);
                var fact = FACT.matcher(AssumptionSignature.normalizeExpression(raw));
                if (!fact.matches() || !variables.contains(fact.group(1))) throw new Halt(Outcome.UNSUPPORTED_CONTEXT, "OUTSIDE_SCALAR_ZERO_NONZERO_CONTEXT");
                var previous = facts.putIfAbsent(fact.group(1), fact.group(2));
                if (previous != null && !previous.equals(fact.group(2))) throw new Halt(Outcome.UNSUPPORTED_CONTEXT, "CONFLICTING_SCALAR_FACTS");
            }
        }
        private void compileRules() {
            var ids = new HashSet<String>();
            for (var rule : requested) {
                work.charge("ruleAdmissionDispatches", 1);
                if (!ids.add(rule.id())) throw new Halt(Outcome.REJECTED_REWRITE, "DUPLICATE_RULE_ID");
                String hash = requestedRuleHashes.get(entries.size()), reason = "NATIVE_SUPPORTED";
                ScalarPolynomialAcMatcher.Plan sourcePlan = null, targetPlan = null;
                if (hash == null) reason = "RULE_PATTERN_ENCODING_ADMISSION_LIMIT";
                else if (rule.getClass() != PatternRewriteRule.class) reason = "CUSTOM_EXECUTOR_NOT_ADMITTED";
                else if (!rule.isEquivalencePreservingByConstruction() || rule.mayEmitAssumptions()) reason = "RULE_NOT_UNCONDITIONAL_EQUIVALENCE";
                else try {
                    var pattern = (PatternRewriteRule) rule;
                    work.charge("planCompilationDispatches", 1); sourcePlan = ScalarPolynomialAcMatcher.compile(pattern.source());
                    work.charge("planCompilationDispatches", 1); targetPlan = ScalarPolynomialAcMatcher.compile(pattern.target());
                    if (!RewriteApplicabilitySchema.fromPatternRule(pattern).requiredAssumptions().isEmpty()) reason = "GUARDED_RULE_NOT_ADMITTED";
                    if (!placeholders(pattern.source()).containsAll(placeholders(pattern.target()))) reason = "UNBOUND_TARGET_PLACEHOLDER";
                    if (!context.rationalVariables().containsAll(literalVariables(pattern.source()))
                            || !context.rationalVariables().containsAll(literalVariables(pattern.target()))) reason = "UNDECLARED_LITERAL_SCALAR_VARIABLE";
                } catch (IllegalArgumentException unsupported) { reason = "OUTSIDE_SCALAR_POLYNOMIAL_RULE_FRAGMENT"; }
                var entry = new RuleEntry(rule.id(), hash, reason.equals("NATIVE_SUPPORTED"), reason, Optional.ofNullable(sourcePlan), Optional.ofNullable(targetPlan));
                entries.add(entry); if (entry.admitted()) admitted.add(new Admitted((PatternRewriteRule) rule, entry));
            }
        }
        private boolean round(int number) {
            work.charge("roundDispatches", 1); long version = graph.version();
            var roots = graph.classes().stream().map(EClass::id).sorted().toList();
            var queries = new ArrayList<ScalarPolynomialAcMatcher.Result>(); var pending = new ArrayList<Pending>();
            for (var rule : admitted) {
                var query = new ScalarPolynomialAcMatcher(graph).match(rule.entry().sourcePlan().orElseThrow(), roots, budget.matching(), work);
                queries.add(query);
                if (!query.complete()) {
                    rounds.add(new Round(number, version, queries, false)); outcome = Outcome.MATCH_INCONCLUSIVE; detail = query.detailCode(); return false;
                }
                query.matches().forEach(match -> pending.add(new Pending(rule, match)));
            }
            rounds.add(new Round(number, version, queries, true));
            long before = graph.version();
            for (var item : pending) apply(item);
            if (graph.version() == before) { outcome = Outcome.FIX_POINT_FOR_ADMITTED_FRAGMENT; detail = "COMPLETE_ATOMIC_FRAGMENT_ROUND_WITHOUT_GRAPH_CHANGE"; return false; }
            return true;
        }
        private void apply(Pending pending) {
            var rule = pending.rule(); var match = pending.match(); Check primitive = null, contextual = null;
            boolean union = false; String applicationDetail = "NOT_APPLIED";
            try {
                var bindings = new TreeMap<String, Expr>();
                for (var entry : new TreeMap<>(match.bindings()).entrySet()) bindings.put(entry.getKey(), extract(entry.getValue()));
                work.charge("primitiveInstantiationDispatches", 1); Expr left = rule.rule().source().instantiate(bindings); boundedTree(left); validatePolynomial(left);
                work.charge("primitiveReplayDispatches", 1);
                if (!rule.rule().matches(left) || !rule.rule().assumptions(left).isEmpty()) throw new Halt(Outcome.REJECTED_REWRITE, "PRIMITIVE_REPLAY_NOT_UNCONDITIONAL");
                Expr right = rule.rule().apply(left); boundedTree(right); validatePolynomial(right);
                primitive = check(left, right);
                if (primitive.status() != CheckStatus.EQUAL) throw new Halt(checkOutcome(primitive), "PRIMITIVE_" + primitive.detailCode());
                Expr candidate = right;
                for (var remainder : match.remainder()) { work.charge("contextOperandConstructions", 1); candidate = new BinaryExpr(candidate, match.contextOperator(), extract(remainder)); }
                boundedTree(candidate); validatePolynomial(candidate);
                contextual = check(extract(match.root()), candidate);
                if (contextual.status() != CheckStatus.EQUAL) throw new Halt(checkOutcome(contextual), "CONTEXT_" + contextual.detailCode());
                admitGraphNodes(candidate);
                // One indivisible logical admission before any mutation; rebuild internals remain unavailable.
                work.charge("checkedGraphMutationBatches", 1);
                EClassId rhs = graph.addExpression(candidate);
                if (!graph.areEquivalent(match.root(), rhs)) { graph.union(match.root(), rhs); union = true; }
                graph.rebuild(); applicationDetail = union ? "CHECKED_UNION" : "ALREADY_EQUIVALENT";
            } catch (Halt halt) { applicationDetail = halt.detail; throw halt; }
            catch (ScalarPolynomialAcMatcher.Stop stop) { applicationDetail = stop.detail; throw stop; }
            catch (RuntimeException failure) { applicationDetail = "TECHNICAL_FAILURE:" + failure.getClass().getName(); throw failure; }
            finally { applications.add(new Application(rule.rule().id(), rule.entry().ruleHash(), rule.entry().sourcePlan().orElseThrow().contentHash(), match,
                Optional.ofNullable(primitive), Optional.ofNullable(contextual), union, applicationDetail)); }
        }
        private Check check(Expr left, Expr right) {
            Optional<Expr> leftNormal = Optional.empty(), rightNormal = Optional.empty();
            CheckStatus status; String checkDetail;
            try {
                work.charge("exactNormalizerDispatches", 1); leftNormal = new PolynomialNormalizer().normalize(left);
                work.charge("exactNormalizerDispatches", 1); rightNormal = new PolynomialNormalizer().normalize(right);
                status = leftNormal.isEmpty() || rightNormal.isEmpty() ? CheckStatus.UNAVAILABLE
                    : leftNormal.equals(rightNormal) ? CheckStatus.EQUAL : CheckStatus.NOT_EQUAL;
                checkDetail = status == CheckStatus.UNAVAILABLE ? "NORMAL_FORM_UNAVAILABLE" : status.name();
            } catch (ScalarPolynomialAcMatcher.Stop stop) { status = CheckStatus.BUDGET_INCONCLUSIVE; checkDetail = stop.detail; }
            catch (RuntimeException failure) { status = CheckStatus.TECHNICAL_FAILURE; checkDetail = failure.getClass().getName(); }
            return new Check(left, right, leftNormal, rightNormal, contextHash, status, checkDetail);
        }
        private Expr extract(EClassId id) {
            work.charge("extractionDispatches", 1); return graph.extract(id, node -> 1);
        }
        private int boundedTree(Expr expression) {
            var pending = new ArrayDeque<NodeAt>(); pending.add(new NodeAt(expression, 1)); int count = 0;
            while (!pending.isEmpty()) {
                work.charge("expressionNodesChecked", 1); var next = pending.removeFirst();
                if (++count > budget.maxExpressionNodes() || next.depth() > 32) throw new Halt(Outcome.BUDGET_INCONCLUSIVE, "EXPRESSION_SIZE_OR_DEPTH_LIMIT");
                if (next.expression() instanceof NumberExpr number) {
                    try { ScalarPolynomialAcMatcher.requireNumber(number.value()); }
                    catch (IllegalArgumentException unsupported) { throw new Halt(Outcome.UNSUPPORTED_SOURCE, "SCALAR_COEFFICIENT_LIMIT"); }
                } else if (next.expression() instanceof VariableExpr variable && variable.name().length() > 128)
                    throw new Halt(Outcome.UNSUPPORTED_SOURCE, "SYMBOL_SIZE_LIMIT");
                if (next.expression() instanceof BinaryExpr binary) {
                    pending.add(new NodeAt(binary.left(), next.depth() + 1)); pending.add(new NodeAt(binary.right(), next.depth() + 1));
                } else if (next.expression() instanceof FunctionExpr function) {
                    if (function.name().length() > 128) throw new Halt(Outcome.UNSUPPORTED_SOURCE, "SYMBOL_SIZE_LIMIT");
                    if (function.arguments().size() > budget.maxExpressionNodes()) throw new Halt(Outcome.BUDGET_INCONCLUSIVE, "EXPRESSION_ARITY_LIMIT");
                    function.arguments().forEach(argument -> pending.add(new NodeAt(argument, next.depth() + 1)));
                }
            }
            return count;
        }
        private void validatePolynomial(Expr expression) {
            work.charge("polynomialFragmentNodesChecked", 1);
            if (expression instanceof NumberExpr number) {
                try { ScalarPolynomialAcMatcher.requireNumber(number.value()); }
                catch (IllegalArgumentException unsupported) { throw new Halt(Outcome.UNSUPPORTED_SOURCE, "SCALAR_COEFFICIENT_LIMIT"); }
            } else if (expression instanceof VariableExpr variable) {
                if (!context.rationalVariables().contains(variable.name())) throw new Halt(Outcome.UNSUPPORTED_SOURCE, "VARIABLE_NOT_DECLARED_COMMUTATIVE_SCALAR");
            } else if (expression instanceof BinaryExpr binary && binary.operator() != de.regelsuche.ast.BinaryOperator.DIV) {
                if (binary.operator() == de.regelsuche.ast.BinaryOperator.POW && (!(binary.right() instanceof NumberExpr exponent)
                        || !ScalarPolynomialAcMatcher.positiveExponent(exponent.value()))) throw new Halt(Outcome.UNSUPPORTED_SOURCE, "UNSUPPORTED_EXPONENT");
                validatePolynomial(binary.left()); validatePolynomial(binary.right());
            } else throw new Halt(Outcome.UNSUPPORTED_SOURCE, "OUTSIDE_SCALAR_POLYNOMIAL_SOURCE_FRAGMENT");
        }
        private void admitGraphNodes(Expr candidate) {
            int upperBound = boundedTree(candidate);
            if (graph.nodeCount() + upperBound > budget.maxGraphNodes()) throw new Halt(Outcome.BUDGET_INCONCLUSIVE, "GRAPH_NODE_ADMISSION_UPPER_BOUND");
        }
    }
    private static Outcome checkOutcome(Check check) {
        return switch (check.status()) {
            case NOT_EQUAL -> Outcome.REJECTED_REWRITE;
            case BUDGET_INCONCLUSIVE -> Outcome.BUDGET_INCONCLUSIVE;
            case TECHNICAL_FAILURE -> Outcome.TECHNICAL_FAILURE;
            case UNAVAILABLE -> Outcome.CHECK_INCONCLUSIVE;
            case EQUAL -> throw new IllegalArgumentException("successful check is not a stop outcome");
        };
    }
    private static Set<String> placeholders(PatternExpr expression) {
        var result = new HashSet<String>();
        if (expression instanceof PatternExpr.Placeholder variable) result.add(variable.name());
        else if (expression instanceof PatternExpr.Operation operation) { result.addAll(placeholders(operation.left())); result.addAll(placeholders(operation.right())); }
        return Set.copyOf(result);
    }
    private static Set<String> literalVariables(PatternExpr expression) {
        var result = new HashSet<String>();
        if (expression instanceof PatternExpr.LiteralVariable variable) result.add(variable.name());
        else if (expression instanceof PatternExpr.Operation operation) { result.addAll(literalVariables(operation.left())); result.addAll(literalVariables(operation.right())); }
        return Set.copyOf(result);
    }
}
