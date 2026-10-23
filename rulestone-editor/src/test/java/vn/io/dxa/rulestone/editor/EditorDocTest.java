//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.editor;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs every HTTP example in docs/editor.md, in order, against an editor over the files the page names, and
 * compares each response with the documented one. In documented responses, {@code …} inside a string matches
 * any text, a final {@code "…"} array element allows more elements, and fields left out are not checked.
 * Also runs the Java example. Update both together.
 */
class EditorDocTest {

    private static final Path DOC = Path.of("../docs/editor.md");
    private static final List<String> FILES = List.of(
            "deposit_rate.stone.json", "loan_eligibility.v2.stone.json", "loan_eligibility.v3.stone.json",
            "transfer_fees.stone.json");
    private static final Pattern HTTP_BLOCK = Pattern.compile("```http\\n(.*?)\\n```", Pattern.DOTALL);

    record Message(String startLine, Map<String, String> headers, String body) {}

    @TempDir
    Path temp;

    @Test
    void everyHttpExampleProducesTheDocumentedResponse() throws IOException {
        List<Message> blocks = new ArrayList<>();
        Matcher m = HTTP_BLOCK.matcher(Files.readString(DOC));
        while (m.find()) {
            blocks.add(parse(m.group(1)));
        }
        assertThat(blocks).hasSizeGreaterThanOrEqualTo(20);

        try (EditorHarness editor = EditorHarness.start(temp, FILES)) {
            int examples = 0;
            for (int i = 0; i < blocks.size(); i++) {
                Message request = blocks.get(i);
                if (request.startLine().startsWith("HTTP/")) {
                    continue;
                }
                Message expected = blocks.get(i + 1);
                assertThat(expected.startLine()).as("response after " + request.startLine()).startsWith("HTTP/1.1 ");
                String[] parts = request.startLine().split(" ");
                EditorHarness.Response actual = editor.send(parts[0], parts[1],
                        request.body().isBlank() ? null : request.body(), request.headers());

                String what = request.startLine();
                assertThat(actual.status()).as(what + " -> " + actual.body())
                        .isEqualTo(Integer.parseInt(expected.startLine().split(" ")[1]));
                List<String> differences = new ArrayList<>();
                matches(Json.parse(expected.body()), actual.json(), "$", differences);
                assertThat(differences).as(what + "\nactual: " + actual.body()).isEmpty();
                examples++;
            }
            assertThat(examples).isEqualTo(blocks.size() / 2);
        }
    }

    @Test
    void comparisonDetectsDifferences() {
        List<String> differences = new ArrayList<>();
        matches(Json.parse("{\"a\": \"1@sha256:…\", \"b\": [1, \"…\"], \"c\": 2, \"d\": null}"),
                Json.parse("{\"a\": \"1@sha256:abc\", \"b\": [1, 2, 3], \"c\": 2.0, \"d\": null, \"extra\": true}"), "$", differences);
        assertThat(differences).isEmpty();

        matches(Json.parse("{\"a\": \"2@…\", \"b\": [1], \"c\": 3, \"missing\": 1}"),
                Json.parse("{\"a\": \"1@x\", \"b\": [1, 2], \"c\": 2}"), "$", differences);
        assertThat(differences).hasSize(4);
    }

    @Test
    void javaExample() throws IOException {
        Files.createDirectories(temp.resolve("rules"));
        Path rules = temp.resolve("rules");

        try (EditorServer editor = EditorServer.builder()
                .rulesDir(rules)
                .port(0)                                   // any free port
                .start()) {
            assertThat(editor.uri().toString()).isEqualTo("http://127.0.0.1:" + editor.port() + "/");
        }
    }

    private static Message parse(String block) {
        String[] headAndBody = block.split("\n\n", 2);
        String[] lines = headAndBody[0].split("\n");
        Map<String, String> headers = new LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            headers.put(lines[i].substring(0, colon).strip(), lines[i].substring(colon + 1).strip());
        }
        return new Message(lines[0].strip(), headers, headAndBody.length > 1 ? headAndBody[1] : "");
    }

    /** Whether {@code actual} matches the documented {@code expected}; records each difference. */
    static void matches(JsonNode expected, JsonNode actual, String path, List<String> differences) {
        if (actual == null || actual.isMissingNode()) {
            differences.add(path + ": missing");
        } else if (expected.isTextual()) {
            String pattern = String.join(".*", List.of(expected.textValue().split("…", -1)).stream().map(Pattern::quote).toList());
            if (!actual.isTextual() || !Pattern.compile(pattern, Pattern.DOTALL).matcher(actual.textValue()).matches()) {
                differences.add(path + ": expected " + expected + " but was " + actual);
            }
        } else if (expected.isObject()) {
            if (!actual.isObject()) {
                differences.add(path + ": expected an object but was " + actual);
                return;
            }
            expected.properties().forEach(e -> matches(e.getValue(), actual.get(e.getKey()), path + "." + e.getKey(), differences));
        } else if (expected.isArray()) {
            if (!actual.isArray()) {
                differences.add(path + ": expected an array but was " + actual);
                return;
            }
            int n = expected.size();
            boolean open = n > 0 && "…".equals(expected.get(n - 1).asText(null));
            int required = open ? n - 1 : n;
            if (open ? actual.size() < required : actual.size() != required) {
                differences.add(path + ": expected " + (open ? "at least " : "") + required + " elements but was " + actual.size());
                return;
            }
            Iterator<JsonNode> a = actual.elements();
            for (int i = 0; i < required; i++) {
                matches(expected.get(i), a.next(), path + "[" + i + "]", differences);
            }
        } else if (expected.isNumber()) {
            if (!actual.isNumber() || expected.decimalValue().compareTo(actual.decimalValue()) != 0) {
                differences.add(path + ": expected " + expected + " but was " + actual);
            }
        } else if (!expected.equals(actual)) {
            differences.add(path + ": expected " + expected + " but was " + actual);
        }
    }
}
