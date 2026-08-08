package de.regelsuche.evolution;

import de.regelsuche.evolution.EvolutionGenome.SourceSplit;
import de.regelsuche.evolution.EvolutionGenome.TrainingScope;
import de.regelsuche.json.JsonWriter;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Frozen TRAIN partition with either concrete held-out references or an
 * explicitly deferred public-randomness boundary.
 */
public record EvolutionSplitManifest(
    String schema,
    String studyId,
    String corpusHash,
    String featureSchemaHash,
    List<CaseReference> trainCases,
    List<CaseReference> validationCases,
    List<CaseReference> finalTestCases,
    String familyPartitionHash,
    String signaturePartitionHash,
    String contentHash
) {
    public static final String SCHEMA = "regelsuche.evolution-split-manifest/v1";
    public static final String DEFERRED_HELD_OUT =
        "DEFERRED_TO_PUBLIC_RANDOMNESS";
    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9_-]{2,127}");
    private static final Pattern SHA256 = Pattern.compile("sha256:[0-9a-f]{64}");

    public EvolutionSplitManifest {
        if (!SCHEMA.equals(schema)) {
            throw new IllegalArgumentException("unsupported evolution split-manifest schema");
        }
        requireId(studyId, "studyId");
        requireHash(corpusHash, "corpusHash");
        requireHash(featureSchemaHash, "featureSchemaHash");
        trainCases = normalize(trainCases, "trainCases", false);
        validationCases = normalize(validationCases, "validationCases", true);
        finalTestCases = normalize(finalTestCases, "finalTestCases", true);
        if (validationCases.isEmpty() != finalTestCases.isEmpty()) {
            throw new IllegalArgumentException(
                "VALIDATION and FINAL TEST must be concrete together or deferred together");
        }
        requireDisjoint(trainCases, validationCases, finalTestCases);

        String expectedFamilies = partitionHash(
            "families",
            trainCases.stream().map(CaseReference::familyId).toList(),
            validationCases.stream().map(CaseReference::familyId).toList(),
            finalTestCases.stream().map(CaseReference::familyId).toList());
        if (!expectedFamilies.equals(familyPartitionHash)) {
            throw new IllegalArgumentException("familyPartitionHash does not match split families");
        }
        String expectedSignatures = partitionHash(
            "signatures",
            signatureMaterial(trainCases),
            signatureMaterial(validationCases),
            signatureMaterial(finalTestCases));
        if (!expectedSignatures.equals(signaturePartitionHash)) {
            throw new IllegalArgumentException(
                "signaturePartitionHash does not match exact and alpha signatures");
        }
        requireHash(contentHash, "contentHash");
        String expectedContent = EvolutionGenome.hash(render(
            studyId,
            corpusHash,
            featureSchemaHash,
            trainCases,
            validationCases,
            finalTestCases,
            familyPartitionHash,
            signaturePartitionHash,
            null));
        if (!expectedContent.equals(contentHash)) {
            throw new IllegalArgumentException("contentHash does not match split manifest");
        }
    }

    public static EvolutionSplitManifest create(
        String studyId,
        String corpusHash,
        String featureSchemaHash,
        List<CaseReference> trainCases,
        List<CaseReference> validationCases,
        List<CaseReference> finalTestCases
    ) {
        List<CaseReference> train = normalize(trainCases, "trainCases", false);
        List<CaseReference> validation = normalize(
            validationCases, "validationCases", false);
        List<CaseReference> test = normalize(
            finalTestCases, "finalTestCases", false);
        return createCanonical(
            studyId,
            corpusHash,
            featureSchemaHash,
            train,
            validation,
            test);
    }

    public static EvolutionSplitManifest createTrainOnly(
        String studyId,
        String corpusHash,
        String featureSchemaHash,
        List<CaseReference> trainCases
    ) {
        return createCanonical(
            studyId,
            corpusHash,
            featureSchemaHash,
            normalize(trainCases, "trainCases", false),
            List.of(),
            List.of());
    }

    private static EvolutionSplitManifest createCanonical(
        String studyId,
        String corpusHash,
        String featureSchemaHash,
        List<CaseReference> train,
        List<CaseReference> validation,
        List<CaseReference> test
    ) {
        requireDisjoint(train, validation, test);
        String families = partitionHash(
            "families",
            train.stream().map(CaseReference::familyId).toList(),
            validation.stream().map(CaseReference::familyId).toList(),
            test.stream().map(CaseReference::familyId).toList());
        String signatures = partitionHash(
            "signatures",
            signatureMaterial(train),
            signatureMaterial(validation),
            signatureMaterial(test));
        String content = EvolutionGenome.hash(render(
            studyId,
            corpusHash,
            featureSchemaHash,
            train,
            validation,
            test,
            families,
            signatures,
            null));
        return new EvolutionSplitManifest(
            SCHEMA,
            studyId,
            corpusHash,
            featureSchemaHash,
            train,
            validation,
            test,
            families,
            signatures,
            content);
    }

    public boolean heldOutMaterializationDeferred() {
        return validationCases.isEmpty();
    }

    /** Returns the only scope that candidate genomes may consume. */
    public TrainingScope trainingScope() {
        return new TrainingScope(
            SourceSplit.TRAIN,
            corpusHash,
            familyPartitionHash,
            signaturePartitionHash,
            featureSchemaHash);
    }

    public String toCanonicalJson() {
        return render(
            studyId,
            corpusHash,
            featureSchemaHash,
            trainCases,
            validationCases,
            finalTestCases,
            familyPartitionHash,
            signaturePartitionHash,
            contentHash);
    }

    private static List<CaseReference> normalize(
        List<CaseReference> cases,
        String name,
        boolean allowEmpty
    ) {
        Objects.requireNonNull(cases, name);
        if (cases.isEmpty() && !allowEmpty) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        List<CaseReference> result = cases.stream()
            .map(item -> Objects.requireNonNull(item, name + " entry"))
            .sorted(Comparator.comparing(CaseReference::caseId))
            .toList();
        if (new HashSet<>(result.stream().map(CaseReference::caseId).toList()).size()
                != result.size()) {
            throw new IllegalArgumentException(name + " contains duplicate case IDs");
        }
        return List.copyOf(result);
    }

    private static void requireDisjoint(
        List<CaseReference> train,
        List<CaseReference> validation,
        List<CaseReference> test
    ) {
        List<SplitCases> splits = List.of(
            new SplitCases("TRAIN", train),
            new SplitCases("VALIDATION", validation),
            new SplitCases("FINAL_TEST", test));
        requireUniqueAcross(splits, "case ID", CaseReference::caseId);
        requireUniqueAcross(splits, "family", CaseReference::familyId);
        requireUniqueAcross(splits, "exact signature", CaseReference::exactSignatureHash);
        requireUniqueAcross(splits, "alpha signature", CaseReference::alphaSignatureHash);
        requireUniqueAcross(splits, "input identity", CaseReference::inputHash);
        requireUniqueAcross(splits, "hidden-target identity", CaseReference::hiddenTargetHash);
    }

    private static void requireUniqueAcross(
        List<SplitCases> splits,
        String label,
        java.util.function.Function<CaseReference, String> identity
    ) {
        for (int left = 0; left < splits.size(); left++) {
            Set<String> values = new HashSet<>(splits.get(left).cases().stream()
                .map(identity)
                .toList());
            for (int right = left + 1; right < splits.size(); right++) {
                Set<String> collisions = new HashSet<>(values);
                collisions.retainAll(splits.get(right).cases().stream().map(identity).toList());
                if (!collisions.isEmpty()) {
                    throw new IllegalArgumentException(
                        label + " collision between " + splits.get(left).name()
                            + " and " + splits.get(right).name() + ": " + collisions);
                }
            }
        }
    }

    private static List<String> signatureMaterial(List<CaseReference> cases) {
        return cases.stream()
            .map(item -> item.exactSignatureHash() + "|" + item.alphaSignatureHash())
            .toList();
    }

    private static String partitionHash(
        String label,
        List<String> train,
        List<String> validation,
        List<String> test
    ) {
        return EvolutionGenome.hash(
            "regelsuche.evolution-split-partition/v1"
                + "\nkind=" + label
                + "\nTRAIN=" + sorted(train)
                + "\nVALIDATION=" + sorted(validation)
                + "\nFINAL_TEST=" + sorted(test));
    }

    private static List<String> sorted(List<String> values) {
        return values.stream().sorted().toList();
    }

    private static String render(
        String studyId,
        String corpusHash,
        String featureSchemaHash,
        List<CaseReference> train,
        List<CaseReference> validation,
        List<CaseReference> test,
        String familyPartitionHash,
        String signaturePartitionHash,
        String contentHash
    ) {
        JsonWriter json = new JsonWriter().beginObject()
            .property("schema", SCHEMA)
            .property("studyId", studyId)
            .property("corpusHash", corpusHash)
            .property("featureSchemaHash", featureSchemaHash);
        if (validation.isEmpty() && test.isEmpty()) {
            json.property("heldOutMaterialization", DEFERRED_HELD_OUT);
        }
        writeCases(json, "trainCases", train);
        writeCases(json, "validationCases", validation);
        writeCases(json, "finalTestCases", test);
        json.property("familyPartitionHash", familyPartitionHash)
            .property("signaturePartitionHash", signaturePartitionHash);
        if (contentHash != null) {
            json.property("contentHash", contentHash);
        }
        return json.endObject().toString();
    }

    private static void writeCases(
        JsonWriter json,
        String name,
        List<CaseReference> cases
    ) {
        json.array(name, array -> cases.forEach(item ->
            array.objectValue(object -> object
                .property("caseId", item.caseId())
                .property("familyId", item.familyId())
                .property("exactSignatureHash", item.exactSignatureHash())
                .property("alphaSignatureHash", item.alphaSignatureHash())
                .property("inputHash", item.inputHash())
                .property("hiddenTargetHash", item.hiddenTargetHash()))));
    }

    private static void requireId(String value, String name) {
        if (value == null || !ID.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " has invalid identifier syntax");
        }
    }

    private static void requireHash(String value, String name) {
        if (value == null || !SHA256.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " must be SHA-256");
        }
    }

    public record CaseReference(
        String caseId,
        String familyId,
        String exactSignatureHash,
        String alphaSignatureHash,
        String inputHash,
        String hiddenTargetHash
    ) {
        public CaseReference {
            requireId(caseId, "caseId");
            requireId(familyId, "familyId");
            requireHash(exactSignatureHash, "exactSignatureHash");
            requireHash(alphaSignatureHash, "alphaSignatureHash");
            requireHash(inputHash, "inputHash");
            requireHash(hiddenTargetHash, "hiddenTargetHash");
        }
    }

    private record SplitCases(String name, List<CaseReference> cases) {
    }
}
