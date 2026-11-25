package de.regelsuche.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.regelsuche.math.algorithms.combinatorics.FiniteConstraintCnf;
import de.regelsuche.math.algorithms.combinatorics.FiniteConstraintCounter;
import de.regelsuche.sdk.optimization.CancellationToken;
import de.regelsuche.sdk.optimization.OptimizationBudget;
import de.regelsuche.sdk.optimization.combinatorics.FiniteConstraintSdk;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Bounded web input and lossless JSON export for the public CNF SDK. */
final class CnfCountingWorkbench {
    private static final ObjectMapper JSON = new ObjectMapper();
    private CnfCountingWorkbench() {}

    record Request(FiniteConstraintCnf.Source source, OptimizationBudget budget) {
        FiniteConstraintCnf.Result count(CancellationToken token) {
            return FiniteConstraintSdk.countCnf(source, budget, token);
        }
    }

    static Request read(Map<String, Object> body) {
        if (!body.keySet().equals(Set.of("variableCount", "clauses", "fixed", "maximumWork")))
            throw new IllegalArgumentException("Variablenzahl, Klauseln, Fixwerte und Arbeitsbudget angeben.");
        int n = integer(body.get("variableCount"), 0, 256, "Variablenzahl");
        int work = integer(body.get("maximumWork"), 1, 2_000_000, "Arbeitsbudget");
        var fixed = new HashMap<Integer, Boolean>();
        for (Object item : list(body.get("fixed"), n, "Fixwerte")) {
            int value = literal(item, n);
            if (fixed.put(Math.abs(value) - 1, value > 0) != null)
                throw new IllegalArgumentException("Jede Variable nur einmal festlegen.");
        }
        var clauses = new ArrayList<List<Integer>>();
        int total = 0;
        for (Object clause : list(body.get("clauses"), 256, "Klauseln")) {
            var values = new ArrayList<Integer>();
            for (Object item : list(clause, 4096 - total, "Literale")) values.add(literal(item, n));
            clauses.add(values);
            total += values.size();
        }
        return new Request(new FiniteConstraintCnf.Source(n, clauses, fixed), new OptimizationBudget(work, 40_000, 1, 2_000));
    }

    private static int literal(Object item, int n) {
        int literal = integer(item, -n, n, "Literal");
        if (literal == 0) throw new IllegalArgumentException("Ein Literal darf nicht null sein.");
        return literal;
    }

    private static int integer(Object input, int min, int max, String field) {
        if (input instanceof Integer i && i >= min && i <= max) return i;
        throw new IllegalArgumentException(field + " muss eine ganze Zahl zwischen " + min + " und " + max + " sein.");
    }

    private static List<?> list(Object input, int max, String field) {
        if (input instanceof List<?> values && values.size() <= max) return values;
        throw new IllegalArgumentException(field + ": Liste erwartet oder Größenlimit überschritten.");
    }

    static String report(FiniteConstraintCnf.Result result) {
        var json = JSON.createObjectNode().put("schema", "regelsuche.cnf-count/v1")
            .put("status", result.status().name()).put("reason", result.reason()).put("work", result.work());
        result.count().ifPresent(n -> json.put("count", n.toString()));
        result.certificate().ifPresent(receipt -> {
            ObjectNode certificate = json.putObject("certificate");
            certificate.set("source", JSON.valueToTree(receipt.source()));
            var elimination = certificate.putObject("elimination");
            var proof = receipt.elimination();
            var problem = elimination.putObject("source").put("variableCount", proof.source().variableCount());
            problem.set("fixed", JSON.valueToTree(proof.source().fixed()));
            var factors = problem.putArray("factors");
            proof.source().factors().forEach(f -> writeFactor(factors.addObject(), f));
            var steps = elimination.putArray("steps");
            for (var step : proof.steps()) {
                var row = steps.addObject().put("variable", step.variable());
                row.set("inputs", JSON.valueToTree(step.inputs()));
                writeFactor(row.putObject("output"), step.output());
            }
            elimination.put("total", proof.total().toString());
            proof.witness().ifPresent(w -> elimination.set("witness", JSON.valueToTree(w)));
        });
        return json.toString();
    }

    private static void writeFactor(ObjectNode node, FiniteConstraintCounter.Factor factor) {
        node.set("variables", JSON.valueToTree(factor.variables()));
        var values = node.putArray("values");
        factor.values().forEach(weight -> values.add(weight.toString()));
    }
}
