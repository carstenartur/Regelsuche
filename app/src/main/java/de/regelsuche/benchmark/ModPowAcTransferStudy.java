package de.regelsuche.benchmark;

import static de.regelsuche.benchmark.ModPowTransferFiles.JSON;
import static de.regelsuche.benchmark.ModPowTransferFiles.require;

import com.fasterxml.jackson.databind.node.ObjectNode;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.benchmark.ModPowTransferFiles.Row;
import de.regelsuche.mining.TypedOutputPattern;
import de.regelsuche.search.program.CompiledAstReplayCodec;
import de.regelsuche.transform.RecognitionProfile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/** Process-separated evaluation of the preregistered public transfer panel using the frozen v1 learner. */
public final class ModPowAcTransferStudy {
    private static final String PROTOCOL_SHA = "76f653520ccd573189f67b936cb7002e8a1d134eae8aefed23e434d561a8bc7f";
    private static final CompiledAstReplayCodec CODEC = new CompiledAstReplayCodec();

    private ModPowAcTransferStudy() {}

    public static void main(String[] args) throws IOException {
        require(args.length == 4, "usage: <protocol.json> <uncompressed-model.json> <test.json> <new-result.json>");
        ObjectNode result = evaluate(Path.of(args[0]), Path.of(args[1]), Path.of(args[2]));
        Files.write(Path.of(args[3]), JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(result),
            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    static ObjectNode evaluate(Path protocolFile, Path modelFile, Path corpusFile) throws IOException {
        var protocol = JSON.readTree(boundBytes(protocolFile, PROTOCOL_SHA, "protocol"));
        String modelSha = protocol.required("modelSha256").textValue();
        var model = JSON.readTree(boundBytes(modelFile, modelSha, "frozen model"));
        require(model.required("trainSha256").equals(protocol.required("trainSha256")), "training digest mismatch");
        var hypothesis = ModPowTransferFiles.candidate(model);
        var exact = new TypedOutputPattern(hypothesis, RecognitionProfile.exact());
        var ac = new TypedOutputPattern(hypothesis, RecognitionProfile.arithmeticAc());
        String corpusSha = protocol.required("testSha256").textValue();
        List<Row> cases = ModPowTransferFiles.rows(boundBytes(corpusFile, corpusSha, "TEST corpus"), "TEST");
        int maxAssignments = protocol.required("maxAssignments").intValue();

        var report = JSON.createObjectNode().put("schema", "regelsuche.modpow-ac-transfer-result/v2")
            .put("protocolSha256", PROTOCOL_SHA).put("modelSha256", modelSha).put("testSha256", corpusSha)
            .put("shuffledTrainWitnessCount", model.required("shuffledWitnessCount").intValue());
        var rows = report.putArray("cases");
        int positives = 0, exactTransfers = 0, acTransfers = 0;
        boolean controlsClean = true;
        for (Row row : cases) {
            var baseline = ModPowFrozenTransfer.baseline(row);
            require(baseline.approved().size() == 2, "incomplete primitive replay on " + row.id());
            Expr optimum = ModPowFrozenTransfer.best(row, baseline.approved());
            long optimumCost = ModPowFrozenTransfer.cost(row, optimum).dag();
            var result = rows.addObject().put("id", row.id()).put("positive", row.positive())
                .put("sourceAst", CODEC.encodeExpression(row.source())).put("sourceDag", ModPowFrozenTransfer.cost(row, row.source()).dag())
                .put("primitiveGenerated", baseline.generated()).put("primitiveApproved", baseline.approved().size())
                .put("primitiveOptimumDag", optimumCost).put("primitiveOptimumAst", CODEC.encodeExpression(optimum));
            result.set("premises", JSON.valueToTree(row.assumptions()));
            ObjectNode exactResult = profile(exact, row, maxAssignments, optimumCost);
            ObjectNode acResult = profile(ac, row, maxAssignments, optimumCost);
            result.set("exact", exactResult);
            result.set("arithmeticAc", acResult);
            if (row.positive()) {
                positives++;
                if (exactResult.get("qualifiedTransfer").booleanValue()) exactTransfers++;
                if (acResult.get("qualifiedTransfer").booleanValue()) acTransfers++;
            } else {
                controlsClean &= cleanNegative(exactResult) && cleanNegative(acResult);
            }
        }
        require(positives == protocol.required("positiveCases").intValue()
            && cases.size() - positives == protocol.required("negativeCases").intValue(), "protocol case counts changed");
        return report.put("positiveCases", positives).put("exactTransfers", exactTransfers)
            .put("arithmeticAcTransfers", acTransfers).put("negativeControlsClean", controlsClean)
            .put("arithmeticAcMeetsTransferCriterion", controlsClean && acTransfers == positives);
    }

    private static ObjectNode profile(TypedOutputPattern matcher, Row row, int maxAssignments, long optimum) {
        var measured = matcher.findWithMatchWork(row.source(), maxAssignments);
        var match = measured.result();
        List<Expr> valid = new ArrayList<>();
        var result = JSON.createObjectNode();
        var receipts = result.putArray("receipts");
        for (var proposal : match.applications()) {
            var proof = ModPowCompositionReplay.verify(row.source(), proposal.target(), row.assumptions());
            var receipt = receipts.addObject().put("targetAst", CODEC.encodeExpression(proposal.target()));
            receipt.set("positions", JSON.valueToTree(proposal.positions()));
            boolean preserved = proposal.source() == row.source()
                && preservesOtherOutputs(row.source(), proposal.target(), proposal.positions().getFirst());
            receipt.put("replayApproved", proof.isPresent()).put("otherOutputInstancesPreserved", preserved);
            proof.ifPresent(checked -> receipt.put("primitiveRule", checked.primitiveRule()));
            if (preserved && proof.isPresent()) valid.add(proposal.target());
        }
        Expr selected = ModPowFrozenTransfer.best(row, valid);
        long selectedCost = ModPowFrozenTransfer.cost(row, selected).dag();
        boolean qualified = match.complete() && !valid.isEmpty() && valid.size() == match.applications().size()
            && selectedCost == optimum && selectedCost < ModPowFrozenTransfer.cost(row, row.source()).dag();
        return result.put("assignmentAttempts", match.assignmentAttempts()).put("matcherSteps", match.matcherSteps())
            .put("structuralBranches", measured.patternBranches()).put("complete", match.complete())
            .put("proposed", match.applications().size()).put("verified", valid.size())
            .put("selectedAst", CODEC.encodeExpression(selected)).put("selectedDag", selectedCost)
            .put("qualifiedTransfer", qualified);
    }

    private static boolean cleanNegative(ObjectNode result) {
        return result.get("complete").booleanValue() && result.get("proposed").intValue() == 0;
    }

    private static boolean preservesOtherOutputs(FunctionExpr source, FunctionExpr target, int changed) {
        if (source.arguments().size() != target.arguments().size()) return false;
        for (int index = 0; index < source.arguments().size(); index++) {
            if (index != changed && source.arguments().get(index) != target.arguments().get(index)) return false;
        }
        return true;
    }

    private static byte[] boundBytes(Path path, String sha, String kind) throws IOException {
        byte[] bytes = ModPowTransferFiles.read(path);
        require(ModPowTransferFiles.hash(bytes).equals(sha), kind + " checksum mismatch");
        return bytes;
    }
}
