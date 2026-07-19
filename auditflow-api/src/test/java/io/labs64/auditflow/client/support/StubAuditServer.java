package io.labs64.auditflow.client.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** In-process HTTP stub for AuditFlow publish tests. Not thread-safe; one test at a time. */
public final class StubAuditServer implements AutoCloseable {

    public static final class CannedResponse {
        private final int status;
        private final Map<String, String> headers;
        private final String body;

        public CannedResponse(int status, Map<String, String> headers, String body) {
            this.status = status;
            this.headers = headers;
            this.body = body;
        }

        public int status() { return status; }
        public Map<String, String> headers() { return headers; }
        public String body() { return body; }

        public static CannedResponse ok(Map<String, String> headers) {
            return new CannedResponse(200, headers, "Audit event published successfully");
        }
        public static CannedResponse error(int status, String body) {
            return new CannedResponse(status, Map.of("Content-Type", "application/json"), body);
        }
    }

    public static final class CapturedRequest {
        private final String method;
        private final String path;
        private final Map<String, List<String>> headers;
        private final String body;

        public CapturedRequest(String method, String path, Map<String, List<String>> headers, String body) {
            this.method = method;
            this.path = path;
            this.headers = headers;
            this.body = body;
        }

        public String method() { return method; }
        public String path() { return path; }
        public Map<String, List<String>> headers() { return headers; }
        public String body() { return body; }
    }

    private final HttpServer server;
    private final Deque<CannedResponse> responses = new ArrayDeque<>();
    private final AtomicInteger requestCount = new AtomicInteger();
    private volatile CapturedRequest lastRequest;

    public StubAuditServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/audit/publish", this::handle);
        server.start();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        lastRequest = new CapturedRequest(
                exchange.getRequestMethod(),
                exchange.getRequestURI().getPath(),
                Map.copyOf(exchange.getRequestHeaders()),
                body);
        requestCount.incrementAndGet();

        CannedResponse response = responses.isEmpty()
                ? CannedResponse.ok(Map.of())
                : responses.poll();
        response.headers().forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
        byte[] payload = response.body().getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(response.status(), payload.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(payload);
        }
    }

    /** Queue responses to be returned in order, one per request. */
    public void enqueue(CannedResponse... responses) {
        for (CannedResponse r : responses) {
            this.responses.add(r);
        }
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1";
    }

    public CapturedRequest lastRequest() {
        return lastRequest;
    }

    public int requestCount() {
        return requestCount.get();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
