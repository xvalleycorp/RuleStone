# Temporal integration

[Temporal](https://temporal.io) runs long-lived business processes, such as loan origination or payment investigations, as durable workflows. The `rulestone-temporal` module lets those workflows make RuleStone decisions in a way that stays reproducible across retries, worker restarts and rule releases.

- [Two styles](#two-styles)
- [Setup](#setup)
- [In-workflow evaluation](#in-workflow-evaluation)
- [Activity evaluation](#activity-evaluation)
- [Payloads: decimals, money and dates](#payloads-decimals-money-and-dates)
- [Errors and retries](#errors-and-retries)
- [Determinism and versioning rules](#determinism-and-versioning-rules)
- [Search attributes](#search-attributes)
- [Operational checklist](#operational-checklist)

## Two styles

| | In-workflow: `RuleStoneWorkflow` | Activity: `RuleStoneActivities` |
|---|---|---|
| Where rules run | In workflow code, on the workflow worker | In an activity, on any worker that has the rules |
| What history records | The business date and exact rule version (`3@sha256:…`), a few hundred bytes | The full request (facts) and outcome, plus activity events |
| Latency | None: no round trip to the Temporal service | One activity: scheduling, a worker poll and completion |
| Workflow language | Java | Any: payloads are plain JSON |
| Seeing the outcome | Only if the workflow returns, stores or upserts it | In the activity result, in the Temporal UI |
| Replaying old workflows | Needs every rule version they used to stay registered | Needs nothing: the recorded outcome is reused |

Use **in-workflow** evaluation for Java workflows that make many decisions or need them fast, such as pricing every installment of a schedule. Use the **activity** when the workflow is written in another language, when only dedicated workers may load the rules, or when auditors want each decision's inputs and outcome in the workflow history.

Evaluation is pure, so the in-workflow style needs to store only *which* rules were used, not their result: replaying the same rule version on the same date with the same facts gives the same outcome. The price is the replay requirement in the last row of the table.

## Setup

Add the module. The Temporal SDK has `provided` scope: the module works with the `io.temporal:temporal-sdk` version your application already uses. It was built and tested with Temporal Java SDK 1.40.0.

```xml
<dependency>
    <groupId>vn.io.dxa.rulestone</groupId>
    <artifactId>rulestone-temporal</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

Register both styles on a worker. Build one engine from every rule version that running workflows may still replay, and give the activity the same tables:

```java
RuleStoneEngine engine = RuleStone.builder()
        .clock(clock)
        .decisions(tables)       // every version that running workflows may replay
        .build();

Worker worker = factory.newWorker(TASK_QUEUE);

// Activity style: the activity reads facts with the input types of these tables.
worker.registerActivitiesImplementations(new RuleStoneActivitiesImpl(engine, tables, clock));
worker.registerWorkflowImplementationTypes(LoanActivityWorkflowImpl.class);

// In-workflow style: the workflow needs the engine, so register a factory.
worker.registerWorkflowImplementationFactory(LoanWorkflow.class,
        () -> new LoanWorkflowImpl(engine, zone));
```

`clock` is your bank's business time zone, for example `Clock.system(ZoneId.of("Asia/Ho_Chi_Minh"))`, and `zone` is `clock.getZone()`.

## In-workflow evaluation

`RuleStoneWorkflow.of(engine, businessZone)` returns a helper for workflow code. `evaluate(DecisionRef, Facts)` returns a [`DecisionOutcome`](#payloads-decimals-money-and-dates):

```java
public class LoanWorkflowImpl implements LoanWorkflow {

    private final RuleStoneWorkflow rules;

    public LoanWorkflowImpl(RuleStoneEngine engine, ZoneId businessZone) {
        this.rules = RuleStoneWorkflow.of(engine, businessZone);
    }

    @Override
    public DecisionOutcome decide(LoanApplication application) {
        DecisionOutcome outcome = rules.evaluate(
                DecisionRef.of("loan_eligibility"),
                Facts.of("applicant", application.applicant(), "amount", application.amount()));
        RuleStoneSearchAttributes.upsert(outcome, "decision");
        return outcome;
    }
}
```

### The business date

Without `DecisionRef.asOf`, the business date is `Workflow.currentTimeMillis()` converted to a date in the business zone. The system clock and the engine clock are never read. In tests with time skipping, a workflow that sleeps across an effective date switches to the new rule version, exactly as it would in production. Pass `asOf` when the business date is a fact of the case, such as the application date.

### How version pinning works

On the **first execution** of each `evaluate` call:

1. The business date is resolved as above.
2. Inside `Workflow.sideEffect`, the engine selects the version effective on that date. The decision id, business date and exact rule version, `<version>@sha256:<hash>`, are recorded in workflow history as a small JSON marker. Facts are not recorded.
3. The decision is evaluated with exactly that version and date: `DecisionRef.of(id).version(label).asOf(date)`.

On **replay**, step 2 does not run. The recorded marker is read back and step 3 evaluates the recorded version on the recorded date. A version that became effective in the meantime, a changed time zone or a different clock cannot change the outcome. The engine's current content hash for that version label is then compared with the recorded one.

`sideEffect` is used rather than the alternatives because it records a value once and returns it unchanged on every replay, without a round trip to the Temporal service. `mutableSideEffect` is meant for values that change during a run, keyed by an id shared across calls. A local activity would add a timeout, retries and a second worker thread for pure CPU work that cannot fail transiently. Markers are matched to calls by order, so the marker also stores the decision id, and a replay that reaches a different decision at that point fails.

### When replay cannot reproduce a decision

If the recorded version is no longer registered, or its content hash differs (someone edited the rule text without changing the version label), `evaluate` throws `RuleVersionMismatchException`. Its message names the decision and both versions:

```text
RuleStone replay mismatch for decision 'loan_eligibility': workflow history recorded rule version 3@sha256:2008…, but version '3' now registered in the engine is 3@sha256:77ba…. The rule content changed without a new version label. Register the original content of version '3' again (publish changes under a new label); the workflow task is retried until then.
```

The exception is a Temporal `NonDeterministicException`. Temporal fails the workflow *task*, shows the message in the UI, and retries the task. The workflow stays open: it neither completes nor fails with an outcome the original rules would not have produced. Register the original rule content again and the workflow continues. If you would rather fail such workflows, add `NonDeterministicException.class` to `WorkflowImplementationOptions.setFailWorkflowExceptionTypes`, as Temporal recommends for other non-determinism.

### Facts in workflow code

Facts must be deterministic workflow data: workflow arguments, signal and update payloads, and activity results. Never build facts from clocks, random numbers, environment variables, databases or mutable static fields. Records with `BigDecimal`, `Money` and `LocalDate` fields, like `LoanApplication` above, are safe workflow arguments with Temporal's default data converter.

## Activity evaluation

The activity type is `RuleStoneEvaluate`. The workflow sends a `DecisionRequest` and receives a `DecisionOutcome`:

```java
static final ActivityOptions RULESTONE_OPTIONS = ActivityOptions.newBuilder()
        // Evaluation does no I/O: a short timeout detects a lost worker quickly.
        .setStartToCloseTimeout(Duration.ofSeconds(10))
        .setRetryOptions(RetryOptions.newBuilder()
                .setInitialInterval(Duration.ofSeconds(1))
                .setMaximumInterval(Duration.ofMinutes(1))
                // Bounded: an unexpected error is a bug or an outage; do not retry forever.
                .setMaximumAttempts(10)
                // RuleStone failures are non-retryable already; this states it in the policy.
                .setDoNotRetry(RuleStoneFailures.DECISION_NOT_FOUND,
                        RuleStoneFailures.DECISION_EVALUATION_FAILED,
                        RuleStoneFailures.INVALID_REQUEST)
                .build())
        .build();

private final RuleStoneActivities rules =
        Workflow.newActivityStub(RuleStoneActivities.class, RULESTONE_OPTIONS);

@Override
public DecisionOutcome decide(LoanApplication application) {
    DecisionRequest request = DecisionRequest
            .of("loan_eligibility",
                    Facts.of("applicant", application.applicant(), "amount", application.amount()))
            .withAsOf(application.applicationDate());
    try {
        return rules.evaluate(request);
    } catch (ActivityFailure e) {
        if (e.getCause() instanceof ApplicationFailure failure
                && RuleStoneFailures.DECISION_EVALUATION_FAILED.equals(failure.getType())) {
            // Never substitute a default decision: hand the case to a person.
            return manualReview(application, failure);
        }
        throw e;
    }
}
```

Pass `withAsOf` whenever the case has a business date. Without it, the activity uses today's date from its own clock, passes that date to the engine explicitly, and records it in `DecisionOutcome.businessDate()`. Because the outcome is recorded in history, a replayed workflow gets the original outcome even after the rules change.

**Decision tables only, for now.** The activity evaluates decision tables. [Decision flows](flows.md) cannot be evaluated through it yet. `DecisionRequest.withModels(models)` already carries the results of models that the caller ran itself, in the `models` form of [supplied results](flows.md#supplied-results), for the model nodes of flows. The activity reads them strictly, and reports a malformed one as `INVALID_REQUEST`; until the activity evaluates flows, a decision table rejects them with `DECISION_EVALUATION_FAILED`.

## Payloads: decimals, money and dates

`DecisionRequest.facts()` and `DecisionOutcome.output()` are JSON objects in the forms of `FactsJson` (see the [Java API](java-api.md)), so they are exact and readable in any language:

| Type | JSON | Example |
|---|---|---|
| `decimal` | a string in plain notation; JSON numbers are accepted on input | `"25750000.50"` |
| `money` | `"<amount> <CCY>"` | `"1500000.50 VND"` |
| `date` | `"yyyy-MM-dd"` | `"2026-11-01"` |
| `int` | an integer | `30` |
| `string`, `bool` | as in JSON | `"RETAIL"`, `false` |
| struct, list | an object, an array | |

`DecisionRequest.of(decisionId, facts)` converts Java values, including records, into these forms and rejects `Double` and `Float`. A payload from a client in another language looks like this:

```json
{
  "decisionId": "loan_eligibility",
  "version": null,
  "asOf": "2026-11-15",
  "facts": {
    "applicant": {
      "age": 30,
      "monthlyIncome": "25750000.50",
      "monthlyDebt": "1000000",
      "segment": "RETAIL",
      "blacklisted": false
    },
    "amount": 100000000.00
  }
}
```

The activity reads the facts with the input types of the decision version it evaluates. Undeclared names, values in the wrong form and `null` values are rejected as a whole request, with every problem listed. Numbers are read exactly from their text, never through `double`.

Read outputs with the typed accessors: `outcome.text("decision")`, `outcome.decimal("limit")`, `outcome.money("fee")`, `outcome.date("nextReview")`, `outcome.integer(…)`, `outcome.bool(…)`, or `outcome.outputValue(name, ValueType.listOf(ValueType.MONEY))` for lists. Each returns an empty `Optional` when the decision did not set the output.

The precision guarantee holds with Temporal's default Jackson 2 data converter, or any JSON converter, because exact values travel as text. If you opt in to Temporal's experimental Jackson 3 converter, JSON numbers sent by other clients are no longer read by RuleStone's exact deserializer; send decimals as strings.

## Errors and retries

| Failure type | Raised when | Retry | What to do |
|---|---|---|---|
| `RuleStone.DecisionNotFound` | Unknown decision or version, or no version effective on the business date | Never | Configuration error: check the registered versions and their dates. |
| `RuleStone.DecisionEvaluationFailed` | Missing or invalid input, a failing rule expression, a `UNIQUE` table with several matches | Never | Route the case to manual review. Do not substitute a default decision. Details: decision id, rule version. |
| `RuleStone.InvalidRequest` (activity only) | Facts not in the expected JSON forms | Never | Fix the caller. |
| Any other activity error | A bug or an outage | Yes, per the activity's retry options | Bound retries with `setMaximumAttempts` and alert on failures. |
| `RuleVersionMismatchException` (in-workflow only) | Replay cannot reproduce a recorded decision | Workflow task retried | See [When replay cannot reproduce a decision](#when-replay-cannot-reproduce-a-decision). |

All three `RuleStone.*` types are non-retryable `ApplicationFailure`s; the constants are in `RuleStoneFailures`. From the activity they reach the workflow as the cause of an `ActivityFailure`; from `RuleStoneWorkflow` they are thrown directly and fail the workflow unless caught. Retrying them is pointless: evaluation is deterministic, so the same request fails the same way.

Failure messages and details name the decision, version, rule, input and field involved, never input values. The underlying exception is not attached, so no nested message or stack trace reaches the workflow history. Nothing in the module logs facts. Facts *are* stored in history as activity inputs; if that must not happen in clear, configure a payload codec that encrypts payloads.

## Determinism and versioning rules

1. **A rule version is immutable once used.** Publish every change under a new version label. A changed hash under an old label stops in-workflow replays with `RuleVersionMismatchException`.
2. **Keep old versions registered** with the engine of every worker that runs in-workflow evaluations, until every workflow that used them has closed and the namespace retention period has passed. Use `effectiveTo` to retire a version for new decisions; do not delete its file.
3. **Do not reorder evaluations** in running workflows. Adding, removing or moving an `evaluate` call is a workflow code change; guard it with `Workflow.getVersion`.
4. **Facts are workflow data**, as described in [Facts in workflow code](#facts-in-workflow-code).
5. **Choose the business zone once.** Changing it does not affect running workflows, whose dates are recorded, but it changes which date new workflows get near midnight.

## Search attributes

`RuleStoneSearchAttributes.upsert(outcome, "decision")` sets four typed search attributes, so operators can find workflows by outcome, for example `RuleStoneDecision = 'loan_eligibility' AND RuleStoneOutcome = 'REVIEW'`:

| Attribute | Type | Value |
|---|---|---|
| `RuleStoneDecision` | Keyword | Decision id |
| `RuleStoneRuleVersion` | Keyword | `<version>@sha256:<hash>` |
| `RuleStoneOutcome` | Keyword | The JSON form of the named output; removed if the decision did not set it |
| `RuleStoneReasonCodes` | KeywordList | Reason codes; removed if there are none |

Register them in every namespace before deploying, or the upsert fails the workflow task:

```sh
temporal operator search-attribute create --namespace bank \
    --name RuleStoneDecision --type Keyword \
    --name RuleStoneRuleVersion --type Keyword \
    --name RuleStoneOutcome --type Keyword \
    --name RuleStoneReasonCodes --type KeywordList
```

A workflow holds one value per attribute; when it makes several decisions, upsert the one operators search by. `RuleStoneSearchAttributes.updates(…)` returns the updates for combining with your own. Search attributes are not encrypted by payload codecs and are visible to anyone with visibility access. Use a categorical output such as `decision` as the outcome, never an amount or personal data.

## Operational checklist

- [ ] The worker's engine and the activity's tables hold every rule version that open workflows (in-workflow style) used, plus the retention period.
- [ ] Rule releases add new version labels and never edit used versions. CI compares content hashes of released labels.
- [ ] Engine clock, activity clock and `RuleStoneWorkflow` zone are your bank's business time zone.
- [ ] Activity options have a short start-to-close timeout and bounded retries.
- [ ] Workflows handle `RuleStone.DecisionEvaluationFailed` by routing to manual review, not a default decision.
- [ ] Alerts fire on workflow task failures whose message contains `RuleStone replay mismatch`.
- [ ] The four search attributes are registered in each namespace that upserts them.
- [ ] A payload codec encrypts history if facts must not be stored in clear.
- [ ] A replay test runs recorded production histories against the new rule set before each release, with `WorkflowReplayer`.
