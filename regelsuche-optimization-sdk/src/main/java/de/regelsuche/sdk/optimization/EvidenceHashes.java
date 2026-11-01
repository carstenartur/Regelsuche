package de.regelsuche.sdk.optimization;

import de.regelsuche.search.program.JointComputationPlan;
import de.regelsuche.search.program.CompiledAstReplayCodec;
import de.regelsuche.search.program.PreparedJointComputation;
import de.regelsuche.ast.*;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.security.*;
import java.util.*;

final class EvidenceHashes {
    private static final CompiledAstReplayCodec CODEC=new CompiledAstReplayCodec();
    private EvidenceHashes() {}
    record Digests(String source,String target,String trace) { }
    static final class Capture {
        private final GraphHash graph;
        private final String source,trace;
        Capture(OptimizationRequest request,PreparedJointComputation prepared,VerificationWork work) {
            graph=new GraphHash(work);
            source=plan(request.plan(),prepared,graph); trace=traceHash(request.sourceTrace(),graph);
        }
        Digests finish(JointComputationPlan target,PreparedJointComputation prepared) {
            return new Digests(source,plan(target,prepared,graph),trace);
        }
    }
    static String plan(JointComputationPlan plan) {
        return plan(plan,null);
    }
    static String plan(JointComputationPlan plan,VerificationWork work) {
        return plan(plan,null,new GraphHash(work));
    }
    private static String plan(JointComputationPlan plan,PreparedJointComputation prepared,GraphHash graph) {
        var text=new ByteArrayOutputStream();
        graph.append(text,"typed-value-plan-dag/v1");
        plan.inputs().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> graph.append(text,e.getKey(),e.getValue().id()));
        var expressions=prepared==null ? plan.resolveOutputs(graph::charge).expressions()
                : plan.outputs().stream().map(o -> prepared.nodes().get(prepared.outputBindings().get(o.name())).expression()).toList();
        for(int i=0;i<plan.outputs().size();i++) {
            var output=plan.outputs().get(i);
            graph.append(text,output.name(),output.type().id());
            graph.appendDigest(text,graph.expression(expressions.get(i),0));
        }
        return graph.digest(text);
    }
    static String trace(SourceEvaluationTrace trace) {
        return trace(trace,null);
    }
    static String trace(SourceEvaluationTrace trace,VerificationWork work) {
        return traceHash(trace,new GraphHash(work));
    }
    private static String traceHash(SourceEvaluationTrace trace,GraphHash graph) {
        var text=new ByteArrayOutputStream();
        graph.append(text,"typed-source-occurrences-dag/v1");
        trace.occurrences().forEach(o -> {
            graph.append(text,o.sourceId(),o.declaredKind().name(),o.evaluatedKind().name());
            graph.appendDigest(text,graph.expression(o.expression(),0));
        });
        return graph.digest(text);
    }
    /** Structural digests are independent of object sharing while traversal is bounded by the DAG. */
    private static final class GraphHash {
        private final IdentityHashMap<Expr,String> memo=new IdentityHashMap<>();
        private record FunctionKey(String name,List<String> children) { }
        private final Map<FunctionKey,String> functions=new HashMap<>();
        private final Map<String,String> variables=new HashMap<>();
        private final VerificationWork work;
        private int nodes;
        private long digestBytes;
        GraphHash(VerificationWork work) { this.work=work; }
        void charge(long units) { if(work!=null) work.charge(units); }
        String expression(Expr expression,int depth) {
            if(work!=null) work.charge(1);
            if(depth>JointComputationPlan.MAX_DEPTH) throw new IllegalArgumentException("EVIDENCE_GRAPH_BOUND");
            String known=memo.get(expression); if(known!=null) return known;
            if(++nodes>JointComputationPlan.MAX_NODES) throw new IllegalArgumentException("EVIDENCE_GRAPH_BOUND");
            if(expression instanceof VariableExpr variable) {
                if(variable.name().length()>CompiledAstReplayCodec.MAXIMUM_TEXT_CHARACTERS) throw new IllegalArgumentException("EVIDENCE_TEXT_BOUND");
                charge(1L+(variable.name().length()+15L)/16L);
                known=variables.get(variable.name());
                if(known!=null) { memo.put(expression,known); return known; }
            }
            FunctionKey key=null;
            if(expression instanceof FunctionExpr function) {
                if(function.name().length()>CompiledAstReplayCodec.MAXIMUM_TEXT_CHARACTERS) throw new IllegalArgumentException("EVIDENCE_TEXT_BOUND");
                charge(2L+function.arguments().size()+(function.name().length()+15L)/16L);
                key=new FunctionKey(function.name(),function.arguments().stream().map(child -> expression(child,depth+1)).toList());
                known=functions.get(key);
                if(known!=null) { memo.put(expression,known); return known; }
            }
            var text=new ByteArrayOutputStream();
            if(expression instanceof VariableExpr variable) append(text,"variable",variable.name());
            else if(expression instanceof NumberExpr number) {
                if(work!=null) work.charge(1L+(number.value().numerator().bitLength()+number.value().denominator().bitLength()+63L)/64L);
                append(text,"number",CODEC.encodeExpression(number));
            } else if(expression instanceof FunctionExpr function) {
                append(text,"function",function.name());
                for(String child:key.children()) appendDigest(text,child);
            } else throw new IllegalArgumentException("TYPED_JAVA_EVIDENCE_REQUIRED");
            String result=digest(text); memo.put(expression,result);
            if(key!=null) functions.put(key,result);
            if(expression instanceof VariableExpr variable) variables.put(variable.name(),result);
            return result;
        }
        void appendDigest(ByteArrayOutputStream target,String digest) {
            charge(2); // The internally generated SHA-256 hex field is always 67 ASCII bytes.
            if(target.size()>CompiledAstReplayCodec.MAXIMUM_BYTES-67) throw new IllegalArgumentException("EVIDENCE_TEXT_BOUND");
            target.writeBytes(("64:"+digest).getBytes(StandardCharsets.US_ASCII));
        }
        void append(ByteArrayOutputStream target,String... values) {
            for(String value:values) {
                if(value.length()>CompiledAstReplayCodec.MAXIMUM_TEXT_CHARACTERS) throw new IllegalArgumentException("EVIDENCE_TEXT_BOUND");
                charge(1L+(value.length()+15L)/16L);
                byte[] encoded=field(value).getBytes(StandardCharsets.UTF_8);
                if(encoded.length>CompiledAstReplayCodec.MAXIMUM_BYTES-target.size()) throw new IllegalArgumentException("EVIDENCE_TEXT_BOUND");
                target.writeBytes(encoded);
            }
        }
        String digest(ByteArrayOutputStream value) {
            if(value.size()>CompiledAstReplayCodec.MAXIMUM_BYTES-digestBytes) throw new IllegalArgumentException("EVIDENCE_TEXT_BOUND");
            charge(1L+(value.size()+63L)/64L);
            digestBytes+=value.size(); return hash(value.toByteArray());
        }
    }
    static String assumptions(Set<SemanticAssumption> assumptions) {
        return hash(assumptions.stream().map(a -> field(a.kind().name())+field(a.subject())+field(a.parameter())+field(a.provenance())).sorted().reduce("",String::concat));
    }
    private static String field(String value) {
        Objects.requireNonNull(value, "canonical field");
        for(int i=0;i<value.length();i++) {
            char c=value.charAt(i);
            if(Character.isHighSurrogate(c)) {
                if(++i>=value.length() || !Character.isLowSurrogate(value.charAt(i)))
                    throw new IllegalArgumentException("malformed Unicode in canonical field");
            } else if(Character.isLowSurrogate(c)) {
                throw new IllegalArgumentException("malformed Unicode in canonical field");
            }
        }
        return value.getBytes(StandardCharsets.UTF_8).length+":"+value;
    }
    static String hash(String value) { return hash(value.getBytes(StandardCharsets.UTF_8)); }
    private static String hash(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
