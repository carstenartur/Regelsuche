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
        var journal = new WorkReplacementExperiment.Journal();
        var genome = TraceStrategyTransferExample.inventory();
        var inventory = WorkReplacementLearning.primitives(genome, journal, "restore");
        var all = inventory.newSearchSession(false, 0, 0);
        if (setup.path("learned").asBoolean()) {
            var restored = WorkReplacementLearning.restore(setup.path("model").asText(), genome.contentHash(), journal, "restore");
            all = inventory.newSchemaSearchSession(restored, 4, Map.of());
        } else journal.append(LifecycleWorkAccount.of(LifecycleWorkAccount.Receipt.skipped("restore/restore",
            LifecycleWorkAccount.Phase.RESTORE_REPROOF, "primitive baseline has no learned model")));
        var profile = new TypedSourcePolicySelection.Profile("frozen", all.providers(), MovePriorityPolicy.INVENTORY_ORDER);
        var session = new WorkReplacementTypedExecution(profile, setup.path("historical").asBoolean(), List.of());
        System.out.println(JSON.writeValueAsString(journal.account().receipts()));
        for (String line; (line = input.readLine()) != null;) {
            var request = JSON.readTree(line);
            var source = new CompiledAstReplayCodec().decodeExpression(request.path("source").asText());
            var problem = new TypedMoveSearch.Problem(source, TypedMoveSearch.Context.sourceOnly(List.of(), MoveContext.Phase.FROZEN_EVALUATION),
                List.of(), MovePriorityPolicy.INVENTORY_ORDER, all.verifier(), state -> 0, MoveSearch.Mode.FAST,
                MoveSearch.Scheduling.STAGED, new MoveSearch.Budget(8, 8, 100, 64, request.path("budget").asLong()));
            var query = new WorkReplacementExperiment.Query("query", request.path("source").asText(), problem, WorkReplacementProcessFixture::score);
            var quality = JSON.treeToValue(request.get("quality"), WorkReplacementManifest.Quality.class);
            var execution = new WorkReplacementExperiment.Journal();
            var result = session.execute(query, request.path("budget").asLong(), quality, execution, request.path("prefix").asText());
            System.out.println(JSON.writeValueAsString(Map.of("evaluation", result, "receipts", execution.account().receipts())));
        }
    }
    static TypedSourceOnlySearch.Score score(TypedMoveSearch.State state) { return new TypedSourceOnlySearch.Score(nodes(state.expression()), 1); }
    static int nodes(Expr expression) {
        if (expression instanceof BinaryExpr binary) return 1 + nodes(binary.left()) + nodes(binary.right());
        if (expression instanceof FunctionExpr function) return 1 + function.arguments().stream().mapToInt(WorkReplacementProcessFixture::nodes).sum();
        return 1;
    }
}
