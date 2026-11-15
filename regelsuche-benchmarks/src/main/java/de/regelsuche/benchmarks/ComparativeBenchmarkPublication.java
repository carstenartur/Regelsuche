package de.regelsuche.benchmarks;

import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Stages and replaces a bundle while keeping the output directory itself in place. */
final class ComparativeBenchmarkPublication {
    private ComparativeBenchmarkPublication() {}

    static void replace(Path output, StageWriter writer) throws IOException {
        Files.createDirectories(output);
        // A single work directory excludes concurrent writers and lets all moves
        // remain on the output filesystem, including when it is a Docker mount.
        Path work = Files.createDirectory(output.resolve(".comparative-publication"));
        var completed = new ArrayList<Move>();
        try {
            Path staging = Files.createDirectory(work.resolve("next"));
            Path backup = Files.createDirectory(work.resolve("previous"));
            writer.write(staging);

            for (Path entry : children(output)) {
                if (!entry.equals(work)) {
                    move(entry, backup, completed);
                }
            }
            for (Path entry : children(staging)) {
                move(entry, output, completed);
            }
        } catch (IOException | RuntimeException failure) {
            if (restore(completed, failure)) {
                try {
                    deleteTree(work);
                } catch (IOException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            } else {
                failure.addSuppressed(new IOException("Recovery files retained under " + work));
            }
            throw failure;
        }
        deleteTree(work);
    }

    private static void move(Path from, Path directory, List<Move> completed) throws IOException {
        Path to = directory.resolve(from.getFileName());
        Files.move(from, to, ATOMIC_MOVE);
        completed.add(new Move(from, to));
    }

    private static boolean restore(List<Move> completed, Exception failure) {
        boolean restored = true;
        for (Move move : completed.reversed()) {
            try {
                Files.move(move.to(), move.from(), ATOMIC_MOVE);
            } catch (IOException | RuntimeException restoreFailure) {
                failure.addSuppressed(restoreFailure);
                restored = false;
            }
        }
        return restored;
    }

    private static List<Path> children(Path directory) throws IOException {
        try (var paths = Files.list(directory)) {
            return paths.sorted().toList();
        }
    }

    private static void deleteTree(Path directory) throws IOException {
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    @FunctionalInterface
    interface StageWriter {
        void write(Path staging) throws IOException;
    }

    private record Move(Path from, Path to) {}
}
