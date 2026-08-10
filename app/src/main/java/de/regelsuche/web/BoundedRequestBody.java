package de.regelsuche.web;

import com.sun.net.httpserver.HttpExchange;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Objects;

/**
 * Opens one HTTP request body as a byte-counting stream.
 *
 * <p>The wrapper never materializes the complete body. It rejects a declared
 * {@code Content-Length} above the configured limit immediately and still
 * enforces the same boundary while consuming the stream, so chunked requests,
 * missing headers and incorrect length headers cannot bypass the limit.</p>
 */
public final class BoundedRequestBody {
    private static final int SKIP_BUFFER_SIZE = 8192;

    private BoundedRequestBody() {
    }

    /**
     * Opens the exchange body with an exact maximum number of readable bytes.
     * The returned stream owns the exchange request body and must be closed.
     */
    public static InputStream open(HttpExchange exchange, int maxBytes)
            throws IOException {
        Objects.requireNonNull(exchange, "exchange");
        validateLimit(maxBytes);
        rejectDeclaredOversize(exchange, maxBytes);
        return open(exchange.getRequestBody(), maxBytes);
    }

    static InputStream open(InputStream input, int maxBytes) {
        Objects.requireNonNull(input, "input");
        validateLimit(maxBytes);
        return new LimitedInputStream(input, maxBytes);
    }

    private static void validateLimit(int maxBytes) {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be positive");
        }
    }

    private static void rejectDeclaredOversize(
        HttpExchange exchange,
        int maxBytes
    ) throws PayloadTooLargeException {
        List<String> values = exchange.getRequestHeaders().get("Content-Length");
        if (values == null) {
            return;
        }
        for (String value : values) {
            try {
                long declared = Long.parseLong(value.trim());
                if (declared > maxBytes) {
                    throw new PayloadTooLargeException(maxBytes);
                }
            } catch (NumberFormatException ignored) {
                // The bounded stream remains authoritative for malformed headers.
            }
        }
    }

    private static final class LimitedInputStream extends FilterInputStream {
        private final int maxBytes;
        private int consumed;
        private boolean eof;

        private LimitedInputStream(InputStream input, int maxBytes) {
            super(input);
            this.maxBytes = maxBytes;
        }

        @Override
        public int read() throws IOException {
            if (eof) {
                return -1;
            }
            if (consumed >= maxBytes) {
                return verifyEof();
            }
            int value = super.read();
            if (value < 0) {
                eof = true;
                return -1;
            }
            consumed++;
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length)
                throws IOException {
            Objects.checkFromIndexSize(offset, length, buffer.length);
            if (length == 0) {
                return 0;
            }
            if (eof) {
                return -1;
            }
            int remaining = maxBytes - consumed;
            if (remaining <= 0) {
                return verifyEof();
            }
            int read = super.read(buffer, offset, Math.min(length, remaining));
            if (read < 0) {
                eof = true;
                return -1;
            }
            consumed += read;
            return read;
        }

        @Override
        public long skip(long requested) throws IOException {
            if (requested <= 0L) {
                return 0L;
            }
            byte[] buffer = new byte[(int) Math.min(SKIP_BUFFER_SIZE, requested)];
            long skipped = 0L;
            while (skipped < requested) {
                int chunk = (int) Math.min(buffer.length, requested - skipped);
                int read = read(buffer, 0, chunk);
                if (read < 0) {
                    break;
                }
                skipped += read;
            }
            return skipped;
        }

        @Override
        public int available() throws IOException {
            if (eof) {
                return 0;
            }
            return Math.min(super.available(), Math.max(0, maxBytes - consumed));
        }

        @Override
        public boolean markSupported() {
            return false;
        }

        @Override
        public synchronized void mark(int readLimit) {
            // Deliberately unsupported: resetting would invalidate byte accounting.
        }

        @Override
        public synchronized void reset() throws IOException {
            throw new IOException("mark/reset is not supported by bounded request bodies");
        }

        private int verifyEof() throws IOException {
            if (eof) {
                return -1;
            }
            int extra = super.read();
            if (extra < 0) {
                eof = true;
                return -1;
            }
            throw new PayloadTooLargeException(maxBytes);
        }
    }

    /** Checked signal used by the HTTP boundary to render the stable 413 contract. */
    public static final class PayloadTooLargeException extends IOException {
        private static final long serialVersionUID = 1L;

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
