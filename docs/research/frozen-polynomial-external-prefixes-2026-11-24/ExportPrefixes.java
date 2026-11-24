package de.regelsuche.math.sympy;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.regelsuche.benchmark.polynomial.PolynomialTheoryUtilityExecutionPlan;
import de.regelsuche.benchmark.polynomial.PolynomialTheoryUtilityExternalAdmission;

final class ExportPrefixes {
    public static void main(String[] args) throws Exception {
        String frozenHash = "sha256:6cfac16d65611820b713cf1f2aca0fdb724fc542ccef6b9de80981dc290af619";
        if (!frozenHash.equals(PolynomialTheoryUtilityExecutionPlan.freeze().contentHash())) {
            throw new IllegalArgumentException("execution plan differs from the original frozen bytes");
        }
        var json = new ObjectMapper();
        var codec = SymPyFactorizationCodec.rationals();
        for (var prefix : PolynomialTheoryUtilityExternalAdmission.inspect()) {
            var input = prefix.input();
            var row = json.createObjectNode();
            row.put("inputId", input.inputId()).put("case", input.caseId()).put("checkpoint", input.checkpointId());
            row.put("primitiveLimit", input.admittedPrimitiveWork()).put("mechanicalLimit", input.totalMechanicalWork())
                .put("factorizationLimit", input.factorizationWork());
            row.put("prefixWork", prefix.admittedWork().totalWorkUnits());
            row.set("prefixStatuses", json.valueToTree(prefix.completed().stream().map(value -> value.status()).toList()));
            row.set("path", json.valueToTree(prefix.pendingPath()));
            if (prefix.firstRequest().isPresent()) {
                var request = prefix.firstRequest().orElseThrow();
                row.put("requestLimit", request.maxWorkUnits());
                row.put("bridgePayload", codec.encode(request).payload());
            }
            System.out.println(json.writeValueAsString(row));
        }
    }
}
