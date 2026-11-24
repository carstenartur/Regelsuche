package de.regelsuche.release;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.treewalk.FileTreeIterator;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.WorkingTreeOptions;
import org.eclipse.jgit.util.FS;

/** Read-only verification of frozen studies' registered source and protocol. */
public final class PolynomialArchiveProvenanceMain {
    private static final Set<String> IMPLEMENTATION = Set.of(".java", ".py", ".gradle", ".kts", ".sh", ".json",
        ".yaml", ".yml", ".xml", ".toml", ".properties");

    public static void main(String[] args) throws Exception {
        if (args.length == 1) System.out.println(verify(Path.of(args[0]).toRealPath()));
        else if (args.length == 2 && args[0].equals("schema-freeze"))
            System.out.println(schemaFreeze(Path.of(args[1]).toRealPath()));
        else throw new IllegalArgumentException("expected a registered source directory or schema-freeze directory");
    }

    static JsonNode schemaFreeze(Path root) throws Exception {
        var identity = verify(root);
        try (var files = new OwnedReleaseEvidenceFiles(root)) {
            var receipt = ReleaseEvidenceJson.parse(files.readBytes(PolynomialSourceProof.RECEIPT), "polynomial source witness");
            var verified = PolynomialSourceProof.verify(root, receipt, identity);
            verified.set("trackedChangesPresent", identity.required("trackedChangesPresent"));
            return verified;
        }
    }

    static JsonNode verify(Path root) throws IOException {
        var identity = QualityArchiveRegistration.identity(root);
        if (!BooleanNode.FALSE.equals(identity.path("trackedChangesPresent")))
            throw new IOException("native study requires unchanged registered source bytes and modes");
        try (var files = new OwnedReleaseEvidenceFiles(root)) {
            var registration = ReleaseEvidenceJson.parse(files.readBytes(QualityArchiveRegistration.RECEIPT), "source tree");
            requireRegisteredImplementation(root, registration);
        }
        return identity;
    }

    static void requireRegisteredImplementation(Path root, JsonNode registration) throws IOException {
        var tracked = new HashSet<String>();
        for (var member : registration.required("members")) tracked.add(member.required("path").textValue());
        var options = new Config().get(WorkingTreeOptions.KEY);
        try (var db = new InMemoryRepository(new DfsRepositoryDescription("study source ignore rules"));
                var walk = new TreeWalk(db)) {
            walk.addTree(new FileTreeIterator(root.toFile(), FS.DETECTED, options));
            while (walk.next()) {
                String name = walk.getPathString();
                if (name.equals(".git") || walk.getTree(0, FileTreeIterator.class).isEntryIgnored()) continue;
                if (walk.isSubtree()) {
                    if (!Set.of("build", "__pycache__").contains(walk.getNameString())) walk.enterSubtree();
                    continue;
                }
                if (tracked.contains(name) || name.endsWith(".pyc")) continue;
                boolean protectedPath = Set.of("gradle", "config").contains(Path.of(name).getName(0).toString());
                if (protectedPath || IMPLEMENTATION.stream().anyMatch(name::endsWith))
                    throw new IOException("unregistered implementation or configuration: " + name);
            }
        }
    }
}
