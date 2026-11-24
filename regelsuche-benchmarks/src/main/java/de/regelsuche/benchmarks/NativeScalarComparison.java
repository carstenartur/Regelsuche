package de.regelsuche.benchmarks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import de.regelsuche.assumption.Assumption;
import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.egraph.NativeScalarSaturation;
import de.regelsuche.egraph.proof.ScalarCertificateCodec;
import de.regelsuche.egraph.proof.ScalarExtractionProof;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.search.strategy.*;
import de.regelsuche.solver.ir.NativeScalarEvidence;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.util.AtomicJsonFile;
import de.regelsuche.value.ExprValueArena;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Public #620 source controls. Targets are consumed only after the three source-only executions return. */
final class NativeScalarComparison {
    private static final JsonMapper JSON=new JsonMapper();
    private static final String PANEL="native-scalar-panel-v1.json";
    private NativeScalarComparison() {}

    static byte[] readPanel() throws IOException {
        try (var input=NativeScalarComparison.class.getResourceAsStream(PANEL)) {
            if (input==null) throw new IOException("missing native scalar comparison panel");
            return input.readAllBytes();
        }
    }

    static NativeScalarSaturation.Budget budget(NativeScalarSaturation.Schedule schedule) {
        return new NativeScalarSaturation.Budget(8, 256, 4000, schedule);
    }

    static void write(Path script,String revision,Path output) throws IOException {
        byte[] panel=readPanel();
        var cases=JSON.readTree(panel);
        var requests=JSON.createArrayNode();
        for(var row:cases) {
            var request=requests.addObject();
            for (String key:List.of("id","source","operation","assumptions")) request.set(key,row.required(key));
        }
        String external=foreign(script,requests.toString());
        var foreign=JSON.readTree(external);
        if (!"1.14.0".equals(foreign.required("sympy").asText()) || !foreign.required("mpmath").asText().equals("1.3.0")
                || foreign.required("cases").size()!=cases.size()) throw new IOException("foreign software or panel mismatch");
        var report=JSON.createObjectNode().put("schema","regelsuche.native-conditional-ac-comparison/v1")
            .put("sourceRevision",revision).put("panelHash",ScalarCertificateCodec.hash(panel))
            .put("externalReferenceHash",ScalarCertificateCodec.hash(Files.readAllBytes(script)))
            .put("externalReportHash",ScalarCertificateCodec.hash(external.getBytes(StandardCharsets.UTF_8)))
            .put("historicalCorpusSha256","53bff974a9ecb101b85e01f202d095fb2a99f5f33127ed7ec0e4ade2685ac422")
            .put("scope","Known public syntax targets evaluated after source-only runs; structural reachability modulo exact scalar AC.")
            .put("workComparison","E-graph matcher fuel, ordered path states and SymPy count_ops measure distinct operations; no cross-engine work ratio.")
            .put("externalProof","SymPy returns an expression and no detached proof. Native changed outputs require receiver-checked proof DAGs.");
        var outcomes=report.putArray("cases");
        for (int index=0;index<cases.size();index++) {
            var row=cases.get(index); var ext=foreign.required("cases").get(index);
            String id=row.required("id").asText(), source=row.required("source").asText(), target=row.required("target").asText();
            if (!id.equals(ext.required("id").asText()) || !source.equals(ext.required("source").asText())
                    || !row.get("assumptions").equals(ext.required("assumptions")) || !row.get("operation").equals(ext.required("operation")))
                throw new IOException("foreign source binding mismatch");
            var premises=JSON.readerForListOf(String.class).<List<String>>readValue(row.required("assumptions"));
            var context=premises.stream().map(p->Assumption.nonZero(p.substring(0,p.length()-5))).toList();
            var result=outcomes.addObject().put("id",id).put("corpus",row.required("corpus").asText()).put("source",source).put("postRunTarget",target);
            result.set("assumptions",row.required("assumptions"));
            result.set("external",ext);
            result.put("externalReached",ext.has("output") && same(ext.get("output").asText(),target));
            var schedules=result.putArray("native");
            for(var scheduler:NativeScalarSaturation.Schedule.values()) {
                var budget=budget(scheduler);
                var artifact=NativeScalarEvidence.run(source,context,revision,budget);
                var dir=output.resolve(id).resolve(scheduler.name());
                artifact.write(dir);
                var digest=ScalarCertificateCodec.hash(artifact.report().getBytes(StandardCharsets.UTF_8));
                NativeScalarEvidence.verify(dir,digest,source,context,revision,budget);
                var actual=JSON.readTree(artifact.report()); var chosen=actual.required("extractions").get(0);
                schedules.addObject().put("schedule",scheduler.name()).put("report",digest).put("status",chosen.required("status").asText())
                    .put("reached",same(chosen.required("authorized").asText(),target)).put("nodes",actual.required("graphNodes").asInt())
                    .put("proofSteps",chosen.required("proofSteps").asInt()).put("rawExpressionNodes",chosen.required("expressionNodes").asInt());
            }
            path(result.putObject("orderedPath"),source,target,premises,context,revision);
            boolean expectedReach=!id.equals("unknown-cancellation");
            if (result.required("externalReached").asBoolean()!=expectedReach
                    || result.required("orderedPath").required("reached").asBoolean()!=(expectedReach && !id.equals("opposite-factor-roles")))
                throw new IOException("external or ordered-path control differed: " + id);
            for (var observed:schedules) {
                if (!observed.required("status").asText().equals("CHECKED_EQUALITY") || observed.required("reached").asBoolean()!=expectedReach)
                    throw new IOException("native scalar control differed: " + id);
            }
        }
        Files.createDirectories(output);
        AtomicJsonFile.writeUtf8(output.resolve("sympy.json"),external);
        AtomicJsonFile.writeUtf8(output.resolve("comparison.json"),report.toString()+"\n");
    }

    private static void path(com.fasterxml.jackson.databind.node.ObjectNode record,String source,String target,List<String> premises,
                             List<Assumption> assumptions,String revision) {
        var rules=AstRewriteTransformationEngine.allBuiltInRules();
        var context=ScalarExtractionProof.Context.create(rules,assumptions,revision);
        var engine=new AstRewriteTransformationEngine(rules.stream().filter(r->context.enables(r.id())).toList(),16,32);
        var budget=new SearchHeuristic(4,32,1,2,32,16);
        var search=new BestFirstSearchStrategy().searchWithDiagnostics(new SearchProblem(source,engine,new ExpressionScorer(),new ExpressionCanonicalizer(),budget));
        boolean reached=search.states().stream().anyMatch(s -> premises.containsAll(s.assumptions()) && same(s.expression(),target));
        record.put("reached",reached).put("visited",search.states().size()).put("maxDepth",budget.maxDepth())
            .put("maxVisited",budget.maxVisitedExpressions()).put("proof","RETAINED_DIRECTED_PRIMITIVE_PATH");
    }
    private static boolean same(String a,String b) {
        var parser=new ExpressionParser();
        try(var arena=new ExprValueArena(4096)) { return arena.intern(parser.parseTerm(a))==arena.intern(parser.parseTerm(b)); }
    }
    private static String foreign(Path script,String request) throws IOException {
        Path response=Files.createTempFile("native-scalar-sympy-", ".json");
        try {
            var pb=new ProcessBuilder(System.getenv().getOrDefault("REGELSUCHE_SYMPY_PYTHON","python3"),script.toAbsolutePath().toString(),"--native-scalar-request");
            var process=pb.redirectOutput(response.toFile()).redirectError(ProcessBuilder.Redirect.INHERIT).start();
            try {
                try(var stream=process.getOutputStream()){stream.write(request.getBytes(StandardCharsets.UTF_8));}
                if (!process.waitFor(30,TimeUnit.SECONDS) || process.exitValue()!=0 || Files.size(response)>65536)
                    throw new IOException("external SymPy comparison failed");
            } catch (InterruptedException e) {Thread.currentThread().interrupt();throw new IOException("external comparison interrupted",e);}
            finally { if(process.isAlive())process.destroyForcibly(); }
            return Files.readString(response);
        } finally {Files.deleteIfExists(response);}
    }
}
