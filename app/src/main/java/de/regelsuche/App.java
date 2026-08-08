package de.regelsuche;

import de.regelsuche.app.transform.SymPyTransformationEngine;
import de.regelsuche.cli.CliRouter;
import de.regelsuche.evolution.ProofCarryingShowcaseTrainAndFreezeCommand;
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
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

public class App {
    public static void main(String[] args) {
        if (args.length == 0) {
            printUsage();
            return;
        }

        String firstArg = args[0].trim();
        if ("showcase-train-freeze".equals(firstArg)) {
            ProofCarryingShowcaseTrainAndFreezeCommand.main(
                Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        if (CliRouter.isSubcommand(firstArg)) {
            int exit = new CliRouter().run(args);
            if (exit != 0) {
                System.exit(exit);
            }
            return;
        }

        if (args.length < 2) {
            printUsage();
            return;
        }

        InputType type;
        try {
            type = InputType.valueOf(firstArg.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            printUsage();
            return;
        }
        String expression = String.join(
            " ",
            Arrays.copyOfRange(args, 1, args.length));

        try (ExpressionGraphStore store = createStore()) {
            TransformationSearchService service =
                new TransformationSearchService(
                    new SymPyTransformationEngine(),
                    store,
                    new SearchHeuristic(5, 500, 2),
                    new ConsoleNotifier());

            service.submit(new InputRequest(type, expression)).join();
            Optional<SimplificationSuccess> best = service.getBestSolution();
            GraphSnapshot snapshot = service.getGraphSnapshot();

            best.ifPresentOrElse(
                success -> System.out.println(
                    "Best simplification: "
                        + success.simplifiedExpression()),
                () -> System.out.println("No simplification found yet"));
            System.out.println(
                "Graph nodes: " + snapshot.nodes().size()
                    + ", edges: " + snapshot.edges().size());
            service.shutdown();
        }
    }

    private static ExpressionGraphStore createStore() {
        String uri = System.getenv("NEO4J_URI");
        String user = System.getenv("NEO4J_USER");
        String password = System.getenv("NEO4J_PASSWORD");
        if (uri != null && !uri.isBlank()
                && user != null && password != null) {
            return new Neo4jExpressionGraphStore(uri, user, password);
        }
        return new InMemoryExpressionGraphStore();
    }

    private static void printUsage() {
        System.out.println("Usage:");
        System.out.println("  <term|equation|system> <expression>");
        System.out.println(
            "  discover [--min N] [--max N] "
                + "[--export json,markdown,mermaid,latex,inventory] "
                + "[--dir PATH]");
        System.out.println("  transform <expression>");
        System.out.println(
            "  showcase-train-freeze <showcase-plan.json> "
                + "<repository-commit> <output-directory>");
        System.out.println(
            "  plugins list|reload|watch|status "
                + "[--dir PATH] [--rules PATH]");
        System.out.println("  rules list [--dir PATH] [--profile ID]");
        System.out.println("  rules validate <file.regelsuche>");
        System.out.println("  rules conflicts [--dir PATH]");
        System.out.println(
            "  rules profiles [--dir PATH] [--profile ID]");
        System.out.println("  rules debug <expression> [--dir PATH]");
        System.out.println(
            "  rules import <file-or-dir> [--into PATH]");
        System.out.println(
            "  rules export [--profile ID] [--dir PATH] [--out PATH]");
        System.out.println("  inventory list");
        System.out.println("  inventory export --format json [--dir PATH]");
        System.out.println(
            "  path show <pathId> --format markdown|latex|mermaid|json");
        System.out.println("Valid input types: term, equation, system");
    }
}
