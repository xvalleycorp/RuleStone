---
source: decision-tables.md
sourceHash: 37fa184ea65701ab8b5e5f3cc6377b39bc77a4cb31a3025ca623318f2cf41189
---

# Bảng quyết định

Bảng quyết định biến các đầu vào có kiểu thành các đầu ra có kiểu, dựa trên một danh sách luật có thứ tự. Trang này là tài liệu tham khảo cho định dạng tệp `.stone.json`, và cho cách các bảng được đánh phiên bản, được chọn và được đánh giá.

- [Định dạng tệp](#định-dạng-tệp)
- [Kiểu dữ liệu](#kiểu-dữ-liệu)
- [Luật](#luật)
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
| `effectiveTo` | không | Ngày đầu tiên phiên bản này **không còn** hiệu lực, dạng `yyyy-MM-dd`, không tính ngày đó. Bỏ trống nghĩa là "cho đến khi có thông báo mới". Phải sau `effectiveFrom`. |
| `hitPolicy` | có | `FIRST`, `UNIQUE` hoặc `COLLECT`. Xem [Hit policy](#hit-policy). |
| `inputs` | có | Đối tượng ánh xạ tên mỗi đầu vào tới [kiểu](#kiểu-dữ-liệu) của nó. Có thể rỗng. |
| `outputs` | có | Đối tượng ánh xạ tên mỗi đầu ra tới [kiểu](#kiểu-dữ-liệu) của nó. Có thể rỗng, ví dụ với một bảng chỉ trả về mã lý do. |
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

Một phiên bản có hiệu lực vào ngày `d` khi `effectiveFrom <= d < effectiveTo`. Vì ngày kết thúc không được tính, các phiên bản liên tiếp dùng chung một ngày ranh giới:

```
v2  effectiveFrom 2026-01-01  effectiveTo 2026-11-01
v3  effectiveFrom 2026-11-01
```

Ngày 31 tháng 10 năm 2026 áp dụng v2; ngày 1 tháng 11 năm 2026 áp dụng v3. Không có khoảng trống, cũng không có chồng lấn.

**Các phiên bản chồng lấn bị từ chối.** Nếu hai phiên bản của cùng một quyết định có thể cùng có hiệu lực vào một ngày nào đó, engine từ chối khởi tạo. Do đó việc chọn phiên bản không bao giờ mơ hồ.

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
| Luật | Trùng id luật; mã lý do không theo dạng `UPPER_SNAKE_CASE`; `then` đặt một đầu ra chưa khai báo. |
| Biểu thức | Lỗi cú pháp; biến hoặc trường không xác định; lỗi kiểu, kể cả mọi sự trộn lẫn giữa `decimal` và số dấu phẩy động, hoặc giữa `money` và số thông thường; `when` không trả về `bool`; `then` không trả về đúng kiểu của đầu ra. |
| Phiên bản | Cùng một `id@version` được đăng ký hai lần; các khoảng thời gian hiệu lực chồng lấn. |

Điều mà việc kiểm tra hợp lệ không phát hiện được là một luật đúng cú pháp nhưng sai về nghiệp vụ: ví dụ các dòng `UNIQUE` chỉ chồng lấn với một số đầu vào nhất định. Những lỗi đó được phát hiện bằng kiểm thử. Hãy viết kiểm thử đơn vị cho bảng quyết định giống như cho mã nguồn.
