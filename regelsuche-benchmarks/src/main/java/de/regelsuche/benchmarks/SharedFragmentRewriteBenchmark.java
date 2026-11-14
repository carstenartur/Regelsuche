package de.regelsuche.benchmarks;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.canonical.PolynomialNormalizer;
import de.regelsuche.egraph.EqualitySaturation;
import de.regelsuche.egraph.SaturationStats;
import de.regelsuche.egraph.proof.ExtractionCertificateCodec;
import de.regelsuche.egraph.proof.ExtractionProofChecker;
import de.regelsuche.egraph.proof.ProofLimits;
import de.regelsuche.json.JsonWriter;
import de.regelsuche.knowledge.RuleInventoryFingerprint;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.scoring.cost.StructuralCostModel;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.search.strategy.BestFirstSearchStrategy;
import de.regelsuche.search.strategy.EqualitySaturationStrategy;
import de.regelsuche.search.strategy.SearchProblem;
import de.regelsuche.search.strategy.SearchState;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.PatternRewriteRule;
import de.regelsuche.transform.RecordedExecution;
import de.regelsuche.transform.RecognitionProfile;
import de.regelsuche.transform.RewriteRule;
import de.regelsuche.transform.Transformation;
import de.regelsuche.util.AtomicJsonFile;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** Source-only comparison on a fixed inventory of unconditional ordered rewrites. */
final class SharedFragmentRewriteBenchmark {
    private static final String PATH = "best-first";
    private static final String EQSAT = "equality-saturation";
    private static final String EXACT = "EXACT_AST";
    private static final int NO_REFERENCE_COST = -1;
    private static final int MAX_EXPONENT = 4;
    private static final SearchHeuristic PATH_BUDGET = new SearchHeuristic(5, 128, 1, 0, 32, 8);
    private static final int ENGINE_MAX_AST_GROWTH = 0;
    private static final int ENGINE_MAX_CANDIDATES = 128;
    private static final EqualitySaturation.Config GRAPH_BUDGET = new EqualitySaturation.Config(8, 256);
    private static final List<String> RULE_IDS = List.of(
        "ast_add_zero_right", "ast_add_zero_left", "ast_multiply_one_right",
        "ast_multiply_one_left", "ast_multiply_zero_right", "ast_multiply_zero_left",
        "ast_subtract_zero", "ast_product_to_power_two");

    private static final List<BenchmarkCase> CASES = List.of(
        new BenchmarkCase("neutral-composition", "(x + 0) * (1 * (y + 0))", 1),
        new BenchmarkCase("annihilation", "(x + 0) * 0 + y", 0),
        new BenchmarkCase("ordered-subtraction", "(x - 0) - (y - 0)", 1),
        new BenchmarkCase("square-folding", "(x + 0) * (x + 0)", 1),
        new BenchmarkCase("right-nested", "x + (y + z)", 2),
        new BenchmarkCase("missing-cancellation", "(x - x) + y", 0),
        new BenchmarkCase("guarded-division", "x / x", List.of("x != 0"), NO_REFERENCE_COST),
        new BenchmarkCase("symbolic-power", "x ^ n", NO_REFERENCE_COST),
        new BenchmarkCase("unregistered-function", "f(x + 0)", NO_REFERENCE_COST));

    private final ExpressionParser parser = new ExpressionParser();
    private final ExpressionScorer scorer = new ExpressionScorer();
    private final ExpressionCanonicalizer canonicalizer = new ExpressionCanonicalizer();
    private final PolynomialNormalizer normalizer = new PolynomialNormalizer();
    private final List<RewriteRule> rules = sharedRules();
    private final ProofLimits proofLimits;

    SharedFragmentRewriteBenchmark(ProofLimits proofLimits) {
        this.proofLimits = proofLimits;
    }

    static void write(Path output) {
        String report = new SharedFragmentRewriteBenchmark(ProofLimits.defaults()).report();
        try {
            AtomicJsonFile.writeUtf8(output, report);
        } catch (IOException failure) {
            throw new UncheckedIOException("Could not write shared rewrite benchmark", failure);
        }
    }

    String report() {
        return new JsonWriter().beginObject()
            .property("schema", "regelsuche.shared-fragment-rewrite-benchmark/v1")
            .property("track", "EQUALITY_SATURATION_AND_TERM_REWRITING")
            .property("scorePolicy", ComparativeBenchmark.SCORE_POLICY)
            .property("targetVisible", false)
            .property("familyLabelsVisible", false)
            .property("sideConditionPolicy", "UNCONDITIONAL_RULES_ONLY")
            .property("fragment", "ordered-polynomial-unconditional/v1")
            .property("maximumLiteralExponent", MAX_EXPONENT)
            .property("objective", StructuralCostModel.OPERATOR_COUNT.id())
            .property("pathSelection", "MINIMUM_OPERATOR_COUNT_THEN_EXPRESSION_THEN_DEPTH")
            .property("graphSelection", "MINIMUM_OPERATOR_COUNT_NATIVE_EXTRACTION")
            .property("inventoryHash", RuleInventoryFingerprint.contentHash(rules))
            .stringArray("ruleIds", RULE_IDS)
            .object("pathBudget", SharedFragmentRewriteBenchmark::writePathBudget)
            .object("graphBudget", SharedFragmentRewriteBenchmark::writeGraphBudget)
            .object("proofLimits", this::writeProofLimits)
            .array("cases", array -> CASES.forEach(row -> array.objectValue(json -> writeCase(json, row))))
            .endObject().toString();
    }

    private void writeCase(JsonWriter json, BenchmarkCase row) {
        Expr input = parser.parseTerm(row.source());
        String translation = translation(input);
        if (EXACT.equals(translation) && !row.assumptions().isEmpty()) {
            translation = "ASSUMPTIONS_OUTSIDE_FRAGMENT";
        }
        json.property("id", row.id()).property("source", row.source())
            .stringArray("sourceAssumptions", row.assumptions());
        if (row.referenceCost() == NO_REFERENCE_COST) json.nullProperty("referenceOperatorCount");
        else json.property("referenceOperatorCount", row.referenceCost());
        if (EXACT.equals(translation)) {
            SearchProblem problem = new SearchProblem(row.source(), engine(rules),
                scorer, canonicalizer, PATH_BUDGET).withCostModel(StructuralCostModel.OPERATOR_COUNT);
            json.array("outcomes", array -> {
                array.objectValue(result -> writeBestFirst(result, input, problem, row.referenceCost()));
                array.objectValue(result -> writeSaturation(result, input, problem, row.referenceCost()));
            });
        } else {
            String reason = translation;
            json.array("outcomes", array -> {
                array.objectValue(result -> writeUnsupported(result, PATH, reason));
                array.objectValue(result -> writeUnsupported(result, EQSAT, reason));
            });
        }
    }

    private void writeBestFirst(JsonWriter json, Expr input, SearchProblem problem, int referenceCost) {
        var run = new BestFirstSearchStrategy().searchWithDiagnostics(problem);
        SearchState selected = run.states().stream()
            .min(Comparator.comparingInt((SearchState state) -> operatorCount(parser.parseTerm(state.expression())))
                .thenComparing(SearchState::expression).thenComparingInt(SearchState::depth)).orElseThrow();
        PathReplay replay = replay(problem.rootExpression(), selected);
        writeOutput(json, PATH, input, selected, referenceCost, true);
        var work = run.metrics();
        json.property("proofStatus", "PRIMITIVE_REPLAYED").property("proofObjectAvailable", false)
            .property("execution", replay.execution().toCanonicalJson())
            .array("occurrencePaths", paths -> replay.occurrencePaths().forEach(path ->
                paths.arrayValue(indexes -> path.forEach(index -> indexes.value(index)))))
            .object("work", metrics -> metrics.property("exploredStates", work.exploredStates())
                .property("expandedStates", work.expandedStates())
                .property("generatedTransformations", work.generatedTransformations())
                .property("depthPrunes", work.depthPrunes())
                .property("candidateBudgetPrunes", work.candidateBudgetPrunes())
                .property("stateBudgetReached", work.exploredStates() == PATH_BUDGET.maxVisitedExpressions())
                .property("selectedPrimitiveSteps", replay.execution().edgeCount())
                .property("detachedReplayEngineInvocations", replay.execution().edgeCount()));
    }

    private PathReplay replay(String input, SearchState selected) {
        var recorded = selected.recordedExecution().orElseThrow();
        List<RewriteRule> replayRules = sharedRules();
        var engine = engine(replayRules);
        var replayed = new ArrayList<Transformation>();
        var positions = new ArrayList<List<Integer>>();
        String source = input;
        for (var expected : selected.transformations()) {
            var occurrences = new ArrayList<AstRewriteTransformationEngine.GeneratedTransformation>();
            engine.transformWithOccurrences(source, occurrences::add);
            var actual = occurrences.stream().filter(move -> move.transformation().equals(expected))
                .findFirst().orElseThrow();
            requireExactStep(parser.parseTerm(source), actual, replayRules);
            replayed.add(actual.transformation());
            positions.add(actual.position().path());
            source = actual.transformation().transformedExpression();
        }
        recorded.requireEndpoints(input, selected.expression());
        recorded.requireReplay(input, replayed);
        return new PathReplay(recorded, List.copyOf(positions));
    }

    private void requireExactStep(Expr source, AstRewriteTransformationEngine.GeneratedTransformation move,
            List<RewriteRule> replayRules) {
        var step = move.transformation();
        RewriteRule rule = replayRules.stream().filter(candidate -> candidate.id().equals(step.rule()))
            .findFirst().orElseThrow();
        Expr subtree = move.position().subtreeAt(source).orElseThrow();
        if (!rule.matches(subtree) || !rule.assumptions(subtree).isEmpty()) {
            throw new IllegalStateException("retained rule did not match its ordered AST occurrence: " + step.rule());
        }
        Expr rewritten = move.position().replaceAt(source, rule.apply(subtree)).rewrittenRoot().orElseThrow();
        if (!rewritten.equals(parser.parseTerm(step.transformedExpression()))) {
            throw new IllegalStateException("retained step changed the ordered AST outside its rule: " + step.rule());
        }
    }

    private void writeSaturation(JsonWriter json, Expr input, SearchProblem problem, int referenceCost) {
        var strategy = new EqualitySaturationStrategy(GRAPH_BUDGET,
            new EqualitySaturation.ProofOptions(proofLimits, proofLimits));
        SearchState selected = strategy.search(problem).getLast();
        var proof = strategy.lastProofOutcome();
        writeOutput(json, EQSAT, input, selected, referenceCost, proof.complete());
        json.property("producerProofStatus", proof.status().name());
        json.object("work", work -> {
            writeGraphWork(work, strategy.lastStats());
            work.property("proofCaptureWork", proof.captureWork()).property("proofBuildWork", proof.buildWork())
                .property("producerReplayWork", proof.replayWork());
        });
        if (proof.complete()) {
            var certificate = proof.checked().orElseThrow().certificate();
            byte[] encoded = ExtractionCertificateCodec.encode(certificate, proofLimits);
            var reloaded = ExtractionCertificateCodec.decode(encoded, proofLimits);
            var checked = ExtractionProofChecker.check(parser.parseTerm(problem.rootExpression()),
                parser.parseTerm(selected.expression()), reloaded, sharedRules(), proofLimits);
            if (checked.status() != ExtractionProofChecker.Status.ACCEPTED) {
                throw new IllegalStateException("stored shared-fragment certificate did not replay: " + checked.detail());
            }
            json.property("proofStatus", "DETACHED_CERTIFICATE_CHECKED").property("proofObjectAvailable", true)
                .property("certificateHash", digest(encoded))
                .property("certificateBase64", Base64.getEncoder().encodeToString(encoded))
                .property("detachedReplayWork", checked.workUsed());
        } else {
            json.property("proofStatus", "UNAVAILABLE").property("proofObjectAvailable", false)
                .property("proofDetail", proof.detail());
        }
    }

    private void writeOutput(JsonWriter json, String system, Expr input, SearchState selected,
            int referenceCost, boolean evidenceAvailable) {
        Expr output = parser.parseTerm(selected.expression());
        int cost = operatorCount(output);
        var normalizedOutput = normalizer.normalize(output);
        boolean equal = normalizedOutput.isPresent() && normalizedOutput.equals(normalizer.normalize(input));
        String verdict = "OUTPUT_VALIDATION_FAILED";
        if (equal && selected.assumptions().isEmpty()) {
            verdict = costVerdict(cost, referenceCost, evidenceAvailable);
        }
        json.property("system", system).property("translation", EXACT)
            .property("output", selected.expression()).property("operatorCount", cost)
            .stringArray("emittedAssumptions", selected.assumptions())
            .property("exactPolynomialEquality", equal).property("verdict", verdict);
    }

    private static String costVerdict(int cost, int referenceCost, boolean evidenceAvailable) {
        if (!evidenceAvailable) return "PROOF_UNAVAILABLE";
        return cost <= referenceCost ? "REFERENCE_COST_REACHED" : "REFERENCE_COST_MISSED";
    }

    private static void writeUnsupported(JsonWriter json, String system, String reason) {
        json.property("system", system).property("translation", reason)
            .property("verdict", "UNSUPPORTED").property("proofStatus", "NOT_REQUESTED")
            .property("proofObjectAvailable", false);
    }

    private static String translation(Expr input) {
        return switch (input) {
            case VariableExpr ignored -> EXACT;
            case NumberExpr number -> number.value().isInteger() ? EXACT : "NUMBER_OUTSIDE_FRAGMENT";
            case FunctionExpr ignored -> "FUNCTION_OUTSIDE_FRAGMENT";
            case BinaryExpr binary -> switch (binary.operator()) {
                case DIV -> "DIVISION_OUTSIDE_FRAGMENT";
                case POW -> supportedPower(binary.right()) ? translation(binary.left()) : "POWER_OUTSIDE_FRAGMENT";
                default -> childTranslation(binary);
            };
        };
    }

    private static boolean supportedPower(Expr exponent) {
        return exponent instanceof NumberExpr number && number.value().isInteger()
            && number.value().signum() > 0
            && number.value().numerator().compareTo(BigInteger.valueOf(MAX_EXPONENT)) <= 0;
    }

    private static String childTranslation(BinaryExpr binary) {
        String left = translation(binary.left());
        return EXACT.equals(left) ? translation(binary.right()) : left;
    }

    private static int operatorCount(Expr expression) {
        return StructuralCostModel.OPERATOR_COUNT.cost("", expression, null);
    }

    private static List<RewriteRule> sharedRules() {
        List<RewriteRule> rules = AstRewriteTransformationEngine.allBuiltInRules().stream()
            .filter(rule -> RULE_IDS.contains(rule.id())).toList();
        if (!rules.stream().map(RewriteRule::id).toList().equals(RULE_IDS)
            || rules.stream().anyMatch(rule -> !admitted(rule))) {
            throw new IllegalStateException("shared exact rule inventory drifted");
        }
        return rules;
    }

    private static boolean admitted(RewriteRule rule) {
        return rule.getClass() == PatternRewriteRule.class
            && ((PatternRewriteRule) rule).recognitionProfile().equals(RecognitionProfile.exact())
            && !rule.mayEmitAssumptions() && rule.isEquivalencePreservingByConstruction();
    }

    private static AstRewriteTransformationEngine engine(List<RewriteRule> rules) {
        return new AstRewriteTransformationEngine(rules, ENGINE_MAX_AST_GROWTH, ENGINE_MAX_CANDIDATES) {
            @Override
            protected String formatState(Expr expression) {
                return formatOrdered(expression);
            }
        };
    }

    private static String formatOrdered(Expr expression) {
        if (expression instanceof BinaryExpr binary) {
            return "(" + formatOrdered(binary.left()) + " " + binary.operator().symbol()
                + " " + formatOrdered(binary.right()) + ")";
        }
        return ExpressionFormatter.format(expression);
    }

    private static void writePathBudget(JsonWriter json) {
        json.property("maxDepth", PATH_BUDGET.maxDepth())
            .property("maxVisitedExpressions", PATH_BUDGET.maxVisitedExpressions())
            .property("maxCandidatesPerState", PATH_BUDGET.maxCandidatesPerState())
            .property("maxExpandingSteps", PATH_BUDGET.maxExpandingSteps())
            .property("engineMaxAstGrowth", ENGINE_MAX_AST_GROWTH)
            .property("engineMaxCandidates", ENGINE_MAX_CANDIDATES);
    }

    private static void writeGraphBudget(JsonWriter json) {
        json.property("maxIterations", GRAPH_BUDGET.maxIterations())
            .property("maxNodes", GRAPH_BUDGET.maxNodes())
            .property("dirtyWorklist", GRAPH_BUDGET.useDirtyWorklist());
    }

    private void writeProofLimits(JsonWriter json) {
        json.property("maxTerms", proofLimits.maxTerms()).property("maxProofSteps", proofLimits.maxProofSteps())
            .property("maxReferences", proofLimits.maxReferences()).property("maxCharacters", proofLimits.maxCharacters())
            .property("maxRationalBits", proofLimits.maxRationalBits()).property("maxDepth", proofLimits.maxDepth())
            .property("maxExpandedNodes", proofLimits.maxExpandedNodes()).property("maxWork", proofLimits.maxWork());
    }

    private static void writeGraphWork(JsonWriter json, SaturationStats stats) {
        if (stats == null) return;
        json.property("iterations", stats.iterations()).property("eClasses", stats.eclasses())
            .property("eNodes", stats.enodes()).property("rulesFired", stats.rulesFired())
            .property("nodesScanned", stats.nodesScanned()).property("matchesFound", stats.matchesFound())
            .property("stopReason", stats.stopReason().name())
            .object("appliedRules", counts -> stats.appliedRules().entrySet().stream()
                .sorted(Map.Entry.comparingByKey()).forEach(entry -> counts.property(entry.getKey(), entry.getValue())));
    }

    private static String digest(byte[] data) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record BenchmarkCase(String id, String source, List<String> assumptions, int referenceCost) {
        BenchmarkCase(String id, String source, int referenceCost) {
            this(id, source, List.of(), referenceCost);
        }
    }

    private record PathReplay(RecordedExecution execution, List<List<Integer>> occurrencePaths) {}
}
