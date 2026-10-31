---
source: parameters.md
sourceHash: 89bea269d971e3d5e071c5ac92db3a214bd04e0e5979836de7021758420a02bc
---

# Tham số, hàm tổng hợp và giá trị cục bộ

> Trạng thái: **đã có** · Cập nhật lần cuối: 2026-10-31
>
> Cả ba bổ sung đã có trong cả hai bộ máy luật. Tài liệu tham chiếu nằm ở [Hàm tổng hợp](expressions.md#hàm-tổng-hợp), [Tham số](decision-tables.md#tham-số) và [Giá trị cục bộ](decision-tables.md#giá-trị-cục-bộ). Trang này giữ lại thiết kế và lý do của nó.

Luật thường cần những giá trị không thuộc về logic của chúng: hạn mức tỷ lệ nợ trên thu nhập, ngưỡng báo cáo giao dịch tiền mặt, tuổi tối thiểu. Luật cũng cần tính tổng trên danh sách, và cần các kết quả trung gian dùng chung cho nhiều luật. Trang này mô tả ba bổ sung:

| | Bổ sung | Ví dụ |
|---|---|---|
| A | [Hàm tổng hợp trên danh sách](#a-hàm-tổng-hợp) | `loans.map(l, l.outstanding).sum()` |
| B | [Bộ tham số](#b-bộ-tham-số): giá trị nghiệp vụ tách khỏi luật, có phiên bản và ngày hiệu lực | `params.maxDti` |
| C | [Giá trị cục bộ trong bảng](#c-giá-trị-cục-bộ-trong-bảng) | `"let": { "dti": "applicant.monthlyDebt / applicant.monthlyIncome" }` |

Những gì đã làm được và những gì vẫn nằm ngoài phạm vi được tóm tắt [ở cuối trang](#những-gì-đã-có-và-những-gì-nằm-ngoài-phạm-vi).

## Nguyên tắc

1. **Bên gọi không chọn ngưỡng.** Những giá trị do chính sách của ngân hàng quy định, như hạn mức và ngưỡng, không được đến từ đầu vào của ứng dụng gọi, vì khi đó ứng dụng, chứ không phải chính sách đã được duyệt, là bên quyết định. Chúng nằm trong các file đi qua cùng quy trình xem xét như luật.
2. **Mọi quyết định đều tái tạo được.** Bản ghi quyết định nêu tên mọi thứ đã quyết định kết quả: phiên bản luật, và từ nay cả phiên bản bộ tham số.
3. **Lỗi được phát hiện khi nạp luật.** Tên và kiểu của tham số và giá trị cục bộ được kiểm tra lúc build, không phải ở yêu cầu đầu tiên dùng đến chúng.
4. **Hai bộ máy luật cho kết quả như nhau.** Bộ máy luật Java và bộ máy luật trên trình duyệt triển khai mọi bổ sung giống hệt nhau, được kiểm tra bằng bộ kiểm thử tương thích.

## A. Hàm tổng hợp

> Đã có. Tài liệu tham chiếu: [Hàm tổng hợp](expressions.md#hàm-tổng-hợp).

Danh sách truyền vào làm đầu vào hiện đã đếm và kiểm tra được (`filter`, `exists`, `all`, `size()`), nhưng chưa cộng tổng được. Bốn hàm được thêm cho danh sách `int`, `decimal` và `money`:

| Biểu thức | Kết quả |
|---|---|
| `list.sum()` | Tổng. `0` với `list<int>` rỗng, `decimal('0')` với `list<decimal>` rỗng. `list<money>` rỗng không có loại tiền nên thất bại; hãy dùng `sum(zero)`. |
| `list.sum(zero)` | Tổng, hoặc `zero` khi danh sách rỗng. Với money, `zero` còn xác định loại tiền: mọi phần tử phải cùng loại tiền đó. |
| `list.min()`, `list.max()` | Phần tử nhỏ nhất và lớn nhất. Danh sách rỗng làm việc đánh giá thất bại. |
| `list.avg()` | Giá trị trung bình: `decimal` với danh sách `int` và `decimal`, `money` với danh sách money, chia theo cách `/` chia (34 chữ số có nghĩa, làm tròn half-even). Danh sách rỗng làm việc đánh giá thất bại. |

Các hàm này kết hợp với `map` và `filter`:

```
loans.map(l, l.outstanding).sum(money(0, 'VND')) > applicant.income * 12
transactions.filter(t, t.channel == 'ATM').map(t, t.amount).max() > money(50000000, 'VND')
scores.avg() >= decimal('0.7')
```

Quy tắc:

- Các phần tử money khác loại tiền làm việc đánh giá thất bại, giống như `+`. RuleStone không bao giờ quy đổi tiền tệ.
- Tổng `int` vượt quá 64 bit làm việc đánh giá thất bại.
- Mỗi phần tử được tính vào giới hạn số vòng lặp, như các hàm danh sách khác (xem [Giới hạn](expressions.md#giới-hạn)).
- Các hàm là phương thức của danh sách, nên không trùng với `min(a, b)` và `max(a, b)` hai tham số đã có.
- Không thêm `count`: `list.filter(x, cond).size()` đã đếm được.

## B. Bộ tham số

> Đã có. Tài liệu tham chiếu: [Tham số](decision-tables.md#tham-số); Java API: [Bộ tham số](java-api.md#bộ-tham-số).

Một bộ tham số là một file riêng, `<id>.params.json`, chứa các giá trị nghiệp vụ có tên và có kiểu:

```json
{
  "id": "retail_credit",
  "version": "7",
  "description": "Retail credit policy limits, approved by the Credit Committee on 2026-11-20",
  "effectiveFrom": "2026-12-01",
  "values": {
    "maxDti": { "type": "decimal", "value": "0.45" },
    "minAge": { "type": "int", "value": 18 },
    "maxUnsecured": { "type": "money", "value": "500000000 VND" },
    "blockedSegments": { "type": ["string"], "value": ["SANCTIONED", "DORMANT"] }
  }
}
```

Một bảng nêu tên bộ tham số và khai báo các tham số nó đọc, kèm kiểu, giống như khai báo đầu vào:

```json
{
  "id": "loan_eligibility",
  "version": "4",
  "params": { "set": "retail_credit", "values": { "maxDti": "decimal", "minAge": "int" } },
  "rules": [
    { "id": "R1", "when": "applicant.age < params.minAge", "then": { "decision": "'REJECT'" } },
    { "id": "R3", "when": "applicant.monthlyDebt / applicant.monthlyIncome > params.maxDti", "then": { "decision": "'REJECT'" } }
  ]
}
```

### Phiên bản và cách chọn

Bộ tham số có phiên bản và ngày hiệu lực đúng như bảng, và được chọn theo cùng một cách: dùng phiên bản đang có hiệu lực vào ngày nghiệp vụ, và một phiên bản mới thay thế phiên bản trước mà không cần sửa phiên bản trước (xem [Phiên bản và ngày hiệu lực](decision-tables.md#phiên-bản-và-ngày-hiệu-lực)). Để đổi một hạn mức từ một ngày nào đó, hãy thêm một phiên bản của bộ tham số; bảng không thay đổi.

Một bảng có thể ghim một phiên bản cho các trường hợp đặc biệt: `"params": { "set": "retail_credit", "version": "7", … }`.

Kiểu được viết như với đầu vào của bảng, nên danh sách chuỗi là `["string"]`. Tham số không có trường con: không cho phép kiểu struct. Id bộ tham số là một không gian tên riêng, tách khỏi id của quyết định.

### Kiểm tra khi nạp

`build()` thất bại nếu:

- bộ tham số chưa được đăng ký, hoặc có phiên bản của nó không khai báo một tham số mà bảng đọc, với cùng kiểu;
- một giá trị không khớp với kiểu đã khai báo, ví dụ `"0.45"` khai báo là `int`, hoặc một loại tiền không tồn tại;
- bảng đọc `params.x` mà không khai báo `x`, hoặc đọc `params` mà không khai báo tham số nào;
- bảng có đầu vào tên `params` và đồng thời khai báo tham số.

Một phiên bản bộ tham số bị từ chối, như một bảng, nếu tệp của nó sai định dạng, giá trị không khớp kiểu, hoặc ngày của nó mâu thuẫn với một phiên bản khác của cùng bộ tham số.

Việc kiểm tra áp dụng cho **mọi phiên bản đã đăng ký** của bộ tham số, như với các nút bảng không ghim phiên bản trong luồng quyết định. Vì vậy một lần triển khai mà lẽ ra sẽ thất bại vào một ngày nghiệp vụ nào đó thì thất bại ngay khi khởi động.

### Hash và bản ghi

- Hash của một bảng bao gồm phần khai báo `params` của nó (mã bộ tham số, tên và kiểu), không bao gồm các giá trị, nên đổi một giá trị không làm đổi hash của luật nào.
- Một bộ tham số có hash nội dung riêng, tính theo cùng dạng chuẩn hoá như bảng: `7@sha256:…`.
- Bản ghi quyết định có thêm trường `params`, liệt kê từng bộ tham số đã dùng với phiên bản chính xác:

```json
"params": [ { "set": "retail_credit", "version": "7@sha256:<64 hex digits>" } ]
```

Để tái tạo một quyết định, lần replay nêu tên cả phiên bản luật lẫn phiên bản bộ tham số lấy từ bản ghi: `DecisionRef.of(id).version("4").params("retail_credit", "7")`. Một bảng ghim phiên bản của bộ tham số thì giữ phiên bản đó. Giá trị tham số không bao giờ xuất hiện trong bản ghi, cũng không có trong `factsHash`; phiên bản là đủ để xác định chúng. Bản ghi bỏ trường `params` khi không dùng bộ tham số nào, nên bản ghi của các bảng không có tham số không thay đổi.

### Luồng quyết định

Một luồng có thể khai báo `params` theo cùng cách; khi đó các biểu thức của luồng (`when`, `derive`, `facts`, `end` và `features` của nút mô hình) đọc được `params.x`. Một nút bảng trong luồng dùng các tham số mà bảng đó khai báo, lấy từ phiên bản có hiệu lực vào cùng ngày nghiệp vụ. Bản ghi liệt kê mỗi bộ tham số mà luồng và các bảng của nó đã dùng, mỗi bộ một lần.

### Thay đổi giá trị khi đang vận hành

Bộ máy luật là bất biến. Phiên bản tham số mới có hiệu lực khi ứng dụng dựng một bộ máy luật mới từ nguồn luật của nó, đúng như với phiên bản luật mới. Nhờ ngày hiệu lực, một thay đổi có thể được triển khai trước và có hiệu lực lúc nửa đêm mà không cần khởi động lại. Một mô-đun quản trị sau này (maker-checker) sẽ duyệt bộ tham số giống như duyệt luật.

## C. Giá trị cục bộ trong bảng

> Đã có. Tài liệu tham chiếu: [Giá trị cục bộ](decision-tables.md#giá-trị-cục-bộ).

Một bảng có thể đặt tên cho các kết quả trung gian trong `let`, và mọi luật đều đọc được chúng:

```json
"let": {
  "dti": "applicant.monthlyDebt / applicant.monthlyIncome",
  "annualIncome": "applicant.monthlyIncome * 12"
},
"rules": [
  { "id": "R3", "when": "dti > params.maxDti", "then": { "decision": "'REJECT'" } },
  { "id": "R4", "when": "amount <= annualIncome", "then": { "decision": "'APPROVE'" } }
]
```

- **Kiểu được suy ra** từ các biểu thức, vốn được kiểm tra kiểu khi nạp bảng.
- **Một giá trị cục bộ có thể dùng giá trị cục bộ khác.** Dạng chuẩn hoá sắp xếp các khoá, nên thứ tự trong file không thể có ý nghĩa; quan hệ phụ thuộc được xác định từ các biểu thức, và vòng lặp phụ thuộc bị từ chối.
- **Giá trị được tính khi được dùng lần đầu**, nhiều nhất một lần mỗi lần đánh giá. Một giá trị cục bộ không luật nào dùng tới thì không bao giờ được tính, nên không thể làm một lần đánh giá thất bại, y như khi biểu thức của nó được viết thẳng vào các luật.
- **Tên** phải là định danh hợp lệ và không được trùng với đầu vào, `params` hay `businessDate`.
- Giá trị cục bộ không được ghi vào bản ghi: chúng suy ra được từ đầu vào và phiên bản luật.
- Hash của bảng bao gồm `let`.

Trong một luồng, các nút `derive` đã làm việc này giữa các nút; `let` dành cho các phép tính dùng chung giữa các luật của một bảng.

## Những gì đã có và những gì nằm ngoài phạm vi

| Nhu cầu | Cách làm |
|---|---|
| Giá trị thay đổi theo từng yêu cầu | Đầu vào, truyền vào mỗi lần đánh giá. |
| Phép tính | Phép tính CEL trong mọi biểu thức; `let` trong bảng; nút `derive` trong [luồng quyết định](flows.md). |
| Đếm và kiểm tra phần tử danh sách | `size()`, `filter`, `exists`, `all`, `exists_one`. |
| Tổng, nhỏ nhất, lớn nhất và trung bình của danh sách | Hàm tổng hợp. |
| Ngưỡng và hạn mức của chính sách | Bộ tham số. |
| Dữ liệu tra cứu trong lúc đánh giá, như số dư lấy từ core banking | Dự kiến làm dưới dạng `FactResolver`, cùng với replay, vì mọi giá trị lấy về phải được ghi lại để tái tạo quyết định. Trong lúc chờ, bên gọi tự lấy dữ liệu và truyền vào làm đầu vào. |
| Tổng hợp theo cửa sổ thời gian, như tổng giao dịch trong 24 giờ | **Nằm ngoài phạm vi.** RuleStone không giữ trạng thái giữa các lần đánh giá. Hãy tính chúng trong một hệ thống xử lý luồng dữ liệu hoặc một kho dữ liệu như Flink hay Valkey, rồi truyền kết quả vào làm đầu vào. |

## Kế hoạch triển khai

Mỗi bước là một thay đổi riêng, được triển khai trong cả hai bộ máy luật kèm dữ liệu kiểm thử tương thích, tài liệu tiếng Anh và tiếng Việt, và kiểm thử.

1. **Hàm tổng hợp.** Đã có.
2. **Giá trị cục bộ trong bảng**, gồm cả lưới luật và trình dựng điều kiện của trình soạn thảo trên trình duyệt, vốn phải hiển thị các tên trong `let`. Đã có.
3. **Bộ tham số**: định dạng file, cách chọn phiên bản, kiểm tra khi nạp, trường `params` của bản ghi, hỗ trợ trong `rulestone-test` (ca kiểm thử có thể nêu tên một phiên bản bộ tham số), và SDK trình soạn thảo (thẻ Tham số trong trình soạn thảo bảng, và gợi ý `params.` trong trình dựng điều kiện). Đã có; xem ví dụ [loan_eligibility v4](examples.md#xét-điều-kiện-vay-thứ-tự-ưu-tiên-và-phiên-bản).
