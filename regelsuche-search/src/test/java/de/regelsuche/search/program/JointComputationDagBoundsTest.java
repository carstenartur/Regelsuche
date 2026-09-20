package de.regelsuche.search.program;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.ast.*;
import java.io.File;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class JointComputationDagBoundsTest {
    @Test void namedSharedDagsCannotTriggerRecursiveRecordHashing() throws Exception {
        String classpath = List.of(JointComputationDagProbe.class, JointComputationPlan.class, Expr.class).stream()
            .map(type -> {
                try { return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(); }
                catch (Exception error) { throw new IllegalStateException(error); }
            }).distinct().collect(Collectors.joining(File.pathSeparator));
        var child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp", classpath, JointComputationDagProbe.class.getName()).redirectErrorStream(true).start();
        try {
            boolean completed = child.waitFor(5, TimeUnit.SECONDS);
            if (!completed) child.destroyForcibly().waitFor();
            assertTrue(completed, "48-node shared DAG exceeded child deadline; recursive AST hashing can do exponential work");
            assertEquals(0, child.exitValue(), new String(child.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        } finally { if (child.isAlive()) child.destroyForcibly().waitFor(); }
    }

    @Test void directSharedAstIdentitiesAreResolvedOnce() {
        Expr expression = new VariableExpr("x");
        for (int i = 0; i < 48; i++) expression = new FunctionExpr("add", List.of(expression, expression));
        var plan = JointComputationDagProbe.plan(Map.of(), expression, expression);
        var prepared = assertDoesNotThrow(() -> plan.prepare(JointComputationDagProbe.BACKEND));
        assertEquals(48, prepared.cost().operationCount());
        assertEquals(BigInteger.ONE.shiftLeft(48), prepared.execute(Map.of("x", BigInteger.ONE)).get("first"));
        assertTrue(prepared.cost().inspectionWork() < 3_000, "counted work must scale with DAG nodes and edges");
        var envelope = new FunctionExpr(JointComputationPlan.OUTPUTS, List.of(
            new FunctionExpr(JointComputationPlan.OUTPUT, List.of(new VariableExpr("first"), expression)),
            new FunctionExpr(JointComputationPlan.OUTPUT, List.of(new VariableExpr("second"), expression))));
        var restored = plan.withExpression(envelope);
        assertEquals(48, restored.prepare(JointComputationDagProbe.BACKEND).cost().operationCount());
        assertThrows(IllegalArgumentException.class, restored::searchExpression);
    }

    @Test void frontierEnvelopeRejectsOversizedTreeExpansionBeforeTheCodecRuns() {
        var plan = JointComputationDagProbe.namedPlan(48);
        assertThrows(IllegalArgumentException.class, plan::searchExpression);
        assertDoesNotThrow(() -> plan.prepare(JointComputationDagProbe.BACKEND), "preparation itself supports the compact DAG");
    }

    @Test void excessiveDepthIsRejectedBeforePreparation() {
        Expr expression = new VariableExpr("x");
        for (int i = 0; i <= JointComputationPlan.MAX_DEPTH; i++) expression = new FunctionExpr("add", List.of(expression, new VariableExpr("x")));
        var plan = JointComputationDagProbe.plan(Map.of(), expression, new VariableExpr("x"));
        assertThrows(IllegalArgumentException.class, () -> plan.prepare(JointComputationDagProbe.BACKEND));
    }
}
