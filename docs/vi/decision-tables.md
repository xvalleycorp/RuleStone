---
source: decision-tables.md
sourceHash: 19d8383a39049d23a65bf54cc0fe2a068e6e5864b77dceb069d881f0222865f4
---

# Bảng quyết định

Bảng quyết định biến các đầu vào có kiểu thành các đầu ra có kiểu, dựa trên một danh sách luật có thứ tự. Trang này là tài liệu tham khảo cho định dạng tệp `.stone.json`, và cho cách các bảng được đánh phiên bản, được chọn và được đánh giá.

- [Định dạng tệp](#định-dạng-tệp)
- [Kiểu dữ liệu](#kiểu-dữ-liệu)
- [Luật](#luật)
- [Giá trị cục bộ](#giá-trị-cục-bộ)
- [Tham số](#tham-số)
- [Hit policy](#hit-policy)
- [Phiên bản và ngày hiệu lực](#phiên-bản-và-ngày-hiệu-lực)
- [Kiểm tra hợp lệ](#kiểm-tra-hợp-lệ)

## Định dạng tệp

Một bảng là một đối tượng JSON. Theo quy ước, tên tệp kết thúc bằng `.stone.json`.

```json
{
  "id": "deposit_rate",
  "version": "1",
  "description": "Term deposit interest rate by term and balance",
  "effectiveFrom": "2026-01-01",
  "effectiveTo": "2027-01-01",
  "hitPolicy": "UNIQUE",
  "inputs":  { "termMonths": "int", "balance": "decimal" },
  "outputs": { "rate": "decimal" },
  "rules": [
    { "id": "SHORT", "when": "termMonths < 6", "then": { "rate": "decimal('0.035')" } }
  ]
}
```

| Trường | Bắt buộc | Mô tả |
|---|---|---|
| `id` | có | Tên của quyết định, dùng trong `DecisionRef.of(id)`. Bắt đầu bằng một chữ cái; gồm chữ cái, chữ số, `_`, `.` và `-`. Mọi phiên bản của một quyết định có chung `id`. |
| `version` | có | Nhãn phiên bản, ví dụ `"3"` hoặc `"2026.11"`. Gồm chữ cái, chữ số, `_`, `.` và `-`. Duy nhất trong cùng một `id`. |
| `description` | không | Mô tả tự do cho người đọc. Là một phần của mã băm nội dung. |
| `effectiveFrom` | không | Ngày đầu tiên phiên bản này có hiệu lực, dạng `yyyy-MM-dd`, tính cả ngày đó. Bỏ trống nghĩa là "có hiệu lực từ trước tới nay". |
| `effectiveTo` | không | Ngày đầu tiên phiên bản này **không còn** hiệu lực, dạng `yyyy-MM-dd`, không tính ngày đó. Bỏ trống nghĩa là "cho đến khi phiên bản kế tiếp có hiệu lực". Phải sau `effectiveFrom`. |
| `hitPolicy` | có | `FIRST`, `UNIQUE` hoặc `COLLECT`. Xem [Hit policy](#hit-policy). |
| `inputs` | có | Đối tượng ánh xạ tên mỗi đầu vào tới [kiểu](#kiểu-dữ-liệu) của nó. Có thể rỗng. |
| `outputs` | có | Đối tượng ánh xạ tên mỗi đầu ra tới [kiểu](#kiểu-dữ-liệu) của nó. Có thể rỗng, ví dụ với một bảng chỉ trả về mã lý do. |
| `params` | không | Bộ tham số mà các luật đọc giá trị nghiệp vụ từ đó, cùng các tham số được đọc và kiểu của chúng. Xem [Tham số](#tham-số). |
| `let` | không | Đối tượng ánh xạ tên tới biểu thức mà mọi luật đều đọc được theo tên. Xem [Giá trị cục bộ](#giá-trị-cục-bộ). |
| `rules` | có | Mảng [luật](#luật) không rỗng, theo thứ tự đánh giá. |

Việc đọc tệp là **chặt chẽ**:

- Trường không xác định là lỗi. Một chữ `"efectiveFrom"` gõ sai không thể âm thầm đưa một phiên bản vào hiệu lực.
- Khoá trùng lặp trong bất kỳ đối tượng nào là lỗi.
- Mọi lỗi trong tệp được báo cáo cùng lúc, mỗi lỗi kèm đường dẫn JSON, ví dụ `$.rules[2].then.limit`.

Tên đầu vào, tên đầu ra và tên trường của struct phải là định danh hợp lệ: một chữ cái hoặc `_`, theo sau là chữ cái, chữ số hoặc `_`. Không được dùng các từ khoá dành riêng của CEL (`in`, `if`, `true`, `null`, …). Không đầu vào nào được đặt tên `businessDate`: mọi quyết định đều nhận giá trị này một cách ngầm định (xem [Ngày nghiệp vụ](expressions.md#ngày-nghiệp-vụ)).

## Kiểu dữ liệu

| Kiểu | JSON | Đầu vào Java được chấp nhận | Đầu ra Java |
|---|---|---|---|
| Boolean | `"bool"` | `Boolean` | `Boolean` |
| Số nguyên 64 bit | `"int"` | `Long`, `Integer`, `Short`, `Byte`, `BigInteger` trong phạm vi | `Long` |
| Văn bản | `"string"` | `String`, mọi `enum` (lấy `name()`) | `String` |
| Số thập phân chính xác | `"decimal"` | `BigDecimal`; số nguyên được chuyển đổi | `BigDecimal` |
| Tiền | `"money"` | chỉ `Money` (`vn.io.dxa.rulestone.api`) | `Money` |
| Ngày | `"date"` | chỉ `LocalDate`; chuỗi và các kiểu `java.time` khác bị từ chối | `LocalDate` |
| Danh sách | `["<type>"]`, ví dụ `["decimal"]` | mọi `Collection` | `List` |
| Struct | `{ "field": "<type>", … }` | `Map` có khoá `String`, hoặc một `record` | — (chỉ dùng cho đầu vào) |

**Không có kiểu dấu phẩy động.** Lãi suất và tỷ lệ dùng `decimal`; số tiền dùng `money` khi loại tiền tệ có ý nghĩa, nếu không thì dùng `decimal`. Khai báo `"double"`, `"float"` hay `"number"` là lỗi, và truyền một `Double` hoặc `Float` vào chỗ cần `decimal` sẽ bị từ chối lúc đánh giá.

Các kiểu có thể lồng nhau. Ví dụ một khách hàng có danh sách tài khoản:

```json
"inputs": {
  "customer": {
    "segment": "string",
    "accounts": [ { "type": "string", "balance": "decimal" } ]
  }
}
```

```
customer.accounts.exists(a, a.type == 'SAVINGS' && a.balance >= decimal(100000000))
```

**Trường của struct là tuỳ chọn lúc đánh giá.** Một trường bị thiếu trong đầu vào dạng `Map`, hoặc có giá trị `null`, được coi là chưa đặt. (Java record vẫn phải khai báo mọi trường thành component; xem [Java API](java-api.md#đầu-vào-facts).) Kiểm tra bằng `has(customer.segment)`; đọc một trường chưa đặt sẽ làm việc đánh giá thất bại. **Đầu vào cấp cao nhất là bắt buộc**: thiếu một đầu vào sẽ làm việc đánh giá thất bại.

Đầu ra phải có kiểu `bool`, `int`, `string`, `decimal`, `money`, `date`, hoặc danh sách của các kiểu này.

Một bảng dùng tiền và ngày:

```json
"inputs":  { "transfer": { "amount": "money", "valueDate": "date" } },
"outputs": { "fee": "money", "settles": "date" }
```

```
max(money(2000, transfer.amount.currency()), (transfer.amount * decimal('0.0005')).round())
transfer.valueDate.plusDays(1)
```

Ngoài các đầu vào đã khai báo, mọi luật đều đọc được `businessDate`, tức ngày mà quyết định được đưa ra cho ngày đó. Xem [Ngày nghiệp vụ](expressions.md#ngày-nghiệp-vụ).

## Luật

| Trường | Bắt buộc | Mô tả |
|---|---|---|
| `id` | có | Duy nhất trong bảng. Được ghi vào vết thực thi và nhật ký kiểm toán, nên hãy giữ nguyên qua các phiên bản khi ý nghĩa của luật không đổi. |
| `description` | không | Mô tả tự do cho người đọc. |
| `when` | có | [Biểu thức](expressions.md) phải trả về `bool`. Luật *khớp* khi biểu thức là `true`. Dùng `"true"` cho luật hứng mọi trường hợp còn lại. |
| `then` | không | Đối tượng ánh xạ tên đầu ra tới biểu thức. Mỗi biểu thức phải trả về đúng kiểu đã khai báo của đầu ra. Một luật có thể đặt một số, tất cả hoặc không đầu ra nào. |
| `reasonCode` | không | Được báo cáo khi luật khớp. Viết dạng `UPPER_SNAKE_CASE`, ví dụ `DTI_HIGH`. |

Giá trị trong `then` là biểu thức, không phải hằng giá trị. Văn bản cần có dấu nháy bên trong:

```json
"then": { "decision": "'APPROVE'", "limit": "applicant.monthlyIncome * 12", "manual": "false" }
```

Mã lý do là bộ từ vựng ổn định mà bạn đưa cho khách hàng và kiểm toán viên ("vì sao tôi bị từ chối?"). Hãy giữ chúng ổn định qua các phiên bản và lập tài liệu cho tổ chức của bạn.

## Giá trị cục bộ

`let` đặt tên cho các kết quả trung gian mà nhiều luật dùng chung, để một phép tính chỉ phải viết một lần:

```json
"let": {
  "monthlyDebt": "loans.map(l, l.monthlyPayment).sum(money(0, 'VND'))",
  "dti": "monthlyDebt.amount() / applicant.monthlyIncome.amount()"
},
"rules": [
  { "id": "DTI_HIGH", "when": "dti > decimal('0.5')", "then": { "decision": "'REJECT'" } },
  { "id": "LOW_DEBT", "when": "monthlyDebt < money(5000000, 'VND')", "then": { "decision": "'APPROVE'" } }
]
```

- **Kiểu được suy ra** từ biểu thức, và biểu thức được kiểm tra kiểu khi nạp bảng. Một giá trị cục bộ có thể là `bool`, `int`, `string`, `decimal`, `money`, `date`, một danh sách hoặc một struct, ví dụ `loans[0]`.
- **Một giá trị cục bộ có thể đọc đầu vào, `businessDate` và các giá trị cục bộ khác.** Thứ tự trong tệp không quan trọng; giá trị cục bộ nào đọc chính nó, trực tiếp hay qua giá trị khác, đều bị từ chối.
- **Giá trị được tính khi được đọc lần đầu**, tối đa một lần cho mỗi lần đánh giá. Giá trị cục bộ mà không luật nào được đánh giá đọc tới thì không bao giờ được tính, nên không thể làm việc đánh giá thất bại. Trong [credit_limit_increase](../../examples/credit_limit_increase.stone.json), `dti` chia cho thu nhập, nhưng khách hàng có kỳ trả nợ bị lỡ đã bị luật đầu tiên từ chối trước khi `dti` được đọc, kể cả khi không có thu nhập.
- **Lỗi xử lý như thể biểu thức được viết ngay trong luật.** `||` và `&&` vẫn hấp thụ lỗi như thường lệ. Khi một luật thất bại vì một giá trị cục bộ, thông báo lỗi nêu tên giá trị đó: `rule DTI_HIGH condition failed: local value dti failed: decimal division by zero in: …`. Mỗi giá trị cục bộ có [giới hạn số vòng lặp](expressions.md#giới-hạn) riêng.
- **Tên** là định danh, không được trùng với một đầu vào, `businessDate` hoặc `params` (dành cho bộ tham số). Một đầu ra có thể trùng tên với một giá trị cục bộ.
- Giá trị cục bộ không được ghi vào bản ghi quyết định: chúng suy ra được từ đầu vào và phiên bản luật. [Mã băm nội dung](#mã-băm-nội-dung) bao gồm `let`; bảng không có `let`, hoặc có `let` rỗng, giữ nguyên mã băm như trước khi có `let`.

Trong một [luồng](flows.md), nút `derive` tính giá trị giữa các nút; còn `let` dành cho các phép tính dùng chung bởi các luật của một bảng.

## Tham số

Những giá trị do chính sách của ngân hàng ấn định, như hạn mức tỷ lệ nợ trên thu nhập hay tuổi tối thiểu, thuộc về một **bộ tham số**: một tệp `.params.json` riêng, được rà soát và quản lý phiên bản như luật, mà các luật đọc bằng `params.<tên>`. Bên gọi không bao giờ truyền chúng vào; phiên bản của bộ tham số có hiệu lực vào ngày nghiệp vụ được dùng. Thiết kế và lý do nằm ở [Tham số, hàm tổng hợp và giá trị cục bộ](parameters.md#b-bộ-tham-số).

Bảng khai báo bộ tham số và các tham số nó đọc, cùng kiểu của chúng, giống như khai báo đầu vào:

```json
"params": {
  "set": "retail_credit",
  "values": { "minAge": "int", "maxDti": "decimal", "incomeMultiple": "int", "blockedSegments": ["string"] }
},
"let": {
  "dti": "applicant.monthlyDebt / applicant.monthlyIncome",
  "maxAmount": "applicant.monthlyIncome * params.incomeMultiple"
},
"rules": [
  { "id": "R1", "when": "applicant.age < params.minAge", "then": { "decision": "'REJECT'" } },
  { "id": "R4", "when": "dti > params.maxDti", "then": { "decision": "'REJECT'" } }
]
```

| Trường | Bắt buộc | Mô tả |
|---|---|---|
| `set` | có | Id của bộ tham số. |
| `version` | không | Một phiên bản của bộ tham số dùng bất kể ngày nghiệp vụ, cho các trường hợp đặc biệt. Nếu không có, phiên bản có hiệu lực vào ngày nghiệp vụ được dùng. |
| `values` | có | Đối tượng ánh xạ mỗi tham số mà bảng đọc tới kiểu của nó: `bool`, `int`, `string`, `decimal`, `money`, `date` hoặc danh sách của chúng. Ít nhất một tham số. |

Luật và giá trị cục bộ đọc `params.<tên>`; đọc một tham số mà `values` không khai báo là lỗi. [Mã băm nội dung](#mã-băm-nội-dung) bao gồm phần khai báo, không bao gồm giá trị: một phiên bản mới của bộ tham số không làm thay đổi phiên bản luật nào. Bảng không có `params` giữ nguyên mã băm như trước khi có bộ tham số.

### Tệp bộ tham số

Một bộ tham số là một tệp `.params.json`, mỗi phiên bản một tệp:

```json
{
  "id": "retail_credit",
  "version": "2",
  "description": "Tighter debt-to-income limit and lower income multiple, approved by the Credit Committee on 2027-06-10",
  "effectiveFrom": "2027-07-01",
  "values": {
    "minAge": { "type": "int", "value": 18 },
    "maxDti": { "type": "decimal", "value": "0.45" },
    "incomeMultiple": { "type": "int", "value": 10 },
    "blockedSegments": { "type": ["string"], "value": ["DORMANT", "SANCTIONED"] }
  }
}
```

| Trường | Bắt buộc | Mô tả |
|---|---|---|
| `id` | có | Id của bộ tham số, dùng chung cho mọi phiên bản. Id bộ tham số là một không gian tên riêng: một bộ tham số có thể trùng id với một quyết định. |
| `version` | có | Nhãn phiên bản, như với bảng. |
| `description` | không | Văn bản tự do, ví dụ ai đã phê duyệt các giá trị. |
| `effectiveFrom`, `effectiveTo` | không | Như với bảng: một phiên bản có hiệu lực cho tới khi phiên bản kế tiếp có hiệu lực. Xem [Phiên bản và ngày hiệu lực](#phiên-bản-và-ngày-hiệu-lực). |
| `values` | có | Đối tượng ánh xạ mỗi tên tham số tới `{ "type": …, "value": … }`. Kiểu được viết như với đầu vào; tham số không có trường con, nên không cho phép kiểu struct. Giá trị dùng các dạng JSON của fact: tiền là `"500000000 VND"`, số thập phân là chuỗi hoặc số, được đọc chính xác, ngày là `"yyyy-MM-dd"`. |

Việc đọc là chặt chẽ, như với bảng: trường không xác định, khoá trùng lặp và giá trị không khớp kiểu đều là lỗi, mỗi lỗi kèm đường dẫn JSON, ví dụ `$.values.maxDti.value`. Mỗi phiên bản có mã băm nội dung riêng, `2@sha256:…`, tính trên cùng dạng chuẩn như của bảng.

Để thay đổi một hạn mức từ một ngày, hãy thêm một phiên bản của bộ tham số với `effectiveFrom` muộn hơn: bảng không thay đổi. Đăng ký mọi phiên bản bằng `RuleStone.builder().paramSet(...)` (xem [Java API](java-api.md#bộ-tham-số)); mỗi bản ghi quyết định liệt kê các phiên bản bộ tham số đã dùng, và `DecisionRef.params(set, version)` chạy lại với chúng.

## Hit policy

Hit policy (chính sách khớp luật) quyết định cách kết hợp các luật cùng khớp. Tên gọi theo chuẩn [DMN](https://www.omg.org/dmn/).

### `FIRST`

Các luật được đánh giá theo thứ tự. Luật đầu tiên có `when` là `true` tạo ra kết quả; các luật phía sau **không được đánh giá**.

- `output` chứa các đầu ra do luật đó đặt.
- `reasonCodes` chứa mã lý do của luật đó, nếu có.
- Nếu không luật nào khớp, `output` và `reasonCodes` rỗng và `matched()` là `false`. Hãy kết thúc bảng bằng một luật `"when": "true"` nếu mọi yêu cầu đều phải có câu trả lời.

Dùng `FIRST` cho việc xét điều kiện và mọi trường hợp có thứ tự ưu tiên tự nhiên, chẳng hạn "các lý do từ chối trước, rồi đến phê duyệt, rồi chuyển cho người xem xét".

### `UNIQUE`

Mọi luật đều được đánh giá. **Tối đa một luật được khớp.** Nếu hai luật trở lên cùng khớp, việc đánh giá thất bại với một `DecisionEvaluationException` nêu tên các luật đó, và không có kết quả nào được trả về.

- Khi có một luật khớp, `output` và `reasonCodes` lấy từ luật đó.
- Khi không có luật nào khớp, cả hai đều rỗng.

Dùng `UNIQUE` cho các bảng mà các dòng phải chia tách không gian đầu vào, chẳng hạn biểu lãi suất. Hai luật cùng khớp nghĩa là logic của bảng có lỗ hổng, và báo lỗi an toàn hơn là chọn đại một luật. Xem [`examples/deposit_rate.stone.json`](../../examples/deposit_rate.stone.json).

### `COLLECT`

Mọi luật đều được đánh giá và **mọi luật khớp đều đóng góp vào kết quả**.

- Mỗi đầu ra đã khai báo xuất hiện trong `output` dưới dạng danh sách, chứa giá trị từ các luật khớp có đặt đầu ra đó, theo thứ tự luật. Đầu ra không được luật khớp nào đặt là một danh sách rỗng.
- `reasonCodes` liệt kê mã lý do của mọi luật khớp, theo thứ tự luật.

Dùng `COLLECT` để cộng dồn, chẳng hạn các loại phí áp dụng cho một giao dịch chuyển tiền. Xem [`examples/transfer_fees.stone.json`](../../examples/transfer_fees.stone.json).

### Vết thực thi

Dù dùng hit policy nào, `DecisionResult.trace()` cũng liệt kê theo thứ tự từng luật đã được đánh giá và việc luật đó có khớp hay không. Với `FIRST`, vết thực thi dừng ở luật khớp đầu tiên. Vết thực thi không bao giờ chứa giá trị đầu vào, nên có thể lưu an toàn vào nhật ký kiểm toán.

## Phiên bản và ngày hiệu lực

Một quyết định thường có nhiều phiên bản trong vòng đời của nó. Hãy đăng ký tất cả với engine; engine chọn một phiên bản cho mỗi yêu cầu:

1. Nếu `DecisionRef` chỉ định một phiên bản (`.version("2")`), phiên bản đó được dùng, bất kể ngày. Dùng cách này để chạy lại hoặc điều tra một quyết định trong quá khứ.
2. Nếu không, engine dùng phiên bản **có hiệu lực vào** ngày nghiệp vụ: `DecisionRef.asOf(date)` nếu được truyền, nếu không thì là ngày hôm nay theo đồng hồ của engine.
3. Nếu không có phiên bản nào có hiệu lực vào ngày đó, việc đánh giá thất bại với `DecisionNotFoundException`.

Các luật nhận cùng ngày đó qua đầu vào ngầm định `businessDate`: `asOf(date)` nếu được truyền, nếu không thì là ngày hôm nay theo đồng hồ, kể cả khi đã chỉ định phiên bản.

**Phiên bản mới thay thế phiên bản trước.** Các phiên bản của một quyết định được sắp theo `effectiveFrom`; phiên bản không có `effectiveFrom` được coi là bắt đầu từ trước tới nay. Mỗi phiên bản có hiệu lực cho đến mốc sớm hơn trong hai mốc:

- `effectiveTo` của chính nó, nếu có;
- `effectiveFrom` của phiên bản kế tiếp.

Để thay đổi một chính sách từ một ngày, hãy thêm một tệp chứa phiên bản mới với `effectiveFrom` là ngày đó. **Không bao giờ sửa phiên bản trước.** Tệp của nó, và do đó mã băm nội dung của nó, giữ nguyên như khi công bố, nên mọi quyết định đã ghi nhận theo phiên bản đó vẫn đối chiếu được với nó:

```
v3  effectiveFrom 2026-11-01
v4  effectiveFrom 2027-03-01
```

Ngày 28 tháng 2 năm 2027 áp dụng v3; ngày 1 tháng 3 năm 2027 áp dụng v4. Cả hai đầu mút đều không tính ngày kết thúc, nên không có khoảng trống, cũng không có chồng lấn.

**`effectiveTo` dùng cho một ngày kết thúc cố định.** Hãy đặt nó khi một phiên bản phải dừng vào một ngày, dù có phiên bản kế tiếp hay không, ví dụ một chương trình khuyến mãi kết thúc ngày 31 tháng 12. Nếu phiên bản kế tiếp bắt đầu muộn hơn, sẽ không có phiên bản nào có hiệu lực ở giữa, và việc đánh giá vào những ngày đó thất bại với `DecisionNotFoundException`. Một `effectiveTo` bằng đúng `effectiveFrom` của phiên bản kế tiếp cũng hợp lệ: bảng mẫu `loan_eligibility.v2` kết thúc đúng ngày v3 bắt đầu.

**Mâu thuẫn bị từ chối.** Engine từ chối khởi tạo khi:

- hai phiên bản của cùng một quyết định có hiệu lực cùng một ngày, hoặc cả hai đều không có `effectiveFrom`, vì không phiên bản nào thay thế phiên bản kia;
- `effectiveTo` của một phiên bản nằm sau `effectiveFrom` của phiên bản kế tiếp, vì hai tệp mâu thuẫn nhau về phiên bản nào được áp dụng.

Do đó việc chọn phiên bản không bao giờ mơ hồ.

**Triển khai mọi phiên bản cùng nhau.** Vì một phiên bản có hiệu lực cho đến khi phiên bản kế tiếp bắt đầu, một lần triển khai thiếu phiên bản mới hơn sẽ tiếp tục áp dụng phiên bản cũ mà không báo lỗi. Hãy nạp mọi phiên bản của một quyết định từ cùng một nguồn, ví dụ một thư mục hay một kho lưu trữ.

**Khi chạy lại, hãy chỉ định phiên bản.** Một phiên bản được thêm sau với `effectiveFrom` nằm trong quá khứ sẽ làm thay đổi phiên bản mà một lần chạy lại theo ngày (`asOf`) chọn ra. Để tái lập chính xác một quyết định đã ghi nhận, hãy chỉ định phiên bản lấy từ bản ghi của nó: `DecisionRef.of(id).version(...)`. [Bản ghi quyết định](java-api.md#bản-ghi-quyết-định-và-kiểm-toán) lưu phiên bản chính xác của mọi lần đánh giá.

### Mã băm nội dung

Mọi kết quả đều báo cáo `ruleVersion()` dưới dạng `<version>@sha256:<hash>`, trong đó mã băm gồm 64 chữ số thập lục phân viết thường:

```
3@sha256:<64 hex digits>
```

Mã băm được tính trên dạng chuẩn tắc của bảng: JSON viết gọn, khoá được sắp xếp và bỏ qua các trường tuỳ chọn không có mặt. Vì vậy:

- định dạng lại tệp hay đổi thứ tự khoá **không** làm thay đổi mã băm;
- mọi thay đổi về ý nghĩa, kể cả một dòng mô tả, một biểu thức hay một ngày, **đều** làm thay đổi mã băm.

Hãy lưu `ruleVersion()` cùng với mỗi quyết định. Giá trị này chứng minh nội dung luật nào đã tạo ra quyết định, kể cả khi sau này có người sửa tệp mà không đổi nhãn `version`. `DecisionTableJson.write(table)` tạo ra dạng chuẩn tắc.

## Kiểm tra hợp lệ

`RuleStone.builder().build()` kiểm tra mọi bảng đã đăng ký và ném ra một `InvalidDecisionException` duy nhất liệt kê **tất cả** các lỗi. Mỗi lỗi có tiền tố `id@version` và vị trí, ví dụ `loan_eligibility@3 rules[R1].when: …`.

Các bước kiểm tra gồm:

| Nhóm | Bị từ chối |
|---|---|
| Tệp | JSON không hợp lệ, trường không xác định, khoá trùng lặp, thiếu trường bắt buộc, sai kiểu JSON, kiểu không xác định, hit policy không xác định, ngày không hợp lệ. |
| Bảng | `id` hoặc `version` không hợp lệ; `effectiveTo` không sau `effectiveFrom`; không có luật nào; đầu ra có kiểu struct; tên đầu vào hoặc tên trường không hợp lệ; một đầu vào tên là `businessDate`. |
| Tham số | `params` không phải là đối tượng có `set` và `values`; bộ tham số chưa được đăng ký, hoặc không có phiên bản được cố định; một tham số được khai báo mà một phiên bản bảng có thể dùng (mọi phiên bản đã đăng ký, hoặc phiên bản được cố định) không có, hoặc có với kiểu khác; tên tham số không phải định danh; kiểu struct; một đầu vào tên là `params`; biểu thức đọc `params.x` khi `x` không được khai báo, hoặc đọc `params` trong một bảng không khai báo tham số nào. |
| Bộ tham số | Mọi lỗi của một tệp `.params.json`, như với bảng; `id` hoặc `version` không hợp lệ; giá trị không khớp kiểu; các phiên bản của một bộ tham số được đăng ký hai lần, có hiệu lực cùng một ngày hoặc chồng lấn, như với bảng. Có tiền tố `parameter set id@version`. |
| Giá trị cục bộ | `let` không phải là đối tượng chứa các biểu thức; tên không phải định danh hoặc trùng với một đầu vào, `businessDate` hay `params`; vòng phụ thuộc, được báo một lần dạng `let.a: cycle: a -> b -> a`; biểu thức có lỗi cú pháp hoặc lỗi kiểu, hoặc có kiểu không phải kiểu của RuleStone (như `double`). Luật đọc một giá trị cục bộ bị từ chối không bị kiểm tra lại. |
| Luật | Trùng id luật; mã lý do không theo dạng `UPPER_SNAKE_CASE`; `then` đặt một đầu ra chưa khai báo. |
| Biểu thức | Lỗi cú pháp; biến hoặc trường không xác định; lỗi kiểu, kể cả mọi sự trộn lẫn giữa `decimal` và số dấu phẩy động, hoặc giữa `money` và số thông thường; `when` không trả về `bool`; `then` không trả về đúng kiểu của đầu ra. |
| Phiên bản | Cùng một `id@version` được đăng ký hai lần; hai phiên bản có hiệu lực cùng một ngày, hoặc cả hai đều không có `effectiveFrom`; một `effectiveTo` nằm sau `effectiveFrom` của phiên bản kế tiếp. |

Điều mà việc kiểm tra hợp lệ không phát hiện được là một luật đúng cú pháp nhưng sai về nghiệp vụ: ví dụ các dòng `UNIQUE` chỉ chồng lấn với một số đầu vào nhất định. Những lỗi đó được phát hiện bằng kiểm thử. Hãy viết kiểm thử đơn vị cho bảng quyết định giống như cho mã nguồn.
