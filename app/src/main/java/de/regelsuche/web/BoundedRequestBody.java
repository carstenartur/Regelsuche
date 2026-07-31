package de.regelsuche.web;

import com.sun.net.httpserver.HttpExchange;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/**
 * Reads one HTTP request body without ever buffering more than the configured
 * limit. The limit is enforced independently of {@code Content-Length}, so
 * chunked requests and incorrect length headers are handled identically.
 */
public final class BoundedRequestBody {
    private static final int BUFFER_SIZE = 8192;

    private BoundedRequestBody() {
    }

    public static byte[] read(HttpExchange exchange, int maxBytes) throws IOException {
        Objects.requireNonNull(exchange, "exchange");
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be positive");
        }

        try (InputStream input = exchange.getRequestBody();
             ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maxBytes, BUFFER_SIZE))) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int total = 0;
            while (true) {
                int remaining = maxBytes - total;
                int requested = remaining >= buffer.length ? buffer.length : remaining + 1;
                int read = input.read(buffer, 0, requested);
                if (read < 0) {
                    return output.toByteArray();
                }
                if (read > remaining) {
                    throw new PayloadTooLargeException(maxBytes);
                }
                output.write(buffer, 0, read);
                total += read;
            }
        }
    }

    /** Checked signal used by the HTTP boundary to render the stable 413 contract. */
    public static final class PayloadTooLargeException extends IOException {
        private final int limitBytes;

        public PayloadTooLargeException(int limitBytes) {
            super("request body exceeds configured limit of " + limitBytes + " bytes");
            this.limitBytes = limitBytes;
        }

        public int limitBytes() {
            return limitBytes;
        }
    }
}
