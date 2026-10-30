---
source: flows.md
sourceHash: a17f39ecbc9948ddc7e26250c54523f093ac22eb18fbe0db2391537aa0182c5c
---

# Luồng quyết định

Một luồng quyết định (decision flow) nối các bảng quyết định và một số logic đơn giản thành một quyết định: luật loại trừ (knock-out) của chính sách chạy trước, sau đó là phân khúc, xếp hạng và ma trận hạn mức, kèm một phép chia champion/challenger (so sánh phiên bản đang chạy với phiên bản thử thách) ở nơi bạn muốn thử một chiến lược mới. Trang này là tài liệu tham khảo cho định dạng `.flow.json` và cho cách luồng được kiểm tra, đánh giá và ghi lại.

Luồng là bước 2 và 3 của [Ra quyết định AI lai](hybrid-ai.md#9-kế-hoạch-triển-khai). Luồng còn có thể dùng mô hình học máy: một [nút mô hình](#nút-mô-hình) hoặc gọi mô hình, hoặc nhận kết quả mà bên gọi truyền vào, rồi kiểm tra kết quả theo một hợp đồng có kiểu. Bộ máy luật Java chạy được luồng; bộ máy luật trên trình duyệt của [SDK trình soạn thảo](editor-sdk.md) thì chưa.

- [Một luồng đầu tiên](#một-luồng-đầu-tiên)
- [Chạy một luồng](#chạy-một-luồng)
- [Định dạng tệp](#định-dạng-tệp)
- [Các nút](#các-nút)
- [Giá trị giữa các nút](#giá-trị-giữa-các-nút)
- [Nút mô hình](#nút-mô-hình)
- [Đánh giá](#đánh-giá)
- [Bản ghi quyết định](#bản-ghi-quyết-định)
- [Phiên bản và mã băm nội dung](#phiên-bản-và-mã-băm-nội-dung)
- [Kiểm tra hợp lệ](#kiểm-tra-hợp-lệ)
- [Giới hạn](#giới-hạn)

## Một luồng đầu tiên

Luồng dưới đây, [`examples/flows/card_origination.flow.json`](../../examples/flows/card_origination.flow.json), quyết định về một hồ sơ mở thẻ tín dụng. Luồng dùng bốn bảng quyết định trong cùng thư mục: `card_policy`, `card_grade_existing`, `card_grade_new` và `card_limit`.

```json
{
  "id": "card_origination",
  "version": "1",
  "description": "Credit card origination: knock-out policy, segmentation, risk grade, and a champion/challenger limit strategy",
  "effectiveFrom": "2026-11-01",
  "inputs": {
    "applicant": { "id": "string", "age": "int", "income": "money", "existingCustomer": "bool" },
    "bureau": { "dpd30Count12m": "int", "utilisation": "decimal" }
  },
  "outputs": { "decision": "string", "limit": "money", "riskGrade": "string" },
  "start": "policy",
  "nodes": {
    "policy": {
      "type": "table", "decision": "card_policy",
      "next": [ { "when": "policy.decision == 'REJECT'", "to": "rejected" }, { "to": "affordability" } ]
    },
    "affordability": {
      "type": "derive",
      "outputs": { "annualIncome": "money" },
      "set": { "annualIncome": "applicant.income * 12" },
      "next": "segment"
    },
    "segment": {
      "type": "switch",
      "cases": [ { "when": "applicant.existingCustomer", "to": "grade_existing" }, { "to": "grade_new" } ]
    },
    "grade_existing": {
      "type": "table", "decision": "card_grade_existing", "as": "grade",
      "next": [ { "when": "grade.riskGrade == 'D'", "to": "referred" }, { "to": "limit_strategy" } ]
    },
    "grade_new": {
      "type": "table", "decision": "card_grade_new", "as": "grade",
      "next": [ { "when": "grade.riskGrade == 'D'", "to": "referred" }, { "to": "limit_strategy" } ]
    },
    "limit_strategy": {
      "type": "split", "key": "applicant.id", "salt": "card-limit-2026Q4",
      "buckets": [
        { "name": "champion", "percent": 90, "to": "limit" },
        { "name": "challenger", "percent": 10, "to": "limit" }
      ]
    },
    "limit": {
      "type": "table", "decision": "card_limit",
      "facts": {
        "riskGrade": "grade.riskGrade",
        "annualIncome": "affordability.annualIncome",
        "strategy": "limit_strategy.taken"
      },
      "next": "approved"
    },
    "approved": { "type": "end", "set": {
      "decision": "'APPROVE'", "riskGrade": "grade.riskGrade", "limit": "limit.limit" } },
    "referred": { "type": "end", "set": {
      "decision": "'REFER'", "riskGrade": "grade.riskGrade", "limit": "money(0, applicant.income.currency())" } },
    "rejected": { "type": "end", "set": {
      "decision": "'REJECT'", "riskGrade": "'N/A'", "limit": "money(0, applicant.income.currency())" } }
  }
}
```

Trong luồng này:

- Bảng chính sách chạy trước. Một lần từ chối ở đó kết thúc luồng trước khi bất kỳ nút nào khác được đánh giá.
- `affordability` tính thu nhập năm một lần, để bảng hạn mức dùng về sau.
- Khách hàng hiện hữu được xếp hạng bằng một bảng, khách hàng mới bằng một bảng khác. Cả hai công bố kết quả dưới tên chung `grade`, nên các nút phía sau đọc `grade.riskGrade` dù bảng nào đã chạy.
- Hạng `D` được chuyển cho người xét duyệt, không bao giờ bị từ chối tự động.
- 10% người nộp hồ sơ, được chọn theo id người nộp hồ sơ, nhận chiến lược hạn mức challenger. Bảng hạn mức nhận nhóm (bucket) đã chọn làm đầu vào `strategy`.

## Chạy một luồng

Hãy đăng ký luồng cùng với mọi bảng mà luồng dùng, và đánh giá luồng theo id như một bảng:

```java
List<DecisionTable> tables = Stream.of("card_policy", "card_grade_existing", "card_grade_new", "card_limit")
        .map(id -> DecisionTableJson.read(Path.of("rules/" + id + ".stone.json")))
        .toList();
RuleStoneEngine engine = RuleStone.builder()
        .clock(Clock.system(ZoneId.of("Asia/Ho_Chi_Minh")))
        .decisions(tables)
        .flow(DecisionFlowJson.read(Path.of("rules/card_origination.flow.json")))
        .auditSink(record -> auditLog.add(DecisionRecordJson.write(record)))
        .build();

DecisionResult result = engine.evaluate(
        DecisionRef.of("card_origination").asOf(LocalDate.of(2026, 12, 1)).correlationId("CARD-2026-000777"),
        Facts.of("applicant", Map.of("id", "C-1001", "age", 35, "income", Money.of("20000000", "VND"),
                                     "existingCustomer", true),
                 "bureau", Map.of("dpd30Count12m", 0, "utilisation", new BigDecimal("0.10"))));

result.output();         // {decision=APPROVE, limit=144000000 VND, riskGrade=A}
result.matchedRules();   // [PASS, A, A_CHALLENGER]: the matched rules of every table node, in order
```

`build()` kiểm tra luồng với các bảng đã đăng ký và ném `InvalidDecisionException` liệt kê mọi lỗi (xem [Kiểm tra hợp lệ](#kiểm-tra-hợp-lệ)). Luồng dùng chung không gian `id` với bảng, nên một luồng và một bảng không thể có cùng `id`. Mọi nội dung khác trong [Java API](java-api.md) áp dụng cho luồng như cho bảng: `DecisionRef`, `Facts`, `FactsJson`, thời gian chờ tối đa, audit sink, phiên bản canary và phiên bản bóng.

## Định dạng tệp

Một luồng là một đối tượng JSON. Tên tệp kết thúc bằng `.flow.json`. Việc đọc tệp chặt chẽ như với bảng: khoá lạ hoặc khoá trùng đều là lỗi.

| Trường | Bắt buộc | Mô tả |
|---|---|---|
| `id`, `version`, `description`, `effectiveFrom`, `effectiveTo` | như với [bảng quyết định](decision-tables.md#định-dạng-tệp) | Cùng quy tắc và ý nghĩa. Các phiên bản của một luồng được chọn giống như các phiên bản của một bảng; xem [Phiên bản và ngày hiệu lực](decision-tables.md#phiên-bản-và-ngày-hiệu-lực). |
| `inputs` | có | Đối tượng ánh xạ tên mỗi đầu vào tới [kiểu](decision-tables.md#kiểu-dữ-liệu) của nó, như với bảng. Giống bảng, luồng cũng nhận `businessDate` một cách ngầm định. |
| `outputs` | có | Đối tượng ánh xạ tên mỗi đầu ra tới kiểu của nó. Mọi nút `end` đặt tất cả các đầu ra này. Như với bảng, đầu ra không thể là struct. |
| `start` | có | Id của nút đầu tiên. |
| `nodes` | có | Đối tượng ánh xạ id của mỗi nút tới nút đó. Id của nút là tên dùng để đọc giá trị của nút trong biểu thức, nên phải là một định danh (chữ cái, chữ số và `_`, không bắt đầu bằng chữ số) và khác mọi tên đầu vào. |

## Các nút

Mỗi nút có một `type` và một `description` tuỳ chọn.

| `type` | Làm gì | Công bố | Đi tới |
|---|---|---|---|
| `table` | Đánh giá một bảng quyết định | Các đầu ra của bảng | `next` |
| `switch` | Chọn một nhánh | `taken`: id của nút được chọn | Case đầu tiên có `when` đúng |
| `derive` | Tính các giá trị có tên một lần | Các giá trị đó | `next` |
| `model` | Lấy kết quả của một mô hình và kiểm tra nó | Các đầu ra của mô hình | `next`, hoặc `onFailure` khi mô hình lỗi |
| `split` | Gán một nhóm theo tỷ lệ phần trăm | `taken`: tên của nhóm được chọn | Nút của nhóm đó |
| `end` | Đặt các đầu ra của luồng | Không có gì | Kết thúc luồng |

Tên kiểu `flow` được dành riêng cho việc gọi luồng khác. Kiểu này chưa được hỗ trợ, và tệp nào dùng nó sẽ bị từ chối.

### Nhánh: `next` và `cases`

`next` là một id nút, hoặc một mảng các lựa chọn. `cases` của một `switch` luôn là một mảng:

```json
"next": [ { "when": "policy.decision == 'REJECT'", "to": "rejected" }, { "to": "affordability" } ]
```

Các lựa chọn được thử theo thứ tự và lựa chọn đầu tiên có `when` đúng được chọn. Chỉ lựa chọn cuối cùng không có `when`, và nó bắt buộc không có, để luôn có một lựa chọn được áp dụng. `when` là một điều kiện CEL, viết như trong [luật của bảng](expressions.md). Trong `next` của một nút `table` hoặc `derive`, điều kiện có thể đọc các giá trị của chính nút đó.

### `table`

| Trường | Bắt buộc | Mô tả |
|---|---|---|
| `decision` | có | Id của một bảng quyết định đã đăng ký. |
| `version` | không | Luôn dùng phiên bản này của bảng. Nếu không có, nút dùng phiên bản có hiệu lực vào ngày nghiệp vụ. |
| `facts` | không | Đối tượng ánh xạ một đầu vào của bảng tới một biểu thức CEL trên các giá trị của luồng. Đầu vào không được liệt kê nhận giá trị cùng tên của luồng. |
| `as` | không | Tên để công bố các đầu ra của bảng thay cho id của nút. |
| `next` | có | Đi đâu tiếp theo. |

Các đầu ra của bảng được công bố thành một giá trị, mỗi đầu ra là một trường: `policy.decision`. Với `COLLECT`, mỗi trường là một danh sách, như trong kết quả của chính bảng đó.

### `switch`

| Trường | Bắt buộc | Mô tả |
|---|---|---|
| `cases` | có | Mảng các lựa chọn, như mô tả ở trên. |

### `derive`

| Trường | Bắt buộc | Mô tả |
|---|---|---|
| `outputs` | có | Đối tượng ánh xạ tên mỗi giá trị tới kiểu của nó, như đầu ra của bảng. |
| `set` | có | Đối tượng ánh xạ tên mỗi giá trị tới một biểu thức CEL có kiểu đó. Mọi giá trị trong `outputs` đều được đặt, và không đặt gì khác. |
| `as` | không | Tên để công bố các giá trị thay cho id của nút. |
| `next` | có | Đi đâu tiếp theo. |

Các biểu thức của cùng một nút `derive` không đọc được giá trị của nhau. Để tính một giá trị từ một giá trị dẫn xuất khác, hãy dùng thêm một nút `derive`.

### `model`

Gọi một mô hình học máy, hoặc nhận kết quả mà bên gọi truyền vào, rồi kiểm tra kết quả. Xem [Nút mô hình](#nút-mô-hình).

### `split`

| Trường | Bắt buộc | Mô tả |
|---|---|---|
| `key` | có | Biểu thức CEL cho ra một `string`, như `applicant.id`. Với một `int`, hãy dùng `string(applicant.number)`. |
| `salt` | có | Văn bản bất kỳ. Hãy dùng salt mới cho một thử nghiệm mới, để các nhóm của nó độc lập với các thử nghiệm trước. |
| `buckets` | có | Mảng các `{ "name", "percent", "to" }`. Tên không trùng nhau; tỷ lệ phần trăm là số nguyên từ 1 tới 100 và có tổng bằng 100. |

Nhóm chỉ phụ thuộc vào salt và khoá: 64 bit đầu của SHA-256 của `"<salt>\n<key>"`, đọc như một phân số rồi nhân với 100, rơi vào các nhóm theo thứ tự. Vì vậy cùng một khách hàng luôn nhận cùng một nhóm với cùng một salt, trên mọi máy chủ và vào mọi ngày. Đổi salt hoặc tỷ lệ phần trăm sẽ đổi mã băm nội dung của luồng, như mọi chỉnh sửa khác.

### `end`

| Trường | Bắt buộc | Mô tả |
|---|---|---|
| `set` | có | Đối tượng ánh xạ mọi đầu ra của luồng tới một biểu thức CEL có kiểu tương ứng. |

## Giá trị giữa các nút

Biểu thức trong một nút có thể đọc:

- các đầu vào của luồng và `businessDate`, theo tên;
- các giá trị của những nút đứng trước nó, dưới id của nút hoặc tên `as` của nút: `policy.decision`, `affordability.annualIncome`, `segment.taken`.

**Chỉ những giá trị được đặt trên mọi đường đi.** Một nút chỉ đọc được một giá trị nếu mọi đường đi từ `start` tới nút đó đều qua một nút đặt giá trị ấy. Trong ví dụ, `referred` đọc được `grade.riskGrade` vì cả hai đường đi tới nó đều qua một nút công bố `grade`; nó sẽ không đọc được `affordability.annualIncome` nếu có đường đi nào tới nó bỏ qua `affordability`. Điều này được kiểm tra khi engine được tạo, nên luồng không bao giờ đọc một giá trị có thể không tồn tại.

**Tên dùng chung.** Các nút trên các nhánh thay thế nhau có thể công bố dưới cùng một tên `as` nếu chúng công bố cùng các giá trị với cùng kiểu, như `grade_existing` và `grade_new`. Hai nút trên cùng một đường đi không thể dùng chung một tên.

**Fact cho một bảng.** Một nút `table` truyền cho mỗi đầu vào của bảng giá trị cùng tên của luồng, giá trị này phải có cùng kiểu, hoặc, với một struct, có ít nhất các trường mà bảng khai báo. Hãy liệt kê đầu vào trong `facts` để truyền một giá trị khác. Đầu vào kiểu struct chỉ có thể được truyền một giá trị theo tên, ví dụ `"customer": "applicant"`. Trong ví dụ, `limit` truyền `grade.riskGrade` làm đầu vào `riskGrade` của bảng và nhóm đã chọn làm đầu vào `strategy`.

**Đầu ra không luật nào đặt.** Khi không luật nào của bảng khớp, hoặc luật đã khớp không đặt một đầu ra, đầu ra đó vắng mặt. Đọc nó sẽ làm việc đánh giá thất bại; hãy kiểm tra bằng `has()`, như trong luật của bảng: `has(policy.decision) && policy.decision == 'REJECT'`.

## Nút mô hình

Một nút `model` đưa một mô hình học máy (ML) vào luồng: một điểm gian lận, một xác suất vỡ nợ (PD), một thẻ điểm. Nút kiểm tra kết quả của mô hình theo một hợp đồng có kiểu trước khi bất kỳ nút nào phía sau đọc nó, và luôn có một đường được khai báo sẵn cho trường hợp mô hình lỗi. Các luật xung quanh vẫn là bên quyết định: mô hình chỉ tạo ra các giá trị có kiểu để các nút phía sau đọc.

Ví dụ [`examples/flows/card_origination_scored.flow.json`](../../examples/flows/card_origination_scored.flow.json) là luồng phát hành thẻ có dùng mô hình. Luồng dùng các bảng `card_policy`, `card_pd_grade` và `card_limit`, và có ba nút mô hình:

```json
    "fraud": {
      "type": "model", "mode": "supplied",
      "model": { "id": "card_application_fraud", "version": "1.2.0" },
      "outputs": { "score": "decimal" },
      "valid": "score >= decimal(0) && score <= decimal(1)",
      "reasonCodes": { "deviceAgeDays": "NEW_DEVICE", "addressMismatch": "ADDRESS_MISMATCH" },
      "onFailure": { "to": "referred", "reasonCode": "FRAUD_SCORE_UNAVAILABLE" },
      "next": [ { "when": "fraud.score >= decimal('0.80')", "to": "referred" }, { "to": "segment" } ]
    },
    "segment": {
      "type": "switch",
      "cases": [ { "when": "applicant.existingCustomer", "to": "pd_behaviour" }, { "to": "pd_application" } ]
    },
    "pd_behaviour": {
      "type": "model", "mode": "invoke", "as": "score",
      "model": { "id": "retail_pd_behaviour", "version": "2.4.0" },
      "features": { "utilisation": "bureau.utilisation", "dpd30Count12m": "bureau.dpd30Count12m" },
      "outputs": { "pd": "decimal" },
      "valid": "pd >= decimal(0) && pd <= decimal(1)",
      "timeoutMs": 80,
      "reasonCodes": { "utilisation": "HIGH_UTILISATION", "dpd30Count12m": "RECENT_DELINQUENCY" },
      "onFailure": { "to": "referred", "reasonCode": "MODEL_UNAVAILABLE" },
      "next": "grade"
    },
    "pd_application": {
      "type": "model", "mode": "invoke", "as": "score",
      "model": { "id": "retail_pd_application", "version": "3.1.0" },
      "features": { "age": "applicant.age", "utilisation": "bureau.utilisation" },
      "outputs": { "pd": "decimal" },
      "valid": "pd >= decimal(0) && pd <= decimal(1)",
      "minConfidence": "0.70",
      "timeoutMs": 80,
      "reasonCodes": { "utilisation": "HIGH_UTILISATION", "age": "THIN_CREDIT_FILE" },
      "maxReasonCodes": 1,
      "onFailure": { "to": "referred", "reasonCode": "MODEL_UNAVAILABLE" },
      "next": "grade"
    },
```

Trong luồng này:

- Bảng chính sách vẫn chạy trước, nên một luật loại trừ không bao giờ phụ thuộc vào mô hình.
- `fraud` ở chế độ `supplied`: nền tảng chống gian lận của bên gọi chấm điểm hồ sơ và truyền kết quả cùng với các fact. Điểm từ 0,80 trở lên được chuyển người xét duyệt.
- `pd_behaviour` và `pd_application` ở chế độ `invoke`: engine tự gọi mô hình, chỉ với các đặc trưng đã khai báo. Cả hai công bố đầu ra dưới tên `score`, nên bảng `grade` đọc `score.pd` bất kể mô hình nào đã chạy.
- Hễ một mô hình lỗi, hồ sơ được chuyển người xét duyệt, kèm một mã lý do cho biết vì sao. Hồ sơ không bao giờ được phê duyệt hay từ chối dựa trên một điểm số đoán chừng.

### Các trường

| Trường | Bắt buộc | Mô tả |
|---|---|---|
| `mode` | có | `invoke`: engine gọi mô hình qua `ModelInvoker` đã đăng ký cho id của nó. `supplied`: bên gọi truyền kết quả của mô hình cùng với các fact. |
| `model` | có | `{ "id", "version" }`: mô hình và đúng một phiên bản mà luồng dùng, ví dụ `"2.4.0"`. Một khoảng phiên bản, một ký tự đại diện như `2.x`, và `latest` đều bị từ chối. Cả hai đều thuộc mã băm nội dung của luồng, nên một phiên bản mô hình mới là một phiên bản luồng mới. |
| `features` | ở chế độ `invoke` | Đối tượng ánh xạ mỗi tên đặc trưng tới một biểu thức CEL trên các giá trị của luồng. Mô hình nhận các giá trị này và không gì khác. Mỗi biểu thức phải cho ra một `bool`, `int`, `string`, `decimal`, `money` hoặc `date`, hoặc một danh sách của chúng. Không được dùng ở chế độ `supplied`. |
| `outputs` | có | Đối tượng ánh xạ mỗi tên đầu ra tới kiểu của nó, như đầu ra của bảng. Kết quả của mô hình phải có đúng các đầu ra này. Tên đầu ra là định danh, vì `valid` đọc chúng theo tên. |
| `valid` | không | Một điều kiện CEL trên các đầu ra, theo tên, ví dụ `pd >= decimal(0) && pd <= decimal(1)`. Nếu điều kiện sai, kết quả không hợp lệ. |
| `minConfidence` | không | Một số thập phân từ 0 tới 1, ví dụ `"0.70"`. Khi đó mô hình phải báo độ tin cậy, và độ tin cậy thấp hơn là lỗi. |
| `timeoutMs` | ở chế độ `invoke` | Thời gian tối đa mô hình được dùng, tính bằng mili giây. Không được dùng ở chế độ `supplied`. |
| `reasonCodes` | không | Đối tượng ánh xạ một tên đặc trưng tới mã lý do được đưa ra khi đặc trưng đó nằm trong các lý do chính của mô hình; xem [Mã lý do từ attribution](#mã-lý-do-từ-attribution). |
| `maxReasonCodes` | không | Số mã lý do tối đa mà nút đưa ra, từ 1 tới 10. Mặc định là 3. |
| `onFailure` | có | `{ "to", "reasonCode" }`: nút đi tới khi mô hình lỗi, và mã lý do được đưa ra. |
| `as` | không | Một tên để công bố các đầu ra thay cho id của nút, như với nút `table`. |
| `next` | có | Nơi đi tới khi kết quả đáp ứng hợp đồng. Điều kiện có thể đọc các đầu ra, ví dụ `fraud.score`. |

Khi thành công, nút công bố các đầu ra dưới id hoặc tên `as` của nó, như một nút `table`: `fraud.score`, `score.pd`. Khi lỗi, nút không công bố gì. Vì vậy một nút sau `onFailure` không đọc được các đầu ra của mô hình, và engine từ chối được tạo nếu có nút cố đọc; đây chính là quy tắc một nút chỉ đọc được những giá trị mà [mọi đường đi tới nó đều đặt](#giá-trị-giữa-các-nút).

### Kết quả do bên gọi cung cấp

Ở chế độ `supplied`, bên gọi truyền kết quả của mô hình cùng với các fact, dưới id của nút:

```java
Facts facts = Facts.of("applicant", applicant, "bureau", bureau)
        .withModelResult("fraud", ModelResult.of("card_application_fraud", "1.2.0",
                Map.of("score", new BigDecimal("0.05"))));
```

Nếu chính lần gọi mô hình của bên gọi đã lỗi, bên gọi báo điều đó thay cho kết quả: `withModelFailure("fraud", ModelStatus.TIMEOUT)` (hoặc `ERROR`). Bên gọi dùng JSON viết cùng nội dung này thành một đối tượng `models` bên cạnh các fact, được đọc bằng `FactsJson.readModels`, hoặc bằng `FactsJson.readFacts(facts, inputs, models)` cùng lúc với các fact:

```json
{
  "fraud": {
    "modelId": "card_application_fraud",
    "modelVersion": "1.2.0",
    "outputs": { "score": "0.05" },
    "attributionMethod": "SHAP",
    "attributions": [
      { "feature": "deviceAgeDays", "contribution": "0.30" },
      { "feature": "addressMismatch", "contribution": "0.10" }
    ]
  },
  "pd_behaviour": { "failure": "TIMEOUT" }
}
```

`confidence`, `attributionMethod` và `attributions` là tuỳ chọn. Các đầu ra được đọc theo kiểu mà nút khai báo, qua [các dạng JSON](java-api.md#fact-và-kết-quả-dạng-json-factsjson): một số thập phân có thể là `"0.05"` hoặc `0.05`, và tiền là `"1500000 VND"`. Trong Java, `BigDecimal`, `Long`, `Money` và `LocalDate` đều dùng được, cũng như dạng văn bản JSON của chúng; `Double` và `Float` vi phạm hợp đồng, vì chúng không giữ được số thập phân chính xác.

Engine kiểm tra một kết quả do bên gọi cung cấp đúng như thể chính nó đã gọi mô hình. Một kết quả được cung cấp cho một nút không phải nút `model`, hoặc cho một nút ở chế độ `invoke`, sẽ làm việc đánh giá thất bại, nên một id nút viết sai không thể lọt qua. Một nút mô hình ở chế độ `supplied` không có kết quả sẽ lỗi với `MISSING` và đi theo đường `onFailure` của nó.

### Mô hình được engine gọi

Ở chế độ `invoke`, engine gọi `ModelInvoker` đã đăng ký cho id của mô hình:

```java
ModelInvoker behaviourModel = call -> {
    // call.features(): {utilisation=0.40, dpd30Count12m=0}; call.timeout(): at most 80 ms
    ScoringResponse response = scoringClient.score(call.modelId(), call.modelVersion(), call.features(), call.timeout());
    return ModelResult.of(call.modelId(), call.modelVersion(), Map.of("pd", response.pd()))
            .withAttributions("SHAP", response.contributions());
};

RuleStoneEngine engine = RuleStone.builder()
        .decisions(tables)
        .flow(DecisionFlowJson.read(Path.of("rules/card_origination_scored.flow.json")))
        .modelInvoker("retail_pd_behaviour", behaviourModel)
        .modelInvoker("retail_pd_application", applicationModel)
        .build();
```

- **Lần gọi.** `ModelCall` chứa id mô hình và phiên bản đã ghim, các đặc trưng đã khai báo dưới dạng giá trị có kiểu (số thập phân chính xác, không bao giờ là `double`), thời gian được phép và mã tương quan (correlation id) của yêu cầu. `toString()` của nó nêu tên các đặc trưng nhưng không bao giờ hiển thị giá trị.
- **Thời gian được phép** là `timeoutMs` của nút, hoặc ít hơn nếu [thời gian chờ tối đa](java-api.md#thời-gian-chờ-tối-đa) của chính việc đánh giá chỉ còn ít hơn. Invoker chạy trên luồng thực thi (thread) đang đánh giá và phải trả về kịp thời: engine không ngắt nó, và coi một kết quả đến muộn là `TIMEOUT`. Nếu thời gian chờ tối đa của chính việc đánh giá hết trong lúc gọi, toàn bộ việc đánh giá hết thời gian.
- **Không thử lại.** Một lần gọi lỗi là lỗi của mô hình, được xử lý bằng `onFailure`. Việc thử lại thuộc về invoker, trong phạm vi thời gian của nó.
- **Ngoại lệ.** Một `ModelException` hoặc bất kỳ ngoại lệ nào khác từ invoker là lỗi loại `ERROR`. Bản ghi chỉ nêu kiểu của ngoại lệ, không bao giờ nêu thông báo của nó, vì thông báo có thể chứa giá trị đặc trưng.
- **Đăng ký.** `build()` thất bại nếu một nút mô hình ở chế độ `invoke` không có invoker cho id mô hình của nó. Một invoker có thể phục vụ mọi phiên bản của một mô hình, vì mỗi lần gọi đều nêu phiên bản.

Cho kiểm thử và replay, `RuleStone.builder().suppliedModelsOnly()` chạy mọi nút mô hình ở chế độ `supplied`: engine không gọi mô hình nào và không cần invoker, và các nút `invoke` cũng lấy kết quả từ các fact. Các đặc trưng của chúng vẫn được tính, để lỗi biểu thức lộ ra ngay trong kiểm thử. Đừng dùng chế độ này trong production.

### Lỗi của mô hình

Mọi lỗi đều đi theo đường `onFailure` của nút và thêm mã lý do của nó. Lỗi của mô hình không bao giờ làm việc đánh giá thất bại; bản ghi cho biết loại lỗi trong `status` của bước.

| `status` | Khi nào |
|---|---|
| `TIMEOUT` | Invoker không trả lời trong thời gian được phép, hoặc bên gọi cung cấp `{ "failure": "TIMEOUT" }`. |
| `ERROR` | Invoker ném ngoại lệ hoặc không trả về gì, hoặc bên gọi cung cấp `{ "failure": "ERROR" }`. |
| `CONTRACT_VIOLATION` | Một id hoặc phiên bản mô hình khác đã trả lời; một đầu ra bị thiếu, chưa khai báo hoặc sai kiểu; hoặc nút đặt `minConfidence` mà không có độ tin cậy nào được báo. |
| `INVALID_OUTPUT` | Điều kiện `valid` sai, hoặc không đánh giá được. |
| `LOW_CONFIDENCE` | Độ tin cậy thấp hơn `minConfidence`. |
| `MISSING` | Nút ở chế độ `supplied` và không có kết quả nào được cung cấp. |

Các bước kiểm tra chạy theo thứ tự này, và bước đầu tiên thất bại quyết định trạng thái. `detail` của bước cho biết điều gì đã thất bại, như `output 'pd' is missing` hoặc `the invoker threw vn.io.dxa.rulestone.api.ModelException`, và không bao giờ hiển thị giá trị.

### Mã lý do từ attribution

Một mô hình có thể trả về các attribution (mức đóng góp của đặc trưng): mỗi đặc trưng đã làm kết quả thay đổi bao nhiêu, được tính ví dụ bằng SHAP hoặc từ điểm của thẻ điểm. Mức đóng góp dương làm tăng rủi ro; với một điểm số mà cao hơn nghĩa là ít rủi ro hơn, invoker đổi dấu các mức đóng góp. Nút chuyển chúng thành mã lý do:

1. Các attribution được xếp hạng theo độ lớn mức đóng góp, theo cả hai chiều.
2. Đi xuống theo thứ hạng, mỗi đặc trưng làm tăng rủi ro và có mã trong `reasonCodes` cho ra mã đó, cho tới khi nút đã đưa ra `maxReasonCodes` mã (mặc định ba). Mỗi mã chỉ được đưa ra một lần, kể cả khi hai đặc trưng cùng ánh xạ tới nó. Đặc trưng không có mã được bỏ qua.

Trong ca kiểm thử của ví dụ mà `pd_application` trả về `age` với 0,0100 và `utilisation` với 0,0200, `utilisation` xếp thứ nhất, và với `maxReasonCodes` bằng 1 thì nút chỉ đưa ra `HIGH_UTILISATION`. Một mô hình không trả về attribution nào thì không cho mã lý do nào, và bước của nó trong bản ghi có danh sách `attributions` rỗng.

Các mã này được thêm vào mã lý do của luồng theo thứ tự các nút đã chạy, với nút làm nguồn. Một mô hình lỗi chỉ cho mã lý do `onFailure` của nó.

### Tính tất định và replay

- **Chế độ `supplied` là tất định.** Cùng các fact, cùng các kết quả mô hình và cùng ngày nghiệp vụ luôn cho cùng đường đi, đầu ra, mã lý do và bản ghi (trừ `recordId` và `evaluatedAt`).
- **Chế độ `invoke`** phụ thuộc vào câu trả lời của mô hình, và thời điểm trả lời. Vì vậy bản ghi giữ mọi thứ mà các nút phía sau đã dùng: các đầu ra, độ tin cậy, và thứ hạng, chiều và mã lý do của các attribution. Replay (chạy lại), bước 5 của [Ra quyết định AI lai](hybrid-ai.md#9-kế-hoạch-triển-khai), sẽ dựng lại kết quả của mỗi mô hình từ bước của nó và chạy luồng với `suppliedModelsOnly()`; thứ hạng và chiều là đủ để cho cùng các mã lý do. Một bước lỗi giữ `status` của nó, và được replay thành cùng lỗi đó. Replay hiện chưa có.

### Bước mô hình trong bản ghi

Mỗi nút mô hình thêm một bước kiểu `model` vào `path` của bản ghi. Với một khách hàng hiện hữu có điểm gian lận do bên gọi cung cấp và có mô hình hành vi trả lời trong 23 ms, hai bước mô hình là:

```json
[
  { "node": "fraud", "type": "model", "mode": "supplied",
    "model": { "id": "card_application_fraud", "version": "1.2.0" },
    "status": "OK",
    "outputs": { "score": "0.05" },
    "attributionMethod": "SHAP",
    "attributions": [
      { "feature": "deviceAgeDays", "rank": 1, "direction": "INCREASES_RISK", "reasonCode": "NEW_DEVICE" },
      { "feature": "addressMismatch", "rank": 2, "direction": "INCREASES_RISK", "reasonCode": "ADDRESS_MISMATCH" }
    ] },
  { "node": "pd_behaviour", "type": "model", "mode": "invoke",
    "model": { "id": "retail_pd_behaviour", "version": "2.4.0" },
    "status": "OK", "latencyMs": 23,
    "outputs": { "pd": "0.0150" },
    "confidence": "0.95",
    "attributionMethod": "SHAP",
    "attributions": [
      { "feature": "utilisation", "rank": 1, "direction": "INCREASES_RISK", "reasonCode": "HIGH_UTILISATION" },
      { "feature": "dpd30Count12m", "rank": 2, "direction": "DECREASES_RISK" }
    ] }
]
```

Nếu mô hình hành vi hết thời gian, bước của nó sẽ là `{ "node": "pd_behaviour", "type": "model", "mode": "invoke", "model": { … }, "status": "TIMEOUT", "detail": "no result within 80 ms", "latencyMs": 81 }`, theo sau là bước `referred`.

| Trường của bước | Nội dung |
|---|---|
| `mode` | Cách kết quả được lấy. Một nút `invoke` của engine được tạo với `suppliedModelsOnly()` hiển thị `supplied`. |
| `model` | Id của mô hình và phiên bản mà nút ghim. |
| `status` | `OK`, hoặc [loại lỗi](#lỗi-của-mô-hình). |
| `detail` | Với một lỗi, điều gì đã thất bại, không có giá trị. |
| `latencyMs` | Với mô hình do engine gọi, độ trễ của lần gọi. |
| `outputs` | Với `OK`, các đầu ra mà các nút phía sau đọc, theo [các dạng JSON](java-api.md#fact-và-kết-quả-dạng-json-factsjson). |
| `confidence`, `attributionMethod` | Với `OK`, như mô hình đã báo, nếu có. |
| `attributions` | Với `OK`, mọi attribution theo thứ hạng, kèm chiều và, nếu nút đã đưa ra mã cho nó, mã lý do. Giá trị mức đóng góp bị loại ra. |

Bản ghi không bao giờ chứa các đặc trưng đã gửi tới mô hình, hay các mức đóng góp thô: chỉ có định danh, các đầu ra mà luồng đã dùng, và thứ hạng. Nhờ vậy bản ghi vẫn an toàn khi ghi vào log thông thường, như với bảng.

## Đánh giá

Engine đánh giá từng nút một, bắt đầu từ `start`, và đi theo đúng một đường tới một nút `end`. Cùng các fact và cùng ngày nghiệp vụ luôn cho cùng một đường đi.

- **Ngày nghiệp vụ.** Ngày nghiệp vụ của luồng (`DecisionRef.asOf`, nếu không thì theo đồng hồ của engine) chọn phiên bản luồng, chọn phiên bản của mọi bảng không ghim `version`, và là `businessDate` của mọi biểu thức và mọi bảng.
- **Phiên bản bảng.** Một nút `table` không có `version` dùng phiên bản của bảng có hiệu lực vào ngày nghiệp vụ. Vì đó có thể là bất kỳ phiên bản đã đăng ký nào, luồng được kiểm tra với tất cả các phiên bản: fact phải phù hợp với mọi phiên bản, và luồng chỉ đọc được các đầu ra mà mọi phiên bản đều khai báo với cùng kiểu. Hãy ghim `version` khi các phiên bản của một bảng khác nhau.
- **Mã lý do.** Mã lý do của mọi nút `table` và nút mô hình, theo thứ tự. Mỗi mã xuất hiện một lần, cùng với nút đầu tiên đã tạo ra mã đó.
- **Luật đã khớp.** `DecisionResult.matchedRules()` và `trace()` liệt kê các luật của mọi nút `table`, theo thứ tự. Bản ghi quyết định cho biết mỗi luật đến từ nút nào.
- **Thời gian chờ tối đa.** [Thời gian chờ tối đa](java-api.md#thời-gian-chờ-tối-đa) bao trùm toàn bộ luồng, kể cả các lần gọi mô hình. Engine kiểm tra thời gian trước mỗi nút và trước mỗi luật, và không cho mô hình nhiều thời gian hơn phần việc đánh giá còn lại.
- **Lỗi.** Khi một nút lỗi, ví dụ chia cho 0 hoặc một bảng `UNIQUE` có mọi luật cùng khớp, việc đánh giá thất bại với một `DecisionEvaluationException` của luồng, có thông báo nêu tên nút. Như mọi khi, thông báo không chứa giá trị đầu vào nào.
- **Phiên bản canary và phiên bản bóng** hoạt động với luồng như với bảng: hãy dùng id của luồng trong `canary` hoặc `shadow` (xem [Phiên bản canary và phiên bản bóng](java-api.md#phiên-bản-canary-và-phiên-bản-bóng)). Bên trong một luồng, các nút `table` luôn dùng phiên bản đang có hiệu lực hoặc phiên bản mà nút ghim; canary và phiên bản bóng của các bảng đó không được áp dụng.

## Bản ghi quyết định

[Bản ghi quyết định](java-api.md#bản-ghi-quyết-định-và-kiểm-toán) của một luồng có trong `path` một bước cho mỗi nút đã đi qua, theo thứ tự. Với lần đánh giá trong [Chạy một luồng](#chạy-một-luồng), bản ghi là:

```json
{
  "recordId": "6b0e2c51-3f7a-4d8e-9a14-c2d5f8e1b037",
  "correlationId": "CARD-2026-000777",
  "decision": "card_origination",
  "version": "1@sha256:986ffd80e5fedf4282e123e5c45f00da428234a6b2fbffb874571d3c056d924c",
  "businessDate": "2026-12-01",
  "evaluatedAt": "2026-12-01T02:15:04.218Z",
  "engine": "rulestone 0.1.0",
  "status": "OK",
  "factsHash": "sha256:899ae821198f33a92c369a3b18ea9f0cee2aff1da7830743b6753ffeb63ab790",
  "output": { "decision": "APPROVE", "limit": "144000000 VND", "riskGrade": "A" },
  "reasonCodes": [],
  "path": [
    { "node": "policy", "type": "table", "decision": "card_policy",
      "version": "1@sha256:54e99ef7d8be5c18c3d490cbe6dc2facd60e32bce51f7769a486af9a0854adcb", "matchedRules": ["PASS"] },
    { "node": "affordability", "type": "derive" },
    { "node": "segment", "type": "switch", "taken": "grade_existing" },
    { "node": "grade_existing", "type": "table", "decision": "card_grade_existing",
      "version": "1@sha256:63cd7b62f4208a03bfd914f30d0ee45e4dcfcce038dccfb0e62964dc1af6fa7b", "matchedRules": ["A"] },
    { "node": "limit_strategy", "type": "split", "taken": "challenger" },
    { "node": "limit", "type": "table", "decision": "card_limit",
      "version": "1@sha256:af5200a630dbf793caa4d3d72f7a4547ee675eb5d03e5baaf74d27259408d31a", "matchedRules": ["A_CHALLENGER"] },
    { "node": "approved", "type": "end" }
  ]
}
```

| Trường của bước | Có với | Nội dung |
|---|---|---|
| `node`, `type` | mọi bước | Id của nút và kiểu của nút. |
| `decision`, `version` | `table` | Bảng đã được đánh giá và đúng phiên bản của nó, để quyết định có thể tái lập ngay cả sau khi bảng thay đổi. |
| `matchedRules` | `table` | Các luật đã khớp, theo thứ tự. |
| `taken` | `switch`, `split` | Nút mà switch đã chọn, hoặc nhóm mà split đã chọn. |
| `mode`, `model`, `status`, `detail`, `latencyMs`, `outputs`, `confidence`, `attributionMethod`, `attributions` | `model` | Mô hình đã được hỏi gì và đã trả lời gì; xem [Bước mô hình trong bản ghi](#bước-mô-hình-trong-bản-ghi). |

`version` của chính bản ghi là phiên bản của luồng. Khi việc đánh giá lỗi bên trong một nút, nút đó là bước cuối cùng của đường đi, và một bước `table` cho thấy các luật đã khớp trước khi lỗi.

## Phiên bản và mã băm nội dung

Phiên bản của một luồng gồm nhãn `version` cộng với SHA-256 của dạng chuẩn tắc, như với bảng: `1@sha256:…`. Mã băm chỉ bao gồm tệp luồng. Nó không bao gồm các bảng mà luồng dùng, vì một nút `table` không có `version` dùng phiên bản nào của bảng đang có hiệu lực; thay vào đó, bản ghi quyết định nêu đúng phiên bản bảng của mọi bước `table`. Để cố định một phiên bản bảng như một phần nội dung của luồng, hãy đặt `version` trên nút.

Dạng chuẩn tắc viết một `next` duy nhất, không điều kiện, thành id của nút, nên `"next": "segment"` và `"next": [ { "to": "segment" } ]` là cùng một nội dung.

## Kiểm tra hợp lệ

`RuleStone.builder().build()` từ chối một luồng, liệt kê mọi lỗi kèm vị trí, khi:

- đồ thị có chu trình, có nút mà `start` không tới được, hoặc có một `to` không trỏ tới nút nào;
- một mảng `next` hoặc `cases` có `when` ở lựa chọn cuối cùng, hoặc thiếu `when` ở một lựa chọn khác;
- một biểu thức không biên dịch được hoặc sai kiểu, hoặc đọc một giá trị mà có đường đi tới nút của nó không đặt;
- các nút dùng chung một tên `as` công bố các giá trị khác nhau, hoặc có thể cùng chạy trên một đường đi;
- một nút `table` trỏ tới một bảng hoặc phiên bản chưa được đăng ký, tới một luồng, hoặc có một đầu vào của bảng mà nút không đáp ứng được;
- một nút `end` không đặt mọi đầu ra, hoặc đặt một đầu ra chưa được khai báo;
- một nút `model` không ghim đúng một phiên bản mô hình; thiếu `features` hoặc `timeoutMs` ở chế độ `invoke`, hoặc có chúng ở chế độ `supplied`; có một đặc trưng hoặc điều kiện `valid` không qua được kiểm tra kiểu, một đầu ra có trường con, một `minConfidence` nằm ngoài khoảng 0 tới 1, hoặc một mã lý do không viết dạng `UPPER_SNAKE_CASE`; hoặc ở chế độ `invoke` mà chưa có invoker nào được đăng ký cho mô hình của nó (trừ khi engine được tạo với `suppliedModelsOnly()`). `onFailure` là bắt buộc ngay khi đọc tệp;
- một `split` có tên nhóm trùng nhau, hoặc có tỷ lệ phần trăm không có tổng bằng 100;
- một id nút hoặc tên `as` không phải định danh, hoặc trùng tên một đầu vào;
- `id` của luồng cũng là id của một bảng, hoặc hai phiên bản mâu thuẫn nhau, như với bảng.

Ví dụ, nếu `referred` cũng đọc `limit.limit`, giá trị chỉ được đặt trên đường phê duyệt, engine sẽ từ chối được tạo với:

```
invalid decision definition (1 problem):
  - card_origination@1 nodes.referred.set.limit: reads 'limit', which is not set on every path that reaches this node; a node may read only values that every path to it sets
```

## Kiểm thử luồng

Ca kiểm thử cho luồng là các tệp `.stone-test.json`, giống như với bảng, và còn có thể kỳ vọng đường đi qua các nút và cung cấp kết quả mô hình trong `models`. Khi đó độ bao phủ đòi hỏi mọi nút đều được đi qua và mọi nhánh đều được đi theo, kể cả đường `onFailure` của mọi nút mô hình. Xem [Kiểm thử luồng quyết định](testing.md#kiểm-thử-luồng-quyết-định).

## Giới hạn

- **Chưa có replay.** Chạy lại một quyết định đã ghi với các kết quả mô hình đã ghi là bước 5 của [Ra quyết định AI lai](hybrid-ai.md#9-kế-hoạch-triển-khai).
- **Chưa có mô hình chạy bóng (shadow) hay nút LLM.** Một nút mô hình có `shadow`, `kind` hoặc `prompt` bị từ chối vì chưa được hỗ trợ; chúng thuộc bước 7.
- **Chưa có luồng con.** Một nút chưa thể gọi luồng khác.
- **Chỉ bộ máy luật Java.** Bộ máy luật trên trình duyệt của [SDK trình soạn thảo](editor-sdk.md) chưa chạy luồng, và vùng vẽ luồng của nó chỉ dùng để thiết kế. [Trình soạn thảo web](editor.md) và các tích hợp [Temporal](temporal.md) và [Camunda](camunda.md) hiện chỉ đánh giá bảng quyết định. Chúng đã đọc được kết quả mô hình do bên gọi cung cấp ở dạng JSON, theo đúng dạng `models` ở trên, để dùng khi chúng đánh giá được luồng; cho tới lúc đó, một bảng quyết định sẽ từ chối các kết quả này.
