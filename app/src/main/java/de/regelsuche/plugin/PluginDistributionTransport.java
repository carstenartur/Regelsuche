package de.regelsuche.plugin;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import javax.net.ssl.SSLContext;

/** Bounded, allowlisted retrieval. It never interprets a redirect or executes downloaded bytes. */
public final class PluginDistributionTransport implements AutoCloseable {
    private final Set<String> origins;
    private final boolean allowLoopbackHttp;
    private final Duration requestTimeout;
    private final HttpClient client;

    /**
     * HTTPS uses the JVM trust roots unless explicit operator TLS roots are supplied.
     * Plain HTTP is restricted to literal loopback addresses and an explicit opt-in;
     * the package client still requires HTTPS URIs from the unchanged index contract.
     */
    public PluginDistributionTransport(Set<URI> origins, Duration connectTimeout,
            Duration requestTimeout, boolean allowLoopbackHttp, SSLContext tlsContext) {
        this.allowLoopbackHttp = allowLoopbackHttp;
        positive(connectTimeout);
        positive(requestTimeout);
        this.requestTimeout = requestTimeout;
        if (Objects.requireNonNull(origins, "origins").isEmpty()) {
            throw new IllegalArgumentException("at least one allowed origin is required");
        }
        this.origins = origins.stream().map(uri -> {
            if (!validUri(uri) || (uri.getRawPath() != null && !uri.getRawPath().isEmpty()
                    && !uri.getRawPath().equals("/")) || uri.getRawQuery() != null) {
                throw new IllegalArgumentException("invalid allowed transport origin: " + uri);
            }
            return origin(uri);
        }).collect(Collectors.toUnmodifiableSet());
        var builder = HttpClient.newBuilder().connectTimeout(connectTimeout)
            .followRedirects(HttpClient.Redirect.NEVER);
        if (tlsContext != null) {
            builder.sslContext(tlsContext);
        }
        client = builder.build();
    }

    public byte[] download(URI uri, long maximumBytes) throws IOException {
        if (maximumBytes < 1 || maximumBytes > Integer.MAX_VALUE - 8) {
            throw new IllegalArgumentException("maximumBytes must be a positive bounded array size");
        }
        if (!validUri(uri) || !origins.contains(origin(uri))) {
            throw new SecurityException("transport URI is outside the allowed origins: " + uri);
        }
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(requestTimeout)
            .header("Accept-Encoding", "identity").GET().build();
        var future = client.sendAsync(request, response -> {
            String rejection = response.statusCode() != 200
                ? "transport requires HTTP 200; received " + response.statusCode() : null;
            if (response.headers().allValues("Content-Encoding").stream()
                    .flatMap(value -> Arrays.stream(value.split(",", -1)))
                    .anyMatch(value -> !value.trim().equalsIgnoreCase("identity"))) {
                rejection = "encoded transport response is unsupported";
            }
            try {
                if (response.headers().firstValueAsLong("Content-Length").orElse(0) > maximumBytes) {
                    rejection = "transport response exceeds byte limit";
                }
            } catch (NumberFormatException malformed) {
                rejection = "invalid transport content length";
            }
            return new BoundedBody((int) maximumBytes, rejection);
        });
        try {
            // HttpRequest.timeout alone must not be relied on to bound a streaming body.
            return future.get(requestTimeout.toNanos(), TimeUnit.NANOSECONDS).body();
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new IOException("transport interrupted", interrupted);
        } catch (TimeoutException timeout) {
            future.cancel(true);
            throw new IOException("transport exceeded total response deadline", timeout);
        } catch (ExecutionException failure) {
            throw new IOException("transport request failed", failure.getCause());
        }
    }

    private boolean validUri(URI uri) {
        if (uri == null || uri.isOpaque() || uri.getHost() == null
                || uri.getRawUserInfo() != null || uri.getRawFragment() != null
                || uri.getPort() == 0 || uri.getPort() > 65535) {
            return false;
        }
        if ("https".equalsIgnoreCase(uri.getScheme())) {
            return true;
        }
        return allowLoopbackHttp && "http".equalsIgnoreCase(uri.getScheme())
            && Set.of("127.0.0.1", "[::1]").contains(uri.getHost());
    }

    private static String origin(URI uri) {
        String scheme = uri.getScheme().toLowerCase(java.util.Locale.ROOT);
        return scheme + "://" + uri.getHost().toLowerCase(java.util.Locale.ROOT)
            + ":" + (uri.getPort() < 0 ? (scheme.equals("https") ? 443 : 80) : uri.getPort());
    }

    private static void positive(Duration duration) {
        if (duration == null || duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException("transport deadlines must be positive");
        }
        duration.toNanos();
    }

    @Override
    public void close() {
        // Immediate cancellation avoids waiting on hostile or stalled response bodies.
        client.shutdownNow();
    }

    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final int maximumBytes;
        private final String rejection;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private Flow.Subscription subscription;

        BoundedBody(int maximumBytes, String rejection) {
            this.maximumBytes = maximumBytes;
            this.rejection = rejection;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            if (rejection != null) {
                subscription.cancel();
                result.completeExceptionally(new IOException(rejection));
            } else {
                subscription.request(1);
            }
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > maximumBytes - bytes.size()) {
                    subscription.cancel();
                    result.completeExceptionally(new IOException("transport response exceeds byte limit"));
                    return;
                }
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }

        @Override
        public void onError(Throwable failure) {
            result.completeExceptionally(failure);
        }

        @Override
        public void onComplete() {
            result.complete(bytes.toByteArray());
        }
    }
}
