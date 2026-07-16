package de.regelsuche.solver.ir;

import de.regelsuche.json.JsonWriter;
import de.regelsuche.solver.ir.SolverIr.Obligation;
import de.regelsuche.solver.ir.SolverIr.SolverResult;
import java.util.Objects;

/** One atomic backend execution linking obligation, translation and result. */
public record SolverExecution(
    String schema,
    String obligationHash,
    SolverTranslation translation,
    SolverResult result,
    String contentHash
) {
    public static final String SCHEMA = "regelsuche.solver-execution/v1";

    public SolverExecution {
        if (!SCHEMA.equals(schema)) {
            throw new IllegalArgumentException("unsupported solver execution schema");
        }
        requireSha(obligationHash, "obligationHash");
        Objects.requireNonNull(translation, "translation");
        Objects.requireNonNull(result, "result");
        if (!obligationHash.equals(translation.obligationHash())
                || !obligationHash.equals(result.obligationHash())) {
            throw new IllegalArgumentException(
                "translation and result must reference the same obligation");
        }
        if (!translation.backendId().equals(result.backendId())
                || !translation.backendVersion().equals(result.backendVersion())) {
            throw new IllegalArgumentException(
                "translation and result must reference the same backend revision");
        }
        if (translation.status() != result.translationStatus()
                || !translation.issues().equals(result.translationIssues())) {
            throw new IllegalArgumentException(
                "result translation summary differs from translation artifact");
        }
        requireSha(contentHash, "contentHash");
        String expected = hash(obligationHash, translation, result);
        if (!expected.equals(contentHash)) {
            throw new IllegalArgumentException(
                "solver execution hash does not match canonical fields");
        }
    }

    public static SolverExecution create(
        Obligation obligation,
        SolverTranslation translation,
        SolverResult result
    ) {
        Objects.requireNonNull(obligation, "obligation");
        Objects.requireNonNull(translation, "translation");
        Objects.requireNonNull(result, "result");
        return new SolverExecution(
            SCHEMA,
            obligation.contentHash(),
            translation,
            result,
            hash(obligation.contentHash(), translation, result));
    }

    public String toCanonicalJson() {
        return new JsonWriter().beginObject()
            .property("schema", schema)
            .property("obligationHash", obligationHash)
            .property("translationHash", translation.contentHash())
            .property("resultHash", result.contentHash())
            .property("backendId", result.backendId())
            .property("backendVersion", result.backendVersion())
            .property("translationStatus", translation.status().name())
            .property("resultStatus", result.status().name())
            .property("contentHash", contentHash)
            .endObject()
            .toString();
    }

    private static String hash(
        String obligationHash,
        SolverTranslation translation,
        SolverResult result
    ) {
        return SolverIr.sha256(
            SCHEMA
                + "\nobligation=" + obligationHash
                + "\ntranslation=" + translation.contentHash()
                + "\nresult=" + result.contentHash());
    }

    private static void requireSha(String value, String name) {
        if (value == null || !value.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be SHA-256");
        }
    }
}
