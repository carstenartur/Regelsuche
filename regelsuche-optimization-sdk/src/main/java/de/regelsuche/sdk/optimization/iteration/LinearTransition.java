package de.regelsuche.sdk.optimization.iteration;

import de.regelsuche.ast.Expr;
import java.util.*;

/** Immutable transport: states, constant one, then index powers in ascending degree. */
public record LinearTransition(List<List<Expr>> matrix, List<Expr> initial, int stateCount, int indexDegree) {
    public LinearTransition {
        int dimension=stateCount+indexDegree+1;
        if(stateCount<1 || stateCount>LinearIteration.MAX_STATES || indexDegree<0 || indexDegree>LinearIteration.MAX_INDEX_DEGREE
                || matrix.size()!=dimension || initial.size()!=dimension)
            throw new IllegalArgumentException("TRANSITION_DIMENSION");
        var copy=new ArrayList<List<Expr>>();
        for(var row:matrix) {
            if(row.size()!=dimension) throw new IllegalArgumentException("TRANSITION_DIMENSION");
            for(var value:row) LinearIteration.validate(value,null);
            copy.add(List.copyOf(row));
        }
        matrix=List.copyOf(copy); initial=List.copyOf(initial);
        for(var value:initial) LinearIteration.validate(value,null);
    }
}
