//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.editor;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** The parts of an HTTP request the API uses, with the body read lazily and bounded. */
final class HttpRequest {

    private final HttpExchange exchange;
    private final long maxBodyBytes;
    private final Map<String, String> query = new LinkedHashMap<>();

    HttpRequest(HttpExchange exchange, long maxBodyBytes) {
        this.exchange = exchange;
        this.maxBodyBytes = maxBodyBytes;
        String raw = exchange.getRequestURI().getRawQuery();
        if (raw != null && !raw.isEmpty()) {
            for (String pair : raw.split("&")) {
                int eq = pair.indexOf('=');
                String key = eq < 0 ? pair : pair.substring(0, eq);
                String value = eq < 0 ? "" : pair.substring(eq + 1);
                try {
                    query.putIfAbsent(URLDecoder.decode(key, StandardCharsets.UTF_8),
                            URLDecoder.decode(value, StandardCharsets.UTF_8));
                } catch (IllegalArgumentException e) {
                    throw new ApiException(400, "invalid_query", "malformed query string");
                }
            }
        }
    }

    String method() {
        return exchange.getRequestMethod().toUpperCase(Locale.ROOT);
    }

    /** The raw (not percent-decoded) path. */
    String path() {
        return exchange.getRequestURI().getRawPath();
    }

    String query(String name) {
        return query.get(name);
    }

    /** {@code ?name=true}; any other value, or none, is false. */
    boolean flag(String name) {
        return "true".equals(query.get(name));
    }

    String header(String name) {
        return exchange.getRequestHeaders().getFirst(name);
    }

    /**
     * The body as UTF-8 JSON text.
     *
     * @throws ApiException 415 unless the content type is JSON, 413 if larger than the limit
     */
    String body() {
        String type = header("Content-Type");
        if (type == null || !type.toLowerCase(Locale.ROOT).strip().startsWith("application/json")) {
            throw new ApiException(415, "unsupported_media_type", "request body must be sent as application/json");
        }
        String length = header("Content-Length");
        if (length != null) {
            try {
                if (Long.parseLong(length.strip()) > maxBodyBytes) {
                    throw tooLarge();
                }
            } catch (NumberFormatException e) {
                throw new ApiException(400, "bad_request", "invalid Content-Length");
            }
        }
        byte[] bytes;
        try (InputStream in = exchange.getRequestBody()) {
            bytes = in.readNBytes((int) Math.min(Integer.MAX_VALUE - 8L, maxBodyBytes + 1));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (bytes.length > maxBodyBytes) {
            throw tooLarge();
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new ApiException(400, "invalid_json", "request body is not valid UTF-8");
        }
    }

    private ApiException tooLarge() {
        return new ApiException(413, "body_too_large", "request body is larger than " + maxBodyBytes + " bytes");
    }
}
