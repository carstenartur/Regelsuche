package de.regelsuche.discovery.representation;

import static de.regelsuche.discovery.representation.RepresentationDiscoveryRunContractSupport.WORKSPACE_SCHEMA;
import static de.regelsuche.discovery.representation.RepresentationDiscoveryRunContractSupport.append;
import static de.regelsuche.discovery.representation.RepresentationDiscoveryRunContractSupport.requireSha256;
import static de.regelsuche.discovery.representation.RepresentationDiscoveryRunContractSupport.requireText;
import static de.regelsuche.discovery.representation.RepresentationDiscoveryRunContractSupport.sha256;

/** Exact repository and application revision used by a run. */
public record RepresentationDiscoveryRevisionEvidence(
    String repositoryCommit,
    String applicationRevision,
    String contentHash
) {
    public RepresentationDiscoveryRevisionEvidence {
        repositoryCommit = requireText(
            repositoryCommit, "repositoryCommit");
        if (!repositoryCommit.matches("[0-9a-f]{40}")) {
            throw new IllegalArgumentException(
                "repositoryCommit must be a lowercase Git commit SHA");
        }
        applicationRevision = requireText(
            applicationRevision, "applicationRevision");
        contentHash = requireSha256(contentHash, "contentHash");
        String expected = revisionHash(
            repositoryCommit, applicationRevision);
        if (!expected.equals(contentHash)) {
            throw new IllegalArgumentException(
                "revision evidence content hash mismatch");
        }
    }

    public static RepresentationDiscoveryRevisionEvidence create(
        String repositoryCommit,
        String applicationRevision
    ) {
        String commit = requireText(repositoryCommit, "repositoryCommit");
        String application = requireText(
            applicationRevision, "applicationRevision");
        return new RepresentationDiscoveryRevisionEvidence(
            commit,
            application,
            revisionHash(commit, application)
        );
    }

    private static String revisionHash(
        String repositoryCommit,
        String applicationRevision
    ) {
        StringBuilder descriptor = new StringBuilder();
        append(descriptor, WORKSPACE_SCHEMA + "/revision");
        append(descriptor, repositoryCommit);
        append(descriptor, applicationRevision);
        return sha256(descriptor.toString());
    }
}
