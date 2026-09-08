package de.regelsuche.evolution;

import de.regelsuche.evolution.TraceRewriteStrategyLearner.Application;
import de.regelsuche.evolution.TraceRewriteStrategyLearner.FrozenStrategy;
import de.regelsuche.evolution.TraceRewriteStrategyLearner.Input;
import de.regelsuche.evolution.TraceRewriteStrategyLearner.Limits;
import de.regelsuche.evolution.TraceRewriteStrategyLearner.Profile;
import de.regelsuche.json.JsonWriter;
import de.regelsuche.math.algorithms.equivalence.ExactPolynomialAnalysis;
import de.regelsuche.search.strategy.WorkBudgetBestFirstSearchStrategy.Budget;
import de.regelsuche.transform.RewriteKind;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Executable development comparison, deliberately separate from the sealed
 * flagship FINAL TEST. Profiles, inputs and budgets are fixed before training.
 * The learner receives only TRAIN; labels and comparisons are report metadata.
 */
public final class TraceStrategyTransferExample {
    public static final String REVISION = "regelsuche.trace-strategy-transfer-development/v1";
    private static final Budget BUDGET = Budget.primitive(6, 80, 32, 6, 30_000);
    private TraceStrategyTransferExample() {}

    public record Case(String id, String family, String expression) {
        public Case { id = SchematicProofPlan.requireId(id, "case id"); }
    }
    public record Row(Case example, Profile profile, Application application, String unsupportedReason) {}
    public record Report(String protocol, FrozenStrategy strategy, List<Row> rows) {
        public Report { rows = List.copyOf(rows); }
        public String toCanonicalJson() {
            return new JsonWriter().beginObject().property("schema", REVISION)
                .property("claim", "DEVELOPMENT_TRANSFER_CHARACTERIZATION;NO_FINAL_TEST_OR_SUPERIORITY_CLAIM")
                .property("protocolHash", SchematicProofPlan.hash(protocol))
                .property("strategyHash", strategy.contentHash())
                .array("rows", values -> rows.forEach(row -> values.objectValue(value -> {
                    value.property("id", row.example().id()).property("family", row.example().family())
                        .property("profile", row.profile().name()).property("input", row.example().expression());
                    if (row.application() == null) {
                        value.property("status", "DOMAIN_UNSUPPORTED").property("reason", row.unsupportedReason());
                    } else value.property("application", row.application().toCanonicalJson());
                }))).endObject().toString();
        }
        public String contentHash() { return SchematicProofPlan.hash(toCanonicalJson()); }
    }

    public static List<Input> trainingInputs() {
        return List.of(
            new Input("train-offset", "(a+b)*(a-b)+b*b+3"),
            new Input("train-scale", "5*((c+d)*(c-d)+d*d)"),
            new Input("train-common-factor", "p*p+p*q+0"),
            new Input("train-nested-factor", "(r*r+r*s)*(t+1)+0"));
    }

    public static List<Case> evaluationInputs() {
        return List.of(
            new Case("sum-composition", "sum of independently reducible terms", "((u+v)*(u-v)+v*v)+u*u"),
            new Case("product-composition", "outer polynomial product", "((j+k)*(j-k)+k*k)*(j+2)"),
            new Case("multiple-instances", "two independent cancellation sites", "((x+y)*(x-y)+y*y)+((z+1)*(z-1)+1)"),
            new Case("compound-base", "multivariate product inside a square", "((u*v+w)*(u*v-w)+w*w)+7"),
            new Case("nested-factors", "repeated factored subexpressions", "3*(m*m+m*n)+3*(m*m+m*n)"),
            new Case("near-miss", "noncancelling residual", "(x+y)*(x-y)+y*(y+1)"),
            new Case("already-simple", "unchanged control", "z+9"),
            new Case("unsupported", "non-polynomial control", "sin(x)+1"));
    }

    public static EvolutionGenome inventory() {
        String trainHash = SchematicProofPlan.hash(trainingJson());
        var scope = new EvolutionGenome.TrainingScope(EvolutionGenome.SourceSplit.TRAIN, trainHash,
            SchematicProofPlan.hash(REVISION + ":development-split"),
            SchematicProofPlan.hash(REVISION + ":no-final-test"),
            SchematicProofPlan.hash(REVISION + ":fixed-objective"));
        return EvolutionGenome.create(EvolutionGenome.Objective.OPEN_TARGET_OPERATOR, scope,
            List.of(
                gene("difference-product", "(?A+?B)*(?A-?B)", "?A^2-?B^2"),
                gene("cancel-addend", "(?A-?B)+?B", "?A"),
                gene("square-product", "?A*?A", "?A^2"),
                gene("double-term", "?A+?A", "2*?A"),
                gene("factor-left", "?A*?B+?A*?C", "?A*(?B+?C)"),
                gene("factor-right", "?A*?C+?B*?C", "(?A+?B)*?C"),
                gene("add-zero", "?A+0", "?A"),
                gene("multiply-one", "?A*1", "?A")),
            List.of(new EvolutionGenome.FeatureWeight(EvolutionGenome.FitnessSignal.CANDIDATE_COMPLEXITY, -1000)),
            EvolutionGenome.GuardPolicy.strictDefault(), new EvolutionGenome.ResourceBudget(64, 256, 32, 8, 32),
            List.of("core.ast-rewrite"), List.of());
    }

    private static EvolutionGenome.RewriteGene gene(String id, String source, String target) {
        return new EvolutionGenome.RewriteGene(id, source, target, RewriteKind.SIMPLIFY, false, -2, 8, 32,
            List.of(), List.of(EvolutionGenome.EvidenceObligation.SEMANTIC_VALIDATION,
                EvolutionGenome.EvidenceObligation.COUNTEREXAMPLE_SEARCH,
                EvolutionGenome.EvidenceObligation.PROOF_OR_CERTIFICATE,
                EvolutionGenome.EvidenceObligation.NOVELTY_REVIEW,
                EvolutionGenome.EvidenceObligation.HOLDOUT_EVALUATION));
    }

    public static Limits limits() { return new Limits(BUDGET, 8, 6, 64); }

    public static String protocol() {
        return new JsonWriter().beginObject().property("schema", REVISION)
            .property("information", "NO_TARGET_IN_TRAIN_OR_APPLICATION;EVALUATION_LABELS_REPORT_ONLY")
            .property("split", "PUBLIC_DEVELOPMENT_CASES;NOT_SEALED_VALIDATION_OR_FINAL_TEST")
            .property("inventory", inventory().toCanonicalJson()).property("train", trainingJson())
            .object("learner", value -> TraceRewriteStrategyLearner.writeLearningProtocol(value, limits()))
            .property("objective", "EXPRESSION_SCORE_PLUS_2_PER_PRIMITIVE_PLUS_5_PER_EXPANDING_STEP")
            .property("policy", "RETAIN_EVERY_PROFILE_AND_CASE_INCLUDING_LOSSES")
            .object("commonBudget", value -> TraceRewriteStrategyLearner.writeBudget(value, BUDGET))
            .stringArray("profiles", java.util.Arrays.stream(Profile.values()).map(Enum::name).toList())
            .array("evaluation", values -> evaluationInputs().forEach(input -> values.objectValue(value ->
                value.property("id", input.id()).property("family", input.family()).property("source", input.expression()))))
            .endObject().toString();
    }

    private static String trainingJson() {
        return new JsonWriter().beginObject().array("inputs", values -> trainingInputs().forEach(input ->
            values.objectValue(value -> value.property("id", input.id()).property("source", input.expression()))))
            .endObject().toString();
    }

    public static Report run() {
        String frozenProtocol = protocol();
        var learner = new TraceRewriteStrategyLearner();
        var strategy = learner.learn(inventory(), trainingInputs(), limits());
        List<Row> rows = new ArrayList<>();
        var analysis = new ExactPolynomialAnalysis();
        for (Case input : evaluationInputs()) {
            String unsupported = "";
            // Classify only input-domain rejection. Search, learning, replay and
            // overlap errors propagate; they cannot become favourable null rows.
            try { analysis.alphaIdentity(input.expression()); }
            catch (IllegalArgumentException exception) { unsupported = exception.getMessage(); }
            for (Profile profile : Profile.values()) {
                rows.add(new Row(input, profile, unsupported.isEmpty()
                    ? learner.apply(strategy, input.expression(), BUDGET, profile) : null, unsupported));
            }
        }
        return new Report(frozenProtocol, strategy, rows);
    }

    /** Writes a content-addressed run; existing differing files are never replaced. */
    public static Path write(Report report, Path output) throws IOException {
        Map<String, String> artifacts = new TreeMap<>();
        artifacts.put("protocol.json", report.protocol());
        artifacts.put("strategy.json", report.strategy().toCanonicalJson());
        artifacts.put("report.json", report.toCanonicalJson());
        artifacts.put("report.md", markdown(report));
        artifacts.put("index.html", html(report));
        for (var observation : report.strategy().observations()) {
            artifacts.put(observation.input().id() + ".search.json", observation.search().toCanonicalJson());
        }
        for (var row : report.rows()) {
            if (row.application() != null) artifacts.put(row.example().id() + "-" + row.profile().name().toLowerCase(java.util.Locale.ROOT)
                + ".search.json", row.application().search().toCanonicalJson());
        }
        return writeArtifacts(report.contentHash(), artifacts, output, REVISION);
    }

    static Path writeArtifacts(String reportHash, Map<String, String> artifacts, Path output, String revision) throws IOException {
        String manifest = new JsonWriter().beginObject().property("schema", revision).property("reportHash", reportHash)
            .array("artifacts", values -> artifacts.forEach((name, content) -> values.objectValue(value ->
                value.property("name", name).property("sha256", SchematicProofPlan.hash(content)))))
            .endObject().toString();
        // Bind the presentation and artifact set too: a renderer change must
        // create a new bundle even when all mathematical observations match.
        Path directory = output.resolve(SchematicProofPlan.hash(manifest).substring("sha256:".length()));
        Files.createDirectories(directory);
        for (var artifact : artifacts.entrySet()) writeIdentical(directory.resolve(artifact.getKey()), artifact.getValue());
        writeIdentical(directory.resolve("manifest.json"), manifest);
        return directory;
    }

    private static void writeIdentical(Path path, String content) throws IOException {
        if (Files.exists(path)) {
            if (Files.isSymbolicLink(path) || !Files.readString(path, StandardCharsets.UTF_8).equals(content)) {
                throw new IOException("existing artifact differs: " + path.getFileName());
            }
        } else Files.writeString(path, content, StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE_NEW);
    }

    public static String markdown(Report report) {
        var out = new StringBuilder("# Learned rewrite strategies: development comparison\n\n")
            .append("The learner infers rule order and branching from target-free TRAIN searches. All profiles receive the same eight rules and search budgets.\n\n")
            .append("These public development cases share algebraic building blocks with TRAIN. They characterize transfer to new compositions and contexts, not independently held-out mathematical families or the flagship FINAL TEST.\n\n")
            .append("## Learned program\n\n```text\n")
            .append(report.strategy().plan().map(EvolutionRewriteProgramPlan::toReadableProgram).orElse("No multistep strategy learned."))
            .append("\n```\n\nTraining search work: ").append(report.strategy().trainingSearchWorkUnits())
            .append("; primitive replay work: ").append(report.strategy().trainingReplayWorkUnits())
            .append("; exact step checks: ").append(report.strategy().trainingExactAuditCalls()).append(".\n\n")
            .append("These dimensions exclude a complete account of identity projection, compiler, parser, BigInteger and model-construction work. They do not establish amortized total-work or runtime superiority.\n\n")
            .append("| Case | Profile | Selected output | Score ↓ | Search work | Primitive steps | Learned/shuffled program used | Status |\n")
            .append("|---|---|---|---:|---:|---:|---|---|\n");
        for (var row : report.rows()) {
            out.append("| ").append(row.example().id()).append(" | ").append(row.profile()).append(" | ");
            if (row.application() == null) out.append("— | — | — | — | — | DOMAIN_UNSUPPORTED |\n");
            else {
                var result = row.application().search();
                out.append('`').append(result.bestState().expression()).append("` | ")
                    .append(result.bestState().score().weightedTotal()).append(" | ")
                    .append(result.metrics().chargedSearchWorkUnits()).append(" | ")
                    .append(result.bestState().primitiveDepth()).append(" | ").append(result.bestState().programUsed())
                    .append(" | ").append(result.status()).append(" |\n");
            }
        }
        return out.append("\nLower score means the frozen project scorer prefers that output; it does not mean a universally simplest expression. Full searches, failed alternatives, primitive lineage and work are retained in the adjacent JSON files. No automatic promotion or external novelty claim is made.\n").toString();
    }

    private static String html(Report report) {
        var out = new StringBuilder("<!doctype html><html lang=\"de\"><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
            .append("<title>Regelsuche · Gelernte Strategien</title><style>body{font:17px/1.55 system-ui;margin:40px auto;padding:0 20px;max-width:1100px;color:#172b36;background:#f7f9fa}h1{line-height:1.2}code,pre{font-size:14px;overflow-wrap:anywhere;white-space:pre-wrap}details{background:white;border:1px solid #ccd9df;padding:16px;margin:14px 0;border-radius:8px}summary{cursor:pointer;font-weight:600}li{margin:8px 0}.note{color:#455e6b}</style>")
            .append("<h1>Aus Suchwegen eine Strategie lernen</h1><p>Regelsuche lernt die Reihenfolge und Verzweigung von Regeln aus vier Trainingsaufgaben. Neue Aufgaben werden mit denselben Regeln und Budgets verglichen.</p>")
            .append("<p class=\"note\">Entwicklungsvergleich: neue Zusammensetzungen bekannter algebraischer Bausteine. Noch kein unabhängiger Nachweis für andere Mathematikbereiche oder einen allgemeinen Geschwindigkeitsvorteil.</p>")
            .append("<details open><summary>Das gelernte Programm</summary><pre>")
            .append(escape(report.strategy().plan().map(EvolutionRewriteProgramPlan::toReadableProgram).orElse("Keine mehrstufige Strategie gelernt.")))
            .append("</pre><p>Sucharbeit im Training: ").append(report.strategy().trainingSearchWorkUnits())
            .append(". Prüf- und weitere Lernkosten stehen getrennt im <a href=\"report.md\">vollständigen Bericht</a>.</p></details>");
        for (var row : report.rows()) {
            out.append("<details><summary>").append(escape(row.example().id())).append(" · ").append(row.profile()).append("</summary><p>Ausgang: <code>")
                .append(escape(row.example().expression())).append("</code></p>");
            if (row.application() == null) out.append("<p>Dieser Ausdruck liegt außerhalb des unterstützten Polynomfragments.</p>");
            else {
                var state = row.application().search().bestState();
                out.append("<p>Ergebnis: <code>").append(escape(state.expression())).append("</code></p><p>Sucharbeit: ")
                    .append(row.application().search().metrics().chargedSearchWorkUnits()).append(" · Primitive Schritte: ")
                    .append(state.primitiveDepth()).append(" · Programm verwendet: ").append(state.programUsed() ? "ja" : "nein")
                    .append("</p><ol>");
                out.append("<li><code>").append(escape(state.path().getFirst())).append("</code></li>");
                appendPrimitiveSteps(out, state.transformations());
                out.append("</ol>");
            }
            out.append("</details>");
        }
        return out.append("<p><a href=\"report.json\">Vergleichsdaten</a> · <a href=\"strategy.json\">Strategie und Herkunft</a> · <a href=\"manifest.json\">Nachweise</a></p></html>").toString();
    }

    static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    static void appendPrimitiveSteps(StringBuilder out, List<de.regelsuche.transform.Transformation> steps) {
        for (var step : steps) {
            if (step.provenance() instanceof de.regelsuche.transform.TransformationProvenance.Sequence sequence) {
                appendPrimitiveSteps(out, sequence.steps());
            } else {
                out.append("<li><code>").append(escape(step.transformedExpression()))
                    .append("</code><br><small>Regel: ").append(escape(step.rule()))
                    .append(" · Anwendung: ").append(escape(step.applicationKey())).append("</small></li>");
            }
        }
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 1) throw new IllegalArgumentException("usage: TraceStrategyTransferExample OUTPUT_DIRECTORY");
        Path directory = write(run(), Path.of(args[0]));
        System.out.println(directory.resolve("index.html").toAbsolutePath());
    }
}
