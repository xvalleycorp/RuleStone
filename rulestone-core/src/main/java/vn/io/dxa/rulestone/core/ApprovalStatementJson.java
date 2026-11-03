//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import vn.io.dxa.rulestone.api.ArtifactKind;

/**
 * Reads and writes {@link ApprovalStatement approval statements}, the payload of {@code .approval.jws}
 * files. Reading is strict: unknown or duplicate keys and every other problem are errors, each
 * reported with its location, all together. A signed statement must be written exactly in the
 * canonical form of {@link #write}, which {@link ApprovalVerifier} checks.
 */
public final class ApprovalStatementJson {

    /** An exact version: {@code <label>@sha256:<64 lower-case hex digits>}. */
    static final Pattern EXACT_VERSION = Pattern.compile("[^@\\s]+@sha256:[0-9a-f]{64}");

    private static final Pattern HASH = Pattern.compile("sha256:[0-9a-f]{64}");
    private static final Pattern INSTANT = Pattern.compile(
            "(\\d{4})-(\\d{2})-(\\d{2})T([01]\\d|2[0-3]):[0-5]\\d:[0-5]\\d(\\.\\d{1,9})?Z");
    private static final Set<String> STATEMENT_FIELDS = Set.of("type", "approvalId", "changeSet", "subjects", "makers",
            "approvers", "policy", "emergency", "postApprovalDue", "issuer", "issuedAt", "logEntry");
    private static final Set<String> SUBJECT_FIELDS = Set.of("kind", "id", "version");
    private static final Set<String> APPROVER_FIELDS = Set.of("principal", "role", "at");

    private ApprovalStatementJson() {}

    /** @throws InvalidDecisionException listing every problem found */
    public static ApprovalStatement read(String json) {
        JsonNode root;
        try {
            root = DecisionTableJson.MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            String location = e.getLocation() == null ? ""
                    : " (line " + e.getLocation().getLineNr() + ", column " + e.getLocation().getColumnNr() + ")";
            throw new InvalidDecisionException(List.of("invalid JSON: " + e.getOriginalMessage() + location));
        }
        return statement(root);
    }

    /**
     * Writes the canonical form: compact, keys sorted, subjects, makers and approvers in their
     * order, absent optionals omitted, {@code emergency} always written.
     */
    public static String write(ApprovalStatement statement) {
        Map<String, Object> json = new TreeMap<>();
        json.put("type", ApprovalStatement.TYPE);
        json.put("approvalId", statement.approvalId());
        json.put("changeSet", statement.changeSet());
        List<Object> subjects = new ArrayList<>();
        for (ApprovalStatement.Subject subject : statement.subjects()) {
            subjects.add(new TreeMap<>(Map.of(
                    "kind", subject.kind().jsonName(), "id", subject.id(), "version", subject.version())));
        }
        json.put("subjects", subjects);
        json.put("makers", statement.makers());
        List<Object> approvers = new ArrayList<>();
        for (ApprovalStatement.Approver approver : statement.approvers()) {
            Map<String, Object> a = new TreeMap<>();
            a.put("principal", approver.principal());
            approver.role().ifPresent(r -> a.put("role", r));
            a.put("at", approver.at());
            approvers.add(a);
        }
        json.put("approvers", approvers);
        statement.policy().ifPresent(p -> json.put("policy", p));
        json.put("emergency", statement.emergency());
        statement.postApprovalDue().ifPresent(d -> json.put("postApprovalDue", d));
        json.put("issuer", statement.issuer());
        json.put("issuedAt", statement.issuedAt());
        statement.logEntry().ifPresent(e -> json.put("logEntry", e));
        return DecisionTableJson.writeCanonical(json);
    }

    static ApprovalStatement statement(JsonNode root) {
        if (root == null || !root.isObject()) {
            throw new InvalidDecisionException(List.of("$: expected a JSON object"));
        }
        DecisionTableJson.Reader reader = new DecisionTableJson.Reader();
        reader.unknownFields(root, STATEMENT_FIELDS, "$");
        String type = reader.requiredText(root, "type", "$");
        if (!type.isEmpty() && !type.equals(ApprovalStatement.TYPE)) {
            reader.problem("$.type", "must be '" + ApprovalStatement.TYPE + "'");
        }
        String approvalId = reader.requiredText(root, "approvalId", "$");
        String changeSet = reader.requiredText(root, "changeSet", "$");
        List<ApprovalStatement.Subject> subjects = subjects(root, reader);
        List<String> makers = makers(root, reader);
        List<ApprovalStatement.Approver> approvers = approvers(root, reader);
        Optional<String> policy = reader.optionalText(root, "policy", "$");
        JsonNode emergency = root.get("emergency");
        if (emergency == null || !emergency.isBoolean()) {
            reader.problem("$.emergency", "required boolean");
        }
        Optional<String> postApprovalDue = reader.optionalText(root, "postApprovalDue", "$");
        postApprovalDue.ifPresent(d -> instant(d, "$.postApprovalDue", reader));
        String issuer = reader.requiredText(root, "issuer", "$");
        String issuedAt = reader.requiredText(root, "issuedAt", "$");
        if (!issuedAt.isEmpty()) {
            instant(issuedAt, "$.issuedAt", reader);
        }
        Optional<String> logEntry = reader.optionalText(root, "logEntry", "$");
        if (logEntry.isPresent() && !HASH.matcher(logEntry.get()).matches()) {
            reader.problem("$.logEntry", "expected sha256:<64 hex digits>");
        }
        if (!reader.problems.isEmpty()) {
            throw new InvalidDecisionException(reader.problems);
        }
        return new ApprovalStatement(approvalId, changeSet, subjects, makers, approvers, policy,
                emergency.booleanValue(), postApprovalDue, issuer, issuedAt, logEntry);
    }

    private static List<ApprovalStatement.Subject> subjects(JsonNode root, DecisionTableJson.Reader reader) {
        JsonNode node = root.get("subjects");
        List<ApprovalStatement.Subject> subjects = new ArrayList<>();
        if (node == null || !node.isArray() || node.isEmpty()) {
            reader.problem("$.subjects", "required non-empty array of { kind, id, version }");
            return subjects;
        }
        for (int i = 0; i < node.size(); i++) {
            String path = "$.subjects[" + i + "]";
            JsonNode s = node.get(i);
            if (!s.isObject()) {
                reader.problem(path, "expected an object");
                continue;
            }
            reader.unknownFields(s, SUBJECT_FIELDS, path);
            String kindName = reader.requiredText(s, "kind", path);
            Optional<ArtifactKind> kind = ArtifactKind.fromJsonName(kindName);
            if (!kindName.isEmpty() && kind.isEmpty()) {
                reader.problem(path + ".kind", "unknown kind '" + kindName + "' (expected table, flow, params or catalog)");
            }
            String id = reader.requiredText(s, "id", path);
            String version = reader.requiredText(s, "version", path);
            if (!version.isEmpty() && !EXACT_VERSION.matcher(version).matches()) {
                reader.problem(path + ".version", "expected an exact version, <label>@sha256:<64 hex digits>");
            }
            kind.ifPresent(k -> subjects.add(new ApprovalStatement.Subject(k, id, version)));
        }
        return subjects;
    }

    private static List<String> makers(JsonNode root, DecisionTableJson.Reader reader) {
        JsonNode node = root.get("makers");
        List<String> makers = new ArrayList<>();
        if (node == null || !node.isArray() || node.isEmpty()) {
            reader.problem("$.makers", "required non-empty array of principal ids");
            return makers;
        }
        for (int i = 0; i < node.size(); i++) {
            JsonNode m = node.get(i);
            if (!m.isTextual() || m.asText().isBlank()) {
                reader.problem("$.makers[" + i + "]", "required non-empty string");
            } else {
                makers.add(m.asText());
            }
        }
        return makers;
    }

    private static List<ApprovalStatement.Approver> approvers(JsonNode root, DecisionTableJson.Reader reader) {
        JsonNode node = root.get("approvers");
        List<ApprovalStatement.Approver> approvers = new ArrayList<>();
        if (node == null || !node.isArray() || node.isEmpty()) {
            reader.problem("$.approvers", "required non-empty array of { principal, role, at }");
            return approvers;
        }
        Set<String> principals = new HashSet<>();
        for (int i = 0; i < node.size(); i++) {
            String path = "$.approvers[" + i + "]";
            JsonNode a = node.get(i);
            if (!a.isObject()) {
                reader.problem(path, "expected an object");
                continue;
            }
            reader.unknownFields(a, APPROVER_FIELDS, path);
            String principal = reader.requiredText(a, "principal", path);
            if (!principal.isEmpty() && !principals.add(principal)) {
                reader.problem(path + ".principal", "approver " + principal + " is named more than once");
            }
            Optional<String> role = reader.optionalText(a, "role", path);
            String at = reader.requiredText(a, "at", path);
            if (!at.isEmpty()) {
                instant(at, path + ".at", reader);
            }
            approvers.add(new ApprovalStatement.Approver(principal, role, at));
        }
        return approvers;
    }

    /** Checks that {@code text} is a UTC instant such as {@code 2026-11-20T07:41:02Z}. */
    private static void instant(String text, String path, DecisionTableJson.Reader reader) {
        Matcher m = INSTANT.matcher(text);
        boolean valid = m.matches();
        if (valid) {
            try {
                LocalDate.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
            } catch (DateTimeException e) {
                valid = false;
            }
        }
        if (!valid) {
            reader.problem(path, "expected a UTC instant such as 2026-11-20T07:41:02Z");
        }
    }
}
