package de.regelsuche.release;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.*;
import de.regelsuche.discovery.domain.*;
import de.regelsuche.discovery.domain.DiscoveryDomain.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Ordinary schema controls for public finite examples; no release qualification or campaign execution. */
class DomainDownstreamValidationSchemaTest {
    @TempDir Path directory;
    private final JsonMapper json = new JsonMapper();

    @Test void actualReceiptsFromBothDomainsAndPartialChecksSatisfyTheOfflineSchemas() throws Exception {
        var registry = registry();
        var schema = registry.getSchema(SchemaLocation.of("urn:regelsuche:domain-downstream-validation:v1"));
        for (boolean recurrence : new boolean[]{false,true}) for (boolean refuted : new boolean[]{false,true}) {
            var workspace = workspace(recurrence,refuted);
            for (var budget : List.of(DomainDownstreamValidation.Budget.defaults(),
                    new DomainDownstreamValidation.Budget(1,512,new DomainFiniteDataCheck.Budget(2,1000,1024)))) {
                var receipt = json.readTree(DomainDownstreamValidation.validate(workspace,budget).toCanonicalJson());
                assertTrue(schema.validate(receipt).isEmpty(),() -> schema.validate(receipt).toString());
            }
        }
    }

    @Test void schemaRejectsFalseCompletenessMissingBindingAndContradictoryProofRoles() throws Exception {
        var schema = registry().getSchema(SchemaLocation.of("urn:regelsuche:domain-downstream-validation:v1"));
        var positive = (ObjectNode)json.readTree(DomainDownstreamValidation.validate(workspace(false,false)).toCanonicalJson());
        for (int mutation = 0; mutation < 5; mutation++) {
            var bad = positive.deepCopy();
            switch (mutation) {
                case 0 -> ((ObjectNode)bad.path("check")).put("complete",false);
                case 1 -> bad.put("universalProofStatus","PRODUCED");
                case 2 -> ((ObjectNode)bad.path("source")).remove("workspaceHash");
                case 3 -> bad.put("claimedPromotion",true);
                case 4 -> {
                    for (JsonNode role : bad.path("artifacts")) if (role.path("role").asText().equals("UNIVERSAL_PROOF")) {
                        ((ObjectNode)role).put("status","AVAILABLE").put("artifactSchema","invented-proof/v1")
                            .put("targetContentHash","sha256:"+"a".repeat(64));
                    }
                }
            }
            assertFalse(schema.validate(bad).isEmpty(),"accepted invalid receipt mutation "+mutation);
        }
    }

    private SchemaRegistry registry() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("docs/schemas"))) root = root.getParent();
        assertNotNull(root,"checkout schema directory");
        var schemas = new LinkedHashMap<String,String>();
        for (String name : List.of("finite-data-check","downstream-validation")) {
            String schema = Files.readString(root.resolve("docs/schemas/regelsuche-domain-"+name+"-v1.schema.json"));
            schemas.put(json.readTree(schema).required("$id").asText(),schema);
        }
        var registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12,builder -> builder
            .schemaLoader(loader -> loader.fetchRemoteResources(false)).schemas(schemas)
            .schemaRegistryConfig(SchemaRegistryConfig.builder().typeLoose(false).formatAssertionsEnabled(false).build()));
        var meta = registry.getSchema(SchemaLocation.of("https://json-schema.org/draft/2020-12/schema"));
        for (String schema : schemas.values()) assertTrue(meta.validate(json.readTree(schema)).isEmpty());
        return registry;
    }

    private DomainExportWorkspace workspace(boolean recurrence, boolean refuted) {
        DiscoveryDomain<?,?,?> domain = recurrence ? new LinearRecurrenceSequenceDomain() : new FiniteDifferenceSequenceDomain();
        String payload = recurrence ? "observed=2,3,5,8,13,21;holdout="+(refuted ? "35,55,89" : "34,55,89")+";maximumOrder=3"
            : "observed=1,4,9,16;holdout="+(refuted ? "26" : "25,36");
        var source = new DomainDiscoveryRunner().run("public-schema-control",domain,
            DiscoverySeed.create("public-source",domain.domainId(),payload,"ordinary finite unit control"),
            new DiscoveryBudget(4,16,32,8,8,32)).evidence();
        new DomainDiscoveryExport().write(directory,source);
        return DomainExportWorkspace.fromVerified(new DomainDiscoveryExportVerifier().requireVerified(directory));
    }
}
