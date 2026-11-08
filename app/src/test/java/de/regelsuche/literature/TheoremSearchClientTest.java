package de.regelsuche.literature;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static de.regelsuche.literature.TheoremSearchClient.Status.CONFIRMATION_REQUIRED;
import static de.regelsuche.literature.TheoremSearchClient.Status.DISABLED;
import static de.regelsuche.literature.TheoremSearchClient.Status.FAILED;
import static de.regelsuche.literature.TheoremSearchClient.Status.RATE_LIMITED;
import static de.regelsuche.literature.TheoremSearchClient.Status.SUCCESS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TheoremSearchClientTest {
    private static final String EMPTY = """
            {"theorems":[]}
            """;
    private static final String THEOREM = """
            {"slogan_id":8291,"theorem_id":5104,"name":"Lemma 4.2",
             "body":"untrusted full text","slogan":"untrusted slogan","theorem_type":"Lemma",
             "link":"https://stacks.math.columbia.edu/tag/04YC","similarity":0.871,"score":0.871,
             "paper":{"source":"Stacks Project","title":"Stacks Project","primary_category":"math.AG",
                      "year":null,"citations":null,"journal_published":null}}
            """;
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<Request> received = new AtomicReference<>();
    private final AtomicReference<Response> reply = new AtomicReference<>();
    private final MutableClock clock = new MutableClock();
    private HttpServer server;
    private URI endpoint;
    private TheoremSearchClient client;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/search", exchange -> {
            try (exchange) {
                calls.incrementAndGet();
                received.set(new Request(exchange.getRequestMethod(),
                        exchange.getRequestHeaders().getFirst("Content-Type"),
                        new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                reply.get().send(exchange);
            }
        });
        server.start();
        endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/search");
        reply.set(exchange -> send(exchange, 200, EMPTY));
        client = new TheoremSearchClient(true, endpoint, clock);
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void neitherDisabledNorUnconfirmedLookupSendsAnything() {
        var disabled = new TheoremSearchClient(false, endpoint, clock);
        assertFalse(disabled.enabled());
        assertEquals(DISABLED, disabled.search("private rule", true).status());
        assertTrue(client.enabled());
        assertEquals(CONFIRMATION_REQUIRED, client.search("private rule", false).status());
        assertEquals(0, calls.get());
    }

    @Test
    void rejectsInvalidQueriesWithoutSendingAnything() {
        for (String query : new String[]{null, "", " \t\n", "q".repeat(501), "lemma \uD800", "\uDC00 lemma"}) {
            assertEquals(FAILED, client.search(query, true).status());
        }
        assertEquals(0, calls.get());
    }

    @Test
    void sendsTheExactConfirmedQueryOnceAndProjectsBibliographicMetadata() throws IOException {
        String query = "  Formel \\alpha: \"é𝛼\"\n ";
        reply.set(exchange -> send(exchange, 200, "{\"theorems\":[" + THEOREM + "]}"));

        var result = client.search(query, true);

        assertEquals(SUCCESS, result.status());
        assertEquals(List.of(new TheoremSearchClient.Hit(
                "Lemma 4.2", "https://stacks.math.columbia.edu/tag/04YC", "Stacks Project", "Stacks Project", null)),
                result.hits());
        assertThrows(UnsupportedOperationException.class, () -> result.hits().clear());
        assertEquals(1, calls.get());
        var request = received.get();
        assertEquals("POST", request.method());
        assertTrue(request.contentType().startsWith("application/json"));
        var payload = new ObjectMapper().readTree(request.body());
        assertEquals(2, payload.size());
        assertEquals(query, payload.path("query").textValue());
        assertEquals(5, payload.path("n_results").intValue());
    }

    @Test
    void acceptsOnlyBoundedBibliographicMetadataAndSafeLinks() {
        String hit = """
                {"name":"Proposition","link":"javascript:alert(1)",
                 "paper":{"title":"%s","source":"Journal","year":2025}}
                """.formatted("T".repeat(400));
        reply.set(exchange -> send(exchange, 200,
                "{\"theorems\":[" + String.join(",", Collections.nCopies(6, hit)) + "]}"));

        var result = client.search("confirmed", true);

        assertEquals(SUCCESS, result.status());
        assertEquals(5, result.hits().size());
        assertEquals(new TheoremSearchClient.Hit("Proposition", null, null, "Journal", 2025), result.hits().getFirst());
    }

    @Test
    void observesRetryAfterDeltaAcrossLaterSearches() {
        reply.set(exchange -> {
            exchange.getResponseHeaders().set("Retry-After", "12");
            send(exchange, 429, "private error response");
        });

        assertEquals(new TheoremSearchClient.Result(RATE_LIMITED, List.of(), 12),
                client.search("first", true));
        clock.advance(Duration.ofMillis(10_001));
        assertEquals(new TheoremSearchClient.Result(RATE_LIMITED, List.of(), 2),
                client.search("second", true));
        assertEquals(1, calls.get());

        clock.advance(Duration.ofMillis(1_999));
        reply.set(exchange -> send(exchange, 200, EMPTY));
        assertEquals(SUCCESS, client.search("third", true).status());
        assertEquals(2, calls.get());
    }

    @Test
    void observesRetryAfterHttpDate() {
        reply.set(exchange -> {
            exchange.getResponseHeaders().set("Retry-After", "Sun, 8 Nov 2026 12:00:35 GMT");
            send(exchange, 429, "");
        });

        assertEquals(35, client.search("first", true).retryAfterSeconds());
        clock.advance(Duration.ofSeconds(30));
        assertEquals(new TheoremSearchClient.Result(RATE_LIMITED, List.of(), 5),
                client.search("second", true));
        assertEquals(1, calls.get());
    }

    @Test
    void countsFailedAttemptsAgainstTheRollingBudget() {
        reply.set(exchange -> send(exchange, 503, "private error response"));

        for (int i = 0; i < 5; i++) {
            assertEquals(new TheoremSearchClient.Result(FAILED, List.of(), 0),
                    client.search("confirmed " + i, true));
        }
        assertEquals(new TheoremSearchClient.Result(RATE_LIMITED, List.of(), 3600),
                client.search("budget exhausted", true));
        assertEquals(5, calls.get());

        clock.advance(Duration.ofHours(1));
        reply.set(exchange -> send(exchange, 200, EMPTY));
        assertEquals(SUCCESS, client.search("budget available", true).status());
        assertEquals(6, calls.get());
    }

    @Test
    void doesNotWaitInQueueOrSendInParallel() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        reply.set(exchange -> {
            entered.countDown();
            try {
                if (release.await(5, TimeUnit.SECONDS)) {
                    send(exchange, 200, EMPTY);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        try (var executor = Executors.newSingleThreadExecutor()) {
            var first = executor.submit(() -> client.search("first", true));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertEquals(RATE_LIMITED, client.search("second", true).status());
                assertEquals(1, calls.get());
            } finally {
                release.countDown();
            }
            assertEquals(SUCCESS, first.get(5, TimeUnit.SECONDS).status());
            assertEquals(1, calls.get());
        }
    }

    @Test
    void rejectsRedirectWithoutSendingTheQueryToTheLocation() {
        var redirected = new AtomicInteger();
        server.createContext("/redirected", exchange -> {
            try (exchange) {
                redirected.incrementAndGet();
                send(exchange, 200, EMPTY);
            }
        });
        reply.set(exchange -> {
            exchange.getResponseHeaders().set("Location", endpoint.resolve("/redirected").toString());
            send(exchange, 307, "");
        });

        assertEquals(FAILED, client.search("confirmed", true).status());
        assertEquals(1, calls.get());
        assertEquals(0, redirected.get());
    }

    @Test
    void rejectsMalformedOrUnexpectedResponseEnvelopes() {
        for (String body : List.of("{", "[]", "{}", "{\"theorems\":{}}", EMPTY + "{}")) {
            reply.set(exchange -> send(exchange, 200, body));
            assertEquals(new TheoremSearchClient.Result(FAILED, List.of(), 0),
                    client.search("confirmed", true));
        }
        assertEquals(5, calls.get());
    }

    @Test
    void rejectsAnOversizeBodyWithoutRelyingOnContentLength() {
        reply.set(exchange -> {
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write((" ".repeat(300_000) + EMPTY).getBytes(StandardCharsets.UTF_8));
        });

        assertEquals(new TheoremSearchClient.Result(FAILED, List.of(), 0),
                client.search("confirmed", true));
        assertEquals(1, calls.get());
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    @FunctionalInterface
    private interface Response {
        void send(HttpExchange exchange) throws IOException;
    }

    private record Request(String method, String contentType, String body) {
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-11-08T12:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(now, zone);
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
