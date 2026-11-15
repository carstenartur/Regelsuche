package de.regelsuche.math.algorithms.oracle;

import de.regelsuche.json.JsonReader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The frozen inputs and deterministic receipts shared by the two test drivers. */
public record StateChoiceOraclePanel(Path checkout, Map<?, ?> protocol) {
    private static final Path PROTOCOL = Path.of("config/benchmarks/state-choice-oracle-v1.json");
    private static final String DIGEST = "73a94ac912f9897b2c951a3c201a1241999960657554098e288708d9fe15fb7f";

    public static StateChoiceOraclePanel load() throws IOException {
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            var file = dir.resolve(PROTOCOL);
            if (Files.isRegularFile(file)) {
                return new StateChoiceOraclePanel(dir, read(file, DIGEST));
            }
        }
        throw new IOException("Frozen state-choice protocol not found");
    }

    public static Map<String, Object> read(Path file, String expectedSha256) throws IOException {
        var bytes = Files.readAllBytes(file);
        try {
            String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            assertEquals(expectedSha256, actual, file.toString());
        } catch (NoSuchAlgorithmException unavailable) {
            throw new AssertionError(unavailable);
        }
        return new JsonReader(new String(bytes, StandardCharsets.UTF_8)).readObject();
    }

    public void checkOrWrite(String name, String content) throws IOException {
        String output = System.getProperty("stateChoiceOracleOutput");
        if (output != null) {
            var directory = Path.of(output);
            Files.createDirectories(directory);
            Files.writeString(directory.resolve(name), content);
        } else {
            var published = checkout.resolve("docs/research/state-choice-oracle-v1").resolve(name);
            assertEquals(Files.readString(published), content, name);
        }
    }

    public static Map<?, ?> object(Object value) {
        return (Map<?, ?>) value;
    }

    public static List<?> array(Object value) {
        return (List<?>) value;
    }

    public static int number(Map<?, ?> object, String key) {
        return ((Number) object.get(key)).intValue();
    }

    public static void row(StringBuilder output, Object... cells) {
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) output.append('\t');
            output.append(cells[i]);
        }
        output.append('\n');
    }
}
