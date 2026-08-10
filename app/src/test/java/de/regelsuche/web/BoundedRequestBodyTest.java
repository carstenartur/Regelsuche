package de.regelsuche.web;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import org.junit.jupiter.api.Test;

class BoundedRequestBodyTest {


    @Test
    void rejectsDeclaredOversizeBeforeOpeningTheTransport() {
        DeclaredLengthExchange exchange = new DeclaredLengthExchange("5", "abcde");

        BoundedRequestBody.PayloadTooLargeException exception = assertThrows(
            BoundedRequestBody.PayloadTooLargeException.class,
            () -> BoundedRequestBody.open(exchange, 4)
        );

        assertEquals(4, exception.limitBytes());
        assertFalse(exchange.bodyAccessed);
    }

    @Test
    void malformedOrTooSmallDeclaredLengthsCannotBypassTheStreamLimit() {
        for (String declared : new String[] {"not-a-number", "1"}) {
            DeclaredLengthExchange exchange = new DeclaredLengthExchange(
                declared, "abcde");
            assertThrows(
                BoundedRequestBody.PayloadTooLargeException.class,
                () -> {
                    try (InputStream input = BoundedRequestBody.open(exchange, 4)) {
                        input.readAllBytes();
                    }
                },
                declared
            );
            assertTrue(exchange.bodyAccessed);
        }
    }

    @Test
    void readsExactlyTheConfiguredBoundaryAndThenReportsEof() throws IOException {
        byte[] source = "abcd".getBytes(StandardCharsets.UTF_8);
        try (InputStream input = BoundedRequestBody.open(
                new ByteArrayInputStream(source), source.length)) {
            assertArrayEquals(source, input.readAllBytes());
            assertEquals(-1, input.read());
        }
    }

    @Test
    void rejectsTheFirstByteBeyondTheConfiguredBoundary() {
        byte[] source = "abcde".getBytes(StandardCharsets.UTF_8);
        BoundedRequestBody.PayloadTooLargeException exception = assertThrows(
            BoundedRequestBody.PayloadTooLargeException.class,
            () -> {
                try (InputStream input = BoundedRequestBody.open(
                        new ByteArrayInputStream(source), 4)) {
                    input.readAllBytes();
                }
            }
        );
        assertEquals(4, exception.limitBytes());
    }

    @Test
    void enforcesTheSameLimitForSmallTransportChunks() throws IOException {
        byte[] source = "chunked".getBytes(StandardCharsets.UTF_8);
        try (InputStream input = BoundedRequestBody.open(
                new SmallChunkInputStream(source, 1), source.length)) {
            assertArrayEquals(source, input.readAllBytes());
        }

        assertThrows(
            BoundedRequestBody.PayloadTooLargeException.class,
            () -> {
                try (InputStream input = BoundedRequestBody.open(
                        new SmallChunkInputStream(source, 1), source.length - 1)) {
                    input.readAllBytes();
                }
            }
        );
    }

    @Test
    void skipCannotBypassAccounting() throws IOException {
        byte[] source = "abcde".getBytes(StandardCharsets.UTF_8);
        try (InputStream input = BoundedRequestBody.open(
                new ByteArrayInputStream(source), 4)) {
            assertEquals(4, input.skip(4));
            assertThrows(
                BoundedRequestBody.PayloadTooLargeException.class,
                input::read
            );
        }
    }

    @Test
    void markAndResetAreFailClosed() throws IOException {
        try (InputStream input = BoundedRequestBody.open(
                new ByteArrayInputStream(new byte[] {1}), 1)) {
            assertFalse(input.markSupported());
            input.mark(1);
            IOException exception = assertThrows(IOException.class, input::reset);
            assertTrue(exception.getMessage().contains("mark/reset"));
        }
    }

    @Test
    void closingTheBoundedStreamClosesTheTransport() throws IOException {
        CloseTrackingInputStream transport = new CloseTrackingInputStream();
        InputStream bounded = BoundedRequestBody.open(transport, 1);
        bounded.close();
        assertTrue(transport.closed);
    }

    private static final class SmallChunkInputStream extends InputStream {
        private final ByteArrayInputStream delegate;
        private final int maximumChunk;

        private SmallChunkInputStream(byte[] bytes, int maximumChunk) {
            this.delegate = new ByteArrayInputStream(bytes);
            this.maximumChunk = maximumChunk;
        }

        @Override
        public int read() {
            return delegate.read();
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            return delegate.read(buffer, offset, Math.min(length, maximumChunk));
        }
    }

    private static final class CloseTrackingInputStream extends InputStream {
        private boolean closed;

        @Override
        public int read() {
            return -1;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static final class DeclaredLengthExchange extends HttpExchange {
        private final Headers requestHeaders = new Headers();
        private final Headers responseHeaders = new Headers();
        private InputStream requestBody;
        private boolean bodyAccessed;

        private DeclaredLengthExchange(String declaredLength, String body) {
            requestHeaders.add("Content-Length", declaredLength);
            requestBody = new ByteArrayInputStream(
                body.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public Headers getRequestHeaders() {
            return requestHeaders;
        }

        @Override
        public Headers getResponseHeaders() {
            return responseHeaders;
        }

        @Override
        public URI getRequestURI() {
            return URI.create("http://localhost/test");
        }

        @Override
        public String getRequestMethod() {
            return "POST";
        }

        @Override
        public HttpContext getHttpContext() {
            return null;
        }

        @Override
        public void close() {
            try {
                requestBody.close();
            } catch (IOException ignored) {
                // ByteArrayInputStream close cannot fail.
            }
        }

        @Override
        public InputStream getRequestBody() {
            bodyAccessed = true;
            return requestBody;
        }

        @Override
        public OutputStream getResponseBody() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public void sendResponseHeaders(int responseCode, long responseLength) {
        }

        @Override
        public InetSocketAddress getRemoteAddress() {
            return new InetSocketAddress("127.0.0.1", 12345);
        }

        @Override
        public int getResponseCode() {
            return -1;
        }

        @Override
        public InetSocketAddress getLocalAddress() {
            return new InetSocketAddress("127.0.0.1", 8080);
        }

        @Override
        public String getProtocol() {
            return "HTTP/1.1";
        }

        @Override
        public Object getAttribute(String name) {
            return null;
        }

        @Override
        public void setAttribute(String name, Object value) {
        }

        @Override
        public void setStreams(InputStream input, OutputStream output) {
            requestBody = input;
        }

        @Override
        public HttpPrincipal getPrincipal() {
            return null;
        }
    }

}
