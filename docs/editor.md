# Web editor

> **Embedding editing into an existing system?** Use the [editor SDK](editor-sdk.md) instead: the same kind of editors as Web Components for your own React, Angular, Vue or plain HTML application, which keeps its own storage, sign-in and approval workflow. This page describes the standalone editor application.

The RuleStone editor is a small web application for creating and changing decision tables without typing JSON. Business analysts edit rules in a grid, build conditions from drop-down lists, test the table with sample data and see problems on the exact cell while they type. Developers get the same `.stone.json` files as before, saved in a stable format that diffs cleanly in Git.

- [Running the editor](#running-the-editor)
- [Using the editor](#using-the-editor)
- [Security model](#security-model)
- [How files are saved](#how-files-are-saved)
- [Condition builder and CEL](#condition-builder-and-cel)
- [API reference](#api-reference)
- [Embedding in Java](#embedding-in-java)
- [Limitations and roadmap](#limitations-and-roadmap)

## Running the editor

The editor is one executable jar with no other requirements than Java 17 or later. Build it from the repository root:

```
mvn -B verify
```

Then start it on the directory of rules you want to edit, usually a clone of the Git repository that holds your `.stone.json` files:

```
java -jar rulestone-editor/target/rulestone-editor-0.1.0-SNAPSHOT-all.jar --rules-dir ./rules
```

and open `http://127.0.0.1:8080/`.

| Option | Default | Meaning |
|---|---|---|
| `--rules-dir <dir>` | required | Directory of `.stone.json` files, searched recursively. Hidden directories such as `.git` are skipped. |
| `--port <n>` | `8080` | Port to listen on; `0` picks a free port. |
| `--host <address>` | `127.0.0.1` | Address to listen on. Read [Security model](#security-model) before changing it. |
| `--allowed-host <name>` | none | An additional `Host` header to accept, such as the name of your reverse proxy, e.g. `rules.bank.example`. Repeatable. |
| `--max-body-bytes <n>` | `1048576` | Largest request body accepted. |

The jar contains the user interface; nothing is downloaded at run time and no internet access is needed.

## Using the editor

**Workspace.** The start page lists every decision in the directory, grouped by `id`, with each version's effective period, hit policy, number of rules and problems. The version in force today is marked. **New decision table** opens a wizard for the id, version, hit policy and effective dates and creates a file with one catch-all rule.

**Rules.** One row per rule: id, description, condition (*when*), one column per output, and reason code. Rows can be added, duplicated, deleted and reordered with the buttons, by dragging the handle, or with <kbd>Alt</kbd>+<kbd>↑</kbd>/<kbd>↓</kbd>. The grid is keyboard-navigable: arrow keys move between cells, <kbd>Enter</kbd> edits a cell and <kbd>Escape</kbd> returns to the grid.

- The *when* cell shows the condition as readable clauses when the [condition builder](#condition-builder-and-cel) can represent it, and as highlighted CEL otherwise. **Builder…** opens the builder; **CEL** switches to the expression with autocomplete of input paths (`applicant.monthlyIncome`) and functions.
- An output cell shows a typed field for literal values: text for `string`, a number for `int` and `decimal`, amount and currency for `money`, a date picker for `date`, true/false for `bool`, and tags for lists. **ƒx** switches it to a CEL expression. An empty cell means the rule does not set that output.

**Inputs & outputs.** Declares the table's inputs and outputs with type pickers, nested structures and lists. The list of types comes from the server, so types added to RuleStone appear without changing the editor.

**Validation.** Every change is checked by the server within half a second, exactly as `RuleStone.builder().build()` would, plus a check against the other versions of the same decision in the directory (duplicate versions and overlapping effective periods). Problems appear on the cell they concern, with the wrong part of an expression underlined, and in the problems panel. **Save** is disabled while there are problems; **Save anyway** saves an invalid table after a confirmation, for example to share work in progress in a branch.

**Test.** Evaluates the table as it is in the editor, unsaved changes included, with facts entered in a form generated from the inputs or as JSON, and a business date (`asOf`). The result shows the outputs, reason codes, matched rules and rule version, and the Rules tab highlights matched, unmatched and unevaluated rows.

**JSON & changes.** The file exactly as it will be saved, with a copy button, and a line diff against the file as it was loaded.

**Save as new version** copies the rules, including unsaved changes, into a new file with the next version number and a new `effectiveFrom`. It can also set `effectiveTo` of the current version to that date, so that the two versions follow each other with no gap and no overlap.

The user interface is available in English and Vietnamese.

## Security model

**The editor has no authentication and no authorization in this version.** Anyone who can send requests to its port can read and change every rule in the directory. Run it in one of two ways:

1. **On the user's own machine**, the default: it listens on `127.0.0.1` only, so other machines cannot reach it. Each user edits a clone of the rules repository and publishes changes through Git and your usual review process.
2. **Behind your organization's single sign-on reverse proxy**, which authenticates users and allows only those who may edit rules. Bind the editor to an address only the proxy can reach, and name the proxy's host:

   ```
   java -jar rulestone-editor-0.1.0-SNAPSHOT-all.jar --rules-dir /srv/rules --host 10.0.0.5 --allowed-host rules.bank.example
   ```

   The editor serves its pages with relative URLs, so it also works under a sub-path of the proxy.

Never expose it directly to a network. In both setups, approval of rule changes (maker-checker) happens outside the editor, in Git: review and merge the commits before the rules reach production.

The editor protects itself against attacks from other web sites and malformed requests:

| Threat | Protection |
|---|---|
| Another web site driving the user's browser (CSRF) | Requests with an `Origin` other than the editor's own are refused (403). So are API requests marked `Sec-Fetch-Site: cross-site`. Request bodies must be `application/json`, which an HTML form cannot send, and CORS preflight requests are not answered. |
| DNS rebinding | The `Host` header must be `localhost`, `127.0.0.1` or `[::1]` with the editor's port, the bind address, or an `--allowed-host`; otherwise 421. |
| Reading or writing other files | File names must be relative paths of letters, digits, `_`, `.` and `-`, ending in `.stone.json`, at most 8 directories deep. `..`, absolute paths, hidden files, backslashes, encoded separators and symbolic links are refused. |
| Oversized requests | Bodies larger than `--max-body-bytes` are refused (413); files larger than 4 MiB are not read. |
| Script injection, framing | `Content-Security-Policy: default-src 'self'; script-src 'self'; ... frame-ancestors 'none'`, `X-Frame-Options: DENY`, `X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer`. |
| Customer data in logs | Facts entered in the test panel are never logged or echoed: error messages name inputs and expected types, never values. The server log records only startup and unexpected errors, without request content. |
| Lost updates | Saving sends the `ETag` of the file as loaded in `If-Match`; if the file changed on disk meanwhile (another user, `git pull`), the save is refused (412) and the user reloads. Files are written atomically (temporary file, then rename). |

## How files are saved

The editor writes files in a fixed, human-diffable layout, so that a change to one rule shows up as a change to a few lines in Git:

- keys in a fixed order: table `id`, `version`, `description`, `effectiveFrom`, `effectiveTo`, `hitPolicy`, `inputs`, `outputs`, `rules`; rule `id`, `description`, `when`, `then`, `reasonCode`;
- the author's order for inputs, outputs, struct fields and `then`, because it is meaningful (outputs are reported in declaration order);
- two-space indentation, one key per line, objects with a single short value on one line (`"then": { "decision": "'REJECT'" }`), short lists on one line (`["decimal"]`), UTF-8 without escaping Vietnamese text, and a final newline.

Formatting never changes a table's [content hash](decision-tables.md#content-hash): the hash is computed over the canonical form, which ignores whitespace and key order. Opening and saving an unchanged file written by hand reformats it but keeps its `ruleVersion`.

A typical workflow:

1. `git pull` the rules repository, or create a branch.
2. Edit and test in the editor; save.
3. Review the diff (`git diff`, or the **JSON & changes** tab), run the decision tests (`.stone-test.json`, see [Testing](testing.md)), commit and open a pull request.
4. After review and merge, deploy the rules as usual.

Create a new version, rather than editing a version that is already in force, whenever a change must apply from a given date: past decisions can then still be replayed with the version that made them.

## Condition builder and CEL

The builder edits a rule's `when` as a list of clauses, all joined by AND or all by OR. Each clause is an input path, an operator and a value. It generates ordinary CEL, so the file contains nothing editor-specific:

| Builder | Generated CEL |
|---|---|
| `applicant.age` *is at least* `18` | `applicant.age >= 18` |
| `applicant.monthlyIncome` *is greater than* `0.5` | `applicant.monthlyIncome > decimal('0.5')` |
| `applicant.segment` *equals* `SME` | `applicant.segment == 'SME'` |
| `payment.amount` *is at least* `1500000 VND` | `payment.amount >= money('1500000', 'VND')` |
| `applicant.birthDate` *is less than* `2008-11-01` | `applicant.birthDate < date('2008-11-01')` |
| `applicant.blacklisted` *is true* / *is false* | `applicant.blacklisted` / `!applicant.blacklisted` |
| `applicant.segment` *is one of* `RETAIL`, `SME` | `applicant.segment in ['RETAIL', 'SME']` |
| `applicant.segment` *is not one of* `VIP` | `!(applicant.segment in ['VIP'])` |
| `customer.tags` *contains* `PEP` | `'PEP' in customer.tags` |
| `applicant.segment` *is provided* / *is not provided* | `has(applicant.segment)` / `!has(applicant.segment)` |
| `amount` *is at most* (another input) `applicant.limit` | `amount <= applicant.limit` |
| `amount` *is at most* (an expression) `applicant.monthlyIncome * 12` | `amount <= applicant.monthlyIncome * 12` |
| no clauses | `true` |

The operators offered depend on the input's type: `bool` inputs offer *is true* and *is false*; `int`, `decimal`, `money` and `date` the six comparisons and *is one of*; `string` equality and *is one of*; lists *contains*; struct fields also *is provided*. Values are checked as you type: a `decimal` must be written in plain notation (`1234.50`), `money` as an amount and an upper-case currency code, a `date` must exist.

The builder reads existing expressions back, including ones written by hand: `decimal(5)`, `money(2000, 'VND')` and double-quoted strings are recognised, and parentheses around a value are understood. An expression the builder cannot represent, such as `applicant.monthlyDebt / applicant.monthlyIncome > decimal('0.5')` (a calculation on the left), a mix of AND and OR, or list functions like `exists`, is shown read-only and kept exactly as written; edit it as CEL. You can switch any condition to CEL at any time.

## API reference

The user interface uses a JSON API that you can also call from scripts. All requests and responses are UTF-8 JSON. Requests with a body must send `Content-Type: application/json`. Errors are answered as `{"error": {"code": "...", "message": "..."}}` with an HTTP status: 400 malformed request, 403 cross-origin, 404 not found, 405 wrong method, 409 file exists, 412 file changed, 413 too large, 415 not JSON, 421 unknown host, 422 invalid table or facts.

The examples below run against a directory holding copies of `deposit_rate`, `loan_eligibility` versions 2 and 3, and `transfer_fees` from [`examples/`](../examples/), in this order. `…` stands for text left out. The test suite runs every example on this page and compares the responses.

### Problems

Validation results are lists of problems. Each problem has a one-line `message`, the `detail` exactly as RuleStone reports it, and a `location` with whatever applies:

| Field | Meaning |
|---|---|
| `path` | JSON path in the table, e.g. `$.rules[0].when` |
| `rule`, `ruleIndex` | The rule's id and its zero-based position |
| `field` | The rule field (`id`, `when`, `then`, `reasonCode`) when `rule` is set; otherwise the table field (`id`, `version`, `effectiveFrom`, `inputs`, …) |
| `output` | The output, for `then` and `outputs` problems |
| `input` | The input path, for `inputs` problems, e.g. `applicant.age` |
| `file` | The other file involved in a version conflict |
| `line`, `column` | One-based position in the expression, for expression errors |

### `GET /api/meta`

Types, hit policies and expression functions for the user interface. `types` lists the engine's primitive types; `implicitInputs` the inputs every rule can read without declaring them.

```http
GET /api/meta
```

```http
HTTP/1.1 200 OK

{
  "editorVersion": "…",
  "types": [
    { "name": "bool", "description": "true or false" },
    { "name": "int", "description": "64-bit integer" },
    { "name": "string", "description": "text" },
    { "name": "decimal", "description": "exact decimal number, for amounts and rates" },
    { "name": "money", "description": "amount with a currency" },
    { "name": "date", "description": "calendar date (yyyy-MM-dd)" },
    "…"
  ],
  "hitPolicies": [
    { "name": "UNIQUE", "description": "At most one rule may match; several matches fail the evaluation." },
    "…"
  ],
  "functions": [
    { "name": "decimal", "kind": "function", "signature": "decimal(string) -> decimal", "insert": "decimal('${}')",
      "description": "Exact decimal from text in plain notation, e.g. decimal('0.05')." },
    "…"
  ],
  "implicitInputs": [{ "name": "businessDate", "type": "date" }],
  "limits": { "maxBodyBytes": 1048576, "maxFileBytes": 4194304 }
}
```

### `GET /api/tables`

Every `.stone.json` file in the directory, sorted by decision id and effective date, with its problems: problems in the file itself and conflicts with other versions of the same decision. A file that cannot be read has only `file` and `problems`.

```http
GET /api/tables
```

```http
HTTP/1.1 200 OK

{
  "directory": "rules",
  "tables": [
    {
      "file": "deposit_rate.stone.json",
      "id": "deposit_rate",
      "version": "1",
      "description": "Term deposit interest rate by term and balance",
      "hitPolicy": "UNIQUE",
      "ruleCount": 4,
      "ruleVersion": "1@sha256:…",
      "problems": []
    },
    {
      "file": "loan_eligibility.v2.stone.json",
      "id": "loan_eligibility",
      "version": "2",
      "effectiveFrom": "2026-01-01",
      "effectiveTo": "2026-11-01",
      "hitPolicy": "FIRST",
      "ruleCount": 3,
      "ruleVersion": "2@sha256:…",
      "problems": []
    },
    "…"
  ]
}
```

### `GET /api/tables/{file}`

One file: its text as stored (`content`), the parsed `table` (`null` if the file is not JSON), its problems, and an `etag` (also sent as the `ETag` header) to pass back when saving. `{file}` is the path relative to the rules directory, e.g. `loans/loan_eligibility.v3.stone.json`.

```http
GET /api/tables/loan_eligibility.v3.stone.json
```

```http
HTTP/1.1 200 OK

{
  "file": "loan_eligibility.v3.stone.json",
  "etag": "…",
  "content": "{\n  \"id\": \"loan_eligibility\",\n  \"version\": \"3\",…",
  "table": { "id": "loan_eligibility", "version": "3", "effectiveFrom": "2026-11-01", "hitPolicy": "FIRST" },
  "valid": true,
  "problems": [],
  "ruleVersion": "3@sha256:…"
}
```

### `POST /api/validate`

Checks a table, sent as the body, as the engine would, and against the other versions of the same decision in the directory. Pass `?file=` with the file the table is (or will be) saved as, so that it is not compared with its own saved copy. `ruleVersion` is the content hash the engine will report, present when the table itself is valid.

```http
POST /api/validate?file=loan_eligibility.v3.stone.json
Content-Type: application/json

{
  "id": "loan_eligibility", "version": "3", "effectiveFrom": "2026-11-01", "hitPolicy": "FIRST",
  "inputs": { "applicant": { "age": "int" } },
  "outputs": { "decision": "string" },
  "rules": [
    { "id": "R1", "when": "applicant.agee < 18", "then": { "decision": "'REJECT'" }, "reasonCode": "UNDERAGE" }
  ]
}
```

```http
HTTP/1.1 200 OK

{
  "valid": false,
  "problems": [
    {
      "message": "undefined field 'agee'",
      "detail": "loan_eligibility@3 rules[R1].when: ERROR: <input>:1:10: undefined field 'agee'\n | applicant.agee < 18\n | .........^",
      "location": { "path": "$.rules[0].when", "rule": "R1", "ruleIndex": 0, "field": "when", "line": 1, "column": 10 }
    }
  ],
  "ruleVersion": null
}
```

A new version that starts while version 3 is still open-ended overlaps it:

```http
POST /api/validate
Content-Type: application/json

{
  "id": "loan_eligibility", "version": "4", "effectiveFrom": "2026-12-01", "hitPolicy": "FIRST",
  "inputs": {}, "outputs": {}, "rules": [{ "id": "R1", "when": "true" }]
}
```

```http
HTTP/1.1 200 OK

{
  "valid": false,
  "problems": [
    {
      "message": "effective period overlaps version 3 in loan_eligibility.v3.stone.json (2026-11-01 to no end); set effectiveTo of version 3 or move effectiveFrom",
      "location": { "path": "$.effectiveFrom", "field": "effectiveFrom", "file": "loan_eligibility.v3.stone.json" }
    }
  ],
  "ruleVersion": "4@sha256:…"
}
```

### `POST /api/evaluate`

Evaluates a table, which need not be saved, with `facts` and an optional business date `asOf` (`yyyy-MM-dd`; default: today on the server). The table's own version is used whatever its effective dates; `effectiveOnAsOf` tells whether it is in force on `asOf`.

Facts and outputs use the JSON forms of [`FactsJson`](java-api.md): `decimal` as a number or a numeric string, `money` as `"<amount> <currency>"`, `date` as `"yyyy-MM-dd"`. JSON numbers are read exactly, never as floating point, and decimal outputs are returned as strings so that no JavaScript client rounds them.

```http
POST /api/evaluate
Content-Type: application/json

{
  "table": {
    "id": "transfer_fee", "version": "1", "hitPolicy": "FIRST",
    "inputs": { "transfer": { "amount": "money", "valueDate": "date" } },
    "outputs": { "fee": "money", "settles": "date" },
    "rules": [
      { "id": "LARGE", "when": "transfer.amount > money(1000000, 'VND')",
        "then": { "fee": "(transfer.amount * decimal('0.0005')).round()", "settles": "transfer.valueDate.plusDays(1)" },
        "reasonCode": "LARGE_AMOUNT" },
      { "id": "SMALL", "when": "true", "then": { "fee": "money(2000, 'VND')", "settles": "transfer.valueDate" } }
    ]
  },
  "facts": { "transfer": { "amount": "150000000 VND", "valueDate": "2026-11-02" } },
  "asOf": "2026-11-02"
}
```

```http
HTTP/1.1 200 OK

{
  "decisionId": "transfer_fee",
  "ruleVersion": "1@sha256:…",
  "asOf": "2026-11-02",
  "effectiveOnAsOf": true,
  "matched": true,
  "output": { "fee": "75000 VND", "settles": "2026-11-03" },
  "reasonCodes": ["LARGE_AMOUNT"],
  "matchedRules": ["LARGE"],
  "trace": [{ "ruleId": "LARGE", "matched": true }]
}
```

Facts that do not match the inputs are refused with every problem, by location and expected form, never quoting the values:

```http
POST /api/evaluate
Content-Type: application/json

{
  "table": {
    "id": "transfer_fee", "version": "1", "hitPolicy": "FIRST",
    "inputs": { "transfer": { "amount": "money", "valueDate": "date" } },
    "outputs": { "fee": "money" },
    "rules": [{ "id": "R1", "when": "true", "then": { "fee": "transfer.amount" } }]
  },
  "facts": { "transfer": { "amount": 150000000, "valueDate": "02/11/2026" } }
}
```

```http
HTTP/1.1 422 Unprocessable Entity

{
  "error": { "code": "invalid_facts", "message": "…" },
  "factProblems": [
    "transfer.amount: expected money as \"<amount> <CCY>\", e.g. \"1500000 VND\"",
    "transfer.valueDate: expected a date as \"yyyy-MM-dd\""
  ]
}
```

An evaluation failure, such as a division by zero or two matches under `UNIQUE`, is answered with 422 and `"code": "evaluation_failed"`; an invalid table with 422, `"code": "invalid_table"` and its `problems`.

### `PUT /api/tables/{file}`

Saves a table, sent as the body, to an existing file in the [stable format](#how-files-are-saved). The example raises the rate of rule `MID`. A table with problems is refused with 422 and the problems, and the file is left unchanged; add `?force=true` to save it anyway. Send `If-Match` with the `etag` from loading the file to refuse the save (412) if the file has changed since.

```http
PUT /api/tables/deposit_rate.stone.json
Content-Type: application/json

{
  "id": "deposit_rate", "version": "1", "description": "Term deposit interest rate by term and balance",
  "hitPolicy": "UNIQUE",
  "inputs": { "termMonths": "int", "balance": "decimal" },
  "outputs": { "rate": "decimal" },
  "rules": [
    { "id": "SHORT", "when": "termMonths < 6", "then": { "rate": "decimal('0.035')" } },
    { "id": "MID", "when": "termMonths >= 6 && termMonths < 12", "then": { "rate": "decimal('0.049')" } },
    { "id": "LONG", "when": "termMonths >= 12 && balance < 5000000000", "then": { "rate": "decimal('0.052')" } },
    { "id": "VIP_LONG", "when": "termMonths >= 12 && balance >= 5000000000", "then": { "rate": "decimal('0.055')" } }
  ]
}
```

```http
HTTP/1.1 200 OK

{
  "saved": true,
  "file": "deposit_rate.stone.json",
  "etag": "…",
  "valid": true,
  "problems": [],
  "ruleVersion": "1@sha256:…"
}
```

### `POST /api/tables`

Creates a file: `{"table": {...}, "file": "optional/name.stone.json"}`. Without `file`, the name is `<id>.v<version>.stone.json`. An existing file is never overwritten (409). `closePrevious` sets `effectiveTo` of another version to the new table's `effectiveFrom` in the same request, after checking both; this is what **Save as new version** does. Problems and `?force=true` work as for `PUT`.

```http
POST /api/tables
Content-Type: application/json

{
  "table": {
    "id": "loan_eligibility", "version": "4", "effectiveFrom": "2027-01-01", "hitPolicy": "FIRST",
    "inputs": { "applicant": { "age": "int" } },
    "outputs": { "decision": "string" },
    "rules": [{ "id": "R1", "when": "applicant.age < 21", "then": { "decision": "'REJECT'" }, "reasonCode": "UNDERAGE" }]
  },
  "closePrevious": { "file": "loan_eligibility.v3.stone.json" }
}
```

```http
HTTP/1.1 201 Created

{
  "saved": true,
  "file": "loan_eligibility.v4.stone.json",
  "etag": "…",
  "valid": true,
  "problems": [],
  "ruleVersion": "4@sha256:…",
  "previous": { "file": "loan_eligibility.v3.stone.json", "etag": "…", "effectiveTo": "2027-01-01" }
}
```

### `POST /api/format`

Returns the text that saving the table would write, without saving it; the user interface uses it for the JSON view and the diff.

```http
POST /api/format
Content-Type: application/json

{ "rules": [{ "when": "true", "id": "R1" }], "version": "1", "id": "t" }
```

```http
HTTP/1.1 200 OK

{
  "content": "{\n  \"id\": \"t\",\n  \"version\": \"1\",\n  \"rules\": [\n    {\n      \"id\": \"R1\",\n      \"when\": \"true\"\n    }\n  ]\n}\n"
}
```

### Refused requests

A request from another web site's page carries that site's `Origin` and is refused:

```http
POST /api/validate
Content-Type: application/json
Origin: https://attacker.example

{}
```

```http
HTTP/1.1 403 Forbidden

{ "error": { "code": "cross_origin", "message": "cross-origin requests are not allowed" } }
```

## Embedding in Java

`rulestone-editor` is also a library, for example to start the editor from an internal tool or a test:

```java
try (EditorServer editor = EditorServer.builder()
        .rulesDir(Path.of("rules"))
        .port(0)                                   // any free port
        .start()) {
    System.out.println("Open " + editor.uri());
    // ...
}
```

| Method | Default | Purpose |
|---|---|---|
| `rulesDir(Path)` | required | Directory of `.stone.json` files. |
| `host(String)` | `127.0.0.1` | Address to listen on. |
| `port(int)` | `8080` | Port; `0` for any free port, then read `port()` or `uri()`. |
| `allowedHost(String)` | none | Additional `Host` header to accept. |
| `maxBodyBytes(long)` | 1 MiB | Largest request body. |
| `threads(int)` | 8 | Request threads. |

The server uses only the JDK's built-in HTTP server and Jackson. `close()` stops it.

## Limitations and roadmap

- **No users, roles or approval.** Authentication, per-decision permissions and an in-editor maker-checker workflow (draft, submit, approve) are on the roadmap. Until then, rely on the reverse proxy for access and on Git reviews for approval.
- **No audit trail of its own.** Who changed what is recorded by Git commits, not by the editor.
- **No Git integration.** The editor reads and writes files; committing, branching and pushing happen outside it.
- **One directory per server.** To edit several repositories, run several editors on different ports.
- **The condition builder covers flat AND/OR lists** of comparisons on input paths. Calculations on the left side, nested groups and list functions stay in CEL.
- **Renaming an input does not update the rules** that use it; validation points them out.
- **Desktop only.** The interface is designed for screens 1280 pixels wide or more.
