package de.regelsuche.search.moves;

import de.regelsuche.retention.RetainedGraph;

import de.regelsuche.ast.Expr;
import de.regelsuche.transform.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Native proposal. Admission still independently verifies the full retained producer data. */
public record NativeSearchMove(NativeMoveProof proof,MoveProvider.Descriptor descriptor,long generationCost,
        Set<String> capabilityDelta) implements SearchExecution.Edge<Expr,NativeSearchMove>,RetainedGraph.View {
    @Override public void retainedReferences(RetainedGraph.Visitor v){v.reference(proof);v.reference(descriptor);v.reference(capabilityDelta);}

    public NativeSearchMove {
        Objects.requireNonNull(proof);Objects.requireNonNull(descriptor);capabilityDelta=Set.copyOf(capabilityDelta);
        if(generationCost<0)throw new IllegalArgumentException("negative generation work");
    }
    public NativeSearchMove(AstRewriteTransport.Step step,MoveProvider.Descriptor descriptor,long generationCost,Set<String> delta){
        this(new NativeMoveProof.Primitive(step),descriptor,generationCost,delta);
    }
    public Expr sourceExpression(){return proof.source();}
    @Override public Expr targetExpression(){return proof.target();}
    @Override public int primitiveStepCount(){return Math.toIntExact(executionWork().primitiveRewrites());}
    @Override public ExecutionWork executionWork(){return proof.work();}
    @Override public String ruleId(){return descriptor.sourceKind()==SearchMove.SourceKind.PRIMITIVE?proof.rule():descriptor.id();}
    @Override public String ruleFamily(){return descriptor.ruleFamily().equals("*")?proof.rule():descriptor.ruleFamily();}
    @Override public List<String> assumptions(){return proof.assumptions();}
    @Override public NativeSearchMove withCapabilityDelta(Set<String> delta){return new NativeSearchMove(proof,descriptor,generationCost,delta);}
    @Override public void requireSource(Expr source){if(!proof.source().equals(source))throw new IllegalArgumentException("native proposal differs from source");}
    public SearchMove exportLegacy(){
        var transformation=proof.exportLegacy();
        try(var held=de.regelsuche.retention.RetainedOperation.retain(transformation)) {
            var projected=SearchMove.from(transformation,descriptor,generationCost);
            try(var first=de.regelsuche.retention.RetainedOperation.retain(projected)) {
                de.regelsuche.retention.RetainedOperation.work(3);
                return de.regelsuche.retention.RetainedOperation.produced(projected.withCapabilityDelta(capabilityDelta));
            }
        }
    }
    static String digest(String value){
        try{
            byte[] input=value.getBytes(StandardCharsets.UTF_8);
            try(var held=de.regelsuche.retention.RetainedOperation.retain(value,input)) {
                de.regelsuche.retention.RetainedOperation.work(Math.addExact(value.length(),input.length));
                byte[] digest=MessageDigest.getInstance("SHA-256").digest(input);
                try(var output=de.regelsuche.retention.RetainedOperation.retain(digest)) {
                    de.regelsuche.retention.RetainedOperation.work(digest.length*3L);
                    return de.regelsuche.retention.RetainedOperation.produced(HexFormat.of().formatHex(digest));
                }
            }
        }
        catch(NoSuchAlgorithmException failure){throw new IllegalStateException(failure);}
    }
}
