package de.regelsuche.math.sympy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Immutable access to the versioned Python adapter source. */
final class SymPyScript {
    static final String PROTOCOL =
        "regelsuche.sympy-factorization/v1";
    static final String RESOURCE_DIRECTORY =
        "GRAALPY-VFS/de.regelsuche/regelsuche-math-sympy";
    static final String RESOURCE_PATH = RESOURCE_DIRECTORY
        + "/src/regelsuche_sympy_factor.py";

    private static final byte[] SOURCE_BYTES = load();
    private static final String SOURCE =
        new String(SOURCE_BYTES, StandardCharsets.UTF_8);
    private static final String SOURCE_HASH =
        SymPyEvidence.sha256(SOURCE_BYTES);

    private SymPyScript() {
    }

    static String source() {
        return SOURCE;
    }

    static String sourceHash() {
        return SOURCE_HASH;
    }

    static String processProgram() {
        return SOURCE
            + "\nimport sys\n"
            + "sys.stdout.write(factor_payload(sys.stdin.read()))\n";
    }

    private static byte[] load() {
        ClassLoader loader = SymPyScript.class.getClassLoader();
        try (InputStream input = loader.getResourceAsStream(RESOURCE_PATH)) {
            if (input == null) {
                throw new IllegalStateException(
                    "embedded SymPy adapter source is missing: "
                        + RESOURCE_PATH);
            }
            return input.readAllBytes();
        } catch (IOException exception) {
            throw new IllegalStateException(
                "embedded SymPy adapter source cannot be read",
                exception);
        }
    }
}
