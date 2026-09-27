package de.regelsuche.inventory;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.regelsuche.ast.*;
import de.regelsuche.evolution.*;
import de.regelsuche.search.moves.*;
import de.regelsuche.search.program.CompiledAstReplayCodec;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Map;

/** Registered test worker: fixed inventory, node objective and bounded typed executor, no callback deserialization. */
public final class WorkReplacementProcessFixture {
    private static final ObjectMapper JSON = new ObjectMapper();
    public static void main(String[] args) throws Exception {
        var input = new BufferedReader(new InputStreamReader(System.in));
        var setup = JSON.readTree(input.readLine());
        boolean lazy = setup.path("lazy").asBoolean();
        var journal = new WorkReplacementExperiment.Journal();
        var genome = TraceStrategyTransferExample.inventory();
        var inventory = WorkReplacementLearning.primitives(genome, journal, "restore");
        var all = inventory.newSearchSession(false, 0, 0);
        var selectedProviders = all.providers();
        if (setup.path("learned").asBoolean()) {
            var restored = WorkReplacementLearning.restore(setup.path("model").asText(), genome.contentHash(), journal, "restore");
            String oracleSchemaId = setup.path("oracleSchemaId").asText();
            if (lazy) {
                var included = oracleSchemaId.isEmpty() ? restored.schemas().stream()
                    .map(CheckedLearnedSchemaModel.Schema::id).collect(java.util.stream.Collectors.toSet())
                    : java.util.Set.of(oracleSchemaId);
                var prepared = WorkReplacementLearning.prepareSchemas(restored, oracleSchemaId.isEmpty() ? 4 : 1,
                    Map.of(), included, journal, "restore");
                var provider = prepared.provider();
                all = inventory.newSchemaSearchSession(restored, List.of(provider));
                selectedProviders = oracleSchemaId.isEmpty() ? all.providers() : List.of(provider);
            } else {
                all = inventory.newSchemaSearchSession(restored, 4, Map.of());
                selectedProviders = oracleSchemaId.isEmpty() ? all.providers()
                    : restored.providers(1, Map.of(), java.util.Set.of(oracleSchemaId));
            }
        } else journal.append(LifecycleWorkAccount.of(LifecycleWorkAccount.Receipt.skipped("restore/restore",
            LifecycleWorkAccount.Phase.RESTORE_REPROOF, "primitive baseline has no learned model")));
        var profile = new TypedSourcePolicySelection.Profile("frozen", selectedProviders, MovePriorityPolicy.INVENTORY_ORDER);
        var session = new WorkReplacementTypedExecution(profile, setup.path("historical").asBoolean(), List.of());
        System.out.println(JSON.writeValueAsString(journal.account().receipts()));
        for (String line; (line = input.readLine()) != null;) {
            var request = JSON.readTree(line);
            var source = new CompiledAstReplayCodec().decodeExpression(request.path("source").asText());
            var problem = new TypedMoveSearch.Problem(source, TypedMoveSearch.Context.sourceOnly(List.of(), MoveContext.Phase.FROZEN_EVALUATION),
                List.of(), MovePriorityPolicy.INVENTORY_ORDER, all.verifier(), state -> 0, MoveSearch.Mode.FAST,
                lazy ? MoveSearch.Scheduling.STAGED_INCREMENTAL : MoveSearch.Scheduling.STAGED,
                new MoveSearch.Budget(8, 8, lazy ? 100_000 : 100, 64, request.path("budget").asLong()));
            var query = new WorkReplacementExperiment.Query("query", request.path("source").asText(), problem, lazy ? WorkReplacementProcessFixture::paidScore : WorkReplacementProcessFixture::score);
            var quality = JSON.treeToValue(request.get("quality"), WorkReplacementManifest.Quality.class);
            var execution = new WorkReplacementExperiment.Journal();
            var result = session.execute(query, request.path("budget").asLong(), quality, execution, request.path("prefix").asText());
            System.out.println(JSON.writeValueAsString(Map.of("evaluation", result,
                "receipts", execution.account().receipts(), "accountingComplete", execution.complete())));
        }
    }
    static TypedSourceOnlySearch.Score score(TypedMoveSearch.State state) { return new TypedSourceOnlySearch.Score(nodes(state.expression()), 1); }
    static TypedSourceOnlySearch.Score paidScore(TypedMoveSearch.State state) {
        int visited = nodes(state.expression());
        return new TypedSourceOnlySearch.Score(visited, visited);
    }
    static int nodes(Expr expression) {
        if (expression instanceof BinaryExpr binary) return 1 + nodes(binary.left()) + nodes(binary.right());
        if (expression instanceof FunctionExpr function) return 1 + function.arguments().stream().mapToInt(WorkReplacementProcessFixture::nodes).sum();
        return 1;
    }
}
