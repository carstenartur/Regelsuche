package de.regelsuche.release;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.TreeWalk;

/** Portable Git commit plus tree witnesses. Source archives need neither a repository nor a Git executable. */
final class QualityArchiveRegistration {
    static final Path RECEIPT = Path.of("build/authority-evidence/quality-source-tree.json");

    static void write(Path root, Path output) throws IOException {
        var builder = new FileRepositoryBuilder().setWorkTree(root.toFile()).setMustExist(true).findGitDir(root.toFile());
        try (var db = builder.build(); var walk = new TreeWalk(db)) {
            if (!db.getWorkTree().toPath().toRealPath().equals(root.toRealPath())) throw new IOException("not a checkout root");
            var commit = db.parseCommit(db.resolve(Constants.HEAD));
            var receipt = QualityAcceptanceCollection.JSON.createObjectNode().put("schema", "regelsuche.quality.source-tree/v1")
                .put("revision", commit.name()).put("commit", Base64.getEncoder().encodeToString(db.open(commit).getBytes()));
            var members = receipt.putArray("members");
            walk.addTree(commit.getTree());
            walk.setRecursive(true);
            while (walk.next()) {
                var original = walk.getObjectId(0);
                var row = members.addObject().put("path", walk.getPathString()).put("mode", walk.getRawMode(0)).put("blob", original.name());
                if (walk.getAttributes().isSet("export-subst"))
                    row.put("template", Base64.getEncoder().encodeToString(db.open(original).getBytes()));
            }
            Files.createDirectories(output.toAbsolutePath().getParent());
            Files.writeString(output, receipt.toString() + "\n", StandardOpenOption.CREATE_NEW);
        }
    }

    static ObjectNode identity(Path root) throws IOException {
        JsonNode registration;
        try (var files = new OwnedReleaseEvidenceFiles(root)) {
            registration = ReleaseEvidenceJson.parse(files.readBytes(RECEIPT), RECEIPT.toString());
        }
        if (!"regelsuche.quality.source-tree/v1".equals(registration.path("schema").asText())) throw new IOException("source registration schema");
        var cache = DirCache.newInCore();
        var builder = cache.builder();
        byte[] rawCommit = registration.required("commit").binaryValue();
        try (var db = new InMemoryRepository(new DfsRepositoryDescription("archive identity")); var objects = db.newObjectInserter()) {
            var commit = RevCommit.parse(rawCommit);
            if (!objects.idFor(Constants.OBJ_COMMIT, rawCommit).name().equals(registration.required("revision").asText()))
                throw new IOException("registered source revision differs from its commit bytes");
            boolean changed = false;
            var distinct = new HashSet<String>();
            for (var row : registration.required("members")) {
                String name = row.required("path").textValue();
                Path member = Path.of(name);
                if (member.isAbsolute() || !member.equals(member.normalize()) || member.toString().isEmpty()
                        || member.startsWith("..") || !distinct.add(name)) throw new IOException("invalid source member " + name);
                var id = ObjectId.fromString(row.required("blob").textValue());
                var entry = new DirCacheEntry(name);
                var mode = FileMode.fromBits(row.required("mode").intValue());
                if (mode != FileMode.REGULAR_FILE && mode != FileMode.EXECUTABLE_FILE) throw new IOException("unregistered source file mode: " + name);
                entry.setFileMode(mode);
                entry.setObjectId(id);
                builder.add(entry);
                Path file = root.resolve(member);
                boolean regular = Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS);
                for (Path parent = file.getParent(); regular && !parent.equals(root); parent = parent.getParent())
                    regular = !Files.isSymbolicLink(parent);
                if (!regular || Files.isExecutable(file) != (mode == FileMode.EXECUTABLE_FILE)) changed = true;
                else if (!matches(file, id, row, objects, commit)) changed = true;
            }
            builder.finish();
            if (!cache.writeTree(objects).equals(commit.getTree())) throw new IOException("registered paths and file modes do not reconstruct the commit tree");
            return QualityAcceptanceCollection.JSON.createObjectNode().put("revision", commit.name())
                .put("tree", commit.getTree().name()).put("trackedChangesPresent", changed);
        }
    }

    private static boolean matches(Path file, ObjectId id, JsonNode row, ObjectInserter objects, RevCommit commit) throws IOException {
        try (var bytes = Files.newInputStream(file)) {
            if (objects.idFor(Constants.OBJ_BLOB, Files.size(file), bytes).equals(id)) return true;
        }
        if (row.has("template")) {
            byte[] template = row.required("template").binaryValue();
            if (!objects.idFor(Constants.OBJ_BLOB, template).equals(id)) throw new IOException("export substitution template differs from source blob");
            String expected = new String(template, StandardCharsets.UTF_8).replace("$Format:%H$", commit.name())
                .replace("$Format:%T$", commit.getTree().name());
            return Arrays.equals(expected.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(file));
        }
        if (file.getFileName().toString().endsWith(".bat")) {
            // The repository's .gitattributes requests CRLF for batch launchers.
            byte[] canonical = Files.readString(file).replace("\r\n", "\n").getBytes(StandardCharsets.UTF_8);
            return objects.idFor(Constants.OBJ_BLOB, canonical).equals(id);
        }
        return false;
    }
}
