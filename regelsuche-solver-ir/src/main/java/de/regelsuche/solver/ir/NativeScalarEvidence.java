package de.regelsuche.solver.ir;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.regelsuche.assumption.Assumption;
import de.regelsuche.ast.Expr;
import de.regelsuche.egraph.*;
import de.regelsuche.egraph.proof.*;
import de.regelsuche.parse.*;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Production run and detached receiver for the declared, finite exact scalar lane. */
public final class NativeScalarEvidence {
    public static final String SCHEMA = "regelsuche.native-scalar-saturation/v1";
    private static final JsonMapper JSON = new JsonMapper();
    private static final ProofLimits LIMITS = ProofLimits.defaults();
    private NativeScalarEvidence() {}

    public record Archive(String report, Map<String,byte[]> certificates) {
        public Archive { certificates=Map.copyOf(certificates); }
        public void write(Path directory) throws IOException {
            Files.createDirectories(directory);
            for (var item : certificates.entrySet()) {
                Path target=directory.resolve(item.getKey());
                publish(target,item.getValue());
            }
            publish(directory.resolve("saturation.json"),report.getBytes(StandardCharsets.UTF_8));
        }
        private static void publish(Path target,byte[] data) throws IOException {
            Path temporary=Files.createTempFile(target.getParent(),"scalar-evidence-",".tmp");
            try {
                Files.write(temporary,data);
                try { Files.createLink(target,temporary); }
                catch (FileAlreadyExistsException exists) {
                    if (!Files.isRegularFile(target,LinkOption.NOFOLLOW_LINKS) || Files.size(target)!=data.length
                            || !Arrays.equals(Files.readAllBytes(target),data)) throw new IOException("conflicting immutable scalar evidence");
                }
            } finally { Files.deleteIfExists(temporary); }
        }
    }
    public static Archive run(String source,List<Assumption> assumptions,String revision,NativeScalarSaturation.Budget budget) {
        var rules=AstRewriteTransformationEngine.allBuiltInRules();
        var context=ScalarExtractionProof.Context.create(rules,assumptions,revision);
        Expr input=new ExpressionParser().parseTerm(source);
        var run=new EqualitySaturation(rules).saturateExactScalars(input,context,budget);
        var report=JSON.createObjectNode().put("schema",SCHEMA).put("source",source).put("contextHash",ScalarCertificateCodec.contextHash(context))
            .put("matcher",FlatAcMatchPlan.CONTEXTUAL_VERSION).put("orientation","native-matched-source-to-directed-target/v1")
            .put("scheduler",budget.schedule().name().toLowerCase(Locale.ROOT)+"/v1").put("terminal",run.terminal()).put("rounds",run.rounds())
            .put("graphNodes",run.graphNodes()).put("analysisEvaluations",run.analysisEvaluations());
        report.set("context",JSON.valueToTree(context));
        report.set("budget",JSON.valueToTree(budget));
        report.set("classes",JSON.valueToTree(run.analyses()));
        report.set("ledger",JSON.valueToTree(run.ledger()));
        var guards=report.putArray("guards");
        for (var guard : run.guards()) guards.addObject().put("rule",guard.rule()).put("factor",ExpressionFormatter.format(guard.factor()))
            .put("truth",guard.result()).put("applied",guard.applied());
        var journal=report.putArray("nativeUnionWitnesses");
        for (var step : run.unionEvidence()) journal.addObject().put("rule",step.ruleId()).put("before",ExpressionFormatter.format(step.before()))
            .put("after",ExpressionFormatter.format(step.after()));
        var files=new TreeMap<String,byte[]>();
        var extractions=report.putArray("extractions");
        for (var selection : run.extractions()) {
            var record=extractions.addObject().put("objective",selection.objective()).put("proposed",ExpressionFormatter.format(selection.proposal()))
                .put("authorized",ExpressionFormatter.format(selection.expression())).put("status",selection.status())
                .put("expressionNodes",selection.rawNodes()).put("proofSteps",selection.proofSteps());
            selection.proof().ifPresent(bundle -> attach(record,"derivation",files,bundle));
        }
        var selected=run.extractions().getFirst();
        var backend=new ScalarPolynomialProofWitness();
        backend.execute(context,input,selected.expression()).ifPresent(receipt -> {
            var lemma=new ScalarExtractionProof.Lemma(ScalarExtractionProof.BACKEND,input,selected.expression(),receipt);
            ScalarExtractionProof.build(input,selected.expression(),context,List.of(lemma),LIMITS,backend)
                .ifPresent(bundle -> attach(report,"independentPolynomialCheck",files,bundle));
        });
        return new Archive(report.toString()+"\n",files);
    }
    private static void attach(ObjectNode record,String field,Map<String,byte[]> files,ScalarExtractionProof.Bundle bundle) {
        byte[] bytes=ScalarCertificateCodec.encode(bundle,LIMITS);
        String digest=ScalarCertificateCodec.hash(bytes);
        files.put(digest.substring(7)+".rsproof",bytes);
        record.put(field,digest);
    }

    public static void checkCertificate(Path path,String digest,String source,String target,List<Assumption> assumptions,String revision) throws IOException {
        if (!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS) || Files.size(path)>ScalarCertificateCodec.MAX_BYTES) throw new IOException("invalid certificate file");
        var receiver=ScalarExtractionProof.Context.create(AstRewriteTransformationEngine.allBuiltInRules(),assumptions,revision);
        var proof=ScalarCertificateCodec.read(Files.readAllBytes(path),digest,receiver,LIMITS);
        var parser=new ExpressionParser();
        if (!ScalarExtractionProof.check(parser.parseTerm(source),parser.parseTerm(target),receiver,proof,LIMITS,new ScalarPolynomialProofWitness()))
            throw new IllegalArgumentException("detached scalar proof rejected");
    }

    public static void verify(Path directory,String expectedHash,String source,List<Assumption> assumptions,String revision,
                              NativeScalarSaturation.Budget budget) throws IOException {
        var expected=run(source,assumptions,revision,budget);
        var data=expected.report().getBytes(StandardCharsets.UTF_8);
        Path report=directory.resolve("saturation.json");
        if (!ScalarCertificateCodec.hash(data).equals(expectedHash) || Files.size(report)!=data.length
                || !Arrays.equals(data,Files.readAllBytes(report))) throw new IllegalArgumentException("saturation report did not reproduce");
        for (var entry:expected.certificates().entrySet()) {
            var file=directory.resolve(entry.getKey());
            if (Files.size(file)!=entry.getValue().length || !Arrays.equals(Files.readAllBytes(file),entry.getValue()))
                throw new IllegalArgumentException("retained extraction certificate did not reproduce");
        }
    }
}
