//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import vn.io.dxa.rulestone.api.DecisionRecord;
import vn.io.dxa.rulestone.api.DecisionRecord.PathStep;
import vn.io.dxa.rulestone.api.DecisionRecord.RankedAttribution;
import vn.io.dxa.rulestone.api.Explanation;

/**
 * Builds and writes {@link Explanation}s: a decision record explained in one language with the
 * messages of a {@link ReasonCodeCatalog}.
 *
 * <pre>{@code
 * ReasonCodeCatalog catalog = ReasonCodeCatalogJson.read(Path.of("rules/reason-codes.json"));
 * Explanation explanation = Explanations.explain(result.record(), catalog, "vi");
 * String notice = Explanations.text(explanation);
 * }</pre>
 *
 * <p>Everything here is a pure function of its arguments: no clock, no randomness and no default
 * locale or time zone of the JVM. The same record, catalog and locale always give the same
 * explanation, the same text and the same JSON, byte for byte, as the browser engine does.
 */
public final class Explanations {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private Explanations() {}

    /**
     * Explains {@code record} in {@code locale}. A reason whose code the catalog lacks has no message;
     * an engine built with the catalog rules that out for every code its tables and flows can give.
     *
     * @param locale one of the catalog's locales, e.g. {@code vi}
     * @throws IllegalArgumentException if the catalog has no such locale
     */
    public static Explanation explain(DecisionRecord record, ReasonCodeCatalog catalog, String locale) {
        Objects.requireNonNull(catalog, "catalog");
        return explain(record, catalog, ReasonCodeCatalogJson.exactVersion(catalog), locale);
    }

    static Explanation explain(DecisionRecord record, ReasonCodeCatalog catalog, String catalogVersion,
                               String locale) {
        Objects.requireNonNull(record, "record");
        Objects.requireNonNull(locale, "locale");
        if (!catalog.locales().contains(locale)) {
            throw new IllegalArgumentException("locale '" + locale + "' is not in the reason-code catalog, which has "
                    + String.join(", ", catalog.locales()));
        }
        List<Explanation.Reason> reasons = record.reasonCodes().stream()
                .map(r -> new Explanation.Reason(r.code(), r.source(), catalog.message(r.code(), locale)))
                .toList();
        List<Explanation.Step> steps = record.path().stream().map(Explanations::step).toList();
        return new Explanation(record.recordId(), record.correlationId(), record.decision(), record.version(),
                record.businessDate(), record.status(), locale, catalogVersion, record.output(), reasons, steps,
                record.params());
    }

    private static Explanation.Step step(PathStep step) {
        Optional<Explanation.Model> model = step.model().map(m ->
                new Explanation.Model(m.modelId(), m.modelVersion(), m.status(), m.attributions()));
        return new Explanation.Step(step.node(), step.type(), step.decision(), step.version(), step.matchedRules(),
                step.taken(), model);
    }

    /**
     * The explanation as a JSON object. Fields are written in a fixed order and absent optional
     * fields are left out, as in {@link DecisionRecordJson}; a {@code table} step always lists its
     * matched rules, and a {@code model} step its attributions.
     */
    public static ObjectNode toJson(Explanation explanation) {
        ObjectNode json = NODES.objectNode();
        json.put("recordId", explanation.recordId());
        explanation.correlationId().ifPresent(id -> json.put("correlationId", id));
        json.put("decision", explanation.decision());
        explanation.version().ifPresent(v -> json.put("version", v));
        json.put("businessDate", explanation.businessDate().toString());
        json.put("status", explanation.status().name());
        json.put("locale", explanation.locale());
        json.put("catalog", explanation.catalog());
        json.set("outcome", FactsJson.writeValues(explanation.outcome()));
        ArrayNode reasons = json.putArray("reasons");
        explanation.reasons().forEach(r -> {
            ObjectNode reason = reasons.addObject().put("code", r.code()).put("source", r.source());
            r.message().ifPresent(m -> reason.put("message", m));
        });
        ArrayNode steps = json.putArray("steps");
        explanation.steps().forEach(s -> {
            ObjectNode step = steps.addObject().put("node", s.node()).put("type", s.type());
            s.decision().ifPresent(d -> step.put("decision", d));
            s.version().ifPresent(v -> step.put("version", v));
            if (s.type().equals("table") || !s.matchedRules().isEmpty()) {
                ArrayNode matched = step.putArray("matchedRules");
                s.matchedRules().forEach(matched::add);
            }
            s.taken().ifPresent(t -> step.put("taken", t));
            s.model().ifPresent(m -> {
                ObjectNode model = step.putObject("model")
                        .put("id", m.modelId())
                        .put("version", m.modelVersion())
                        .put("status", m.status().name());
                ArrayNode attributions = model.putArray("attributions");
                m.attributions().forEach(a -> {
                    ObjectNode attribution = attributions.addObject()
                            .put("feature", a.feature())
                            .put("rank", a.rank())
                            .put("direction", a.direction().name());
                    a.reasonCode().ifPresent(code -> attribution.put("reasonCode", code));
                });
            });
        });
        if (!explanation.params().isEmpty()) {
            ArrayNode params = json.putArray("params");
            explanation.params().forEach(p -> params.addObject().put("set", p.set()).put("version", p.version()));
        }
        return json;
    }

    /** The explanation as compact JSON text, one line. */
    public static String write(Explanation explanation) {
        try {
            return MAPPER.writeValueAsString(toJson(explanation));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot write JSON", e);
        }
    }

    /**
     * The explanation as plain text, one item per line and ending with a line break, with headings in
     * the explanation's language: English and Vietnamese are built in, and other locales get English
     * headings. Output values are written in their JSON form, e.g. {@code "APPROVE"} or
     * {@code "60000000 VND"}.
     *
     * <pre>{@code
     * Decision: loan_eligibility 3@sha256:…
     * Business date: 2026-12-01
     * Outcome: eligible = false, maxAmount = "0 VND"
     * Reasons:
     *   1. Your monthly debt payments are high compared with your income. (DTI_HIGH, loan_eligibility)
     * Decided by:
     *   - loan_eligibility: table 3@sha256:…; matched R3
     * Reason-code catalog: 1@sha256:…
     * }</pre>
     */
    public static String text(Explanation explanation) {
        Labels l = Labels.of(explanation.locale());
        List<String> lines = new ArrayList<>();
        lines.add(l.decision + ": " + explanation.decision() + explanation.version().map(v -> " " + v).orElse(""));
        lines.add(l.businessDate + ": " + explanation.businessDate());
        String outcome;
        if (explanation.status() != DecisionRecord.Status.OK) {
            outcome = l.noDecision + " (" + explanation.status().name() + ")";
        } else if (explanation.outcome().isEmpty()) {
            outcome = l.noOutputs;
        } else {
            outcome = explanation.outcome().entrySet().stream()
                    .map(e -> e.getKey() + " = " + json(e.getValue()))
                    .collect(Collectors.joining(", "));
        }
        lines.add(l.outcome + ": " + outcome);
        if (explanation.reasons().isEmpty()) {
            lines.add(l.reasons + ": " + l.none);
        } else {
            lines.add(l.reasons + ":");
            int i = 1;
            for (Explanation.Reason r : explanation.reasons()) {
                lines.add("  " + i++ + ". " + r.message().orElse(l.noMessage) + " (" + r.code() + ", " + r.source() + ")");
            }
        }
        lines.add(l.decidedBy + ":");
        for (Explanation.Step s : explanation.steps()) {
            StringBuilder line = new StringBuilder("  - ").append(s.node()).append(": ").append(s.type());
            s.decision().ifPresent(d -> line.append(' ').append(d));
            s.version().ifPresent(v -> line.append(' ').append(v));
            if (s.type().equals("table")) {
                line.append("; ").append(s.matchedRules().isEmpty() ? l.noRuleMatched
                        : l.matched + " " + String.join(", ", s.matchedRules()));
            }
            s.taken().ifPresent(t -> line.append("; ").append(l.took).append(' ').append(t));
            s.model().ifPresent(m -> line.append(' ').append(m.modelId()).append(' ').append(m.modelVersion())
                    .append("; ").append(m.status().name()));
            lines.add(line.toString());
            s.model().ifPresent(m -> m.attributions().forEach(a -> lines.add("      " + a.rank() + ". " + a.feature()
                    + ": " + l.direction(a.direction()) + a.reasonCode().map(c -> " (" + c + ")").orElse(""))));
        }
        if (!explanation.params().isEmpty()) {
            lines.add(l.params + ": " + explanation.params().stream()
                    .map(p -> p.set() + " " + p.version()).collect(Collectors.joining(", ")));
        }
        lines.add(l.catalog + ": " + explanation.catalog());
        return String.join("\n", lines) + "\n";
    }

    private static String json(Object value) {
        try {
            return MAPPER.writeValueAsString(FactsJson.writeValue(value));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot write JSON", e);
        }
    }

    /**
     * Every reason code of {@code tables} and {@code flows} that {@code catalog} has no message for,
     * one problem each, located like the other problems of tables and flows.
     */
    static List<String> missingCodes(List<DecisionTable> tables, List<DecisionFlow> flows,
                                     ReasonCodeCatalog catalog) {
        List<String> problems = new ArrayList<>();
        for (DecisionTable table : tables) {
            for (Rule rule : table.rules()) {
                rule.reasonCode().ifPresent(code -> missing(catalog, code,
                        table.ref() + " rules[" + rule.id() + "].reasonCode", problems));
            }
        }
        for (DecisionFlow flow : flows) {
            flow.nodes().forEach((id, node) -> {
                if (node instanceof FlowNode.Model model) {
                    String at = flow.ref() + " nodes." + id;
                    model.reasonCodes().forEach((feature, code) ->
                            missing(catalog, code, at + ".reasonCodes." + feature, problems));
                    missing(catalog, model.onFailure().reasonCode(), at + ".onFailure.reasonCode", problems);
                }
            });
        }
        return problems;
    }

    private static void missing(ReasonCodeCatalog catalog, String code, String at, List<String> problems) {
        if (!catalog.codes().containsKey(code)) {
            problems.add(at + ": reason code '" + code + "' is not in the reason-code catalog");
        }
    }

    /** The headings of the text form in one language. */
    private record Labels(String decision, String businessDate, String outcome, String noDecision,
                          String noOutputs, String reasons, String none, String noMessage, String decidedBy,
                          String matched, String noRuleMatched, String took, String increasesRisk,
                          String decreasesRisk, String noEffect, String params, String catalog) {

        static final Labels EN = new Labels("Decision", "Business date", "Outcome", "no decision", "no outputs",
                "Reasons", "none", "(no message in the catalog)", "Decided by", "matched", "no rule matched", "took",
                "increases risk", "decreases risk", "no effect", "Parameter sets", "Reason-code catalog");
        static final Labels VI = new Labels("Quyết định", "Ngày nghiệp vụ", "Kết quả", "chưa có quyết định",
                "không có đầu ra", "Lý do", "không có", "(danh mục chưa có nội dung cho mã này)", "Căn cứ quyết định",
                "khớp", "không có luật nào khớp", "chọn", "làm tăng rủi ro", "làm giảm rủi ro", "không ảnh hưởng",
                "Bộ tham số", "Danh mục mã lý do");
        private static final Map<String, Labels> BY_LANGUAGE = Map.of("en", EN, "vi", VI);

        /** The labels of the locale's language, e.g. {@code vi} for {@code vi-VN}; English for any other. */
        static Labels of(String locale) {
            int dash = locale.indexOf('-');
            return BY_LANGUAGE.getOrDefault(dash < 0 ? locale : locale.substring(0, dash), EN);
        }

        String direction(RankedAttribution.Direction direction) {
            return switch (direction) {
                case INCREASES_RISK -> increasesRisk;
                case DECREASES_RISK -> decreasesRisk;
                case NONE -> noEffect;
            };
        }
    }
}
