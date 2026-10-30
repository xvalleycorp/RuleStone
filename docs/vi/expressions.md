---
source: expressions.md
sourceHash: 4a7f845f49641767fb40cb799ecfc0e1ca48411251d1ae905cf447ee3d37dedb
---

# Ngôn ngữ biểu thức

Các trường `when` và `then` của một luật là biểu thức viết bằng **[CEL](https://cel.dev)** (Common Expression Language), ngôn ngữ biểu thức được Kubernetes, Envoy và Google Cloud dùng để viết chính sách. RuleStone bổ sung đầu vào có kiểu, các kiểu chính xác `decimal` và `money`, kiểu `date` và đầu vào ngầm định `businessDate`.

CEL được chọn vì nó **an toàn ngay từ thiết kế**:

- Không có câu lệnh, vòng lặp, phép gán, I/O hay truy cập lớp Java: một biểu thức chỉ có thể tính một giá trị từ các đầu vào của nó.
- Mọi biểu thức đều kết thúc. Các thao tác trên danh sách có giới hạn (xem [Giới hạn](#giới-hạn)).
- Biểu thức được kiểm tra kiểu khi nạp luật, nên phần lớn lỗi không bao giờ lọt tới môi trường production.

Trang này trình bày những gì người viết luật cần biết. Toàn bộ ngôn ngữ được mô tả trong [định nghĩa ngôn ngữ CEL](https://github.com/google/cel-spec/blob/master/doc/langdef.md).

- [Giá trị và toán tử](#giá-trị-và-toán-tử)
- [Số thập phân](#số-thập-phân)
- [Tiền](#tiền)
- [Ngày](#ngày)
- [Ngày nghiệp vụ](#ngày-nghiệp-vụ)
- [Struct, danh sách và trường bị thiếu](#struct-danh-sách-và-trường-bị-thiếu)
- [Hàm tổng hợp](#hàm-tổng-hợp)
- [Văn bản](#văn-bản)
- [So khớp gần đúng](#so-khớp-gần-đúng)
- [Lỗi](#lỗi)
- [Giới hạn](#giới-hạn)
- [Tra cứu nhanh](#tra-cứu-nhanh)

## Giá trị và toán tử

| Loại | Ví dụ |
|---|---|
| Hằng | `true`, `false`, `42`, `-7`, `'RETAIL'`, `"RETAIL"`, `[1, 2, 3]`, `decimal('0.05')`, `money('1500000', 'VND')`, `date('2026-11-01')` |
| Đầu vào | `amount`, `applicant.age`, `customer.accounts[0].balance` |
| So sánh | `==`, `!=`, `<`, `<=`, `>`, `>=` |
| Logic | `&&`, `\|\|`, `!` |
| Số học | `+`, `-`, `*`, `/`, `%` |
| Điều kiện | `applicant.age >= 18 ? 'ADULT' : 'MINOR'` |
| Thuộc tập | `applicant.segment in ['RETAIL', 'SME']` |

Trong tệp JSON, biểu thức là một chuỗi JSON, nên hằng văn bản dùng dấu nháy đơn: `"when": "applicant.segment == 'SME'"`.

**Số nguyên** là 64 bit. Phép chia số nguyên bỏ phần lẻ (`7 / 2` bằng `3`) và tràn số là lỗi, không bao giờ âm thầm quay vòng. Với lãi suất và mọi giá trị có phần lẻ, hãy dùng `decimal`; với số tiền theo một loại tiền tệ, hãy dùng `money`.

**Luật không có số dấu phẩy động.** Một hằng như `0.5` là kiểu `double` của CEL, và RuleStone từ chối mọi biểu thức kết hợp `double` với `decimal`. Hãy viết `decimal('0.5')`.

## Số thập phân

`decimal` là số cơ số 10 chính xác, dựa trên `BigDecimal` của Java. `0.1 + 0.2` đúng bằng `0.3`.

### Tạo số thập phân

| Biểu thức | Giá trị |
|---|---|
| `decimal('1234.50')` | 1234.50 |
| `decimal(5)` | 5 |
| một đầu vào khai báo `"decimal"` | giá trị `BigDecimal` được truyền vào |

Viết hằng thập phân dưới dạng chuỗi, theo cách viết thông thường (không dùng ký pháp khoa học). Một chuỗi không phân tích được, như `decimal('abc')`, làm việc đánh giá thất bại.

### Số học và so sánh

`+`, `-`, `*`, `/`, `<`, `<=`, `>`, `>=` dùng được giữa hai số thập phân, và giữa một số thập phân với một `int`:

```
amount <= applicant.monthlyIncome * 12
fee + 2000
balance > 0
-amount
```

`==` và `!=` đòi hỏi **cả hai vế đều là decimal**. `income == 5` là lỗi kiểu; hãy viết `income == decimal(5)`. Phép so sánh bằng so sánh giá trị số, bỏ qua scale (số chữ số sau dấu thập phân): `decimal('1.0') == decimal('1.00')` là `true`, và điều này cũng đúng với `in` và các hàm trên danh sách.

### Phép chia

`/` giữ 34 chữ số có nghĩa và làm tròn half-even (IEEE 754 *decimal128*):

```
decimal(1) / 3     // 0.3333333333333333333333333333333333
```

Chia cho không làm việc đánh giá thất bại. Vì phép chia có thể tạo ra phần lẻ rất dài, hãy **làm tròn tường minh** trước khi hiển thị hoặc lưu một số tiền.

### Làm tròn

| Biểu thức | Cách làm tròn | Ví dụ → kết quả |
|---|---|---|
| `x.round(scale)` | HALF_EVEN (làm tròn kiểu ngân hàng) | `decimal('2.345').round(2)` → 2.34 |
| `x.round(scale, mode)` | theo chế độ được nêu | `decimal('2.345').round(2, 'HALF_UP')` → 2.35 |

`scale` là số chữ số sau dấu thập phân. Giá trị này có thể âm: `x.round(-3)` làm tròn đến hàng nghìn. Khi đó kết quả có scale âm, nên trong Java `toString()` in ra dạng ký pháp khoa học (`1.535E+6`); hãy dùng `toPlainString()` (`1535000`) để hiển thị. `mode` là một trong `HALF_EVEN`, `HALF_UP`, `HALF_DOWN`, `UP`, `DOWN`, `CEILING`, `FLOOR`, viết hoa hay thường đều được. Chế độ không xác định làm việc đánh giá thất bại.

Làm tròn tới bội số, ví dụ làm tròn xuống tới hàng triệu gần nhất:

```
(applicant.monthlyIncome * 12 / 1000000).round(0, 'FLOOR') * 1000000
```

### Các hàm khác

| Biểu thức | Kết quả |
|---|---|
| `x.abs()` | giá trị tuyệt đối |
| `min(a, b)`, `max(a, b)` | số nhỏ hơn / lớn hơn trong hai số thập phân |
| `string(x)` | văn bản theo cách viết thông thường, ví dụ `'1500.50'` |

## Tiền

`money` là một số tiền chính xác theo một loại tiền tệ [ISO 4217](https://www.iso.org/iso-4217-currency-codes.html): gồm số tiền kiểu `decimal` và mã tiền tệ. Trong Java, đó là `vn.io.dxa.rulestone.api.Money`. Hãy dùng kiểu này khi loại tiền tệ của số tiền có ý nghĩa, để không luật nào có thể vô tình cộng đồng với đô la.

### Tạo giá trị tiền

| Biểu thức | Giá trị |
|---|---|
| `money('1500000.50', 'VND')` | 1500000.50 VND |
| `money(decimal('12.5'), 'USD')` | 12.5 USD |
| `money(2000, 'VND')` | 2000 VND |
| một đầu vào khai báo `"money"` | giá trị `Money` được truyền vào |

Mã tiền tệ là mã ISO 4217 viết hoa. Mã không xác định hoặc số tiền không phân tích được làm việc đánh giá thất bại.

RuleStone chấp nhận một danh sách mã cố định, nên mọi phiên bản Java đều chấp nhận và làm tròn các mã như nhau: các mã hiện hành của ISO 4217 đến bản sửa đổi 180 (tháng 9 năm 2025), cùng các mã đã ngừng sử dụng mà Java vẫn chấp nhận từ lâu, như `DEM`, hoặc mới ngừng sử dụng gần đây, như `BGN` khi Bulgaria chuyển sang đồng euro. Trong Java, danh sách này là `vn.io.dxa.rulestone.api.Iso4217`.

### Số học và so sánh

| Biểu thức | Kết quả |
|---|---|
| `a + b`, `a - b` | money; hai vế cùng loại tiền tệ |
| `m * decimal('0.015')`, `m * 2`, `2 * m` | money; nhân với một decimal hoặc int, ở vế nào cũng được |
| `m / 3`, `m / decimal('0.5')` | money; chia cho một decimal hoặc int |
| `-m` | money đổi dấu |
| `a < b`, `a <= b`, `a > b`, `a >= b` | bool; hai vế cùng loại tiền tệ |
| `a == b`, `a != b` | bool; bằng nhau khi cùng loại tiền tệ và số tiền bằng nhau về giá trị |

**Không bao giờ trộn lẫn các loại tiền tệ.** `+`, `-`, các phép so sánh `< <= > >=`, `min` và `max` làm việc đánh giá thất bại khi hai vế khác loại tiền tệ, với lỗi như `money currencies differ: VND and USD`. RuleStone không quy đổi tiền tệ; hãy truyền vào số tiền đã quy đổi, hoặc viết luật riêng cho từng loại tiền tệ. Phép so sánh bằng không gây lỗi: số tiền khác loại tiền tệ đơn giản là không bằng nhau.

Tiền và số thông thường cũng không trộn lẫn được: `m + 2000` và `m > 1000000` là lỗi kiểu. Hãy viết `m + money(2000, m.currency())` hoặc `m > money(1000000, 'VND')`. Nhân tiền với tiền cũng là lỗi kiểu.

Phép so sánh bằng bỏ qua scale, giống như với số thập phân: `money('1.0', 'USD') == money('1.00', 'USD')` là `true`.

### Làm tròn

Các phép tính số học **không bao giờ làm tròn** tiền: kết quả giữ đủ độ chính xác, và phép chia giữ 34 chữ số có nghĩa như với số thập phân.

```
money('100', 'VND') / 3     // 33.33333333333333333333333333333333 VND
```

Hãy làm tròn tường minh tới đơn vị nhỏ nhất của loại tiền tệ trước khi hiển thị hoặc lưu một số tiền:

| Biểu thức | Cách làm tròn | Ví dụ → kết quả |
|---|---|---|
| `m.round()` | tới đơn vị nhỏ nhất của loại tiền tệ, HALF_EVEN | `money('1234.565', 'USD').round()` → 1234.56 USD |
| `m.round(mode)` | tới đơn vị nhỏ nhất của loại tiền tệ, theo chế độ được nêu | `money('1234.565', 'USD').round('HALF_UP')` → 1234.57 USD |

`mode` nhận các tên giống như với [số thập phân](#làm-tròn). Đơn vị nhỏ nhất lấy theo ISO 4217: 0 chữ số thập phân với `VND` và `JPY`, 2 với `USD` và `EUR`, 3 với `KWD`, nên `money('2.5', 'VND').round()` là `2 VND`. Một số mã, như `XAU` (vàng), không có đơn vị nhỏ nhất trong ISO 4217; làm tròn các mã này sẽ thất bại trừ khi bạn cấu hình. Để cấu hình hoặc ghi đè đơn vị nhỏ nhất của một loại tiền tệ, hãy dùng `RuleStone.builder().currencyScale("XAU", 4)` (xem [Java API](java-api.md#tạo-engine)).

Phí thanh toán bằng 0,05% của `payment.amount`, làm tròn tới đồng, tối thiểu 2.000 đồng:

```
max(money(2000, 'VND'), (payment.amount * decimal('0.0005')).round())
```

### Các hàm khác

| Biểu thức | Kết quả |
|---|---|
| `m.amount()` | số tiền, kiểu `decimal` |
| `m.currency()` | mã tiền tệ, kiểu `string`, ví dụ `'VND'` |
| `m.abs()` | giá trị tuyệt đối |
| `min(a, b)`, `max(a, b)` | số tiền nhỏ hơn / lớn hơn trong hai số tiền cùng loại tiền tệ |
| `string(m)` | số tiền theo cách viết thông thường kèm mã, ví dụ `'1500000 VND'` |

## Ngày

`date` là một ngày theo lịch, không có giờ và không có múi giờ, chẳng hạn ngày nghiệp vụ hay ngày sinh. Trong Java, đó là `java.time.LocalDate`. (Kiểu `timestamp` của CEL không được dùng cho ngày, nên múi giờ không bao giờ làm kết quả lệch đi một ngày.)

| Biểu thức | Giá trị |
|---|---|
| `date('2026-11-01')` | ngày 1 tháng 11 năm 2026 |
| một đầu vào khai báo `"date"` | giá trị `LocalDate` được truyền vào |

Viết ngày theo định dạng ISO, `yyyy-MM-dd`. Mọi định dạng khác, như `date('01/11/2026')`, hoặc một ngày không tồn tại như `date('2026-02-30')`, đều làm việc đánh giá thất bại.

Ngày so sánh được bằng `==`, `!=`, `<`, `<=`, `>` và `>=`; ngày muộn hơn thì lớn hơn.

| Biểu thức | Kết quả | Ví dụ → kết quả |
|---|---|---|
| `d.plusDays(n)` | ngày sau `n` ngày; `n` có thể âm | `date('2026-11-01').plusDays(30)` → 2026-12-01 |
| `d.plusMonths(n)` | ngày sau `n` tháng, giới hạn ở ngày cuối tháng | `date('2026-01-31').plusMonths(1)` → 2026-02-28 |
| `d.plusYears(n)` | ngày sau `n` năm, giới hạn ở ngày cuối tháng | `date('2028-02-29').plusYears(1)` → 2029-02-28 |
| `d.year()`, `d.month()`, `d.day()` | `int`; tháng từ 1 đến 12 | `date('2026-11-01').month()` → 11 |
| `d.dayOfWeek()` | `int`, 1 = thứ Hai … 7 = Chủ nhật | `date('2026-11-01').dayOfWeek()` → 7 |
| `daysBetween(a, b)` | số ngày trọn vẹn từ `a` đến `b` | `daysBetween(date('2026-10-01'), date('2026-11-01'))` → 31 |
| `monthsBetween(a, b)` | số tháng trọn vẹn từ `a` đến `b` | `monthsBetween(date('2026-01-31'), date('2026-02-28'))` → 0 |
| `yearsBetween(a, b)` | số năm trọn vẹn từ `a` đến `b` | `yearsBetween(date('2008-11-02'), date('2026-11-01'))` → 17 |
| `string(d)` | văn bản dạng ISO | `string(date('2026-11-01'))` → `'2026-11-01'` |

Các hàm `…Between` chỉ đếm đơn vị trọn vẹn, giống `java.time.temporal.ChronoUnit.between`, và cho giá trị âm khi `b` trước `a`. Nhờ vậy `yearsBetween(birthDate, day)` chính là tuổi của một người vào ngày `day`.

## Ngày nghiệp vụ

Mọi quyết định đều có một đầu vào ngầm định, `businessDate`, kiểu `date`. Đó là ngày mà quyết định được đưa ra cho ngày đó:

- `DecisionRef.asOf(date)` khi yêu cầu có truyền ngày;
- nếu không thì là ngày hôm nay theo đồng hồ của engine (`RuleStone.builder().clock(...)`), kể cả khi yêu cầu chỉ định một phiên bản.

Đây cũng chính là ngày dùng để [chọn phiên bản luật](decision-tables.md#phiên-bản-và-ngày-hiệu-lực). Hãy dùng giá trị này, thay vì một ngày do bên gọi truyền vào, mỗi khi luật cần "hôm nay":

```
yearsBetween(applicant.birthDate, businessDate) >= 18
businessDate.dayOfWeek() >= 6                       // Saturday or Sunday
loan.maturityDate <= businessDate.plusMonths(3)
```

Biểu thức không tự đọc được đồng hồ, nên một quyết định được đánh giá với `asOf` luôn cho cùng một kết quả mỗi lần chạy lại.

Tên này được dành riêng. Một bảng khai báo đầu vào tên `businessDate` bị từ chối khi nạp, và truyền một fact tên `businessDate` làm việc đánh giá thất bại, nên bên gọi không thể khiến các luật và việc chọn phiên bản hiểu khác nhau về ngày. Đầu vào ngầm định không thuộc bảng, nên không ảnh hưởng tới [mã băm nội dung](decision-tables.md#mã-băm-nội-dung).

## Struct, danh sách và trường bị thiếu

Đầu vào khai báo dạng struct được truy cập bằng `.`; danh sách được truy cập bằng `[index]` và các hàm trên danh sách dưới đây.

```
applicant.age >= 18
customer.accounts[0].balance
customer.accounts.exists(a, a.type == 'SAVINGS' && a.balance >= decimal(100000000))
customer.accounts.all(a, a.balance >= 0)
customer.accounts.filter(a, a.type == 'LOAN').size() > 2
customer.accounts.map(a, a.type)
```

| Hàm | Kết quả |
|---|---|
| `list.exists(x, cond)` | `true` nếu có phần tử thoả `cond` |
| `list.all(x, cond)` | `true` nếu mọi phần tử đều thoả `cond` |
| `list.exists_one(x, cond)` | `true` nếu đúng một phần tử thoả `cond` |
| `list.filter(x, cond)` | các phần tử thoả `cond` |
| `list.map(x, expr)` | `expr` áp dụng cho từng phần tử |
| `size(list)` hoặc `list.size()` | số phần tử |
| `value in list` | kiểm tra thuộc danh sách |

Tên trường được đối chiếu với kiểu đã khai báo khi nạp luật: `applicant.agee` được báo là `undefined field 'agee'`.

**Trường bị thiếu.** Bên gọi có thể bỏ qua một trường của struct hoặc truyền `null`. Khi đó trường *chưa được đặt*. Đọc một trường chưa đặt làm việc đánh giá thất bại, nên khi một trường là tuỳ chọn, hãy kiểm tra bằng `has()`:

```
has(applicant.segment) && applicant.segment == 'VIP'
```

`has()` nhận một phép chọn trường (`has(applicant.segment)`), không nhận tên đầu vào cấp cao nhất. Đầu vào cấp cao nhất luôn là bắt buộc.

## Hàm tổng hợp

Danh sách các phần tử `int`, `decimal` và `money` có thể được tính tổng, tìm phần tử nhỏ nhất, lớn nhất và giá trị trung bình. Các hàm này là phương thức của danh sách:

| Biểu thức | Kết quả | Ví dụ → kết quả |
|---|---|---|
| `list.sum()` | Tổng. `0` với `list<int>` rỗng, `decimal('0')` với `list<decimal>` rỗng. `list<money>` rỗng không có loại tiền tệ nên làm việc đánh giá thất bại: hãy dùng `sum(zero)`. | `[3, 1, 2].sum()` → 6 |
| `list.sum(zero)` | Tổng, hoặc `zero` khi danh sách rỗng. `zero` có kiểu của phần tử; với tiền, nó còn xác định loại tiền tệ, và mọi phần tử phải thuộc loại tiền tệ đó. | `[1].filter(x, x > 5).sum(0)` → 0 |
| `list.min()`, `list.max()` | Phần tử nhỏ nhất và lớn nhất; nếu có nhiều phần tử bằng nhau, lấy phần tử đầu tiên. Danh sách rỗng làm việc đánh giá thất bại. | `[money(100, 'VND'), money(50, 'VND')].min()` → 50 VND |
| `list.avg()` | Giá trị trung bình: `decimal` với danh sách `int` và `decimal`, `money` với danh sách tiền, chia như phép `/` (34 chữ số có nghĩa, làm tròn half-even). Danh sách rỗng làm việc đánh giá thất bại. | `[1, 2].avg()` → 1.5 |

Các hàm này kết hợp được với `map` và `filter`, nên để tổng hợp một danh sách struct, trước hết hãy dùng `map` để lấy trường cần tính:

```
loans.map(l, l.outstanding).sum(money(0, 'VND')) > money(500000000, 'VND')
transactions.filter(t, t.channel == 'ATM').map(t, t.amount).max() > money(50000000, 'VND')
scores.avg() >= decimal('0.7')
```

`max()` thất bại với danh sách rỗng, nên khi danh sách có thể rỗng hãy kiểm tra trước, ví dụ bằng `transactions.exists(t, t.channel == 'ATM') && …`. `sum(zero)` không bao giờ cần kiểm tra như vậy.

- **Không bao giờ trộn loại tiền tệ.** Các phần tử tiền thuộc những loại tiền tệ khác nhau làm việc đánh giá thất bại, giống phép `+`, với lỗi như `money currencies differ: VND and USD`.
- **Tổng số nguyên là chính xác.** Tổng của `int` chỉ thất bại khi kết quả không vừa 64 bit. `avg()` của danh sách `int` không bao giờ bị tràn.
- **Mỗi phần tử được tính vào [giới hạn số vòng lặp](#giới-hạn)**, như các hàm khác trên danh sách.
- `min(a, b)` và `max(a, b)` hai tham số không thay đổi: `min(a, b)` so sánh hai giá trị, còn `list.min()` so sánh các phần tử của một danh sách.
- Không có hàm `count`: `list.filter(x, cond).size()` đã đếm được.

## Văn bản

| Biểu thức | Kết quả |
|---|---|
| `s.startsWith('VN')`, `s.endsWith('X')`, `s.contains('abc')` | `bool` |
| `s.matches('^[0-9]{12}$')` | `bool`; [cú pháp RE2](https://github.com/google/re2/wiki/Syntax) |
| `size(s)` | số ký tự |
| `a + b` | nối chuỗi |
| `string(42)`, `int('42')` | chuyển đổi kiểu |

Giá trị enum truyền từ Java được nhận dưới dạng `name()` của nó, nên hãy so sánh với văn bản: `applicant.segment == 'RETAIL'`.

## So khớp gần đúng

Để sàng lọc theo tên, RuleStone cung cấp `normalizeName(s)`, `similarity(a, b)`, `nameSimilarity(a, b)`, `levenshtein(a, b)` và `maxNameSimilarity(name, names)`. Điểm số là giá trị `decimal` từ 0 đến 1 với bốn chữ số thập phân; tên được so sánh không phân biệt dấu (kể cả chữ `đ` tiếng Việt), chữ hoa chữ thường hay dấu câu.

```
maxNameSimilarity(customer.fullName, watchlist) >= decimal('0.92')
```

Xem [So khớp gần đúng](fuzzy-matching.md) để biết về thuật toán, ngưỡng, giới hạn và các hạn chế.

## Lỗi

RuleStone phân biệt lỗi phát hiện khi **nạp** luật với lỗi phát hiện khi **đánh giá** luật.

Phát hiện khi nạp, và được báo bởi `RuleStone.builder().build()`:

- lỗi cú pháp;
- đầu vào và trường không xác định;
- lỗi kiểu, như so sánh `decimal` với `double`, so sánh `string` với `int`, hay cộng `money` với `decimal`;
- `when` không có kiểu `bool`, hoặc `then` không khớp kiểu của đầu ra.

Phát hiện khi đánh giá, và được báo dưới dạng `DecisionEvaluationException` kèm id của luật:

- chia cho không;
- tràn số nguyên;
- đọc một trường chưa đặt;
- tham số không phân tích được của `decimal('…')`, `money('…', '…')` hoặc `date('…')`, mã tiền tệ không xác định hoặc chế độ làm tròn không xác định;
- hai số tiền khác loại tiền tệ được kết hợp bằng `+`, `-`, `<`, `<=`, `>`, `>=`, `min` hoặc `max`, hoặc nằm chung một danh sách truyền cho `sum`, `min`, `max` hay `avg`;
- `min()`, `max()` hoặc `avg()` của danh sách rỗng, `sum()` của danh sách tiền rỗng, và tổng `int` vượt quá 64 bit;
- làm tròn tiền thuộc loại tiền tệ không có đơn vị nhỏ nhất và chưa cấu hình `currencyScale`;
- tính toán ngày vượt ngoài phạm vi năm được hỗ trợ;
- vượt quá giới hạn số vòng lặp.

Thông báo lỗi nêu tên đầu vào, trường, luật và mã tiền tệ, không bao giờ nêu giá trị đầu vào hay số tiền. Điều này cũng áp dụng cho các hàm chuẩn: `int('x')` thất bại với `int() argument is not a valid integer`, và chỉ số vượt quá cuối danh sách thất bại với `list index out of bounds`.

Một lỗi khi đánh giá **làm thất bại toàn bộ quyết định**. RuleStone không bỏ qua luật đó rồi chạy tiếp, vì một luật từ chối bị bỏ qua có thể biến thành một quyết định phê duyệt. Hãy xử lý lỗi này như mọi lỗi hệ thống khác, ví dụ chuyển hồ sơ sang xem xét thủ công.

## Giới hạn

Để bảo vệ engine khỏi một luật chạy mất kiểm soát, các hàm trên danh sách (`exists`, `all`, `map`, `sum`, …) mặc định chỉ được thực hiện tối đa **10.000 vòng lặp**. Mỗi phần tử mà một hàm trên danh sách duyệt qua được tính là một vòng lặp, và số đếm này dùng chung cho mọi hàm trên danh sách trong biểu thức: `loans.map(l, l.outstanding).sum()` tốn hai vòng lặp cho mỗi khoản vay. Vượt quá giới hạn này làm việc đánh giá thất bại. Thay đổi giới hạn bằng `RuleStone.builder().maxIterations(n)`.

## Tra cứu nhanh

```
// comparison and logic
applicant.age >= 18 && !applicant.blacklisted
applicant.segment in ['RETAIL', 'SME']

// decimals
amount <= applicant.monthlyIncome * 12
applicant.monthlyDebt / applicant.monthlyIncome > decimal('0.5')
(transfer.amount * decimal('0.0005')).round(0, 'HALF_UP')
max(decimal(2000), fee)

// money
payment.amount > money(1000000000, 'VND')
(payment.amount * decimal('0.0005')).round()
money('1500000', 'VND') + money(2000, 'VND')
loan.balance.amount() > decimal(0)

// dates and the business date
yearsBetween(applicant.birthDate, businessDate) >= 18
businessDate >= date('2026-11-01')
businessDate.plusDays(30)
daysBetween(invoice.dueDate, businessDate) > 90

// optional field
has(applicant.segment) && applicant.segment == 'VIP'

// lists
customer.accounts.exists(a, a.balance < 0)
customer.accounts.filter(a, a.type == 'LOAN').size()

// totals, minimum, maximum and mean
loans.map(l, l.outstanding).sum(money(0, 'VND'))
transactions.map(t, t.amount).max() > money(50000000, 'VND')
scores.avg() >= decimal('0.7')

// text
applicant.idNumber.matches('^[0-9]{12}$')

// fuzzy name matching
maxNameSimilarity(customer.fullName, watchlist) >= decimal('0.92')
watchlist.exists(w, nameSimilarity(w, customer.fullName) >= decimal('0.92'))

// conditional output
amount > decimal(1000000000) ? 'SENIOR_APPROVAL' : 'STANDARD'
```
