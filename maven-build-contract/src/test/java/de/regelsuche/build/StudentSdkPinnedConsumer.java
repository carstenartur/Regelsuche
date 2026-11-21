package de.regelsuche.build;

import static de.regelsuche.build.StudentSdkArchive.JSON;
import static de.regelsuche.build.StudentSdkArchive.canonicalJson;
import static de.regelsuche.build.StudentSdkArchive.regularFiles;
import static de.regelsuche.build.StudentSdkArchive.require;
import static de.regelsuche.build.StudentSdkArchive.sha256;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectInserter;

final class StudentSdkPinnedConsumer {
    private static final String COMMIT = "9917424453fd4382e8ce088c969902cbced64302";
    private static final String REPOSITORY = "https://github.com/carstenartur/primachsenraum";
    private static final String INVENTORY = """
        def expectedRegelsucheRuntimeModules = [
            'regelsuche-core',
            'regelsuche-egraph',
            'regelsuche-search',
            'regelsuche-validation',
            'regelsuche-discovery',
            'regelsuche-discovery-sdk'
        ] as Set""";
    private static final List<String> ADDITIONS =
        List.of("regelsuche-extension-api", "regelsuche-math-algorithms", "regelsuche-solver-ir");

    private StudentSdkPinnedConsumer() {}

    static Map<String, byte[]> files(Path source) throws IOException {
        byte[] manifestBytes = Files.readAllBytes(source.resolve("SOURCE.json"));
        var manifest = JSON.readTree(manifestBytes);
        require(COMMIT.equals(manifest.path("commit").textValue())
            && REPOSITORY.equals(manifest.path("repository").textValue()), "unexpected Primachsenraum source revision");

        var actual = new TreeMap<String, byte[]>();
        for (Path path : regularFiles(source, Set.of("build", "target", ".gradle"))) {
            actual.put(source.relativize(path).toString().replace(path.getFileSystem().getSeparator(), "/"),
                Files.readAllBytes(path));
        }
        var expected = new TreeMap<String, String>();
        manifest.required("files").properties().forEach(entry -> expected.put(entry.getKey(), entry.getValue().textValue()));
        actual.remove("SOURCE.json");
        require(actual.keySet().equals(expected.keySet()), "independent consumer source set differs from the pinned snapshot");
        try (var git = new ObjectInserter.Formatter()) {
            for (var file : expected.entrySet()) {
                String name = file.getKey();
                StudentSdkArchive.validateMember(name, "invalid consumer source path");
                require(git.idFor(Constants.OBJ_BLOB, actual.get(name)).name().equals(file.getValue()),
                    "independent consumer source differs from pinned Git blob: " + name);
            }
        }
        byte[] original = actual.get("build.gradle");
        require(original != null, "missing pinned consumer build.gradle");
        byte[] adapted = adapt(original);
        actual.put("build.gradle", adapted);
        actual.put("upstream-build.gradle", original);
        actual.put("SOURCE.json", manifestBytes);
        var receipt = new TreeMap<String, Object>();
        receipt.put("schema", "regelsuche.external-consumer-sdk-adaptation/v1");
        receipt.put("adaptationId", "discovery-scoped-proof-runtime-contract/v4");
        receipt.put("upstreamRepository", REPOSITORY);
        receipt.put("upstreamCommit", COMMIT);
        receipt.put("upstreamBuildGitBlob", expected.get("build.gradle"));
        receipt.put("upstreamBuildSha256", sha256(original));
        receipt.put("adaptedBuildSha256", sha256(adapted));
        receipt.put("addedRuntimeModules", ADDITIONS);
        receipt.put("sdkGroupId", "io.github.carstenartur");
        receipt.put("sourceManifestMeaning",
            "SOURCE.json identifies the upstream baseline, not the adapted build.gradle; original build retained as upstream-build.gradle");
        receipt.put("unchangedFiles", expected.keySet().stream().filter(name -> !name.equals("build.gradle")).toList());
        actual.put("SDK-ADAPTATION.json", canonicalJson(receipt));
        return actual;
    }

    private static byte[] adapt(byte[] original) throws IOException {
        String binary = new String(original, StandardCharsets.ISO_8859_1);
        String marker = "def expectedRegelsucheRuntimeModules";
        require(binary.indexOf(INVENTORY) >= 0
            && binary.indexOf(marker) == binary.lastIndexOf(marker),
            "upstream Discovery runtime inventory differs from exact pinned contract");
        String required = INVENTORY.replace("    'regelsuche-discovery-sdk'\n] as Set",
            "    'regelsuche-discovery-sdk',\n    '" + String.join("',\n    '", ADDITIONS) + "'\n] as Set");
        return binary.replace(INVENTORY, required)
            .replace("'de.regelsuche'", "'io.github.carstenartur'")
            .replace("de.regelsuche:regelsuche-", "io.github.carstenartur:regelsuche-")
            .getBytes(StandardCharsets.ISO_8859_1);
    }
}
