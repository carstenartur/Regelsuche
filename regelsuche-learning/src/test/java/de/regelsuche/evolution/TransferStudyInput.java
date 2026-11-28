package de.regelsuche.evolution;

import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.search.moves.MoveSearch;
import java.util.List;

/** Only the source, node bound and common limits cross the fixture/solver boundary. */
interface TransferStudyInput {
    MoveSearch.Budget QUERY = new MoveSearch.Budget(32, 32, 200_000, 512, 8_192);

    int index();
    int variant();
    String source();
    int nodeBound();

    default CheckedSchemaTaskSolver.Task task() {
        return new CheckedSchemaTaskSolver.Task(new ExpressionParser().parseExactTerm(source()).expression(),
            List.of(), CheckedSchemaTaskSolver.Goal.atMostNodes(nodeBound()), QUERY);
    }
}
