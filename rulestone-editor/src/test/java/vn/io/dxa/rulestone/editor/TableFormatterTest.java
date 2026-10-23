//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.editor;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import vn.io.dxa.rulestone.core.DecisionTableJson;

class TableFormatterTest {

    static List<Path> examples() throws IOException {
        try (Stream<Path> files = Files.list(EditorHarness.EXAMPLES)) {
            return files.filter(f -> f.getFileName().toString().endsWith(".stone.json")).sorted().toList();
        }
    }

    @ParameterizedTest
    @MethodSource("examples")
    void formattingIsStableAndKeepsTheContentHash(Path example) {
        String original = EditorHarness.read(example);
        String formatted = TableFormatter.format(Json.parse(original));

        assertThat(TableFormatter.format(Json.parse(formatted))).isEqualTo(formatted);
        assertThat(TableValidator.ruleVersion(DecisionTableJson.read(formatted)))
                .isEqualTo(TableValidator.ruleVersion(DecisionTableJson.read(original)));
        assertThat(formatted).endsWith("}\n").doesNotContain("\t");
    }

    @Test
    void fixedKeyOrderAndCompactCells() {
        String json = """
                {"rules": [{"reasonCode": "X", "then": {"b": "'B'", "a": "1"}, "when": "true", "id": "R1",
                            "description": "Đúng"}],
                 "outputs": {"b": "string", "a": "int"}, "inputs": {"xs": ["decimal"]}, "hitPolicy": "FIRST",
                 "version": "1", "id": "t", "effectiveTo": "2027-01-01", "custom": 1}""";

        assertThat(TableFormatter.format(Json.parse(json))).isEqualTo("""
                {
                  "id": "t",
                  "version": "1",
                  "effectiveTo": "2027-01-01",
                  "hitPolicy": "FIRST",
                  "inputs": {
                    "xs": ["decimal"]
                  },
                  "outputs": {
                    "b": "string",
                    "a": "int"
                  },
                  "rules": [
                    {
                      "id": "R1",
                      "description": "Đúng",
                      "when": "true",
                      "then": {
                        "b": "'B'",
                        "a": "1"
                      },
                      "reasonCode": "X"
                    }
                  ],
                  "custom": 1
                }
                """);
    }

    @Test
    void longSingleEntriesAreNotInlined() {
        String long_ = "x".repeat(120);
        String formatted = TableFormatter.format(Json.parse("{\"rules\": [{\"then\": {\"a\": \"" + long_ + "\"}}]}"));
        assertThat(formatted).contains("\"then\": {\n        \"a\": \"" + long_ + "\"\n      }");
    }

    @Test
    void textIsEscapedOnlyWhereJsonRequires() {
        String formatted = TableFormatter.format(Json.parse("{\"description\": \"a \\\"quote\\\", a\\\\b, line\\nbreak, ngày hiệu lực\"}"));
        assertThat(formatted).contains("\"a \\\"quote\\\", a\\\\b, line\\nbreak, ngày hiệu lực\"");
    }
}
