//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.cel;

import dev.cel.common.CelFunctionDecl;
import dev.cel.common.CelOverloadDecl;
import dev.cel.common.types.CelType;
import dev.cel.common.types.ListType;
import dev.cel.common.types.SimpleType;
import dev.cel.runtime.CelEvaluationException;
import dev.cel.runtime.CelFunctionBinding;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Fuzzy name matching for screening rules (AML/KYC).
 *
 * <ul>
 *   <li>{@code normalizeName(s)}: strips accents (including Vietnamese {@code đ}), lowercases,
 *       turns punctuation into spaces and collapses whitespace.</li>
 *   <li>{@code similarity(a, b)}: Jaro-Winkler similarity of the normalized names.</li>
 *   <li>{@code nameSimilarity(a, b)}: the larger of {@code similarity(a, b)} and the similarity
 *       of the names with their words sorted, so reordered names match.</li>
 *   <li>{@code levenshtein(a, b)}: edit distance of the normalized names.</li>
 *   <li>{@code maxNameSimilarity(name, names)}: the best {@code nameSimilarity} against a list.</li>
 * </ul>
 *
 * <p>Scores are {@code decimal} values in [0, 1] with exactly four decimal places, rounded
 * HALF_EVEN from the exact rational Jaro-Winkler value. No floating point is involved, so
 * scores are identical on every JVM. All functions work on Unicode code points.
 *
 * <p>Jaro-Winkler follows Winkler's {@code strcmp95} and the Apache Commons Text and
 * jellyfish implementations: match window {@code max(|a|, |b|) / 2 - 1}, transpositions
 * halved with integer division, prefix scale 0.1 over at most four characters, and the
 * prefix boost applied only when the Jaro similarity exceeds 0.7. Two empty names score 1;
 * an empty name and a non-empty one score 0.
 */
final class FuzzyLibrary {

    /** Largest list accepted by {@code maxNameSimilarity}. */
    static final int MAX_LIST_SIZE = 100_000;

    /** Longest name, in code points after normalization, accepted by the comparison functions. */
    static final int MAX_NAME_LENGTH = 1_000;

    private static final CelType STRING = SimpleType.STRING;
    private static final CelType INT = SimpleType.INT;
    private static final CelType DECIMAL = DecimalLibrary.TYPE;

    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final int PREFIX_LIMIT = 4;
    private static final BigInteger TEN = BigInteger.TEN;
    private static final BigInteger SEVEN = BigInteger.valueOf(7);

    private FuzzyLibrary() {}

    static List<CelFunctionDecl> declarations() {
        return List.of(
                CelFunctionDecl.newFunctionDeclaration("normalizeName",
                        CelOverloadDecl.newGlobalOverload("rulestone_normalize_name_string", STRING, STRING)),
                CelFunctionDecl.newFunctionDeclaration("similarity",
                        CelOverloadDecl.newGlobalOverload("rulestone_similarity_string_string",
                                DECIMAL, STRING, STRING)),
                CelFunctionDecl.newFunctionDeclaration("nameSimilarity",
                        CelOverloadDecl.newGlobalOverload("rulestone_name_similarity_string_string",
                                DECIMAL, STRING, STRING)),
                CelFunctionDecl.newFunctionDeclaration("levenshtein",
                        CelOverloadDecl.newGlobalOverload("rulestone_levenshtein_string_string",
                                INT, STRING, STRING)),
                CelFunctionDecl.newFunctionDeclaration("maxNameSimilarity",
                        CelOverloadDecl.newGlobalOverload("rulestone_max_name_similarity_string_list",
                                DECIMAL, STRING, ListType.create(STRING))));
    }

    static List<CelFunctionBinding> bindings() {
        return List.of(
                CelFunctionBinding.from("rulestone_normalize_name_string", String.class,
                        FuzzyLibrary::normalizeName),
                CelFunctionBinding.from("rulestone_similarity_string_string", String.class, String.class,
                        (a, b) -> DecimalLibrary.wrap(similarity(a, b))),
                CelFunctionBinding.from("rulestone_name_similarity_string_string", String.class, String.class,
                        (a, b) -> DecimalLibrary.wrap(nameSimilarity(a, b))),
                CelFunctionBinding.from("rulestone_levenshtein_string_string", String.class, String.class,
                        (a, b) -> (long) levenshtein(a, b)),
                CelFunctionBinding.from("rulestone_max_name_similarity_string_list", String.class, List.class,
                        (name, names) -> DecimalLibrary.wrap(maxNameSimilarity(name, names))));
    }

    // Error messages never quote argument values: they are typically customer names.

    /**
     * NFD, drop combining marks, map {@code đ}/{@code Đ} to {@code d} (they have no
     * decomposition), lowercase, replace every code point that is not a letter or digit
     * with a space, collapse runs of spaces and trim.
     */
    static String normalizeName(String s) {
        String stripped = MARKS.matcher(Normalizer.normalize(s, Normalizer.Form.NFD)).replaceAll("")
                .replace('đ', 'd')
                .replace('Đ', 'd')
                .toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(stripped.length());
        boolean pendingSpace = false;
        for (int i = 0; i < stripped.length(); ) {
            int cp = stripped.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isLetterOrDigit(cp)) {
                if (pendingSpace && out.length() > 0) {
                    out.append(' ');
                }
                pendingSpace = false;
                out.appendCodePoint(cp);
            } else {
                pendingSpace = true;
            }
        }
        return out.toString();
    }

    static BigDecimal similarity(String a, String b) throws CelEvaluationException {
        return jaroWinkler(codePoints(normalizeName(a)), codePoints(normalizeName(b)));
    }

    static BigDecimal nameSimilarity(String a, String b) throws CelEvaluationException {
        return nameSimilarity(new Name(normalizeName(a)), new Name(normalizeName(b)));
    }

    static int levenshtein(String a, String b) throws CelEvaluationException {
        int[] x = codePoints(normalizeName(a));
        int[] y = codePoints(normalizeName(b));
        if (x.length < y.length) {
            int[] swap = x;
            x = y;
            y = swap;
        }
        int[] previous = new int[y.length + 1];
        int[] current = new int[y.length + 1];
        for (int j = 0; j <= y.length; j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= x.length; i++) {
            current[0] = i;
            for (int j = 1; j <= y.length; j++) {
                int substitution = previous[j - 1] + (x[i - 1] == y[j - 1] ? 0 : 1);
                current[j] = Math.min(substitution, Math.min(previous[j], current[j - 1]) + 1);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[y.length];
    }

    static BigDecimal maxNameSimilarity(String name, List<?> names) throws CelEvaluationException {
        if (names.size() > MAX_LIST_SIZE) {
            throw new CelEvaluationException(
                    "maxNameSimilarity() list must not have more than " + MAX_LIST_SIZE + " elements");
        }
        Name target = new Name(normalizeName(name));
        target.codePoints();
        BigDecimal best = BigDecimal.ZERO.setScale(4);
        for (Object element : names) {
            if (!(element instanceof String candidate)) {
                throw new CelEvaluationException("maxNameSimilarity() list elements must be strings");
            }
            BigDecimal score = nameSimilarity(target, new Name(normalizeName(candidate)));
            if (score.compareTo(best) > 0) {
                best = score;
                if (best.compareTo(BigDecimal.ONE) == 0) {
                    break;
                }
            }
        }
        return best;
    }

    private static BigDecimal nameSimilarity(Name a, Name b) throws CelEvaluationException {
        BigDecimal direct = jaroWinkler(a.codePoints(), b.codePoints());
        BigDecimal sorted = jaroWinkler(a.sortedCodePoints(), b.sortedCodePoints());
        return direct.max(sorted);
    }

    /**
     * Jaro-Winkler similarity, computed as an exact fraction and rounded once to four
     * decimal places.
     */
    private static BigDecimal jaroWinkler(int[] a, int[] b) {
        if (a.length == 0 || b.length == 0) {
            return (a.length == b.length ? BigDecimal.ONE : BigDecimal.ZERO).setScale(4);
        }
        int window = Math.max(Math.max(a.length, b.length) / 2 - 1, 0);
        boolean[] matchedA = new boolean[a.length];
        boolean[] matchedB = new boolean[b.length];
        int matches = 0;
        for (int i = 0; i < a.length; i++) {
            int end = Math.min(i + window + 1, b.length);
            for (int j = Math.max(0, i - window); j < end; j++) {
                if (!matchedB[j] && a[i] == b[j]) {
                    matchedA[i] = true;
                    matchedB[j] = true;
                    matches++;
                    break;
                }
            }
        }
        if (matches == 0) {
            return BigDecimal.ZERO.setScale(4);
        }
        int outOfOrder = 0;
        for (int i = 0, j = 0; i < a.length; i++) {
            if (matchedA[i]) {
                while (!matchedB[j]) {
                    j++;
                }
                if (a[i] != b[j]) {
                    outOfOrder++;
                }
                j++;
            }
        }
        int transpositions = outOfOrder / 2;
        int prefix = 0;
        while (prefix < PREFIX_LIMIT && prefix < a.length && prefix < b.length && a[prefix] == b[prefix]) {
            prefix++;
        }

        // jaro = (m/|a| + m/|b| + (m-t)/m) / 3 = num / den
        BigInteger m = BigInteger.valueOf(matches);
        BigInteger lenA = BigInteger.valueOf(a.length);
        BigInteger lenB = BigInteger.valueOf(b.length);
        BigInteger num = m.multiply(m).multiply(lenB)
                .add(m.multiply(m).multiply(lenA))
                .add(m.subtract(BigInteger.valueOf(transpositions)).multiply(lenA).multiply(lenB));
        BigInteger den = BigInteger.valueOf(3).multiply(m).multiply(lenA).multiply(lenB);

        // Winkler boost when jaro > 0.7: jw = jaro + prefix/10 * (1 - jaro) = (10 num + prefix (den - num)) / (10 den)
        if (num.multiply(TEN).compareTo(den.multiply(SEVEN)) > 0) {
            num = num.multiply(TEN).add(BigInteger.valueOf(prefix).multiply(den.subtract(num)));
            den = den.multiply(TEN);
        }
        return new BigDecimal(num).divide(new BigDecimal(den), 4, RoundingMode.HALF_EVEN);
    }

    private static int[] codePoints(String normalized) throws CelEvaluationException {
        int[] codePoints = normalized.codePoints().toArray();
        if (codePoints.length > MAX_NAME_LENGTH) {
            throw new CelEvaluationException(
                    "fuzzy matching arguments must not be longer than " + MAX_NAME_LENGTH
                            + " characters after normalization");
        }
        return codePoints;
    }

    /** A normalized name and, lazily, the same name with its words in sorted order. */
    private static final class Name {

        private final String normalized;
        private int[] codePoints;
        private int[] sortedCodePoints;

        Name(String normalized) {
            this.normalized = normalized;
        }

        int[] codePoints() throws CelEvaluationException {
            if (codePoints == null) {
                codePoints = FuzzyLibrary.codePoints(normalized);
            }
            return codePoints;
        }

        int[] sortedCodePoints() throws CelEvaluationException {
            if (sortedCodePoints == null) {
                codePoints();
                String[] tokens = normalized.isEmpty() ? new String[0] : normalized.split(" ");
                Arrays.sort(tokens);
                sortedCodePoints = String.join(" ", tokens).codePoints().toArray();
            }
            return sortedCodePoints;
        }
    }
}
