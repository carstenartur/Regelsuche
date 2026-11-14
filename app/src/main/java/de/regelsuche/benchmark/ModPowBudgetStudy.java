package de.regelsuche.benchmark;

import static de.regelsuche.benchmark.ModPowTransferFiles.JSON;
import static de.regelsuche.benchmark.ModPowTransferFiles.hash;
import static de.regelsuche.benchmark.ModPowTransferFiles.read;
import static de.regelsuche.benchmark.ModPowTransferFiles.require;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.regelsuche.ast.Expr;
import de.regelsuche.benchmark.ModPowTransferFiles.Row;
import de.regelsuche.mining.TypedOutputPattern;
import de.regelsuche.transform.RecognitionProfile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.List;

/** Finite, prefix-accounted study of an already published modular-program learner. */
public final class ModPowBudgetStudy {
    static final String PROTOCOL = "80610c07660656ffa96e80070c5c7faec0745ce9735067c1e0e32c096b1f95af";
    private static final String ACQUISITION = "1f09b47df5044ec226e5f3a4aa496cc87fea785764548f03140a6b694912e7db";
    private ModPowBudgetStudy() {}

    public static void main(String[] args) throws Exception {
        require(args.length == 5, "acquire protocol TRAIN new-model new-receipt | evaluate protocol model receipt new-result (TEST beside protocol)");
        Path protocolFile = Path.of(args[1]);
        var protocol = JSON.readTree(bound(protocolFile, PROTOCOL));
        if ("acquire".equals(args[0])) {
            acquire(protocol, Path.of(args[2]), Path.of(args[3]), Path.of(args[4]));
        } else {
            require("evaluate".equals(args[0]), "unknown mode");
            var model = JSON.readTree(bound(Path.of(args[2]), protocol.required("modelSha256").asText()));
            byte[] receipt = bound(Path.of(args[3]), ACQUISITION);
            var acquisition = JSON.readTree(receipt);
            require(acquisition.required("modelSha256").equals(protocol.required("modelSha256"))
                && acquisition.required("protocolSha256").asText().equals(PROTOCOL)
                && acquisition.required("trainSha256").equals(protocol.required("trainSha256")), "foreign acquisition");
            var rows = ModPowTransferFiles.rows(bound(protocolFile.resolveSibling("test.json"),
                protocol.required("testSha256").asText()), "TEST");
            var result = evaluate(protocol, model, acquisition, rows);
            result.put("acquisitionSha256", hash(receipt));
            write(Path.of(args[4]), result);
        }
    }

    private static void acquire(JsonNode protocol, Path train, Path model, Path receipt) throws Exception {
        byte[] corpus = bound(train, protocol.required("trainSha256").asText());
        var rows = ModPowTransferFiles.rows(corpus, "TRAIN");
        var artifact = ModPowFrozenTransfer.train(train);
        byte[] bytes = JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(artifact);
        require(hash(bytes).equals(protocol.required("modelSha256").asText()), "TRAIN model drift");
        var counts = JSON.createObjectNode();
        var matching = ModPowSearchTrace.NONE;
        for (var row : rows) {
            for (var sample : List.of(row, ModPowFrozenTransfer.shuffled(row))) {
                var baseline = ModPowFrozenTransfer.baseline(sample);
                var trace = new ModPowSearchTrace(sample);
                trace.runPrimitive();
                require(trace.primitiveTargets().equals(new HashSet<>(baseline.approved())), "TRAIN primitive mismatch");
                matching = matching.plus(trace.work().primitiveMatch());
                add(counts, "primitiveGeneratorCalls", 1);
                add(counts, "generatedCandidates", baseline.generated());
                add(counts, "replayRequests", baseline.generated());
                Expr best = ModPowFrozenTransfer.best(sample, baseline.approved(), expression -> {
                    add(counts, "scoreCalls", 1);
                    return ModPowFrozenTransfer.cost(sample, expression);
                });
                int explicitScores = sample == row ? 4 : 2;
                // v1 scores both sides of the savings test and writes both scores for retained witnesses.
                add(counts, "scoreCalls", explicitScores);
                if (sample == row) {
                    require(ModPowCompositionReplay.verify(row.source(), best, row.assumptions()).isPresent(), "TRAIN witness");
                    add(counts, "replayRequests", 1);
                    add(counts, "trainingExamples", 1);
                }
            }
        }
        add(counts, "primitiveMatchAttempts", matching.attempts());
        add(counts, "primitiveMatcherSteps", matching.steps());
        add(counts, "primitiveMatcherBranches", matching.branches());
        add(counts, "generalizations", 1);
        add(counts, "serializations", 1);
        add(counts, "modelDecodes", 1);
        var result = JSON.createObjectNode().put("protocolSha256", PROTOCOL)
            .put("trainSha256", hash(corpus)).put("modelSha256", hash(bytes));
        result.set("counts", counts);
        result.put("work", countWork(counts));
        Files.write(model, bytes, StandardOpenOption.CREATE_NEW);
        write(receipt, result);
    }

    private static ObjectNode evaluate(JsonNode protocol, JsonNode model, JsonNode acquisition, List<Row> rows) {
        var matcher = new TypedOutputPattern(ModPowTransferFiles.candidate(model), RecognitionProfile.arithmeticAc());
        long setup = countWork(acquisition.required("counts"));
        require(setup == acquisition.required("work").longValue() && setup > 0, "invalid acquisition cost");
        var report = JSON.createObjectNode().put("schema", "regelsuche.modpow-budget-result/v3")
            .put("protocolSha256", PROTOCOL);
        report.set("modelSha256", protocol.required("modelSha256"));
        report.set("testSha256", protocol.required("testSha256"));
        report.set("acquisition", acquisition);
        var cases = report.putArray("cases");
        long primitivePrefix = 0, learnedPrefix = setup;
        for (Row row : rows) {
            var targets = ModPowSearchTrace.referenceTargets(row);
            long optimum = ModPowSearchTrace.referenceDag(row, targets);
            long original = ModPowFrozenTransfer.cost(row, row.source()).dag();
            var primitive = new ModPowSearchTrace(row);
            primitive.runPrimitive();
            var learned = new ModPowSearchTrace(row);
            learned.runLearned(matcher);
            learned.runPrimitive();
            require(primitive.primitiveTargets().equals(targets) && learned.primitiveTargets().equals(targets),
                "full transport differs from scheduler: " + row.id());
            require(primitive.best() == optimum && learned.best() == optimum, "unbounded endpoint differs from reference");
            var result = cases.addObject().put("id", row.id()).put("positive", row.positive()).put("sourceDag", original)
                .put("optimumDag", optimum).put("uniquePrimitiveTargets", targets.size());
            putArm(result.putObject("primitive"), primitive, original, optimum, primitivePrefix);
            putArm(result.putObject("learned"), learned, original, optimum, learnedPrefix);
            long pHit = primitive.firstOptimum(original, optimum), lHit = learned.firstOptimum(original, optimum);
            var budgets = result.putArray("budgets");
            for (var budget : protocol.required("budgetPoints")) {
                long limit = budget.longValue();
                budgets.addObject().put("budget", limit)
                    .put("primitiveDag", at(primitive, original, limit)).put("warmLearnedDag", at(learned, original, limit))
                    .put("coldLearnedDag", at(learned, original, limit - setup))
                    .put("streamPrimitiveOptimum", limit >= primitivePrefix + pHit)
                    .put("streamLearnedOptimum", limit >= learnedPrefix + lHit);
            }
            primitivePrefix += primitive.work().total();
            learnedPrefix += learned.work().total();
        }
        return report.put("primitiveStreamWork", primitivePrefix).put("learnedStreamWorkIncludingAcquisition", learnedPrefix);
    }

    private static long at(ModPowSearchTrace trace, long original, long budget) {
        long incumbent = original;
        for (var improvement : trace.improvements()) {
            if (improvement.work() > budget) break;
            incumbent = improvement.dag();
        }
        return incumbent;
    }

    private static void putArm(ObjectNode node, ModPowSearchTrace trace, long source, long optimum, long prefix) {
        node.set("ledger", JSON.valueToTree(trace.work()));
        node.set("improvements", JSON.valueToTree(trace.improvements()));
        long first = trace.firstOptimum(source, optimum);
        node.put("firstOptimumWork", first).put("completedWork", trace.work().total())
            .put("streamFirstOptimumWork", prefix + first);
    }

    private static void add(ObjectNode node, String key, long value) { node.put(key, node.path(key).longValue() + value); }
    private static long countWork(JsonNode counts) {
        long total = 0;
        for (var value : counts) {
            require(value.isIntegralNumber() && value.canConvertToLong() && value.longValue() >= 0, "invalid count");
            total = Math.addExact(total, value.longValue());
        }
        return total;
    }
    private static byte[] bound(Path path, String digest) throws Exception {
        byte[] bytes = read(path);
        require(hash(bytes).equals(digest), "digest mismatch for " + path);
        return bytes;
    }
    private static void write(Path path, ObjectNode value) throws Exception {
        Files.write(path, JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(value), StandardOpenOption.CREATE_NEW);
    }
}
