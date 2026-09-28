package de.regelsuche.search.moves;

import de.regelsuche.ast.Expr;
import java.util.Map;
import java.util.TreeMap;

/** Costed structural capability descriptions; these are never mathematical proof. */
@FunctionalInterface
public interface NativeStateValue {
    Assessment evaluate(TypedMoveSearch.State state,TypedMoveSearch.Context context);
    record Capability(String providerId,Expr sourceExpression,String subtreePath,Expr matchedExpression,Expr rewrittenExpression)
            implements SearchExecution.Capability<Expr> {
        public Capability {
            if(providerId==null || providerId.isBlank() || sourceExpression==null || subtreePath==null
                || matchedExpression==null || rewrittenExpression==null || matchedExpression.equals(rewrittenExpression))
                throw new IllegalArgumentException("capability requires an executable non-identity witness");
        }
    }
    record Assessment(int complexity,double value,long searchWork,long primitiveWork,Map<String,Capability> capabilities)
            implements SearchExecution.Assessment<Expr> {
        public static final Assessment EMPTY=new Assessment(0,0,0,0,Map.of());
        public Assessment {
            if(complexity<0 || !Double.isFinite(value) || searchWork<0 || primitiveWork<0 || capabilities==null
                || capabilities.entrySet().stream().anyMatch(e->e.getKey()==null || e.getKey().isBlank() || e.getValue()==null))
                throw new IllegalArgumentException("invalid native state assessment");
            capabilities=java.util.Collections.unmodifiableMap(new TreeMap<>(capabilities));
        }
    }
    NativeStateValue NONE=(state,context)->Assessment.EMPTY;
}
