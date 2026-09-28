package de.regelsuche.evolution;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.retention.RetainedGraph;
import de.regelsuche.search.moves.*;

/** Actual captures are explicit; no test adapter grants mathematical authority. */
final class NativeTestObservation {
    static final class Checks implements NativeVerifier,RetainedGraph.View {
        private final NativeVerifier verifier;
        private int calls;
        Checks(NativeVerifier verifier){this.verifier=verifier;}
        int calls(){return calls;}
        @Override public NativeVerification verify(TypedMoveSearch.State state,NativeSearchMove move,TypedMoveSearch.Context context){
            calls++;return verifier.verify(state,move,context);
        }
        @Override public void retainedReferences(RetainedGraph.Visitor v){v.reference(verifier);}
    }
    enum Objective implements TypedSourceOnlySearch.Objective,RetainedGraph.View {
        DEPTH, POWER;
        @Override public TypedSourceOnlySearch.Score evaluate(TypedMoveSearch.State state){
            boolean adequate=this==DEPTH?state.searchDepth()>0:state.expression() instanceof BinaryExpr binary && binary.operator()==BinaryOperator.POW;
            return new TypedSourceOnlySearch.Score(adequate?0:1,1);
        }
        @Override public void retainedReferences(RetainedGraph.Visitor v){}
    }
    private NativeTestObservation(){}
}
