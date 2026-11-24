package de.regelsuche.release;

import static org.junit.jupiter.api.Assertions.*;

import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import de.regelsuche.discovery.domain.*;
import de.regelsuche.discovery.domain.DiscoveryDomain.*;
import de.regelsuche.solver.ir.SolverIr;
import de.regelsuche.solver.ir.SolverIr.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Offline contract controls for actual model receipts, separate from release qualification. */
class DomainModelProofSchemaTest {
    @TempDir Path directory;
    private final JsonMapper json = new JsonMapper();
    private int exports;

    @Test void snapshotSchemaChecksIndependentGatesAcrossDomains() throws Exception {
        var schema = registry().getSchema(SchemaLocation.of("urn:regelsuche:domain-snapshot-assessment:v1"));
        for (var workspace : List.of(differences("observed=1,4,9,16;holdout=25,36"),
                differences("observed=1,4,9,16;holdout=26"),
                workspace(new ExpressionRewriteDiscoveryDomain("x"), "x + 0"))) {
            var report = (ObjectNode) json.readTree(DomainSnapshotAssessment.assess(workspace).toCanonicalJson());
            assertTrue(schema.validate(report).isEmpty(), () -> schema.validate(report).toString());
            for (String gate : List.of("externalNovelty", "promotion", "publicEvidence")) {
                var forged = report.deepCopy();
                ((ObjectNode) forged.path(gate)).put("status", "APPROVED");
                assertFalse(schema.validate(forged).isEmpty(), gate);
            }
        }
    }

    @Test void sourceOnlyLogicalReceiptsSatisfyAllClosedSchemas() throws Exception {
        var source = logicalSource();
        var schemas = registry();
        var reports = java.util.Map.of(
            "domain-snapshot-assessment", DomainSnapshotAssessment.assess(source).toCanonicalJson(),
            "domain-downstream-validation", DomainDownstreamValidation.validate(source).toCanonicalJson(),
            "domain-model-proof", DomainModelProof.prove(source).toCanonicalJson());
        for (var entry : reports.entrySet()) {
            var schema = schemas.getSchema(SchemaLocation.of("urn:regelsuche:" + entry.getKey() + ":v1"));
            var report = json.readTree(entry.getValue());
            assertTrue(schema.validate(report).isEmpty(), () -> schema.validate(report).toString());
            assertEquals("scoped-logic", report.at("/source/domainId").asText());
        }
    }

    private DomainExportWorkspace logicalSource() throws Exception {
        var domain = new ScopedLogicDiscoveryDomain(proposal -> {
            throw new AssertionError("schema source must not run a proof receiver");
        }, 0);
        var proposition = new Symbol("p");
        var context = Obligation.create("logical-schema-source", List.of(new SymbolDeclaration("p", Sort.PROPOSITION)),
            List.of(Theory.SCOPED_ANALYSIS), List.of(Predicate.holds("given", proposition)), Goal.holds(proposition),
            RequestedEvidence.FORMAL_PROOF, new SourceProvenance("public-control", "schema", SolverIr.sha256("public context")));
        return workspace(domain, context.toCanonicalJson());
    }

    @Test void schemaAcceptsActualProofsForBothDomainsAndEverySupportedDimension() throws Exception {
        var schema = registry().getSchema(SchemaLocation.of("urn:regelsuche:domain-model-proof:v1"));
        var sources = List.of(
            differences("observed=5,5,5;holdout=5,5"),
            differences("observed=1,4,9,16;holdout=25,36"),
            differences("observed=1,8,27,64,125,216;holdout=343,512,729"),
            recurrence("observed=2,3,5,8,13,21;holdout=34,55,89;maximumOrder=3"));
        for (var workspace : sources) {
            var receipt = read(DomainModelProof.prove(workspace));
            assertEquals("PROVED_MODEL", receipt.path("status").asText());
            assertTrue(schema.validate(receipt).isEmpty(), () -> schema.validate(receipt).toString());
        }
    }

    @Test void schemasRejectRebindingPositiveReceiptsToSourceOnlyLogicalDomain() throws Exception {
        var source = recurrence("observed=2,3,5,8,13,21;holdout=34,55,89;maximumOrder=3");
        assertAll(
            () -> rejectScopedPositive("domain-snapshot-assessment", DomainSnapshotAssessment.assess(source).toCanonicalJson()),
            () -> rejectScopedPositive("domain-downstream-validation", DomainDownstreamValidation.validate(source).toCanonicalJson()),
            () -> rejectScopedPositive("domain-model-proof", DomainModelProof.prove(source).toCanonicalJson()));
    }

    private void rejectScopedPositive(String name, String receipt) throws Exception {
        var forged = (ObjectNode) json.readTree(receipt);
        var schema = registry().getSchema(SchemaLocation.of("urn:regelsuche:" + name + ":v1"));
        assertTrue(schema.validate(forged).isEmpty(), () -> schema.validate(forged).toString());
        ((ObjectNode) forged.path("source")).put("domainId", "scoped-logic");
        assertFalse(schema.validate(forged).isEmpty(), "positive scoped receipt accepted by " + name);
    }

    @Test void scopedNegativeReceiptsCannotBorrowPositivePayloadsOrInventExecution() throws Exception {
        var source = logicalSource();
        var checked = recurrence("observed=2,3,5,8,13,21;holdout=34,55,89;maximumOrder=3");
        var positiveAssessment = (ObjectNode) json.readTree(DomainSnapshotAssessment.assess(checked).toCanonicalJson());
        var positiveValidation = (ObjectNode) json.readTree(DomainDownstreamValidation.validate(checked).toCanonicalJson());
        var positiveModel = read(DomainModelProof.prove(checked));
        var assessment = (ObjectNode) json.readTree(DomainSnapshotAssessment.assess(source).toCanonicalJson());
        var validation = (ObjectNode) json.readTree(DomainDownstreamValidation.validate(source).toCanonicalJson());
        var model = read(DomainModelProof.prove(source));
        assertAll(
            () -> rejectScopedMutation("domain-snapshot-assessment", assessment, "borrowed embedded proof", bad ->
                ((ObjectNode) bad.path("proof")).set("modelReceipt", positiveModel.deepCopy())),
            () -> rejectScopedMutation("domain-snapshot-assessment", assessment, "invented replay resources", bad ->
                bad.set("resources", positiveAssessment.path("resources").deepCopy())),
            () -> rejectScopedMutation("domain-downstream-validation", validation, "borrowed finite check", bad ->
                bad.set("check", positiveValidation.path("check").deepCopy())),
            () -> rejectScopedMutation("domain-downstream-validation", validation, "borrowed validation artifacts", bad ->
                bad.set("artifacts", positiveValidation.path("artifacts").deepCopy())),
            () -> rejectScopedMutation("domain-model-proof", model, "borrowed finite check", bad ->
                bad.set("finiteCheck", positiveModel.path("finiteCheck").deepCopy())),
            () -> rejectScopedMutation("domain-model-proof", model, "invented replay admission", bad ->
                ((ObjectNode) bad.at("/replay/admission")).put("executed", 1).put("remaining", 0)));
    }

    private void rejectScopedMutation(String name, ObjectNode receipt, String detail, Consumer<ObjectNode> edit)
            throws Exception {
        var schema = registry().getSchema(SchemaLocation.of("urn:regelsuche:" + name + ":v1"));
        assertTrue(schema.validate(receipt).isEmpty(), () -> schema.validate(receipt).toString());
        var forged = receipt.deepCopy();
        edit.accept(forged);
        assertFalse(schema.validate(forged).isEmpty(), name + " accepted " + detail);
    }

    @Test void schemaAcceptsActualUnsupportedAndStoppedReceiptsWithoutProof() throws Exception {
        var schema = registry().getSchema(SchemaLocation.of("urn:regelsuche:domain-model-proof:v1"));
        var workspace = differences("observed=1,4,9,16;holdout=25,36");
        var defaults = DomainModelProof.Budget.defaults();
        var receipts = new LinkedHashMap<String, ObjectNode>();
        receipts.put("UNSUPPORTED", read(DomainModelProof.prove(differences(
            "observed=1,16,81,256,625,1296;holdout=2401,4096"))));
        receipts.put("NOT_EVALUATED_NO_SELECTED_CANDIDATE", read(DomainModelProof.prove(
            differences("observed=1,4,9,16;holdout=26"))));
        receipts.put("FINITE_CHECK_NOT_CONFIRMED", read(DomainModelProof.prove(workspace,
            new DomainModelProof.Budget(1, 512, new DomainFiniteDataCheck.Budget(2, 1000, 1024),
                200_000, 200_000, 200_000))));
        receipts.put("INCONCLUSIVE", read(DomainModelProof.prove(workspace,
            new DomainModelProof.Budget(0, 512, defaults.finiteCheck(), 200_000, 200_000, 200_000))));
        for (var entry : receipts.entrySet()) {
            var receipt = entry.getValue();
            assertEquals(entry.getKey(), receipt.path("status").asText());
            assertTrue(receipt.path("proof").isNull());
            assertTrue(schema.validate(receipt).isEmpty(), () -> schema.validate(receipt).toString());
        }
        for (int phase = 0; phase < 3; phase++) {
            var budget = new DomainModelProof.Budget(1, 512, defaults.finiteCheck(),
                phase == 0 ? 0 : 200_000, phase == 1 ? 0 : 200_000, phase == 2 ? 0 : 200_000);
            var receipt = read(DomainModelProof.prove(workspace, budget));
            assertEquals("INCONCLUSIVE", receipt.path("status").asText());
            assertTrue(receipt.path("model").isObject());
            assertTrue(receipt.path("proof").isNull());
            assertTrue(schema.validate(receipt).isEmpty(), () -> schema.validate(receipt).toString());
        }
    }

    @Test void schemaRequiresEverySourceHashAndKeepsTheOriginalSequenceUnproved() throws Exception {
        var schema = registry().getSchema(SchemaLocation.of("urn:regelsuche:domain-model-proof:v1"));
        var positive = positive();
        for (String field : List.of("runId", "manifestHash", "manifestByteHash", "verificationHash",
                "workspaceHash", "descriptorHash", "seedHash", "evidenceHash", "handoffHash",
                "selectedCandidateHash", "certificateObjectHash", "renderedCertificateHash")) {
            var bad = positive.deepCopy();
            ((ObjectNode) bad.path("source")).remove(field);
            assertFalse(schema.validate(bad).isEmpty(), "accepted missing source binding " + field);
        }
        for (String field : List.of("originalSequenceProofStatus", "formalProofStatus",
                "externalNoveltyStatus", "promotionStatus", "publicEvidenceStatus")) {
            var bad = positive.deepCopy();
            bad.put(field, "PROVED_MODEL");
            assertFalse(schema.validate(bad).isEmpty(), "accepted promotion of " + field);
        }
        var noSelection = positive.deepCopy();
        ((ObjectNode) noSelection.path("source")).putNull("selectedCandidateHash");
        assertFalse(schema.validate(noSelection).isEmpty(), "accepted a proof without a selected candidate");
    }

    @Test void schemaRejectsContradictoryProofPresenceAndMalformedModelsOrProofs() throws Exception {
        var schema = registry().getSchema(SchemaLocation.of("urn:regelsuche:domain-model-proof:v1"));
        var positive = positive();
        var mutations = List.of(
            new Mutation("missing proof", bad -> bad.putNull("proof")),
            new Mutation("missing model", bad -> bad.putNull("model")),
            new Mutation("unconfirmed finite check", bad ->
                ((ObjectNode) bad.path("finiteCheck")).put("complete", false)),
            new Mutation("wrong replay authority", bad ->
                ((ObjectNode) bad.path("replay")).put("status", "NOT_EVALUATED")),
            new Mutation("missing proof-model binding", bad ->
                ((ObjectNode) bad.path("proof")).remove("modelHash")),
            new Mutation("wrong model dimension", bad ->
                ((ObjectNode) bad.path("model")).put("dimension", 3)),
            new Mutation("erased model assumption", bad ->
                ((ObjectNode) bad.path("model")).putArray("assumptions").add("Unconditional source theorem")),
            new Mutation("nonrational model coefficient", bad ->
                ((ArrayNode) bad.at("/model/linear/0")).set(0, json.getNodeFactory().textNode("0.5"))),
            new Mutation("wrong recurrence shape", bad ->
                ((ObjectNode) bad.path("proof")).put("recurrenceOrder", 0)),
            new Mutation("empty recurrence for the fixed nonzero observation", bad -> {
                ((ObjectNode) bad.path("proof")).put("recurrenceOrder", 0);
                ((ArrayNode) bad.at("/proof/coefficientsOldestFirst")).removeAll();
            }),
            new Mutation("nonrational recurrence coefficient", bad ->
                ((ArrayNode) bad.at("/proof/coefficientsOldestFirst")).set(0,
                    json.getNodeFactory().textNode("NaN"))),
            new Mutation("unbounded query index", bad ->
                ((ObjectNode) bad.at("/proof/queries/0")).put("index", 256)),
            new Mutation("unobserved value claimed as retained", bad ->
                ((ObjectNode) bad.at("/proof/queries/0")).put("role", "MODEL_PREDICTION")),
            new Mutation("extra theorem claim", bad ->
                ((ObjectNode) bad.path("proof")).put("universalOriginalSequence", true)));
        for (var mutation : mutations) {
            var bad = positive.deepCopy();
            mutation.edit().accept(bad);
            assertFalse(schema.validate(bad).isEmpty(), "accepted " + mutation.name());
        }
        var unsupported = read(DomainModelProof.prove(differences(
            "observed=1,16,81,256,625,1296;holdout=2401,4096")));
        unsupported.set("proof", positive.path("proof").deepCopy());
        assertFalse(schema.validate(unsupported).isEmpty(), "accepted a proof on an unsupported model");
    }

    @Test void schemaRejectsInventedWorkAndUnobservableCompletedPhases() throws Exception {
        var schema = registry().getSchema(SchemaLocation.of("urn:regelsuche:domain-model-proof:v1"));
        var positive = positive();
        var mutations = List.of(
            new Mutation("negative work", bad ->
                ((ObjectNode) bad.at("/work/preparation")).put("executed", -1)),
            new Mutation("unobserved complete work", bad ->
                ((ObjectNode) bad.at("/work/preparation")).putNull("executed")),
            new Mutation("incomplete phase with published proof", bad ->
                ((ObjectNode) bad.at("/work/binding")).put("status", "BUDGET_INCONCLUSIVE")),
            new Mutation("invented total work", bad ->
                ((ObjectNode) bad.path("work")).put("totalWork", 0)),
            new Mutation("invented projection meter", bad ->
                ((ObjectNode) bad.path("work")).put("modelProjectionWork", 0)),
            new Mutation("excessive phase budget", bad ->
                ((ObjectNode) bad.path("budget")).put("maxQueryWorkUnits", 200_001)),
            new Mutation("extra phase work", bad ->
                ((ObjectNode) bad.at("/work/queries")).put("estimatedOperations", 0)));
        for (var mutation : mutations) {
            var bad = positive.deepCopy();
            mutation.edit().accept(bad);
            assertFalse(schema.validate(bad).isEmpty(), "accepted " + mutation.name());
        }
        var stopped = read(DomainModelProof.prove(differences(
            "observed=1,16,81,256,625,1296;holdout=2401,4096")));
        ((ObjectNode) stopped.at("/work/preparation")).put("executed", 1);
        assertFalse(schema.validate(stopped).isEmpty(), "accepted execution in a NOT_RUN phase");
    }

    private ObjectNode positive() throws Exception {
        return read(DomainModelProof.prove(differences("observed=1,4,9,16;holdout=25,36")));
    }

    private ObjectNode read(DomainModelProof.Receipt receipt) throws Exception {
        return (ObjectNode) json.readTree(receipt.toCanonicalJson());
    }

    private SchemaRegistry registry() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("docs/schemas"))) root = root.getParent();
        assertNotNull(root, "checkout schema directory");
        var schemas = new LinkedHashMap<String, String>();
        for (String name : List.of("finite-data-check", "downstream-validation", "model-proof", "snapshot-assessment")) {
            Path path = root.resolve("docs/schemas/regelsuche-domain-" + name + "-v1.schema.json");
            assertTrue(Files.isRegularFile(path), "missing published schema: " + path);
            String schema = Files.readString(path);
            schemas.put(json.readTree(schema).required("$id").asText(), schema);
        }
        var registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12, builder -> builder
            .schemaLoader(loader -> loader.fetchRemoteResources(false)).schemas(schemas)
            .schemaRegistryConfig(SchemaRegistryConfig.builder().typeLoose(false).formatAssertionsEnabled(false).build()));
        var meta = registry.getSchema(SchemaLocation.of("https://json-schema.org/draft/2020-12/schema"));
        for (String schema : schemas.values()) {
            assertTrue(meta.validate(json.readTree(schema)).isEmpty(), () -> meta.validate(json.readTree(schema)).toString());
        }
        return registry;
    }

    private DomainExportWorkspace differences(String payload) {
        return workspace(new FiniteDifferenceSequenceDomain(), payload);
    }

    private DomainExportWorkspace recurrence(String payload) {
        return workspace(new LinearRecurrenceSequenceDomain(), payload);
    }

    private <S, C, K> DomainExportWorkspace workspace(DiscoveryDomain<S, C, K> domain, String payload) {
        var source = new DomainDiscoveryRunner().run("model-proof-schema-control", domain,
            DiscoverySeed.create("public-source", domain.domainId(), payload, "ordinary schema unit control"),
            new DiscoveryBudget(8, 32, 32, 8, 8, 64)).evidence();
        Path export = directory.resolve("export-" + exports++);
        new DomainDiscoveryExport().write(export, source);
        return DomainExportWorkspace.fromVerified(new DomainDiscoveryExportVerifier().requireVerified(export));
    }

    private record Mutation(String name, Consumer<ObjectNode> edit) { }
}
