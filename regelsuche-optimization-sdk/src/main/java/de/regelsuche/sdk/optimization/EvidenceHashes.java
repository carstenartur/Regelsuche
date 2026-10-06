package de.regelsuche.sdk.optimization;

import de.regelsuche.search.program.JointComputationPlan;
import de.regelsuche.search.program.CompiledAstReplayCodec;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

final class EvidenceHashes {
    private static final CompiledAstReplayCodec CODEC=new CompiledAstReplayCodec();
    private EvidenceHashes() {}
    static String plan(JointComputationPlan plan) {
        var text=new StringBuilder();
        plan.inputs().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> text.append(field(e.getKey())).append(field(e.getValue().id())));
        plan.outputs().forEach(o -> text.append(field(o.name())).append(field(o.type().id())));
        text.append(field(CODEC.encodeExpression(plan.expression()))); return hash(text.toString());
    }
    static String trace(SourceEvaluationTrace trace) {
        var text=new StringBuilder();
        trace.occurrences().forEach(o -> text.append(field(o.sourceId())).append(field(o.declaredKind().name())).append(field(o.evaluatedKind().name())).append(field(CODEC.encodeExpression(o.expression()))));
        return hash(text.toString());
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
    static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
