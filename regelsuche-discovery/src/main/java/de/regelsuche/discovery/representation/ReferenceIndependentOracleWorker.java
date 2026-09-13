package de.regelsuche.discovery.representation;

import static de.regelsuche.discovery.representation.ReferenceIndependentCandidateValidation.JSON;
import static de.regelsuche.discovery.representation.ReferenceIndependentCandidateValidation.hash;

import de.regelsuche.validation.OracleValidator.OracleValidation;
import de.regelsuche.validation.SymPyOracleValidator;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Isolated transport for the existing numeric/quadratic oracle, not external SymPy. */
public final class ReferenceIndependentOracleWorker {
    private ReferenceIndependentOracleWorker() {
    }

    public static void main(String[] args) throws Exception {
        var oracle = new SymPyOracleValidator();
        var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        System.out.println(ReferenceIndependentOracleSession.READY);
        while (true) {
            String line;
            try {
                line = ReferenceIndependentOracleSession.readBoundedLine(input);
            } catch (java.io.IOException exception) {
                return;
            }
            var request = JSON.readValue(line, ReferenceIndependentOracleSession.Request.class);
            if (request.source() == null || request.candidate() == null
                    || (long) request.source().length() + request.candidate().length() > 8192
                    || !hash(List.of(request.source(), request.candidate())).equals(request.requestHash())) {
                throw new IllegalArgumentException("invalid bounded oracle request");
            }
            OracleValidation validation = oracle.validateEquivalence(request.source(), request.candidate());
            if (validation.evidence().equals("no equivalence evidence found")) {
                // The legacy boolean service uses false for unsupported as well as refuted.
                validation = OracleValidation.unavailable(validation.evidence());
            }
            System.out.println(JSON.writeValueAsString(new ReferenceIndependentOracleSession.Response(
                request.requestHash(), validation.status(), validation.evidence())));
        }
    }
}
