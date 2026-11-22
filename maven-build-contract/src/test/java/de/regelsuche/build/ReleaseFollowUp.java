package de.regelsuche.build;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.submodule.SubmoduleWalk.IgnoreSubmoduleMode;

final class ReleaseFollowUp {
    static boolean prepare(Path root, String released, String next) throws Exception {
        if (released == null || !released.matches("[0-9]+\\.[0-9]+\\.[0-9]+")
                || next == null || !next.matches("[0-9]+\\.[0-9]+\\.[0-9]+-SNAPSHOT"))
            throw new IllegalArgumentException("Expected a release and a next SNAPSHOT");
        if (!Files.isDirectory(root.resolve(".git"), LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Follow-up requires its own checkout");
        try (var repository = new FileRepositoryBuilder().setGitDir(root.resolve(".git").toFile()).setMustExist(true).build();
             var git = new Git(repository)) {
            if (!repository.getWorkTree().toPath().toRealPath().equals(root.toRealPath()))
                throw new IOException("Follow-up checkout root differs");
            if (!git.status().setIgnoreSubmodules(IgnoreSubmoduleMode.NONE).call().isClean())
                throw new IOException("Follow-up checkout has pending changes");
        }
        String current = MavenReleaseMetadataAlignmentTest.oneVersion(Files.readString(root.resolve("release.properties")));
        if (current.equals(next) || !current.equals(released + "-SNAPSHOT")) {
            System.out.println("Main is at " + current + "; no follow-up change required");
            return false;
        }
        ReleaseMetadataTransition.write(root, next, null);
        return true;
    }
}
