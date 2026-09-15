package de.regelsuche.scoring;

import de.regelsuche.json.JsonReader;

/** Identity of score semantics, independent of mathematical value or proof authority. */
public final class ScoreRevision {
    /** Includes token-aware quadratic recognition and identity-independent scoped-symbol costs. */
    public static final String CURRENT = "regelsuche.expression-score/v2";
    public static final String UNSPECIFIED = "unspecified";

    private ScoreRevision() { }

    public static String normalize(String revision) {
        if (revision == null) return UNSPECIFIED;
        if (revision.isBlank()) throw new IllegalArgumentException("scoringRevision must not be blank");
        return revision;
    }

    public static void requireCurrent(String revision) {
        if (!CURRENT.equals(revision)) {
            throw new IllegalArgumentException("incompatible scoring revision: " + normalize(revision)
                + "; regenerate scores from their original source with " + CURRENT);
        }
    }

    /** Validate metadata before executing independently supplied replay sources. */
    public static void requireReplay(String json, String schema, String expectedRevision) {
        var header = new JsonReader(json).readObject();
        if (!schema.equals(header.get("schema"))) {
            throw new IllegalArgumentException("unsupported scoring replay schema: " + header.get("schema"));
        }
        if (UNSPECIFIED.equals(normalize(expectedRevision)) || !expectedRevision.equals(header.get("scoringRevision"))) {
            throw new IllegalArgumentException("incompatible scoring revision in replay: " + header.get("scoringRevision"));
        }
    }
}
