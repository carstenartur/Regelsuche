package de.regelsuche.benchmark;

import static de.regelsuche.benchmark.ModPowTransferFiles.JSON;
import static de.regelsuche.benchmark.ModPowTransferFiles.hash;
import static de.regelsuche.benchmark.ModPowTransferFiles.read;
import static de.regelsuche.benchmark.ModPowTransferFiles.require;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.regelsuche.benchmark.ModPowTransferFiles.Row;
import de.regelsuche.mining.TypedOutputPattern;
import de.regelsuche.transform.RecognitionProfile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

/** Public v4 comparison. The reference oracle is used only for outcome checks and reporting. */
public final class ModPowDispatchStudy {
    static final String PROTOCOL = "03284a1911c4c83bf36c9665d0eb133c325ddf66b9ed31e67acba0c9954662a0";
    static final String HISTORICAL = "c5d040bd4ba2c088f9e229d8360997fb808c01ef2278c78a30bd1210bcbfc2b6";
    private static final List<String> ARMS = List.of("primitive", "exhaustive", "indexed");
    private ModPowDispatchStudy() {}

    public static void main(String[] args) throws Exception {
        require(args.length == 5 || args.length == 7, "acquire protocol TRAIN model receipt | evaluate protocol model receipt receipt-SHA256 historical-TEST result");
        Path protocolPath = Path.of(args[1]);
        var protocol = JSON.readTree(bound(protocolPath, PROTOCOL));
        if ("acquire".equals(args[0]) && args.length == 5) {
            var work = new ModPowFrozenTransfer.TrainWork();
            String train = protocol.required("trainSha256").asText();
            var model = ModPowFrozenTransfer.train(Path.of(args[2]), train, work);
            work.serializations++;
            byte[] bytes = JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(model);
            String digest = hash(bytes);
            require(digest.equals(protocol.required("modelSha256").asText()), "model drift");
            var receipt = JSON.createObjectNode().put("protocolSha256", PROTOCOL)
                .put("modelSha256", digest).put("trainSha256", train);
            receipt.set("counts", work.counts());
            receipt.put("work", total(work.counts()));
            Files.write(Path.of(args[3]), bytes, StandardOpenOption.CREATE_NEW);
            write(Path.of(args[4]), receipt);
            return;
        }
        require("evaluate".equals(args[0]) && args.length == 7, "wrong mode/arguments");
        var artifact = JSON.readTree(bound(Path.of(args[2]), protocol.required("modelSha256").asText()));
        var acquisition = JSON.readTree(bound(Path.of(args[3]), args[4]));
        require(acquisition.required("protocolSha256").asText().equals(PROTOCOL)
            && acquisition.required("trainSha256").equals(protocol.required("trainSha256"))
            && acquisition.required("modelSha256").equals(protocol.required("modelSha256")), "foreign receipt");
        long training = total(acquisition.required("counts"));
        require(training > 0 && training == acquisition.required("work").longValue(), "acquisition total mismatch");
        // Decode the persisted model at TEST startup in addition to the validation decode already charged at TRAIN.
        var matcher = new TypedOutputPattern(ModPowTransferFiles.candidate(artifact), RecognitionProfile.arithmeticAc());
        var index = matcher.compileIndex();
        var report = JSON.createObjectNode().put("schema", "regelsuche.modpow-dispatch-result/v4")
            .put("protocolSha256", PROTOCOL).put("testSha256", protocol.required("testSha256").asText())
            .put("historicalTestSha256", HISTORICAL).put("receiptSha256", args[4]).put("testModelDecodes", 1)
            .put("indexCompilationWork", index.compilationWork());
        report.set("acquisition", acquisition);
        var panel = ModPowTransferFiles.rows(bound(protocolPath.resolveSibling("test.json"),
            protocol.required("testSha256").asText()), "TEST");
        var historical = ModPowTransferFiles.rows(bound(Path.of(args[5]), HISTORICAL), "TEST");
        var budgets = protocol.required("budgetPoints");
        report.set("prospective", panel(panel, budgets, matcher, index, training + 1));
        report.set("retrospective", panel(historical, budgets, matcher, index, training + 1));
        write(Path.of(args[6]), report);
    }

    private static ObjectNode panel(List<Row> rows, JsonNode budgets, TypedOutputPattern matcher,
            TypedOutputPattern.Indexed index, long acquisition) {
        var out = JSON.createObjectNode();
        var cases = out.putArray("cases");
        long[] setup = {0, acquisition, acquisition + index.compilationWork()};
        long[] prefix = setup.clone();
        int[][] reach = new int[ARMS.size()][budgets.size()];
        for (var row : rows) {
            runRow(row, cases.addObject(), budgets, matcher, index, setup, prefix, reach);
        }
        var summary = out.putObject("summary");
        summary.set("budgets", budgets);
        for (int arm = 0; arm < ARMS.size(); arm++) {
            String name = ARMS.get(arm);
            var node = summary.putObject(name).put("setup", setup[arm]).put("streamWork", prefix[arm]);
            node.set("positiveStreamReach", JSON.valueToTree(reach[arm]));
            if (arm > 0) {
                node.set("warmDag", pairedOutcomes(cases, name, "warmDag"));
                node.set("coldDag", pairedOutcomes(cases, name, "coldDag"));
            }
        }
        return out;
    }

    private static void runRow(Row row, ObjectNode result, JsonNode budgets,
            TypedOutputPattern matcher, TypedOutputPattern.Indexed index, long[] setup, long[] prefix, int[][] reach) {
        var targets = ModPowSearchTrace.referenceTargets(row);
        long optimum = ModPowSearchTrace.referenceDag(row, targets);
        long original = ModPowFrozenTransfer.cost(row, row.source()).dag();
        result.put("id", row.id()).put("positive", row.positive()).put("sourceDag", original)
            .put("optimumDag", optimum).put("uniquePrimitiveTargets", targets.size());
        var exhaustive = matcher.findWithMatchWork(row.source(), TypedOutputPattern.MAXIMUM_ASSIGNMENTS);
        var indexed = index.find(row.source(), TypedOutputPattern.MAXIMUM_ASSIGNMENTS);
        require(exhaustive.result().complete() && indexed.matching().result().complete()
            && exhaustive.result().applications().equals(indexed.matching().result().applications()),
            "ordered applications differ on " + row.id());
        result.put("learnedApplications", exhaustive.result().applications().size());
        for (var proposal : exhaustive.result().applications()) {
            require(targets.contains(proposal.target()), "learned target outside reference grammar");
        }
        for (int arm = 0; arm < ARMS.size(); arm++) {
            var trace = new ModPowSearchTrace(row);
            if (arm == 1) trace.runLearned(exhaustive, 0);
            if (arm == 2) trace.runLearned(indexed.matching(), indexed.dispatch().total());
            trace.runPrimitive();
            require(targets.equals(trace.primitiveTargets()) && trace.best() == optimum, "reference mismatch: " + row.id());
            var node = result.putObject(ARMS.get(arm));
            node.set("ledger", JSON.valueToTree(trace.work()));
            if (arm == 2) node.set("dispatch", JSON.valueToTree(indexed.dispatch()));
            recordCheckpoints(node, trace, budgets, original, optimum, setup[arm], prefix[arm],
                row.positive(), reach[arm]);
            prefix[arm] += trace.completedWork();
        }
    }

    private static void recordCheckpoints(ObjectNode node, ModPowSearchTrace trace, JsonNode budgets,
            long original, long optimum, long setup, long prefix, boolean positive, int[] reach) {
        node.put("duplicates", trace.work().generated() - trace.work().replayed());
        node.set("improvements", JSON.valueToTree(trace.improvements()));
        long hit = trace.firstOptimum(original, optimum);
        node.put("firstOptimum", hit).put("streamFirstOptimum", prefix + hit)
            .put("completedWork", trace.completedWork());
        var checkpoints = node.putArray("checkpoints");
        for (int i = 0; i < budgets.size(); i++) {
            long budget = budgets.get(i).longValue();
            boolean reached = budget >= prefix + hit;
            if (positive && reached) reach[i]++;
            checkpoints.addObject().put("budget", budget).put("warmDag", at(trace, original, budget))
                .put("coldDag", at(trace, original, budget - setup)).put("streamOptimum", reached);
        }
    }

    private static ObjectNode pairedOutcomes(ArrayNode cases, String arm, String view) {
        int wins = 0, ties = 0, losses = 0;
        for (var row : cases) {
            if (!row.required("positive").booleanValue()) continue;
            var primitive = row.required("primitive").required("checkpoints");
            var learned = row.required(arm).required("checkpoints");
            for (int i = 0; i < primitive.size(); i++) {
                long baseline = primitive.get(i).required(view).longValue();
                long candidate = learned.get(i).required(view).longValue();
                if (candidate < baseline) wins++;
                else if (candidate > baseline) losses++;
                else ties++;
            }
        }
        return JSON.createObjectNode().put("wins", wins).put("ties", ties).put("losses", losses);
    }

    private static long at(ModPowSearchTrace trace, long original, long budget) {
        long value = original;
        for (var improvement : trace.improvements()) {
            if (improvement.work() > budget) break;
            value = improvement.dag();
        }
        return value;
    }

    private static long total(JsonNode counts) {
        long work = 0;
        for (var count : counts) {
            require(count.isIntegralNumber() && count.canConvertToLong() && count.longValue() >= 0, "invalid receipt count");
            work = Math.addExact(work, count.longValue());
        }
        return work;
    }

    private static byte[] bound(Path path, String digest) throws Exception {
        byte[] bytes = read(path);
        require(hash(bytes).equals(digest), "digest mismatch for " + path);
        return bytes;
    }

    private static void write(Path path, JsonNode root) throws Exception {
        Files.write(path, JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(root), StandardOpenOption.CREATE_NEW);
    }
}
