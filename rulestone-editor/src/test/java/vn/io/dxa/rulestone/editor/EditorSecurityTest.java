//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.editor;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Path traversal, Host and Origin checks. */
class EditorSecurityTest {

    @TempDir
    Path temp;

    private EditorHarness editor;
    private String table;

    @BeforeEach
    void start() throws IOException {
        editor = EditorHarness.start(temp);
        table = editor.get("/api/tables/deposit_rate.stone.json").json().get("table").toString();
        Files.writeString(temp.resolve("outside.stone.json"), EditorHarness.read(editor.dir.resolve("deposit_rate.stone.json")));
    }

    @AfterEach
    void stop() {
        editor.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "../outside.stone.json",
            "..%2Foutside.stone.json",
            "%2e%2e/outside.stone.json",
            "%2e%2e%2foutside.stone.json",
            "sub/../../outside.stone.json",
            "./deposit_rate.stone.json",
            "/etc/passwd",
            "..\\outside.stone.json",
            "C:%5Cwindows%5Cwin.ini",
            ".hidden.stone.json",
            "deposit_rate.json",
            "deposit_rate.stone.json%00.txt",
            ".stone.json",
            "a//deposit_rate.stone.json",
    })
    void filesOutsideTheDirectoryOrNotStoneJsonCannotBeReadOrWritten(String name) {
        EditorHarness.Response read = editor.get("/api/tables/" + name);
        EditorHarness.Response write = editor.put("/api/tables/" + name + "?force=true", table);
        EditorHarness.Response create = editor.post("/api/tables?force=true",
                "{\"file\": \"" + name.replace("\\", "\\\\") + "\", \"table\": " + table + "}");

        assertThat(read.status()).isIn(400, 404);
        assertThat(write.status()).isIn(400, 404);
        assertThat(create.status()).isIn(400, 404);
        assertThat(read.body()).doesNotContain("deposit_rate\"").doesNotContain("root:");
    }

    @Test
    void createCannotEscapeTheDirectory() {
        EditorHarness.Response create = editor.post("/api/tables?force=true",
                "{\"file\": \"../escaped.stone.json\", \"table\": " + table + "}");

        assertThat(create.status()).isEqualTo(400);
        assertThat(Files.exists(temp.resolve("escaped.stone.json"))).isFalse();
    }

    @Test
    void symbolicLinksLeadingOutsideAreNeitherListedNorRead() throws IOException {
        Path link = editor.dir.resolve("link.stone.json");
        Path dirLink = editor.dir.resolve("linked");
        try {
            Files.createSymbolicLink(link, temp.resolve("outside.stone.json"));
            Files.createSymbolicLink(dirLink, temp);
        } catch (UnsupportedOperationException | IOException e) {
            return; // no symbolic links on this file system
        }

        assertThat(editor.get("/api/tables").body()).doesNotContain("link.stone.json").doesNotContain("linked/");
        assertThat(editor.get("/api/tables/link.stone.json").status()).isEqualTo(404);
        assertThat(editor.get("/api/tables/linked/outside.stone.json").status()).isIn(400, 404);
        assertThat(editor.put("/api/tables/linked/outside.stone.json?force=true", table).status()).isIn(400, 404);
    }

    @Test
    void subdirectoriesAreSupported() throws IOException {
        Files.createDirectories(editor.dir.resolve("loans"));
        Files.move(editor.dir.resolve("loan_eligibility.v3.stone.json"), editor.dir.resolve("loans/loan_eligibility.v3.stone.json"));

        assertThat(editor.get("/api/tables").body()).contains("loans/loan_eligibility.v3.stone.json");
        assertThat(editor.get("/api/tables/loans/loan_eligibility.v3.stone.json").status()).isEqualTo(200);
    }

    @ParameterizedTest
    @ValueSource(strings = {"evil.example", "evil.example:80", "127.0.0.1", "127.0.0.1.evil.example"})
    void unknownHostNamesAreRefused(String host) {
        EditorHarness.Response response = editor.raw("GET", "/api/meta", null, Map.of("Host", host));
        assertThat(response.status()).isEqualTo(421);
    }

    @Test
    void missingHostIsRefused() {
        assertThat(editor.raw("GET", "/api/meta", null, Map.of()).status()).isEqualTo(421);
    }

    @Test
    void loopbackNamesAreAccepted() {
        int port = editor.server.port();
        for (String host : new String[] {"localhost:" + port, "127.0.0.1:" + port, "[::1]:" + port, "LOCALHOST:" + port}) {
            assertThat(editor.raw("GET", "/api/meta", null, Map.of("Host", host)).status()).as(host).isEqualTo(200);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://evil.example", "http://127.0.0.1", "http://localhost:1", "null", "file://"})
    void crossOriginRequestsAreRefused(String origin) {
        Map<String, String> headers = Map.of("Origin", origin);

        assertThat(editor.send("PUT", "/api/tables/deposit_rate.stone.json", table, headers).status()).isEqualTo(403);
        assertThat(editor.send("POST", "/api/validate", table, headers).status()).isEqualTo(403);
        assertThat(editor.send("GET", "/api/tables", null, headers).status()).isEqualTo(403);
    }

    @Test
    void sameOriginRequestsAreAccepted() {
        Map<String, String> headers = Map.of("Origin", "http://" + editor.host(), "Sec-Fetch-Site", "same-origin");
        assertThat(editor.send("POST", "/api/validate", table, headers).status()).isEqualTo(200);
    }

    @Test
    void crossSiteFetchMetadataIsRefusedForTheApi() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Sec-Fetch-Site", "cross-site");
        assertThat(editor.send("GET", "/api/tables", null, headers).status()).isEqualTo(403);
        // A link from the intranet portal to the editor's page still works.
        assertThat(editor.send("GET", "/", null, headers).status()).isEqualTo(200);
    }

    @Test
    void corsPreflightIsNotAnswered() {
        EditorHarness.Response response = editor.send("OPTIONS", "/api/tables", null, Map.of());
        assertThat(response.status()).isEqualTo(405);
        assertThat(response.header("Access-Control-Allow-Origin")).isNull();
    }

    @Test
    void configuredProxyHostIsAccepted(@TempDir Path other) throws IOException {
        Files.createDirectories(other);
        try (EditorServer proxied = EditorServer.builder().rulesDir(other).port(0).allowedHost("rules.bank.example").start()) {
            RequestGuard guard = new RequestGuard("127.0.0.1", proxied.port(), java.util.Set.of("rules.bank.example"));
            guard.check("rules.bank.example", "https://rules.bank.example", "same-origin", true);
            assertThat(guard.allowedHosts()).contains("rules.bank.example", "localhost:" + proxied.port());
        }
    }
}
