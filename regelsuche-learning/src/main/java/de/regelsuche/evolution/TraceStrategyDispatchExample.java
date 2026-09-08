package de.regelsuche.evolution;

import de.regelsuche.evolution.TraceRewriteStrategyLearner.Input;
import de.regelsuche.evolution.TraceStrategyDispatchLearner.FrozenPolicy;
import de.regelsuche.evolution.TraceStrategyDispatchLearner.Observation;
import de.regelsuche.evolution.TraceStrategyDispatchLearner.Profile;
import de.regelsuche.json.JsonWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** New public development batch, fixed independently of the optimizer's measured outcomes. */
public final class TraceStrategyDispatchExample {
    public static final String REVISION = "regelsuche.trace-strategy-dispatch-comparison/v1";
    private TraceStrategyDispatchExample() {}
    public record Case(Input input, String family) {}
    public record Row(Case example, Profile profile, Observation observation) {}

    public static List<Input> selectionInputs() {
        return List.of(new Input("select-offset", "(a+b)*(a-b)+b*b+23"),
            new Input("select-offset-two", "(c+d)*(c-d)+d*d+31"),
            new Input("select-scale", "7*((a+b)*(a-b)+b*b)"),
            new Input("select-product", "((a+b)*(a-b)+b*b)*(a+5)"),
            new Input("select-compound", "((a*b+c)*(a*b-c)+c*c)+13"),
            new Input("select-residual", "(a+b)*(a-b)+b*(b+1)+19"),
            new Input("select-factor", "a*a+a*b+17"),
            new Input("select-unchanged", "a+29"));
    }

    public static TraceStrategyDispatchLearner.Limits limits() {
        return new TraceStrategyDispatchLearner.Limits(TraceStrategyTransferExample.limits().trainingBudget(), 12, 16, 32);
    }

    public static List<Case> evaluationInputs() {
        List<Case> result = new ArrayList<>();
        for (int i = 0; i < 32; i++) {
            int n = 1000 + i;
            add(result, "offset", i, "((x+y)*(x-y)+y*y)+" + n);
            add(result, "scale", i, (n + 2) + "*((x+y)*(x-y)+y*y)");
            add(result, "product", i, "((x+y)*(x-y)+y*y)*(x+" + n + ")");
            add(result, "compound", i, "((u*v+w)*(u*v-w)+w*w)+" + n);
            add(result, "multiple-sites", i, "((x+y)*(x-y)+y*y)+((z+1)*(z-1)+1)+" + n);
            add(result, "noncancelling", i, "(x+y)*(x-y)+y*(y+1)+" + n);
            add(result, "factor", i, "(x*x+x*y)+" + n);
            add(result, "unchanged", i, "x+" + n);
            add(result, "same-hint-mismatch", i, "(x+y)*(x-y)+z*z+" + (5000 + i));
        }
        return List.copyOf(result);
    }

    private static void add(List<Case> cases, String family, int index, String expression) {
        cases.add(new Case(new Input(family + "-" + index, expression), family));
    }

    public static String protocol() {
        return new JsonWriter().beginObject().property("schema", REVISION)
            .property("formationProtocol", TraceStrategyTransferExample.protocol())
            .property("optimizer", TraceStrategyDispatchLearner.REVISION)
            .property("continuationBackend", de.regelsuche.search.program.CompiledLinearRewriteEngine.REVISION)
            .property("backend", "PREPARED").property("goal", "TARGET_FREE")
            .property("split", "PUBLIC_DEVELOPMENT;SEPARATE_FORMATION_AND_SELECTION_TRAIN;NEW_FIXED_APPLICATION_BATCH")
            .property("successRule", "NO_SCORE_REGRESSIONS_VS_FLAT_GREEDY;LOWER_TOTAL_MEASURED_WORK;REPORT_AMORTIZATION_SEPARATELY")
            .property("reportPolicy", "ALL_288_CASES_ALL_FOUR_PROFILES_NO_RESULT_SELECTION")
            .object("limits", value -> TraceStrategyDispatchLearner.writeLimits(value, limits()))
            .stringArray("profiles", java.util.Arrays.stream(Profile.values()).map(Enum::name).toList())
            .array("selectionTrain", values -> selectionInputs().forEach(input -> values.objectValue(value ->
                value.property("id", input.id()).property("input", input.expression()))))
            .array("application", values -> evaluationInputs().forEach(input -> values.objectValue(value ->
                value.property("id", input.input().id()).property("input", input.input().expression()).property("family", input.family()))))
            .endObject().toString();
    }

    public record Report(String protocol, FrozenPolicy policy, List<Row> rows) {
        public Report { rows = List.copyOf(rows); }
        public long work(Profile profile) { return rows.stream().filter(row -> row.profile() == profile)
            .mapToLong(row -> row.observation().measuredWork()).reduce(0, Math::addExact); }
        public long regressions(Profile profile) {
            Map<String, Integer> baseline = new TreeMap<>();
            rows.stream().filter(row -> row.profile() == Profile.FLAT_GREEDY).forEach(row ->
                baseline.put(row.example().input().id(), row.observation().search().bestState().score().weightedTotal()));
            return rows.stream().filter(row -> row.profile() == profile).filter(row ->
                row.observation().search().bestState().score().weightedTotal() > baseline.get(row.example().input().id())).count();
        }
        public boolean passes() { return regressions(Profile.LEARNED_DISPATCH) == 0 && work(Profile.LEARNED_DISPATCH) < work(Profile.FLAT_GREEDY); }
        public String toCanonicalJson() {
            return new JsonWriter().beginObject().property("schema", REVISION)
                .property("protocolHash", SchematicProofPlan.hash(protocol)).property("policyHash", policy.contentHash())
                .property("passesDevelopmentCriterion", passes()).property("learningWork", policy.learningWork())
                .property("amortizedOnThisBatch", passes() && policy.learningWork() + work(Profile.LEARNED_DISPATCH) < work(Profile.FLAT_GREEDY))
                .array("profiles", values -> { for (var profile : Profile.values()) values.objectValue(value ->
                    value.property("profile", profile.name()).property("work", work(profile)).property("scoreRegressions", regressions(profile))); })
                .array("rows", values -> rows.forEach(row -> values.objectValue(value -> value
                    .property("family", row.example().family()).property("profile", row.profile().name())
                    .property("observation", row.observation().toCanonicalJson()))))
                .endObject().toString();
        }
        public String contentHash() { return SchematicProofPlan.hash(toCanonicalJson()); }
        public String summaryJson() {
            return new JsonWriter().beginObject().property("schema", REVISION).property("reportHash", contentHash())
                .property("protocolHash", SchematicProofPlan.hash(protocol)).property("policyHash", policy.contentHash())
                .property("cases", rows.size() / Profile.values().length).property("rows", rows.size())
                .property("passesDevelopmentCriterion", passes()).property("learningWork", policy.learningWork())
                .array("families", values -> rows.stream().map(row -> row.example().family()).distinct().sorted().forEach(family ->
                    values.objectValue(value -> {
                        value.property("family", family);
                        for (var profile : Profile.values()) value.property(profile.name(), rows.stream().filter(row ->
                            row.example().family().equals(family) && row.profile() == profile)
                            .mapToLong(row -> row.observation().measuredWork()).sum());
                    })))
                .array("profiles", values -> { for (var profile : Profile.values()) values.objectValue(value -> value
                    .property("profile", profile.name()).property("work", work(profile)).property("scoreRegressions", regressions(profile))); })
                .endObject().toString();
        }
    }

    public static FrozenPolicy train() {
        var formation = new TraceRewriteStrategyLearner().learn(TraceStrategyTransferExample.inventory(),
            TraceStrategyTransferExample.trainingInputs(), TraceStrategyTransferExample.limits());
        return new TraceStrategyDispatchLearner().train(formation, selectionInputs(), limits());
    }

    public static Report run() {
        String frozenProtocol = protocol();
        var policy = train();
        var learner = new TraceStrategyDispatchLearner();
        var prepared = new java.util.EnumMap<Profile, TraceStrategyDispatchLearner.PreparedPolicy>(Profile.class);
        for (var profile : Profile.values()) prepared.put(profile, learner.prepare(policy, profile));
        List<Row> rows = new ArrayList<>();
        for (var example : evaluationInputs()) for (var profile : Profile.values()) {
            rows.add(new Row(example, profile, prepared.get(profile).apply(example.input())));
        }
        return new Report(frozenProtocol, policy, rows);
    }

    public static String markdown(Report report) {
        var out = new StringBuilder("# Conditional continuation: fixed development comparison\n\n")
            .append("All 288 new polynomial inputs and four profiles are retained. These are public development compositions of known building blocks, not a sealed FINAL TEST or independent mathematical families.\n\n")
            .append("The learned dispatcher reuses an actual primitive candidate and executes only a selected continuation. All one-successor profiles use the same ranking and budgets. FLAT_EXHAUSTIVE is retained as an additional stronger search control.\n\n")
            .append("| Profile | Search mechanics + exact audit calls | Score regressions vs FLAT_GREEDY |\n|---|---:|---:|\n");
        for (var profile : Profile.values()) out.append("| ").append(profile).append(" | ").append(report.work(profile))
            .append(" | ").append(report.regressions(profile)).append(" |\n");
        out.append("\nEach family has 32 inputs. Work includes the cases where a learned hint fails.\n\n| Family | Exhaustive | Greedy | Learned dispatch | Ungated |\n|---|---:|---:|---:|---:|\n");
        for (var family : report.rows().stream().map(row -> row.example().family()).distinct().sorted().toList()) {
            out.append("| ").append(family).append(" |");
            for (var profile : Profile.values()) out.append(' ').append(report.rows().stream().filter(row -> row.profile() == profile
                && row.example().family().equals(family)).mapToLong(row -> row.observation().measuredWork()).sum()).append(" |");
            out.append('\n');
        }
        out.append("\nFormation work: ").append(report.policy().formationWork())
            .append("; dispatch training including rejected trials and context collection: ").append(report.policy().dispatchLearningWork())
            .append("; total counted learning work: ").append(report.policy().learningWork()).append(".\n\n")
            .append("Development criterion passed: ").append(report.passes()).append(". Learning plus application cheaper on this batch: ")
            .append(report.passes() && report.policy().learningWork() + report.work(Profile.LEARNED_DISPATCH) < report.work(Profile.FLAT_GREEDY))
            .append(".\n\nThese units are not CPU time. Complete parsing, identity projection, compilation and BigInteger work remain outside this ledger. All output paths are exact-audited. A heuristic route can lose quality on unseen inputs; this batch cannot prove general optimality.\n\n")
            .append("## Frozen routes\n\n");
        for (var route : report.policy().routes()) out.append("- Available-gene mask ").append(route.contextMask()).append(": ")
            .append(String.join(" → ", route.sequence())).append('\n');
        return out.toString();
    }

    public static Path write(Report report, Path output) throws IOException {
        Map<String, String> artifacts = new TreeMap<>();
        artifacts.put("protocol.json", report.protocol());
        artifacts.put("policy.json", report.policy().toCanonicalJson());
        artifacts.put("formation.json", report.policy().formation().toCanonicalJson());
        artifacts.put("report.json", report.toCanonicalJson());
        artifacts.put("summary.json", report.summaryJson());
        artifacts.put("report.md", markdown(report));
        artifacts.put("index.html", html(report));
        for (var trial : report.policy().trials()) for (var observation : trial.observations()) artifacts.put(
            "train-" + trial.id() + "-" + observation.input().id() + ".search.json", observation.search().toCanonicalJson());
        for (var observation : report.policy().formation().observations()) artifacts.put(
            "formation-" + observation.input().id() + ".search.json", observation.search().toCanonicalJson());
        for (var row : report.rows()) artifacts.put(row.example().input().id() + "-" + row.profile().name() + ".search.json",
            row.observation().search().toCanonicalJson());
        return TraceStrategyTransferExample.writeArtifacts(report.contentHash(), artifacts, output, REVISION);
    }

    static String html(Report report) {
        var out = new StringBuilder("<!doctype html><html lang=\"de\"><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>Regelsuche · Gezielte Strategien</title>")
            .append("<style>body{font:17px/1.5 system-ui;max-width:1150px;margin:36px auto;padding:0 20px;background:#f7f9fa;color:#172b36}table{width:100%;border-collapse:collapse;margin:16px 0}th,td{text-align:left;border-bottom:1px solid #ccd9df;padding:10px;overflow-wrap:anywhere}details{background:white;border:1px solid #ccd9df;border-radius:8px;padding:14px;margin:12px 0}summary{cursor:pointer;font-weight:600}code{white-space:pre-wrap;overflow-wrap:anywhere}small{color:#455e6b}input{font:inherit;padding:10px;width:90%;max-width:500px}li{margin:10px 0}.table{overflow-x:auto}h1{line-height:1.2}</style>")
            .append("<h1>Gelernte Strategien gezielt einsetzen</h1><p>288 neue Polynomaufgaben, vier Verfahren, gleiche Budgets. Einzelne Fälle und jeder primitive Rechenschritt lassen sich unten prüfen.</p>")
            .append("<p>Das Training entscheidet, bei welchen verfügbaren Regeln eine gelernte Folge fortgesetzt wird. Ein vorhandener erster Schritt wird wiederverwendet; bei fehlender Fortsetzung bleiben die Einzelregeln verfügbar.</p>")
            .append("<div class=\"table\"><table><thead><tr><th>Verfahren</th><th>Gezählte Arbeit</th><th>Schlechterer Score als Greedy</th></tr></thead><tbody>");
        for (var profile : Profile.values()) out.append("<tr><td>").append(profileLabel(profile)).append("</td><td>")
            .append(report.work(profile)).append("</td><td>").append(report.regressions(profile)).append("</td></tr>");
        out.append("</tbody></table></div><p>Einmalige gezählte Lernarbeit: ").append(report.policy().learningWork())
            .append(". Entwicklungskriterium erfüllt: ").append(report.passes() ? "ja" : "nein")
            .append(". Lernen plus Anwendung auf diesem Bestand günstiger als Greedy: ")
            .append(report.passes() && report.policy().learningWork() + report.work(Profile.LEARNED_DISPATCH) < report.work(Profile.FLAT_GREEDY) ? "ja" : "nein")
            .append(".</p><p><small>Öffentlicher Entwicklungsvergleich bekannter algebraischer Bausteine. Gezählte Sucharbeit und Prüfschritte sind keine Laufzeiten oder vollständigen CPU-Kosten. Keine allgemeine Überlegenheit und kein versiegelter Abschlusstest.</small></p>")
            .append("<label for=\"filter\">Aufgabe oder Ausdruck suchen</label><p><input id=\"filter\" type=\"search\" placeholder=\"z. B. product oder same-hint\"></p>");
        for (var example : evaluationInputs()) {
            out.append("<details data-case><summary>").append(TraceStrategyTransferExample.escape(example.input().id()))
                .append(" · <code>").append(TraceStrategyTransferExample.escape(example.input().expression())).append("</code></summary>");
            for (var row : report.rows().stream().filter(value -> value.example().equals(example)).toList()) {
                var state = row.observation().search().bestState();
                out.append("<details><summary>").append(profileLabel(row.profile())).append(" · Arbeit ")
                    .append(row.observation().measuredWork()).append("</summary><p>Ergebnis: <code>")
                    .append(TraceStrategyTransferExample.escape(state.expression())).append("</code></p><p>Primitive Schritte: ")
                    .append(state.primitiveDepth()).append(" · Programm verwendet: ").append(state.programUsed() ? "ja" : "nein")
                    .append("</p><ol><li><code>").append(TraceStrategyTransferExample.escape(state.path().getFirst())).append("</code></li>");
                TraceStrategyTransferExample.appendPrimitiveSteps(out, state.transformations());
                out.append("</ol><p><a href=\"").append(example.input().id()).append('-').append(row.profile())
                    .append(".search.json\">Vollständige Suche und Replay-Daten</a></p></details>");
            }
            out.append("</details>");
        }
        return out.append("<p><a href=\"report.md\">Bericht</a> · <a href=\"report.json\">Alle Vergleichsdaten</a> · <a href=\"policy.json\">Training und Auswahl</a> · <a href=\"manifest.json\">Hashmanifest</a></p>")
            .append("<script>document.getElementById('filter').addEventListener('input',function(){const q=this.value.toLowerCase();document.querySelectorAll('[data-case]').forEach(d=>{d.hidden=!d.firstElementChild.textContent.toLowerCase().includes(q)})});</script></html>").toString();
    }

    private static String profileLabel(Profile profile) {
        return switch (profile) {
            case FLAT_EXHAUSTIVE -> "Vollständige flache Suche";
            case FLAT_GREEDY -> "Einzelregeln: bester nächster Schritt";
            case LEARNED_DISPATCH -> "Gelernte bedingte Fortsetzung";
            case UNGATED_CONTINUATIONS -> "Fortsetzungen ohne gelernte Auswahl";
        };
    }

    public static void main(String[] args) throws IOException {
        if (args.length == 1 && args[0].equals("--protocol")) {
            System.out.print(protocol());
            return;
        }
        if (args.length == 1 && args[0].equals("--train-only")) {
            var trained = train();
            System.out.println(trained.toCanonicalJson());
            return;
        }
        if (args.length != 1) throw new IllegalArgumentException("usage: TraceStrategyDispatchExample OUTPUT_DIRECTORY");
        var report = run();
        System.out.println(markdown(report));
        System.out.println(write(report, Path.of(args[0])).resolve("index.html").toAbsolutePath());
    }
}
