//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.editor;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class EditorMainTest {

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private int run(String... args) {
        return EditorMain.run(args, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    @Test
    void help() {
        assertThat(run("--help")).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("--rules-dir").contains("NO authentication");
    }

    @Test
    void rulesDirIsRequired() {
        assertThat(run("--port", "0")).isEqualTo(2);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("--rules-dir is required");
    }

    @Test
    void unknownOptionsAndBadValuesAreRejected() {
        assertThat(run("--rules-dir", ".", "--verbose")).isEqualTo(2);
        assertThat(run("--rules-dir", ".", "--port", "99999")).isEqualTo(2);
        assertThat(run("--rules-dir")).isEqualTo(2);
    }

    @Test
    void missingDirectoryFailsToStart() {
        assertThat(run("--rules-dir", "does-not-exist", "--port", "0")).isEqualTo(1);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("does not exist");
    }
}
