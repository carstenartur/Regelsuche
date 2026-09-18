package de.regelsuche.benchmark;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Writes the deterministic #1024 study result; exits non-zero when the frozen GREEN gate fails. */
public final class ModPowDagRediscoveryReport {
    private ModPowDagRediscoveryReport() {
    }

    public static void main(String[] arguments) throws IOException {
        if (arguments.length != 1) {
            throw new IllegalArgumentException("expected output directory");
        }
        Path output = Path.of(arguments[0]);
        Files.createDirectories(output);
        var study = ModPowDagRediscoveryStudy.run();
        Files.writeString(output.resolve("modpow-dag-rediscovery-result.json"),
            json(study), StandardCharsets.UTF_8);
        Files.writeString(output.resolve("modpow-dag-rediscovery-result.md"),
            markdown(study), StandardCharsets.UTF_8);
        if (!study.green()) {
            throw new IllegalStateException("frozen modPow DAG rediscovery gate is not GREEN");
        }
    }

    static String json(ModPowDagRediscoveryStudy.StudyResult study) {
        StringBuilder out = new StringBuilder();
        out.append("{\n");
        out.append("  \"schema\": \"regelsuche.modpow-dag-rediscovery-result/v1\",\n");
        out.append("  \"revision\": \"").append(ModPowDagRediscoveryStudy.REVISION).append("\",\n");
        out.append("  \"green\": ").append(study.green()).append(",\n");
        appendCases(out, "formation", study.formation(), true);
        appendCases(out, "test", study.test(), true);
        out.append("  \"negativeControl\": ");
        appendCase(out, study.negativeControl());
        out.append("\n}\n");
        return out.toString();
    }

    private static void appendCases(StringBuilder out, String name,
            List<ModPowDagRediscoveryStudy.CaseResult> cases, boolean comma) {
        out.append("  \"").append(name).append("\": [\n");
        for (int index = 0; index < cases.size(); index++) {
            out.append("    ");
            appendCase(out, cases.get(index));
            if (index + 1 < cases.size()) {
                out.append(',');
            }
            out.append('\n');
        }
        out.append("  ]");
        if (comma) {
            out.append(',');
        }
        out.append('\n');
    }

    private static void appendCase(StringBuilder out,
            ModPowDagRediscoveryStudy.CaseResult result) {
        out.append("{");
        field(out, "id", result.id()).append(',');
        field(out, "negativeControl", result.negativeControl()).append(',');
        field(out, "outcome", result.outcome().name()).append(',');
        field(out, "completeBoundedRelation", result.completeBoundedRelation()).append(',');
        field(out, "reachedStates", result.reachedStates()).append(',');
        field(out, "generatedSuccessors", result.generatedSuccessors()).append(',');
        field(out, "totalSearchWork", result.totalSearchWork()).append(',');
        field(out, "verificationWork", result.verificationWork()).append(',');
        field(out, "originalTreeCost", result.originalCost().tree()).append(',');
        field(out, "originalDagCost", result.originalCost().dag()).append(',');
        field(out, "selectedTreeCost", result.selectedCost().tree()).append(',');
        field(out, "selectedDagCost", result.selectedCost().dag()).append(',');
        field(out, "dagImproved", result.dagImproved()).append(',');
        field(out, "treeImproved", result.treeImproved()).append(',');
        field(out, "expectedSharedResidue", result.expectedSharedResidue()).append(',');
        field(out, "acceptedProofReceipts", result.acceptedProofReceipts());
        out.append("}");
    }

    private static StringBuilder field(StringBuilder out, String name, String value) {
        return out.append('\"').append(name).append("\": \"").append(value).append('\"');
    }

    private static StringBuilder field(StringBuilder out, String name, boolean value) {
        return out.append('\"').append(name).append("\": ").append(value);
    }

    private static StringBuilder field(StringBuilder out, String name, long value) {
        return out.append('\"').append(name).append("\": ").append(value);
    }

    private static String markdown(ModPowDagRediscoveryStudy.StudyResult study) {
        StringBuilder out = new StringBuilder();
        out.append("# Target-blind modular exponent DAG rediscovery\n\n");
        out.append("Frozen verdict: **").append(study.green() ? "GREEN" : "NOT GREEN").append("**.\n\n");
        out.append("| case | original TREE | selected TREE | original DAG | selected DAG | complete | reuse |\n");
        out.append("|---|---:|---:|---:|---:|---|---|\n");
        for (var result : study.test()) {
            row(out, result);
        }
        row(out, study.negativeControl());
        out.append("\nTREE is the control that charges repeated calls repeatedly. DAG charges a structurally identical modpow call once.\n");
        out.append("The known factored Pocklington target is not supplied to the search; selection happens after bounded closure.\n");
        return out.toString();
    }

    private static void row(StringBuilder out, ModPowDagRediscoveryStudy.CaseResult result) {
        out.append("| ").append(result.id())
            .append(" | ").append(result.originalCost().tree())
            .append(" | ").append(result.selectedCost().tree())
            .append(" | ").append(result.originalCost().dag())
            .append(" | ").append(result.selectedCost().dag())
            .append(" | ").append(result.completeBoundedRelation())
            .append(" | ").append(result.expectedSharedResidue())
            .append(" |\n");
    }
}
