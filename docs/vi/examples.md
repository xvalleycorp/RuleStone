---
source: examples.md
sourceHash: da8b452ada0b02595f9df941a44adadfe84ba39d28cd7faf71d51cf989cb159c
---

# Ví dụ

Thư mục [`examples/`](../../examples/) chứa các bảng quyết định hoàn chỉnh cho những bài toán ngân hàng thường gặp. Mỗi bảng đều được bộ kiểm thử của dự án nạp và đánh giá, nên các kết quả nêu ở đây được kiểm tra trong mọi lần build. Các ngưỡng, điểm số và lãi suất là số liệu giả định để minh hoạ; số tiền tính bằng VND.

| Tệp | Bài toán | Minh hoạ |
|---|---|---|
| [`loan_eligibility.v2.stone.json`](../../examples/loan_eligibility.v2.stone.json), [`loan_eligibility.v3.stone.json`](../../examples/loan_eligibility.v3.stone.json) | Xét điều kiện vay tín chấp | `FIRST`, mã lý do, tỷ lệ và làm tròn với decimal, hai phiên bản có ngày hiệu lực |
| [`deposit_rate.stone.json`](../../examples/deposit_rate.stone.json) | Biểu lãi suất tiền gửi có kỳ hạn | `UNIQUE` |
| [`transfer_fees.stone.json`](../../examples/transfer_fees.stone.json) | Phí chuyển tiền | `COLLECT`, `max`, làm tròn phí tính theo phần trăm |
| [`card_fraud_signals.stone.json`](../../examples/card_fraud_signals.stone.json), [`card_fraud_action.stone.json`](../../examples/card_fraud_action.stone.json) | Sàng lọc gian lận thẻ | `COLLECT` cho các tín hiệu rủi ro và `FIRST` cho hành động, do ứng dụng nối tiếp; danh sách với `in` |
| [`consumer_loan_underwriting.stone.json`](../../examples/consumer_loan_underwriting.stone.json), [`consumer_loan_conditions.stone.json`](../../examples/consumer_loan_conditions.stone.json) | Thẩm định tín dụng khoản vay tiêu dùng | `FIRST` với từ chối, chuyển xem xét và hạng rủi ro; trường tuỳ chọn với `has()`; `min`, các chế độ làm tròn; `COLLECT` cho điều kiện phê duyệt |
| [`aml_name_screening.stone.json`](../../examples/aml_name_screening.stone.json), [`aml_transaction_monitoring.stone.json`](../../examples/aml_transaction_monitoring.stone.json), [`aml_alert_action.stone.json`](../../examples/aml_alert_action.stone.json) | Phòng, chống rửa tiền: sàng lọc danh sách theo dõi và giám sát giao dịch | So khớp tên gần đúng với bí danh và ngày sinh; số học và so sánh với `money`; các hàm ngày và `businessDate`; cảnh báo `COLLECT` nối tiếp tới hành động `FIRST` |

## Xét điều kiện vay: thứ tự ưu tiên và phiên bản

Phiên bản 3 được trình bày từng bước trong [Bắt đầu](getting-started.md). Các luật của nó được xét theo thứ tự ưu tiên: các trường hợp từ chối dứt khoát (`UNDERAGE`, `BLACKLISTED`, `DTI_HIGH`), rồi phê duyệt, rồi một luật hứng mọi trường hợp còn lại để chuyển hồ sơ sang xem xét (`AMOUNT_ABOVE_LIMIT`).

Phiên bản 2 là chính sách áp dụng trước ngày 1 tháng 11 năm 2026:

| | Phiên bản 2 | Phiên bản 3 |
|---|---|---|
| Hiệu lực | 2026-01-01 đến 2026-10-31 | từ 2026-11-01 |
| Tuổi tối thiểu | 20 | 18 |
| Kiểm tra danh sách đen | — | có |
| Kiểm tra tỷ lệ nợ trên thu nhập | — | từ chối nếu trên 50% |
| Số tiền tối đa | 6 × thu nhập tháng | 12 × thu nhập tháng, làm tròn xuống tới hàng triệu |

Hãy đăng ký cả hai phiên bản với engine. Ngày `asOf` chọn phiên bản đang có hiệu lực:

```java
Facts facts = Facts.of("applicant", new Applicant(19, new BigDecimal("30000000"),
                BigDecimal.ZERO, "RETAIL", false),
        "amount", new BigDecimal("1000000"));

engine.evaluate(DecisionRef.of("loan_eligibility").asOf(LocalDate.of(2026, 10, 31)), facts);
// version 2: {decision=REJECT}, reason UNDERAGE (minimum age was 20)

engine.evaluate(DecisionRef.of("loan_eligibility").asOf(LocalDate.of(2026, 11, 1)), facts);
// version 3: {decision=APPROVE, limit=360000000}
```

Lưu ý rằng `R3` chia cho `monthlyIncome`. Một người vay có thu nhập bằng không sẽ làm phép chia đó thất bại, kéo theo thất bại của toàn bộ quyết định. Để thay vào đó từ chối những người vay như vậy kèm một lý do, hãy thêm một luật trước `R3`:

```json
{ "id": "R2b", "when": "applicant.monthlyIncome <= 0", "then": { "decision": "'REJECT'" }, "reasonCode": "NO_INCOME" }
```

## Lãi suất tiền gửi: biểu lãi suất với `UNIQUE`

```json
{ "id": "SHORT",    "when": "termMonths < 6",                                 "then": { "rate": "decimal('0.035')" } },
{ "id": "MID",      "when": "termMonths >= 6 && termMonths < 12",             "then": { "rate": "decimal('0.048')" } },
{ "id": "LONG",     "when": "termMonths >= 12 && balance < 5000000000",       "then": { "rate": "decimal('0.052')" } },
{ "id": "VIP_LONG", "when": "termMonths >= 12 && balance >= 5000000000",      "then": { "rate": "decimal('0.055')" } }
```

| Đầu vào | Luật khớp | Lãi suất |
|---|---|---|
| 9 tháng, 100.000.000 | `MID` | 0.048 |
| 12 tháng, 6.000.000.000 | `VIP_LONG` | 0.055 |

Các dòng được thiết kế để chia tách mọi tổ hợp kỳ hạn và số dư, nên bảng dùng `UNIQUE`. Nếu sau này có người viết `LONG` chỉ là `termMonths >= 12`, một khoản tiền gửi 6 tỷ kỳ hạn 12 tháng sẽ khớp cả `LONG` lẫn `VIP_LONG`. Khi đó RuleStone sẽ làm việc đánh giá thất bại với lỗi `UNIQUE hit policy violated: rules [LONG, VIP_LONG] all matched`, thay vì âm thầm trả một trong hai mức lãi suất.

## Phí chuyển tiền: cộng dồn với `COLLECT`

Mỗi luật thêm một loại phí khi luật đó áp dụng:

```json
{ "id": "INTERBANK", "when": "transfer.interbank",
  "then": { "feeCode": "'IBT'", "fee": "max(decimal(2000), (transfer.amount * decimal('0.0005')).round(0, 'HALF_UP'))" } },
{ "id": "EXPRESS",   "when": "transfer.express",            "then": { "feeCode": "'EXP'", "fee": "decimal(5000)" } },
{ "id": "COUNTER",   "when": "transfer.channel == 'COUNTER'", "then": { "feeCode": "'CTR'", "fee": "decimal(10000)" } }
```

Một giao dịch chuyển tiền liên ngân hàng 50.000.000 thực hiện tại quầy:

```
output       {feeCode=[IBT, CTR], fee=[25000, 10000]}
matchedRules [INTERBANK, COUNTER]
```

Phí liên ngân hàng bằng 0,05% số tiền, tối thiểu 2.000. Với `COLLECT`, mỗi đầu ra là một danh sách theo thứ tự luật, nên `feeCode` và `fee` khớp nhau theo từng vị trí. Hãy cộng các khoản phí trong ứng dụng, hoặc trong một quyết định tiếp theo.

Khi không có luật nào khớp, mọi đầu ra là danh sách rỗng và `matched()` là `false`.

## Gian lận thẻ: tín hiệu với `COLLECT`, hành động với `FIRST`

Một yêu cầu cấp phép giao dịch thẻ phải được trả lời trong vài mili giây bằng một trong ba hành động: phê duyệt, **thách thức** (xác thực tăng cường như OTP hoặc 3-D Secure, chỉ dùng được với giao dịch trực tuyến), hoặc từ chối. Chuyên viên phân tích gian lận lập luận theo *tín hiệu*: "cao hơn hẳn mức chi tiêu thường ngày của khách hàng này", "quốc gia thứ ba trong ngày hôm nay", "đơn vị chấp nhận thẻ là sòng bạc". Mỗi tín hiệu riêng lẻ thì yếu; gộp lại thì mạnh.

Ví dụ này tách bài toán thành hai bảng, được ứng dụng đánh giá lần lượt:

1. [`card_fraud_signals.stone.json`](../../examples/card_fraud_signals.stone.json) dùng `COLLECT`. Mỗi luật phát hiện độc lập một tín hiệu và trả về mã `signal` cùng số điểm `points` của tín hiệu đó. Thêm một tín hiệu nghĩa là thêm một dòng; không luật nào khác phải thay đổi.
2. [`card_fraud_action.stone.json`](../../examples/card_fraud_action.stone.json) dùng `FIRST`. Bảng này chuyển tổng điểm thành một hành động, theo thứ tự ưu tiên.

Các tín hiệu, với trọng số giả định:

| Luật | Kích hoạt khi | Điểm |
|---|---|---|
| `AMOUNT_SPIKE` | số tiền lớn hơn 5 × mức trung bình 30 ngày (và đã có mức trung bình) | 30 |
| `HIGH_VELOCITY` | có từ 5 giao dịch trở lên trong một giờ trước đó, không tính giao dịch này | 25 |
| `FOREIGN_COUNTRY` | quốc gia của đơn vị chấp nhận thẻ khác quốc gia của chủ thẻ | 10 |
| `MULTI_COUNTRY_24H` | từ 3 quốc gia khác nhau trở lên trong 24 giờ, tính cả giao dịch này (dấu hiệu "di chuyển bất khả thi") | 35 |
| `NIGHT_ECOM` | giao dịch thương mại điện tử từ 00:00 đến 04:59, theo giờ địa phương của chủ thẻ | 15 |
| `HIGH_RISK_MCC` | mã ngành hàng 7995 (cờ bạc), 4829 (chuyển tiền) hoặc 6051 (tương đương tiền mặt, tiền mã hoá) | 20 |
| `NEW_DEVICE_ECOM` | giao dịch thương mại điện tử từ một thiết bị chưa từng dùng với thẻ này | 20 |
| `NEW_ACCOUNT` | tài khoản mở chưa đầy 30 ngày | 15 |
| `KEYED_POS` | giao dịch tại điểm bán không có thẻ vật lý (nhập số thẻ thủ công) | 15 |
| `FOREIGN_CURRENCY` | loại tiền gốc không phải VND | 5 |

Mã lý do của mỗi luật chính là mã tín hiệu của nó, nên `reasonCodes()` giải thích được điểm số:

```json
{ "id": "AMOUNT_SPIKE",
  "when": "profile.averageAmount30d > 0 && transaction.amount > profile.averageAmount30d * 5",
  "then": { "signal": "'AMOUNT_SPIKE'", "points": "30" }, "reasonCode": "AMOUNT_SPIKE" },
{ "id": "HIGH_RISK_MCC",
  "when": "transaction.merchantCategory in ['7995', '4829', '6051']",
  "then": { "signal": "'HIGH_RISK_MCC'", "points": "20" }, "reasonCode": "HIGH_RISK_MCC" }
```

Bảng hành động, theo thứ tự đánh giá:

```json
{ "id": "DECLINE_VERY_HIGH",  "when": "riskScore >= 80",                                  "then": { "action": "'DECLINE'" },   "reasonCode": "RISK_SCORE_VERY_HIGH" },
{ "id": "DECLINE_NO_STEP_UP", "when": "riskScore >= 40 && transaction.channel != 'ECOM'", "then": { "action": "'DECLINE'" },   "reasonCode": "STEP_UP_UNAVAILABLE" },
{ "id": "CHALLENGE_HIGH",     "when": "riskScore >= 40",                                  "then": { "action": "'CHALLENGE'" }, "reasonCode": "RISK_SCORE_HIGH" },
{ "id": "CHALLENGE_LARGE_AMOUNT",
  "when": "transaction.channel == 'ECOM' && riskScore >= 20 && transaction.amount >= 20000000",
  "then": { "action": "'CHALLENGE'" }, "reasonCode": "LARGE_AMOUNT_WITH_RISK" },
{ "id": "APPROVE", "when": "true", "then": { "action": "'APPROVE'" } }
```

Với `COLLECT`, `points` là một danh sách, nên ứng dụng cộng các điểm lại rồi truyền tổng điểm cho bảng thứ hai. Cùng một đối tượng `transaction` được dùng cho cả hai bảng: bảng hành động chỉ khai báo hai trường mà nó đọc, các component khác của record bị bỏ qua.

```java
record CardTransaction(BigDecimal amount, String currency, String merchantCategory, String country,
                       String channel, int hourOfDay, boolean cardPresent) {}

record CardProfile(String homeCountry, BigDecimal averageAmount30d, int transactionsLastHour,
                   int distinctCountriesLast24h, boolean newDevice, int accountAgeDays) {}

DecisionResult signals = engine.evaluate(DecisionRef.of("card_fraud_signals"),
        Facts.of("transaction", transaction, "profile", profile));

long riskScore = ((List<?>) signals.output().get("points")).stream()
        .mapToLong(points -> (Long) points)
        .sum();

DecisionResult action = engine.evaluate(DecisionRef.of("card_fraud_action"),
        Facts.of("riskScore", riskScore, "transaction", transaction));
```

Một số giao dịch mẫu của các chủ thẻ có quốc gia là VN (số tiền tính bằng VND):

| Giao dịch | Hồ sơ | Tín hiệu (điểm) | Điểm | Hành động (luật, lý do) |
|---|---|---|---|---|
| 450.000 VND, tạp hoá 5411, VN, POS, 18:00, có thẻ | trung bình 600.000, 1 quốc gia, tài khoản 800 ngày | không có | 0 | `APPROVE` (`APPROVE`) |
| 6.000.000 quy đổi từ giao dịch USD, điện tử 5732, US, ECOM, 23:00 | trung bình 1.000.000, 2 quốc gia, thiết bị mới | `AMOUNT_SPIKE`, `FOREIGN_COUNTRY`, `NEW_DEVICE_ECOM`, `FOREIGN_CURRENCY` (30, 10, 20, 5) | 65 | `CHALLENGE` (`CHALLENGE_HIGH`, `RISK_SCORE_HIGH`) |
| 2.000.000 quy đổi từ giao dịch THB, nhà hàng 5812, TH, POS, 14:00, có thẻ | trung bình 800.000, 3 quốc gia | `FOREIGN_COUNTRY`, `MULTI_COUNTRY_24H`, `FOREIGN_CURRENCY` (10, 35, 5) | 50 | `DECLINE` (`DECLINE_NO_STEP_UP`, `STEP_UP_UNAVAILABLE`) |
| 25.000.000 VND, chuyển tiền 4829, VN, ECOM, 10:00 | trung bình 8.000.000 | `HIGH_RISK_MCC` (20) | 20 | `CHALLENGE` (`CHALLENGE_LARGE_AMOUNT`, `LARGE_AMOUNT_WITH_RISK`) |
| 15.000.000 VND, tiền mã hoá 6051, VN, ECOM, 02:00 | trung bình 1.200.000, thiết bị mới | `AMOUNT_SPIKE`, `NIGHT_ECOM`, `HIGH_RISK_MCC`, `NEW_DEVICE_ECOM` (30, 15, 20, 20) | 85 | `DECLINE` (`DECLINE_VERY_HIGH`, `RISK_SCORE_VERY_HIGH`) |

Với giao dịch thứ hai, `signals.output()` in ra:

```
{signal=[AMOUNT_SPIKE, FOREIGN_COUNTRY, NEW_DEVICE_ECOM, FOREIGN_CURRENCY], points=[30, 10, 20, 5]}
```

Những điểm cần lưu ý:

- **Thống nhất đơn vị và cách đếm.** `amount` là số tiền ghi nợ tính bằng VND, bất kể loại tiền gốc `currency`, để có thể so sánh với mức trung bình 30 ngày. `transactionsLastHour` không tính giao dịch hiện tại, còn `distinctCountriesLast24h` thì có tính. `hourOfDay` theo giờ địa phương của chủ thẻ và do ứng dụng tính. Hãy ghi lại các quy ước này bên cạnh bảng; luật không thể tự kiểm tra chúng.
- **Bảo vệ các tỷ lệ khỏi trường hợp thiếu lịch sử.** Nếu không có điều kiện `averageAmount30d > 0`, một thẻ chưa có lịch sử giao dịch sẽ kích hoạt `AMOUNT_SPIKE` ở mọi giao dịch. Thẻ mới được xử lý bằng `NEW_ACCOUNT`.
- **Mã ngành hàng là văn bản.** MCC có thể bắt đầu bằng số không (`0742`), nên được khai báo kiểu `string` và liệt kê dạng `'7995'`, không phải `7995`.
- **Sắp xếp bảng hành động theo mức độ nghiêm trọng.** `DECLINE_NO_STEP_UP` phải đứng trước `CHALLENGE_HIGH`: máy POS không thể hiển thị OTP, nên điểm cao ở đó dẫn tới từ chối thay vì thách thức.
- **Giữ các danh sách khớp nhau.** `signal`, `points` và `reasonCodes()` khớp nhau theo từng vị trí. Hãy cộng hoặc lọc chúng cùng nhau; đừng bao giờ sắp xếp riêng một danh sách.
- **Kiểm toán cả hai bảng.** Hãy lưu `ruleVersion()` của cả hai kết quả cùng với giao dịch cấp phép, để một lần từ chối có thể truy ngược tới đúng trọng số và khoảng điểm đang có hiệu lực.

## Thẩm định tín dụng khoản vay tiêu dùng: từ chối, chuyển xem xét và hạng rủi ro

Thẩm định tín dụng một khoản vay tiêu dùng, có hay không có tài sản bảo đảm, lần lượt trả lời bốn câu hỏi: có được cho vay không, có cần chuyên viên thẩm định xem xét không, rủi ro đến đâu, và cho vay với điều kiện gì? [`consumer_loan_underwriting.stone.json`](../../examples/consumer_loan_underwriting.stone.json) trả lời bằng một bảng `FIRST` có các luật được nhóm theo đúng thứ tự đó:

| Thứ tự | Luật | Quyết định | Mã lý do | Khi |
|---|---|---|---|---|
| 1 | `DECLINE_AGE` | `DECLINE` | `AGE_OUT_OF_RANGE` | dưới 20 hoặc trên 65 tuổi |
| 2 | `DECLINE_BAD_DEBT` | `DECLINE` | `BAD_DEBT_HISTORY` | có nợ xấu trong 5 năm gần nhất |
| 3 | `DECLINE_SCORE` | `DECLINE` | `CREDIT_SCORE_TOO_LOW` | điểm tín dụng dưới 500 |
| 4 | `DECLINE_INCOME` | `DECLINE` | `INCOME_BELOW_MINIMUM` | thu nhập tháng dưới 5.000.000 |
| 5 | `DECLINE_TERM` | `DECLINE` | `TERM_OUT_OF_RANGE` | kỳ hạn ngoài khoảng 6–60 tháng (6–84 nếu có tài sản bảo đảm) |
| 6 | `DECLINE_DTI` | `DECLINE` | `DTI_TOO_HIGH` | tỷ lệ nợ trên thu nhập trên 60% |
| 7 | `DECLINE_LTV` | `DECLINE` | `LTV_TOO_HIGH` | tỷ lệ cho vay trên giá trị tài sản bảo đảm trên 85% |
| 8 | `REFER_DTI` | `REFER` | `DTI_ELEVATED` | tỷ lệ nợ trên thu nhập trên 45% |
| 9 | `REFER_LTV` | `REFER` | `LTV_ELEVATED` | tỷ lệ cho vay trên giá trị tài sản bảo đảm trên 70% |
| 10 | `REFER_EMPLOYMENT` | `REFER` | `EMPLOYMENT_HISTORY_SHORT` | làm việc dưới 12 tháng, hoặc dưới 24 tháng nếu tự kinh doanh |
| 11 | `REFER_PURPOSE` | `REFER` | `PURPOSE_NOT_STANDARD` | mục đích vay không thuộc `CONSUMER`, `EDUCATION`, `HOME_IMPROVEMENT` hoặc `VEHICLE` |
| 12 | `APPROVE_A` | `APPROVE`, hạng A | — | điểm từ 750 trở lên và tỷ lệ nợ trên thu nhập tối đa 30% |
| 13 | `APPROVE_B` | `APPROVE`, hạng B | — | điểm từ 680 trở lên |
| 14 | `APPROVE_C` | `APPROVE`, hạng C | — | điểm từ 620 trở lên |
| 15 | `APPROVE_D_SECURED` | `APPROVE`, hạng D | — | điểm từ 560 trở lên, có tài sản bảo đảm |
| 16 | `REFER_D_UNSECURED` | `REFER`, hạng D | `UNSECURED_GRADE_D` | điểm từ 560 trở lên, tín chấp |
| 17 | `REFER_OTHER` | `REFER`, hạng E | `CREDIT_SCORE_MARGINAL` | mọi trường hợp còn lại; trên thực tế là điểm từ 500 đến 559 |

`FIRST` làm thứ tự ưu tiên trở nên tường minh: người vay đã bị từ chối thì không bao giờ được chuyển xem xét, và hồ sơ đã chuyển xem xét thì không bao giờ được phê duyệt. Luật hứng mọi trường hợp ở cuối bảo đảm mọi hồ sơ đều có câu trả lời, và khi còn nghi ngờ, con người sẽ quyết định.

Bảng tính toán các đại lượng sau, hoàn toàn bằng số học `decimal`:

| Đại lượng | Công thức |
|---|---|
| Số tiền trả góp hằng tháng ước tính | amount × (1 + 1% × termMonths) / termMonths: lãi phẳng 1% mỗi tháng trên dư nợ gốc ban đầu, một phép xấp xỉ cố ý thận trọng để minh hoạ |
| Tỷ lệ nợ trên thu nhập (DTI) | (existingMonthlyDebt + số tiền trả góp) / monthlyIncome |
| Tỷ lệ cho vay trên giá trị tài sản bảo đảm (LTV) | amount / collateralValue, chỉ với khoản vay có tài sản bảo đảm |
| `interestRate` | lãi suất cơ sở theo hạng (A 8,5%, B 10,5%, C 13%, D 16%) + 5% × DTI, trừ 2% nếu có tài sản bảo đảm, làm tròn half-up tới 4 chữ số thập phân |
| `maxAmount` | số tiền lớn nhất giữ DTI ở mức 45%, (45% × monthlyIncome − existingMonthlyDebt) × termMonths / (1 + 1% × termMonths), và nếu có tài sản bảo đảm thì tối đa 70% giá trị tài sản; làm tròn xuống tới hàng triệu |

Vì các luật chuyển xem xét đứng trước các luật phê duyệt, mọi yêu cầu được phê duyệt đều nằm trong `maxAmount` trước khi làm tròn; phần chênh lệch là số tiền ngân hàng có thể đề nghị thêm.

Khoản vay tín chấp bỏ trống `collateralValue` (`null` trong một Java record), và các luật kiểm tra trường này bằng `has()`. Các ngưỡng được so sánh bằng phép nhân thay vì phép chia, nên các luật này không thể chia cho không:

```json
{ "id": "DECLINE_DTI",
  "when": "applicant.existingMonthlyDebt + loan.amount * (decimal(1) + decimal('0.01') * loan.termMonths) / loan.termMonths > applicant.monthlyIncome * decimal('0.6')",
  "then": { "decision": "'DECLINE'" }, "reasonCode": "DTI_TOO_HIGH" },
{ "id": "DECLINE_LTV",
  "when": "has(loan.collateralValue) && loan.amount > loan.collateralValue * decimal('0.85')",
  "then": { "decision": "'DECLINE'" }, "reasonCode": "LTV_TOO_HIGH" },
{ "id": "REFER_EMPLOYMENT",
  "when": "applicant.employmentMonths < (applicant.employmentType == 'SELF_EMPLOYED' ? 24 : 12)",
  "then": { "decision": "'REFER'" }, "reasonCode": "EMPLOYMENT_HISTORY_SHORT" }
```

Một quyết định phê duyệt đi kèm các điều kiện. Các điều kiện cộng dồn, nên chúng nằm trong bảng thứ hai, [`consumer_loan_conditions.stone.json`](../../examples/consumer_loan_conditions.stone.json), dùng `COLLECT` và nhận `riskGrade` từ kết quả đầu tiên:

| Luật | Khi | `condition` |
|---|---|---|
| `SALARY_ASSIGNMENT` | hưởng lương, hạng C hoặc D | `SALARY_ASSIGNMENT` |
| `TAX_RETURNS` | tự kinh doanh | `TAX_RETURNS_2Y` |
| `GUARANTOR` | hạng D, hoặc tín chấp và trên 500.000.000 | `GUARANTOR` |
| `CREDIT_LIFE_INSURANCE` | từ 100.000.000 trở lên, hoặc kỳ hạn dài hơn 36 tháng | `CREDIT_LIFE_INSURANCE` |
| `COLLATERAL_INSURANCE` | có tài sản bảo đảm | `COLLATERAL_INSURANCE` |
| `HOLD_VEHICLE_REGISTRATION` | vay mua xe có tài sản bảo đảm | `HOLD_VEHICLE_REGISTRATION` |
| `DISBURSE_TO_SCHOOL` | vay học tập | `DISBURSE_TO_SCHOOL` |

Ứng dụng chỉ đánh giá các điều kiện với hồ sơ được phê duyệt:

```java
record Applicant(int age, int creditScore, BigDecimal monthlyIncome, BigDecimal existingMonthlyDebt,
                 int employmentMonths, String employmentType, boolean hasBadDebtLast5y) {}

record Loan(BigDecimal amount, int termMonths, BigDecimal collateralValue, String purpose) {}

DecisionResult underwriting = engine.evaluate(DecisionRef.of("consumer_loan_underwriting"),
        Facts.of("applicant", applicant, "loan", loan));

List<?> conditions = List.of();
if ("APPROVE".equals(underwriting.output().get("decision"))) {
    DecisionResult approval = engine.evaluate(DecisionRef.of("consumer_loan_conditions"),
            Facts.of("riskGrade", underwriting.output().get("riskGrade"),
                    "applicant", applicant, "loan", loan));
    conditions = (List<?>) approval.output().get("condition");
}
```

Một số hồ sơ mẫu (số tiền tính bằng VND):

| Người vay | Khoản vay | `output()` | Luật, lý do | Điều kiện |
|---|---|---|---|---|
| 35 tuổi, điểm 780, thu nhập 60.000.000, nợ 2.000.000, hưởng lương 60 tháng | 360.000.000 tín chấp, 36 tháng, `CONSUMER` | `{decision=APPROVE, riskGrade=A, interestRate=0.0980, maxAmount=661000000}` | `APPROVE_A` | `[CREDIT_LIFE_INSURANCE]` |
| 42 tuổi, điểm 640, thu nhập 35.000.000, nợ 3.000.000, tự kinh doanh 30 tháng | 420.000.000, 60 tháng, `VEHICLE` trị giá 700.000.000 | `{decision=APPROVE, riskGrade=C, interestRate=0.1303, maxAmount=478000000}` | `APPROVE_C` | `[TAX_RETURNS_2Y, CREDIT_LIFE_INSURANCE, COLLATERAL_INSURANCE, HOLD_VEHICLE_REGISTRATION]` |
| 28 tuổi, điểm 700, thu nhập 20.000.000, nợ 6.000.000, hưởng lương 24 tháng | 240.000.000 tín chấp, 24 tháng, `CONSUMER` | `{decision=DECLINE}` | `DECLINE_DTI`, `DTI_TOO_HIGH` | không đánh giá |
| 31 tuổi, điểm 720, thu nhập 40.000.000, không có nợ, tự kinh doanh 18 tháng | 200.000.000 tín chấp, 48 tháng, `HOME_IMPROVEMENT` | `{decision=REFER}` | `REFER_EMPLOYMENT`, `EMPLOYMENT_HISTORY_SHORT` | không đánh giá |
| 26 tuổi, điểm 590, thu nhập 15.000.000, nợ 1.000.000, hưởng lương 36 tháng | 60.000.000 tín chấp, 24 tháng, `EDUCATION` | `{decision=REFER, riskGrade=D}` | `REFER_D_UNSECURED`, `UNSECURED_GRADE_D` | không đánh giá |

Ở hồ sơ thứ nhất, số tiền trả góp là 360.000.000 × 1,36 / 36 = 13.600.000, nên DTI là (2.000.000 + 13.600.000) / 60.000.000 = 26% và lãi suất là 8,5% + 5% × 26% = 9,80%. Ở hồ sơ thứ hai, DTI là 14.200.000 / 35.000.000 ≈ 40,57% và LTV là 60%, nên lãi suất là 13% + 2,03% − 2% ≈ 13,03%, và `maxAmount` bị giới hạn bởi thu nhập (478.125.000) chứ không phải bởi tài sản bảo đảm (490.000.000).

Những điểm cần lưu ý:

- **Kiểm tra trước khi chia.** `DECLINE_INCOME` và `DECLINE_TERM` đứng trước mọi luật chia cho thu nhập hoặc kỳ hạn, và các ngưỡng DTI, LTV dùng phép nhân thay vì phép chia. Thu nhập bằng không hoặc tài sản bảo đảm có giá trị bằng không sẽ bị từ chối kèm lý do, thay vì làm việc đánh giá thất bại.
- **Tín chấp nghĩa là bỏ trống, không phải bằng không.** Hãy bỏ trống `collateralValue` với khoản vay tín chấp. Giá trị `0` nghĩa là khoản vay có tài sản bảo đảm nhưng tài sản không có giá trị, và `DECLINE_LTV` sẽ từ chối.
- **Công thức trả góp bị lặp lại.** Một bảng không thể đặt tên cho giá trị trung gian, nên cùng một biểu thức xuất hiện ở cả hai luật DTI, ở `APPROVE_A` và ở mọi mức lãi suất. Hãy sửa chúng cùng lúc; các ca kiểm thử ở từng ngưỡng (DTI đúng bằng 30%, 45% và 60%) sẽ phát hiện bản sao bị lệch. Tính số tiền trả góp trong ứng dụng rồi truyền vào như một đầu vào sẽ làm các luật ngắn hơn, nhưng lại giấu công thức khỏi người đọc bảng.
- **Nêu rõ chế độ làm tròn.** Lãi suất làm tròn `HALF_UP`: người vay hạng B có DTI đúng bằng 30,1% nhận 12,005%, hiển thị là `0.1201`; chế độ mặc định `HALF_EVEN` sẽ cho `0.1200`. `maxAmount` làm tròn `FLOOR`, để ngân hàng không bao giờ đề nghị nhiều hơn mức chính sách cho phép.
- **Từ chối và chuyển xem xét đặt ít đầu ra hơn.** `{decision=DECLINE}` không có `riskGrade`, `interestRate` hay `maxAmount`. Hãy kiểm tra quyết định trước khi đọc các đầu ra này.
- **`interestRate` giữ nguyên scale.** Giá trị in ra là `0.0980`, không phải `0.098`. Hãy so sánh các giá trị `BigDecimal` bằng `compareTo`, không dùng `equals`.

## Phòng, chống rửa tiền: sàng lọc và giám sát giao dịch

Ngân hàng phải biết khách hàng của mình là ai và theo dõi dòng tiền đi qua tài khoản của họ. Hai biện pháp kiểm soát là cốt lõi của một chương trình phòng, chống rửa tiền (AML):

1. **Sàng lọc.** Khi tiếp nhận khách hàng, và mỗi khi các danh sách thay đổi, đối chiếu khách hàng với các danh sách theo dõi: danh sách cấm vận (những cá nhân và công ty không ai được giao dịch) và danh sách người có ảnh hưởng chính trị (PEP, cần áp dụng biện pháp thẩm định tăng cường).
2. **Giám sát giao dịch.** Xem xét từng giao dịch cùng với hoạt động gần đây của tài khoản để tìm các dấu hiệu điển hình của rửa tiền, như giao dịch tiền mặt lớn, chia nhỏ giao dịch hay tiền chỉ đi qua tài khoản, và quyết định xem chuyên viên có cần xem xét không và có phải gửi báo cáo cho cơ quan chức năng không.

Ví dụ này triển khai cả hai bằng ba bảng.

> **Quan trọng.** Đây là ví dụ minh hoạ định dạng luật, không phải một hệ thống AML. Mọi tên đều là giả định, không chỉ bất kỳ cá nhân, công ty hay thực thể có trong danh sách nào có thật. Mọi ngưỡng, khoảng điểm và điểm số đều là giả định và **không** lấy từ bất kỳ quy định nào: mỗi tổ chức phải áp dụng ngưỡng báo cáo, thời hạn và nghĩa vụ theo các quy định áp dụng cho mình, và tự hiệu chỉnh ngưỡng cảnh báo trên dữ liệu của mình. Các hàm so khớp gần đúng không phải sản phẩm sàng lọc được chứng nhận; xem [So khớp gần đúng](fuzzy-matching.md#hạn-chế).

### Sàng lọc khách hàng theo danh sách theo dõi

[`aml_name_screening.stone.json`](../../examples/aml_name_screening.stone.json) nhận thông tin khách hàng và danh sách theo dõi, cả hai đều có bí danh và ngày sinh:

```json
"inputs": {
  "customer":  { "fullName": "string", "aliases": ["string"], "dateOfBirth": "date", "nationality": "string" },
  "watchlist": [ { "name": "string", "aliases": ["string"], "dateOfBirth": "date", "listType": "string" } ]
},
"outputs": { "result": "string", "bestScore": "decimal" }
```

Bảng dùng `FIRST`, vì các kết quả có thứ tự ưu tiên chặt chẽ, từ nghiêm trọng nhất đến nhẹ nhất:

| Thứ tự | Luật | `result` | Mã lý do | Khi |
|---|---|---|---|---|
| 1 | `NAME_MISSING` | `REVIEW` | `NAME_MISSING` | tên bị thiếu hoặc để trống |
| 2 | `SANCTIONS_MATCH` | `BLOCK` | `SANCTIONS_MATCH` | một tên hoặc bí danh của khách hàng đạt từ 0.92 trở lên so với một tên hoặc bí danh của một mục trong danh sách cấm vận, **và** cả hai có cùng ngày sinh |
| 3 | `SANCTIONS_POSSIBLE_MATCH` | `REVIEW` | `SANCTIONS_POSSIBLE_MATCH` | cùng phép thử tên đó, nhưng ngày sinh không trùng |
| 4 | `SANCTIONS_WEAK_MATCH` | `REVIEW` | `SANCTIONS_WEAK_MATCH` | một tên hoặc bí danh đạt từ 0.85 đến dưới 0.92 so với một mục trong danh sách cấm vận |
| 5 | `PEP_MATCH` | `ENHANCED_DUE_DILIGENCE` | `PEP_MATCH` | một tên hoặc bí danh đạt từ 0.92 trở lên so với một PEP |
| 6 | `CLEAR` | `CLEAR` | — | mọi trường hợp còn lại |

Thứ tự này mang ý nghĩa:

- `NAME_MISSING` đứng đầu vì một tên trống được 0 điểm với mọi mục, và nếu không có luật này sẽ lọt qua thành `CLEAR`.
- `SANCTIONS_MATCH` đứng trước `SANCTIONS_POSSIBLE_MATCH` vì cả hai đều khớp theo tên; luật mạnh hơn bổ sung thêm ngày sinh. Do đó một mục trong danh sách không có ngày sinh (một công ty, hoặc một người không rõ ngày sinh) không bao giờ dẫn tới chặn, mà chỉ dẫn tới rà soát.
- `SANCTIONS_WEAK_MATCH` chỉ kiểm tra `>= decimal('0.85')`: mọi điểm từ 0.92 trở lên đã được hai luật phía trên xử lý, nên với `FIRST` luật này bao đúng khoảng từ 0.85 đến dưới 0.92. Đưa luật này lên trên sẽ biến các kết quả khớp mạnh thành khớp yếu.
- Cấm vận đứng trước PEP. Khách hàng có tên trong cả hai danh sách nhận kết quả của danh sách cấm vận.

Phép thử tên so sánh mọi tên của khách hàng (họ tên đầy đủ và bí danh) với mọi tên của mục trong danh sách (tên và bí danh). `maxNameSimilarity` chấm điểm một tên so với một danh sách, dùng `nameSimilarity`, vốn bỏ qua dấu, chữ hoa chữ thường, dấu câu và thứ tự từ:

```json
{ "id": "SANCTIONS_MATCH",
  "when": "has(customer.dateOfBirth) && watchlist.exists(w, w.listType == 'SANCTIONS' && has(w.dateOfBirth) && w.dateOfBirth == customer.dateOfBirth && ([customer.fullName] + customer.aliases).exists(n, normalizeName(n) != '' && maxNameSimilarity(n, [w.name] + w.aliases) >= decimal('0.92')))",
  "then": { "result": "'BLOCK'", "bestScore": "maxNameSimilarity(customer.fullName, watchlist.map(w, w.name))" },
  "reasonCode": "SANCTIONS_MATCH" },
{ "id": "SANCTIONS_WEAK_MATCH",
  "when": "watchlist.exists(w, w.listType == 'SANCTIONS' && ([customer.fullName] + customer.aliases).exists(n, normalizeName(n) != '' && maxNameSimilarity(n, [w.name] + w.aliases) >= decimal('0.85')))",
  "then": { "result": "'REVIEW'", "bestScore": "maxNameSimilarity(customer.fullName, watchlist.map(w, w.name))" },
  "reasonCode": "SANCTIONS_WEAK_MATCH" }
```

Ngày sinh là tuỳ chọn ở cả hai phía và được kiểm tra bằng `has()`. Bí danh là bắt buộc: hãy truyền danh sách rỗng khi không có. `normalizeName(n) != ''` bỏ qua bí danh trống của khách hàng, vốn sẽ được 1 điểm khi so với một bí danh trống trong danh sách. `bestScore` là điểm `nameSimilarity` cao nhất giữa họ tên đầy đủ của khách hàng và các tên chính trong danh sách theo dõi, để chuyên viên rà soát cảnh báo thấy cái tên gần giống đến đâu. `nationality` thuộc hồ sơ khách hàng nhưng không được các luật này sử dụng.

Trong Java, record là cách đơn giản nhất để truyền khách hàng và danh sách; ngày sinh `null` được coi là chưa đặt:

```java
record Customer(String fullName, List<String> aliases, LocalDate dateOfBirth, String nationality) {}

record WatchlistEntry(String name, List<String> aliases, LocalDate dateOfBirth, String listType) {}

List<WatchlistEntry> watchlist = List.of(
        new WatchlistEntry("Trần Thị Bích Ngọc", List.of("Jade Tran"), LocalDate.of(1975, 3, 14), "SANCTIONS"),
        new WatchlistEntry("Mekong Phantom Trading Co., Ltd", List.of("MPT Trading"), null, "SANCTIONS"),
        new WatchlistEntry("Phạm Văn Quyết", List.of(), LocalDate.of(1959, 7, 20), "PEP"));

DecisionResult screening = engine.evaluate(DecisionRef.of("aml_name_screening"),
        Facts.of("customer", new Customer("TRAN THI BICH NGOC", List.of(), LocalDate.of(1975, 3, 14), "VN"),
                "watchlist", watchlist));
// {result=BLOCK, bestScore=1.0000}, reason SANCTIONS_MATCH
```

Các khách hàng được sàng lọc theo danh sách đó:

| Khách hàng | Ngày sinh | `output()` | Luật và mã lý do |
|---|---|---|---|
| `TRAN THI BICH NGOC` | 1975-03-14 | `{result=BLOCK, bestScore=1.0000}` | `SANCTIONS_MATCH` |
| `Ngoc Tran Thi Bich` | 1990-05-01 | `{result=REVIEW, bestScore=1.0000}` | `SANCTIONS_POSSIBLE_MATCH` |
| `Trần Thị Bích Ngọc` | không có | `{result=REVIEW, bestScore=1.0000}` | `SANCTIONS_POSSIBLE_MATCH` |
| `Lê Thị Hoa`, bí danh `Jade Tran` | 1975-03-14 | `{result=BLOCK, bestScore=0.6206}` | `SANCTIONS_MATCH` |
| `Tran Thi Bich Hong` | 1992-06-30 | `{result=REVIEW, bestScore=0.9660}` | `SANCTIONS_POSSIBLE_MATCH` |
| `Tran Thi Mai` | 1988-01-09 | `{result=REVIEW, bestScore=0.8778}` | `SANCTIONS_WEAK_MATCH` |
| `Mekong Phantom Trading Company Limited` | không có | `{result=REVIEW, bestScore=0.9526}` | `SANCTIONS_POSSIBLE_MATCH` |
| `Pham Van Quyet` | 1959-07-20 | `{result=ENHANCED_DUE_DILIGENCE, bestScore=1.0000}` | `PEP_MATCH` |
| `Lê Thị Hoa` | 1990-02-02 | `{result=CLEAR, bestScore=0.6206}` | `CLEAR`, không có mã lý do |
| ` - ` | 1990-02-02 | `{result=REVIEW}` | `NAME_MISSING` |

Ba dòng đầu là tên trong danh sách được viết hoa không dấu, đảo thứ tự từ, và có dấu: tất cả đều đạt `1.0000`, và chỉ ngày sinh quyết định giữa chặn và rà soát. Dòng thứ tư khớp qua bí danh, nên `bestScore` của nó, vốn tính trên họ tên đầy đủ, lại thấp: hãy đọc mã lý do, không phải điểm số, để biết cái gì đã khớp. Dòng thứ năm và thứ sáu, theo cách dựng ví dụ, là những người khác: họ chỉ có chung họ Trần và tên đệm Thị (ở dòng thứ năm còn chung cả chữ Bích) rất phổ biến với người trong danh sách, nhưng như vậy đã đủ để vượt ngưỡng. Công ty ở dòng thứ bảy không có ngày sinh trong danh sách, nên chỉ có thể dẫn tới rà soát.

### Giám sát giao dịch

[`aml_transaction_monitoring.stone.json`](../../examples/aml_transaction_monitoring.stone.json) dùng `COLLECT`: mỗi luật phát hiện độc lập một dấu hiệu và trả về một mã `alert` cùng một `score`, giống như các [tín hiệu gian lận thẻ](#gian-lận-thẻ-tín-hiệu-với-collect-hành-động-với-first). Số tiền có kiểu `money` theo VND, ngày có kiểu `date`:

```json
"inputs": {
  "account":     { "openedOn": "date", "lastActivityBeforeToday": "date", "riskRating": "string", "customerIsPep": "bool" },
  "transaction": { "amount": "money", "type": "string", "counterpartyCountry": "string", "valueDate": "date" },
  "history":     { "cashDepositsLast7d": ["money"], "totalInLast24h": "money", "totalOutLast24h": "money" }
}
```

Các cảnh báo, với ngưỡng và điểm số giả định. Ngưỡng báo cáo giao dịch tiền mặt được đặt ở mức 300.000.000 VND cho ví dụ này:

| Luật | `alert` | Kích hoạt khi | Điểm | Mã lý do |
|---|---|---|---|---|
| `LARGE_CASH` | `LARGE_CASH` | nộp hoặc rút tiền mặt từ 300.000.000 trở lên | 10 | `LARGE_CASH_TRANSACTION` |
| `STRUCTURING` | `STRUCTURING` | từ 3 lần nộp tiền mặt trở lên trong 7 ngày, mỗi lần từ 80% ngưỡng (240.000.000) đến ngay dưới ngưỡng | 50 | `POSSIBLE_STRUCTURING` |
| `RAPID_IN_OUT` | `RAPID_IN_OUT` | tiền ra trong 24 giờ bằng từ 90% tiền vào trở lên, và tiền vào tối thiểu 500.000.000 (100.000.000 với khách hàng xếp hạng rủi ro `HIGH`) | 40 | `RAPID_MOVEMENT_OF_FUNDS` |
| `HIGH_RISK_COUNTRY` | `HIGH_RISK_COUNTRY` | quốc gia của đối tác là `XA`, `XB` hoặc `XC` | 30 | `HIGH_RISK_JURISDICTION` |
| `DORMANT_REACTIVATED` | `DORMANT_ACCOUNT` | không có hoạt động từ 365 ngày trở lên trước ngày nghiệp vụ (tính từ ngày mở nếu chưa từng hoạt động), và có khoản tiền vào từ 100.000.000 trở lên | 30 | `DORMANT_ACCOUNT_REACTIVATED` |
| `NEW_ACCOUNT_LARGE_TRANSFER` | `NEW_ACCOUNT_LARGE_TRANSFER` | chuyển tiền vào hoặc ra từ 200.000.000 trở lên trên tài khoản mở chưa đầy 30 ngày tính đến ngày giá trị | 25 | `NEW_ACCOUNT_LARGE_TRANSFER` |
| `PEP_LARGE_TRANSACTION` | `PEP_LARGE_TRANSACTION` | khách hàng là PEP và số tiền từ 500.000.000 trở lên | 20 | `PEP_LARGE_TRANSACTION` |

`XA`, `XB` và `XC` là các mã mà ISO 3166 dành cho mục đích sử dụng riêng; ở đây chúng đại diện cho danh sách quốc gia, vùng lãnh thổ rủi ro cao của tổ chức bạn. Riêng giao dịch tiền mặt lớn chỉ được 10 điểm: giao dịch đó phải được báo cáo, nhưng bản thân nó không đáng ngờ.

Các luật so sánh `money` với `money`, không bao giờ với số thông thường, và tính ngưỡng bằng số học trên tiền. Ngày lấy từ tài khoản và từ đầu vào ngầm định `businessDate`:

```json
{ "id": "STRUCTURING",
  "when": "history.cashDepositsLast7d.filter(d, d >= money(300000000, 'VND') * decimal('0.8') && d < money(300000000, 'VND')).size() >= 3",
  "then": { "alert": "'STRUCTURING'", "score": "50" }, "reasonCode": "POSSIBLE_STRUCTURING" },
{ "id": "RAPID_IN_OUT",
  "when": "history.totalInLast24h >= (account.riskRating == 'HIGH' ? money(100000000, 'VND') : money(500000000, 'VND')) && history.totalOutLast24h >= history.totalInLast24h * decimal('0.9')",
  "then": { "alert": "'RAPID_IN_OUT'", "score": "40" }, "reasonCode": "RAPID_MOVEMENT_OF_FUNDS" },
{ "id": "DORMANT_REACTIVATED",
  "when": "daysBetween(has(account.lastActivityBeforeToday) ? account.lastActivityBeforeToday : account.openedOn, businessDate) >= 365 && transaction.type in ['CASH_DEPOSIT', 'TRANSFER_IN'] && transaction.amount >= money(100000000, 'VND')",
  "then": { "alert": "'DORMANT_ACCOUNT'", "score": "30" }, "reasonCode": "DORMANT_ACCOUNT_REACTIVATED" }
```

`RAPID_IN_OUT` nhân tiền vào với 90% thay vì chia tiền ra cho tiền vào, nên không thể chia cho không. Nếu cần một tỷ lệ, hãy viết với số tiền dạng decimal, `history.totalOutLast24h.amount() / history.totalInLast24h.amount()`, vì chia tiền cho tiền là lỗi kiểu.

### Chọn hành động

[`aml_alert_action.stone.json`](../../examples/aml_alert_action.stone.json) dùng `FIRST` để chuyển tổng điểm thành hành động được khuyến nghị. Đầu ra thứ hai, `fileCtr`, cho biết có phải lập báo cáo giao dịch tiền mặt lớn hay không, bất kể hành động là gì:

| Thứ tự | Luật | `action` | `fileCtr` | Mã lý do | Khi |
|---|---|---|---|---|---|
| 1 | `FILE_STR` | `FILE_STR` | `hasLargeCash` | `SUSPICION_SCORE_HIGH` | điểm từ 70 trở lên |
| 2 | `ANALYST_REVIEW` | `ANALYST_REVIEW` | `hasLargeCash` | `SUSPICION_SCORE_ELEVATED` | điểm từ 30 trở lên |
| 3 | `FILE_CTR_ONLY` | `FILE_CTR_ONLY` | `true` | `LARGE_CASH_REPORTABLE` | giao dịch tiền mặt lớn, không có dấu hiệu đáng ngờ nào khác |
| 4 | `NO_ACTION` | `NO_ACTION` | `false` | — | mọi trường hợp còn lại |

`FILE_STR` nghĩa là: chuẩn bị báo cáo giao dịch đáng ngờ cho cán bộ tuân thủ, người quyết định có gửi báo cáo hay không.

Giống như ví dụ gian lận thẻ, ứng dụng nối tiếp hai bảng: cộng các điểm số và suy ra `hasLargeCash` từ các cảnh báo. Cả hai lần đánh giá dùng cùng một ngày nghiệp vụ, nên luật về tài khoản ngủ đông và việc chọn phiên bản thống nhất với nhau:

```java
record Account(LocalDate openedOn, LocalDate lastActivityBeforeToday, String riskRating, boolean customerIsPep) {}

record Transaction(Money amount, String type, String counterpartyCountry, LocalDate valueDate) {}

record History(List<Money> cashDepositsLast7d, Money totalInLast24h, Money totalOutLast24h) {}

DecisionResult alerts = engine.evaluate(DecisionRef.of("aml_transaction_monitoring").asOf(businessDate),
        Facts.of("account", account, "transaction", transaction, "history", history));

long riskScore = ((List<?>) alerts.output().get("score")).stream()
        .mapToLong(score -> (Long) score)
        .sum();
boolean hasLargeCash = ((List<?>) alerts.output().get("alert")).contains("LARGE_CASH");

DecisionResult action = engine.evaluate(DecisionRef.of("aml_alert_action").asOf(businessDate),
        Facts.of("riskScore", riskScore, "hasLargeCash", hasLargeCash));
```

Các giao dịch mẫu với ngày nghiệp vụ là 2 tháng 11 năm 2026, cũng là ngày giá trị (số tiền tính bằng VND; lịch sử đã bao gồm chính giao dịch đó):

| Tài khoản | Giao dịch | Lịch sử | Cảnh báo (điểm) | Điểm | `action.output()` (luật) |
|---|---|---|---|---|---|
| mở năm 2019, hoạt động gần nhất 2026-10-30, rủi ro `LOW` | `TRANSFER_IN` 15.000.000 từ VN | vào 15.000.000, ra 2.000.000 | không có | 0 | `{action=NO_ACTION, fileCtr=false}` (`NO_ACTION`) |
| như trên | `CASH_DEPOSIT` 350.000.000 | nộp tiền mặt 350.000.000 | `LARGE_CASH` (10) | 10 | `{action=FILE_CTR_ONLY, fileCtr=true}` (`FILE_CTR_ONLY`) |
| như trên | `CASH_DEPOSIT` 290.000.000 | nộp tiền mặt 250.000.000, 270.000.000, 290.000.000 | `STRUCTURING` (50) | 50 | `{action=ANALYST_REVIEW, fileCtr=false}` (`ANALYST_REVIEW`) |
| như trên | `TRANSFER_OUT` 950.000.000 tới XB | vào 1.000.000.000, ra 950.000.000 | `RAPID_IN_OUT`, `HIGH_RISK_COUNTRY` (40, 30) | 70 | `{action=FILE_STR, fileCtr=false}` (`FILE_STR`) |
| mở năm 2018, hoạt động gần nhất 2025-09-01 | `TRANSFER_IN` 180.000.000 từ VN | vào 180.000.000 | `DORMANT_ACCOUNT` (30) | 30 | `{action=ANALYST_REVIEW, fileCtr=false}` (`ANALYST_REVIEW`) |
| mở 2026-10-20, chưa từng hoạt động | `TRANSFER_IN` 250.000.000 từ XA | vào 250.000.000 | `HIGH_RISK_COUNTRY`, `NEW_ACCOUNT_LARGE_TRANSFER` (30, 25) | 55 | `{action=ANALYST_REVIEW, fileCtr=false}` (`ANALYST_REVIEW`) |
| PEP, rủi ro `HIGH`, hoạt động gần nhất 2026-10-28 | `CASH_DEPOSIT` 600.000.000 | nộp tiền mặt 600.000.000 | `LARGE_CASH`, `PEP_LARGE_TRANSACTION` (10, 20) | 30 | `{action=ANALYST_REVIEW, fileCtr=true}` (`ANALYST_REVIEW`) |
| PEP, rủi ro `HIGH`, hoạt động gần nhất 2026-11-01 | `CASH_DEPOSIT` 520.000.000 | nộp tiền mặt 245.000.000, 255.000.000, 265.000.000, 520.000.000 | `LARGE_CASH`, `STRUCTURING`, `PEP_LARGE_TRANSACTION` (10, 50, 20) | 80 | `{action=FILE_STR, fileCtr=true}` (`FILE_STR`) |

Với giao dịch thứ tư, `alerts.output()` in ra:

```
{alert=[RAPID_IN_OUT, HIGH_RISK_COUNTRY], score=[40, 30]}
```

và `alerts.reasonCodes()` in ra `[RAPID_MOVEMENT_OF_FUNDS, HIGH_RISK_JURISDICTION]`.

Những điểm cần lưu ý:

- **Dương tính giả.** Tên người Việt dùng chung một số ít họ và tên đệm, nên những người khác nhau thường đạt điểm cao: `Tran Thi Bich Hong` đạt 0.9660 và `Tran Thi Mai` đạt 0.8778 khi so với `Trần Thị Bích Ngọc`. Mỗi cảnh báo đều tốn thời gian của chuyên viên, và quá nhiều cảnh báo sẽ che lấp những cảnh báo thật. Chính các thuộc tính bổ trợ, như ngày sinh trong `SANCTIONS_MATCH`, mới làm cho việc khớp tên trở nên chính xác.
- **Âm tính giả.** Bỏ sót một người có trong danh sách nghiêm trọng hơn nhiều so với sinh thêm một cảnh báo. Tên ở hệ chữ viết khác, tên chuyển tự, tên gọi thân mật và danh xưng không được so khớp (xem [So khớp gần đúng](fuzzy-matching.md#hạn-chế)); hãy ghi các cách viết đã biết thành bí danh. Trong giám sát, một ngưỡng số tiền cố định sẽ khiến người ta chia nhỏ khoản thanh toán xuống ngay dưới ngưỡng, đó chính là điều `STRUCTURING` tìm kiếm, và kẻ rửa tiền sẽ thích nghi với mọi luật mà họ đoán được.
- **Hiệu chỉnh ngưỡng trên dữ liệu của bạn.** Các khoảng 0.92 và 0.85, các số tiền và điểm số ở đây đều là giả định. Hãy hiệu chỉnh chúng trên các mẫu đã gán nhãn từ khách hàng và giao dịch của chính bạn (xem [Chọn ngưỡng](fuzzy-matching.md#chọn-ngưỡng)), lấy ngưỡng báo cáo từ các quy định áp dụng cho tổ chức của bạn, lập hồ sơ cho các lựa chọn, và kiểm thử cả hai phía của mọi ngưỡng, như `AmlExampleTest` đã làm: `Tram Van Bich Ngoc` đạt đúng 0.9200 và là trường hợp có thể khớp, còn `Tran Kim Bich Ngoc` đạt 0.9181 và chỉ là khớp yếu.
- **`bestScore` so sánh họ tên đầy đủ với các tên chính.** Một kết quả khớp tìm được qua bí danh có thể có `bestScore` thấp, như ở dòng sàng lọc thứ tư. Mã lý do cho biết luật nào đã khớp; chuyên viên vẫn cần xem mục nào và tên nào đã khớp, điều mà bảng không trả về.
- **Không phải hệ thống sàng lọc được chứng nhận.** `CLEAR` nghĩa là "các luật này không đưa ra cảnh báo", không phải khách hàng đã được xác minh an toàn. `BLOCK` và `FILE_STR` là khuyến nghị cho những người ra quyết định theo chính sách AML của tổ chức bạn.
- **Cập nhật danh sách theo dõi.** Nạp các danh sách chính thức và danh sách nội bộ từ nguồn của chúng ở mỗi lần cập nhật, sàng lọc lại khách hàng hiện hữu khi danh sách thay đổi, và ghi lại phiên bản danh sách mà mỗi lần sàng lọc đã dùng, bên cạnh `ruleVersion()` của quyết định. RuleStone không quản lý danh sách.
- **Danh sách lớn.** `watchlist.exists(...)` là một comprehension, mặc định giới hạn 10.000 vòng lặp (xem [Giới hạn](expressions.md#giới-hạn)). Với danh sách lớn, hãy đo độ trễ, nâng giới hạn bằng `maxIterations`, hoặc dùng một dịch vụ sàng lọc chuyên dụng.
- **Không bao giờ ghi log tên.** Tên khách hàng và tên trong danh sách theo dõi là dữ liệu cá nhân. Lỗi khi đánh giá và vết thực thi không bao giờ chứa giá trị đầu vào; hãy giữ nguyên tắc đó trong log của bạn, và lưu mã lý do, `bestScore` và `ruleVersion()` thay vì tên.
- **Một loại tiền tệ.** Mọi số tiền phải bằng VND. Một giao dịch bằng loại tiền khác làm việc đánh giá thất bại với lỗi `money currencies differ` thay vì âm thầm lọt qua các ngưỡng: hãy quy đổi số tiền trước khi giám sát, hoặc viết luật riêng cho từng loại tiền tệ.
- **Thống nhất về lịch sử giao dịch.** `cashDepositsLast7d`, `totalInLast24h` và `totalOutLast24h` có tính giao dịch hiện tại, còn `lastActivityBeforeToday` không tính ngày nghiệp vụ. Hãy ghi lại các quy ước như vậy bên cạnh các bảng; luật không thể tự kiểm tra chúng.
