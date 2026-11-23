package de.regelsuche.discovery.representation;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import tools.jackson.databind.json.JsonMapper;

/** Checks the exchange contract over the original canonical companion bytes. */
final class ReferenceIndependentValidationSchema {
    private static final JsonMapper JSON = new JsonMapper();
    private static final String DIALECT = "https://json-schema.org/draft/2020-12/schema";
    private static final Path SCHEMA_PATH = Path.of("docs", "schemas",
        "regelsuche-reference-independent-candidate-validation-v1.schema.json");
    static final Schema CONTRACT = load();

    private ReferenceIndependentValidationSchema() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            throw new IllegalArgumentException("Expected the companion JSON path");
        }
        System.out.println("schemaVerifiedCompanionHash=" + verify(Files.readString(Path.of(args[0]))));
    }

    static String verify(String canonical) {
        var artifact = ReferenceIndependentCandidateValidation.Artifact.fromCanonicalJson(canonical);
        var violations = CONTRACT.validate(JSON.readTree(canonical));
        if (!violations.isEmpty()) {
            throw new IllegalArgumentException("Companion schema violation: " + violations.getFirst());
        }
        return artifact.contentHash();
    }

    private static Schema load() {
        Path schemaPath = Path.of(System.getProperty("regelsuche.repositoryRoot", "..")).resolve(SCHEMA_PATH);
        try {
            var source = JSON.readTree(Files.readString(schemaPath));
            var registry = SchemaRegistry.withDefaultDialect(
                SpecificationVersion.DRAFT_2020_12, builder -> builder
                    .schemaLoader(loader -> loader.fetchRemoteResources(false))
                    .schemaRegistryConfig(SchemaRegistryConfig.builder()
                        .typeLoose(false).formatAssertionsEnabled(false).build()));
            if (!DIALECT.equals(source.path("$schema").asString())
                    || !registry.getSchema(SchemaLocation.of(DIALECT)).validate(source).isEmpty()) {
                throw new IllegalArgumentException("Invalid companion Draft 2020-12 schema: " + schemaPath);
            }
            return registry.getSchema(
                SchemaLocation.of("https://regelsuche.invalid/reference-independent-v1/"), source);
        } catch (IOException failure) {
            throw new UncheckedIOException("Cannot read the companion schema: " + schemaPath, failure);
        }
    }
}
