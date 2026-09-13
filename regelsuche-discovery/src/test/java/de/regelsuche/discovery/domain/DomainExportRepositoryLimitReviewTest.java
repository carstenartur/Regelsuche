package de.regelsuche.discovery.domain;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DomainExportRepositoryLimitReviewTest {
    @TempDir Path temporary;

    @Test void pendingDirectoriesDoNotRejectARealRetainedExportOrConsumeItsCapacity() throws Exception {
        Path source = temporary.resolve("source"), retained = temporary.resolve("retained");
        var repository = new DomainExportWorkspaceRepository(retained);
        new DomainDiscoveryExport().write(source, DomainExportWorkspaceTest.sequence("observed=1,4,9,16;holdout=25,36"));
        var first = repository.retain(new DomainDiscoveryExportVerifier().requireVerified(source));
        byte[] original = Files.readAllBytes(retained.resolve(first.runId().substring(7)).resolve("workspace.json"));
        for (int i = 0; i < DomainExportWorkspaceRepository.MAX_EXPORTS + 2; i++) {
            Files.createDirectory(retained.resolve(".pending-review-" + i));
        }
        var page = assertDoesNotThrow(() -> repository.list(0, 25));
        assertEquals(1, page.total());
        assertEquals(first.runId(), page.exports().getFirst().runId());
        new DomainDiscoveryExport().write(source, DomainExportWorkspaceTest.sequence("observed=1,4,9,16;holdout=26"));
        var second = assertDoesNotThrow(() -> repository.retain(new DomainDiscoveryExportVerifier().requireVerified(source)));
        assertNotEquals(first.runId(), second.runId());
        assertEquals(2, repository.list(0, 25).total());
        assertArrayEquals(original, Files.readAllBytes(retained.resolve(first.runId().substring(7)).resolve("workspace.json")));
        assertTrue(Files.isDirectory(retained.resolve(".pending-review-0")), "listing is not staging cleanup");
    }

    @Test void rejectsThe257thRetainedDirectoryBeforePagingOrDecoding() throws Exception {
        Path retained = Files.createDirectory(temporary.resolve("retained"));
        var repository = new DomainExportWorkspaceRepository(retained);
        // Directory-entry admission is checked even when the requested page decodes no payloads.
        for (int i = 0; i < DomainExportWorkspaceRepository.MAX_EXPORTS; i++) {
            Files.createDirectory(retained.resolve(String.format("%064x", i)));
        }
        assertEquals(DomainExportWorkspaceRepository.MAX_EXPORTS,
            repository.list(DomainExportWorkspaceRepository.MAX_EXPORTS, 1).total());
        Files.createDirectory(retained.resolve(String.format("%064x", DomainExportWorkspaceRepository.MAX_EXPORTS)));
        var failure = assertThrows(IllegalStateException.class,
            () -> repository.list(DomainExportWorkspaceRepository.MAX_EXPORTS + 1, 1));
        assertTrue(failure.getMessage().contains("limit exceeded"));
        try (var entries = Files.list(retained)) {
            assertEquals(DomainExportWorkspaceRepository.MAX_EXPORTS + 1, entries.count(), "rejection must not delete retained entries");
        }
    }
}
