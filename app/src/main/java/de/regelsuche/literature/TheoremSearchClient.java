package de.regelsuche.literature;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Optional literature lookup. Each instance allows at most one request at a time and five attempts
 * in a rolling hour across all users of that instance. Only the explicitly confirmed query and
 * fixed result count are sent. Returned metadata is untrusted and is not proof evidence.
 */
public final class TheoremSearchClient {
    public static final URI ENDPOINT = URI.create("https://api.theoremsearch.com/search");
    /** Length in Java UTF-16 code units. */
    public static final int MAX_QUERY_CHARS = 500;
    public static final int RESULT_LIMIT = 5;

    private static final int ATTEMPTS_PER_WINDOW = 5;
    private static final Duration BUDGET_WINDOW = Duration.ofHours(1);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(8);
    private static final Duration DEFAULT_COOLDOWN = Duration.ofMinutes(1);
    private static final int MAX_RESPONSE_BYTES = 256 * 1024;
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private final boolean enabled;
    private final URI endpoint;
    private final Clock clock;
    private final HttpClient http;
    private final Object policyLock = new Object();
    private final Deque<Instant> attempts = new ArrayDeque<>();
    private Instant cooldownUntil = Instant.MIN;
    private boolean inFlight;

    public TheoremSearchClient(boolean enabled) {
        this(enabled, ENDPOINT, Clock.systemUTC(), true);
    }

    TheoremSearchClient(boolean enabled, URI endpoint, Clock clock) {
        this(enabled, endpoint, clock, false);
    }

    private TheoremSearchClient(boolean enabled, URI endpoint, Clock clock, boolean production) {
        this.enabled = enabled;
        this.endpoint = Objects.requireNonNull(endpoint);
        this.clock = Objects.requireNonNull(clock);
        if (!production && !("http".equals(endpoint.getScheme())
                && ("127.0.0.1".equals(endpoint.getHost()) || "[::1]".equals(endpoint.getHost()))
                && endpoint.getPort() > 0 && endpoint.getRawUserInfo() == null
                && endpoint.getRawQuery() == null && endpoint.getRawFragment() == null
                && "/search".equals(endpoint.getRawPath()))) {
            throw new IllegalArgumentException("The test endpoint must be a loopback search endpoint");
        }
        http = enabled ? HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .proxy(HttpClient.Builder.NO_PROXY)
                .version(HttpClient.Version.HTTP_1_1)
                .build() : null;
    }

    public boolean enabled() {
        return enabled;
    }

    public Result search(String query, boolean confirmed) {
        if (!enabled) {
            return empty(Status.DISABLED);
        }
        if (!confirmed) {
            return empty(Status.CONFIRMATION_REQUIRED);
        }
        if (!validQuery(query)) {
            return empty(Status.FAILED);
        }
        synchronized (policyLock) {
            Instant now = clock.instant();
            long wait = retryDelay(now);
            if (inFlight || wait > 0) {
                return limited(Math.max(wait, 1));
            }
            attempts.addLast(now);
            inFlight = true;
        }
        try {
            return performSearch(query);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return empty(Status.FAILED);
        } catch (IOException | ExecutionException | TimeoutException e) {
            return empty(Status.FAILED);
        } finally {
            synchronized (policyLock) {
                inFlight = false;
            }
        }
    }

    private Result performSearch(String query)
            throws IOException, InterruptedException, ExecutionException, TimeoutException {
        byte[] payload = JSON.writeValueAsBytes(JSON.createObjectNode()
                .put("query", query)
                .put("n_results", RESULT_LIMIT));
        // A transport-level replay cannot obtain the query body a second time.
        var available = new AtomicBoolean(true);
        var body = HttpRequest.BodyPublishers.ofInputStream(
                () -> available.getAndSet(false) ? new ByteArrayInputStream(payload) : null);
        var request = HttpRequest.newBuilder(endpoint)
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.fromPublisher(body, payload.length))
                .build();
        var pending = http.sendAsync(request, info -> info.statusCode() == 200
                ? new BoundedBodySubscriber()
                : HttpResponse.BodySubscribers.mapping(HttpResponse.BodySubscribers.ofInputStream(), stream -> {
                    try {
                        stream.close();
                    } catch (IOException ignored) {
                        // No error response content is needed.
                    }
                    return new byte[0];
                }));
        HttpResponse<byte[]> response;
        try {
            // Bound the entire exchange, including reading a successful response body.
            response = pending.get(REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException | InterruptedException e) {
            pending.cancel(true);
            throw e;
        }
        int status = response.statusCode();
        if (status == 429 || status == 503) {
            var retryAfter = response.headers().firstValue("Retry-After");
            synchronized (policyLock) {
                Instant now = clock.instant();
                Instant retry = retryAfter.map(value -> retryAfter(value, now)).orElse(null);
                if (retry != null || status == 429) {
                    cooldownUntil = retry == null ? now.plus(DEFAULT_COOLDOWN) : retry;
                }
                if (status == 429) {
                    return limited(retryDelay(now));
                }
            }
        }
        if (status != 200) {
            return empty(Status.FAILED);
        }
        return new Result(Status.SUCCESS, metadata(response.body()), 0);
    }

    private long retryDelay(Instant now) {
        while (!attempts.isEmpty() && !attempts.getFirst().plus(BUDGET_WINDOW).isAfter(now)) {
            attempts.removeFirst();
        }
        Instant until = cooldownUntil;
        if (attempts.size() >= ATTEMPTS_PER_WINDOW) {
            Instant budgetUntil = attempts.getFirst().plus(BUDGET_WINDOW);
            if (budgetUntil.isAfter(until)) {
                until = budgetUntil;
            }
        }
        if (!until.isAfter(now)) {
            return 0;
        }
        Duration remaining = Duration.between(now, until);
        return remaining.getSeconds() + (remaining.getNano() == 0 ? 0 : 1);
    }

    private static Instant retryAfter(String value, Instant now) {
        String header = value.strip();
        if (!header.isEmpty() && header.chars().allMatch(c -> c >= '0' && c <= '9')) {
            BigInteger seconds = new BigInteger(header);
            long maxSeconds = Duration.between(now, Instant.MAX).getSeconds();
            return now.plusSeconds(seconds.min(BigInteger.valueOf(maxSeconds)).longValueExact());
        }
        try {
            return ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static boolean validQuery(String query) {
        return query != null && query.length() <= MAX_QUERY_CHARS && validUnicode(query)
                && query.codePoints().anyMatch(c -> !Character.isWhitespace(c)
                        && !Character.isSpaceChar(c) && c != 0x85);
    }

    private static boolean validUnicode(String text) {
        return StandardCharsets.UTF_8.newEncoder().canEncode(text);
    }

    private static List<Hit> metadata(byte[] body) throws IOException {
        JsonNode root = JSON.readTree(body);
        if (root == null || !root.path("theorems").isArray()) {
            throw malformed();
        }
        var hits = new ArrayList<Hit>(RESULT_LIMIT);
        int inspected = 0;
        for (JsonNode theorem : root.path("theorems")) {
            if (inspected++ == RESULT_LIMIT) {
                break;
            }
            JsonNode paper = theorem.path("paper");
            if (!theorem.isObject() || (!paper.isMissingNode() && !paper.isNull() && !paper.isObject())) {
                throw malformed();
            }
            String name = text(theorem.path("name"), 240);
            String link = safeLink(text(theorem.path("link"), 2_048));
            String title = text(paper.path("title"), 360);
            String source = text(paper.path("source"), 160);
            Integer year = year(paper.path("year"));
            if (name != null || link != null || title != null || source != null) {
                hits.add(new Hit(name, link, title, source, year));
            }
        }
        return hits;
    }

    private static String text(JsonNode node, int maxLength) throws IOException {
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            throw malformed();
        }
        String value = node.textValue();
        return value.length() <= maxLength && !value.isBlank() && validUnicode(value)
                && value.codePoints().noneMatch(Character::isISOControl) ? value : null;
    }

    private static String safeLink(String link) {
        if (link != null) {
            try {
                URI uri = URI.create(link);
                if (("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                        && uri.getHost() != null && uri.getRawUserInfo() == null) {
                    return link;
                }
            } catch (IllegalArgumentException ignored) {
                // Keep other metadata even if the provider supplied an invalid link.
            }
        }
        return null;
    }

    private static Integer year(JsonNode node) throws IOException {
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (!node.isIntegralNumber()) {
            throw malformed();
        }
        return node.canConvertToInt() && node.intValue() > 0 && node.intValue() <= 9_999 ? node.intValue() : null;
    }

    private static IOException malformed() {
        return new IOException("Invalid literature response");
    }

    private static Result empty(Status status) {
        return new Result(status, List.of(), 0);
    }

    private static Result limited(long retryAfterSeconds) {
        return new Result(Status.RATE_LIMITED, List.of(), retryAfterSeconds);
    }

    public enum Status {
        DISABLED, CONFIRMATION_REQUIRED, SUCCESS, RATE_LIMITED, FAILED
    }

    public record Hit(String name, String link, String title, String source, Integer year) {
    }

    public record Result(Status status, List<Hit> hits, long retryAfterSeconds) {
        public Result {
            hits = List.copyOf(hits);
        }
    }

    private static final class BoundedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
        private Flow.Subscription subscription;
        private int remaining = MAX_RESPONSE_BYTES;
        private boolean exceeded;

        @Override
        public CompletionStage<byte[]> getBody() {
            return delegate.getBody();
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            delegate.onSubscribe(subscription);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            if (exceeded) {
                return;
            }
            for (ByteBuffer buffer : buffers) {
                remaining -= buffer.remaining();
                if (remaining < 0) {
                    exceeded = true;
                    subscription.cancel();
                    delegate.onError(new IOException("Literature response exceeded its size limit"));
                    return;
                }
            }
            delegate.onNext(buffers);
        }

        @Override
        public void onError(Throwable error) {
            if (!exceeded) {
                delegate.onError(error);
            }
        }

        @Override
        public void onComplete() {
            if (!exceeded) {
                delegate.onComplete();
            }
        }
    }
}
