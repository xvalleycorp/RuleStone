---
source: flows.md
sourceHash: 71baed6219af27980a79d41234877df7cdc112210426dbd19fb8c9cb46ed0cd4
---

# Luồng quyết định

Một luồng quyết định (decision flow) nối các bảng quyết định và một số logic đơn giản thành một quyết định: luật loại trừ (knock-out) của chính sách chạy trước, sau đó là phân khúc, xếp hạng và ma trận hạn mức, kèm một phép chia champion/challenger (so sánh phiên bản đang chạy với phiên bản thử thách) ở nơi bạn muốn thử một chiến lược mới. Trang này là tài liệu tham khảo cho định dạng `.flow.json` và cho cách luồng được kiểm tra, đánh giá và ghi lại.

Luồng là bước 2 của [Ra quyết định AI lai](hybrid-ai.md#9-kế-hoạch-triển-khai). Luồng chưa có nút mô hình: điểm số của mô hình đi vào luồng như một đầu vào thông thường (xem [Giới hạn](#giới-hạn)). Bộ máy luật Java chạy được luồng; bộ máy luật trên trình duyệt của [SDK trình soạn thảo](editor-sdk.md) thì chưa.

- [Một luồng đầu tiên](#một-luồng-đầu-tiên)
- [Chạy một luồng](#chạy-một-luồng)
- [Định dạng tệp](#định-dạng-tệp)
- [Các nút](#các-nút)
- [Giá trị giữa các nút](#giá-trị-giữa-các-nút)
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
| `split` | Gán một nhóm theo tỷ lệ phần trăm | `taken`: tên của nhóm được chọn | Nút của nhóm đó |
| `end` | Đặt các đầu ra của luồng | Không có gì | Kết thúc luồng |

Tên kiểu `model` và `flow` được dành riêng cho nút mô hình và cho việc gọi luồng khác. Chúng chưa được hỗ trợ, và tệp nào dùng chúng sẽ bị từ chối.

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

## Đánh giá

Engine đánh giá từng nút một, bắt đầu từ `start`, và đi theo đúng một đường tới một nút `end`. Cùng các fact và cùng ngày nghiệp vụ luôn cho cùng một đường đi.

- **Ngày nghiệp vụ.** Ngày nghiệp vụ của luồng (`DecisionRef.asOf`, nếu không thì theo đồng hồ của engine) chọn phiên bản luồng, chọn phiên bản của mọi bảng không ghim `version`, và là `businessDate` của mọi biểu thức và mọi bảng.
- **Phiên bản bảng.** Một nút `table` không có `version` dùng phiên bản của bảng có hiệu lực vào ngày nghiệp vụ. Vì đó có thể là bất kỳ phiên bản đã đăng ký nào, luồng được kiểm tra với tất cả các phiên bản: fact phải phù hợp với mọi phiên bản, và luồng chỉ đọc được các đầu ra mà mọi phiên bản đều khai báo với cùng kiểu. Hãy ghim `version` khi các phiên bản của một bảng khác nhau.
- **Mã lý do.** Mã lý do của mọi nút `table`, theo thứ tự. Mỗi mã xuất hiện một lần, cùng với nút đầu tiên đã tạo ra mã đó.
- **Luật đã khớp.** `DecisionResult.matchedRules()` và `trace()` liệt kê các luật của mọi nút `table`, theo thứ tự. Bản ghi quyết định cho biết mỗi luật đến từ nút nào.
- **Thời gian chờ tối đa.** [Thời gian chờ tối đa](java-api.md#thời-gian-chờ-tối-đa) bao trùm toàn bộ luồng. Engine kiểm tra thời gian trước mỗi nút và trước mỗi luật.
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
- một `split` có tên nhóm trùng nhau, hoặc có tỷ lệ phần trăm không có tổng bằng 100;
- một id nút hoặc tên `as` không phải định danh, hoặc trùng tên một đầu vào;
- `id` của luồng cũng là id của một bảng, hoặc hai phiên bản mâu thuẫn nhau, như với bảng.

Ví dụ, nếu `referred` cũng đọc `limit.limit`, giá trị chỉ được đặt trên đường phê duyệt, engine sẽ từ chối được tạo với:

```
invalid decision definition (1 problem):
  - card_origination@1 nodes.referred.set.limit: reads 'limit', which is not set on every path that reaches this node; a node may read only values that every path to it sets
```

## Kiểm thử luồng

Ca kiểm thử cho luồng là các tệp `.stone-test.json`, giống như với bảng, và còn có thể kỳ vọng đường đi qua các nút. Khi đó độ bao phủ đòi hỏi mọi nút đều được đi qua và mọi nhánh đều được đi theo. Xem [Kiểm thử luồng quyết định](testing.md#kiểm-thử-luồng-quyết-định).

## Giới hạn

- **Chưa có nút mô hình.** Nút mô hình (`"type": "model"`), với hợp đồng có kiểu, đường xử lý lỗi và attribution, là bước 3 của [Ra quyết định AI lai](hybrid-ai.md#9-kế-hoạch-triển-khai). Trong lúc chờ, hãy tính điểm số bên ngoài RuleStone và truyền vào như một đầu vào, như trong [Mẫu A](hybrid-ai.md#21-đầu-vào-thông-thường-dùng-được-ngay-hôm-nay).
- **Chưa có luồng con.** Một nút chưa thể gọi luồng khác.
- **Chỉ bộ máy luật Java.** Bộ máy luật trên trình duyệt của [SDK trình soạn thảo](editor-sdk.md) chưa chạy luồng, và vùng vẽ luồng của nó chỉ dùng để thiết kế. [Trình soạn thảo web](editor.md) và các tích hợp [Temporal](temporal.md) và [Camunda](camunda.md) hiện chỉ làm việc với bảng quyết định.
