# Explanations

Every decision RuleStone makes has a [decision record](java-api.md#decision-records-and-auditing), and every reason for a decision has a stable reason code such as `DTI_HIGH`. An explanation turns a record into words: the outcome, the reasons with a reviewed message for each code, and what decided, in the customer's language. This page describes the reason-code catalog that holds the messages, how the engine checks it, and the explanation it renders.

Explanations are step 4 of [Hybrid AI decisioning](hybrid-ai.md#9-implementation-plan). They are available in the Java engine and in the browser engine of the [Editor SDK](editor-sdk.md), which give the same explanation, byte for byte.

- [The reason-code catalog](#the-reason-code-catalog)
- [Checking the catalog when the engine is built](#checking-the-catalog-when-the-engine-is-built)
- [Explaining a decision](#explaining-a-decision)
- [What an explanation contains](#what-an-explanation-contains)
- [Customers and staff](#customers-and-staff)
- [In the browser](#in-the-browser)
- [Testing explanations](#testing-explanations)
- [Limits](#limits)

## The reason-code catalog

A catalog is a JSON file, conventionally named `reason-codes.json`, kept in the same repository as the rules and reviewed the same way. This excerpt is from [`examples/reason-codes.json`](../examples/reason-codes.json), which has a message for every reason code of the [examples](examples.md):

```json
{
  "version": "1",
  "description": "Messages for the reason codes of the examples, reviewed by Credit Risk and Compliance",
  "locales": ["en", "vi"],
  "codes": {
    "DTI_HIGH": {
      "en": "Your monthly debt payments are too high compared with your income.",
      "vi": "Tổng số tiền trả nợ hằng tháng của quý khách quá cao so với thu nhập."
    },
    "HIGH_UTILISATION": {
      "en": "Your credit utilisation is high compared with your limits.",
      "vi": "Mức sử dụng hạn mức tín dụng của quý khách đang cao so với hạn mức được cấp."
    },
    "MODEL_UNAVAILABLE": {
      "en": "Staff: the risk model was not available, so the application was referred for manual review.",
      "vi": "Nội bộ: không có kết quả từ mô hình rủi ro, nên hồ sơ được chuyển xem xét thủ công."
    }
  }
}
```

| Field | Meaning |
|---|---|
| `version` | Required. The version label, as for tables: letters, digits, `.`, `_` or `-`. |
| `description` | Optional free text, for example who reviewed the messages and when. |
| `locales` | Required. The languages of the messages, as language tags such as `en`, `vi` or `en-GB`, each once. |
| `codes` | Required. Reason code to locale to message. |

The catalog is read strictly, as tables are, and every problem is reported together, with its location:

- every code is UPPER_SNAKE_CASE, as in tables and flows;
- every code has a message in **exactly** the declared locales: a missing locale and an undeclared one are both problems;
- a message is one line of plain text, not blank, without leading or trailing spaces and without control characters;
- a message contains no `{` or `}`. These are reserved for placeholders, so that a later version can add them without changing the meaning of an existing catalog. Messages never contain values.
- unknown fields and duplicate keys are problems.

Like tables and parameter sets, a catalog has an exact version: its label followed by the SHA-256 of its canonical form, for example `1@sha256:fccd…`. Every explanation names the exact catalog version its messages come from, so a notice sent to a customer can be traced to the reviewed text. To change a message, change the file and its `version`, and have the change reviewed. A catalog has no effective dates: the engine uses the one it was built with.

```java
ReasonCodeCatalog catalog = ReasonCodeCatalogJson.read(Path.of("rules/reason-codes.json"));
ReasonCodeCatalogJson.exactVersion(catalog);   // "1@sha256:fccd…"
catalog.message("DTI_HIGH", "vi");             // Optional[Tổng số tiền trả nợ hằng tháng …]
```

## Checking the catalog when the engine is built

Register the catalog with the engine. The engine then checks, when it is built, that every reason code that a registered table or flow can give has a message, in every locale of the catalog: the `reasonCode` of every rule, and, in flows, the `reasonCodes` map and the `onFailure` reason code of every [model node](flows.md#model-nodes).

```java
RuleStoneEngine engine = RuleStone.builder()
        .decisions(tables)
        .flows(flows)
        .reasonCodeCatalog(catalog)
        .requireReasonCodeCatalog()   // fail unless a catalog is registered
        .build();
```

A catalog is optional: without one, the engine works as before and cannot explain records. `requireReasonCodeCatalog()` makes it mandatory, so that every decision the engine makes can be explained with reviewed text, as adverse-action notices need. A code without a message is reported with the other problems of the tables and flows, located as they are:

```text
invalid decision definition (1 problem):
  - credit_limit_increase@1 rules[DTI_HIGH].reasonCode: reason code 'DTI_HIGH' is not in the reason-code catalog
```

A catalog may hold more codes than the engine's tables use, so one catalog can serve several engines.

## Explaining a decision

`engine.explain(record, locale)` explains any decision record in one of the catalog's locales. The locale is a plain string: nothing depends on the JVM's default locale or time zone.

```java
DecisionResult result = engine.evaluate(
        DecisionRef.of("credit_limit_increase").asOf(LocalDate.of(2026, 12, 1)).correlationId("CLI-2026-000318"),
        facts);

Explanation explanation = engine.explain(result.record(), "vi");
explanation.reasonCodes();                       // [DTI_HIGH]
explanation.reasons().get(0).message();          // Optional[Tổng số tiền trả nợ hằng tháng …]
String notice = Explanations.text(explanation);  // the text below
String json = Explanations.write(explanation);   // one line of JSON
```

`Explanations.explain(record, catalog, locale)` does the same without an engine, for example in a service that writes notices from stored records. An engine without a catalog throws `UnsupportedOperationException`, and a locale the catalog does not have throws `IllegalArgumentException`.

`Explanations.text` writes one item per line, with headings in the explanation's language:

```text
Quyết định: credit_limit_increase 1@sha256:192cf62ada772414ce5857bc657ca2a61f2587650f45a7ac0007808f32054444
Ngày nghiệp vụ: 2026-12-01
Kết quả: decision = "REJECT"
Lý do:
  1. Tổng số tiền trả nợ hằng tháng của quý khách quá cao so với thu nhập. (DTI_HIGH, credit_limit_increase)
Căn cứ quyết định:
  - credit_limit_increase: table 1@sha256:192cf62ada772414ce5857bc657ca2a61f2587650f45a7ac0007808f32054444; khớp DTI_HIGH
Danh mục mã lý do: 1@sha256:fccd049e783da1c1ee3bd1ff85d2b38ff479b13db06ed5494c5b3ceb48d1ec71
```

```text
Decision: credit_limit_increase 1@sha256:192cf62ada772414ce5857bc657ca2a61f2587650f45a7ac0007808f32054444
Business date: 2026-12-01
Outcome: decision = "REJECT"
Reasons:
  1. Your monthly debt payments are too high compared with your income. (DTI_HIGH, credit_limit_increase)
Decided by:
  - credit_limit_increase: table 1@sha256:192cf62ada772414ce5857bc657ca2a61f2587650f45a7ac0007808f32054444; matched DTI_HIGH
Reason-code catalog: 1@sha256:fccd049e783da1c1ee3bd1ff85d2b38ff479b13db06ed5494c5b3ceb48d1ec71
```

Headings are built in for English and Vietnamese. A catalog may declare other locales; their messages are used as written, with English headings. For a flow, each node visited is one line: a `switch` names the branch it took, a `split` its bucket, and a model node its pinned version, its status and its attributions by rank and direction, with the reason code each one gave:

```text
  - pd_behaviour: model retail_pd_behaviour 2.4.0; OK
      1. utilisation: increases risk (HIGH_UTILISATION)
      2. dpd30Count12m: decreases risk
```

`Explanations.write` gives the same content as JSON, with fields in a fixed order and absent fields left out, as for decision records:

```json
{
  "recordId": "6f1c0f3e-5b1d-4f0e-9a52-1d3a3f9c2b7e",
  "correlationId": "CLI-2026-000318",
  "decision": "credit_limit_increase",
  "version": "1@sha256:192cf62ada772414ce5857bc657ca2a61f2587650f45a7ac0007808f32054444",
  "businessDate": "2026-12-01",
  "status": "OK",
  "locale": "en",
  "catalog": "1@sha256:fccd049e783da1c1ee3bd1ff85d2b38ff479b13db06ed5494c5b3ceb48d1ec71",
  "outcome": { "decision": "REJECT" },
  "reasons": [
    { "code": "DTI_HIGH", "source": "credit_limit_increase",
      "message": "Your monthly debt payments are too high compared with your income." }
  ],
  "steps": [
    { "node": "credit_limit_increase", "type": "table",
      "version": "1@sha256:192cf62ada772414ce5857bc657ca2a61f2587650f45a7ac0007808f32054444",
      "matchedRules": ["DTI_HIGH"] }
  ]
}
```

Rendering is a pure function of the record, the catalog and the locale: there is no clock and no randomness, so the same record, catalog and locale always give the same text and the same JSON, byte for byte, in Java and in the browser. A record of a failed evaluation is explained too: its outcome reads `no decision (ERROR)`.

## What an explanation contains

An explanation holds nothing that the record does not hold, apart from the catalog's messages. Since a record never contains input values, neither does an explanation, and it can be stored and logged like the record.

| Part | Content |
|---|---|
| Header | Record id, correlation id, decision, exact version, business date, status, locale and exact catalog version |
| `outcome` | The decision's outputs, as in the record |
| `reasons` | Each reason code of the record, in order, with the node that gave it and its message |
| `steps` | Every node visited: table versions and matched rules, branches and buckets taken, and for model nodes the model id, pinned version, status and attributions by rank and direction |
| `params` | The parameter set versions used, if any |

It also leaves out what the person affected does not need: the facts hash, the evaluation instant, model outputs and confidence, attribution methods, latencies and failure details. Contribution values and feature values are never in a record, so they cannot be in an explanation either.

Outputs are shown as the record has them. If a table copies an input into an output, the explanation shows that output; keep inputs out of outputs that customers see.

## Customers and staff

One catalog serves both audiences, with one message per code and locale. Write each message for whoever will read it:

- **Credit decisions** are explained to the customer, for example in an adverse-action notice. Write these messages in plain language, addressing the customer, and state the reason, not the threshold. `DTI_HIGH` says the debt payments are too high compared with the income; it does not give the limit.
- **Fraud, AML and model-failure codes** are for staff. Telling a customer that a transaction raised a suspicion alert or matched a sanctions list can amount to tipping off, which AML law prohibits. The example catalog starts these messages with "Staff:" ("Nội bộ:") so that a reviewer sees at once that they must not go into a customer letter.

The engine does not choose the audience: the application that sends the notice decides which decisions are explained to the customer, and a reviewer checks the messages before they are released, as for any rule change.

## In the browser

The browser engine reads the same catalog and gives the same explanations:

```js
import { createEngine, explanationText, parseReasonCodeCatalog } from '@rulestone/engine'

const engine = await createEngine({
  tables,
  reasonCodeCatalog: parseReasonCodeCatalog(catalogText),
  requireReasonCodeCatalog: true,
})
const result = await engine.evaluateJson({ id: 'credit_limit_increase', asOf: '2026-12-01' }, facts)
console.log(explanationText(engine.explain(result.record, 'vi')))
```

`writeExplanation`, `explainDecision` (without an engine) and `reasonCodeCatalogVersionOf` mirror the Java methods. `<rulestone-try-it>` and `<rulestone-table-editor>` take a catalog in their `reasonCodes` property; the Try it panel then lists the messages of each run's reasons in the panel's language. The [playground](https://rulestone.dxa.io.vn/playground) uses the examples' catalog. See [Editor SDK](editor-sdk.md).

## Testing explanations

A `.stone-test.json` case can expect the explanation of its record in one locale: its reason codes, each of which must have a message, its messages, or both. See [Testing explanations](testing.md#testing-explanations).

```json
"expect": {
  "output": { "decision": "REJECT" },
  "explanation": {
    "locale": "vi",
    "reasons": ["DTI_HIGH"],
    "messages": ["Tổng số tiền trả nợ hằng tháng của quý khách quá cao so với thu nhập."]
  }
}
```

## Limits

- **No placeholders.** Messages are fixed text. A placeholder for an output value, such as an offered limit, may be added later; `{` and `}` are reserved for it.
- **One catalog per engine,** without effective dates. Messages for a new product go into the same file.
- **Rephrasing.** An LLM may later rephrase explanations for tone, using the explanation as its only input. The codes and the reviewed messages stay the source of truth.
