package de.regelsuche.web;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpServer;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class CnfCountingRouteTest {
    @Test void aBlockedResponseWriterRetainsAdmissionAfterComputation() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var route = new CnfCountingRoute();
        String first = "/" + UUID.randomUUID();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var context = server.createContext("/", exchange -> route.handle(exchange,
            exchange.getRequestURI().getPath().substring(1), 32768));
        context.getFilters().add(Filter.beforeHandler("Hold the first response body", exchange -> {
            if (!exchange.getRequestURI().getPath().equals(first)) return;
            exchange.setStreams(null, new FilterOutputStream(exchange.getResponseBody()) {
                @Override public void write(byte[] data, int offset, int length) throws IOException {
                    entered.countDown();
                    try {
                        if (!release.await(10, TimeUnit.SECONDS)) throw new IOException("response latch timed out");
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IOException(interrupted);
                    }
                    out.write(data, offset, length);
                }
            });
        }));
        server.start();
        try (var client = HttpClient.newHttpClient()) {
            var base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            var payload = HttpRequest.BodyPublishers.ofString("""
                {"variableCount":3,"clauses":[],"fixed":[],"maximumWork":10000}
                """);
            var pending = client.sendAsync(HttpRequest.newBuilder(base.resolve(first)).POST(payload).build(),
                HttpResponse.BodyHandlers.ofString());
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                var cancel = HttpRequest.newBuilder(base.resolve(first + "/cancel")).POST(HttpRequest.BodyPublishers.noBody()).build();
                assertEquals(404, client.send(cancel, HttpResponse.BodyHandlers.discarding()).statusCode());
                var second = HttpRequest.newBuilder(base.resolve("/" + UUID.randomUUID())).POST(payload).build();
                assertEquals(429, client.send(second, HttpResponse.BodyHandlers.discarding()).statusCode());
            } finally { release.countDown(); }
            assertEquals(200, pending.get(5, TimeUnit.SECONDS).statusCode());
            var next = HttpRequest.newBuilder(base.resolve("/" + UUID.randomUUID())).POST(payload).build();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            int status;
            do {
                status = client.send(next, HttpResponse.BodyHandlers.discarding()).statusCode();
            } while (status == 429 && System.nanoTime() < deadline);
            assertEquals(200, status);
        } finally { release.countDown(); server.stop(0); }
    }
}
