package de.regelsuche.discovery.representation;

import static de.regelsuche.discovery.representation
    .RepresentationDiscoveryRunContractSupport.append;
import static de.regelsuche.discovery.representation
    .RepresentationDiscoveryRunContractSupport.requireSha256;
import static de.regelsuche.discovery.representation
    .RepresentationDiscoveryRunContractSupport.requireText;
import static de.regelsuche.discovery.representation
    .RepresentationDiscoveryRunContractSupport.sha256;

import de.regelsuche.json.JsonWriter;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * Content-addressed localization of representation-detection failure stages.
 *
 * <p>The audit is bound to an existing immutable representation-discovery run
 * workspace. It does not create a competing run identity.</p>
 */
public record RepresentationSalienceAudit(
    String schema,
    String studyId,
    String repositoryRevision,
    String runWorkspaceHash,
    String informationBoundaryHash,
    List<RepresentationSalienceCaseAudit> cases,
    RepresentationSalienceSummary summary,
    String claimBoundary,
    String contentHash
) {
    public static final String SCHEMA =
        "regelsuche.representation-salience-audit/v1";
    public static final String CLAIM_BOUNDARY =
        "Bounded target-free representation detection recall and failure-stage "
            + "localization; not global reachability, universal "
            + "interestingness, external novelty or representation optimality.";

    public RepresentationSalienceAudit {
        schema = requireText(schema, "schema");
        if (!SCHEMA.equals(schema)) {
            throw new IllegalArgumentException(
                "unsupported salience-audit schema"
            );
        }
        studyId = requireText(studyId, "studyId");
        repositoryRevision = requireText(
            repositoryRevision,
            "repositoryRevision"
        );
        runWorkspaceHash = requireSha256(
            runWorkspaceHash,
            "runWorkspaceHash"
        );
        informationBoundaryHash = requireSha256(
            informationBoundaryHash,
            "informationBoundaryHash"
        );
        cases = orderedCases(cases);
        RepresentationSalienceSummary expectedSummary =
            RepresentationSalienceSummary.derive(cases);
        summary = Objects.requireNonNull(summary, "summary");
        if (!summary.equals(expectedSummary)) {
            throw new IllegalArgumentException(
                "salience-audit summary differs from cases"
            );
        }
        claimBoundary = requireText(claimBoundary, "claimBoundary");
        if (!CLAIM_BOUNDARY.equals(claimBoundary)) {
            throw new IllegalArgumentException(
                "unsupported salience-audit claim boundary"
            );
        }
        contentHash = requireSha256(contentHash, "contentHash");
        String expected = artifactHash(
            studyId,
            repositoryRevision,
            runWorkspaceHash,
            informationBoundaryHash,
            cases,
            summary
        );
        if (!expected.equals(contentHash)) {
            throw new IllegalArgumentException(
                "salience-audit content hash mismatch"
            );
        }
    }

    /** Creates an audit bound to an existing run and information boundary. */
    public static RepresentationSalienceAudit create(
        String studyId,
        String repositoryRevision,
        String runWorkspaceHash,
        String informationBoundaryHash,
        List<RepresentationSalienceCaseAudit> cases
    ) {
        String retainedStudyId = requireText(studyId, "studyId");
        String retainedRevision = requireText(
            repositoryRevision,
            "repositoryRevision"
        );
        String retainedWorkspace = requireSha256(
            runWorkspaceHash,
            "runWorkspaceHash"
        );
        String retainedBoundary = requireSha256(
            informationBoundaryHash,
            "informationBoundaryHash"
        );
        List<RepresentationSalienceCaseAudit> retainedCases = orderedCases(
            cases
        );
        RepresentationSalienceSummary summary =
            RepresentationSalienceSummary.derive(retainedCases);
        String contentHash = artifactHash(
            retainedStudyId,
            retainedRevision,
            retainedWorkspace,
            retainedBoundary,
            retainedCases,
            summary
        );
        return new RepresentationSalienceAudit(
            SCHEMA,
            retainedStudyId,
            retainedRevision,
            retainedWorkspace,
            retainedBoundary,
            retainedCases,
            summary,
            CLAIM_BOUNDARY,
            contentHash
        );
    }

    public String toCanonicalJson() {
        return new JsonWriter().beginObject()
            .property("schema", schema)
            .property("studyId", studyId)
            .property("repositoryRevision", repositoryRevision)
            .property("runWorkspaceHash", runWorkspaceHash)
            .property("informationBoundaryHash", informationBoundaryHash)
            .array("cases", array -> cases.forEach(value ->
                array.objectValue(value::writeJson)))
            .object("summary", summary::writeJson)
            .property("claimBoundary", claimBoundary)
            .property("contentHash", contentHash)
            .endObject()
            .toString();
    }

    private static List<RepresentationSalienceCaseAudit> orderedCases(
        List<RepresentationSalienceCaseAudit> cases
    ) {
        List<RepresentationSalienceCaseAudit> result = List.copyOf(
            Objects.requireNonNull(cases, "cases")
        ).stream()
            .map(value -> Objects.requireNonNull(value, "case"))
            .sorted(Comparator.comparing(
                RepresentationSalienceCaseAudit::caseId))
            .toList();
        if (result.isEmpty()) {
            throw new IllegalArgumentException("audit cases must not be empty");
        }
        if (new HashSet<>(result.stream()
                .map(RepresentationSalienceCaseAudit::caseId)
                .toList()).size() != result.size()) {
            throw new IllegalArgumentException("audit case IDs must be unique");
        }
        return result;
    }

    private static String artifactHash(
        String studyId,
        String repositoryRevision,
        String runWorkspaceHash,
        String informationBoundaryHash,
        List<RepresentationSalienceCaseAudit> cases,
        RepresentationSalienceSummary summary
    ) {
        StringBuilder descriptor = new StringBuilder();
        append(descriptor, SCHEMA);
        append(descriptor, studyId);
        append(descriptor, repositoryRevision);
        append(descriptor, runWorkspaceHash);
        append(descriptor, informationBoundaryHash);
        append(descriptor, Integer.toString(cases.size()));
        cases.forEach(value -> value.appendIdentity(descriptor));
        summary.appendIdentity(descriptor);
        append(descriptor, CLAIM_BOUNDARY);
        return sha256(descriptor.toString());
    }
}
