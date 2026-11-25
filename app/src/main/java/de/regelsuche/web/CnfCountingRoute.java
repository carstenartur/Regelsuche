package de.regelsuche.web;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** One bounded live request per server. The caller's random request ID addresses cancellation. */
final class CnfCountingRoute {
    static final String BASE = "/api/certificate-demos/cnf/";
    private record Job(String id, AtomicBoolean cancelled) {}
    private final AtomicReference<Job> active = new AtomicReference<>();

    void handle(HttpExchange exchange, String suffix, int maxBytes) throws IOException {
        String[] path = suffix.split("/", -1);
        if (!path[0].matches("[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}")) {
            send(exchange, 400, "text/plain", "Ungültige Auftragskennung."); return;
        }
        if (path.length == 2 && path[1].equals("cancel")) {
            var job = active.get();
            if (job == null || !job.id().equals(path[0])) send(exchange, 404, "text/plain", "Kein laufender Auftrag.");
            else {
                job.cancelled().set(true);
                send(exchange, 202, "application/json", "{\"status\":\"CANCELLATION_REQUESTED\"}");
            }
            return;
        }
        CnfCountingWorkbench.Request request;
        try {
            request = CnfCountingWorkbench.read(new StreamingJsonRequestBody(Math.min(32_768, maxBytes)).readObject(exchange));
        } catch (IllegalArgumentException invalid) {
            send(exchange, 400, "text/plain", invalid.getMessage()); return;
        }
        var job = new Job(path[0], new AtomicBoolean());
        if (!active.compareAndSet(null, job)) {
            send(exchange, 429, "text/plain", "Eine Zählung läuft bereits."); return;
        }
        try {
            Thread.startVirtualThread(() -> finish(exchange, request, job));
        } catch (RuntimeException | Error failure) {
            active.compareAndSet(job, null); throw failure;
        }
    }

    private void finish(HttpExchange exchange, CnfCountingWorkbench.Request request, Job job) {
        try {
            String body;
            try { body = CnfCountingWorkbench.count(request, job.cancelled()::get); }
            finally { active.compareAndSet(job, null); }
            send(exchange, 200, "application/json", body);
        } catch (IOException disconnected) {
            exchange.close();
        } catch (RuntimeException failure) {
            try { send(exchange, 500, "text/plain", "Zählung fehlgeschlagen."); }
            catch (IOException disconnected) { exchange.close(); }
        }
    }

    private static void send(HttpExchange exchange, int status, String type, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", type + "; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }
}
