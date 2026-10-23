//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.editor;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/** An editor on a random port over a temporary copy of examples/, and a raw HTTP/1.1 client for it. */
final class EditorHarness implements AutoCloseable {

    static final Path EXAMPLES = Path.of("../examples");

    final Path dir;
    final EditorServer server;

    private EditorHarness(Path dir, EditorServer server) {
        this.dir = dir;
        this.server = server;
    }

    /** Starts an editor over a copy of every {@code *.stone.json} in examples/. */
    static EditorHarness start(Path tempDir) {
        return start(tempDir, EditorServer.DEFAULT_MAX_BODY_BYTES);
    }

    /** Starts an editor over a copy of the named files of examples/. */
    static EditorHarness start(Path tempDir, java.util.List<String> exampleFiles) {
        try {
            Path dir = Files.createDirectories(tempDir.resolve("rules"));
            for (String file : exampleFiles) {
                Files.copy(EXAMPLES.resolve(file), dir.resolve(file));
            }
            return new EditorHarness(dir, EditorServer.builder().rulesDir(dir).port(0).start());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static EditorHarness start(Path tempDir, long maxBodyBytes) {
        try {
            Path dir = Files.createDirectories(tempDir.resolve("rules"));
            try (Stream<Path> files = Files.list(EXAMPLES)) {
                for (Path file : files.filter(f -> f.getFileName().toString().endsWith(".stone.json")).toList()) {
                    Files.copy(file, dir.resolve(file.getFileName()));
                }
            }
            return new EditorHarness(dir, EditorServer.builder().rulesDir(dir).port(0).maxBodyBytes(maxBodyBytes).start());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void close() {
        server.close();
    }

    String host() {
        return "127.0.0.1:" + server.port();
    }

    Response get(String path) {
        return send("GET", path, null, Map.of());
    }

    Response post(String path, String json) {
        return send("POST", path, json, Map.of());
    }

    Response put(String path, String json) {
        return send("PUT", path, json, Map.of());
    }

    /** Sends a request as a same-origin browser page would (Host and Origin of the editor). */
    Response send(String method, String path, String json, Map<String, String> headers) {
        Map<String, String> all = new LinkedHashMap<>();
        all.put("Host", host());
        if (json != null) {
            all.put("Content-Type", "application/json");
        }
        all.putAll(headers);
        all.values().removeIf(v -> v == null);
        return raw(method, path, json, all);
    }

    /** Sends exactly these headers and path, unencoded, without any client-side normalization. */
    Response raw(String method, String path, String body, Map<String, String> headers) {
        return raw(method, path, body, headers, body != null);
    }

    /** Like {@link #raw} but sends the body bytes verbatim with no Content-Length (e.g. pre-chunked). */
    Response rawWithBody(String method, String path, String body, Map<String, String> headers) {
        return raw(method, path, body, headers, false);
    }

    private Response raw(String method, String path, String body, Map<String, String> headers, boolean length) {
        try (Socket socket = new Socket("127.0.0.1", server.port())) {
            socket.setSoTimeout(10_000);
            byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
            StringBuilder request = new StringBuilder(method + " " + path + " HTTP/1.1\r\n");
            headers.forEach((k, v) -> request.append(k).append(": ").append(v).append("\r\n"));
            if (length) {
                request.append("Content-Length: ").append(bytes.length).append("\r\n");
            }
            request.append("Connection: close\r\n\r\n");
            OutputStream out = socket.getOutputStream();
            out.write(request.toString().getBytes(StandardCharsets.ISO_8859_1));
            out.write(bytes);
            out.flush();
            return Response.parse(socket.getInputStream());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    record Response(int status, Map<String, String> headers, String body) {

        String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }

        JsonNode json() {
            return Json.parse(body);
        }

        static Response parse(InputStream in) throws IOException {
            ByteArrayOutputStream all = new ByteArrayOutputStream();
            in.transferTo(all);
            String text = all.toString(StandardCharsets.UTF_8);
            int split = text.indexOf("\r\n\r\n");
            String head = text.substring(0, split);
            String body = text.substring(split + 4);
            String[] lines = head.split("\r\n");
            int status = Integer.parseInt(lines[0].split(" ")[1]);
            Map<String, String> headers = new LinkedHashMap<>();
            for (int i = 1; i < lines.length; i++) {
                int colon = lines[i].indexOf(':');
                headers.put(lines[i].substring(0, colon).strip().toLowerCase(Locale.ROOT), lines[i].substring(colon + 1).strip());
            }
            if ("chunked".equalsIgnoreCase(headers.get("transfer-encoding"))) {
                body = dechunk(body);
            }
            return new Response(status, headers, body);
        }

        private static String dechunk(String body) {
            StringBuilder out = new StringBuilder();
            int pos = 0;
            while (true) {
                int eol = body.indexOf("\r\n", pos);
                int size = Integer.parseInt(body.substring(pos, eol).strip(), 16);
                if (size == 0) {
                    return out.toString();
                }
                // sizes are in bytes; the bodies used in tests are ASCII-safe for chunked responses
                out.append(body, eol + 2, eol + 2 + size);
                pos = eol + 2 + size + 2;
            }
        }
    }

    static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
