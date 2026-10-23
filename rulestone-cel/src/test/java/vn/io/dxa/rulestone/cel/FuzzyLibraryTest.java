//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.cel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Tests the fuzzy matching functions and every example in docs/fuzzy-matching.md. */
class FuzzyLibraryTest {

    private final ExpressionEnvironment env = ExpressionEnvironment.builder()
            .variable("customer", ValueType.struct(Map.of("fullName", ValueType.STRING)))
            .variable("watchlist", ValueType.listOf(ValueType.STRING))
            .build();

    private Object eval(String source, ValueType type, String fullName, List<String> watchlist) {
        return env.compile(source, type).evaluate(env.bind(Map.of(
                "customer", Map.of("fullName", fullName),
                "watchlist", watchlist)));
    }

    private Object eval(String source, ValueType type) {
        return eval(source, type, "", List.of());
    }

    private BigDecimal score(String source) {
        return (BigDecimal) eval(source, ValueType.DECIMAL);
    }

    // normalizeName

    @Test
    void normalizesVietnameseNames() throws Exception {
        assertThat(eval("normalizeName('  NGUYỄN  Văn Đức, Jr. ')", ValueType.STRING)).isEqualTo("nguyen van duc jr");
        assertThat(FuzzyLibrary.normalizeName("Đặng Thị Ngọc Hân")).isEqualTo("dang thi ngoc han");
        assertThat(FuzzyLibrary.normalizeName("ĐOÀN đình HUỆ")).isEqualTo("doan dinh hue");
        assertThat(FuzzyLibrary.normalizeName("Phạm Thị Ánh Tuyết")).isEqualTo("pham thi anh tuyet");
    }

    @Test
    void normalizationHandlesDecomposedInputAndOtherScripts() throws Exception {
        // "ễ" written as e + combining circumflex + combining tilde (already decomposed).
        assertThat(FuzzyLibrary.normalizeName("Nguyễn")).isEqualTo("nguyen");
        assertThat(FuzzyLibrary.normalizeName("José Müller-Lüdenscheidt")).isEqualTo("jose muller ludenscheidt");
        assertThat(FuzzyLibrary.normalizeName("İSTANBUL")).isEqualTo("istanbul");
        assertThat(FuzzyLibrary.normalizeName("O'Brien\tJr.\n")).isEqualTo("o brien jr");
        assertThat(FuzzyLibrary.normalizeName("Владимир Путин")).isEqualTo("владимир путин");
        assertThat(FuzzyLibrary.normalizeName("ACME Ltd. No.7")).isEqualTo("acme ltd no 7");
        // Letters outside the Basic Multilingual Plane are kept whole.
        assertThat(FuzzyLibrary.normalizeName("𠀋-x")).isEqualTo("𠀋 x");
    }

    @Test
    void normalizationOfBlankOrPunctuationOnlyTextIsEmpty() throws Exception {
        assertThat(FuzzyLibrary.normalizeName("")).isEmpty();
        assertThat(FuzzyLibrary.normalizeName("   ")).isEmpty();
        assertThat(FuzzyLibrary.normalizeName(" -,.; ")).isEmpty();
    }

    // similarity (Jaro-Winkler)

    @ParameterizedTest
    @CsvSource({
            // Published reference values (Winkler 1990; Wikipedia)
            "MARTHA,   MARHTA,   0.9611",
            "DWAYNE,   DUANE,    0.8400",
            "DIXON,    DICKSONX, 0.8133",
            "JONES,    JOHNSON,  0.8324",
            "MASSEY,   MASSIE,   0.9333",
            "CRATE,    TRACE,    0.7333",
            // Identical and disjoint
            "abc,      abc,      1.0000",
            "abc,      xyz,      0.0000",
            "kitten,   sitting,  0.7460",
    })
    void matchesReferenceValues(String a, String b, String expected) throws Exception {
        assertThat(FuzzyLibrary.similarity(a, b)).isEqualTo(new BigDecimal(expected));
        assertThat(FuzzyLibrary.similarity(b, a)).isEqualTo(new BigDecimal(expected));
    }

    @Test
    void prefixBoostAppliesOnlyAboveJaroThreshold() throws Exception {
        // Jaro = 2/3 (not above 0.7), common prefix "abc": no boost. With an unconditional boost it would be 0.7667.
        assertThat(FuzzyLibrary.similarity("abcxyz", "abcuvw")).isEqualTo(new BigDecimal("0.6667"));
        // Jaro of MARTHA/MARHTA is 0.9444, boosted by its 3-letter prefix.
        assertThat(FuzzyLibrary.similarity("MARTHA", "MARHTA")).isEqualTo(new BigDecimal("0.9611"));
        // Prefix is capped at four characters.
        assertThat(FuzzyLibrary.similarity("abcdefgh", "abcdefgx")).isEqualTo(new BigDecimal("0.9500"));
    }

    @Test
    void similarityIgnoresAccentsCaseAndPunctuation() {
        assertThat(score("similarity('Nguyễn Văn An', 'NGUYEN van an')")).isEqualTo(new BigDecimal("1.0000"));
        assertThat(score("similarity('Lê Văn Tám', 'le-van  tam')")).isEqualTo(new BigDecimal("1.0000"));
        assertThat(score("similarity('Đặng Thị Ngọc Hân', 'Dang Thi Ngoc Han')")).isEqualTo(new BigDecimal("1.0000"));
    }

    @Test
    void emptyNames() {
        assertThat(score("similarity('', '')")).isEqualTo(new BigDecimal("1.0000"));
        assertThat(score("similarity(' ', '-')")).isEqualTo(new BigDecimal("1.0000"));
        assertThat(score("similarity('a', '')")).isEqualTo(new BigDecimal("0.0000"));
        assertThat(score("similarity('', 'a')")).isEqualTo(new BigDecimal("0.0000"));
        assertThat(score("nameSimilarity('', '')")).isEqualTo(new BigDecimal("1.0000"));
        assertThat(score("nameSimilarity('Nguyen', '')")).isEqualTo(new BigDecimal("0.0000"));
        assertThat(eval("levenshtein('', 'abc')", ValueType.INT)).isEqualTo(3L);
        assertThat(eval("levenshtein('', '')", ValueType.INT)).isEqualTo(0L);
    }

    @Test
    void scoresHaveFourDecimalPlacesAndStayInRange() throws Exception {
        List<String> names = List.of("", "a", "ab", "Nguyen Van An", "Van An Nguyen", "Tran Thi Hoa",
                "MARTHA", "Ngô Bảo Châu", "x y z", "aaaaaaaaaaaaaaaaaaaa");
        for (String a : names) {
            for (String b : names) {
                for (BigDecimal s : List.of(FuzzyLibrary.similarity(a, b), FuzzyLibrary.nameSimilarity(a, b))) {
                    assertThat(s.scale()).isEqualTo(4);
                    assertThat(s).isBetween(BigDecimal.ZERO, BigDecimal.ONE);
                }
                assertThat(FuzzyLibrary.similarity(a, b)).isEqualTo(FuzzyLibrary.similarity(b, a));
                assertThat(FuzzyLibrary.nameSimilarity(a, b)).isGreaterThanOrEqualTo(FuzzyLibrary.similarity(a, b));
            }
        }
    }

    @Test
    void resultsAreDeterministic() throws Exception {
        BigDecimal first = FuzzyLibrary.similarity("Nguyen Thi Minh Khai", "Nguyen T. Minh Khai");
        for (int i = 0; i < 1_000; i++) {
            assertThat(FuzzyLibrary.similarity("Nguyen Thi Minh Khai", "Nguyen T. Minh Khai")).isEqualTo(first);
        }
        assertThat(first).isEqualTo(new BigDecimal("0.9244"));
    }

    // nameSimilarity

    @Test
    void nameSimilarityMatchesReorderedNames() {
        assertThat(score("similarity('Nguyen Van An', 'Van An Nguyen')")).isEqualTo(new BigDecimal("0.5971"));
        assertThat(score("nameSimilarity('Nguyen Van An', 'Van An Nguyen')")).isEqualTo(new BigDecimal("1.0000"));
        assertThat(score("nameSimilarity('Smith, John', 'JOHN SMITH')")).isEqualTo(new BigDecimal("1.0000"));
        assertThat(score("nameSimilarity('Ngô Bảo Châu', 'Châu Ngô Bảo')")).isEqualTo(new BigDecimal("1.0000"));
        assertThat(score("nameSimilarity('Nguyen Thi Minh Khai', 'Nguyen T. Minh Khai')"))
                .isEqualTo(new BigDecimal("0.9800"));
    }

    @Test
    void nameSimilarityKeepsTheDirectScoreWhenItIsHigher() {
        assertThat(score("nameSimilarity('Tran Van Binh', 'Van An Nguyen')")).isEqualTo(new BigDecimal("0.6603"));
        assertThat(score("nameSimilarity('Nguyen Van An', 'Nguyen Van Anh')")).isEqualTo(new BigDecimal("0.9857"));
    }

    // levenshtein

    @ParameterizedTest
    @CsvSource({
            "kitten,        sitting,       3",
            "flaw,          lawn,          2",
            "MARTHA,        MARHTA,        2",
            "Lê Văn Tám,    Le Van Tam,    0",
            "Nguyen Van An, Nguyen Van Anh, 1",
            "Nguyen Van An, Van An Nguyen, 10",
            "abc,           abc,           0",
    })
    void levenshteinDistance(String a, String b, long expected) {
        assertThat(eval("levenshtein('" + a + "', '" + b + "')", ValueType.INT)).isEqualTo(expected);
        assertThat(eval("levenshtein('" + b + "', '" + a + "')", ValueType.INT)).isEqualTo(expected);
    }

    @Test
    void levenshteinCountsCodePointsNotUtf16Units() throws Exception {
        assertThat(FuzzyLibrary.levenshtein("𠀋", "𠀌")).isEqualTo(1);
        assertThat(FuzzyLibrary.similarity("𠀋a", "𠀋a")).isEqualTo(new BigDecimal("1.0000"));
    }

    // maxNameSimilarity and screening rules

    @Test
    void maxNameSimilarityOverAWatchlist() throws Exception {
        List<String> watchlist = List.of("Tran Van Binh", "Van An Nguyen", "Le Thi Hoa");

        assertThat(eval("maxNameSimilarity(customer.fullName, watchlist)", ValueType.DECIMAL,
                "Nguyễn Văn An", watchlist)).isEqualTo(new BigDecimal("1.0000"));
        assertThat(eval("maxNameSimilarity(customer.fullName, watchlist)", ValueType.DECIMAL,
                "Pham Minh Chinh", watchlist)).isEqualTo(FuzzyLibrary.maxNameSimilarity("Pham Minh Chinh", watchlist));
        assertThat(eval("maxNameSimilarity(customer.fullName, watchlist)", ValueType.DECIMAL,
                "Nguyen Van An", List.of())).isEqualTo(new BigDecimal("0.0000"));
    }

    @Test
    void maxNameSimilarityIsTheMaximumOfNameSimilarity() throws Exception {
        List<String> watchlist = List.of("Tran Van Binh", "Nguyen Van Anh", "Le Thi Hoa", "", "Nguyen An");
        BigDecimal expected = BigDecimal.ZERO;
        for (String w : watchlist) {
            expected = expected.max(FuzzyLibrary.nameSimilarity("Nguyen Van An", w));
        }
        assertThat(FuzzyLibrary.maxNameSimilarity("Nguyen Van An", watchlist)).isEqualTo(expected)
                .isEqualTo(new BigDecimal("0.9857"));
    }

    @Test
    void screeningRulesFromTheDocumentation() {
        List<String> watchlist = List.of("Tran Van Binh", "Van An Nguyen");
        String byMax = "maxNameSimilarity(customer.fullName, watchlist) >= decimal('0.92')";
        String byExists = "watchlist.exists(w, nameSimilarity(w, customer.fullName) >= decimal('0.92'))";

        for (String rule : List.of(byMax, byExists)) {
            assertThat(eval(rule, ValueType.BOOL, "NGUYỄN Văn An", watchlist)).isEqualTo(true);
            assertThat(eval(rule, ValueType.BOOL, "Le Thi Hoa", watchlist)).isEqualTo(false);
            assertThat(eval(rule, ValueType.BOOL, "Le Thi Hoa", List.of())).isEqualTo(false);
        }
    }

    @Test
    void acceptsListsUpToTheCap() {
        List<String> atCap = Collections.nCopies(FuzzyLibrary.MAX_LIST_SIZE, "x");

        assertThat(eval("maxNameSimilarity(customer.fullName, watchlist)", ValueType.DECIMAL, "y", atCap))
                .isEqualTo(new BigDecimal("0.0000"));
    }

    @Test
    void rejectsListsAboveTheCap() {
        List<String> tooMany = Collections.nCopies(FuzzyLibrary.MAX_LIST_SIZE + 1, "x");

        assertThatThrownBy(() -> eval("maxNameSimilarity(customer.fullName, watchlist) > decimal(0)",
                ValueType.BOOL, "x", tooMany))
                .isInstanceOf(ExpressionException.class)
                .hasMessageContaining("must not have more than 100000 elements");
    }

    @Test
    void rejectsOverlongNames() throws Exception {
        String atCap = "a".repeat(FuzzyLibrary.MAX_NAME_LENGTH);
        String tooLong = "a".repeat(FuzzyLibrary.MAX_NAME_LENGTH + 1);

        assertThat(FuzzyLibrary.similarity(atCap, atCap)).isEqualTo(new BigDecimal("1.0000"));
        assertThat(FuzzyLibrary.levenshtein(atCap, "")).isEqualTo(FuzzyLibrary.MAX_NAME_LENGTH);
        // Separators collapse before the length is checked.
        assertThat(FuzzyLibrary.similarity(atCap + "  ,,  ", atCap)).isEqualTo(new BigDecimal("1.0000"));
        for (String rule : List.of("similarity(customer.fullName, 'a')", "nameSimilarity('a', customer.fullName)",
                "decimal(levenshtein(customer.fullName, 'a'))", "maxNameSimilarity(customer.fullName, [])",
                "maxNameSimilarity('a', [customer.fullName])")) {
            assertThatThrownBy(() -> eval(rule, ValueType.DECIMAL, tooLong, List.of()))
                    .isInstanceOf(ExpressionException.class)
                    .hasMessageContaining("must not be longer than 1000 characters");
        }
    }

    @Test
    void comprehensionsOverLargeWatchlistsAreBoundedByMaxIterations() {
        List<String> large = Collections.nCopies(ExpressionEnvironment.DEFAULT_MAX_ITERATIONS + 1, "Le Thi Hoa");

        assertThatThrownBy(() -> eval("watchlist.exists(w, nameSimilarity(w, customer.fullName) >= decimal('0.92'))",
                ValueType.BOOL, "Nguyen Van An", large))
                .isInstanceOf(ExpressionException.class);
        assertThat(eval("maxNameSimilarity(customer.fullName, watchlist) >= decimal('0.92')",
                ValueType.BOOL, "Nguyen Van An", large)).isEqualTo(false);
    }

    @Test
    void rejectsWrongArgumentTypesAtCompileTime() {
        assertThatThrownBy(() -> env.compile("similarity('a', 1) > decimal(0)", ValueType.BOOL))
                .isInstanceOf(ExpressionException.class);
        assertThatThrownBy(() -> env.compile("maxNameSimilarity('a', [1, 2]) > decimal(0)", ValueType.BOOL))
                .isInstanceOf(ExpressionException.class);
        assertThatThrownBy(() -> env.compile("similarity('a', 'b') > 0.9", ValueType.BOOL))
                .isInstanceOf(ExpressionException.class);
    }

    @Test
    void errorMessagesNeverContainInputValues() {
        String secret = "Nguyen Van Bimat";
        String longSecret = (secret + " ").repeat(100);
        List<String> tooMany = Collections.nCopies(FuzzyLibrary.MAX_LIST_SIZE + 1, secret);

        assertThatThrownBy(() -> eval("maxNameSimilarity(customer.fullName, watchlist) > decimal(0)",
                ValueType.BOOL, secret, tooMany))
                .satisfies(e -> assertThat(stackTraceOf(e)).doesNotContainIgnoringCase("bimat"));
        for (String rule : List.of("similarity(customer.fullName, 'x') > decimal(0)",
                "nameSimilarity(customer.fullName, 'x') > decimal(0)",
                "levenshtein(customer.fullName, 'x') > 0",
                "maxNameSimilarity('x', [customer.fullName]) > decimal(0)")) {
            assertThatThrownBy(() -> eval(rule, ValueType.BOOL, longSecret, List.of()))
                    .satisfies(e -> assertThat(stackTraceOf(e)).doesNotContainIgnoringCase("bimat"));
        }
    }

    // Documentation

    private static final Path DOC = Path.of("../docs/fuzzy-matching.md");
    private static final Pattern EXAMPLE_ROW = Pattern.compile("^\\| `([^`]+)` \\| `([^`]+)` \\|$");

    /** The rows {@code | `expression` | `result` |} of the "Worked examples" section, as [expression, result]. */
    private static List<String[]> documentedExamples() throws IOException {
        List<String[]> rows = new ArrayList<>();
        boolean inSection = false;
        for (String line : Files.readAllLines(DOC, StandardCharsets.UTF_8)) {
            if (line.startsWith("## ")) {
                inSection = line.equals("## Worked examples");
                continue;
            }
            Matcher row = EXAMPLE_ROW.matcher(line);
            if (inSection && row.matches()) {
                rows.add(new String[] {row.group(1), row.group(2)});
            }
        }
        return rows;
    }

    @Test
    void everyExampleInTheDocumentationHolds() throws IOException {
        List<String[]> examples = documentedExamples();
        for (String[] example : examples) {
            String expression = example[0];
            String expected = example[1];
            String actual;
            if (expected.startsWith("'")) {
                actual = "'" + eval(expression, ValueType.STRING) + "'";
            } else if (expected.equals("true") || expected.equals("false")) {
                actual = String.valueOf(eval(expression, ValueType.BOOL));
            } else if (expected.contains(".")) {
                actual = ((BigDecimal) eval(expression, ValueType.DECIMAL)).toPlainString();
            } else {
                actual = String.valueOf(eval(expression, ValueType.INT));
            }
            assertThat(actual).as(expression).isEqualTo(expected);
        }
        assertThat(examples).hasSizeGreaterThanOrEqualTo(30);
    }

    /** Every four-decimal score quoted anywhere in the page must be the result of a worked example. */
    @Test
    void everyScoreQuotedInTheDocumentationIsAnExampleResult() throws IOException {
        List<String> results = documentedExamples().stream().map(example -> example[1]).toList();
        Matcher score = Pattern.compile("\\b[01]\\.\\d{4}\\b").matcher(Files.readString(DOC, StandardCharsets.UTF_8));
        int quoted = 0;
        while (score.find()) {
            assertThat(results).as("score %s quoted in the documentation", score.group()).contains(score.group());
            quoted++;
        }
        assertThat(quoted).isGreaterThan(30);
    }

    private static String stackTraceOf(Throwable t) {
        var out = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(out));
        return out.toString();
    }
}
