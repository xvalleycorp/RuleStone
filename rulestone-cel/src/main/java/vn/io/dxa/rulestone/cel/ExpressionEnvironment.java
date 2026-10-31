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
import dev.cel.common.ast.CelExpr;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
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
    private final int maxIterations;
    private final Map<String, CelExpression> locals;
    private final CelCompiler compiler;
    private final CelRuntime runtime;

    private ExpressionEnvironment(Builder builder) {
        this.variables = Collections.unmodifiableMap(new LinkedHashMap<>(builder.variables));
        this.currencyScales = Map.copyOf(builder.currencyScales);
        this.maxIterations = builder.maxIterations;
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
        this.locals = Collections.unmodifiableMap(new LinkedHashMap<>(builder.locals));
        locals.forEach((name, expression) -> compilerBuilder.addVar(name, expression.celType()));
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
        CelAbstractSyntaxTree ast = check(source);
        CelType actual = ast.getResultType();
        CelType wanted = celType(expected, "", new HashMap<>());
        if (!actual.equals(SimpleType.DYN) && !wanted.isAssignableFrom(actual)) {
            throw new ExpressionException(
                    "expression must produce " + expected + " but produces " + actual.name() + ": " + source, null);
        }
        return program(source, expected, actual, ast);
    }

    /**
     * Parses and type-checks the expression of a local value, inferring its type, which must be
     * one of {@link ValueType}: a {@code bool}, {@code int}, {@code string}, {@code decimal},
     * {@code money}, {@code date}, a list or a struct.
     *
     * @return an expression to declare with {@link Builder#local} in an environment with the same
     *         variables, local values, iteration limit and currency scales as this one, or more
     *         variables and local values
     * @throws ExpressionException if the expression is malformed, ill-typed, or of another type
     */
    public CelExpression compileLocal(String source) {
        Objects.requireNonNull(source, "source");
        CelAbstractSyntaxTree ast = check(source);
        CelType actual = ast.getResultType();
        ValueType type = valueType(actual);
        if (type == null) {
            throw new ExpressionException("a local value must produce bool, int, string, decimal, money, date, "
                    + "a list or a struct but produces " + actual.name() + ": " + source, null);
        }
        return program(source, type, actual, ast);
    }

    /**
     * The names an expression reads from its environment: inputs and local values, but not the
     * variables of list functions such as {@code x} in {@code xs.all(x, x > 0)}. The expression
     * is parsed but not type-checked, so names that are not declared are included.
     *
     * @throws ExpressionException if the expression is malformed
     */
    public Set<String> references(String source) {
        Objects.requireNonNull(source, "source");
        CelExpr root;
        try {
            root = compiler.parse(source).getAst().getExpr();
        } catch (CelValidationException e) {
            throw new ExpressionException(e.getMessage(), e);
        }
        Set<String> names = new TreeSet<>();
        collectReferences(root, Set.of(), names);
        return Collections.unmodifiableSet(names);
    }

    /**
     * The fields an expression selects directly on variable {@code variable}, such as {@code maxDti}
     * in {@code dti > params.maxDti}, also inside {@code has()}; not those of a list function's
     * variable of the same name. The expression is parsed but not type-checked.
     *
     * @throws ExpressionException if the expression is malformed
     */
    public Set<String> selectedFields(String source, String variable) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(variable, "variable");
        CelExpr root;
        try {
            root = compiler.parse(source).getAst().getExpr();
        } catch (CelValidationException e) {
            throw new ExpressionException(e.getMessage(), e);
        }
        Set<String> fields = new TreeSet<>();
        collectSelections(root, variable, false, fields);
        return Collections.unmodifiableSet(fields);
    }

    private static void collectSelections(CelExpr expr, String variable, boolean shadowed, Set<String> fields) {
        switch (expr.exprKind().getKind()) {
            case SELECT -> {
                CelExpr operand = expr.select().operand();
                if (!shadowed && operand.exprKind().getKind() == CelExpr.ExprKind.Kind.IDENT
                        && operand.ident().name().equals(variable)) {
                    fields.add(expr.select().field());
                } else {
                    collectSelections(operand, variable, shadowed, fields);
                }
            }
            case CALL -> {
                expr.call().target().ifPresent(t -> collectSelections(t, variable, shadowed, fields));
                expr.call().args().forEach(a -> collectSelections(a, variable, shadowed, fields));
            }
            case LIST -> expr.list().elements().forEach(e -> collectSelections(e, variable, shadowed, fields));
            case MAP -> expr.map().entries().forEach(e -> {
                collectSelections(e.key(), variable, shadowed, fields);
                collectSelections(e.value(), variable, shadowed, fields);
            });
            case STRUCT -> expr.struct().entries().forEach(e -> collectSelections(e.value(), variable, shadowed, fields));
            case COMPREHENSION -> {
                CelExpr.CelComprehension c = expr.comprehension();
                collectSelections(c.iterRange(), variable, shadowed, fields);
                collectSelections(c.accuInit(), variable, shadowed, fields);
                boolean accu = shadowed || c.accuVar().equals(variable);
                boolean loop = accu || c.iterVar().equals(variable) || c.iterVar2().equals(variable);
                collectSelections(c.loopCondition(), variable, loop, fields);
                collectSelections(c.loopStep(), variable, loop, fields);
                collectSelections(c.result(), variable, accu, fields);
            }
            default -> {
                // identifiers and constants
            }
        }
    }

    /**
     * Checks that a Java value can be bound to a variable of type {@code type}, as {@link #bind}
     * would convert it.
     *
     * @param path names the value in messages, e.g. {@code values.maxDti}
     * @throws InvalidValueException if it cannot
     */
    public static void checkValue(Object value, ValueType type, String path) {
        Objects.requireNonNull(type, "type");
        Values.toCel(value, type, path);
    }

    private static void collectReferences(CelExpr expr, Set<String> bound, Set<String> names) {
        switch (expr.exprKind().getKind()) {
            case IDENT -> {
                if (!bound.contains(expr.ident().name())) {
                    names.add(expr.ident().name());
                }
            }
            case SELECT -> collectReferences(expr.select().operand(), bound, names);
            case CALL -> {
                expr.call().target().ifPresent(t -> collectReferences(t, bound, names));
                expr.call().args().forEach(a -> collectReferences(a, bound, names));
            }
            case LIST -> expr.list().elements().forEach(e -> collectReferences(e, bound, names));
            case MAP -> expr.map().entries().forEach(e -> {
                collectReferences(e.key(), bound, names);
                collectReferences(e.value(), bound, names);
            });
            case STRUCT -> expr.struct().entries().forEach(e -> collectReferences(e.value(), bound, names));
            case COMPREHENSION -> {
                CelExpr.CelComprehension c = expr.comprehension();
                collectReferences(c.iterRange(), bound, names);
                collectReferences(c.accuInit(), bound, names);
                Set<String> accu = new HashSet<>(bound);
                accu.add(c.accuVar());
                Set<String> loop = new HashSet<>(accu);
                loop.add(c.iterVar());
                if (!c.iterVar2().isEmpty()) {
                    loop.add(c.iterVar2());
                }
                collectReferences(c.loopCondition(), loop, names);
                collectReferences(c.loopStep(), loop, names);
                collectReferences(c.result(), accu, names);
            }
            default -> {
                // constants
            }
        }
    }

    /** Whether {@code name} can name a variable or local value: an identifier that is not a reserved word. */
    public static boolean isValidName(String name) {
        return IDENTIFIER.matcher(name).matches() && !RESERVED.contains(name);
    }

    /** The local values declared with {@link Builder#local}, by name. */
    public Map<String, CelExpression> locals() {
        return locals;
    }

    private CelAbstractSyntaxTree check(String source) {
        try {
            return compiler.compile(source).getAst();
        } catch (CelValidationException e) {
            throw new ExpressionException(e.getMessage(), e);
        }
    }

    private CelExpression program(String source, ValueType type, CelType celType, CelAbstractSyntaxTree ast) {
        try {
            return new CelExpression(this, source, type, celType, runtime.createProgram(ast));
        } catch (CelEvaluationException e) {
            throw new ExpressionException("cannot prepare expression: " + source, e);
        }
    }

    /** The {@link ValueType} of a checked type, or null if it has none (dyn, double, maps, …). */
    private static ValueType valueType(CelType type) {
        if (type.equals(SimpleType.BOOL)) {
            return ValueType.BOOL;
        }
        if (type.equals(SimpleType.INT)) {
            return ValueType.INT;
        }
        if (type.equals(SimpleType.STRING)) {
            return ValueType.STRING;
        }
        if (type.equals(DecimalLibrary.TYPE)) {
            return ValueType.DECIMAL;
        }
        if (type.equals(MoneyLibrary.TYPE)) {
            return ValueType.MONEY;
        }
        if (type.equals(DateLibrary.TYPE)) {
            return ValueType.DATE;
        }
        if (type instanceof ListType list) {
            ValueType element = valueType(list.elemType());
            return element == null ? null : ValueType.listOf(element);
        }
        if (type instanceof StructType struct && struct.name().startsWith("rulestone.input.")) {
            Map<String, ValueType> fields = new LinkedHashMap<>();
            for (String field : struct.fieldNames()) {
                ValueType fieldType = valueType(struct.findField(field).orElseThrow().type());
                if (fieldType == null) {
                    return null;
                }
                fields.put(field, fieldType);
            }
            return ValueType.struct(fields);
        }
        return null;
    }

    /**
     * Parses and type-checks {@code source}, whose result type is whatever it produces: one of the
     * primitive types or a list of them, as {@link CelExpression#resultType()} then says.
     *
     * @throws ExpressionException if the expression is malformed or ill-typed, or produces a value
     *                             with fields, or one whose type cannot be determined
     */
    public CelExpression compile(String source) {
        Objects.requireNonNull(source, "source");
        CelAbstractSyntaxTree ast = check(source);
        ValueType type = valueType(ast.getResultType());
        if (type == null || containsStruct(type)) {
            throw new ExpressionException("expression must produce bool, int, string, decimal, money, date or a "
                    + "list of them, but produces " + ast.getResultType().name() + ": " + source, null);
        }
        return program(source, type, ast.getResultType(), ast);
    }

    private static boolean containsStruct(ValueType type) {
        return type instanceof ValueType.Struct || type instanceof ValueType.ListOf list && containsStruct(list.element());
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
        private final Map<String, CelExpression> locals = new LinkedHashMap<>();
        private int maxIterations = DEFAULT_MAX_ITERATIONS;

        private Builder() {}

        public Builder variable(String name, ValueType type) {
            Objects.requireNonNull(type, "type");
            requireIdentifier(name, "variable");
            if (locals.containsKey(name) || variables.putIfAbsent(name, type) != null) {
                throw new IllegalArgumentException("duplicate variable: " + name);
            }
            return this;
        }

        /**
         * Declares a local value: a variable whose value is computed from {@code expression} when an
         * expression first reads it, at most once per {@link Bindings}. A local value that no
         * expression reads is never computed, and its failure fails only expressions that read it,
         * as if its expression were written in their place (so {@code ||} and {@code &&} can
         * absorb it). Its type is the inferred type of {@code expression}.
         *
         * @param expression compiled with {@link ExpressionEnvironment#compileLocal} by an
         *                   environment whose variables and local values this builder also declares,
         *                   with the same iteration limit and currency scales
         * @throws IllegalArgumentException for an invalid or duplicate name, or an expression from
         *                                  an incompatible environment
         */
        public Builder local(String name, CelExpression expression) {
            Objects.requireNonNull(expression, "expression");
            requireIdentifier(name, "local value");
            if (variables.containsKey(name) || locals.containsKey(name)) {
                throw new IllegalArgumentException("duplicate variable: " + name);
            }
            ExpressionEnvironment from = expression.environment();
            boolean compatible = from.maxIterations == maxIterations && from.currencyScales.equals(currencyScales)
                    && from.variables.entrySet().stream().allMatch(v -> v.getValue().equals(variables.get(v.getKey())))
                    && from.locals.entrySet().stream().allMatch(l -> l.getValue() == locals.get(l.getKey()));
            if (!compatible) {
                throw new IllegalArgumentException("local value " + name
                        + " was compiled in an environment with other variables, local values or settings");
            }
            locals.put(name, expression);
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
