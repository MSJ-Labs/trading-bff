package com.msj.trading.bff.gateway;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Stand-in for trading-core in gateway tests: a plain JDK HTTP server (no extra dependency) that counts the
 * requests it receives and answers with a scripted behaviour per path.
 *
 * <p>Behaviours queued with {@link #script} are consumed one request at a time, in order; once the queue is
 * empty, the path's default behaviour applies (200 unless {@link #thenAlways} says otherwise).
 */
final class FakeBackend implements AutoCloseable {

    /** What the backend does with one request. */
    sealed interface Behavior permits Respond, Slow, Drop {
    }

    /** Answers immediately with this status. */
    private record Respond(int status) implements Behavior {
    }

    /** Waits, then answers with this status (simulates an overloaded service). */
    private record Slow(Duration delay, int status) implements Behavior {
    }

    /** Accepts the request then closes the connection with no response (simulates a network failure). */
    private record Drop() implements Behavior {
    }

    private static final Queue<Behavior> NO_SCRIPT = new ConcurrentLinkedQueue<>();
    private static final Behavior OK = new Respond(200);

    private final HttpServer server;
    private final Map<String, Queue<Behavior>> scripts = new ConcurrentHashMap<>();
    private final Map<String, Behavior> defaults = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> counters = new ConcurrentHashMap<>();

    FakeBackend() {
        try {
            // Port 0: the OS picks a free port, so tests never collide with a real trading-core on 8080.
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        }
        catch (IOException e) {
            throw new IllegalStateException("Cannot start the fake backend", e);
        }
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    static Behavior respond(int status) {
        return new Respond(status);
    }

    static Behavior slow(Duration delay, int status) {
        return new Slow(delay, status);
    }

    static Behavior drop() {
        return new Drop();
    }

    int port() {
        return server.getAddress().getPort();
    }

    /** Queues behaviours for this path, consumed in order, one per request. */
    FakeBackend script(String path, Behavior... behaviors) {
        Queue<Behavior> queue = scripts.computeIfAbsent(path, _ -> new ConcurrentLinkedQueue<>());
        queue.addAll(List.of(behaviors));
        return this;
    }

    /** Behaviour once the script of this path is exhausted (default: 200). */
    FakeBackend thenAlways(String path, Behavior behavior) {
        defaults.put(path, behavior);
        return this;
    }

    /** Number of requests received for this method and path since the last {@link #reset()}. */
    int requests(String method, String path) {
        AtomicInteger counter = counters.get(key(method, path));
        return counter == null ? 0 : counter.get();
    }

    /** Forgets scripts and counters: call between tests when the backend is shared. */
    void reset() {
        scripts.clear();
        defaults.clear();
        counters.clear();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        countRequest(exchange.getRequestMethod(), path);
        exchange.getRequestBody().readAllBytes();
        apply(nextBehavior(path), exchange);
    }

    private void countRequest(String method, String path) {
        counters.computeIfAbsent(key(method, path), _ -> new AtomicInteger()).incrementAndGet();
    }

    /** The next scripted behaviour for this path, or the path's default once the script is exhausted. */
    private Behavior nextBehavior(String path) {
        Behavior scripted = scripts.getOrDefault(path, NO_SCRIPT).poll();
        return scripted != null ? scripted : defaults.getOrDefault(path, OK);
    }

    private static void apply(Behavior behavior, HttpExchange exchange) throws IOException {
        switch (behavior) {
            case Respond r -> reply(exchange, r.status());
            case Slow s -> {
                // If interrupted (server shutting down), the exchange is left unanswered: nobody is waiting anymore.
                if (sleep(s.delay())) {
                    reply(exchange, s.status());
                }
            }
            // An exception escaping the handler makes the JDK server close the connection without answering.
            case Drop _ -> throw new IOException("simulated connection drop");
        }
    }

    /** @return {@code false} if the thread was interrupted before the delay elapsed. */
    private static boolean sleep(Duration delay) {
        try {
            Thread.sleep(delay);
            return true;
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void reply(HttpExchange exchange, int status) throws IOException {
        byte[] body = """
                {"status":%d}""".formatted(status).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static String key(String method, String path) {
        return method + " " + path;
    }
}