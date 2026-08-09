package de.regelsuche.web;

import com.sun.net.httpserver.HttpExchange;
import de.regelsuche.json.JsonWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Stable JSON error responses shared by the embedded HTTP adapters. */
public final class HttpJsonErrorResponse {
    private HttpJsonErrorResponse() {
    }

    public static void sendPayloadTooLarge(
        HttpExchange exchange,
        int limitBytes
    ) throws IOException {
        Objects.requireNonNull(exchange, "exchange");
        if (limitBytes <= 0) {
            throw new IllegalArgumentException("limitBytes must be positive");
        }
        JsonWriter writer = new JsonWriter().beginObject()
            .property("error", true)
            .property("code", "PAYLOAD_TOO_LARGE")
            .property("message", "request body exceeds configured limit")
            .property("limitBytes", limitBytes)
            .endObject();
        byte[] bytes = writer.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set(
            "Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(413, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
