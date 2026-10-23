//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.cel;

import dev.cel.common.CelOptions;
import dev.cel.runtime.CelEvaluationException;
import dev.cel.runtime.CelFunctionBinding;
import dev.cel.runtime.CelStandardFunctions;
import dev.cel.runtime.RuntimeEquality;
import dev.cel.runtime.RuntimeHelpers;
import dev.cel.runtime.standard.BoolFunction.BoolOverload;
import dev.cel.runtime.standard.CelStandardOverload;
import dev.cel.runtime.standard.DoubleFunction.DoubleOverload;
import dev.cel.runtime.standard.DurationFunction.DurationOverload;
import dev.cel.runtime.standard.IndexOperator.IndexOverload;
import dev.cel.runtime.standard.IntFunction.IntOverload;
import dev.cel.runtime.standard.MatchesFunction.MatchesOverload;
import dev.cel.runtime.standard.TimestampFunction.TimestampOverload;
import dev.cel.runtime.standard.UintFunction.UintOverload;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The CEL standard functions, with the messages of those whose errors would quote an argument
 * replaced by messages without values.
 *
 * <p>cel-java reports, for example, {@code int('x')} as {@code For input string: "x"} and an
 * index out of range as {@code Index out of bounds: 5}. Arguments may be customer data, and
 * RuleStone's error messages never contain input values, so these overloads are wrapped: the
 * result for valid arguments is unchanged, and any failure becomes the fixed message below.
 * The browser engine uses the same messages.
 */
final class SafeStandardFunctions {

    /** Overloads whose failures may quote an argument, with the message that replaces them. */
    private static final Map<CelStandardOverload, String> MESSAGES = Map.ofEntries(
            Map.entry(IntOverload.STRING_TO_INT64, "int() argument is not a valid integer"),
            Map.entry(UintOverload.STRING_TO_UINT64, "uint() argument is not a valid unsigned integer"),
            Map.entry(DoubleOverload.STRING_TO_DOUBLE, "double() argument is not a valid number"),
            Map.entry(BoolOverload.STRING_TO_BOOL, "bool() argument is not a valid boolean"),
            Map.entry(IndexOverload.INDEX_LIST, "list index out of bounds"),
            Map.entry(IndexOverload.INDEX_MAP, "map has no such key"),
            Map.entry(MatchesOverload.MATCHES, "matches() pattern is not a valid regular expression"),
            Map.entry(MatchesOverload.MATCHES_STRING, "matches() pattern is not a valid regular expression"),
            Map.entry(TimestampOverload.STRING_TO_TIMESTAMP, "timestamp() argument is not a valid timestamp"),
            Map.entry(DurationOverload.STRING_TO_DURATION, "duration() argument is not a valid duration"));

    private SafeStandardFunctions() {}

    /** The standard functions without the overloads that {@link #bindings} replaces. */
    static CelStandardFunctions standardFunctions() {
        return CelStandardFunctions.newBuilder()
                .filterFunctions((function, overload) -> !MESSAGES.containsKey(overload))
                .build();
    }

    /** The replaced overloads: the standard implementation with a value-free failure message. */
    static List<CelFunctionBinding> bindings(CelOptions options) {
        RuntimeEquality equality = RuntimeEquality.create(RuntimeHelpers.create(), options);
        List<CelFunctionBinding> bindings = new ArrayList<>();
        MESSAGES.forEach((overload, message) -> {
            CelFunctionBinding standard = overload.newFunctionBinding(options, equality);
            bindings.add(CelFunctionBinding.from(standard.getOverloadId(), standard.getArgTypes(), args -> {
                try {
                    return standard.getDefinition().apply(args);
                } catch (CelEvaluationException | RuntimeException e) {
                    throw new CelEvaluationException(message);
                }
            }));
        });
        return bindings;
    }
}
