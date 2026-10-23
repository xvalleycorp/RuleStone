//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.camunda8;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.camunda.zeebe.model.bpmn.Bpmn;
import io.camunda.zeebe.model.bpmn.BpmnModelInstance;
import io.camunda.zeebe.model.bpmn.instance.Error;
import io.camunda.zeebe.model.bpmn.instance.ServiceTask;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeHeader;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeTaskDefinition;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeTaskHeaders;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;

/**
 * Checks the element template's structure against the parts of the Camunda 8 element template schema
 * (zeebe-element-templates-json-schema 0.46.0) it uses, and against the worker's header names and
 * defaults. The sample BPMN must be valid and use the template as published.
 */
class ElementTemplateTest {

    private static final Path TEMPLATE = Path.of("element-templates", "rulestone-decision.json");
    private static final Set<String> PROPERTY_TYPES = Set.of("String", "Text", "Hidden", "Dropdown", "Boolean", "Number");

    private static JsonNode template() throws IOException {
        return JobVariables.MAPPER.readTree(Files.readString(TEMPLATE));
    }

    private static List<JsonNode> properties(JsonNode template) {
        return StreamSupport.stream(template.get("properties").spliterator(), false).toList();
    }

    /** Header key to the template's default value (null when it has none). */
    private static Map<String, String> headers(JsonNode template) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (JsonNode property : properties(template)) {
            JsonNode binding = property.get("binding");
            if (binding.get("type").asText().equals("zeebe:taskHeader")) {
                headers.put(binding.get("key").asText(), property.has("value") ? property.get("value").asText() : null);
            }
        }
        return headers;
    }

    @Test
    void hasTheFieldsTheSchemaRequires() throws IOException {
        JsonNode template = template();

        assertThat(template.get("$schema").asText())
                .isEqualTo("https://unpkg.com/@camunda/zeebe-element-templates-json-schema/resources/schema.json");
        assertThat(template.get("id").asText()).isEqualTo("vn.io.dxa.rulestone.decision");
        assertThat(template.get("name").asText()).isNotBlank();
        assertThat(template.get("version").isInt()).isTrue();
        assertThat(template.get("appliesTo").toString()).isEqualTo("[\"bpmn:Task\"]");
        assertThat(template.get("elementType").get("value").asText()).isEqualTo("bpmn:ServiceTask");
        assertThat(template.get("engines").get("camunda").asText()).startsWith("^8.");
    }

    @Test
    void everyPropertyIsWellFormed() throws IOException {
        JsonNode template = template();
        Set<String> groups = new HashSet<>();
        template.get("groups").forEach(g -> groups.add(g.get("id").asText()));

        for (JsonNode property : properties(template)) {
            String label = property.get("label").asText();
            assertThat(PROPERTY_TYPES).as(label).contains(property.get("type").asText());
            assertThat(groups).as(label).contains(property.get("group").asText());
            JsonNode binding = property.get("binding");
            switch (binding.get("type").asText()) {
                case "zeebe:taskHeader" -> assertThat(binding.get("key").asText()).as(label).isNotBlank();
                case "zeebe:taskDefinition" -> assertThat(binding.get("property").asText()).as(label).isIn("type", "retries");
                default -> throw new AssertionError(label + ": unexpected binding " + binding);
            }
            if (property.get("type").asText().equals("Dropdown")) {
                Set<String> values = new HashSet<>();
                property.get("choices").forEach(c -> values.add(c.get("value").asText()));
                assertThat(values).as(label).contains(property.get("value").asText());
            }
            if (property.path("optional").asBoolean()) {
                // The schema allows optional only on these bindings, and only without notEmpty.
                assertThat(binding.get("type").asText()).isEqualTo("zeebe:taskHeader");
                assertThat(property.path("constraints").path("notEmpty").asBoolean()).isFalse();
            }
            JsonNode pattern = property.path("constraints").path("pattern");
            if (!pattern.isMissingNode()) {
                Pattern.compile(pattern.get("value").asText());
                assertThat(pattern.get("message").asText()).isNotBlank();
            }
        }
    }

    @Test
    void headersAndDefaultsMatchTheWorker() throws IOException {
        JsonNode template = template();
        Map<String, String> headers = headers(template);

        assertThat(headers.keySet()).containsExactlyInAnyOrder(RuleStoneHeaders.DECISION_ID, RuleStoneHeaders.VERSION,
                RuleStoneHeaders.AS_OF_VARIABLE, RuleStoneHeaders.RESULT_VARIABLE, RuleStoneHeaders.FAIL_ON_NO_MATCH,
                RuleStoneHeaders.ERROR_CODE);
        assertThat(headers.get(RuleStoneHeaders.RESULT_VARIABLE)).isEqualTo(RuleStoneHeaders.DEFAULT_RESULT_VARIABLE);
        assertThat(headers.get(RuleStoneHeaders.ERROR_CODE)).isEqualTo(RuleStoneHeaders.DEFAULT_ERROR_CODE);
        assertThat(headers.get(RuleStoneHeaders.FAIL_ON_NO_MATCH)).isEqualTo("false");
        String jobType = properties(template).stream()
                .filter(p -> p.get("binding").path("property").asText().equals("type"))
                .findFirst().orElseThrow().get("value").asText();
        assertThat(jobType).isEqualTo(RuleStoneWorkerOptions.DEFAULT_JOB_TYPE);
    }

    @Test
    void theDefaultHeadersAreAValidTaskConfiguration() throws IOException {
        Map<String, String> headers = new HashMap<>();
        headers(template()).forEach((key, value) -> {
            if (value != null) {
                headers.put(key, value);
            }
        });
        headers.put(RuleStoneHeaders.DECISION_ID, "loan_eligibility");

        DecisionTask task = DecisionTask.fromHeaders(headers, "UNUSED");

        assertThat(task.errorCode()).isEqualTo(RuleStoneHeaders.DEFAULT_ERROR_CODE);
        assertThat(task.failOnNoMatch()).isFalse();
    }

    @Test
    void theSampleBpmnIsValidAndUsesTheTemplate() throws IOException {
        BpmnModelInstance model;
        try (InputStream in = getClass().getResourceAsStream("/loan-approval.bpmn")) {
            model = Bpmn.readModelFromStream(in);
        }
        Bpmn.validateModel(model);

        JsonNode template = template();
        ServiceTask task = model.getModelElementById("CheckEligibility");
        assertThat(task.getAttributeValueNs("http://camunda.org/schema/zeebe/1.0", "modelerTemplate"))
                .isEqualTo(template.get("id").asText());
        assertThat(task.getAttributeValueNs("http://camunda.org/schema/zeebe/1.0", "modelerTemplateVersion"))
                .isEqualTo(template.get("version").asText());
        assertThat(task.getSingleExtensionElement(ZeebeTaskDefinition.class).getType())
                .isEqualTo(RuleStoneWorkerOptions.DEFAULT_JOB_TYPE);

        Map<String, String> headers = new LinkedHashMap<>();
        for (ZeebeHeader header : task.getSingleExtensionElement(ZeebeTaskHeaders.class).getHeaders()) {
            headers.put(header.getKey(), header.getValue());
        }
        assertThat(headers(template).keySet()).containsAll(headers.keySet());
        DecisionTask config = DecisionTask.fromHeaders(headers, "UNUSED");
        assertThat(config.decisionId()).isEqualTo("loan_eligibility");
        assertThat(config.asOfVariable()).contains("applicationDate");

        Error error = model.getModelElementById("Error_DecisionFailed");
        assertThat(error.getErrorCode()).isEqualTo(config.errorCode());
    }
}
