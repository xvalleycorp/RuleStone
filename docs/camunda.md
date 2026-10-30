# Camunda integration

Many banks orchestrate loan origination, onboarding and payment investigations with Camunda. Two modules let a BPMN process make RuleStone decisions:

| Module | For | What it provides |
|---|---|---|
| `rulestone-camunda8` | Camunda 8 (Zeebe) | A job worker, and a Camunda Modeler element template that turns a service task into a "RuleStone decision" |
| `rulestone-camunda7` | The Camunda 7 embedded engine | A `JavaDelegate`, a process engine plugin and an expression bean |

Both read the decision's inputs from process variables, evaluate the version in force on the business date, and write the same result: `output`, `reasonCodes`, `matchedRules`, `ruleVersion` and `matched`. Both turn bad data into a **BPMN error** that a boundary event can catch, and configuration mistakes, such as an unknown decision, into an **incident**. Neither logs anything, and no message contains a variable value.

- [Camunda 8](#camunda-8)
- [Camunda 7](#camunda-7)
- [Decimal precision](#decimal-precision)
- [RuleStone or Camunda DMN?](#rulestone-or-camunda-dmn)

## Camunda 8

### Setup

Add the module, and the Camunda Java client your application already uses. The client has `provided` scope: RuleStone does not choose its version or redistribute it. The module was built and tested with `io.camunda:camunda-client-java` 8.10.6, which, like everything RuleStone depends on, is Apache-2.0 licensed.

```xml
<dependency>
    <groupId>vn.io.dxa.rulestone</groupId>
    <artifactId>rulestone-camunda8</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

Register a worker. It needs the engine and the decision tables you built it from: the tables' input types say how to read process variables.

```java
List<DecisionTable> tables;
try (Stream<Path> files = Files.list(rulesDirectory)) {
    tables = files.filter(f -> f.toString().endsWith(".stone.json"))
            .map(DecisionTableJson::read)
            .toList();
}
Clock clock = Clock.system(ZoneId.of("Asia/Ho_Chi_Minh"));
RuleStoneEngine engine = RuleStone.builder().clock(clock).decisions(tables).build();

return RuleStoneJobWorker.register(client, engine, tables,
        RuleStoneWorkerOptions.builder().clock(clock).build());
```

The worker subscribes to the job type `rulestone:decision`. `RuleStoneWorkerOptions` also sets the job type, the variables to fetch, the default BPMN error code, the retry backoff and the worker name. For any other worker setting, build the worker yourself with `new RuleStoneJobHandler(engine, tables, options)` as its handler. A Spring Boot starter is not available yet.

### The element template

`rulestone-camunda8/element-templates/rulestone-decision.json` is a Camunda 8 element template. It is validated against version 0.46.0 of the [element template JSON schema](https://github.com/camunda/element-templates-json-schema) and needs Camunda 8.6 or later.

- **Desktop Modeler:** copy the file into `resources/element-templates` in the Modeler's user data directory, or into a `.camunda/element-templates` folder next to your `.bpmn` files, and restart the Modeler.
- **Web Modeler:** upload the file to a project, then publish the template so that diagrams can use it.

Then select a task, choose **Change element → RuleStone decision**, and fill in the properties panel. The template writes the task's job type and custom headers:

```xml
<bpmn:serviceTask id="CheckEligibility" name="Check eligibility"
                  zeebe:modelerTemplate="vn.io.dxa.rulestone.decision" zeebe:modelerTemplateVersion="1">
  <bpmn:extensionElements>
    <zeebe:taskDefinition type="rulestone:decision" retries="3" />
    <zeebe:taskHeaders>
      <zeebe:header key="decisionId" value="loan_eligibility" />
      <zeebe:header key="asOfVariable" value="applicationDate" />
      <zeebe:header key="resultVariable" value="decision" />
      <zeebe:header key="failOnNoMatch" value="false" />
      <zeebe:header key="errorCode" value="RULESTONE_DECISION_FAILED" />
    </zeebe:taskHeaders>
    <zeebe:ioMapping>
      <zeebe:output source="=decision" target="decision" />
      <zeebe:output source="=decision.output.decision" target="loanDecision" />
    </zeebe:ioMapping>
  </bpmn:extensionElements>
  <bpmn:incoming>Flow_ToCheck</bpmn:incoming>
  <bpmn:outgoing>Flow_ToGateway</bpmn:outgoing>
</bpmn:serviceTask>
```

| Header | Required | Default | Meaning |
|---|---|---|---|
| `decisionId` | yes | | The decision to evaluate. |
| `version` | no | | An exact version label, for replays and investigations. Otherwise the version effective on the business date is used. |
| `asOfVariable` | no | | A process variable holding the business date as `"yyyy-MM-dd"`, such as the application date. Otherwise today, according to the worker's clock. |
| `modelsVariable` | no | | A process variable holding the results of models that the process ran itself, in the `models` form of [supplied results](flows.md#supplied-results). Not in the element template yet; see below. |
| `resultVariable` | no | `decision` | The variable the result is written to. |
| `failOnNoMatch` | no | `false` | `true` throws the BPMN error when no rule matches. |
| `errorCode` | no | `RULESTONE_DECISION_FAILED` | The code of the BPMN error. |

Use `asOfVariable` whenever the process has a business date: the decision then stays the same if the job is retried, or the process waits, past a version change.

**Decision tables only, for now.** The worker evaluates decision tables; [decision flows](flows.md) cannot be evaluated through it yet. The `modelsVariable` header is there for the model nodes of flows. The worker reads the variable strictly and reports a missing or malformed one as a BPMN error; until the worker evaluates flows, a decision table rejects model results, also with a BPMN error.

### Variables

The worker reads **only the inputs the decision declares**, each with its declared type; it ignores every other variable. Values use RuleStone's JSON forms (`FactsJson`, see the [Java API](java-api.md)):

| Type | Process variable | Example |
|---|---|---|
| `decimal` | a string in plain notation, or a JSON number (see [Decimal precision](#decimal-precision)) | `"25750000.50"` |
| `money` | `"<amount> <CCY>"` | `"1500000.50 VND"` |
| `date` | `"yyyy-MM-dd"` | `"2026-11-01"` |
| `int` | an integer | `35` |
| `string`, `bool` | as in JSON | `"MASS"`, `false` |
| struct, list | an object, an array | |

By default Zeebe sends every variable visible to the task with each job. To send less data, and keep unrelated personal data away from the worker, give each decision its own job type and fetch only its inputs and as-of variable:

```java
return RuleStoneJobWorker.register(client, engine, tables,
        RuleStoneWorkerOptions.builder()
                .jobType("rulestone:loan_eligibility")
                .fetchVariables("applicant", "amount", "applicationDate")
                .clock(clock)
                .build());
```

In the model, set the task's **Job type** (in the template's **Worker** group) to `rulestone:loan_eligibility`.

### Result and output mapping

The job completes with one variable:

```json
{
  "decision": {
    "output": { "decision": "APPROVE", "limit": "309000000" },
    "reasonCodes": [],
    "matchedRules": ["R4"],
    "ruleVersion": "3@sha256:…",
    "matched": true
  }
}
```

Decimals, money and dates are strings, so they never pass through floating point. Compare decimals in FEEL with `number(decision.output.limit) >= 100000000`, and texts directly: `decision.output.decision = "APPROVE"`.

Without output mappings, Zeebe merges the variable into the process instance. To control the scope, or to copy single values, add output mappings as in the task above. When a task has output mappings, only the mapped variables reach the process.

### Errors and incidents

| Situation | Behaviour |
|---|---|
| An input is missing or in the wrong form; the as-of variable is missing or not a date; a rule fails, for example on division by zero; a `UNIQUE` table matches twice; no rule matches and `failOnNoMatch` is `true` | The task throws the BPMN error `errorCode`. Catch it with an error boundary event and route the case to manual review. **Do not substitute a default decision.** |
| Unknown decision or version, no version effective on the business date, or an invalid header | The job fails with no retries left, which raises an **incident** with a message saying what to fix. Fix the deployment or the model, then retry the incident in Operate. |
| Anything else, such as an outage | The job fails with one retry fewer, after the backoff (10 seconds by default). When retries run out, Zeebe raises an incident. |

A boundary event for the error, in the sample `loan-approval.bpmn`:

```xml
<bpmn:boundaryEvent id="DecisionFailed" name="Decision failed" attachedToRef="CheckEligibility">
  <bpmn:outgoing>Flow_FailedToReview</bpmn:outgoing>
  <bpmn:errorEventDefinition id="ErrorDefinition_DecisionFailed" errorRef="Error_DecisionFailed" />
</bpmn:boundaryEvent>
```

Error and incident messages name the decision, its rule version, the inputs and the expected forms, never values. For unexpected exceptions, the message gives only the exception type and where it was thrown, because the original message might quote data.

## Camunda 7

### Which engine

Camunda 7 Community Edition reached its end of life in October 2025; 7.24 is its last release. Many banks still run it, embedded in their applications, and the Camunda 7 Enterprise Edition has the same Java API. The module is compiled and tested against `org.camunda.bpm:camunda-engine` 7.24.0, an Apache-2.0 artifact, in `provided` scope.

[Operaton](https://operaton.org) is an Apache-2.0 fork of Camunda 7. Its API is the same under the package `org.operaton.bpm` instead of `org.camunda.bpm`, so this module does not load in an Operaton engine. A port is a mechanical rename of imports; it is planned as a separate module if there is demand.

```xml
<dependency>
    <groupId>vn.io.dxa.rulestone</groupId>
    <artifactId>rulestone-camunda7</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

### Setup

Create a `RuleStoneDecisionService` from the engine and the tables, and add the plugin to the process engine configuration:

```java
RuleStoneEngine rules = RuleStone.builder().clock(clock).decisions(tables).build();
RuleStoneDecisionService decisions = new RuleStoneDecisionService(rules, tables, clock);

configuration.getProcessEnginePlugins().add(new RuleStoneProcessEnginePlugin(decisions));
return configuration.buildProcessEngine();
```

The plugin registers the service under the process engine's name, where `RuleStoneDelegate` finds it, and adds it to the engine's expression beans as `rulestone`.

**With Spring** (including the Camunda Spring Boot starter), declare the `RuleStoneProcessEnginePlugin` as a bean; the starter applies every `ProcessEnginePlugin` bean. Spring expressions resolve Spring beans rather than the engine's own, so also declare the `RuleStoneDecisionService` as a bean named `rulestone`. Keep `camunda:class` for the delegate. If you prefer `camunda:delegateExpression`, create the delegate with `new RuleStoneDelegate(decisions)` as a **prototype** bean: Camunda injects fields into the bean, which is not thread-safe on a singleton.

### The delegate

Configure the delegate with fields, each a string or an expression:

```xml
<bpmn:serviceTask id="CheckEligibility" name="Check eligibility"
                  camunda:class="vn.io.dxa.rulestone.camunda7.RuleStoneDelegate">
  <bpmn:extensionElements>
    <camunda:field name="decisionId" stringValue="loan_eligibility" />
    <camunda:field name="asOfVariable" stringValue="applicationDate" />
    <camunda:field name="resultVariable" stringValue="decision" />
    <camunda:field name="errorCode" stringValue="RULESTONE_DECISION_FAILED" />
  </bpmn:extensionElements>
</bpmn:serviceTask>
<bpmn:boundaryEvent id="DecisionFailed" attachedToRef="CheckEligibility">
  <bpmn:errorEventDefinition errorRef="Error_DecisionFailed" camunda:errorMessageVariable="decisionError" />
</bpmn:boundaryEvent>
```

The fields are those of the Camunda 8 headers: `decisionId` (required), `version`, `asOfVariable`, `modelsVariable`, `resultVariable` (default `decision`), `failOnNoMatch` (default `false`) and `errorCode` (default `RULESTONE_DECISION_FAILED`). As in Camunda 8, the delegate evaluates decision tables only for now, and `modelsVariable` is for the model nodes of flows. Gateways then read the result with JUEL:

```xml
<bpmn:sequenceFlow id="Flow_Approve" sourceRef="Outcome" targetRef="Disburse">
  <bpmn:conditionExpression xsi:type="bpmn:tFormalExpression">${decision.output.decision == 'APPROVE'}</bpmn:conditionExpression>
</bpmn:sequenceFlow>
```

### Expressions

To decide directly in a gateway condition or an input mapping, call the `rulestone` bean. `evaluate(decisionId, execution)` uses today's date according to the service's clock; `evaluateAsOf(decisionId, asOfVariable, execution)` uses the date in a variable:

```xml
<bpmn:conditionExpression xsi:type="bpmn:tFormalExpression">${rulestone.evaluateAsOf('loan_eligibility', 'applicationDate', execution).output.decision == 'APPROVE'}</bpmn:conditionExpression>
```

Both return the result map without storing it. Errors are thrown as from the delegate, but a gateway has no boundary event to catch a BPMN error, so prefer the delegate when the decision can fail on bad data.

### Variables and results

As in Camunda 8, only the declared inputs are read. A variable can hold a `Boolean`, `String`, `Integer` or `Long`, `BigDecimal`, `Money`, `LocalDate`, enum, or a `Map` or `Collection` of these; text in the JSON forms above (`"25750000.50"`, `"1500000.50 VND"`, `"2026-11-01"`); or a JSON value, such as a Spin `JSON` variable, which is read from its serialized text. **`Double` and `Float` are rejected** with a BPMN error naming the variable, for example `applicant.monthlyIncome: floating-point numbers are not supported; use BigDecimal`. The as-of variable holds a `LocalDate` or a `"yyyy-MM-dd"` string.

The result is a `Map` whose outputs are `Boolean`, `Long`, `String`, `BigDecimal`, `LocalDate` or lists of these, so JUEL compares decimals exactly: `${decision.output.limit >= 100000000}`. Money becomes its text form, `"30000.0100 VND"`. The delegate stores the map as a **Java-serialized object**, whatever the engine's default serialization format: Spin's JSON format would read decimals back as `Double`. The map holds JDK types only, so any application can read it.

### Errors and incidents

| Situation | Behaviour |
|---|---|
| Bad data, a failing rule, or no match with `failOnNoMatch` | `BpmnError` with the task's `errorCode`, for a boundary event to catch. `camunda:errorMessageVariable` receives the message. |
| Unknown decision or version, no version effective on the date, an invalid field, or no service registered | `RuleStoneConfigurationException`, a `ProcessEngineException`. |
| Anything else | The exception propagates. |

A configuration error in a synchronous task fails the API call that ran it, such as `startProcessInstanceByKey`, and rolls the transaction back. In an asynchronous task the job fails and is retried according to its retry cycle; set the cycle to one attempt so that the incident appears at once:

```xml
<bpmn:serviceTask id="DecideAsync" camunda:asyncBefore="true"
                  camunda:class="vn.io.dxa.rulestone.camunda7.RuleStoneDelegate">
  <bpmn:extensionElements>
    <camunda:failedJobRetryTimeCycle>R1/PT0S</camunda:failedJobRetryTimeCycle>
```

## Decimal precision

Camunda 8 stores process variables as MessagePack. A JSON number with a fraction becomes a 64-bit binary floating-point number there, which keeps a decimal exactly only up to 15 significant digits. RuleStone therefore:

- writes decimals, money and dates as **strings** in the result, so they round-trip exactly;
- reads decimal inputs from strings exactly, and from JSON numbers by their text;
- rejects a fractional JSON number with more than 15 significant digits with a BPMN error, because Zeebe may already have changed it. Send such values, and preferably all money, as strings.

Integers are stored exactly by Zeebe, as 64-bit integers.

Camunda 7 keeps Java objects, so `BigDecimal` and `Money` variables are exact; only `Double` and `Float` are rejected. The result is Java-serialized for the same reason.

## RuleStone or Camunda DMN?

Both Camundas include a DMN engine for business rule tasks. Keep DMN for simple tables owned by process modelers, when you want decision requirement diagrams or the DMN standard itself. Choose RuleStone when:

| Need | Camunda DMN | RuleStone |
|---|---|---|
| Exact money | FEEL numbers are decimals, but Camunda 8 stores fractional variables as floating point, and Camunda 7 hands `Double` variables to the table | `decimal` and `money` types; floating point is rejected; results stay exact |
| Effective dating | A decision is selected by deployment, latest version or version tag, not by a business date | Each version has `effectiveFrom`/`effectiveTo`; the version in force on the as-of date is chosen, and overlaps are rejected |
| Proving which rules decided | The deployment and version of the decision | `ruleVersion` names the exact rule content: `3@sha256:…`, stable across redeployments |
| Catching mistakes early | FEEL is checked mostly at evaluation time | CEL expressions are type-checked when the engine is built, and every problem is reported at once |
| Reuse outside the process | The decision runs inside Camunda | The same tables run embedded in services, in Temporal workflows or in tests with `rulestone-test` |

The two work side by side: a process can use a DMN table for routing and a RuleStone decision for credit policy.
