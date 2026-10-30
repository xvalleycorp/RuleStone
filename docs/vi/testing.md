---
source: testing.md
sourceHash: 1004ee8edf4b4dd27faa035af7f0c0a1c445220c7b25b44205f17790325f3aca
---

# Kiểm thử bảng quyết định

Bảng quyết định cũng là mã nguồn: chỉ cần đổi một ngưỡng hay đảo thứ tự một luật là đã thay đổi việc ai được vay. Trang này giải thích cách kiểm thử bảng quyết định, và các [luồng quyết định](flows.md) được xây từ chúng, bằng mô-đun `rulestone-test`, để mọi luật đều được kiểm thử trước khi lên môi trường production.

Mô-đun cung cấp ba công cụ:

| Công cụ | Dùng để |
|---|---|
| [Tệp `.stone-test.json`](#ca-kiểm-thử-bằng-json) | Viết ca kiểm thử dưới dạng dữ liệu, không cần Java. Chuyên viên phân tích nghiệp vụ có thể viết và rà soát chúng ngay cạnh bảng quyết định. |
| [`StoneTests`](#chạy-tệp-kiểm-thử-với-junit-5) và [`RuleCoverage`](#độ-bao-phủ-luật) | Chạy các tệp đó trong mọi bản build dùng JUnit 5, mỗi ca là một test, và làm bản build thất bại nếu có luật không được ca kiểm thử nào khớp, hoặc có nút hay nhánh của luồng chưa từng được đi qua. |
| [`DecisionCheck`](#assertion-dạng-fluent-trong-java) | Viết ca kiểm thử bằng Java với assertion dạng fluent, trong bất kỳ framework kiểm thử nào. |

- [Thêm mô-đun](#thêm-mô-đun)
- [Ca kiểm thử bằng JSON](#ca-kiểm-thử-bằng-json)
- [Chạy tệp kiểm thử với JUnit 5](#chạy-tệp-kiểm-thử-với-junit-5)
- [Độ bao phủ luật](#độ-bao-phủ-luật)
- [Kiểm thử luồng quyết định](#kiểm-thử-luồng-quyết-định)
- [Assertion dạng fluent trong Java](#assertion-dạng-fluent-trong-java)
- [Thực hành khuyến nghị cho ngân hàng](#thực-hành-khuyến-nghị-cho-ngân-hàng)

## Thêm mô-đun

Thêm `rulestone-test` với scope `test`, bên cạnh `rulestone-core`:

```xml
<dependency>
    <groupId>vn.io.dxa.rulestone</groupId>
    <artifactId>rulestone-test</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <scope>test</scope>
</dependency>
```

`StoneTests` cần JUnit 5 (`org.junit.jupiter:junit-jupiter-api`), được `rulestone-test` khai báo với scope `provided`: mô-đun dùng phiên bản JUnit mà dự án của bạn đã có và không kéo theo phiên bản riêng. `DecisionCheck`, `StoneTestJson` và `RuleCoverage` không cần JUnit và dùng được với mọi framework kiểm thử.

## Ca kiểm thử bằng JSON

Hãy lưu các ca kiểm thử trong một tệp tên `<name>.stone-test.json`, đặt cạnh bảng mà nó kiểm thử. Mọi bảng trong [`examples/`](../../examples/) đều có một tệp như vậy, ví dụ [`examples/loan_eligibility.v3.stone-test.json`](../../examples/loan_eligibility.v3.stone-test.json). Hai ca kiểm thử trong tệp đó:

```json
{
  "decision": "loan_eligibility",
  "cases": [
    {
      "name": "adult with low debt is approved",
      "asOf": "2026-12-01",
      "facts": {
        "applicant": { "age": 30, "monthlyIncome": 25750000.50, "monthlyDebt": 5000000, "segment": "RETAIL", "blacklisted": false },
        "amount": 300000000
      },
      "expect": {
        "output": { "decision": "APPROVE", "limit": 309000000 },
        "exactOutput": true,
        "reasonCodes": [],
        "matchedRules": ["R4"],
        "ruleVersion": "3"
      }
    },
    {
      "name": "zero income fails the evaluation",
      "description": "R3 divides by monthlyIncome",
      "asOf": "2026-12-01",
      "facts": {
        "applicant": { "age": 30, "monthlyIncome": 0, "monthlyDebt": 1000, "segment": "RETAIL", "blacklisted": false },
        "amount": 1000
      },
      "expect": { "error": "division by zero" }
    }
  ]
}
```

Mỗi ca kiểm thử đánh giá quyết định một lần với các `facts` của nó và so sánh kết quả với `expect`. Chỉ những kỳ vọng được viết ra mới được kiểm tra: một ca không có `matchedRules` chấp nhận mọi luật khớp.

### Tệp

| Trường | Bắt buộc | Nội dung |
|---|---|---|
| `decision` | có | Id của quyết định được kiểm thử, giống `id` của bảng hoặc của luồng. |
| `description` | không | Mô tả tự do, ví dụ văn bản chính sách làm căn cứ cho các ca kiểm thử. |
| `cases` | có | Mảng ca kiểm thử không rỗng. |

### Ca kiểm thử

| Trường | Bắt buộc | Nội dung |
|---|---|---|
| `name` | có | Duy nhất trong tệp. Được hiển thị làm tên test và trong thông báo lỗi, nên hãy mô tả tình huống: `"debt just above half of income is rejected"`. |
| `description` | không | Mô tả tự do, ví dụ điều khoản chính sách hoặc ticket mà ca kiểm thử này kiểm tra. |
| `asOf` | không | Ngày theo ISO (`yyyy-MM-dd`) dùng để chọn phiên bản có hiệu lực, như `DecisionRef.asOf`. **Hãy đặt giá trị này cho mọi quyết định có ngày hiệu lực**; nếu không, đồng hồ của engine sẽ quyết định và kết quả phụ thuộc vào ngày chạy kiểm thử. |
| `version` | không | Đánh giá đúng phiên bản này, bất kể ngày, như `DecisionRef.version`. |
| `facts` | có | Đối tượng ánh xạ tên đầu vào tới giá trị, như với `Facts`. Đối tượng trở thành `Map` và mảng trở thành `List`. Không được dùng `null`: hãy bỏ trường đó đi để kiểm thử trường hợp thiếu giá trị. |
| `models` | không | Với một luồng có [nút mô hình](flows.md#nút-mô-hình): kết quả mô hình theo id nút, hoặc một lỗi như `{ "failure": "TIMEOUT" }`. Xem [Luồng có nút mô hình](#luồng-có-nút-mô-hình). |
| `expect` | có | Đối tượng chứa các kỳ vọng dưới đây. `{}` chỉ kiểm tra rằng việc đánh giá thành công. |

### Kỳ vọng

| Trường | Kiểm tra |
|---|---|
| `output` | Mỗi đầu ra được liệt kê bằng giá trị đã cho. Đầu ra không được liệt kê thì không được kiểm tra. Giá trị `null` nghĩa là đầu ra phải vắng mặt, như khi một luật `FIRST` không đặt nó. |
| `exactOutput` | Với `true`, quyết định không được tạo ra đầu ra nào ngoài các đầu ra liệt kê trong `output`. Đòi hỏi phải có `output`. |
| `reasonCodes` | Đúng các mã lý do này, theo đúng thứ tự này. `[]` nghĩa là không có mã nào. |
| `matchedRules` | Đúng các luật này khớp, theo thứ tự đánh giá. `[]` nghĩa là không có luật nào. Với một luồng, đó là các luật của mọi nút `table`, theo thứ tự. |
| `path` | Với một luồng: đúng các nút này được đi qua, theo thứ tự, từ nút bắt đầu tới nút kết thúc. Với một bảng, đường đi chỉ gồm id của quyết định. |
| `ruleVersion` | Phiên bản được dùng: `"3"` chấp nhận mọi nội dung của phiên bản 3; `"3@sha256:…"` (giá trị `ruleVersion` đầy đủ của một kết quả) chỉ chấp nhận đúng nội dung đó. |
| `error` | Việc đánh giá thất bại với một `RuleStoneException` có thông báo chứa đoạn văn bản này, ví dụ `"division by zero"`, `"balance: missing input"` hoặc `"no version of decision 'loan_eligibility' is effective on 2025-12-31"`. Không thể kết hợp với các trường khác. |

### Giá trị

Khi các tệp kiểm thử được chạy cùng với các bảng (xem [Độ bao phủ luật](#độ-bao-phủ-luật), hoặc truyền các bảng vào `StoneTests.dynamicTests` hay `StoneTestJson.read`), `facts` và `expect.output` được đọc theo các kiểu mà bảng khai báo, với cùng các dạng JSON như [`FactsJson`](java-api.md#fact-và-kết-quả-dạng-json-factsjson):

| Kiểu | Cách viết | Ví dụ |
|---|---|---|
| `bool` | `true` hoặc `false` | `"blacklisted": false` |
| `int` | một số nguyên | `"age": 30` |
| `string` | một chuỗi | `"segment": "RETAIL"` |
| `decimal` | một số, hoặc một chuỗi theo cách viết thông thường | `"monthlyIncome": 25750000.50`, `"rate": "0.0980"` |
| `money` | một chuỗi gồm số tiền, một khoảng trắng và mã ISO 4217 | `"amount": "1500000.50 VND"` |
| `date` | một chuỗi `yyyy-MM-dd` | `"valueDate": "2026-11-02"` |
| danh sách | một mảng | `"cashDepositsLast7d": ["250000000 VND", "270000000 VND"]` |
| struct | một đối tượng | `"account": { "openedOn": "2019-04-10", "riskRating": "LOW" }` |

Một ca kiểm thử trong [`examples/aml_transaction_monitoring.stone-test.json`](../../examples/aml_transaction_monitoring.stone-test.json) dùng tiền và ngày:

```json
{
  "name": "large cash deposit is a large cash transaction",
  "asOf": "2026-11-02",
  "facts": {
    "account": { "openedOn": "2019-04-10", "lastActivityBeforeToday": "2026-10-30", "riskRating": "LOW", "customerIsPep": false },
    "transaction": { "amount": "350000000 VND", "type": "CASH_DEPOSIT", "valueDate": "2026-11-02" },
    "history": { "cashDepositsLast7d": ["350000000 VND"], "totalInLast24h": "350000000 VND", "totalOutLast24h": "0 VND" }
  },
  "expect": {
    "output": { "alert": ["LARGE_CASH"], "score": [10] },
    "reasonCodes": ["LARGE_CASH_TRANSACTION"],
    "matchedRules": ["LARGE_CASH"]
  }
}
```

Khi đọc theo kiểu, một giá trị không khớp với kiểu của nó là lỗi trong tệp, được báo kèm đường dẫn, ví dụ `$.cases[3].facts.transaction.amount: expected money as "<amount> <CCY>", e.g. "1500000 VND"`. Các đầu vào, trường struct và đầu ra mà bảng không khai báo cũng vậy. Một trường struct bị bỏ qua sẽ ở trạng thái chưa đặt, nên luật nhận biết nó qua `has()`. Với `COLLECT`, mọi đầu ra kỳ vọng đều là danh sách. Các kiểu lấy từ phiên bản mà ca kiểm thử đánh giá: phiên bản được chỉ định bằng `version`, hoặc phiên bản có hiệu lực vào ngày `asOf`. Nếu không có cả hai, mọi phiên bản của quyết định phải khai báo cùng các đầu vào và đầu ra. Các ca kiểm thử cho một quyết định không nằm trong các bảng được truyền vào sẽ được đọc không theo kiểu.

Khi không có các bảng, giá trị giữ nguyên kiểu JSON và quyết định tự chuyển đổi chúng, nên không thể viết đầu vào `money` và `date`: quyết định từ chối một chuỗi ở chỗ cần `Money` hoặc `LocalDate`.

Số được đọc chính xác và không bao giờ đi qua dấu phẩy động: số nguyên trở thành `Long` (`BigInteger` nếu vượt quá 64 bit) và mọi số khác trở thành `BigDecimal`, giữ nguyên scale như khi viết, nên `25750000.50` được truyền cho quyết định đúng là `25750000.50`.

Số kỳ vọng được so sánh **theo giá trị**: `309000000`, `309000000.0` và `309000000.00` đều khớp với hạn mức `309000000`. Số tiền cùng loại tiền tệ cũng vậy: `"25000.00 VND"` khớp với `25000 VND`. Danh sách được so sánh lần lượt từng phần tử, theo thứ tự: `"fee": [25000, 10000]` khớp với đầu ra `COLLECT` `[25000, 10000]`.

### Đọc chặt chẽ

Giống như bảng quyết định, khoá không xác định và khoá trùng lặp đều là lỗi, để một kỳ vọng gõ sai không thể âm thầm tắt một bước kiểm tra. Mọi lỗi trong tệp được báo cáo cùng lúc kèm đường dẫn JSON:

```
invalid test file loan_eligibility.v3.stone-test.json (1 problem):
  - $.cases[0].expect.matchedRule: unknown field (allowed: error, exactOutput, matchedRules, output, path, reasonCodes, ruleVersion)
```

## Chạy tệp kiểm thử với JUnit 5

`StoneTests.dynamicTests` biến các tệp kiểm thử thành dynamic test của JUnit 5, mỗi ca là một test. Hãy truyền vào một tệp, hoặc một thư mục để tìm đệ quy các tệp `*.stone-test.json`:

```java
class DecisionTableTest {

    private final RuleStoneEngine engine = RuleStone.builder()
            .decision(DecisionTableJson.read(Path.of("rules/loan_eligibility.v2.stone.json")))
            .decision(DecisionTableJson.read(Path.of("rules/loan_eligibility.v3.stone.json")))
            .build();

    @TestFactory
    Stream<DynamicTest> decisionTables() {
        return StoneTests.dynamicTests(engine, Path.of("rules"));
    }
}
```

Mỗi test được đặt tên `<file>: <case name>`, ví dụ `loan_eligibility.v3.stone-test.json: adult with low debt is approved`. Một ca thất bại liệt kê mọi kỳ vọng không được đáp ứng. Nếu có người sửa `R4` để chỉ cho vay tối đa 10 tháng thu nhập thay vì 12, ca kiểm thử ở trên sẽ thất bại với:

```
loan_eligibility.v3.stone-test.json, case "adult with low debt is approved", evaluated with loan_eligibility@3:
  - output "decision": expected "APPROVE" but was "REVIEW"
  - output "limit": expected 309000000 but the decision produced no such output
  - reason codes: expected [] but was [AMOUNT_ABOVE_LIMIT]
  - matched rules: expected [R4] but was [R5]
```

Thông báo hiển thị đầu ra kỳ vọng và đầu ra thực tế, không bao giờ hiển thị các fact đầu vào. Một tệp không đọc được sẽ trở thành một test thất bại duy nhất, mang tên tệp, liệt kê các lỗi của tệp; các tệp khác vẫn chạy. Một đường dẫn không tồn tại, hoặc không chứa tệp kiểm thử nào, làm test factory thất bại, nên một đường dẫn sai không thể lọt qua mà không ai biết.

Để đọc giá trị theo kiểu đã khai báo, nhờ đó viết được tiền và ngày (xem [Giá trị](#giá-trị)), hãy truyền thêm các bảng: `StoneTests.dynamicTests(engine, path, tables)`. Overload nhận một `RuleCoverage` ở dưới dùng các bảng của nó.

Để đọc tệp kiểm thử mà không dùng JUnit, hãy dùng `StoneTestJson.read(path, tables)` (hoặc `StoneTestJson.read(path)`, không theo kiểu) và gọi `check(engine)` trên từng `StoneTestCase`. Phương thức này ném `AssertionError` liệt kê các điểm không khớp.

## Độ bao phủ luật

Một luật mà không test nào khớp là một luật chưa ai kiểm tra. `RuleCoverage` theo dõi, với từng quyết định và phiên bản, những luật nào đã được một test thành công khớp. Hãy truyền các bảng vào `dynamicTests` để thêm một test cuối cùng tên `rule coverage`:

```java
@TestFactory
Stream<DynamicTest> decisionTables() {
    List<DecisionTable> tables = List.of(
            DecisionTableJson.read(Path.of("rules/loan_eligibility.v2.stone.json")),
            DecisionTableJson.read(Path.of("rules/loan_eligibility.v3.stone.json")));
    RuleStoneEngine engine = RuleStone.builder().decisions(tables).build();
    return StoneTests.dynamicTests(engine, Path.of("rules"), RuleCoverage.of(tables));
}
```

Test `rule coverage` thất bại trừ khi mọi luật của mọi bảng đều được ít nhất một ca kiểm thử thành công khớp. Nếu xoá `examples/loan_eligibility.v2.stone-test.json`, test độ bao phủ cho `examples/` sẽ thất bại với:

```
rule coverage: 12 of 15 rules matched by a passing test
  deposit_rate@1: 4 of 4 rules
  loan_eligibility@2: 0 of 3 rules, never matched: R1, R2, R3
  loan_eligibility@3: 5 of 5 rules
  transfer_fees@1: 3 of 3 rules
```

Chỉ các ca thành công và kỳ vọng việc đánh giá thành công mới được tính. Một luật chỉ được khớp trong một ca thất bại, hoặc trong một ca kỳ vọng `error`, thì không được tính là đã bao phủ. Độ bao phủ được tính riêng cho từng phiên bản: các test của phiên bản 3 không bao phủ phiên bản 2. Test độ bao phủ tự đánh giá mọi ca kiểm thử, nên kết quả không phụ thuộc vào thứ tự JUnit chạy các test.

Ngoài JUnit, hãy tự ghi nhận kết quả và kiểm tra ở cuối:

```java
RuleCoverage coverage = RuleCoverage.of(loanEligibilityV3);
coverage.record(result);            // the DecisionResult of each passing test; here one that matched R4
coverage.uncoveredRules();          // {loan_eligibility@3=[R1, R2, R3, R5]}
coverage.report();                  // a summary in the format shown above
coverage.assertComplete();          // throws AssertionError with the report
```

Độ bao phủ luật cho thấy mọi luật *có thể* được kích hoạt đúng như dự định. Nó không cho thấy ranh giới giữa các luật là đúng; để kiểm tra điều đó, hãy thêm ca kiểm thử ở cả hai phía của mọi ngưỡng (xem bên dưới).

## Kiểm thử luồng quyết định

Tệp kiểm thử cho một [luồng quyết định](flows.md) ghi tên luồng trong `decision`. Các ca kiểm thử được viết như với bảng, và còn có thể kỳ vọng `path`: đường đi gồm các nút mà việc đánh giá đi qua. Ca kiểm thử này, lấy từ [`examples/flows/card_origination.stone-test.json`](../../examples/flows/card_origination.stone-test.json), kiểm tra rằng một khách hàng hạng `D` được chuyển cho người xét duyệt:

```json
{
  "name": "an existing customer at grade D is referred, not declined",
  "facts": {
    "applicant": { "id": "C-1005", "age": 35, "income": "20000000 VND", "existingCustomer": true },
    "bureau": { "dpd30Count12m": 0, "utilisation": "0.95" }
  },
  "expect": {
    "output": { "decision": "REFER", "riskGrade": "D", "limit": "0 VND" },
    "reasonCodes": ["HIGH_UTILISATION"],
    "path": ["policy", "affordability", "segment", "grade_existing", "referred"]
  }
}
```

Hãy truyền các luồng cùng với các bảng, để fact và đầu ra được đọc theo kiểu mà luồng khai báo, và để độ bao phủ tính cả các luồng:

```java
@TestFactory
Stream<DynamicTest> cardOrigination() {
    List<DecisionTable> tables = Stream.of("card_policy", "card_grade_existing", "card_grade_new", "card_limit")
            .map(id -> DecisionTableJson.read(Path.of("rules/" + id + ".stone.json")))
            .toList();
    List<DecisionFlow> flows = List.of(DecisionFlowJson.read(Path.of("rules/card_origination.flow.json")));
    RuleStoneEngine engine = RuleStone.builder().decisions(tables).flows(flows).build();
    return StoneTests.dynamicTests(engine, Path.of("rules"), RuleCoverage.of(tables, flows));
}
```

Khi đó test cuối cùng có tên `rule and node coverage`. Ngoài mọi luật của mọi bảng, kể cả các luật mà nút `table` khớp bên trong luồng, test này đòi hỏi mọi nút của mọi luồng đều được đi qua và mọi **nhánh** đều được một ca kiểm thử thành công đi theo. Một nhánh là một lối ra của nút có nhiều lối ra: mỗi đích khác nhau của một `switch` hoặc của một `next` có điều kiện, viết là `segment -> grade_new`, và mỗi nhóm (bucket) của một `split`, viết là `limit_strategy[challenger]`. Nếu tệp ví dụ không có ca kiểm thử challenger, báo cáo sẽ là:

```
rule coverage: 14 of 15 rules matched by a passing test
  card_grade_existing@1: 4 of 4 rules
  card_grade_new@1: 4 of 4 rules
  card_limit@1: 3 of 4 rules, never matched: A_CHALLENGER
  card_policy@1: 3 of 3 rules
node coverage: 10 of 10 nodes and 9 of 10 branches exercised by a passing test
  card_origination@1: 10 of 10 nodes, 9 of 10 branches, never taken: limit_strategy[challenger]
```

Để kiểm thử một nhóm của `split`, hãy chọn một khoá rơi vào nhóm đó: với cùng một salt, nhóm của một khoá không bao giờ thay đổi. `RuleCoverage.uncoveredNodes()` và `uncoveredBranches()` trả về những gì còn thiếu, như `uncoveredRules()` làm với luật. Khi không dùng JUnit, hãy đọc các tệp bằng `StoneTestJson.read(path, tables, flows)`.

### Luồng có nút mô hình

Một ca kiểm thử cung cấp kết quả của mỗi [nút mô hình](flows.md#nút-mô-hình) mà nó đi tới trong `models`, theo id nút, ở dạng [kết quả do bên gọi cung cấp](flows.md#kết-quả-do-bên-gọi-cung-cấp). Một lỗi được viết là `{ "failure": "TIMEOUT" }` hoặc `{ "failure": "ERROR" }`; một nút không có kết quả sẽ lỗi với `MISSING`. Các loại lỗi khác đến từ chính kết quả, như một PD lớn hơn 1 hoặc một độ tin cậy thấp hơn `minConfidence`. Ca kiểm thử này, từ [`examples/flows/card_origination_scored.stone-test.json`](../../examples/flows/card_origination_scored.stone-test.json), kiểm tra rằng khi mô hình hết thời gian, hồ sơ được chuyển người xét duyệt:

```json
{
  "name": "a behavioural model timeout is referred, never approved on a guess",
  "facts": {
    "applicant": { "id": "C-2009", "age": 35, "income": "20000000 VND", "existingCustomer": true },
    "bureau": { "dpd30Count12m": 0, "utilisation": "0.10" }
  },
  "models": {
    "fraud": { "modelId": "card_application_fraud", "modelVersion": "1.2.0", "outputs": { "score": "0.05" } },
    "pd_behaviour": { "failure": "TIMEOUT" }
  },
  "expect": {
    "output": { "decision": "REFER", "riskGrade": "N/A" },
    "reasonCodes": ["MODEL_UNAVAILABLE"],
    "path": ["policy", "fraud", "segment", "pd_behaviour", "referred"]
  }
}
```

Hãy tạo engine dùng để kiểm thử với `suppliedModelsOnly()`. Khi đó các nút ở chế độ `invoke` cũng lấy kết quả từ ca kiểm thử, nên kiểm thử là tất định và không cần máy chủ mô hình:

```java
RuleStoneEngine engine = RuleStone.builder().decisions(tables).flows(flows).suppliedModelsOnly().build();
```

Độ bao phủ tính hai loại nhánh cho một nút mô hình, và nút mô hình luôn có cả hai: mỗi đích của `next`, và đường `onFailure`, được viết `pd_behaviour -> referred (onFailure)`. Nhánh `onFailure` chỉ được tính khi mô hình lỗi, kể cả khi `next` dẫn tới cùng một nút, nên mỗi nút mô hình cần một ca kiểm thử trong đó mô hình của nó lỗi.

## Assertion dạng fluent trong Java

`DecisionCheck` kiểm tra một lần đánh giá bằng API dạng fluent. Nó ném `AssertionError`, nên dùng được với JUnit, TestNG hay bất kỳ framework nào khác:

```java
DecisionCheck.of(engine)
        .decision("loan_eligibility").asOf(LocalDate.of(2026, 12, 1))
        .given("applicant", applicant).given("amount", new BigDecimal("300000000"))
        .expectOutput("decision", "APPROVE")
        .expectOutput("limit", new BigDecimal("309000000"))   // compared by value
        .expectReasonCodes()                                   // exactly none
        .expectMatchedRules("R4");
```

Các phương thức thiết lập yêu cầu `decision`, `version`, `asOf`, `given`, `givenModelResult` và `givenModelFailure` chuẩn bị việc đánh giá; hai phương thức cuối cung cấp kết quả hoặc lỗi của một nút mô hình, như `Facts.withModelResult` và `withModelFailure`. Kỳ vọng đầu tiên sẽ đánh giá quyết định; sau đó không thể thay đổi yêu cầu nữa.

| Phương thức | Kiểm tra |
|---|---|
| `expectOutput(name, value)` | Đầu ra `name` bằng `value`. Số được so sánh theo giá trị. `Double` và `Float` bị từ chối: hãy viết `new BigDecimal("0.048")`. |
| `expectNoOutput(name)` | Quyết định không tạo ra đầu ra `name`. |
| `expectExactOutput(Map)` | Đúng các đầu ra này, với đúng các giá trị này. |
| `expectReasonCodes(codes…)` | Đúng các mã lý do này, theo thứ tự. Không có tham số: không có mã nào. |
| `expectMatchedRules(ids…)` | Đúng các luật này khớp, theo thứ tự. Không có tham số: không có luật nào. |
| `expectPath(nodes…)` | Với một luồng, đúng các nút này được đi qua, theo thứ tự. |
| `expectRuleVersion(version)` | `"3"`, hoặc giá trị đầy đủ `"3@sha256:…"`. |
| `expectFailure(type)` | Việc đánh giá ném ra kiểu ngoại lệ này, ví dụ `DecisionEvaluationException.class`. |
| `expectFailureMessage(text)` | Việc đánh giá ném ra một `RuleStoneException` có thông báo chứa `text`. |
| `result()` | Trả về `DecisionResult` để thực hiện mọi kiểm tra khác. |

Một kỳ vọng thất bại nêu tên quyết định, phiên bản được dùng và yêu cầu:

```
loan_eligibility@3 (asOf 2026-12-01): output "limit": expected 300000000 but was 309000000
```

Để kỳ vọng việc đánh giá thất bại:

```java
DecisionCheck.of(engine)
        .decision("loan_eligibility").asOf(LocalDate.of(2026, 12, 1))
        .given("applicant", zeroIncomeApplicant).given("amount", new BigDecimal("1000"))
        .expectFailure(DecisionEvaluationException.class)
        .expectFailureMessage("division by zero");
```

Hãy ưu tiên tệp `.stone-test.json` cho các ca kiểm thử mà chuyên viên phân tích nghiệp vụ cần đọc hoặc viết. Dùng `DecisionCheck` khi test cần đến Java, ví dụ để tạo fact từ các đối tượng nghiệp vụ hoặc để đánh giá nhiều quyết định nối tiếp nhau.

## Thực hành khuyến nghị cho ngân hàng

- **Bao phủ mọi luật.** Chạy kiểm tra độ bao phủ trong mọi bản build. Một thay đổi bảng có thêm luật mà không có ca kiểm thử sẽ làm bản build thất bại.
- **Kiểm thử cả hai phía của mọi ngưỡng.** Với `applicant.monthlyDebt / applicant.monthlyIncome > decimal('0.5')`, hãy kiểm thử tỷ lệ đúng bằng 0,5 và một tỷ lệ vừa vượt quá, như `examples/loan_eligibility.v3.stone-test.json` đã làm. Phần lớn lỗi trong luật là lệch ranh giới (`>` thay vì `>=`), thứ mà riêng độ bao phủ không phát hiện được.
- **Viết kết quả kỳ vọng từ chính sách, không phải từ engine.** Người viết ca kiểm thử nên suy ra kết quả kỳ vọng từ chính sách tín dụng hoặc bản mô tả sản phẩm, chứ không chép lại những gì engine trả về. Nếu không, kiểm thử chỉ xác nhận hành vi hiện tại, kể cả những sai sót của nó.
- **Rà soát ca kiểm thử cùng với thay đổi luật.** Hãy giữ mỗi bảng và tệp `.stone-test.json` của nó trong cùng một repository và thay đổi chúng trong cùng một pull request. Người rà soát, ví dụ chuyên viên thứ hai theo nguyên tắc bốn mắt, phê duyệt cùng lúc thay đổi luật và các ca kiểm thử chứng minh cho thay đổi đó. Một kỳ vọng bị thay đổi trong tệp kiểm thử là một tuyên bố công khai rằng chính sách đã thay đổi.
- **Mỗi phiên bản bảng một tệp kiểm thử.** Khi một phiên bản mới có hiệu lực, hãy thêm một tệp mới cho phiên bản đó và giữ lại tệp cũ: phiên bản cũ vẫn được dùng để chạy lại các quyết định trong quá khứ. Đặt `asOf` trong mọi ca kiểm thử, như trong các ví dụ, để mỗi tệp kiểm thử đúng phiên bản mà nó được viết cho, bất kể ngày build.
- **Kiểm thử những lỗi bạn đã biết.** Nếu một bảng không xử lý được một đầu vào nào đó (như thu nhập bằng không trong ví dụ), hãy thêm một ca `error`. Ca này ghi lại hạn chế đó và sẽ thất bại nếu hạn chế thay đổi.
- **Dùng dữ liệu giả định.** Tệp kiểm thử được chia sẻ và lưu trong hệ thống quản lý phiên bản. Không bao giờ chép dữ liệu khách hàng thật vào đó.
- **Chạy kiểm thử trong CI.** Luật chỉ nên lên production thông qua một bản build mà mọi ca kiểm thử đều thành công và độ bao phủ là đầy đủ. Các tệp kiểm thử, log build và `ruleVersion` của từng bảng cùng nhau cho kiểm toán viên thấy những luật nào đã được kiểm thử, với những ca nào, trước khi phát hành.
