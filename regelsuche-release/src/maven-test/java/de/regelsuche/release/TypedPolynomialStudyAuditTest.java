package de.regelsuche.release;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TypedPolynomialStudyAuditTest {
    @Test void requiresAnAuditBoundToAllRowsAndTheExactSource(@TempDir Path directory) throws Exception {
        var audit = directory.resolve("audit.stdout");
        String revision = "0123456789abcdef0123456789abcdef01234567";
        String valid = """
            {"schema":"regelsuche.typed-selected-path-audit/v1",
             "revision":"%s","rows":729,"profiles":{}}
            """.formatted(revision);
        assertThrows(IOException.class, () -> FrozenPolynomialStudyContainer.verifyTypedAudit(audit, revision));
        Files.writeString(audit, valid);
        assertDoesNotThrow(() -> FrozenPolynomialStudyContainer.verifyTypedAudit(audit, revision));
        for (String invalid : new String[]{valid.replace(revision, "unrelated"),
                valid.replace("729", "728"), valid.replace("729", "729.1"),
                valid.replace("typed-selected-path-audit", "some-other-audit")}) {
            Files.writeString(audit, invalid);
            assertThrows(IOException.class, () -> FrozenPolynomialStudyContainer.verifyTypedAudit(audit, revision));
        }
    }
}
