package de.regelsuche.plugin;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PluginDistributionTransportTest {
    @Test
    void boundsChunkedBytesAndNeverFollowsRedirects() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger destinationRequests = new AtomicInteger();
        server.createContext("/bytes", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(new byte[33]);
            exchange.close();
        });
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "/destination");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/destination", exchange -> {
            destinationRequests.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        URI origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        try (var transport = new PluginDistributionTransport(Set.of(origin),
                Duration.ofSeconds(2), Duration.ofSeconds(2), true, null)) {
            assertEquals(33, transport.download(origin.resolve("/bytes"), 33).length);
            assertThrows(java.io.IOException.class,
                () -> transport.download(origin.resolve("/bytes"), 32));
            assertThrows(java.io.IOException.class,
                () -> transport.download(origin.resolve("/redirect"), 32));
            assertEquals(0, destinationRequests.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsNonIdentityEncodingInEveryRepeatedHeaderAndCommaSeparatedValue() throws Exception {
        var encodings = List.of(List.of("identity", "gzip"), List.of("identity", "br"),
            List.of("identity, unknown"), List.of("identity", "identity, unknown"));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        for (int index = 0; index < encodings.size(); index++) {
            List<String> headers = encodings.get(index);
            server.createContext("/encoding-" + index, exchange -> {
                headers.forEach(value -> exchange.getResponseHeaders().add("Content-Encoding", value));
                exchange.sendResponseHeaders(200, 3);
                exchange.getResponseBody().write(new byte[] {1, 2, 3});
                exchange.close();
            });
        }
        server.start();
        URI origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        try (var transport = new PluginDistributionTransport(Set.of(origin),
                Duration.ofSeconds(2), Duration.ofSeconds(2), true, null)) {
            for (int index = 0; index < encodings.size(); index++) {
                URI uri = origin.resolve("/encoding-" + index);
                assertThrows(java.io.IOException.class, () -> transport.download(uri, 3), encodings.get(index).toString());
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void totalDeadlineIncludesAStalledBodyAfterHeaders() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        CountDownLatch release = new CountDownLatch(1);
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/stall", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(1);
            exchange.getResponseBody().flush();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        URI origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        try (var transport = new PluginDistributionTransport(Set.of(origin),
                Duration.ofSeconds(1), Duration.ofMillis(150), true, null)) {
            assertTimeoutPreemptively(Duration.ofSeconds(2), () ->
                assertThrows(java.io.IOException.class,
                    () -> transport.download(origin.resolve("/stall"), 32)));
        } finally {
            release.countDown();
            server.stop(0);
            executor.close();
        }
    }

    @Test
    void refusesUnlistedOriginsCredentialsFragmentsAndNonLoopbackHttp() throws Exception {
        URI origin = URI.create("https://example.invalid");
        try (var transport = new PluginDistributionTransport(Set.of(origin),
                Duration.ofMillis(100), Duration.ofMillis(100), false, null)) {
            for (String uri : new String[] {"file:///tmp/test", "http://127.0.0.1/test",
                    "https://other.invalid/test", "https://user@example.invalid/test",
                    "https://example.invalid/test#fragment"}) {
                assertThrows(SecurityException.class, () -> transport.download(URI.create(uri), 8));
            }
        }
        assertThrows(IllegalArgumentException.class, () -> new PluginDistributionTransport(
            Set.of(URI.create("http://example.invalid")), Duration.ofSeconds(1),
            Duration.ofSeconds(1), true, null));
    }
}
