package de.regelsuche.search.moves;

import de.regelsuche.ast.Expr;
import de.regelsuche.search.program.CompiledAstReplayCodec;
import de.regelsuche.transform.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Native proposal. Immutable producer data does not authorize admission without independent replay. */
public record NativeSearchMove(AstRewriteTransport.Step step,MoveProvider.Descriptor descriptor,long generationCost,
        Set<String> capabilityDelta) implements SearchExecution.Edge<Expr,NativeSearchMove> {
    public NativeSearchMove { Objects.requireNonNull(step);Objects.requireNonNull(descriptor);capabilityDelta=Set.copyOf(capabilityDelta);if(generationCost<0)throw new IllegalArgumentException("negative generation work"); }
    public Expr sourceExpression(){return step.source();}
    @Override public Expr targetExpression(){return step.target();}
    @Override public int primitiveStepCount(){return 1;}
    @Override public ExecutionWork executionWork(){return new ExecutionWork(1,0,0);}
    @Override public String ruleId(){return step.rule();}
    @Override public String ruleFamily(){return descriptor.ruleFamily().equals("*")?step.rule():descriptor.ruleFamily();}
    @Override public List<String> assumptions(){return step.assumptions();}
    @Override public NativeSearchMove withCapabilityDelta(Set<String> delta){return new NativeSearchMove(step,descriptor,generationCost,delta);}
    @Override public void requireSource(Expr source){if(!step.source().equals(source))throw new IllegalArgumentException("native proposal differs from source");}
    /** Explicit export; never called by the native frontier or verifier. */
    public SearchMove exportLegacy(){
        var codec=new CompiledAstReplayCodec();String source=codec.encodeExpression(step.source()),target=codec.encodeExpression(step.target());
        var transformation=new Transformation(step.rule(),target,step.kind(),step.mayIncreaseComplexity(),step.estimatedCostDelta(),
            step.equivalencePreservingByConstruction(),"typed:"+digest(source+"\n"+target+"\n"+step.rule()),step.assumptions(),step.packId(),step.license());
        return SearchMove.from(transformation,descriptor,generationCost).withCapabilityDelta(capabilityDelta);
    }
    static String digest(String value){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException failure){throw new IllegalStateException(failure);}
    }
}
