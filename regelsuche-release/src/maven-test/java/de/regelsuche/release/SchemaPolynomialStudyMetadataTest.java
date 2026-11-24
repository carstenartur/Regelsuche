package de.regelsuche.release;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import org.junit.jupiter.api.Test;

class SchemaPolynomialStudyMetadataTest {
    @Test void requiresEverySourceAndOriginalFreezeBinding() throws Exception {
        var json = QualityAcceptanceCollection.JSON;
        var source = json.createObjectNode().put("revision", "a".repeat(40)).put("tree", "b".repeat(40));
        var metadata = json.createObjectNode().put("revision", "a".repeat(40)).put("sourceRevision", "a".repeat(40))
            .put("sourceTreeHash", "git:" + "b".repeat(40)).put("freezeCommit", PolynomialSourceProof.SCHEMA)
            .put("freezeProtocolHash", PolynomialSourceProof.SCHEMA_HASH).put("protocolHash", PolynomialSourceProof.SCHEMA_HASH)
            .put("sourceLocalFreezeCommit", "5bc9e21e61131be287b89060ef39f93df111dc25");
        assertDoesNotThrow(() -> FrozenPolynomialStudyContainer.verifySchemaMetadata(metadata, source));
        for (var field : metadata.properties()) {
            var changed = metadata.deepCopy().put(field.getKey(), "unrelated");
            assertThrows(IOException.class, () -> FrozenPolynomialStudyContainer.verifySchemaMetadata(changed, source), field.getKey());
            var missing = metadata.deepCopy();
            missing.remove(field.getKey());
            assertThrows(IOException.class, () -> FrozenPolynomialStudyContainer.verifySchemaMetadata(missing, source), field.getKey());
        }
    }
}
