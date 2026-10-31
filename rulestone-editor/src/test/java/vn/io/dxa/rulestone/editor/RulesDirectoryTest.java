//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.editor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RulesDirectoryTest {

    @TempDir
    Path dir;

    @ParameterizedTest
    @ValueSource(strings = {"a.stone.json", "loan_eligibility.v3.stone.json", "loans/retail/a-b_c.stone.json", "1.stone.json"})
    void validNames(String name) {
        assertThat(RulesDirectory.isValidName(name)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", ".stone.json", "a.json", "a.stone.json.bak", "../a.stone.json", "./a.stone.json",
            "/a.stone.json", "a//b.stone.json", "a\\b.stone.json", ".git/a.stone.json", "a b.stone.json",
            "a/b/c/d/e/f/g/h/i/j.stone.json", "a%2Fb.stone.json", "ä.stone.json"})
    void invalidNames(String name) {
        assertThat(RulesDirectory.isValidName(name)).isFalse();
    }

    @Test
    void listsOnlyStoneJsonFilesAndSkipsHiddenDirectories() throws IOException {
        Files.createDirectories(dir.resolve(".git"));
        Files.createDirectories(dir.resolve("sub"));
        Files.writeString(dir.resolve(".git/x.stone.json"), "{}");
        Files.writeString(dir.resolve("sub/b.stone.json"), "{}");
        Files.writeString(dir.resolve("a.stone.json"), "{}");
        Files.writeString(dir.resolve("a.stone-test.json"), "{}");
        Files.writeString(dir.resolve("README.md"), "");

        assertThat(new RulesDirectory(dir).list()).containsExactly("a.stone.json", "sub/b.stone.json");
    }

    @Test
    void readsTheParameterSetsItCanRead() throws IOException {
        Files.createDirectories(dir.resolve("limits"));
        Files.writeString(dir.resolve("limits/retail.params.json"),
                "{ \"id\": \"retail\", \"version\": \"1\", \"values\": { \"maxDti\": { \"type\": \"decimal\", \"value\": \"0.45\" } } }");
        Files.writeString(dir.resolve("broken.params.json"), "{}");

        assertThat(new RulesDirectory(dir).paramSets()).extracting(s -> s.id() + "@" + s.version())
                .containsExactly("retail@1");
        assertThat(new RulesDirectory(dir).list()).isEmpty();
    }

    @Test
    void writesAtomicallyAndChecksTheEtag() throws IOException {
        RulesDirectory rules = new RulesDirectory(dir);
        String etag = rules.write("a.stone.json", "one\n", null, true);

        assertThat(rules.read("a.stone.json")).isEqualTo("one\n");
        assertThatThrownBy(() -> rules.write("a.stone.json", "x", null, true)).hasMessageContaining("already exists");
        assertThatThrownBy(() -> rules.write("a.stone.json", "x", "0".repeat(64), false)).hasMessageContaining("changed");
        rules.write("a.stone.json", "two\n", etag, false);
        assertThat(rules.read("a.stone.json")).isEqualTo("two\n");
        try (var files = Files.list(dir)) {
            assertThat(files.map(f -> f.getFileName().toString())).containsExactly("a.stone.json");
        }
    }

    @Test
    void refusesFilesThatAreNotUtf8() throws IOException {
        Files.write(dir.resolve("a.stone.json"), new byte[] {'{', (byte) 0xC3, '}'});
        assertThatThrownBy(() -> new RulesDirectory(dir).read("a.stone.json")).hasMessageContaining("UTF-8");
    }

    @Test
    void theDirectoryMustExist() {
        assertThatThrownBy(() -> new RulesDirectory(dir.resolve("missing"))).isInstanceOf(IllegalArgumentException.class);
    }
}
