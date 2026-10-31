//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable named inputs to a decision.
 *
 * <p>Values may be plain Java types, maps, records or other objects a registered
 * type adapter understands. Amounts must be {@link java.math.BigDecimal} or {@link Money},
 * and dates {@link java.time.LocalDate}. The name {@code businessDate} is reserved: decisions
 * receive it from {@link DecisionRef#asOf()} or the engine clock.
 *
 * <p>Facts can also carry the results of models that the caller ran itself, for the
 * {@code model} nodes of a decision flow (see {@link #withModelResult}). They are kept apart from
 * the named inputs, under the id of the node they are for.
 */
public final class Facts {

    private static final Facts EMPTY = new Facts(Map.of(), Map.of());

    private final Map<String, Object> values;
    private final Map<String, SuppliedModel> models;

    private Facts(Map<String, Object> values, Map<String, SuppliedModel> models) {
        this.values = values;
        this.models = models;
    }

    public static Facts empty() {
        return EMPTY;
    }

    public static Facts of(String name, Object value) {
        return empty().with(name, value);
    }

    public static Facts of(String n1, Object v1, String n2, Object v2) {
        return of(n1, v1).with(n2, v2);
    }

    public static Facts of(String n1, Object v1, String n2, Object v2, String n3, Object v3) {
        return of(n1, v1, n2, v2).with(n3, v3);
    }

    public static Facts from(Map<String, ?> values) {
        Facts facts = EMPTY;
        for (Map.Entry<String, ?> e : values.entrySet()) {
            facts = facts.with(e.getKey(), e.getValue());
        }
        return facts;
    }

    /** Returns a copy with {@code name} bound to {@code value}; rejects duplicate names. */
    public Facts with(String name, Object value) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(value, () -> "value of fact '" + name + "'");
        if (values.containsKey(name)) {
            throw new IllegalArgumentException("duplicate fact: " + name);
        }
        Map<String, Object> copy = new LinkedHashMap<>(values);
        copy.put(name, value);
        return new Facts(Map.copyOf(copy), models);
    }

    /**
     * Returns a copy that carries {@code result} for the {@code model} node {@code node} of a
     * decision flow: the caller ran the model itself (Pattern A). The engine checks the result
     * against the node's contract exactly as if it had called the model. Supplying a result for a
     * node that does not take one fails the evaluation.
     *
     * @param node   the id of the model node, e.g. {@code pd}
     * @param result what the model returned
     * @throws IllegalArgumentException if the facts already carry a result for {@code node}
     */
    public Facts withModelResult(String node, ModelResult result) {
        return withModel(node, SuppliedModel.of(Objects.requireNonNull(result, "result")));
    }

    /**
     * Returns a copy that reports that the model of node {@code node} failed when the caller called
     * it. The flow then takes the node's {@code onFailure} path.
     *
     * @param node    the id of the model node
     * @param failure {@link ModelStatus#TIMEOUT} or {@link ModelStatus#ERROR}
     * @throws IllegalArgumentException if {@code failure} is another kind, or the facts already
     *                                  carry a result for {@code node}
     */
    public Facts withModelFailure(String node, ModelStatus failure) {
        return withModel(node, SuppliedModel.failed(Objects.requireNonNull(failure, "failure")));
    }

    /**
     * Returns a copy that carries {@code model} for the {@code model} node {@code node}.
     *
     * @throws IllegalArgumentException if the facts already carry a result for {@code node}
     */
    public Facts withModel(String node, SuppliedModel model) {
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(model, () -> "model result of node '" + node + "'");
        if (models.containsKey(node)) {
            throw new IllegalArgumentException("duplicate model result: " + node);
        }
        Map<String, SuppliedModel> copy = new LinkedHashMap<>(models);
        copy.put(node, model);
        return new Facts(values, Collections.unmodifiableMap(copy));
    }

    /**
     * Returns a copy that carries every entry of {@code models}, as {@link #withModel} does.
     *
     * @throws IllegalArgumentException if the facts already carry a result for one of the nodes
     */
    public Facts withModels(Map<String, SuppliedModel> models) {
        Facts facts = this;
        for (Map.Entry<String, SuppliedModel> e : models.entrySet()) {
            facts = facts.withModel(e.getKey(), e.getValue());
        }
        return facts;
    }

    public Optional<Object> get(String name) {
        return Optional.ofNullable(values.get(name));
    }

    /** The named inputs, without the model results. */
    public Map<String, Object> asMap() {
        return values;
    }

    /** The model results the caller supplied, by model node id, in the order added. */
    public Map<String, SuppliedModel> models() {
        return models;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Facts other && values.equals(other.values) && models.equals(other.models);
    }

    @Override
    public int hashCode() {
        return values.hashCode() * 31 + models.hashCode();
    }

    @Override
    public String toString() {
        // Names only: values may hold personal data and must not leak into logs.
        return "Facts" + values.keySet() + (models.isEmpty() ? "" : ", models " + models.keySet());
    }
}
