package de.regelsuche.evolution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.math.algorithms.equivalence.ExactPolynomialAnalysis;
import de.regelsuche.solver.ir.SolverIr;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Observer receives the trusted frozen candidate bundle before exposing historical references. */
public final class ResidualCorrespondence {
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private static final List<String> REFERENCES = List.of("(a*c-b*d)^2+(a*d+b*c)^2", "(a*c+b*d)^2+(b*c-a*d)^2");
    private ResidualCorrespondence() { }

    public static void main(String[] args) throws Exception { observe(Path.of(args[0]), args[1]); }

    static void observe(Path dir, String expectedHash) throws Exception {
        String frozen = Files.readString(dir.resolve("final.json"));
        if (!SolverIr.sha256(frozen).equals(expectedHash)) throw new IllegalArgumentException("candidate bundle hash differs");
        var c = JSON.readTree(frozen).required("cases").get(0);
        String source = c.required("source").textValue();
        var canonical = new ExpressionCanonicalizer();
        var exact = new ExactPolynomialAnalysis();
        var rows = new ArrayList<Map<String,Object>>();
        for (var arm : c.required("arms")) {
            var candidates = arm.required("candidates");
            var roots = new ArrayList<String>();
            candidates.forEach(n -> roots.add(n.required("root").textValue()));
            if (!arm.required("candidateFreeze").textValue().equals(SolverIr.sha256("residual-candidates/v1\n" + roots)))
                throw new IllegalArgumentException("candidate freeze differs");
            for (var reference : REFERENCES) {
                exact.requireEquivalent(source, reference);
                var matches = new ArrayList<String>();
                for (JsonNode candidate : candidates) {
                    String expression = candidate.required("expression").textValue();
                    exact.requireEquivalent(source, expression);
                    if (!candidate.required("primitiveRules").isEmpty()
                            && canonical.canonicalize(reference).equals(canonical.canonicalize(expression))) matches.add(candidate.required("root").textValue());
                }
                rows.add(Map.of("arm", arm.required("arm").textValue(), "reference", reference, "roots", matches));
            }
        }
        ResidualPlanComparison.save(dir, "correspondence.json", JSON.writerWithDefaultPrettyPrinter()
            .writeValueAsString(Map.of("finalFreeze", expectedHash, "source", source, "matches", rows)) + "\n");
    }
}
