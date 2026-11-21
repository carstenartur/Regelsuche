package de.regelsuche.build;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.core.util.Separators;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** Plan and verify the complete release transition in a private source projection before replacing checked-in bytes. */
final class ReleaseMetadataTransition {
    private static final String OPENAPI = "app/src/main/resources/web/openapi/openapi.json";
    private static final String ORCID = "https://orcid.org/0009-0005-1047-6381";
    private static final ObjectMapper JSON = new ObjectMapper();

    static void write(Path root, String version, LocalDate releaseDay) throws Exception {
        if (!version.matches("[0-9]+\\.[0-9]+\\.[0-9]+(?:-SNAPSHOT)?") || version.endsWith("-SNAPSHOT") != (releaseDay == null))
            throw new IllegalArgumentException("Version and release date disagree");
        Path lock = root.resolve("target/release-metadata.lock");
        MavenReleaseMetadataAlignmentTest.checkNoSymbolicPath(lock);
        Files.createDirectories(lock.getParent());
        try (var channel = FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
             var ignored = channel.lock()) {
            transition(root.toAbsolutePath().normalize(), version, releaseDay);
        }
    }

    private static void transition(Path root, String version, LocalDate date) throws Exception {
        String current = MavenReleaseMetadataAlignmentTest.oneVersion(Files.readString(root.resolve("release.properties")));
        var poms = MavenReleaseMetadataAlignmentTest.verify(root, current);
        var updates = new LinkedHashMap<Path, String>();
        for (var path : poms.stream().sorted().toList()) {
            String text = Files.readString(path);
            var pattern = Pattern.compile("(<artifactId>regelsuche-parent</artifactId>\\s*<version>)" + Pattern.quote(current) + "(</version>)");
            updates.put(root.relativize(path), pattern.matcher(text).replaceFirst("$1" + version + "$2"));
        }
        updates.put(Path.of("release.properties"), Files.readString(root.resolve("release.properties"))
            .replaceAll("(?m)^[ \\t]*version[ \\t]*=.*$", "version=" + version));
        String cff = Files.readString(root.resolve("CITATION.cff")).replaceAll("(?m)^version: .*$", "version: \"" + version + "\"");
        if (date == null) cff = cff.replaceAll("(?m)^date-released: .*\\n?", "");
        else if (Pattern.compile("(?m)^date-released: .*$").matcher(cff).find())
            cff = cff.replaceAll("(?m)^date-released: .*$", "date-released: \"" + date + "\"");
        else cff = cff + (cff.endsWith("\n") ? "" : "\n") + "date-released: \"" + date + "\"\n";
        updates.put(Path.of("CITATION.cff"), cff);
        updates.put(Path.of("CITATION.md"), citation(Files.readString(root.resolve("CITATION.md")), version, date));
        updates.put(Path.of(".zenodo.json"), json(root.resolve(".zenodo.json"), "publication_date", version, date));
        updates.put(Path.of("codemeta.json"), json(root.resolve("codemeta.json"), "datePublished", version, date));
        updates.put(Path.of(OPENAPI), Files.readString(root.resolve(OPENAPI))
            .replaceFirst("(?m)^(\\s*\"version\"\\s*:\\s*\")[^\"]*(\".*)$", "$1" + version + "$2"));

        Path stage = Files.createTempDirectory("regelsuche-release-transition-");
        try {
            for (var entry : updates.entrySet()) {
                Path staged = stage.resolve(entry.getKey());
                Files.createDirectories(staged.getParent());
                Files.writeString(staged, entry.getValue());
            }
            MavenReleaseMetadataAlignmentTest.verify(stage, version);
            replaceAll(root, updates);
        } finally {
            try (var files = Files.walk(stage)) {
                for (var path : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static String citation(String input, String version, LocalDate date) {
        String text = input.replaceAll("(Carsten Hammer\\. \\*\\*Regelsuche\\*\\*\\. Version )[0-9A-Za-z.-]+(\\. \\d{4}\\.)", "$1" + version + "$2")
            .replaceAll("(  version\\s+= \\{)[^}]+(\\},)", "$1" + version + "$2");
        String dateLine = "(?m)^  date\\s+= \\{[^}]+\\},$";
        if (date == null) text = text.replaceAll(dateLine + "\n", "");
        else if (Pattern.compile(dateLine).matcher(text).find()) text = text.replaceAll(dateLine, "  date         = {" + date + "},");
        else text = text.replaceAll("(?m)^(  version\\s+= \\{[^}]+\\},)$", "$1\n  date         = {" + date + "},");
        if (!text.contains("ORCID")) text = text.replace("## What to cite\n",
            "## Author identifier\n\nCarsten Hammer's ORCID iD is [" + ORCID + "](" + ORCID + ").\n\n## What to cite\n");
        if (!text.contains("  orcid")) text = text.replaceAll("(  author\\s+= \\{Hammer, Carsten\\},)",
            "$1\n  orcid        = {" + ORCID + "},");
        return text;
    }

    private static String json(Path path, String dateKey, String version, LocalDate date) throws IOException {
        ObjectNode document = (ObjectNode) JSON.readTree(path.toFile());
        document.put("version", version);
        if (date == null) document.remove(dateKey);
        else document.put(dateKey, date.toString());
        var indent = new DefaultIndenter("  ", "\n");
        var printer = new DefaultPrettyPrinter(Separators.createDefaultInstance()
            .withObjectFieldValueSpacing(Separators.Spacing.AFTER).withObjectEmptySeparator("").withArrayEmptySeparator(""))
            .withObjectIndenter(indent).withArrayIndenter(indent);
        return JSON.writer(printer).writeValueAsString(document) + "\n";
    }

    private static void replaceAll(Path root, Map<Path, String> updates) throws IOException {
        var originals = new LinkedHashMap<Path, String>();
        try {
            for (var entry : updates.entrySet()) {
                Path file = root.resolve(entry.getKey());
                String original = Files.readString(file);
                if (original.equals(entry.getValue())) continue;
                replace(file, entry.getValue());
                originals.put(file, original);
            }
        } catch (IOException failure) {
            for (var entry : new ArrayList<>(originals.entrySet()).reversed()) {
                try { replace(entry.getKey(), entry.getValue()); }
                catch (IOException rollback) { failure.addSuppressed(rollback); }
            }
            throw failure;
        }
    }

    private static void replace(Path target, String bytes) throws IOException {
        var staged = Files.createTempFile(target.getParent(), ".release-metadata-", ".tmp");
        try {
            Files.copy(target, staged, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            Files.writeString(staged, bytes);
            Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(staged); }
    }
}
