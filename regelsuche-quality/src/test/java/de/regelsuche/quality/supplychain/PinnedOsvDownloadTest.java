package de.regelsuche.quality.supplychain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PinnedOsvDownloadTest {
    @TempDir Path root;

    @Test
    void stalledDownloadBodyCannotOutliveTheCompleteDownloadDeadline() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        CountDownLatch release = new CountDownLatch(1);
        var guard = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        server.createContext("/scanner", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(new byte[] {1});
            exchange.getResponseBody().flush();
            try { release.await(); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        // A separate bounded producer prevents the deliberately broken implementation from hanging
        // the test JVM. Its five-second release is much later than the requested 300 ms deadline.
        guard.schedule(release::countDown, 5, java.util.concurrent.TimeUnit.SECONDS);
        try {
            var manifest = SupplyChainJson.JSON.createObjectNode();
            manifest.put("url", "http://127.0.0.1:" + server.getAddress().getPort() + "/scanner")
                .put("bytes", 4).put("sha256", "sha256:" + "0".repeat(64));
            Path binary = root.resolve("cache/osv-scanner");
            long started = System.nanoTime();
            assertThrows(Exception.class, () -> PinnedOsvScanner.download(root, manifest, binary, Duration.ofMillis(300), null));
            assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(3)) < 0);
            assertFalse(Files.exists(binary));
        } finally {
            release.countDown();
            guard.shutdownNow();
            server.stop(0);
        }
    }

    @Test
    void unavailableBrowserRouteUsesBoundAssetIdAndRechecksDownloadedBytes() throws Exception {
        byte[] payload = "fixed scanner bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var manifest = SupplyChainJson.JSON.createObjectNode();
        manifest.put("url", "https://github.com/google/osv-scanner/releases/download/v2.5.1/osv-scanner_linux_amd64")
            .put("bytes", payload.length).put("sha256", SupplyChainJson.hash(payload));
        var asset = SupplyChainJson.JSON.createObjectNode();
        asset.put("browser_download_url", manifest.get("url").textValue()).put("digest", SupplyChainJson.hash(payload))
            .put("size", payload.length).put("id", 123456);
        var release = SupplyChainJson.JSON.createObjectNode();
        release.putArray("assets").add(asset);
        assertEquals(URI.create("https://api.github.com/repos/google/osv-scanner/releases/assets/123456"),
            PinnedOsvScanner.assetApi(manifest, release));
        asset.put("size", payload.length + 1);
        assertThrows(Exception.class, () -> PinnedOsvScanner.assetApi(manifest, release));
        asset.put("size", payload.length);
        release.withArray("assets").add(asset.deepCopy());
        assertThrows(Exception.class, () -> PinnedOsvScanner.assetApi(manifest, release));

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/browser", exchange -> { exchange.sendResponseHeaders(503, -1); exchange.close(); });
        server.createContext("/api", exchange -> {
            if (!"application/octet-stream".equals(exchange.getRequestHeaders().getFirst("Accept"))) {
                exchange.sendResponseHeaders(406, -1);
            } else {
                exchange.sendResponseHeaders(200, payload.length);
                exchange.getResponseBody().write(payload);
            }
            exchange.close();
        });
        server.start();
        try {
            URI browser = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/browser");
            Path binary = root.resolve("cache/osv-scanner");
            manifest.put("url", browser.toString());
            PinnedOsvScanner.download(root, manifest, binary, Duration.ofSeconds(3), browser.resolve("/api"));
            assertEquals(SupplyChainJson.hash(payload), SupplyChainJson.hash(binary));
            assertTrue(Files.isExecutable(binary));
        } finally { server.stop(0); }
    }

    @Test
    void oversizedResponseNeverBecomesAnExecutableCacheEntry() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/scanner", exchange -> {
            byte[] payload = new byte[65536];
            exchange.sendResponseHeaders(200, payload.length);
            try (var body = exchange.getResponseBody()) { body.write(payload); }
        });
        server.start();
        try {
            var manifest = SupplyChainJson.JSON.createObjectNode();
            manifest.put("url", "http://127.0.0.1:" + server.getAddress().getPort() + "/scanner")
                .put("bytes", 4).put("sha256", "sha256:" + "0".repeat(64));
            Path binary = root.resolve("cache/osv-scanner");
            assertThrows(Exception.class, () -> PinnedOsvScanner.download(root, manifest, binary, Duration.ofSeconds(2), null));
            assertFalse(Files.exists(binary));
            try (var paths = Files.list(binary.getParent())) { assertTrue(paths.findAny().isEmpty()); }
        } finally {
            server.stop(0);
        }
    }
}
