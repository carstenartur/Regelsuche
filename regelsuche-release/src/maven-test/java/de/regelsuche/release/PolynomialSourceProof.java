package de.regelsuche.release;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.TreeWalk;

/** Original commit and tree objects prove the registered protocols even in a Git-free source archive. */
final class PolynomialSourceProof {
    static final String PILOT = "476d41e1f3fecdcff9b4ccba45b13aa8a9265542";
    static final String TYPED = "c7f0f6cfc69e1b45b37497109bf5a11686d08f9a";
    static final String SCHEMA = "5ce2980fe68eb78e352f262934cf338f002a72f4";
    static final List<String> FREEZES = List.of(PILOT, TYPED, SCHEMA);
    static final String PROTOCOL = "config/benchmarks/learned-schema-efficiency-v2.json";
    static final String SCHEMA_HASH = "7e9b51c8f4c66655489350ef4be982411b6ab5ae0585ddb8b725b1ef15d7113c";
    static final Path ORIGINAL = Path.of("config/benchmarks/polynomial-freeze-source-objects.json.gz");
    static final Path RECEIPT = Path.of("build/authority-evidence/polynomial-source.json");
    private static final String FORMAT = "regelsuche.polynomial-source-witness/v1";

    static ObjectNode prepare(Path root) throws Exception {
        if (!Files.exists(root.resolve(".git"))) {
            try (var files = new OwnedReleaseEvidenceFiles(root)) {
                return (ObjectNode) ReleaseEvidenceJson.parse(files.readBytes(RECEIPT), RECEIPT.toString());
            }
        }
        try (var source = new FileRepositoryBuilder().setWorkTree(root.toFile()).setMustExist(true).findGitDir(root.toFile()).build();
                var original = original(root)) {
            if (!source.getWorkTree().toPath().toRealPath().equals(root.toRealPath())) throw new IOException("not a checkout root");
            var head = source.resolve(Constants.HEAD);
            var receipt = QualityAcceptanceCollection.JSON.createObjectNode().put("schema", FORMAT).put("revision", head.name());
            var extra = receipt.putObject("objects");
            var paths = paths(head, source, original);
            for (var id : paths.stream().sorted().toList()) {
                if (!original.getObjectDatabase().has(id)) {
                    byte[] raw = source.open(id, Constants.OBJ_COMMIT).getBytes();
                    extra.putObject(id.name()).put("type", "commit").put("data", Base64.getEncoder().encodeToString(raw));
                }
            }
            return receipt;
        }
    }

    static ObjectNode verify(Path root, JsonNode receipt, JsonNode checkout) throws Exception {
        if (!BooleanNode.FALSE.equals(checkout.path("trackedChangesPresent")))
            throw new IOException("polynomial proof requires an unchanged tracked source");
        if (!FORMAT.equals(receipt.path("schema").asText()) || !checkout.required("revision").equals(receipt.required("revision")))
            throw new IOException("polynomial source witness does not identify the current source");
        try (var db = original(root)) {
            insert(db, receipt.required("objects"));
            var head = ObjectId.fromString(receipt.required("revision").asText());
            byte[] commitBytes = db.open(head, Constants.OBJ_COMMIT).getBytes();
            var headCommit = RevCommit.parse(commitBytes);
            if (!headCommit.getTree().name().equals(checkout.required("tree").textValue())) throw new IOException("source tree differs");
            paths(head, db, db);
            var schema = RevCommit.parse(db.open(ObjectId.fromString(SCHEMA), Constants.OBJ_COMMIT).getBytes());
            try (var tree = TreeWalk.forPath(db, PROTOCOL, schema.getTree())) {
                if (tree == null) throw new IOException("registered source protocol absent from frozen tree");
                byte[] original = db.open(tree.getObjectId(0), Constants.OBJ_BLOB).getBytes();
                String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(original));
                if (!SCHEMA_HASH.equals(hash)) throw new IOException("historical registered protocol hash differs");
                var verified = QualityAcceptanceCollection.JSON.createObjectNode().put("revision", head.name())
                    .put("tree", headCommit.getTree().name()).put("registeredProtocolSha256", hash);
                var ancestors = verified.putObject("ancestors");
                for (String freeze : FREEZES) ancestors.put(freeze, true);
                return verified;
            }
        }
    }

    private static InMemoryRepository original(Path root) throws Exception {
        var db = new InMemoryRepository(new DfsRepositoryDescription("registered polynomial experiments"));
        try (var files = new OwnedReleaseEvidenceFiles(root);
                var compressed = new GZIPInputStream(new ByteArrayInputStream(files.readBytes(ORIGINAL)))) {
            var seed = ReleaseEvidenceJson.parse(compressed.readAllBytes(), ORIGINAL.toString());
            if (!"regelsuche.polynomial-original-source-objects/v1".equals(seed.path("schema").asText()))
                throw new IOException("unrecognized polynomial source registration");
            insert(db, seed.required("objects"));
            return db;
        } catch (Exception e) { db.close(); throw e; }
    }

    private static void insert(Repository database, JsonNode objects) throws IOException {
        if (!objects.isObject()) throw new IOException("missing source objects");
        try (var inserter = database.newObjectInserter()) {
            for (var entry : objects.properties()) {
                var row = entry.getValue();
                int type = switch (row.required("type").asText()) {
                    case "commit" -> Constants.OBJ_COMMIT;
                    case "tree" -> Constants.OBJ_TREE;
                    case "blob" -> Constants.OBJ_BLOB;
                    default -> throw new IOException("unsupported source object type");
                };
                var id = inserter.insert(type, row.required("data").binaryValue());
                if (!id.name().equals(entry.getKey())) throw new IOException("source object byte identity differs");
            }
            inserter.flush();
        }
    }

    /** A witnessed path suffices. Missing parents on other merge branches carry no authority. */
    private static Set<ObjectId> paths(ObjectId head, Repository source, Repository original) throws IOException {
        Map<ObjectId,ObjectId> child = new HashMap<>();
        var queue = new ArrayDeque<ObjectId>();
        var pending = new HashSet<>(FREEZES);
        child.put(head, null);
        queue.add(head);
        while (!queue.isEmpty() && !pending.isEmpty()) {
            var current = queue.remove();
            RevCommit commit;
            try {
                var database = original.getObjectDatabase().has(current) ? original : source;
                commit = RevCommit.parse(database.open(current, Constants.OBJ_COMMIT).getBytes());
            } catch (MissingObjectException missing) { continue; }
            pending.remove(current.name());
            for (var parent : commit.getParents()) {
                if (child.containsKey(parent)) continue;
                child.put(parent.copy(), current);
                queue.add(parent.copy());
            }
        }
        if (!pending.isEmpty()) throw new IOException("unproved original protocol ancestry: " + pending);
        var connected = new HashSet<ObjectId>();
        for (String freeze : FREEZES) {
            ObjectId ancestor = ObjectId.fromString(freeze);
            while (ancestor != null && connected.add(ancestor)) ancestor = child.get(ancestor);
        }
        return connected;
    }
}
