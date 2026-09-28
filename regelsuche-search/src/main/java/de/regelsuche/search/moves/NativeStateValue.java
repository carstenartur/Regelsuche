package de.regelsuche.search.moves;

import de.regelsuche.ast.Expr;
import java.util.Map;
import java.util.TreeMap;

/** Costed structural capability descriptions; these are never mathematical proof. */
@FunctionalInterface
public interface NativeStateValue {
    Assessment evaluate(TypedMoveSearch.State state,TypedMoveSearch.Context context);
    record Capability(String providerId,Expr sourceExpression,String subtreePath,Expr matchedExpression,Expr rewrittenExpression)
            implements SearchExecution.Capability<Expr>,de.regelsuche.retention.RetainedGraph.View {
        @Override public void retainedReferences(de.regelsuche.retention.RetainedGraph.Visitor v){v.reference(providerId);v.reference(sourceExpression);v.reference(subtreePath);v.reference(matchedExpression);v.reference(rewrittenExpression);}
        public Capability {
            if(providerId==null || providerId.isBlank() || sourceExpression==null || subtreePath==null
                || matchedExpression==null || rewrittenExpression==null || matchedExpression.equals(rewrittenExpression))
                throw new IllegalArgumentException("capability requires an executable non-identity witness");
        }
    }
    record Assessment(int complexity,double value,long searchWork,long primitiveWork,Map<String,Capability> capabilities)
            implements SearchExecution.Assessment<Expr>,de.regelsuche.retention.RetainedGraph.View {
        @Override public void retainedReferences(de.regelsuche.retention.RetainedGraph.Visitor v){v.reference(capabilities);}
        public static final Assessment EMPTY=new Assessment(0,0,0,0,Map.of());
        public Assessment {
            if(complexity<0 || !Double.isFinite(value) || searchWork<0 || primitiveWork<0 || capabilities==null
                || capabilities.entrySet().stream().anyMatch(e->e.getKey()==null || e.getKey().isBlank() || e.getValue()==null))
                throw new IllegalArgumentException("invalid native state assessment");
            capabilities=de.regelsuche.retention.RetainedSortedMap.copyOf(capabilities);
        }
    }
    enum Empty implements NativeStateValue { INSTANCE; @Override public Assessment evaluate(TypedMoveSearch.State state,TypedMoveSearch.Context context){return Assessment.EMPTY;} }
    NativeStateValue NONE=Empty.INSTANCE;
}
