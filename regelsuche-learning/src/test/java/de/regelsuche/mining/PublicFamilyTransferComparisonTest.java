package de.regelsuche.mining;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PublicFamilyTransferComparisonTest {
    @TempDir Path scratch;

    @Test void replaysThePublicProtocolWithoutEvaluationInputsDuringFormation() throws Exception {
        Path project = Path.of("").toAbsolutePath();
        if (!Files.isDirectory(project.resolve("research"))) project = project.getParent();
        var input = project.resolve("research/benchmarks/track-d-public-transfer");
        var isolated = Files.createDirectory(scratch.resolve("inputs"));
        for (String name : new String[]{"formation.json", "posthoc-family-labels.json"}) {
            Files.copy(input.resolve(name), isolated.resolve(name));
        }
        var formation = scratch.resolve("formation-result.json");
        PublicFamilyTransferComparison.main(new String[]{"form", isolated.toString(), formation.toString()});
        assertArrayEquals(Files.readAllBytes(input.resolve("formation-result.json")), Files.readAllBytes(formation));

        var evaluation = scratch.resolve("transfer-result.json");
        Files.delete(isolated.resolve("formation.json"));
        Files.delete(isolated.resolve("posthoc-family-labels.json"));
        Files.copy(input.resolve("evaluation.json"), isolated.resolve("evaluation.json"));
        PublicFamilyTransferComparison.main(new String[]{"evaluate", isolated.toString(), evaluation.toString(), formation.toString()});
        assertArrayEquals(Files.readAllBytes(input.resolve("transfer-result.json")), Files.readAllBytes(evaluation));
        var altered = new JsonMapper().createObjectNode().put("registration", "substituted");
        assertThrows(IllegalArgumentException.class, () -> PublicFamilyTransferComparison.evaluate(altered, isolated));
        Files.writeString(isolated.resolve("evaluation.json"), "[]");
        JsonNode fixed = new JsonMapper().readTree(formation.toFile());
        assertThrows(IllegalArgumentException.class, () -> PublicFamilyTransferComparison.evaluate(fixed, isolated));
        Files.writeString(isolated.resolve("formation.json"), "[]");
        assertThrows(IllegalArgumentException.class, () -> PublicFamilyTransferComparison.form(isolated));
    }
}
