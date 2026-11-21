package de.regelsuche.release;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

/** Exact checkout metadata from Git objects and tracked worktree/index changes, with no native Git command. */
final class QualityCollectionCheckout {
    static ObjectNode identity(Path root) throws IOException, GitAPIException {
        Path gitPath = root.resolve(".git");
        if (!Files.exists(gitPath, LinkOption.NOFOLLOW_LINKS)) return QualityArchiveRegistration.identity(root);
        var builder = new FileRepositoryBuilder().setMustExist(true).setWorkTree(root.toFile()).findGitDir(root.toFile());
        try (var repo = builder.build(); var git = new Git(repo)) {
            if (!repo.getWorkTree().toPath().toRealPath().equals(root.toRealPath())) throw new IOException("not a checkout root");
            var commit = repo.parseCommit(repo.resolve(Constants.HEAD));
            return QualityAcceptanceCollection.JSON.createObjectNode()
                .put("revision", commit.name()).put("tree", commit.getTree().name())
                .put("trackedChangesPresent", git.status().call().hasUncommittedChanges());
        }
    }
}
