package de.regelsuche.search.moves;

import de.regelsuche.ast.Expr;
import de.regelsuche.search.program.*;
import de.regelsuche.transform.*;
import java.util.*;

/** Source-bound producer structures. Construction describes a proposal, never proves it. */
public sealed interface NativeMoveProof permits NativeMoveProof.Primitive,NativeMoveProof.Program,NativeMoveProof.Exact {
    Expr source(); Expr target(); List<String> assumptions(); ExecutionWork work(); String rule();
    Transformation exportLegacy();
    record Primitive(AstRewriteTransport.Step step) implements NativeMoveProof {
        public Primitive { Objects.requireNonNull(step); }
        @Override public Expr source(){return step.source();}
        @Override public Expr target(){return step.target();}
        @Override public List<String> assumptions(){return step.assumptions();}
        @Override public ExecutionWork work(){return new ExecutionWork(1,0,0);}
        @Override public String rule(){return step.rule();}
        @Override public Transformation exportLegacy(){
            var codec=new CompiledAstReplayCodec();String source=codec.encodeExpression(source()),target=codec.encodeExpression(target());
            return new Transformation(step.rule(),target,step.kind(),step.mayIncreaseComplexity(),step.estimatedCostDelta(),
                step.equivalencePreservingByConstruction(),"typed:"+NativeSearchMove.digest(source+"\n"+target+"\n"+step.rule()),step.assumptions(),step.packId(),step.license());
        }
    }
    record Program(CompiledAstRewriteProgram.Candidate history) implements NativeMoveProof {
        public Program { Objects.requireNonNull(history); }
        @Override public Expr source(){return history.source();}
        @Override public Expr target(){return history.target();}
        @Override public List<String> assumptions(){return history.assumptions();}
        @Override public ExecutionWork work(){return new ExecutionWork(history.steps().size(),0,0);}
        @Override public String rule(){return history.programId();}
        @Override public Transformation exportLegacy(){
            var codec=new CompiledAstReplayCodec();String identity=codec.contentHash(history);
            var steps=new ArrayList<Transformation>();
            for(int i=0;i<history.steps().size();i++) {
                var step=history.steps().get(i);
                steps.add(new Transformation(step.rule(),codec.encodeExpression(step.target()),step.kind(),step.mayIncreaseComplexity(),
                    step.estimatedCostDelta(),step.equivalencePreservingByConstruction(),"typed-program:"+identity+":"+i,step.assumptions(),step.packId(),step.license()));
            }
            return new RewriteCandidate(history.programId(),codec.encodeExpression(source()),codec.encodeExpression(target()),steps).toTransformation();
        }
    }
    record Exact(NativeExactTheoryEvidence evidence) implements NativeMoveProof {
        public Exact { Objects.requireNonNull(evidence); }
        @Override public Expr source(){return evidence.binding().source();}
        @Override public Expr target(){return evidence.binding().target();}
        @Override public List<String> assumptions(){return List.of();}
        @Override public ExecutionWork work(){return new ExecutionWork(0,1,evidence.binding().canonicalWorkUnits());}
        @Override public String rule(){return evidence.binding().theoryStepId();}
        @Override public Transformation exportLegacy(){return Transformation.exactTheory(evidence.exportLegacy());}
    }
}
