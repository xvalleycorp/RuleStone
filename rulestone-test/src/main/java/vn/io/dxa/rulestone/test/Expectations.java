//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import vn.io.dxa.rulestone.api.DecisionResult;

/**
 * Compares a {@link DecisionResult} with expected values and describes each mismatch.
 * Shared by {@link DecisionCheck} and {@link StoneTestCase} so both report alike.
 *
 * <p>Numbers are compared by value ({@code 309000000} equals {@code 309000000.00}); lists
 * element by element and maps entry by entry, with the same rule.
 */
final class Expectations {

    private Expectations() {}

    /** Checks one output; a {@code null} expected value means the output must be absent. */
    static Optional<String> output(DecisionResult result, String name, Object expected) {
        Object actual = result.output().get(name);
        if (expected == null) {
            return actual == null ? Optional.empty()
                    : Optional.of("output " + quote(name) + ": expected no value but was " + format(actual));
        }
        if (actual == null) {
            return Optional.of("output " + quote(name) + ": expected " + format(expected)
                    + " but the decision produced no such output");
        }
        return same(expected, actual) ? Optional.empty()
                : Optional.of("output " + quote(name) + ": expected " + format(expected) + " but was " + format(actual));
    }

    /** Reports every output the result has but {@code expected} does not list. */
    static List<String> unexpectedOutputs(DecisionResult result, Map<String, ?> expected) {
        List<String> problems = new ArrayList<>();
        result.output().forEach((name, value) -> {
            if (expected.get(name) == null) {
                problems.add("output " + quote(name) + ": not expected but was " + format(value));
            }
        });
        return problems;
    }

    static Optional<String> reasonCodes(DecisionResult result, List<String> expected) {
        return list("reason codes", expected, result.reasonCodes());
    }

    static Optional<String> matchedRules(DecisionResult result, List<String> expected) {
        return list("matched rules", expected, result.matchedRules());
    }

    /**
     * Checks the rule version: {@code "3"} matches any content of version 3, while
     * {@code "3@sha256:…"} pins the exact content.
     */
    static Optional<String> ruleVersion(DecisionResult result, String expected) {
        String actual = result.ruleVersion();
        boolean matches = expected.contains("@") ? actual.equals(expected) : versionLabel(actual).equals(expected);
        return matches ? Optional.empty()
                : Optional.of("rule version: expected " + expected + " but was " + actual);
    }

    /** {@code "3@sha256:…"} becomes {@code "3"}. */
    static String versionLabel(String ruleVersion) {
        int at = ruleVersion.indexOf('@');
        return at < 0 ? ruleVersion : ruleVersion.substring(0, at);
    }

    /** One line describing a successful result, for messages about a failure that did not happen. */
    static String describe(DecisionResult result) {
        return "output " + format(result.output()) + ", matched rules " + result.matchedRules();
    }

    /**
     * Rejects values that can never be compared reliably: {@code null} (outside the documented
     * "absent" meaning) and floating point.
     */
    static void requireExact(Object value, String what) {
        if (value == null) {
            throw new IllegalArgumentException(what + " must not be null");
        }
        if (value instanceof Double || value instanceof Float) {
            throw new IllegalArgumentException(what
                    + " is a floating-point number; use BigDecimal (e.g. new BigDecimal(\"0.048\")) or an integer");
        }
        if (value instanceof Iterable<?> items) {
            int i = 0;
            for (Object item : items) {
                requireExact(item, what + "[" + i++ + "]");
            }
        } else if (value instanceof Map<?, ?> map) {
            map.forEach((key, item) -> requireExact(item, what + "." + key));
        }
    }

    static boolean same(Object expected, Object actual) {
        BigDecimal expectedNumber = exactNumber(expected);
        BigDecimal actualNumber = exactNumber(actual);
        if (expectedNumber != null && actualNumber != null) {
            return expectedNumber.compareTo(actualNumber) == 0;
        }
        if (expected instanceof List<?> e && actual instanceof List<?> a) {
            if (e.size() != a.size()) {
                return false;
            }
            Iterator<?> ai = a.iterator();
            for (Object item : e) {
                if (!same(item, ai.next())) {
                    return false;
                }
            }
            return true;
        }
        if (expected instanceof Map<?, ?> e && actual instanceof Map<?, ?> a) {
            if (!e.keySet().equals(a.keySet())) {
                return false;
            }
            return e.entrySet().stream().allMatch(entry -> same(entry.getValue(), a.get(entry.getKey())));
        }
        return expected.equals(actual);
    }

    /** Formats a value the way it would be written in JSON: strings quoted, decimals in plain notation. */
    static String format(Object value) {
        if (value instanceof String s) {
            return quote(s);
        }
        if (value instanceof BigDecimal d) {
            return d.toPlainString();
        }
        if (value instanceof List<?> list) {
            return list.stream().map(Expectations::format).collect(Collectors.joining(", ", "[", "]"));
        }
        if (value instanceof Map<?, ?> map) {
            return map.entrySet().stream()
                    .map(e -> e.getKey() + "=" + format(e.getValue()))
                    .collect(Collectors.joining(", ", "{", "}"));
        }
        return String.valueOf(value);
    }

    static String quote(String text) {
        return "\"" + text + "\"";
    }

    private static Optional<String> list(String what, List<String> expected, List<String> actual) {
        return expected.equals(actual) ? Optional.empty()
                : Optional.of(what + ": expected " + expected + " but was " + actual);
    }

    private static BigDecimal exactNumber(Object value) {
        if (value instanceof BigDecimal d) {
            return d;
        }
        if (value instanceof BigInteger i) {
            return new BigDecimal(i);
        }
        if (value instanceof Long || value instanceof Integer || value instanceof Short || value instanceof Byte) {
            return BigDecimal.valueOf(((Number) value).longValue());
        }
        return null;
    }
}
