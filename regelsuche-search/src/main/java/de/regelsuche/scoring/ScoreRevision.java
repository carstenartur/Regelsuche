package de.regelsuche.scoring;

import de.regelsuche.json.JsonReader;
import java.util.List;
import java.util.Map;

/** Score semantics are metadata, never mathematical equivalence or proof authority. */
public final class ScoreRevision {
    /** Token-aware quadratic recognition and identity-independent scoped-symbol costs. */
    public static final String CURRENT = "regelsuche.expression-score/v2";
    public static final String UNSPECIFIED = "unspecified";

    private ScoreRevision() { }

    public static String normalize(String revision) {
        return revision == null || revision.isBlank() ? UNSPECIFIED : revision;
    }

    public static void requireCurrent(String revision) {
        if (!CURRENT.equals(revision)) {
            throw new IllegalArgumentException("incompatible scoring revision: " + normalize(revision)
                + "; regenerate scores from their original source with " + CURRENT);
        }
    }

    /** Check the contract before invoking a caller-provided replay/source supplier. */
    public static void requireReplay(String json, String schema, String expectedRevision) {
        Map<String, Object> header = new JsonReader(json).readObject();
        if (!schema.equals(header.get("schema"))) {
            throw new IllegalArgumentException("unsupported scoring replay schema: " + header.get("schema"));
        }
        if (UNSPECIFIED.equals(normalize(expectedRevision))
                || !expectedRevision.equals(header.get("scoringRevision"))) {
            throw new IllegalArgumentException("incompatible scoring revision in replay: " + header.get("scoringRevision"));
        }
        requireNestedScores(header, expectedRevision);
    }

    private static void requireNestedScores(Object value, String expectedRevision) {
        if (value instanceof Map<?, ?> map) {
            if (map.get("score") instanceof Map<?, ?> score
                    && !expectedRevision.equals(score.get("scoringRevision"))) {
                throw new IllegalArgumentException("incompatible scoring revision in replay score");
            }
            map.values().forEach(child -> requireNestedScores(child, expectedRevision));
        } else if (value instanceof List<?> list) {
            list.forEach(child -> requireNestedScores(child, expectedRevision));
        }
    }
}
