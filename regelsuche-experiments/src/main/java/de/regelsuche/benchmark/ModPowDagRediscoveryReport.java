package de.regelsuche.benchmark;

import de.regelsuche.json.JsonWriter;
import de.regelsuche.search.program.CompiledAstReplayCodec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Writes v2 evidence without overwriting the retained historical v1 results. */
public final class ModPowDagRediscoveryReport {
    private static final CompiledAstReplayCodec CODEC = new CompiledAstReplayCodec();

    private ModPowDagRediscoveryReport() {
    }

    public static void main(String[] arguments) throws IOException {
        if (arguments.length != 1) {
            throw new IllegalArgumentException("expected output directory");
        }
        Path output = Path.of(arguments[0]);
        Files.createDirectories(output);
        var study = ModPowDagRediscoveryStudy.run();
        Files.writeString(output.resolve("modpow-dag-rediscovery-result-v2.json"),
            json(study), StandardCharsets.UTF_8);
        Files.writeString(output.resolve("modpow-dag-rediscovery-result-v2.md"),
            markdown(study), StandardCharsets.UTF_8);
        if (!study.green()) {
            throw new IllegalStateException("frozen modPow DAG rediscovery gate is not GREEN");
        }
    }

    static String json(ModPowDagRediscoveryStudy.StudyResult study) {
        var out = new JsonWriter().beginObject()
            .property("schema", "regelsuche.modpow-dag-rediscovery-result/v2")
            .property("revision", ModPowDagRediscoveryStudy.REVISION)
            .property("proofContract", "modpow-conditional-proof/v2")
            .stringArray("proofAssumptions", ModPowDagRediscoveryStudy.proofAssumptions())
            .property("workScope", "MECHANICAL_SEARCH_PLUS_SOURCE_TARGET_NODE_RECEIPTS;EXCLUDES_FULL_PROVIDER_TRAVERSAL_DOMAIN_CHECKS_CODEC_HASHING_ALLOCATION_AND_POSTHOC_SCORING")
            .property("costScope", "DECLARED_EXPONENT_BIT_PROXY;OTHER_OPERATIONS_ZERO;NOT_RUNTIME_OR_BIT_COMPLEXITY")
            .property("claimBoundary", "ONE_STEP_CLOSURE_WITH_TWO_GENERIC_FACTOR_ORDERS;FOUR_BIT_PROFILES_OF_ONE_AST;NO_LEARNED_TRANSFER")
            .property("green", study.green());
        appendCases(out, "formation", study.formation());
        appendCases(out, "test", study.test());
        out.object("negativeControl", writer -> appendCase(writer, study.negativeControl()));
        return out.endObject().toString() + "\n";
    }

    private static void appendCases(JsonWriter out, String name,
            List<ModPowDagRediscoveryStudy.CaseResult> cases) {
        out.array(name, writer -> cases.forEach(result ->
            writer.objectValue(item -> appendCase(item, result))));
    }

    private static void appendCase(JsonWriter out,
            ModPowDagRediscoveryStudy.CaseResult result) {
        out.property("id", result.id())
            .property("negativeControl", result.negativeControl())
            .property("outcome", result.outcome().name())
            .property("completeBoundedRelation", result.completeBoundedRelation())
            .property("reachedStates", result.reachedStates())
            .property("generatedSuccessors", result.generatedSuccessors())
            .property("totalSearchWork", result.totalSearchWork())
            .property("verificationWork", result.verificationWork())
            .property("originalTreeCost", result.originalCost().tree())
            .property("originalDagCost", result.originalCost().dag())
            .property("selectedTreeCost", result.selectedCost().tree())
            .property("selectedDagCost", result.selectedCost().dag())
            .property("dagImproved", result.dagImproved())
            .property("treeImproved", result.treeImproved())
            .property("expectedSharedResidue", result.expectedSharedResidue())
            .property("acceptedProofReceipts", result.acceptedProofReceipts())
            .property("sourceProgram", CODEC.encodeExpression(
                ModPowDagRediscoveryStudy.sourceProgram(result.negativeControl())))
            .property("selectedProgram", CODEC.encodeExpression(result.selectedProgram()));
    }

    private static String markdown(ModPowDagRediscoveryStudy.StudyResult study) {
        StringBuilder out = new StringBuilder();
        out.append("# Target-blind modular exponent DAG rediscovery — v2\n\n");
        out.append("Frozen verdict: **").append(study.green() ? "GREEN" : "NOT GREEN").append("**.\n\n");
        out.append("| case | original TREE | selected TREE | original DAG | selected DAG | complete | reuse |\n");
        out.append("|---|---:|---:|---:|---:|---|---|\n");
        for (var result : study.test()) {
            row(out, result);
        }
        row(out, study.negativeControl());
        out.append("\nTREE charges repeated calls repeatedly. DAG charges a structurally identical modpow call once.\n");
        out.append("The known factored target is not supplied to the search; selection happens after one-step bounded closure.\n");
        out.append("The JSON contains the actual canonical source/selected ASTs and normalized conditional proof assumptions.\n");
        out.append("Work counters are mechanical search/node-receipt units, not total computational cost; the JSON states the excluded work.\n");
        out.append("The four TEST entries share one AST and differ only in declared bit costs. This is not learned transfer or a new modular-exponent law.\n");
        out.append("Historical v1 result files remain unchanged.\n");
        return out.toString();
    }

    private static void row(StringBuilder out, ModPowDagRediscoveryStudy.CaseResult result) {
        out.append("| ").append(result.id().replace("|", "\\|").replace("\n", "\\n").replace("\r", "\\r"))
            .append(" | ").append(result.originalCost().tree())
            .append(" | ").append(result.selectedCost().tree())
            .append(" | ").append(result.originalCost().dag())
            .append(" | ").append(result.selectedCost().dag())
            .append(" | ").append(result.completeBoundedRelation())
            .append(" | ").append(result.expectedSharedResidue())
            .append(" |\n");
    }
}
