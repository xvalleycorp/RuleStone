//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.api;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.OptionalInt;
import java.util.TreeMap;

/**
 * The ISO 4217 currency codes RuleStone accepts, with their minor units.
 *
 * <p>The table is fixed by RuleStone (the resource {@code iso4217.txt}, shared with the browser
 * engine) rather than taken from {@link java.util.Currency}, whose codes differ between JDK
 * versions. Every JDK therefore accepts exactly the same codes and rounds them the same way.
 * It holds the current codes up to ISO 4217 amendment {@value #AMENDMENT}, plus the withdrawn
 * codes that {@code java.util.Currency} has long accepted.
 */
public final class Iso4217 {

    /** The ISO 4217 amendment the table reflects. */
    public static final int AMENDMENT = 177;

    /** Code to minor units; -1 for a code without a minor unit, such as {@code XAU}. */
    private static final Map<String, Integer> MINOR_UNITS = load();

    private Iso4217() {}

    /**
     * Whether {@code code} is an upper-case ISO 4217 code in the table, e.g. {@code VND}.
     *
     * @param code the text to test; may be any string
     * @return true for a known code
     */
    public static boolean isCurrencyCode(String code) {
        return code != null && MINOR_UNITS.containsKey(code);
    }

    /**
     * The number of digits after the decimal point of the currency's minor unit.
     *
     * @param code an ISO 4217 code
     * @return the digits, e.g. 0 for {@code VND} and 2 for {@code USD}; empty for a code without a
     *         minor unit, such as {@code XAU}
     * @throws IllegalArgumentException if {@code code} is not in the table
     */
    public static OptionalInt minorUnits(String code) {
        Integer digits = code == null ? null : MINOR_UNITS.get(code);
        if (digits == null) {
            // The text is not quoted: it may come from customer data.
            throw new IllegalArgumentException("not an ISO 4217 currency code");
        }
        return digits < 0 ? OptionalInt.empty() : OptionalInt.of(digits);
    }

    /**
     * Every code in the table with its minor units, -1 for none.
     *
     * @return codes in alphabetical order
     */
    public static Map<String, Integer> table() {
        return MINOR_UNITS;
    }

    private static Map<String, Integer> load() {
        Map<String, Integer> table = new TreeMap<>();
        int amendment = -1;
        try (InputStream in = Iso4217.class.getResourceAsStream("iso4217.txt")) {
            if (in == null) {
                throw new IllegalStateException("resource iso4217.txt is missing");
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.strip();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] parts = line.split(" ");
                if (parts.length != 2) {
                    throw new IllegalStateException("malformed line in iso4217.txt: " + line);
                }
                if (parts[0].equals("amendment")) {
                    amendment = Integer.parseInt(parts[1]);
                } else if (!parts[0].matches("[A-Z]{3}") || table.put(parts[0],
                        parts[1].equals("-") ? -1 : Integer.parseInt(parts[1])) != null) {
                    throw new IllegalStateException("malformed or duplicate code in iso4217.txt: " + line);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (amendment != AMENDMENT) {
            throw new IllegalStateException("iso4217.txt is for amendment " + amendment + ", expected " + AMENDMENT);
        }
        return Collections.unmodifiableMap(table);
    }
}
