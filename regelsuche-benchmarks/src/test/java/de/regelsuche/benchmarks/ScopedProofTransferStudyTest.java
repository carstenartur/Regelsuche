package de.regelsuche.benchmarks;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.regelsuche.solver.ir.*;
import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScopedProofTransferStudyTest {
    @Test void trainingFreezesBeforeEvaluationAndTamperingCannotPass(@TempDir Path dir) throws Exception {
        ScopedProofTransferStudy.train(dir);
        String frozen = Files.readString(dir.resolve("plan.json"));
        assertFalse(Files.exists(dir.resolve("validation.json")));
        assertFalse(Files.exists(dir.resolve("final.json")));
        assertTrue(frozen.contains("SUBTRACT_B_A"));
        assertThrows(IllegalArgumentException.class, () ->
            ScopedProofTransferStudy.evaluate("VALIDATION", dir, SolverIr.sha256("borrowed")));
        Files.writeString(dir.resolve("train.json"), "{}");
        assertThrows(IllegalArgumentException.class, () ->
            ScopedProofTransferStudy.evaluate("VALIDATION", dir, SolverIr.sha256(frozen)));
    }
    @Test void regeneratesTheFrozenEvidenceAndReplaysEveryAcceptedProof(@TempDir Path dir) throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve("docs/research/scoped-proof-transfer-v1/protocol.md")))
            root = root.getParent();
        assertNotNull(root, "repository research evidence");
        Path records = root.resolve("docs/research/scoped-proof-transfer-v1");
        ScopedProofTransferStudy.train(dir);
        String frozen = "sha256:d3adcb985e3a61f07f8a33c410893d76c7e9750bb41e8857da638b2ee6e053f7";
        assertEquals(frozen, SolverIr.sha256(Files.readString(dir.resolve("plan.json"))));
        ScopedProofTransferStudy.evaluate("VALIDATION", dir, frozen);
        ScopedProofTransferStudy.evaluate("FINAL", dir, frozen);
        for (String name : List.of("plan", "train", "validation", "final")) {
            Path expected = records.resolve(name + ".json");
            assertEquals(-1, Files.mismatch(expected, dir.resolve(name + ".json")), name);
            if (!name.equals("plan")) replay(new ObjectMapper().readTree(expected.toFile()).path("cases"));
        }
    }

    private static void replay(JsonNode cases) {
        var wire = new SolverIrJsonCodec();
        for (var c : cases) {
            var receiver = wire.readObligation(c.get("recipient").toString());
            var source = wire.readObligation(c.get("source").toString());
            for (var arm : c.get("arms")) for (var t : arm.get("trials")) {
                if (!t.get("accepted").asBoolean()) continue;
                String binder = c.get("binder").asText();
                if (!binder.isEmpty()) {
                    var statement = new ExistentialIntroduction.Statement(source, binder);
                    var witness = reconstructWitness(statement, t.get("proposal").asText());
                    assertEquals(new CoreExpressionIrAdapter().render(witness), t.get("term").asText());
                    var instance = statement.instantiate(witness);
                    var leaf = new ExistentialIntroduction.NormalForm(instance);
                    assertTrue(ExistentialIntroduction.replay(statement, new ExistentialIntroduction.Proof(statement, witness, leaf)));
                    assertEquals(receiver, statement.toScopedObligation());
                    assertEquals(instance.contentHash(), t.get("instanceHash").asText());
                    assertEquals(SolverIr.sha256("witness/v1\n" + receiver.contentHash() + "\n" + witness.canonicalMaterial()
                        + "\nnormal-form=" + instance.contentHash()), t.get("proofRoot").asText());
                } else {
                    var plan = NaturalInduction.discover(source).stream().filter(p -> p.predicate().equals(t.get("proposal").asText())).findFirst().orElseThrow();
                    assertTrue(NaturalInduction.matches(receiver, plan));
                    assertEquals(plan.base().id(), t.get("baseId").asText());
                    assertEquals(plan.step().id(), t.get("stepId").asText());
                    assertEquals(SolverIr.sha256("induction/v1\n" + receiver.contentHash() + "\n" + plan.predicate()
                        + "\n" + plan.base().id() + "\n" + plan.step().id()), t.get("proofRoot").asText());
                }
            }
        }
    }
    // Preserve the original tree: the human-readable term formatter can omit associative parentheses.
    private static SolverIr.Expression reconstructWitness(ExistentialIntroduction.Statement s, String proposal) {
        if (proposal.equals("ZERO")) return new SolverIr.Literal("0");
        var goal = s.body().goal();
        boolean left = goal.left().referencedSymbols().contains(s.binder());
        var operator = (SolverIr.Binary)(left ? goal.left() : goal.right());
        var b = left ? goal.right() : goal.left();
        var a = operator.left().equals(new SolverIr.Symbol(s.binder())) ? operator.right() : operator.left();
        return switch (proposal) {
            case "B" -> b;
            case "ADD" -> new SolverIr.Binary(SolverIr.BinaryOperator.ADD, b, a);
            case "SUBTRACT_B_A" -> new SolverIr.Binary(SolverIr.BinaryOperator.SUBTRACT, b, a);
            case "SUBTRACT_A_B" -> new SolverIr.Binary(SolverIr.BinaryOperator.SUBTRACT, a, b);
            case "MULTIPLY" -> new SolverIr.Binary(SolverIr.BinaryOperator.MULTIPLY, b, a);
            default -> throw new AssertionError("no checked selector for " + proposal);
        };
    }
}
