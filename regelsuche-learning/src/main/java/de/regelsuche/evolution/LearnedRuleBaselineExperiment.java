package de.regelsuche.evolution;

import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.json.JsonWriter;
import de.regelsuche.math.algorithms.equivalence.ExactPolynomialAnalysis;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.strategy.SearchExpansionSource;
import de.regelsuche.search.strategy.SearchRunDiagnostics;
import de.regelsuche.search.strategy.WorkBudgetBestFirstSearchStrategy;
import de.regelsuche.search.strategy.WorkBudgetBestFirstSearchStrategy.Budget;
import de.regelsuche.search.strategy.WorkBudgetBestFirstSearchStrategy.Problem;
import de.regelsuche.search.strategy.WorkBudgetBestFirstSearchStrategy.Result;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.MeasuredTransformationEngine;
import de.regelsuche.transform.MeasuredTransformationEngines;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** Frozen diagnostic control before introducing a learned scheduling policy (#953, #696, #235). */
public final class LearnedRuleBaselineExperiment {
    public static final String REVISION = "regelsuche.learned-rule-baseline/v1";
    public static final List<Long> WORK_BUDGETS = List.of(128L, 512L, 2048L, 30_000L);
    private static final List<String> FAMILIES = TraceStrategyTransferExample.inventory().rewrites().stream()
        .map(EvolutionGenome.RewriteGene::geneId).toList();
    private LearnedRuleBaselineExperiment() {}

    public record Case(String id, String source, String target) {}
    public record Row(Case example, LearnedSearchProfile profile, long budget, Result search,
                      SearchRunDiagnostics diagnostics, String unsupportedReason, long wallNanos) {
        public String id() { return example.id() + "-" + profile.name().toLowerCase(Locale.ROOT) + "-" + budget; }
    }

    public static List<Case> cases() {
        return List.of(
            new Case("sum-composition", "((u+v)*(u-v)+v*v)+u*u", "2*u^2"),
            new Case("product-composition", "((j+k)*(j-k)+k*k)*(j+2)", "j^2*(j+2)"),
            new Case("compound-base", "((u*v+w)*(u*v-w)+w*w)+7", "(u*v)^2+7"),
            new Case("factor-context", "3*(m*m+m*n)", "3*(m*(m+n))"),
            new Case("near-miss", "(x+y)*(x-y)+y*(y+1)", "x^2"),
            new Case("already-target", "z+9", "z+9"),
            new Case("unsupported", "sin(x)+1", "sin(x)"));
    }

    /** All targets and budgets are fixed independently of the TRAIN result. */
    public static String protocol() {
        return new JsonWriter().beginObject().property("schema", REVISION)
            .property("claim", "PUBLIC_DEVELOPMENT_DIAGNOSIS;NOT_SEALED_FINAL_TEST;NO_POLICY_PROMOTION")
            .property("rankedPolicy", "IDENTICAL_TO_LEARNED_NAIVE")
            .property("expert", "HANDWRITTEN_PRIMITIVE_SEQUENCE_REFERENCE;NOT_AN_UNLEARNED_BASELINE")
            .property("inventoryHash", TraceStrategyTransferExample.inventory().contentHash())
            .property("trainingProtocolHash", SchematicProofPlan.hash(TraceStrategyTransferExample.protocol()))
            .property("workScope", SearchRunDiagnostics.WORK_SCOPE)
            .property("verification", "EXACT_POLYNOMIAL_CHECK_PER_RETAINED_PRIMITIVE_EDGE;SAME_FOR_ALL_PROFILES")
            .property("primitiveBudget", 6).property("stateBudget", 80).property("candidateBudget", 32)
            .stringArray("profiles", java.util.Arrays.stream(LearnedSearchProfile.values()).map(Enum::name).toList())
            .stringArray("workBudgets", WORK_BUDGETS.stream().map(Object::toString).toList())
            .array("cases", values -> cases().forEach(c -> values.objectValue(value ->
                value.property("id", c.id()).property("source", c.source()).property("target", c.target()))))
            .endObject().toString();
    }

    public record Report(String protocol, TraceRewriteStrategyLearner.FrozenStrategy knowledge, List<Row> rows) {
        public Report { rows = List.copyOf(rows); }
        public String toCanonicalJson() {
            return new JsonWriter().beginObject().property("schema", REVISION)
                .property("protocolHash", SchematicProofPlan.hash(protocol)).property("knowledgeHash", knowledge.contentHash())
                .property("trainingSearchWork", knowledge.trainingSearchWorkUnits())
                .property("trainingReplayWork", knowledge.trainingReplayWorkUnits())
                .property("trainingMinimalityWork", knowledge.trainingMinimalityWorkUnits())
                .property("trainingVerificationCalls", knowledge.trainingExactAuditCalls())
                .property("trainingCostScope", "RETAINED_SEARCH_REPLAY_MINIMALITY_AND_AUDIT_EVENTS;EXCLUDES_COMPILATION_AND_IDENTITY_PROJECTION")
                .array("rows", values -> rows.forEach(row -> values.objectValue(value -> {
                    value.property("id", row.id()).property("case", row.example().id()).property("profile", row.profile().name())
                        .property("workBudget", row.budget()).property("unsupportedReason", row.unsupportedReason());
                    if (row.search() == null) value.property("status", "DOMAIN_UNSUPPORTED").nullProperty("diagnostics");
                    else value.property("status", row.search().status().name()).property("reached", row.search().reached())
                        .property("searchHash", SchematicProofPlan.hash(row.search().toCanonicalJson()))
                        .property("withinTotalBudget", row.diagnostics().totalWork() <= row.budget())
                        .property("diagnostics", row.diagnostics().toCanonicalJson());
                }))).endObject().toString();
        }
    }

    public static Report run() {
        String frozenProtocol = protocol();
        var knowledge = new TraceRewriteStrategyLearner().learn(TraceStrategyTransferExample.inventory(),
            TraceStrategyTransferExample.trainingInputs(), TraceStrategyTransferExample.limits());
        var rows = new ArrayList<Row>();
        for (Case example : cases()) for (long budget : WORK_BUDGETS) for (var profile : LearnedSearchProfile.values()) {
            rows.add(runCase(knowledge, example, profile, budget));
        }
        return new Report(frozenProtocol, knowledge, rows);
    }

    public static Row runCase(TraceRewriteStrategyLearner.FrozenStrategy knowledge, Case example,
            LearnedSearchProfile profile, long workBudget) {
        var exact = new ExactPolynomialAnalysis();
        String identity;
        try { identity = exact.alphaIdentity(example.source()); }
        catch (IllegalArgumentException unsupported) {
            return new Row(example, profile, workBudget, null, null, unsupported.getMessage(), 0);
        }
        if (knowledge.excludedIdentities().contains(identity)) throw new IllegalArgumentException("TRAIN overlap: " + example.id());
        var engine = engine(knowledge, profile);
        var budget = Budget.primitive(6, 80, 32, 6, workBudget);
        long start = System.nanoTime();
        var search = new WorkBudgetBestFirstSearchStrategy().search(new Problem(format(example.source()), format(example.target()),
            new SearchExpansionSource.Measured(engine), new ExpressionScorer(), new ExpressionCanonicalizer(), budget));
        var retained = search.reached() ? search.reachedState() : search.bestState();
        int audits = new TraceRewriteStrategyLearner().audit(retained.path().getFirst(), retained.transformations());
        long nanos = System.nanoTime() - start;
        var learnedIds = search.expansions().stream().flatMap(e -> e.execution().transformations().stream())
            .map(de.regelsuche.transform.Transformation::rule)
            .filter(id -> profile.usesFrozenLearning() && id.startsWith("program:")).collect(Collectors.toSet());
        var diagnostics = SearchRunDiagnostics.observe(search, id -> family(id, profile), learnedIds, audits);
        if (search.reached() && diagnostics.totalWork() > workBudget) throw new IllegalStateException("success exceeds total audit-inclusive budget");
        return new Row(example, profile, workBudget, search, diagnostics, "", nanos);
    }

    public static MeasuredTransformationEngine engine(TraceRewriteStrategyLearner.FrozenStrategy knowledge,
            LearnedSearchProfile profile) {
        var inventory = knowledge.inventory();
        var base = MeasuredTransformationEngines.counting(new AstRewriteTransformationEngine(
            new EvolutionGenomeCompiler().compile(inventory).rules(), inventory.budget().maxAstGrowthPerStep(),
            inventory.budget().maxCandidatesPerState()));
        if (profile == LearnedSearchProfile.BASE) return base;
        var plan = profile == LearnedSearchProfile.EXPERT ? java.util.Optional.of(expertPlan(inventory)) : knowledge.plan();
        return plan.isEmpty() ? base : MeasuredTransformationEngines.union(base,
            new EvolutionRewriteProgramCompiler().compile(inventory, plan.orElseThrow()).engine());
    }

    public static EvolutionRewriteProgramPlan expertPlan(EvolutionGenome inventory) {
        var cancel = new EvolutionRewriteProgramPlan.Sequence("expert-cancellation", List.of(
            new EvolutionRewriteProgramPlan.Source("expert-difference-product", List.of("difference-product")),
            new EvolutionRewriteProgramPlan.Source("expert-square-product", List.of("square-product")),
            new EvolutionRewriteProgramPlan.Source("expert-cancel-addend", List.of("cancel-addend"))));
        return EvolutionRewriteProgramPlan.create(inventory, new EvolutionRewriteProgramPlan.Choice("expert-book", List.of(
            cancel, new EvolutionRewriteProgramPlan.Source("expert-factors", List.of("factor-left", "factor-right")))), 64, 64);
    }

    private static String family(String id, LearnedSearchProfile profile) {
        if (id.startsWith("program:")) return profile == LearnedSearchProfile.EXPERT ? "HANDWRITTEN_PROGRAM" : "FROZEN_LEARNED_PROGRAM";
        for (String family : FAMILIES) if (id.endsWith("_" + family)) return family;
        return id;
    }

    private static String format(String expression) { return ExpressionFormatter.format(new ExpressionParser().parseTerm(expression)); }

    public static Path write(Report report, Path output) throws IOException {
        Map<String, String> artifacts = new TreeMap<>();
        artifacts.put("protocol.json", report.protocol());
        artifacts.put("knowledge.json", report.knowledge().toCanonicalJson());
        artifacts.put("report.json", report.toCanonicalJson());
        for (var row : report.rows()) if (row.search() != null) artifacts.put(row.id() + ".search.json", row.search().toCanonicalJson());
        Path directory = TraceStrategyTransferExample.writeArtifacts(SchematicProofPlan.hash(report.toCanonicalJson()), artifacts, output, REVISION);
        // Volatile diagnostics deliberately do not enter the reproducible manifest or scientific work totals.
        var wall = new StringBuilder("run,wallNanos\n");
        report.rows().forEach(row -> wall.append(row.id()).append(',').append(row.wallNanos()).append('\n'));
        Files.writeString(output.resolve("walltime-diagnostic.csv"), wall);
        return directory;
    }

    public static void main(String[] args) throws IOException {
        Path output = args.length == 0 ? Path.of("build/reports/learned-rule-baseline") : Path.of(args[0]);
        System.out.println(write(run(), output));
    }
}
