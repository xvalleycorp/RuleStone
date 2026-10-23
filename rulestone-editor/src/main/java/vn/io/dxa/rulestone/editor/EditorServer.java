//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.editor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * The RuleStone editor: a browser user interface and a JSON API over one directory of {@code .stone.json} files.
 *
 * <pre>{@code
 * try (EditorServer editor = EditorServer.builder()
 *         .rulesDir(Path.of("rules"))
 *         .port(8080)
 *         .start()) {
 *     System.out.println("Open " + editor.uri());
 *     ...
 * }
 * }</pre>
 *
 * <p><strong>There is no authentication.</strong> Anyone who can reach the port can read and change every rule in
 * the directory. The server binds to {@code 127.0.0.1} by default; to share it, put it behind the bank's single
 * sign-on reverse proxy and name the proxy's host with {@link Builder#allowedHost}. Requests from other web sites
 * are refused (Host and Origin checks), request bodies are limited in size, and files outside the directory, or not
 * named {@code *.stone.json}, cannot be read or written. See {@code docs/editor.md}.
 */
public final class EditorServer implements AutoCloseable {

    /** Default maximum size of a request body: 1 MiB. */
    public static final int DEFAULT_MAX_BODY_BYTES = 1024 * 1024;

    private static final System.Logger LOG = System.getLogger(EditorServer.class.getName());

    private static final String CSP = "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
            + "img-src 'self' data:; font-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'none'; "
            + "form-action 'none'; frame-ancestors 'none'";
    private static final Pattern STATIC_PATH = Pattern.compile("/(assets/)?[A-Za-z0-9][A-Za-z0-9_.-]*");
    private static final String WEB_ROOT = "web/";
    private static final Map<String, String> CONTENT_TYPES = Map.of(
            "html", "text/html; charset=utf-8",
            "js", "text/javascript; charset=utf-8",
            "css", "text/css; charset=utf-8",
            "svg", "image/svg+xml",
            "png", "image/png",
            "ico", "image/x-icon",
            "json", "application/json; charset=utf-8",
            "txt", "text/plain; charset=utf-8",
            "woff2", "font/woff2");

    private final HttpServer server;
    private final ExecutorService executor;
    private final RulesDirectory directory;
    private final Api api;
    private final RequestGuard guard;
    private final long maxBodyBytes;
    private final URI uri;

    private EditorServer(Builder builder) throws IOException {
        this.directory = new RulesDirectory(Objects.requireNonNull(builder.rulesDir, "rulesDir"));
        this.maxBodyBytes = builder.maxBodyBytes;
        this.api = new Api(directory, maxBodyBytes);
        this.server = HttpServer.create(new InetSocketAddress(builder.host, builder.port), 0);
        int port = server.getAddress().getPort();
        this.guard = new RequestGuard(builder.host, port, builder.allowedHosts);
        AtomicInteger threads = new AtomicInteger();
        this.executor = Executors.newFixedThreadPool(builder.threads, r -> {
            Thread t = new Thread(r, "rulestone-editor-" + threads.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        String host = RequestGuard.isWildcard(builder.host) ? "127.0.0.1"
                : builder.host.contains(":") && !builder.host.startsWith("[") ? "[" + builder.host + "]" : builder.host;
        this.uri = URI.create("http://" + host + ":" + port + "/");
        server.start();
    }

    /** A builder; {@link Builder#rulesDir} is required. */
    public static Builder builder() {
        return new Builder();
    }

    /** The port the server listens on (useful with port 0). */
    public int port() {
        return server.getAddress().getPort();
    }

    /** The address to open in a browser, e.g. {@code http://127.0.0.1:8080/}. */
    public URI uri() {
        return uri;
    }

    /** The rules directory, as a real path. */
    public Path rulesDir() {
        return directory.root();
    }

    /** Stops the server. Writes are atomic, so a request interrupted by stopping never leaves a partial file. */
    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    // ---- request handling ---------------------------------------------------------------------------------------

    private void handle(HttpExchange exchange) {
        try (exchange) {
            Headers headers = exchange.getResponseHeaders();
            securityHeaders(headers);
            String path = exchange.getRequestURI().getRawPath();
            boolean isApi = path.equals("/api") || path.startsWith("/api/");
            try {
                Headers request = exchange.getRequestHeaders();
                guard.check(request.getFirst("Host"), request.getFirst("Origin"), request.getFirst("Sec-Fetch-Site"), isApi);
                if (isApi) {
                    headers.set("Cache-Control", "no-store");
                    if (exchange.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
                        throw new ApiException(405, "method_not_allowed", "CORS is not supported");
                    }
                    Api.Response response = api.handle(new HttpRequest(exchange, maxBodyBytes));
                    response.headers().forEach(headers::set);
                    sendJson(exchange, response.status(), response.body());
                } else {
                    serveStatic(exchange, path);
                }
            } catch (ApiException e) {
                sendError(exchange, e.status(), e.code(), e.getMessage());
            } catch (RuntimeException e) {
                // Never log request content: it may contain customer data. The type and stack are enough.
                LOG.log(System.Logger.Level.ERROR, "unexpected " + e.getClass().getName() + " handling "
                        + exchange.getRequestMethod() + " " + path);
                sendError(exchange, 500, "internal", "internal error; see the server log");
            }
        } catch (IOException e) {
            LOG.log(System.Logger.Level.DEBUG, "client connection closed: " + e.getClass().getSimpleName());
        }
    }

    private void serveStatic(HttpExchange exchange, String path) throws IOException {
        String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
        if (!method.equals("GET") && !method.equals("HEAD")) {
            throw new ApiException(405, "method_not_allowed", "use GET");
        }
        String file = path.equals("/") ? "index.html" : path.substring(1);
        if (!path.equals("/") && !STATIC_PATH.matcher(path).matches()) {
            throw new ApiException(404, "not_found", "not found");
        }
        byte[] bytes;
        try (InputStream in = EditorServer.class.getResourceAsStream(WEB_ROOT + file)) {
            if (in == null) {
                if (file.equals("index.html")) {
                    bytes = ("<!doctype html><meta charset=utf-8><title>RuleStone Editor</title>"
                            + "<p>The user interface was not built into this jar (built with -Dfrontend.skip?). "
                            + "The API is available under <code>/api</code>.").getBytes(StandardCharsets.UTF_8);
                    file = "fallback.html";
                } else {
                    throw new ApiException(404, "not_found", "not found");
                }
            } else {
                bytes = in.readAllBytes();
            }
        }
        String extension = file.substring(file.lastIndexOf('.') + 1);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", CONTENT_TYPES.getOrDefault(extension, "application/octet-stream"));
        headers.set("Cache-Control", file.startsWith("assets/") ? "public, max-age=31536000, immutable" : "no-cache");
        if (method.equals("HEAD")) {
            exchange.sendResponseHeaders(200, -1);
            return;
        }
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void securityHeaders(Headers headers) {
        headers.set("Content-Security-Policy", CSP);
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("X-Frame-Options", "DENY");
        headers.set("Referrer-Policy", "no-referrer");
        headers.set("Cross-Origin-Opener-Policy", "same-origin");
        headers.set("Cross-Origin-Resource-Policy", "same-origin");
        headers.set("Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=(), usb=()");
    }

    private static void sendError(HttpExchange exchange, int status, String code, String message) throws IOException {
        ObjectNode body = Json.object();
        ObjectNode error = body.putObject("error");
        error.put("code", code);
        error.put("message", message);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        sendJson(exchange, status, body);
    }

    private static void sendJson(HttpExchange exchange, int status, JsonNode body) throws IOException {
        byte[] bytes = Json.write(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        if (exchange.getRequestMethod().equalsIgnoreCase("HEAD")) {
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** Configures and starts an {@link EditorServer}. */
    public static final class Builder {

        private Path rulesDir;
        private String host = "127.0.0.1";
        private int port = 8080;
        private final Set<String> allowedHosts = new LinkedHashSet<>();
        private long maxBodyBytes = DEFAULT_MAX_BODY_BYTES;
        private int threads = 8;

        private Builder() {}

        /** The directory of {@code .stone.json} files to edit; required, must exist. */
        public Builder rulesDir(Path rulesDir) {
            this.rulesDir = Objects.requireNonNull(rulesDir, "rulesDir");
            return this;
        }

        /** Address to listen on; default {@code 127.0.0.1}. Anything else exposes the unauthenticated editor. */
        public Builder host(String host) {
            this.host = Objects.requireNonNull(host, "host").strip();
            return this;
        }

        /** Port to listen on; default 8080, 0 for any free port. */
        public Builder port(int port) {
            if (port < 0 || port > 65535) {
                throw new IllegalArgumentException("port must be between 0 and 65535");
            }
            this.port = port;
            return this;
        }

        /**
         * Accepts requests whose {@code Host} header is {@code hostAndPort}, e.g. {@code rules.bank.example} when the
         * editor runs behind a reverse proxy under that name. Loopback names are always accepted.
         */
        public Builder allowedHost(String hostAndPort) {
            String value = Objects.requireNonNull(hostAndPort, "hostAndPort").strip();
            if (value.isEmpty() || value.contains("/") || value.contains(" ")) {
                throw new IllegalArgumentException("allowed host must be host or host:port, without scheme or path");
            }
            allowedHosts.add(value);
            return this;
        }

        /** Maximum size of a request body in bytes; default {@value EditorServer#DEFAULT_MAX_BODY_BYTES}. */
        public Builder maxBodyBytes(long maxBodyBytes) {
            if (maxBodyBytes < 1024) {
                throw new IllegalArgumentException("maxBodyBytes must be at least 1024");
            }
            this.maxBodyBytes = maxBodyBytes;
            return this;
        }

        /** Number of request threads; default 8. */
        public Builder threads(int threads) {
            if (threads < 1) {
                throw new IllegalArgumentException("threads must be positive");
            }
            this.threads = threads;
            return this;
        }

        /**
         * Starts the server.
         *
         * @throws IOException              if the address cannot be bound
         * @throws IllegalArgumentException if the rules directory does not exist
         */
        public EditorServer start() throws IOException {
            return new EditorServer(this);
        }
    }
}
