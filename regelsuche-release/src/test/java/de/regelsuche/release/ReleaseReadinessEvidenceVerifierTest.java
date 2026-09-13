package de.regelsuche.release;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.regelsuche.experiments.autopilot.AutonomousResearchBriefV2;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

/** One-for-one controls retained from test-release-readiness-evidence.py, over the same 48 files. */
class ReleaseReadinessEvidenceVerifierTest {
    private static final String RUN = "release-readiness-run.json";
    private static final String MATRIX = "release-readiness-report.json";
    private static final String ZERO = "sha256:" + "0".repeat(64);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path SCHEMAS = Path.of(System.getProperty("regelsuche.repositoryRoot", ".."), "docs/schemas").toAbsolutePath();
    @TempDir Path temporary;

    @TestFactory
    Stream<DynamicTest> original26Controls() throws Exception {
        var controls = new ArrayList<Control>();
        controls.add(control("valid_retained_java_hashes", root -> {
            assertEquals(read(root, RUN).get("contentHash").textValue(), ReleaseReadinessJavaBindingsTest.rehashRun(read(root, RUN)));
            assertEquals(read(root, MATRIX).get("contentHash").textValue(), rehashMatrix(read(root, MATRIX)));
            verify(root);
        }));
        controls.add(control("root_catalog_and_nested_directory_symlinks_are_rejected", root -> {
            for (String relative : List.of("profiles.json", "campaign", ".")) {
                Path target = relative.equals(".") ? root : root.resolve(relative), outside = root.getParent().resolve("outside");
                Files.move(target, outside); Files.createSymbolicLink(target, outside);
                try { reject(root); } finally { Files.delete(target); Files.move(outside, target); }
            }
        }));
        controls.add(control("symbolic_ancestor_of_the_requested_root_is_rejected", root -> {
            Path alias = Files.createSymbolicLink(root.getParent().resolve("alias"), root.getParent()); reject(alias.resolve(root.getFileName()));
        }));
        controls.add(control("consumed_identity_fields_cannot_change_under_the_original_hash", root -> {
            for (String[] row : new String[][] {{"evidence-summary.json", "candidateCount", "2"},
                    {"campaign/production-campaign-manifest.json", "candidateCount", "2"},
                    {"hidden-rule-release-evidence.json", "generatedValidationExamples", "176"},
                    {"qualification/candidate-qualification-evidence.json", "pairedUtilityPermille", "999"},
                    {"qualification/candidate-qualification-run.json", "suiteHash", ZERO}}) {
                mutate(root, row[0], object -> { if (row[2].equals(ZERO)) object.put(row[1], ZERO); else object.put(row[1], Integer.parseInt(row[2])); });
            }
        }));
        controls.add(control("every_root_binding_source_requires_its_existing_schema", root -> {
            for (String file : List.of("evidence-summary.json", "hidden-rule-release-evidence.json", "campaign/production-campaign-manifest.json"))
                mutate(root, file, object -> object.put("schema", "unrecognized-evidence/v999"));
        }));
        controls.add(control("unsupported_no_follow_platform_fails_instead_of_skipping_verification", root -> {
            String previous = System.getProperty("os.name");
            try { System.setProperty("os.name", "unsupported"); assertThrows(IllegalArgumentException.class, () -> verify(root)); }
            finally { System.setProperty("os.name", previous); }
        }));
        controls.add(control("schema_symlink_is_rejected", root -> {
            Path schemas = copySchemas(root.getParent().resolve("schemas"));
            Path schema = schemas.resolve(ReleaseReadinessEvidenceVerifier.SCHEMAS.get("profiles.json"));
            Path outside = Files.move(schema, root.getParent().resolve("outside-schema")); Files.createSymbolicLink(schema, outside);
            assertThrows(Exception.class, () -> ReleaseReadinessEvidenceVerifier.verify(root, schemas));
        }));
        controls.add(control("opened_root_and_first_read_bytes_remain_owned_after_path_replacement", root -> {
            byte[] expected = Files.readAllBytes(root.resolve("profiles.json"));
            Path outside = Files.createDirectory(root.getParent().resolve("outside")); Files.writeString(outside.resolve("profiles.json"), "outside");
            try (var files = new OwnedReleaseEvidenceFiles(root)) {
                Path moved = Files.move(root, root.getParent().resolve("moved")); Files.createSymbolicLink(root, outside);
                assertArrayEquals(expected, files.readBytes(Path.of("profiles.json")));
                Files.writeString(moved.resolve("profiles.json"), "changed"); assertArrayEquals(expected, files.readBytes(Path.of("profiles.json")));
            }
        }));
        controls.add(control("reader_refuses_relative_escape_and_nonregular_members", root -> {
            fifo(root.resolve("not-a-file"));
            assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
                try (var files = new OwnedReleaseEvidenceFiles(root)) {
                    for (Path relative : List.of(Path.of("../outside.json"), root.resolve("profiles.json"), Path.of("."), Path.of("not-a-file")))
                        assertThrows(Exception.class, () -> files.readBytes(relative));
                }
            });
        }));
        controls.add(control("campaign_count_still_requires_summary_binding_after_manifest_rehash", root ->
            mutate(root, "campaign/production-campaign-manifest.json", object -> object.put("candidateCount", object.get("candidateCount").intValue() + 1))));
        controls.add(control("each_direct_dependency_hash_is_recomputed_before_root_use", root -> {
            for (String[] row : new String[][] {
                    {"evidence-summary.json", "evidenceHash", "retained AutonomousCampaignReleaseEvidence"},
                    {"hidden-rule-release-evidence.json", "evidenceHash", "retained HiddenRuleBenchmarkReleaseEvidence"},
                    {"campaign/production-campaign-manifest.json", "contentHash", "campaign canonical identity differs"},
                    {"qualification/candidate-qualification-evidence.json", "contentHash", "retained AutonomousCandidateQualificationEvidence"},
                    {"qualification/candidate-qualification-run.json", "contentHash", "qualification run canonical identity differs"}}) {
                mutate(root, row[0], object -> object.put(row[1], ZERO), changedRoot -> {
                    var failure = assertThrows(IllegalArgumentException.class, () -> verify(changedRoot));
                    // A later run-reference mismatch is insufficient: this direct identity must be checked first.
                    assertTrue(failure.getMessage().startsWith(row[2]), failure.getMessage());
                });
            }
        }));
        controls.add(control("java_unicode_order_blank_filter_and_utf8_replacement_are_preserved", root -> {
            var values = List.of("\u000b", "\u0085", "\u00a0", "\u2007", "\u2000", "\u202f", "z", "\ud800\udc00", "\ue000", "z");
            assertEquals("sha256:f5f38822fd19e7c857d71db71027eb0a412044c2d02ea9bb1d3aacb10010324b",
                AutonomousResearchBriefV2.hash(values.stream().filter(value -> !value.isBlank()).distinct().sorted().toList().toString()));
            assertEquals("sha256:c55874dc1cdc737315a4af6031e71eda35060469b02592bcf570b8611f0c22f6", AutonomousResearchBriefV2.hash("\ud800|\udc00|\ud800\udc00|Ä\n"));
        }));
        controls.add(control("original_zero_matrix_hash_reproducer", root -> mutate(root, RUN, object -> object.put("matrixHash", ZERO))));
        controls.add(control("each_root_reference_even_with_fully_rehashed_root", root -> {
            for (String field : List.of("campaignManifestHash", "profileCatalogHash", "evidenceHash", "hiddenRuleEvidenceHash", "qualificationEvidenceHash", "matrixHash"))
                mutate(root, RUN, object -> { object.put(field, ZERO); object.put("contentHash", ReleaseReadinessJavaBindingsTest.rehashRun(object)); });
        }));
        controls.add(control("root_content_hash_is_verified", root -> mutate(root, RUN, object -> object.put("contentHash", ZERO))));
        controls.add(control("catalog_original_bytes_are_bound", root -> { Files.writeString(root.resolve("profiles.json"), Files.readString(root.resolve("profiles.json")) + "\n"); reject(root); }));
        controls.add(control("campaign_summary_references_retained_campaign", root -> mutate(root, "evidence-summary.json", object -> object.put("campaignManifestHash", ZERO))));
        controls.add(control("qualification_cross_bindings", root -> {
            for (String[] row : new String[][] {{"candidate-qualification-evidence.json", "campaignManifestHash"},
                    {"candidate-qualification-run.json", "campaignManifestHash"}, {"candidate-qualification-run.json", "qualificationEvidenceHash"}})
                mutate(root, "qualification/" + row[0], object -> object.put(row[1], ZERO));
        }));
        controls.add(control("matrix_evidence_cross_binding_survives_full_rehash", root -> {
            var matrix = read(root, MATRIX); matrix.put("evidenceHash", ZERO); writeRehashedMatrix(root, matrix); reject(root);
        }));
        controls.add(control("matrix_actual_is_hashed_even_if_status_is_unchanged", root -> mutate(root, MATRIX,
            matrix -> ((ObjectNode) matrix.get("profiles").get(0).get("checks").get(0)).put("actual", "altered observation"))));
        controls.add(control("matrix_claim_matches_bound_catalog", root -> mutate(root, MATRIX,
            matrix -> ((ObjectNode) matrix.get("profiles").get(0)).put("claim", "unsupported claim"))));
        controls.add(control("duplicate_profile_cannot_hide_missing_profile_after_full_rehash", root -> {
            var matrix = read(root, MATRIX); var profiles = (ArrayNode) matrix.get("profiles");
            var replacement = profile(matrix, "AUTONOMOUS_CAMPAIGN");
            for (int i = 0; i < profiles.size(); i++) if (profiles.get(i).get("profile").asText().equals("SEARCH_REPRODUCIBILITY")) profiles.set(i, replacement.deepCopy());
            writeRehashedMatrix(root, matrix); reject(root);
        }));
        controls.add(control("profile_authority_is_derived_even_after_full_rehash", root -> {
            var matrix = read(root, MATRIX); profile(matrix, "SEARCH_REPRODUCIBILITY").put("authorizesAutonomyClaim", true); writeRehashedMatrix(root, matrix); reject(root);
        }));
        controls.add(control("profile_status_and_blockers_must_agree_after_full_rehash", root -> {
            var matrix = read(root, MATRIX); profile(matrix, "SEARCH_REPRODUCIBILITY").putArray("blockers").add("INCONSISTENT_BLOCKER"); writeRehashedMatrix(root, matrix); reject(root);
        }));
        controls.add(control("profile_and_check_order_use_java_canonical_order", root -> {
            var matrix = read(root, MATRIX); reverse((ArrayNode) matrix.get("profiles"));
            for (JsonNode profile : matrix.get("profiles")) { reverse((ArrayNode) profile.get("checks")); reverse((ArrayNode) profile.get("blockers")); }
            write(root, MATRIX, matrix); verify(root);
        }));
        controls.add(control("duplicate_json_fields_are_rejected", root -> {
            String original = Files.readString(root.resolve(RUN)); Files.writeString(root.resolve(RUN), "{\"matrixHash\":\"" + ZERO + "\"," + original.substring(1)); reject(root);
        }));
        assertEquals(26, controls.size());
        return controls.stream().map(control -> DynamicTest.dynamicTest(control.name, () -> control.body.run(copyFixture(temporary.resolve(control.name).resolve("evidence")))));
    }

    @Test void everySchemaIsActiveAndInvalidSchemaMetadataFails() throws Exception {
        Path root = copyFixture(temporary.resolve("evidence"));
        verify(root);
        for (String file : ReleaseReadinessEvidenceVerifier.SCHEMAS.keySet()) mutate(root, file, object -> object.put("schema", "unsupported/v0"));
        Path schemas = copySchemas(temporary.resolve("schemas"));
        Path first = schemas.resolve(ReleaseReadinessEvidenceVerifier.SCHEMAS.get("profiles.json"));
        ObjectNode schema = (ObjectNode) JSON.readTree(Files.readAllBytes(first)); schema.put("required", "invalid"); Files.write(first, JSON.writeValueAsBytes(schema));
        assertThrows(Exception.class, () -> ReleaseReadinessEvidenceVerifier.verify(root, schemas));
    }

    @Test void requiredFieldsAndClosedObjectsAreEnforcedByEveryRetainedSchema() throws Exception {
        var validator = new ReleaseEvidenceJson();
        var fixture = ReleaseReadinessJavaBindingsTest.fixture();
        for (var pair : ReleaseReadinessEvidenceVerifier.SCHEMAS.entrySet()) {
            JsonNode schema = ReleaseEvidenceJson.parse(Files.readAllBytes(SCHEMAS.resolve(pair.getValue())), pair.getValue());
            ObjectNode original = (ObjectNode) ReleaseEvidenceJson.parse(fixture.get(pair.getKey()), pair.getKey());
            validator.validate(original, schema, pair.getKey());
            for (JsonNode required : schema.get("required")) {
                ObjectNode changed = original.deepCopy(); changed.remove(required.textValue());
                assertThrows(IllegalArgumentException.class, () -> validator.validate(changed, schema, pair.getKey()),
                    pair.getKey() + " must require " + required.textValue());
            }
            assertFalse(schema.get("additionalProperties").booleanValue());
            ObjectNode changed = original.deepCopy(); changed.put("undeclaredExtraField", true);
            assertThrows(IllegalArgumentException.class, () -> validator.validate(changed, schema, pair.getKey()), pair.getKey());
        }
    }

    @Test void strictParsingRejectsInvalidUtf8NonFiniteNumbersAndTrailingDocuments() throws Exception {
        Path root = copyFixture(temporary.resolve("evidence"));
        for (byte[] bytes : List.of(new byte[]{'{', '"', 'x', '"', ':', '"', (byte) 0xff, '"', '}'},
                "{\"x\":NaN}".getBytes(StandardCharsets.UTF_8), "{} {}".getBytes(StandardCharsets.UTF_8))) {
            Files.write(root.resolve(RUN), bytes); reject(root);
        }
    }

    @Test void legacyObligationAndExternalSchemaReferencesRemainForbidden() throws Exception {
        Path root = copyFixture(temporary.resolve("evidence"));
        Files.createSymbolicLink(root.resolve("campaign/proof-obligation.json"), Path.of("absent")); reject(root);
        Files.delete(root.resolve("campaign/proof-obligation.json"));
        Path schemas = copySchemas(temporary.resolve("schemas"));
        Path first = schemas.resolve(ReleaseReadinessEvidenceVerifier.SCHEMAS.get("profiles.json"));
        ObjectNode schema = (ObjectNode) JSON.readTree(Files.readAllBytes(first)); schema.put("$ref", "http://127.0.0.1:1/schema"); Files.write(first, JSON.writeValueAsBytes(schema));
        assertThrows(Exception.class, () -> ReleaseReadinessEvidenceVerifier.verify(root, schemas));
    }

    @Test void publicVerifierRejectsForeignFilesystemEvidenceAndSchemaRoots() throws Exception {
        Path root = copyFixture(temporary.resolve("host-evidence"));
        try (var zip = FileSystems.newFileSystem(temporary.resolve("empty.zip"), Map.of("create", "true"))) {
            Path foreignRoot = zip.getPath(root.toString()), foreignSchemas = zip.getPath(SCHEMAS.toString());
            assertFalse(Files.exists(foreignRoot));
            assertFalse(Files.exists(foreignSchemas));
            assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> ReleaseReadinessEvidenceVerifier.verify(foreignRoot, SCHEMAS)),
                () -> assertThrows(IllegalArgumentException.class, () -> ReleaseReadinessEvidenceVerifier.verify(root, foreignSchemas)));
        }
        verify(root);
    }

    @Test void dynamicSchemaReferencesFollowTheSameLocalAdmissionAsOrdinaryReferences() throws Exception {
        var validator = new ReleaseEvidenceJson();
        var local = JSON.createObjectNode().put("$schema", "https://json-schema.org/draft/2020-12/schema")
            .put("$id", "local-dynamic/v1").put("$dynamicRef", "#/$defs/object");
        local.putObject("$defs").putObject("object").put("type", "object").putArray("required").add("kept");
        var original = JSON.createObjectNode().put("kept", true);
        validator.validate(original, local, "local dynamic reference");
        assertThrows(IllegalArgumentException.class, () -> validator.validate(JSON.createObjectNode(), local, "missing kept field"));
        // The metaschema is already in the registry; disabling remote fetches alone cannot reject this external reference.
        for (String keyword : List.of("$ref", "$dynamicRef")) {
            var external = JSON.createObjectNode().put("$schema", "https://json-schema.org/draft/2020-12/schema")
                .put("$id", "external-reference/v1").put(keyword, "https://json-schema.org/draft/2020-12/schema");
            var error = assertThrows(IllegalArgumentException.class,
                () -> validator.validate(original, external, "external " + keyword));
            assertEquals("external schema references are not admitted", error.getMessage());
            var nested = local.deepCopy();
            nested.putObject("properties").putObject("kept").put(keyword, "https://json-schema.org/draft/2020-12/schema");
            assertEquals("external schema references are not admitted", assertThrows(IllegalArgumentException.class,
                () -> validator.validate(original, nested, "nested external " + keyword)).getMessage());
        }
    }

    @Test void nativePathStringConversionCannotRedirectAnEmptyRootToAQualifiedSibling() throws Exception {
        Path qualified = copyFixture(temporary.resolve("root-\ufffd"));
        byte[] prefix = (temporary + "/root-").getBytes(StandardCharsets.UTF_8);
        byte[] nativeName = java.util.Arrays.copyOf(prefix, prefix.length + 2);
        nativeName[prefix.length] = (byte) 0xff; // Invalid UTF-8, followed by the terminating NUL.
        try (var arena = Arena.ofConfined()) {
            var linker = Linker.nativeLinker();
            var mkdir = linker.downcallHandle(linker.defaultLookup().find("mkdir").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
            try { assertEquals(0, (int) mkdir.invokeExact(arena.allocateFrom(ValueLayout.JAVA_BYTE, nativeName), 0700)); }
            catch (Throwable error) { throw new Exception(error); }
        }
        Path rawRoot;
        try (var entries = Files.list(temporary)) {
            rawRoot = entries.filter(path -> !path.equals(Path.of(path.toString()))).findFirst().orElseThrow();
        }
        assertEquals(qualified.toString(), rawRoot.toString());
        assertNotEquals(qualified, rawRoot);
        try (var entries = Files.list(rawRoot)) { assertEquals(0, entries.count()); }
        assertThrows(IllegalArgumentException.class, () -> ReleaseReadinessEvidenceVerifier.verify(rawRoot, SCHEMAS));
        verify(qualified); // A real replacement character remains a valid, distinct Unicode filename.
    }

    @Test void requiredLedgerPayloadsKeepTheirExistingNonemptyOnlyScope() throws Exception {
        Path root = copyFixture(temporary.resolve("evidence"));
        for (String file : List.of("campaign/campaign-resource-ledger.json", "campaign/feedback-reallocation.json")) {
            byte[] original = Files.readAllBytes(root.resolve(file));
            Files.writeString(root.resolve(file), "retained opaque nonempty bytes");
            verify(root);
            Files.write(root.resolve(file), new byte[0]);
            reject(root);
            Files.write(root.resolve(file), original);
        }
    }

    @Test void commandLineRequiresNativeAccessAndNeverPrintsSuccessOnFailure() throws Exception {
        Path root = copyFixture(temporary.resolve("evidence"));
        Path javaExecutable = Path.of(System.getProperty("java.home"), "bin/java");
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Path output = temporary.resolve("without-native-access.log");
        var builder = new ProcessBuilder(javaExecutable.toString(), "-cp", classpath,
            ReleaseReadinessEvidenceVerifier.class.getName(), "--root", root.toString(), "--schemas", SCHEMAS.toString());
        for (String option : List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")) builder.environment().remove(option);
        Process process = builder.redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertTrue(process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS), "verifier did not terminate");
            assertNotEquals(0, process.exitValue());
            String actual = Files.readString(output);
            assertTrue(actual.contains("UNSUPPORTED_NATIVE_ACCESS"), actual);
            assertFalse(actual.contains("release-readiness-contract=valid"), actual);
        } finally {
            if (process.isAlive()) { process.destroyForcibly(); process.waitFor(); }
        }
    }

    private static void verify(Path root) throws Exception { ReleaseReadinessEvidenceVerifier.verify(root, SCHEMAS); }
    private static void reject(Path root) { assertThrows(Exception.class, () -> verify(root)); }
    private static ObjectNode read(Path root, String relative) throws Exception { return (ObjectNode) JSON.readTree(Files.readAllBytes(root.resolve(relative))); }
    private static void write(Path root, String relative, JsonNode node) throws Exception { Files.write(root.resolve(relative), JSON.writeValueAsBytes(node)); }
    private static void mutate(Path root, String relative, java.util.function.Consumer<ObjectNode> mutation) throws Exception {
        mutate(root, relative, mutation, ReleaseReadinessEvidenceVerifierTest::reject);
    }
    private static void mutate(Path root, String relative, java.util.function.Consumer<ObjectNode> mutation, Body assertion) throws Exception {
        byte[] original = Files.readAllBytes(root.resolve(relative));
        try { var changed = read(root, relative); mutation.accept(changed); write(root, relative, changed); assertion.run(root); }
        finally { Files.write(root.resolve(relative), original); }
    }
    private static Path copyFixture(Path root) throws Exception {
        for (var entry : ReleaseReadinessJavaBindingsTest.fixture().entrySet()) {
            Path output = root.resolve(entry.getKey()); Files.createDirectories(output.getParent()); Files.write(output, entry.getValue());
        }
        return root;
    }
    private static Path copySchemas(Path target) throws Exception {
        Files.createDirectories(target);
        for (String name : ReleaseReadinessEvidenceVerifier.SCHEMAS.values()) Files.copy(SCHEMAS.resolve(name), target.resolve(name));
        return target;
    }
    private static ObjectNode profile(ObjectNode matrix, String name) {
        for (JsonNode profile : matrix.get("profiles")) if (profile.get("profile").asText().equals(name)) return (ObjectNode) profile;
        throw new AssertionError("profile absent: " + name);
    }
    private static void reverse(ArrayNode array) { var values = new ArrayList<JsonNode>(); array.forEach(values::add); java.util.Collections.reverse(values); array.removeAll().addAll(values); }
    private static void writeRehashedMatrix(Path root, ObjectNode matrix) throws Exception {
        matrix.put("contentHash", rehashMatrix(matrix)); write(root, MATRIX, matrix);
        var run = read(root, RUN); run.put("matrixHash", matrix.get("contentHash").asText()); run.put("contentHash", ReleaseReadinessJavaBindingsTest.rehashRun(run)); write(root, RUN, run);
    }
    private static String rehashMatrix(JsonNode matrix) {
        var profiles = new ArrayList<JsonNode>(); matrix.get("profiles").forEach(profiles::add); profiles.sort(Comparator.comparing(value -> value.get("profile").asText()));
        var material = new ArrayList<String>();
        for (JsonNode profile : profiles) {
            var checks = new ArrayList<JsonNode>(); profile.get("checks").forEach(checks::add); checks.sort(Comparator.comparing(value -> value.get("code").asText()));
            var checkMaterial = checks.stream().map(check -> check.get("code").asText() + "|passed=" + check.get("passed").asBoolean()
                + "|actual=" + check.get("actual").asText() + "|required=" + check.get("required").asText()).toList();
            var blockers = new ArrayList<String>(); profile.get("blockers").forEach(value -> blockers.add(value.asText())); blockers.sort(String::compareTo);
            material.add(profile.get("profile").asText() + "|" + profile.get("status").asText() + "|" + profile.get("authorizesAutonomyClaim").asBoolean() + "|" + checkMaterial + "|" + blockers);
        }
        return AutonomousResearchBriefV2.hash(matrix.get("schema").asText() + "\nevidence=" + matrix.get("evidenceHash").asText()
            + "\nhiddenRuleEvidence=" + matrix.get("hiddenRuleEvidenceHash").asText() + "\nprofiles=" + material + "\nautonomy=" + matrix.get("autonomousCampaignStatus").asText());
    }
    private static void fifo(Path path) throws Exception {
        try (var arena = Arena.ofConfined()) {
            var linker = Linker.nativeLinker(); var function = linker.downcallHandle(linker.defaultLookup().find("mkfifo").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
            try { assertEquals(0, (int) function.invokeExact(arena.allocateFrom(path.toString()), 0600)); }
            catch (Throwable error) { throw new Exception(error); }
        }
    }
    private static Control control(String name, Body body) { return new Control(name, body); }
    private record Control(String name, Body body) { }
    @FunctionalInterface private interface Body { void run(Path root) throws Exception; }
}
