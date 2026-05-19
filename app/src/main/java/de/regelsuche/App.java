package de.regelsuche;

import de.regelsuche.graph.ExpressionGraphStore;
import de.regelsuche.graph.GraphSnapshot;
import de.regelsuche.graph.InMemoryExpressionGraphStore;
import de.regelsuche.graph.Neo4jExpressionGraphStore;
import de.regelsuche.input.InputRequest;
import de.regelsuche.input.InputType;
import de.regelsuche.notify.ConsoleNotifier;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.search.SimplificationSuccess;
import de.regelsuche.search.TransformationSearchService;
import de.regelsuche.transform.SymPyTransformationEngine;
import java.util.Optional;

public class App {
    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("Usage: <term|equation|system> <expression>");
            return;
        }

        InputType type = InputType.valueOf(args[0].trim().toUpperCase());
        String expression = args[1];

        try (ExpressionGraphStore store = createStore()) {
            TransformationSearchService service = new TransformationSearchService(
                new SymPyTransformationEngine(),
                store,
                new SearchHeuristic(5, 500, 2),
                new ConsoleNotifier()
            );

            service.submit(new InputRequest(type, expression)).join();
            Optional<SimplificationSuccess> best = service.getBestSolution();
            GraphSnapshot snapshot = service.getGraphSnapshot();

            best.ifPresentOrElse(
                success -> System.out.println("Best simplification: " + success.simplifiedExpression()),
                () -> System.out.println("No simplification found yet")
            );
            System.out.println("Graph nodes: " + snapshot.nodes().size() + ", edges: " + snapshot.edges().size());
            service.shutdown();
        }
    }

    private static ExpressionGraphStore createStore() {
        String uri = System.getenv("NEO4J_URI");
        String user = System.getenv("NEO4J_USER");
        String password = System.getenv("NEO4J_PASSWORD");
        if (uri != null && !uri.isBlank() && user != null && password != null) {
            return new Neo4jExpressionGraphStore(uri, user, password);
        }
        return new InMemoryExpressionGraphStore();
    }
}
