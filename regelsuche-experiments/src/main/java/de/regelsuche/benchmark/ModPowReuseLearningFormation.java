package de.regelsuche.benchmark;

import de.regelsuche.mining.GeneralizedPattern;
import de.regelsuche.mining.PatternGeneralizer;
import de.regelsuche.mining.SuccessfulTransformationPath;
import de.regelsuche.scoring.ExpressionScore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * TRAIN-only formation stage for #1026.
 *
 * <p>No held-out program appears in this class. The generated artifact is
 * intended to be content-hash frozen before a separate TEST evaluator is
 * introduced.</p>
 */
public final class ModPowReuseLearningFormation {
    public static final String SCHEMA =
        "regelsuche.modpow-reuse-learning-artifact/v1";

    private static final List<String> ASSUMPTIONS = List.of(
        "base integer",
        "modulus integer",
        "modulus > 0",
        "all exponents integer",
        "all exponents >= 0"
    );

    private ModPowReuseLearningFormation() {
    }

    public static void main(String[] arguments) throws IOException {
        if (arguments.length != 1) {
            throw new IllegalArgumentException("expected output directory");
        }
        Path output = Path.of(arguments[0]);
        Files.createDirectories(output);

        GeneralizedPattern pattern = new PatternGeneralizer()
            .generalize(trainingPaths())
            .orElseThrow(() -> new IllegalStateException(
                "TRAIN paths did not form a reusable pattern"));

        rejectConcreteTrainingLeakage(pattern);

        String canonical = canonicalContent(pattern);
        String digest = sha256(canonical);
        Files.writeString(
            output.resolve("learned-modpow-reuse-pattern.json"),
            json(pattern, digest),
            StandardCharsets.UTF_8);
        Files.writeString(
            output.resolve("learned-modpow-reuse-pattern.md"),
            markdown(pattern, digest),
            StandardCharsets.UTF_8);
    }

    static List<SuccessfulTransformationPath> trainingPaths() {
        return List.of(
            path("TRAIN_15", "modpow(a,15,n)",
                "modpow(modpow(a,3,n),5,n)"),
            path("TRAIN_28", "modpow(b,28,m)",
                "modpow(modpow(b,4,m),7,m)"),
            path("TRAIN_66", "modpow(c,66,k)",
                "modpow(modpow(c,6,k),11,k)")
        );
    }

    private static SuccessfulTransformationPath path(
        String id,
        String source,
        String target
    ) {
        return new SuccessfulTransformationPath(
            id,
            source,
            target,
            List.of(source, target),
            List.of("verified-modpow-composition"),
            new ExpressionScore(100, 0, 0, 0, 0),
            new ExpressionScore(90, 0, 0, 0, 0),
            true,
            "independent-modpow-composition-proof",
            Map.of(),
            ASSUMPTIONS
        );
    }

    private static void rejectConcreteTrainingLeakage(
        GeneralizedPattern pattern
    ) {
        String learned = pattern.leftPattern() + "\n"
            + pattern.rightPattern();
        for (String forbidden : List.of("15", "28", "66")) {
            if (learned.contains(forbidden)) {
                throw new IllegalStateException(
                    "learned artifact retains TRAIN literal: " + forbidden);
            }
        }
        if (pattern.parameterRelations().isEmpty()) {
            throw new IllegalStateException(
                "learned artifact has no parameter relation");
        }
    }

    static String canonicalContent(GeneralizedPattern pattern) {
        return String.join("\n",
            SCHEMA,
            pattern.leftPattern(),
            pattern.rightPattern(),
            String.join("|", pattern.parameterRelations()),
            String.join("|", ASSUMPTIONS));
    }

    private static String json(
        GeneralizedPattern pattern,
        String digest
    ) {
        StringBuilder out = new StringBuilder();
        out.append("{\n");
        field(out, "schema", SCHEMA).append(",\n");
        field(out, "leftPattern", pattern.leftPattern()).append(",\n");
        field(out, "rightPattern", pattern.rightPattern()).append(",\n");
        out.append("  \"parameterRelations\": ");
        stringArray(out, pattern.parameterRelations()).append(",\n");
        out.append("  \"assumptions\": ");
        stringArray(out, ASSUMPTIONS).append(",\n");
        out.append("  \"trainingIds\": ");
        stringArray(out, trainingPaths().stream()
            .map(SuccessfulTransformationPath::id).toList()).append(",\n");
        field(out, "contentSha256", digest).append("\n");
        out.append("}\n");
        return out.toString();
    }

    private static String markdown(
        GeneralizedPattern pattern,
        String digest
    ) {
        return "# TRAIN-only learned modular-power artifact\n\n"
            + "- left: " + pattern.leftPattern() + "\n"
            + "- right: " + pattern.rightPattern() + "\n"
            + "- relations: " + String.join("; ",
                pattern.parameterRelations()) + "\n"
            + "- content SHA-256: " + digest + "\n"
            + "- TRAIN ids: TRAIN_15, TRAIN_28, TRAIN_66\n\n"
            + "No held-out TEST program is consumed by the formation stage.\n";
    }

    private static StringBuilder field(
        StringBuilder out,
        String name,
        String value
    ) {
        return out.append("  \"").append(name).append("\": \"")
            .append(escape(value)).append('"');
    }

    private static StringBuilder stringArray(
        StringBuilder out,
        List<String> values
    ) {
        out.append('[');
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                out.append(',');
            }
            out.append('"').append(escape(values.get(index))).append('"');
        }
        return out.append(']');
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n");
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
