package de.regelsuche.solver.portfolio;

import de.regelsuche.solver.ir.SolverIr.Obligation;
import de.regelsuche.solver.ir.SolverIr.Theory;
import de.regelsuche.solver.ir.SolverIrJsonCodec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Portable generated SMT artifact bound to the same native IR used by the portfolio. */
public final class SmtProofArtifacts {
    public static final String ENVELOPE = "; regelsuche-obligation-base64: ";
    private SmtProofArtifacts() { }

    public static String artifact(Obligation obligation) {
        var material = new SmtLibRenderer().render(obligation);
        if (!material.issues().isEmpty()
                || obligation.theories().stream().anyMatch(t -> t != Theory.REAL_ARITHMETIC))
            throw new IllegalArgumentException("unsupported SMT translation: " + material.issues());
        return ENVELOPE + Base64.getEncoder().encodeToString(
            obligation.toCanonicalJson().getBytes(StandardCharsets.UTF_8)) + "\n"
            + "(set-option :produce-proofs true)\n" + material.scriptPrefix()
            + "(check-sat)\n(get-proof)\n(exit)\n";
    }

    public static Obligation readArtifact(String artifact) {
        if (artifact == null || artifact.length() > 2_000_000 || !artifact.startsWith(ENVELOPE))
            throw new IllegalArgumentException("expected generated typed SMT artifact");
        int end = artifact.indexOf('\n');
        if (end < 0) throw new IllegalArgumentException("incomplete SMT artifact");
        var request = new SolverIrJsonCodec().readObligation(new String(
            Base64.getDecoder().decode(artifact.substring(ENVELOPE.length(), end)), StandardCharsets.UTF_8));
        if (!artifact.equals(artifact(request))) throw new IllegalArgumentException("edited SMT artifact");
        return request;
    }
}
