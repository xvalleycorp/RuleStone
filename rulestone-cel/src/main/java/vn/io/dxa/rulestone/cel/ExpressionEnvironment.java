//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.cel;

import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelOptions;
import dev.cel.common.CelValidationException;
import dev.cel.common.types.CelType;
import dev.cel.common.types.CelTypeProvider;
import dev.cel.common.types.ListType;
import dev.cel.common.types.SimpleType;
import dev.cel.common.types.StructType;
import dev.cel.compiler.CelCompiler;
import dev.cel.compiler.CelCompilerBuilder;
import dev.cel.compiler.CelCompilerFactory;
import dev.cel.parser.CelStandardMacro;
import dev.cel.runtime.CelEvaluationException;
import dev.cel.runtime.CelRuntime;
import dev.cel.runtime.CelRuntimeFactory;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Typed variables plus the RuleStone CEL dialect (standard CEL with macros and the
 * {@code decimal}, {@code money} and {@code date} libraries). Compile expressions once, then
 * evaluate them many times.
 *
 * <pre>{@code
 * ExpressionEnvironment env = ExpressionEnvironment.builder()
 *     .variable("applicant", ValueType.struct(Map.of("age", ValueType.INT, "income", ValueType.DECIMAL)))
 *     .variable("amount", ValueType.DECIMAL)
 *     .build();
 * CelExpression affordable = env.compile("amount <= applicant.income * 5", ValueType.BOOL);
 * boolean ok = (Boolean) affordable.evaluate(env.bind(inputs));
 * }</pre>
 *
 * <p>Instances are immutable and thread-safe.
 */
public final class ExpressionEnvironment {

    /** Default upper bound on iterations of comprehensions such as {@code all()} and {@code exists()}. */
    public static final int DEFAULT_MAX_ITERATIONS = 10_000;

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Set<String> RESERVED = Set.of(
            "true", "false", "null", "in", "as", "break", "const", "continue", "else", "for", "function",
            "if", "import", "let", "loop", "package", "namespace", "return", "var", "void", "while");

    private final Map<String, ValueType> variables;
    private final Map<String, Integer> currencyScales;
    private final CelCompiler compiler;
    private final CelRuntime runtime;

    private ExpressionEnvironment(Builder builder) {
        this.variables = Collections.unmodifiableMap(new LinkedHashMap<>(builder.variables));
        this.currencyScales = Map.copyOf(builder.currencyScales);
        CelOptions options = CelOptions.current()
                .comprehensionMaxIterations(builder.maxIterations)
                .build();

        Map<String, CelType> structs = new HashMap<>();
        CelCompilerBuilder compilerBuilder = CelCompilerFactory.standardCelCompilerBuilder()
                .setOptions(options)
                .setStandardMacros(CelStandardMacro.STANDARD_MACROS)
                .addMacros(AggregateLibrary.macros())
                .addFunctionDeclarations(DecimalLibrary.declarations())
                .addFunctionDeclarations(MoneyLibrary.declarations())
                .addFunctionDeclarations(DateLibrary.declarations())
                .addFunctionDeclarations(FuzzyLibrary.declarations())
                .addFunctionDeclarations(AggregateLibrary.declarations());
        variables.forEach((name, type) -> compilerBuilder.addVar(name, celType(type, "rulestone.input." + name, structs)));
        this.compiler = compilerBuilder.setTypeProvider(new StructTypeProvider(structs)).build();
        this.runtime = CelRuntimeFactory.standardCelRuntimeBuilder()
                .setOptions(options)
                // Standard functions whose errors would quote an argument get value-free messages.
                .setStandardEnvironmentEnabled(false)
                .setStandardFunctions(SafeStandardFunctions.standardFunctions())
                .addFunctionBindings(SafeStandardFunctions.bindings(options))
                .addFunctionBindings(DecimalLibrary.bindings())
                .addFunctionBindings(MoneyLibrary.bindings(currencyScales))
                .addFunctionBindings(DateLibrary.bindings())
                .addFunctionBindings(FuzzyLibrary.bindings())
                .addFunctionBindings(AggregateLibrary.bindings())
                .build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public Map<String, ValueType> variables() {
        return variables;
    }

    /** Minor units overridden with {@link Builder#currencyScale}, by currency code. */
    public Map<String, Integer> currencyScales() {
        return currencyScales;
    }

    /**
     * Parses and type-checks {@code source}, requiring its result to be {@code expected}.
     *
     * @throws ExpressionException if the expression is malformed, ill-typed, or of another type
     */
    public CelExpression compile(String source, ValueType expected) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(expected, "expected");
        if (expected instanceof ValueType.Struct) {
            throw new IllegalArgumentException("expressions cannot be required to produce a struct");
        }
        CelAbstractSyntaxTree ast;
        try {
            ast = compiler.compile(source).getAst();
        } catch (CelValidationException e) {
            throw new ExpressionException(e.getMessage(), e);
        }
        CelType actual = ast.getResultType();
        CelType wanted = celType(expected, "", new HashMap<>());
        if (!actual.equals(SimpleType.DYN) && !wanted.isAssignableFrom(actual)) {
            throw new ExpressionException(
                    "expression must produce " + expected + " but produces " + actual.name() + ": " + source, null);
        }
        try {
            return new CelExpression(this, source, expected, runtime.createProgram(ast));
        } catch (CelEvaluationException e) {
            throw new ExpressionException("cannot prepare expression: " + source, e);
        }
    }

    /**
     * Converts Java values for every declared variable into their runtime form.
     * Undeclared entries are ignored.
     *
     * @throws InvalidValueException if a declared variable is missing or does not match its type
     */
    public Bindings bind(Map<String, ?> values) {
        Map<String, Object> converted = new HashMap<>();
        for (Map.Entry<String, ValueType> variable : variables.entrySet()) {
            String name = variable.getKey();
            Object value = values.get(name);
            if (value == null) {
                throw new InvalidValueException(name, "missing input");
            }
            converted.put(name, Values.toCel(value, variable.getValue(), name));
        }
        return new Bindings(this, converted);
    }

    private static CelType celType(ValueType type, String structName, Map<String, CelType> structs) {
        if (type instanceof ValueType.Primitive primitive) {
            return switch (primitive) {
                case BOOL -> SimpleType.BOOL;
                case INT -> SimpleType.INT;
                case STRING -> SimpleType.STRING;
                case DECIMAL -> DecimalLibrary.TYPE;
                case MONEY -> MoneyLibrary.TYPE;
                case DATE -> DateLibrary.TYPE;
            };
        }
        if (type instanceof ValueType.ListOf list) {
            return ListType.create(celType(list.element(), structName + ".item", structs));
        }
        ValueType.Struct struct = (ValueType.Struct) type;
        Map<String, CelType> fields = new LinkedHashMap<>();
        struct.fields().forEach((field, fieldType) -> {
            requireIdentifier(field, "field");
            fields.put(field, celType(fieldType, structName + "." + field, structs));
        });
        StructType celStruct = StructType.create(structName, ImmutableSet.copyOf(fields.keySet()),
                field -> Optional.ofNullable(fields.get(field)));
        structs.put(structName, celStruct);
        return celStruct;
    }

    private static void requireIdentifier(String name, String kind) {
        if (!IDENTIFIER.matcher(name).matches() || RESERVED.contains(name)) {
            throw new IllegalArgumentException("invalid " + kind + " name: '" + name + "'");
        }
    }

    public static final class Builder {

        private final Map<String, ValueType> variables = new LinkedHashMap<>();
        private final Map<String, Integer> currencyScales = new HashMap<>();
        private int maxIterations = DEFAULT_MAX_ITERATIONS;

        private Builder() {}

        public Builder variable(String name, ValueType type) {
            Objects.requireNonNull(type, "type");
            requireIdentifier(name, "variable");
            if (variables.putIfAbsent(name, type) != null) {
                throw new IllegalArgumentException("duplicate variable: " + name);
            }
            return this;
        }

        public Builder variables(Map<String, ValueType> variables) {
            variables.forEach(this::variable);
            return this;
        }

        /** Bounds the work any single comprehension may do, protecting the engine from runaway rules. */
        public Builder maxIterations(int maxIterations) {
            if (maxIterations < 1) {
                throw new IllegalArgumentException("maxIterations must be positive");
            }
            this.maxIterations = maxIterations;
            return this;
        }

        /**
         * Sets the number of minor-unit digits that {@code money.round()} uses for
         * {@code currency}, instead of its ISO 4217 default. Required before rounding amounts
         * in currencies that have no minor unit in ISO 4217, such as {@code XAU} (gold).
         *
         * @param currency an upper-case ISO 4217 code, e.g. {@code VND}
         * @param digits   digits after the decimal point, 0 to 18
         * @throws IllegalArgumentException if the code is not ISO 4217 or {@code digits} is out of range
         */
        public Builder currencyScale(String currency, int digits) {
            Objects.requireNonNull(currency, "currency");
            if (!MoneyLibrary.isCurrencyCode(currency)) {
                throw new IllegalArgumentException("not an upper-case ISO 4217 currency code: '" + currency + "'");
            }
            if (digits < 0 || digits > 18) {
                throw new IllegalArgumentException("currency scale must be between 0 and 18");
            }
            currencyScales.put(currency, digits);
            return this;
        }

        public ExpressionEnvironment build() {
            return new ExpressionEnvironment(this);
        }
    }

    private record StructTypeProvider(Map<String, CelType> structs) implements CelTypeProvider {
        @Override
        public ImmutableCollection<CelType> types() {
            return ImmutableList.copyOf(structs.values());
        }

        @Override
        public Optional<CelType> findType(String typeName) {
            return Optional.ofNullable(structs.get(typeName));
        }
    }
}
