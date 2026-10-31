//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.core;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;
import vn.io.dxa.rulestone.api.DecisionEvaluationException;
import vn.io.dxa.rulestone.api.DecisionRecord.ModelStep;
import vn.io.dxa.rulestone.api.DecisionRecord.PathStep;
import vn.io.dxa.rulestone.api.DecisionRecord.RankedAttribution;
import vn.io.dxa.rulestone.api.DecisionRecord.RankedAttribution.Direction;
import vn.io.dxa.rulestone.api.DecisionRecord.ReasonCode;
import vn.io.dxa.rulestone.api.DecisionTimeoutException;
import vn.io.dxa.rulestone.api.Facts;
import vn.io.dxa.rulestone.api.ModelCall;
import vn.io.dxa.rulestone.api.ModelInvoker;
import vn.io.dxa.rulestone.api.ModelMode;
import vn.io.dxa.rulestone.api.ModelResult;
import vn.io.dxa.rulestone.api.ModelStatus;
import vn.io.dxa.rulestone.api.RuleStoneException;
import vn.io.dxa.rulestone.api.SuppliedModel;
import vn.io.dxa.rulestone.cel.Bindings;
import vn.io.dxa.rulestone.cel.CelExpression;
import vn.io.dxa.rulestone.cel.ExpressionEnvironment;
import vn.io.dxa.rulestone.cel.ExpressionException;
import vn.io.dxa.rulestone.cel.InvalidValueException;
import vn.io.dxa.rulestone.cel.ValueType;
import vn.io.dxa.rulestone.core.FlowNode.Branch;

/**
 * A decision flow whose graph is checked and whose expressions are all compiled and
 * type-checked against the decision tables it uses. Immutable and thread-safe.
 */
final class CompiledFlow implements Executable {

    /** The value that {@code switch} and {@code split} nodes publish. */
    static final String TAKEN = "taken";

    /** Node ids and {@code as} names are CEL variables, so they must be identifiers. */
    private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Set<String> RESERVED = Set.of(
            "true", "false", "null", "in", "as", "break", "const", "continue", "else", "for", "function",
            "if", "import", "let", "loop", "package", "namespace", "return", "var", "void", "while");

    /** Model versions must be exact: no range, wildcard or {@code latest}. */
    private static final Pattern MODEL_VERSION = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.+-]*");
    private static final int MAX_REASON_CODES = 10;

    /**
     * How the engine obtains model results.
     *
     * @param invokers     model id to the invoker that calls it
     * @param suppliedOnly whether every model node runs in {@code supplied} mode, for tests and replay
     * @param nanoTime     the monotonic clock that model latencies and timeouts are measured with
     */
    record Models(Map<String, ModelInvoker> invokers, boolean suppliedOnly, LongSupplier nanoTime) {

        Models {
            invokers = Map.copyOf(invokers);
        }
    }

    private final DecisionFlow flow;
    private final String ruleVersion;
    private final ExpressionEnvironment inputs;
    private final Map<String, Node> nodes;
    private final Models models;
    /** The parameters the flow's own expressions read, or null if it declares none. */
    private final Parameters.Binding params;

    private CompiledFlow(DecisionFlow flow, String ruleVersion, ExpressionEnvironment inputs, Map<String, Node> nodes,
                         Models models, Parameters.Binding params) {
        this.flow = flow;
        this.ruleVersion = ruleVersion;
        this.inputs = inputs;
        this.nodes = Map.copyOf(nodes);
        this.models = models;
        this.params = params;
    }

    @Override
    public DecisionFlow definition() {
        return flow;
    }

    @Override
    public String ruleVersion() {
        return ruleVersion;
    }

    // --- Compilation ----------------------------------------------------------------------------

    /**
     * Checks the graph and compiles every node of {@code flow}.
     *
     * @param tables     every compiled decision table, by id
     * @param registered every registered decision table, by id, including tables that failed to compile
     * @param flowIds    the ids of every registered flow
     * @param models     how model nodes obtain their results
     * @param parameterSets every registered parameter set, which the flow's {@code params} are checked against
     * @param problems receives every problem found, prefixed with the flow and location
     * @return the compiled flow, or empty if any problem was found
     */
    static Optional<CompiledFlow> compile(DecisionFlow flow, Map<String, List<CompiledDecision>> tables,
                                          Map<String, List<DecisionTable>> registered, Set<String> flowIds,
                                          int maxIterations, Map<String, Integer> currencyScales, Models models,
                                          Parameters.Registry parameterSets, List<String> problems) {
        return new Compiler(flow, tables, registered, flowIds, maxIterations, currencyScales, models, parameterSets,
                problems).compile();
    }

    /** Compiles one flow, collecting every problem instead of stopping at the first. */
    private static final class Compiler {

        private final DecisionFlow flow;
        private final Map<String, List<CompiledDecision>> tables;
        private final Map<String, List<DecisionTable>> registered;
        private final Set<String> flowIds;
        private final int maxIterations;
        private final Map<String, Integer> currencyScales;
        private final Models models;
        private final Parameters.Registry parameterSets;
        private final List<String> problems;
        private final String at;
        private final Map<Map<String, ValueType>, ExpressionEnvironment> environments = new HashMap<>();

        /** Node id to the ids of the nodes it can go to next, in order, without duplicates. */
        private final Map<String, List<String>> successors = new LinkedHashMap<>();
        /** Node id to the name it publishes its values under, and their type, for nodes that publish. */
        private final Map<String, String> publishedAs = new HashMap<>();
        private final Map<String, ValueType> publishedType = new HashMap<>();
        /** Table node id to the versions of its decision it may evaluate. */
        private final Map<String, List<CompiledDecision>> versions = new HashMap<>();

        Compiler(DecisionFlow flow, Map<String, List<CompiledDecision>> tables,
                 Map<String, List<DecisionTable>> registered, Set<String> flowIds, int maxIterations,
                 Map<String, Integer> currencyScales, Models models, Parameters.Registry parameterSets,
                 List<String> problems) {
            this.flow = flow;
            this.models = models;
            this.parameterSets = parameterSets;
            this.tables = tables;
            this.registered = registered;
            this.flowIds = flowIds;
            this.maxIterations = maxIterations;
            this.currencyScales = currencyScales;
            this.problems = problems;
            this.at = flow.ref() + " ";
        }

        Optional<CompiledFlow> compile() {
            int before = problems.size();
            header();
            Map<String, ValueType> inputTypes = new LinkedHashMap<>(flow.inputs());
            if (inputTypes.remove(CompiledDecision.BUSINESS_DATE) != null) {
                problem("inputs." + CompiledDecision.BUSINESS_DATE, "reserved name; every flow receives "
                        + CompiledDecision.BUSINESS_DATE + " implicitly (DecisionRef.asOf, else the engine clock)");
            }
            inputTypes.put(CompiledDecision.BUSINESS_DATE, ValueType.DATE);
            ExpressionEnvironment inputEnvironment = environment(inputTypes, "inputs");
            if (inputEnvironment == null) {
                return Optional.empty();
            }
            // Every node may read the parameters, which are not inputs: they are not part of the facts hash.
            Parameters.Binding params = null;
            Map<String, ValueType> startTypes = inputTypes;
            if (flow.params().isPresent()) {
                params = Parameters.check(at, flow.params().get(), flow.inputs(), parameterSets, problems).orElse(null);
                startTypes = new LinkedHashMap<>(inputTypes);
                startTypes.remove(Parameters.PARAMS);
                ValueType paramsType = Parameters.type(flow.params().get());
                if (paramsType != null) {
                    startTypes.put(Parameters.PARAMS, paramsType);
                }
            }
            names();
            graph();
            structure();
            if (problems.size() > before) {
                return Optional.empty();   // the typed checks below would only repeat these problems
            }
            List<String> order = topologicalOrder();
            if (order == null) {
                return Optional.empty();   // cycles, reported
            }
            unreachable(order);
            publications(order);
            if (problems.size() > before) {
                return Optional.empty();
            }
            sharedNames();
            if (problems.size() > before) {
                return Optional.empty();
            }
            Map<String, Map<String, ValueType>> scopes = scopes(order, startTypes);
            Map<String, Node> compiled = new HashMap<>();
            for (String id : order) {
                Node node = node(id, flow.nodes().get(id), scopes.get(id));
                if (node != null) {
                    compiled.put(id, node);
                }
            }
            if (problems.size() > before) {
                return Optional.empty();
            }
            String ruleVersion = flow.version() + "@sha256:" + CompiledDecision.sha256(DecisionFlowJson.write(flow));
            return Optional.of(new CompiledFlow(flow, ruleVersion, inputEnvironment, compiled, models, params));
        }

        private void header() {
            if (!CompiledDecision.ID.matcher(flow.id()).matches()) {
                problem("id", "must match " + CompiledDecision.ID.pattern());
            }
            if (!CompiledDecision.ID.matcher(flow.version()).matches() && !flow.version().matches("[0-9][A-Za-z0-9_.-]*")) {
                problem("version", "must be letters, digits, '.', '_' or '-'");
            }
            if (flow.effectiveFrom().isPresent() && flow.effectiveTo().isPresent()
                    && !flow.effectiveFrom().get().isBefore(flow.effectiveTo().get())) {
                problem("effectiveTo", "must be after effectiveFrom");
            }
            if (registered.containsKey(flow.id())) {
                problem("id", "a decision table has the same id; tables and flows share one id namespace");
            }
            flow.outputs().forEach((name, type) -> {
                if (CompiledDecision.containsStruct(type)) {
                    problem("outputs." + name, "outputs must be bool, int, string, decimal, money, date or lists of them");
                }
            });
            if (flow.nodes().isEmpty()) {
                problem("nodes", "a flow needs at least one node");
            }
        }

        /** Node ids and {@code as} names become CEL variables next to the inputs. */
        private void names() {
            flow.nodes().forEach((id, node) -> {
                String why = invalidName(id);
                if (why != null) {
                    problem("nodes." + id, "invalid node id: " + why);
                }
                asName(node).ifPresent(as -> {
                    String invalid = invalidName(as);
                    if (invalid != null) {
                        problem("nodes." + id + ".as", "invalid name: " + invalid);
                    } else if (flow.nodes().containsKey(as)) {
                        problem("nodes." + id + ".as", "'" + as + "' is a node id; use a name that is not");
                    }
                });
            });
        }

        private String invalidName(String name) {
            if (!NAME.matcher(name).matches() || RESERVED.contains(name)) {
                return "it names the node's values in expressions, so it must be an identifier "
                        + "(letters, digits and '_', not starting with a digit) and not a CEL keyword";
            }
            if (name.equals(CompiledDecision.BUSINESS_DATE) || flow.inputs().containsKey(name)) {
                return "'" + name + "' is already the name of an input";
            }
            if (name.equals(Parameters.PARAMS)) {
                return "'" + name + "' is reserved for parameter sets, which expressions read as params.<name>";
            }
            return null;
        }

        /** Checks that every target exists and records the edges. */
        private void graph() {
            if (!flow.start().isEmpty() && !flow.nodes().containsKey(flow.start())) {
                problem("start", "no node '" + flow.start() + "'");
            }
            flow.nodes().forEach((id, node) -> {
                Set<String> next = new LinkedHashSet<>();
                targets(id, node).forEach((path, target) -> {
                    if (flow.nodes().containsKey(target)) {
                        next.add(target);
                    } else if (!target.isEmpty()) {
                        problem(path, "no node '" + target + "'");
                    }
                });
                successors.put(id, List.copyOf(next));
            });
        }

        /** Location of each target in the file to the target node id. */
        private Map<String, String> targets(String id, FlowNode node) {
            Map<String, String> targets = new LinkedHashMap<>();
            String path = "nodes." + id;
            if (node instanceof FlowNode.Table table) {
                branchTargets(table.next(), path + ".next", targets);
            } else if (node instanceof FlowNode.Derive derive) {
                branchTargets(derive.next(), path + ".next", targets);
            } else if (node instanceof FlowNode.Model model) {
                branchTargets(model.next(), path + ".next", targets);
                targets.put(path + ".onFailure.to", model.onFailure().to());
            } else if (node instanceof FlowNode.Switch sw) {
                branchTargets(sw.cases(), path + ".cases", targets);
            } else if (node instanceof FlowNode.Split split) {
                for (int i = 0; i < split.buckets().size(); i++) {
                    targets.put(path + ".buckets[" + i + "].to", split.buckets().get(i).to());
                }
            }
            return targets;
        }

        private static void branchTargets(List<Branch> branches, String path, Map<String, String> targets) {
            if (branches.size() == 1 && branches.get(0).when().isEmpty()) {
                targets.put(path, branches.get(0).to());
                return;
            }
            for (int i = 0; i < branches.size(); i++) {
                targets.put(path + "[" + i + "].to", branches.get(i).to());
            }
        }

        /** Checks that need no types: branch structure, split percentages, and the names that end and derive nodes set. */
        private void structure() {
            flow.nodes().forEach((id, node) -> {
                String path = "nodes." + id;
                if (node instanceof FlowNode.Table table) {
                    branches(table.next(), path + ".next");
                    table.version().ifPresent(v -> {
                        if (v.isBlank()) {
                            problem(path + ".version", "must not be empty");
                        }
                    });
                } else if (node instanceof FlowNode.Derive derive) {
                    branches(derive.next(), path + ".next");
                    if (derive.outputs().isEmpty()) {
                        problem(path + ".outputs", "a derive node needs at least one value");
                    }
                    derive.outputs().forEach((name, type) -> {
                        if (CompiledDecision.containsStruct(type)) {
                            problem(path + ".outputs." + name,
                                    "values must be bool, int, string, decimal, money, date or lists of them");
                        }
                    });
                    sameNames(derive.set(), derive.outputs(), path, "value");
                } else if (node instanceof FlowNode.Model model) {
                    branches(model.next(), path + ".next");
                    model(model, path);
                } else if (node instanceof FlowNode.Switch sw) {
                    branches(sw.cases(), path + ".cases");
                } else if (node instanceof FlowNode.Split split) {
                    buckets(split, path);
                } else if (node instanceof FlowNode.End end) {
                    sameNames(end.set(), flow.outputs(), path, "output");
                }
            });
        }

        private void branches(List<Branch> branches, String path) {
            if (branches.isEmpty()) {
                problem(path, "required: at least one alternative");
                return;
            }
            for (int i = 0; i < branches.size(); i++) {
                boolean last = i == branches.size() - 1;
                Optional<String> when = branches.get(i).when();
                String at = branches.size() == 1 ? path : path + "[" + i + "]";
                if (last && when.isPresent()) {
                    problem(at + ".when", "the last alternative must have no condition, so that one always applies");
                } else if (!last && when.isEmpty()) {
                    problem(at + ".when", "required: only the last alternative may have no condition");
                }
            }
        }

        private void buckets(FlowNode.Split split, String path) {
            if (split.salt().isBlank()) {
                problem(path + ".salt", "required non-empty string");
            }
            if (split.buckets().isEmpty()) {
                problem(path + ".buckets", "required: at least one bucket");
                return;
            }
            Set<String> names = new HashSet<>();
            int total = 0;
            for (int i = 0; i < split.buckets().size(); i++) {
                FlowNode.Bucket bucket = split.buckets().get(i);
                String at = path + ".buckets[" + i + "]";
                if (bucket.name().isBlank()) {
                    problem(at + ".name", "required non-empty string");
                } else if (!names.add(bucket.name())) {
                    problem(at + ".name", "duplicate bucket name '" + bucket.name() + "'");
                }
                if (bucket.percent() < 1 || bucket.percent() > 100) {
                    problem(at + ".percent", "must be a whole number from 1 to 100");
                }
                total += bucket.percent();
            }
            if (total != 100) {
                problem(path + ".buckets", "percentages add up to " + total + ", not 100");
            }
        }

        /** The contract of a model node, apart from its expressions. */
        private void model(FlowNode.Model model, String path) {
            if (!CompiledDecision.ID.matcher(model.modelId()).matches()) {
                problem(path + ".model.id", "must match " + CompiledDecision.ID.pattern());
            }
            String version = model.modelVersion();
            if (!MODEL_VERSION.matcher(version).matches() || version.equalsIgnoreCase("latest")
                    || Arrays.stream(version.split("\\.")).anyMatch(part -> part.equalsIgnoreCase("x"))) {
                problem(path + ".model.version", "must pin one exact model version, e.g. \"2.4.0\"; "
                        + "ranges, wildcards and \"latest\" are not allowed");
            }
            if (model.outputs().isEmpty()) {
                problem(path + ".outputs", "a model node needs at least one output");
            }
            model.outputs().forEach((name, type) -> {
                if (!NAME.matcher(name).matches() || RESERVED.contains(name)) {
                    problem(path + ".outputs." + name, "invalid output name: the valid condition reads outputs "
                            + "by name, so it must be an identifier (letters, digits and '_', not starting with a "
                            + "digit) and not a CEL keyword");
                }
                if (CompiledDecision.containsStruct(type)) {
                    problem(path + ".outputs." + name,
                            "outputs must be bool, int, string, decimal, money, date or lists of them");
                }
            });
            if (model.mode() == ModelMode.INVOKE) {
                if (model.features().isEmpty()) {
                    problem(path + ".features", "required in invoke mode: the values sent to the model, as "
                            + "feature name to expression");
                }
                if (model.timeoutMs().isEmpty()) {
                    problem(path + ".timeoutMs", "required in invoke mode: how long the model may take, in "
                            + "milliseconds");
                }
                if (!models.suppliedOnly() && CompiledDecision.ID.matcher(model.modelId()).matches()
                        && !models.invokers().containsKey(model.modelId())) {
                    problem(path + ".model.id", "no ModelInvoker registered for model '" + model.modelId()
                            + "'; register one with RuleStone.builder().modelInvoker(\"" + model.modelId()
                            + "\", invoker), or build with suppliedModelsOnly() for tests");
                }
            } else {
                if (!model.features().isEmpty()) {
                    problem(path + ".features", "not allowed in supplied mode: the caller runs the model, so the "
                            + "engine sends it nothing");
                }
                if (model.timeoutMs().isPresent()) {
                    problem(path + ".timeoutMs", "not allowed in supplied mode: the engine calls no model");
                }
            }
            model.timeoutMs().ifPresent(timeout -> {
                if (timeout < 1) {
                    problem(path + ".timeoutMs", "must be at least 1");
                }
            });
            model.minConfidence().ifPresent(min -> {
                if (min.signum() < 0 || min.compareTo(BigDecimal.ONE) > 0) {
                    problem(path + ".minConfidence", "must be from 0 to 1");
                }
            });
            model.reasonCodes().forEach((feature, code) -> {
                if (!CompiledDecision.REASON_CODE.matcher(code).matches()) {
                    problem(path + ".reasonCodes." + feature, "must be UPPER_SNAKE_CASE, e.g. HIGH_UTILISATION");
                }
            });
            model.maxReasonCodes().ifPresent(max -> {
                if (max < 1 || max > MAX_REASON_CODES) {
                    problem(path + ".maxReasonCodes", "must be from 1 to " + MAX_REASON_CODES);
                }
            });
            if (!CompiledDecision.REASON_CODE.matcher(model.onFailure().reasonCode()).matches()) {
                problem(path + ".onFailure.reasonCode", "must be UPPER_SNAKE_CASE, e.g. MODEL_UNAVAILABLE");
            }
        }

        /** {@code set} must assign exactly the declared names. */
        private void sameNames(Map<String, String> set, Map<String, ValueType> declared, String path, String what) {
            declared.keySet().forEach(name -> {
                if (!set.containsKey(name)) {
                    problem(path + ".set", "does not set " + what + " '" + name + "'");
                }
            });
            set.keySet().forEach(name -> {
                if (!declared.containsKey(name)) {
                    problem(path + ".set." + name, "undeclared " + what + " (declared: " + declared.keySet() + ")");
                }
            });
        }

        /**
         * The nodes reachable from the start, each before every node it can go to; null if the
         * graph has a cycle, which is reported.
         */
        private List<String> topologicalOrder() {
            // Depth-first search from every node, so that cycles among unreachable nodes are found too.
            Map<String, Integer> state = new HashMap<>();   // absent: new, 1: on the stack, 2: done
            List<String> postOrder = new ArrayList<>();
            Set<Set<String>> cycles = new LinkedHashSet<>();
            List<String> roots = new ArrayList<>();
            roots.add(flow.start());
            roots.addAll(flow.nodes().keySet());
            for (String root : roots) {
                if (state.containsKey(root)) {
                    continue;
                }
                Deque<String> stack = new ArrayDeque<>();
                Deque<Integer> index = new ArrayDeque<>();
                stack.push(root);
                index.push(0);
                state.put(root, 1);
                while (!stack.isEmpty()) {
                    String node = stack.peek();
                    int i = index.pop();
                    List<String> next = successors.getOrDefault(node, List.of());
                    if (i < next.size()) {
                        index.push(i + 1);
                        String target = next.get(i);
                        Integer s = state.get(target);
                        if (s == null) {
                            state.put(target, 1);
                            stack.push(target);
                            index.push(0);
                        } else if (s == 1) {
                            List<String> path = new ArrayList<>(stack);   // top first
                            List<String> cycle = new ArrayList<>(path.subList(0, path.indexOf(target) + 1));
                            Collections.reverse(cycle);
                            cycle.add(target);
                            if (cycles.add(new HashSet<>(cycle))) {
                                problem("nodes." + target, "cycle " + String.join(" -> ", cycle)
                                        + "; a flow must be acyclic");
                            }
                        }
                    } else {
                        stack.pop();
                        state.put(node, 2);
                        postOrder.add(node);
                    }
                }
            }
            if (!cycles.isEmpty()) {
                return null;
            }
            // Reverse post-order of the search from the start: every node before its successors.
            Set<String> reachable = reachableFrom(flow.start());
            List<String> order = new ArrayList<>();
            for (int i = postOrder.size() - 1; i >= 0; i--) {
                if (reachable.contains(postOrder.get(i))) {
                    order.add(postOrder.get(i));
                }
            }
            return order;
        }

        private Set<String> reachableFrom(String start) {
            Set<String> seen = new LinkedHashSet<>();
            Deque<String> todo = new ArrayDeque<>(List.of(start));
            while (!todo.isEmpty()) {
                String node = todo.pop();
                if (seen.add(node)) {
                    successors.getOrDefault(node, List.of()).forEach(todo::push);
                }
            }
            return seen;
        }

        private void unreachable(List<String> order) {
            Set<String> reachable = new HashSet<>(order);
            flow.nodes().keySet().forEach(id -> {
                if (!reachable.contains(id)) {
                    problem("nodes." + id, "not reachable from start node '" + flow.start() + "'");
                }
            });
        }

        /** What each node publishes, resolving the decision tables of table nodes. */
        private void publications(List<String> order) {
            for (String id : order) {
                FlowNode node = flow.nodes().get(id);
                String name = asName(node).orElse(id);
                Map<String, ValueType> fields = new LinkedHashMap<>();
                if (node instanceof FlowNode.Table table) {
                    List<CompiledDecision> candidates = tableVersions(id, table);
                    if (candidates == null) {
                        continue;
                    }
                    versions.put(id, candidates);
                    // Only outputs that every version declares alike can be read.
                    Map<String, ValueType> first = FactsJson.resultTypes(candidates.get(0).table());
                    first.forEach((output, type) -> {
                        if (candidates.stream().allMatch(d -> type.equals(FactsJson.resultTypes(d.table()).get(output)))) {
                            fields.put(output, type);
                        }
                    });
                } else if (node instanceof FlowNode.Derive derive) {
                    fields.putAll(derive.outputs());
                } else if (node instanceof FlowNode.Model model) {
                    fields.putAll(model.outputs());
                } else if (node instanceof FlowNode.Switch || node instanceof FlowNode.Split) {
                    fields.put(TAKEN, ValueType.STRING);
                }
                if (!fields.isEmpty()) {
                    publishedAs.put(id, name);
                    publishedType.put(id, ValueType.struct(fields));
                }
            }
        }

        /** The versions a table node may evaluate, or null after reporting why there are none. */
        private List<CompiledDecision> tableVersions(String id, FlowNode.Table table) {
            String path = "nodes." + id + ".decision";
            if (!registered.containsKey(table.decision())) {
                problem(path, flowIds.contains(table.decision())
                        ? "'" + table.decision() + "' is a decision flow; calling another flow is not supported yet"
                        : "unknown decision '" + table.decision() + "'");
                return null;
            }
            List<CompiledDecision> all = tables.getOrDefault(table.decision(), List.of());
            if (all.size() < registered.get(table.decision()).size()) {
                return null;   // a version failed to compile, which is already reported
            }
            if (table.version().isPresent()) {
                List<CompiledDecision> pinned = all.stream()
                        .filter(d -> d.table().version().equals(table.version().get())).toList();
                if (pinned.isEmpty()) {
                    problem("nodes." + id + ".version",
                            "decision '" + table.decision() + "' has no version '" + table.version().get() + "'");
                    return null;
                }
                return pinned;
            }
            return all;
        }

        /** Nodes that publish under one {@code as} name must publish the same values, on alternative paths. */
        private void sharedNames() {
            Map<String, List<String>> byName = new LinkedHashMap<>();
            for (String id : flow.nodes().keySet()) {
                if (asName(flow.nodes().get(id)).isPresent() && publishedAs.containsKey(id)) {
                    byName.computeIfAbsent(publishedAs.get(id), n -> new ArrayList<>()).add(id);
                }
            }
            byName.forEach((name, ids) -> {
                for (int i = 0; i < ids.size(); i++) {
                    for (int j = i + 1; j < ids.size(); j++) {
                        String a = ids.get(i);
                        String b = ids.get(j);
                        if (!publishedType.get(a).equals(publishedType.get(b))) {
                            problem("nodes." + b + ".as", "nodes '" + a + "' and '" + b + "' both publish '" + name
                                    + "' but with different values: " + describe(publishedType.get(a)) + " and "
                                    + describe(publishedType.get(b)));
                        }
                        if (reachableFrom(a).contains(b) || reachableFrom(b).contains(a)) {
                            problem("nodes." + b + ".as", "nodes '" + a + "' and '" + b + "' both publish '" + name
                                    + "' and can both run on one path; only nodes on alternative branches may share a name");
                        }
                    }
                }
            });
        }

        /**
         * The names each node may read: those that every path from the start to it sets. A name is
         * readable at a node if it is set before the node on every path that reaches it.
         */
        private Map<String, Map<String, ValueType>> scopes(List<String> order, Map<String, ValueType> inputTypes) {
            Map<String, Map<String, ValueType>> scopes = new HashMap<>();
            scopes.put(flow.start(), inputTypes);
            for (String id : order) {
                Map<String, ValueType> after = after(id, scopes.get(id));
                // A model node that fails publishes nothing: its onFailure node sees only what the
                // model node itself saw. (If onFailure also leads where next does, that is what
                // every path there has in common.)
                String failureTarget = flow.nodes().get(id) instanceof FlowNode.Model model
                        ? model.onFailure().to() : null;
                for (String next : successors.get(id)) {
                    Map<String, ValueType> reaching = next.equals(failureTarget) ? scopes.get(id) : after;
                    Map<String, ValueType> existing = scopes.get(next);
                    if (existing == null) {
                        scopes.put(next, new LinkedHashMap<>(reaching));
                    } else {
                        Map<String, ValueType> both = new LinkedHashMap<>();
                        existing.forEach((name, type) -> {
                            if (type.equals(reaching.get(name))) {
                                both.put(name, type);
                            }
                        });
                        scopes.put(next, both);
                    }
                }
            }
            return scopes;
        }

        /** The names readable after node {@code id}: its own scope plus what it publishes. */
        private Map<String, ValueType> after(String id, Map<String, ValueType> scope) {
            Map<String, ValueType> after = new LinkedHashMap<>(scope);
            if (publishedAs.containsKey(id)) {
                after.put(publishedAs.get(id), publishedType.get(id));
            }
            return after;
        }

        private Node node(String id, FlowNode node, Map<String, ValueType> scope) {
            String path = "nodes." + id;
            ExpressionEnvironment environment = environment(scope, path);
            if (environment == null) {
                return null;
            }
            if (node instanceof FlowNode.Table table) {
                return table(id, table, scope, environment);
            }
            if (node instanceof FlowNode.Switch sw) {
                return new SwitchNode(environment, routes(sw.cases(), environment, scope, path + ".cases"));
            }
            if (node instanceof FlowNode.Derive derive) {
                Map<String, CelExpression> set = new LinkedHashMap<>();
                derive.outputs().forEach((name, type) -> set.put(name,
                        expression(environment, scope, derive.set().get(name), type, path + ".set." + name)));
                return new DeriveNode(environment, set, publishedAs.get(id), router(id, derive.next(), scope));
            }
            if (node instanceof FlowNode.Model model) {
                return model(id, model, scope, environment);
            }
            if (node instanceof FlowNode.Split split) {
                CelExpression key = expression(environment, scope, split.key(), ValueType.STRING, path + ".key");
                List<Slice> slices = new ArrayList<>();
                int upper = 0;
                for (FlowNode.Bucket bucket : split.buckets()) {
                    upper += bucket.percent();
                    slices.add(new Slice(bucket.name(), upper, bucket.to()));
                }
                return new SplitNode(environment, key, split.salt(), slices);
            }
            FlowNode.End end = (FlowNode.End) node;
            Map<String, CelExpression> set = new LinkedHashMap<>();
            flow.outputs().forEach((name, type) -> set.put(name,
                    expression(environment, scope, end.set().get(name), type, path + ".set." + name)));
            return new EndNode(environment, set);
        }

        private Node table(String id, FlowNode.Table table, Map<String, ValueType> scope,
                           ExpressionEnvironment environment) {
            String path = "nodes." + id;
            List<CompiledDecision> candidates = versions.get(id);
            // The facts must suit every version the node may evaluate.
            Map<String, ValueType> needed = new LinkedHashMap<>();
            for (CompiledDecision candidate : candidates) {
                candidate.table().inputs().forEach((name, type) -> {
                    ValueType other = needed.putIfAbsent(name, type);
                    if (other != null && !other.equals(type)) {
                        problem(path, "versions of decision '" + table.decision() + "' declare input '" + name
                                + "' with different types; pin one with \"version\"");
                    }
                });
            }
            table.facts().keySet().forEach(name -> {
                if (!needed.containsKey(name)) {
                    problem(path + ".facts." + name, "not an input of decision '" + table.decision()
                            + "' (inputs: " + needed.keySet() + ")");
                }
            });
            Map<String, CelExpression> mapped = new LinkedHashMap<>();
            Map<String, String> byName = new LinkedHashMap<>();
            needed.forEach((input, type) -> {
                String source = table.facts().get(input);
                String at = path + ".facts." + input;
                if (source != null && !CompiledDecision.containsStruct(type)) {
                    CelExpression expression = expression(environment, scope, source, type, at);
                    if (expression != null) {
                        mapped.put(input, expression);
                    }
                    return;
                }
                if (source != null && !NAME.matcher(source.strip()).matches()) {
                    problem(at, "input '" + input + "' is " + describe(type)
                            + "; a value with fields can only be passed by name, e.g. \"applicant\"");
                    return;
                }
                String name = source == null ? input : source.strip();
                ValueType actual = scope.get(name);
                if (actual == null) {
                    problem(source == null ? path : at, (source == null ? "input '" + input + "' of decision '"
                            + table.decision() + "': " : "") + unset(name)
                            + (source == null ? "; map it in \"facts\"" : ""));
                } else if (!assignable(actual, type)) {
                    problem(source == null ? path : at, "input '" + input + "' of decision '" + table.decision()
                            + "' is " + describe(type) + " but flow value '" + name + "' is " + describe(actual)
                            + (source == null ? "; map it in \"facts\"" : ""));
                } else {
                    byName.put(input, name);
                }
            });
            List<DecisionTable> definitions = candidates.stream().map(CompiledDecision::table).toList();
            return new TableNode(table.decision(), table.version(), candidates, definitions, environment, mapped,
                    byName, Optional.ofNullable(publishedAs.get(id)), router(id, table.next(), scope));
        }

        private Node model(String id, FlowNode.Model model, Map<String, ValueType> scope,
                           ExpressionEnvironment environment) {
            String path = "nodes." + id;
            Map<String, CelExpression> features = new LinkedHashMap<>();
            model.features().forEach((name, source) -> {
                CelExpression feature = expression(environment, scope, source, null, path + ".features." + name);
                if (feature != null) {
                    features.put(name, feature);
                }
            });
            ExpressionEnvironment outputs = environment(model.outputs(), path + ".outputs");
            CelExpression valid = model.valid().isEmpty() || outputs == null ? null
                    : expression(outputs, model.outputs(), model.valid().get(), ValueType.BOOL, path + ".valid");
            boolean invoke = model.mode() == ModelMode.INVOKE && !models.suppliedOnly();
            return new ModelNode(model, environment, features, outputs, valid,
                    invoke ? models.invokers().get(model.modelId()) : null,
                    model.timeoutMs().map(Duration::ofMillis).orElse(Duration.ZERO),
                    publishedAs.get(id), router(id, model.next(), scope));
        }

        /** Why {@code name} cannot be read here. */
        private String unset(String name) {
            boolean exists = flow.inputs().containsKey(name) || publishedAs.containsValue(name);
            return exists
                    ? "'" + name + "' is not set on every path that reaches this node"
                    : "no input or node value named '" + name + "'";
        }

        private Router router(String id, List<Branch> next, Map<String, ValueType> scope) {
            if (next.size() == 1 && next.get(0).when().isEmpty()) {
                return new Router(null, List.of(new Route(null, next.get(0).to())));
            }
            Map<String, ValueType> after = after(id, scope);
            ExpressionEnvironment environment = environment(after, "nodes." + id + ".next");
            if (environment == null) {
                return null;
            }
            return new Router(environment, routes(next, environment, after, "nodes." + id + ".next"));
        }

        private List<Route> routes(List<Branch> branches, ExpressionEnvironment environment,
                                   Map<String, ValueType> scope, String path) {
            List<Route> routes = new ArrayList<>();
            for (int i = 0; i < branches.size(); i++) {
                Branch branch = branches.get(i);
                String at = path + "[" + i + "].when";
                CelExpression when = branch.when()
                        .map(source -> expression(environment, scope, source, ValueType.BOOL, at)).orElse(null);
                routes.add(new Route(when, branch.to()));
            }
            return routes;
        }

        /**
         * Compiles {@code source}, of type {@code type}, or of the type it produces when that is null.
         * When it fails because it reads a name that exists in the flow
         * but is not set on every path to the node, says so instead of reporting an undeclared name.
         */
        private CelExpression expression(ExpressionEnvironment environment, Map<String, ValueType> scope,
                                         String source, ValueType type, String path) {
            String paramsProblem = Parameters.undeclared(environment, source, flow.params(),
                    flow.inputs().containsKey(Parameters.PARAMS));
            if (paramsProblem != null) {
                problem(path, paramsProblem);
                return null;
            }
            try {
                return type == null ? environment.compile(source) : environment.compile(source, type);
            } catch (ExpressionException e) {
                List<String> unset = new ArrayList<>();
                Set<String> known = new LinkedHashSet<>(flow.inputs().keySet());
                known.addAll(publishedAs.values());
                for (String name : known) {
                    if (!scope.containsKey(name) && mentions(source, name)) {
                        unset.add("'" + name + "'");
                    }
                }
                if (unset.isEmpty()) {
                    problem(path, e.getMessage());
                } else {
                    problem(path, "reads " + String.join(", ", unset) + ", which "
                            + (unset.size() == 1 ? "is" : "are")
                            + " not set on every path that reaches this node; a node may read only values "
                            + "that every path to it sets");
                }
                return null;
            }
        }

        private static boolean mentions(String source, String name) {
            return Pattern.compile("(?<![A-Za-z0-9_.])" + Pattern.quote(name) + "(?![A-Za-z0-9_])")
                    .matcher(source).find();
        }

        private ExpressionEnvironment environment(Map<String, ValueType> variables, String path) {
            ExpressionEnvironment cached = environments.get(variables);
            if (cached != null) {
                return cached;
            }
            try {
                ExpressionEnvironment.Builder builder = ExpressionEnvironment.builder()
                        .variables(variables)
                        .maxIterations(maxIterations);
                currencyScales.forEach(builder::currencyScale);
                ExpressionEnvironment environment = builder.build();
                environments.put(Map.copyOf(variables), environment);
                return environment;
            } catch (IllegalArgumentException e) {
                problem(path, e.getMessage());
                return null;
            }
        }

        private void problem(String path, String message) {
            problems.add(at + path + ": " + message);
        }
    }

    private static Optional<String> asName(FlowNode node) {
        if (node instanceof FlowNode.Table table) {
            return table.as();
        }
        if (node instanceof FlowNode.Derive derive) {
            return derive.as();
        }
        if (node instanceof FlowNode.Model model) {
            return model.as();
        }
        return Optional.empty();
    }

    /**
     * Whether a value of type {@code from} can be passed where {@code to} is declared: the same
     * type, except that a value with fields may have more fields than are declared.
     */
    static boolean assignable(ValueType from, ValueType to) {
        if (from instanceof ValueType.ListOf a && to instanceof ValueType.ListOf b) {
            return assignable(a.element(), b.element());
        }
        if (from instanceof ValueType.Struct a && to instanceof ValueType.Struct b) {
            return b.fields().entrySet().stream().allMatch(field ->
                    a.fields().containsKey(field.getKey()) && assignable(a.fields().get(field.getKey()), field.getValue()));
        }
        return from.equals(to);
    }

    private static String describe(ValueType type) {
        if (type instanceof ValueType.Struct struct) {
            List<String> fields = new ArrayList<>();
            struct.fields().forEach((name, field) -> fields.add(name + ": " + describe(field)));
            return "{" + String.join(", ", fields) + "}";
        }
        return type.toString();
    }

    // --- Evaluation -----------------------------------------------------------------------------

    @Override
    public Run run(Facts facts, LocalDate businessDate, Optional<String> correlationId, Deadline deadline,
                   Progress progress, FactsHasher hasher) {
        if (facts.asMap().containsKey(CompiledDecision.BUSINESS_DATE)) {
            throw failure("invalid input " + CompiledDecision.BUSINESS_DATE + ": reserved name; the business date "
                    + "comes from DecisionRef.asOf or the engine clock and cannot be passed as a fact", null);
        }
        if (params != null && facts.asMap().containsKey(Parameters.PARAMS)) {
            throw failure("invalid input " + Parameters.PARAMS + ": reserved name; the parameters come from "
                    + "parameter set " + params.declaration().set() + " and cannot be passed as a fact", null);
        }
        // The flow's values by name: the facts as given, then each node's values as it runs.
        Map<String, Object> values = new HashMap<>(facts.asMap());
        values.put(CompiledDecision.BUSINESS_DATE, businessDate);
        try {
            progress.factsHash(hasher.hash(inputs.bind(values)));
        } catch (InvalidValueException e) {
            throw failure("invalid input " + e.getMessage(), e);
        }
        if (params != null) {
            values.put(Parameters.PARAMS, params.select(businessDate, progress));
        }
        facts.models().keySet().forEach(node -> {
            if (!(nodes.get(node) instanceof ModelNode model)) {
                throw failure("model result supplied for '" + node + "', which is not a model node of this flow", null);
            }
            if (model.invoker() != null) {
                throw failure("model result supplied for node '" + node + "', which calls its model (mode invoke); "
                        + "supply results only for nodes in supplied mode, or build the engine with "
                        + "suppliedModelsOnly() for tests", null);
            }
        });
        Evaluation evaluation = new Evaluation(facts, correlationId, deadline);
        List<ReasonCode> reasons = new ArrayList<>();
        String id = flow.start();
        while (true) {
            if (deadline.passed()) {
                throw new DecisionTimeoutException(flow.id(), ruleVersion, deadline.timeout(), progress.trace);
            }
            Node node = nodes.get(id);
            try {
                if (node instanceof EndNode end) {
                    String endId = id;
                    progress.open(() -> PathStep.visit(endId, "end"));
                    return finish(end, values, reasons, progress);
                }
                id = step(id, node, values, businessDate, evaluation, progress, reasons);
            } catch (DecisionTimeoutException e) {
                throw new DecisionTimeoutException(flow.id(), ruleVersion, e.timeout(), progress.trace);
            } catch (RuleStoneException | ExpressionException e) {
                throw failure("node " + id + ": " + e.getMessage(), e);
            }
        }
    }

    private Run finish(EndNode end, Map<String, Object> values, List<ReasonCode> reasons, Progress progress) {
        Bindings bindings = end.environment().bind(values);
        Map<String, Object> output = new LinkedHashMap<>();
        end.set().forEach((name, expression) -> output.put(name, expression.evaluate(bindings)));
        progress.close();
        // Reasons in order, each code once, with the node that first gave it.
        Set<String> seen = new HashSet<>();
        List<ReasonCode> distinct = reasons.stream().filter(r -> seen.add(r.code())).toList();
        return new Run(output, distinct);
    }

    /** Evaluates node {@code id} and returns the id of the next node. */
    private String step(String id, Node node, Map<String, Object> values, LocalDate businessDate,
                        Evaluation evaluation, Progress progress, List<ReasonCode> reasons) {
        Deadline deadline = evaluation.deadline();
        if (node instanceof TableNode table) {
            progress.open(() -> new PathStep(id, "table", Optional.of(table.decision()), Optional.empty(),
                    List.of(), Optional.empty()));
            CompiledDecision decision = table.select(businessDate);
            int from = progress.trace.size();
            progress.open(() -> PathStep.table(id, table.decision(), decision.ruleVersion(), progress.matchedSince(from)));
            Map<String, Object> facts = new HashMap<>();
            if (!table.mapped().isEmpty()) {
                Bindings bindings = table.environment().bind(values);
                table.mapped().forEach((input, expression) -> facts.put(input, expression.evaluate(bindings)));
            }
            table.byName().forEach((input, name) -> facts.put(input, values.get(name)));
            CompiledDecision.Outcome outcome = decision.evaluate(decision.bind(Facts.from(facts), businessDate, progress),
                    progress.trace, deadline);
            outcome.reasonCodes().forEach(code -> reasons.add(new ReasonCode(code, id)));
            table.publish().ifPresent(name -> values.put(name, outcome.output()));
            progress.close();
            return table.next().route(values);
        }
        if (node instanceof SwitchNode sw) {
            progress.open(() -> PathStep.visit(id, "switch"));
            Bindings bindings = sw.environment().bind(values);
            String to = choose(sw.cases(), bindings);
            progress.open(() -> PathStep.branch(id, "switch", to));
            progress.close();
            values.put(id, Map.of(TAKEN, to));
            return to;
        }
        if (node instanceof DeriveNode derive) {
            progress.open(() -> PathStep.visit(id, "derive"));
            Bindings bindings = derive.environment().bind(values);
            Map<String, Object> computed = new LinkedHashMap<>();
            derive.set().forEach((name, expression) -> computed.put(name, expression.evaluate(bindings)));
            values.put(derive.publish(), computed);
            progress.close();
            return derive.next().route(values);
        }
        if (node instanceof ModelNode model) {
            return model(id, model, values, evaluation, progress, reasons);
        }
        SplitNode split = (SplitNode) node;
        progress.open(() -> PathStep.visit(id, "split"));
        String key = (String) split.key().evaluate(split.environment().bind(values));
        double point = DefaultRuleStoneEngine.bucket(split.salt(), key) * 100;
        Slice slice = split.slices().stream().filter(s -> point < s.upper()).findFirst()
                .orElse(split.slices().get(split.slices().size() - 1));
        progress.open(() -> PathStep.branch(id, "split", slice.name()));
        progress.close();
        values.put(id, Map.of(TAKEN, slice.name()));
        return slice.to();
    }

    /** What one evaluation passes to its model nodes. */
    private record Evaluation(Facts facts, Optional<String> correlationId, Deadline deadline) {}

    /**
     * Obtains, checks and records the result of a model node, and returns the id of the next node:
     * {@code next} if the result met the contract, {@code onFailure} otherwise.
     */
    private String model(String id, ModelNode node, Map<String, Object> values, Evaluation evaluation,
                         Progress progress, List<ReasonCode> reasons) {
        FlowNode.Model model = node.definition();
        ModelMode mode = node.invoker() == null ? ModelMode.SUPPLIED : ModelMode.INVOKE;
        progress.open(() -> PathStep.visit(id, "model"));
        // Features are computed in every mode, so that tests and replays meet the same expression errors.
        Map<String, Object> features = new LinkedHashMap<>();
        if (!node.features().isEmpty()) {
            Bindings bindings = node.environment().bind(values);
            node.features().forEach((name, expression) -> features.put(name, expression.evaluate(bindings)));
        }
        ModelOutcome outcome;
        OptionalLong latency = OptionalLong.empty();
        if (mode == ModelMode.SUPPLIED) {
            SuppliedModel supplied = evaluation.facts().models().get(id);
            if (supplied == null) {
                outcome = ModelOutcome.failed(ModelStatus.MISSING, "no result was supplied for this node");
            } else if (supplied.failure().isPresent()) {
                outcome = ModelOutcome.failed(supplied.failure().get(), "reported by the caller");
            } else {
                outcome = check(node, supplied.result().orElseThrow());
            }
        } else {
            Duration timeout = evaluation.deadline().remaining(node.timeout());
            long start = models.nanoTime().getAsLong();
            ModelResult result = null;
            String error = null;
            try {
                result = node.invoker().invoke(new ModelCall(model.modelId(), model.modelVersion(), features,
                        timeout, evaluation.correlationId()));
            } catch (RuntimeException e) {
                // The message may contain feature values: record the type only.
                error = "the invoker threw " + e.getClass().getName();
            }
            long elapsed = models.nanoTime().getAsLong() - start;
            latency = OptionalLong.of(Duration.ofNanos(elapsed).toMillis());
            if (evaluation.deadline().passed()) {
                OptionalLong took = latency;
                progress.open(() -> PathStep.model(id, step(model, mode, ModelOutcome.failed(ModelStatus.TIMEOUT,
                        "the evaluation's timeout passed while the model was called"), took)));
                throw new DecisionTimeoutException(flow.id(), ruleVersion, evaluation.deadline().timeout(), progress.trace);
            }
            if (elapsed > node.timeout().toNanos()) {
                outcome = ModelOutcome.failed(ModelStatus.TIMEOUT, "no result within " + model.timeoutMs().orElseThrow()
                        + " ms");
            } else if (error != null) {
                outcome = ModelOutcome.failed(ModelStatus.ERROR, error);
            } else if (result == null) {
                outcome = ModelOutcome.failed(ModelStatus.ERROR, "the invoker returned no result");
            } else {
                outcome = check(node, result);
            }
        }
        ModelOutcome done = outcome;
        OptionalLong took = latency;
        if (outcome.status().failed()) {
            reasons.add(new ReasonCode(model.onFailure().reasonCode(), id));
            progress.open(() -> PathStep.model(id, step(model, mode, done, took)));
            progress.close();
            return model.onFailure().to();
        }
        outcome.reasonCodes().forEach(code -> reasons.add(new ReasonCode(code, id)));
        values.put(node.publish(), outcome.outputs());
        progress.open(() -> PathStep.model(id, step(model, mode, done, took)));
        progress.close();
        return node.next().route(values);
    }

    private static ModelStep step(FlowNode.Model model, ModelMode mode, ModelOutcome outcome, OptionalLong latency) {
        return new ModelStep(model.modelId(), model.modelVersion(), mode, outcome.status(),
                Optional.ofNullable(outcome.detail()), latency, outcome.outputs(), outcome.confidence(),
                outcome.attributionMethod(), outcome.attributions());
    }

    /** Checks a result against the node's contract, and ranks its attributions. */
    private static ModelOutcome check(ModelNode node, ModelResult result) {
        FlowNode.Model model = node.definition();
        if (!result.modelId().equals(model.modelId()) || !result.modelVersion().equals(model.modelVersion())) {
            return ModelOutcome.failed(ModelStatus.CONTRACT_VIOLATION, "answered by " + result.modelId() + "@"
                    + result.modelVersion() + ", not the pinned " + model.modelId() + "@" + model.modelVersion());
        }
        Map<String, Object> outputs = new LinkedHashMap<>();
        for (Map.Entry<String, ValueType> declared : model.outputs().entrySet()) {
            String name = declared.getKey();
            Object value = result.outputs().get(name);
            if (value == null) {
                return ModelOutcome.failed(ModelStatus.CONTRACT_VIOLATION, "output '" + name + "' is missing");
            }
            try {
                // Through the JSON forms: exact decimals, money and dates, also from their text forms.
                outputs.put(name, FactsJson.readValue(FactsJson.writeValue(value), declared.getValue()));
            } catch (InvalidFactsException | IllegalArgumentException e) {
                return ModelOutcome.failed(ModelStatus.CONTRACT_VIOLATION, "output '" + name + "' is not "
                        + describe(declared.getValue()));
            }
        }
        for (String name : result.outputs().keySet()) {
            if (!model.outputs().containsKey(name)) {
                return ModelOutcome.failed(ModelStatus.CONTRACT_VIOLATION, "undeclared output '" + name + "'");
            }
        }
        if (model.minConfidence().isPresent() && result.confidence().isEmpty()) {
            return ModelOutcome.failed(ModelStatus.CONTRACT_VIOLATION,
                    "no confidence reported, and the node sets minConfidence");
        }
        if (node.valid() != null) {
            boolean valid;
            try {
                valid = (Boolean) node.valid().evaluate(node.outputs().bind(outputs));
            } catch (ExpressionException e) {
                return ModelOutcome.failed(ModelStatus.INVALID_OUTPUT, "the valid condition could not be evaluated");
            }
            if (!valid) {
                return ModelOutcome.failed(ModelStatus.INVALID_OUTPUT, "the valid condition is false");
            }
        }
        if (model.minConfidence().isPresent()
                && result.confidence().orElseThrow().compareTo(model.minConfidence().get()) < 0) {
            return ModelOutcome.failed(ModelStatus.LOW_CONFIDENCE, "confidence below minConfidence");
        }
        // Rank by the size of the contribution, in either direction; ties keep the model's order.
        List<ModelResult.Attribution> ranked = new ArrayList<>(result.attributions());
        ranked.sort(Comparator.comparing((ModelResult.Attribution a) -> a.contribution().abs()).reversed());
        int max = model.maxReasonCodes().orElse(FlowNode.Model.DEFAULT_MAX_REASON_CODES);
        List<String> codes = new ArrayList<>();
        List<RankedAttribution> attributions = new ArrayList<>();
        for (int i = 0; i < ranked.size(); i++) {
            ModelResult.Attribution attribution = ranked.get(i);
            int sign = attribution.contribution().signum();
            Direction direction = sign > 0 ? Direction.INCREASES_RISK
                    : sign < 0 ? Direction.DECREASES_RISK : Direction.NONE;
            String code = model.reasonCodes().get(attribution.feature());
            Optional<String> emitted = Optional.empty();
            if (direction == Direction.INCREASES_RISK && code != null && codes.size() < max) {
                if (!codes.contains(code)) {
                    codes.add(code);
                }
                emitted = Optional.of(code);
            }
            attributions.add(new RankedAttribution(attribution.feature(), i + 1, direction, emitted));
        }
        return new ModelOutcome(ModelStatus.OK, null, outputs, result.confidence(), result.attributionMethod(),
                attributions, codes);
    }

    /** What a model node found: the status, and on success what it publishes and records. */
    private record ModelOutcome(ModelStatus status, String detail, Map<String, Object> outputs,
                                Optional<BigDecimal> confidence, Optional<String> attributionMethod,
                                List<RankedAttribution> attributions, List<String> reasonCodes) {

        static ModelOutcome failed(ModelStatus status, String detail) {
            return new ModelOutcome(status, detail, Map.of(), Optional.empty(), Optional.empty(), List.of(), List.of());
        }
    }

    private static String choose(List<Route> routes, Bindings bindings) {
        for (Route route : routes) {
            if (route.when() == null || (Boolean) route.when().evaluate(bindings)) {
                return route.to();
            }
        }
        throw new IllegalStateException("the last alternative has no condition");
    }

    private DecisionEvaluationException failure(String message, Throwable cause) {
        return new DecisionEvaluationException(flow.id(), ruleVersion, message, cause);
    }

    // --- Compiled nodes -------------------------------------------------------------------------

    private sealed interface Node permits TableNode, SwitchNode, DeriveNode, ModelNode, SplitNode, EndNode {}

    /** One alternative; {@code when} is null for the final one. */
    private record Route(CelExpression when, String to) {}

    /** Where to go after a node; {@code environment} is null when there is a single unconditional route. */
    private record Router(ExpressionEnvironment environment, List<Route> routes) {

        String route(Map<String, Object> values) {
            if (environment == null) {
                return routes.get(0).to();
            }
            return choose(routes, environment.bind(values));
        }
    }

    private record TableNode(String decision, Optional<String> pinned, List<CompiledDecision> versions,
                             List<DecisionTable> definitions, ExpressionEnvironment environment,
                             Map<String, CelExpression> mapped, Map<String, String> byName,
                             Optional<String> publish, Router next) implements Node {

        /** The pinned version, else the version in force on {@code date}. */
        CompiledDecision select(LocalDate date) {
            DecisionTable table = DecisionVersions.select(decision, definitions, pinned, date);
            return versions.stream().filter(d -> d.table() == table).findFirst().orElseThrow();
        }
    }

    private record SwitchNode(ExpressionEnvironment environment, List<Route> cases) implements Node {}

    private record DeriveNode(ExpressionEnvironment environment, Map<String, CelExpression> set, String publish,
                              Router next) implements Node {}

    /**
     * A model node. {@code invoker} is null when the node runs in supplied mode; {@code outputs} is
     * the environment of the {@code valid} condition, which is null when there is none.
     */
    private record ModelNode(FlowNode.Model definition, ExpressionEnvironment environment,
                             Map<String, CelExpression> features, ExpressionEnvironment outputs, CelExpression valid,
                             ModelInvoker invoker, Duration timeout, String publish, Router next) implements Node {}

    /** A bucket that takes the keys whose point, from 0 to 100, is below {@code upper}. */
    private record Slice(String name, int upper, String to) {}

    private record SplitNode(ExpressionEnvironment environment, CelExpression key, String salt,
                             List<Slice> slices) implements Node {}

    private record EndNode(ExpressionEnvironment environment, Map<String, CelExpression> set) implements Node {}
}
