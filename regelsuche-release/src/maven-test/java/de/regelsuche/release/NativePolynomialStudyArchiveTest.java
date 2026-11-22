package de.regelsuche.release;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NativePolynomialStudyArchiveTest {
    @Test void extractsRegularEvidence(@TempDir Path root) throws Exception {
        Path archive = root.resolve("evidence.tgz");
        try (var tar = output(archive)) {
            add(tar, "native-polynomial-comparison/rows.json");
        }
        Path output = NativePolynomialComparisonContainerIT.extract(archive, root.resolve("result"));
        assertEquals("[]\n", Files.readString(output.resolve("rows.json")));
    }

    @Test void refusesAliasedDuplicateAndLinkMembers(@TempDir Path root) throws Exception {
        for (String invalid : new String[]{"../outside", "other/../native-polynomial-comparison/different.json",
                "native-polynomial-comparison/rows.json", "native-polynomial-comparison/link"}) {
            Path directory = Files.createTempDirectory(root, "tar-");
            Path archive = directory.resolve("evidence.tgz");
            try (var tar = output(archive)) {
                add(tar, "native-polynomial-comparison/rows.json");
                if (invalid.endsWith("/link")) {
                    var link = new TarArchiveEntry(invalid, org.apache.commons.compress.archivers.tar.TarConstants.LF_SYMLINK);
                    link.setLinkName("rows.json");
                    tar.putArchiveEntry(link);
                    tar.closeArchiveEntry();
                } else {
                    add(tar, invalid);
                }
            }
            assertThrows(IOException.class,
                () -> NativePolynomialComparisonContainerIT.extract(archive, directory.resolve("result")), invalid);
            assertFalse(Files.exists(directory.resolve("outside")), invalid);
        }
    }

    @Test void rejectsTruncatedArchiveAgainstWorkerDigest(@TempDir Path root) throws Exception {
        Path archive = Files.writeString(root.resolve("evidence.tgz"), "abc");
        String digest = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
        NativePolynomialComparisonContainerIT.verifyArchiveDigest(archive, digest);
        Files.writeString(archive, "ab");
        assertThrows(IOException.class, () -> NativePolynomialComparisonContainerIT.verifyArchiveDigest(archive, digest));
    }

    private static TarArchiveOutputStream output(Path archive) throws IOException {
        return new TarArchiveOutputStream(new GZIPOutputStream(Files.newOutputStream(archive)));
    }

    private static void add(TarArchiveOutputStream tar, String name) throws IOException {
        var member = new TarArchiveEntry(name);
        var data = "[]\n".getBytes(StandardCharsets.UTF_8);
        member.setSize(data.length);
        tar.putArchiveEntry(member);
        tar.write(data);
        tar.closeArchiveEntry();
    }
}
