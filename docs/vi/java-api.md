---
source: java-api.md
sourceHash: 92b48a3542edc8279eeaa6abf8b43b8440a9e73b5f7c225bb566307fae953e7a
---

# Java API

Trang này giải thích cách nhúng RuleStone vào một ứng dụng Java: tạo engine, truyền đầu vào, đọc kết quả, xử lý lỗi và nạp lại luật. Để xem đầy đủ chữ ký phương thức, hãy xem Javadoc (`mvn javadoc:javadoc`, hoặc tệp `-javadoc.jar` của từng mô-đun).

- [Các mô-đun](#các-mô-đun)
- [Tạo engine](#tạo-engine)
- [Đánh giá một quyết định](#đánh-giá-một-quyết-định)
- [Đầu vào: `Facts`](#đầu-vào-facts)
- [Tiền, ngày và ngày nghiệp vụ](#tiền-ngày-và-ngày-nghiệp-vụ)
- [Kết quả: `DecisionResult`](#kết-quả-decisionresult)
- [Lỗi](#lỗi)
- [Nạp lại luật](#nạp-lại-luật)
- [Đa luồng và hiệu năng](#đa-luồng-và-hiệu-năng)
- [Dùng biểu thức trực tiếp](#dùng-biểu-thức-trực-tiếp)

## Các mô-đun

| Artifact | Package | Công dụng |
|---|---|---|
| `rulestone-api` | `vn.io.dxa.rulestone.api` | Các kiểu mà mã ứng dụng của bạn gọi tới: `RuleStoneEngine`, `DecisionRef`, `Facts`, `DecisionResult` và các ngoại lệ. Chỉ phụ thuộc JDK. |
| `rulestone-core` | `vn.io.dxa.rulestone.core` | Engine nhúng, bảng quyết định và định dạng `.stone.json`. |
| `rulestone-cel` | `vn.io.dxa.rulestone.cel` | Ngôn ngữ biểu thức. Được `rulestone-core` sử dụng; chỉ dùng trực tiếp khi cần đánh giá các biểu thức độc lập. |
| `rulestone-bom` | — | Import để đồng bộ phiên bản. |

Hãy khai báo phụ thuộc vào `rulestone-core`; mô-đun này kéo theo hai mô-đun còn lại. Mã chỉ *đánh giá* quyết định thì không cần gì ngoài các kiểu trong `api`, nên phần lớn mã nguồn của bạn có thể không phụ thuộc vào `core`:

```java
class LoanService {
    private final RuleStoneEngine rules;   // from vn.io.dxa.rulestone.api

    LoanService(RuleStoneEngine rules) {
        this.rules = rules;
    }
}
```

## Tạo engine

```java
RuleStoneEngine engine = RuleStone.builder()
        .clock(Clock.system(ZoneId.of("Asia/Ho_Chi_Minh")))
        .decision(DecisionTableJson.read(Path.of("rules/loan_eligibility.v2.stone.json")))
        .decision(DecisionTableJson.read(Path.of("rules/loan_eligibility.v3.stone.json")))
        .decisions(otherTables)
        .maxIterations(10_000)
        .currencyScale("XAU", 4)
        .build();
```

| Phương thức | Mục đích |
|---|---|
| `clock(Clock)` | Xác định "hôm nay" khi yêu cầu không có ngày `asOf`. Mặc định dùng múi giờ mặc định của JVM; **hãy đặt tường minh** theo múi giờ nghiệp vụ của ngân hàng bạn. |
| `decision(DecisionTable)` / `decisions(Collection)` | Đăng ký bảng quyết định. Hãy đăng ký mọi phiên bản của một quyết định; engine chọn một phiên bản cho mỗi yêu cầu. |
| `maxIterations(int)` | Giới hạn trên của số vòng lặp trong mọi hàm trên danh sách, ở mọi luật. Mặc định 10.000. |
| `currencyScale(String, int)` | Số chữ số sau dấu thập phân mà `money.round()` dùng cho một loại tiền tệ, ghi đè ISO 4217. Bắt buộc với các mã không có đơn vị nhỏ nhất trong ISO 4217, như `XAU`. Ném `IllegalArgumentException` nếu mã không phải mã ISO 4217 viết hoa, hoặc số chữ số nằm ngoài khoảng 0 đến 18. |
| `build()` | Biên dịch và kiểm tra kiểu mọi luật, kiểm tra các phiên bản trùng lặp và chồng lấn, rồi trả về một engine bất biến. Ném `InvalidDecisionException` liệt kê mọi lỗi. |

`DecisionTableJson.read(...)` nhận một `String`, một `InputStream` (đọc theo UTF-8) hoặc một `Path`, và ném `InvalidDecisionException` khi tệp có bất kỳ lỗi nào. Hãy nạp tệp từ bất cứ nơi nào bạn lưu chúng: classpath, một thư mục, một cột trong cơ sở dữ liệu hay một dịch vụ cấu hình.

Bạn cũng có thể tạo một `DecisionTable` bằng Java, chẳng hạn trong kiểm thử:

```java
DecisionTable table = new DecisionTable(
        "risk_band", "1", Optional.empty(), Optional.empty(), Optional.empty(),
        HitPolicy.FIRST,
        Map.of("score", ValueType.INT),
        Map.of("band", ValueType.STRING),
        List.of(
                Rule.of("HIGH", "score < 500", Map.of("band", "'HIGH'"), "LOW_SCORE"),
                Rule.of("NORMAL", "true", Map.of("band", "'NORMAL'"))));
```

## Đánh giá một quyết định

```java
DecisionResult result = engine.evaluate(
        DecisionRef.of("loan_eligibility").asOf(applicationDate),
        Facts.of("applicant", applicant, "amount", amount));
```

`DecisionRef` chọn quyết định và phiên bản:

| Biểu thức | Phiên bản được dùng |
|---|---|
| `DecisionRef.of("loan_eligibility")` | Phiên bản có hiệu lực hôm nay, theo đồng hồ của engine. |
| `DecisionRef.of("loan_eligibility").asOf(date)` | Phiên bản có hiệu lực vào ngày `date`. **Nên dùng**: kết quả không phụ thuộc vào thời điểm mã chạy. |
| `DecisionRef.of("loan_eligibility").version("2")` | Đúng phiên bản `2`, bất kể ngày. Dùng để chạy lại và điều tra. |

Hãy dùng ngày nghiệp vụ của giao dịch cho `asOf`: ngày nộp hồ sơ với khoản vay, ngày giá trị với khoản thanh toán. Khi đó đánh giá lại cùng một hồ sơ về sau vẫn cho cùng câu trả lời. Ngày này cũng được chuyển tới các luật qua đầu vào ngầm định `businessDate`; nếu không có `asOf`, đó là ngày hôm nay theo đồng hồ của engine, kể cả khi bạn chỉ định phiên bản.

## Đầu vào: `Facts`

`Facts` là một tập bất biến các đầu vào có tên.

```java
Facts.of("termMonths", 12, "balance", new BigDecimal("6000000000"));
Facts.from(Map.of("applicant", applicant, "amount", amount));
Facts.empty().with("applicant", applicant).with("amount", amount);
```

Tên phải là duy nhất và giá trị không được là `null`. Các fact mà quyết định không khai báo sẽ bị bỏ qua, nên bạn có thể truyền một tập fact dùng chung cho nhiều quyết định. Ngoại lệ là `businessDate`, tên này được dành riêng: một fact mang tên đó làm việc đánh giá thất bại với `DecisionEvaluationException`. Hãy truyền ngày qua `DecisionRef.asOf`.

Giá trị được chuyển đổi sang các kiểu khai báo trong `inputs` của bảng:

| Kiểu khai báo | Giá trị Java được chấp nhận |
|---|---|
| `bool` | `Boolean` |
| `int` | `Long`, `Integer`, `Short`, `Byte`, `BigInteger` trong phạm vi 64 bit |
| `string` | `String`, mọi `enum` (truyền dưới dạng `name()`) |
| `decimal` | `BigDecimal`, hoặc một số nguyên. **`Double` và `Float` bị từ chối.** |
| `money` | chỉ `Money`. `BigDecimal` và các kiểu số bị từ chối, vì chúng không có loại tiền tệ. |
| `date` | chỉ `LocalDate`. Chuỗi, `LocalDateTime`, `Instant` và các kiểu khác bị từ chối. |
| danh sách `["…"]` | mọi `Collection`, từng phần tử được chuyển đổi |
| struct `{…}` | một `Map` có khoá `String`, hoặc một `record`; từng trường đã khai báo được chuyển đổi |

Với struct, chỉ các trường đã khai báo được đọc; các mục khác trong map và các component khác của record bị bỏ qua. Một trường đã khai báo mà không có trong `Map`, hoặc có giá trị `null` trong `Map` hay record, được coi là chưa đặt (xem [trường bị thiếu](expressions.md#struct-danh-sách-và-trường-bị-thiếu)).

Một record phải có component cho **mọi** trường đã khai báo. Một record thiếu component, chẳng hạn vì gõ sai tên, làm việc đánh giá thất bại với lỗi `record Applicant has no component named 'monthlyIncome'`, thay vì âm thầm coi trường đó là chưa đặt. Với các trường thực sự là tuỳ chọn, hãy dùng `Map`, hoặc một component của record có thể nhận `null`.

Record là cách đơn giản nhất để truyền các đối tượng nghiệp vụ:

```java
record Applicant(int age, BigDecimal monthlyIncome, BigDecimal monthlyDebt,
                 String segment, boolean blacklisted) {}
```

Với các lớp khác, như JavaBean hay entity JPA, hãy chuyển sang `Map` hoặc record ở ranh giới gọi. Cách này giữ cho các proxy lazy-loading và các trường không liên quan nằm ngoài quyết định của bạn.

## Tiền, ngày và ngày nghiệp vụ

`Money` (trong `vn.io.dxa.rulestone.api`) là một record gồm số tiền kiểu `BigDecimal` và mã tiền tệ ISO 4217:

```java
Money price = Money.of("1500000.50", "VND");       // or Money.of(new BigDecimal("1500000.50"), "VND")
price.amount();                                     // 1500000.50
price.currency();                                   // "VND"
price.toString();                                   // "1500000.50 VND"
```

Constructor và các phương thức factory từ chối số tiền `null` và mã tiền tệ không phải mã ISO 4217 viết hoa. `Money` không bao giờ làm tròn. Giống như `BigDecimal`, `equals` của nó phân biệt scale (`1.0 VND` không bằng `1.00 VND`); hãy so sánh số tiền bằng `compareTo` khi điều đó quan trọng. Bên trong luật, `==` so sánh theo giá trị số.

Ngày là `java.time.LocalDate`, cả ở đầu vào lẫn đầu ra. Mọi luật cũng đọc được `businessDate`, tức ngày của yêu cầu (xem [Ngày nghiệp vụ](expressions.md#ngày-nghiệp-vụ)). Ví dụ, bảng sau thu phí thẻ thường niên bằng 0,1% chi tiêu trong năm, miễn phí cho khách hàng dưới 25 tuổi:

```json
{
  "id": "card_fee",
  "version": "1",
  "hitPolicy": "FIRST",
  "inputs":  { "customer": { "birthDate": "date" }, "spend": "money" },
  "outputs": { "fee": "money", "nextReview": "date" },
  "rules": [
    { "id": "YOUTH", "when": "yearsBetween(customer.birthDate, businessDate) < 25",
      "then": { "fee": "money(0, spend.currency())", "nextReview": "businessDate.plusYears(1)" },
      "reasonCode": "YOUTH_WAIVER" },
    { "id": "STANDARD", "when": "true",
      "then": { "fee": "(spend * decimal('0.001')).round()", "nextReview": "businessDate.plusMonths(6)" } }
  ]
}
```

```java
DecisionResult result = engine.evaluate(
        DecisionRef.of("card_fee").asOf(LocalDate.of(2026, 11, 1)),
        Facts.of("customer", Map.of("birthDate", LocalDate.of(1990, 5, 20)),
                 "spend", Money.of("123456789", "VND")));

result.output();   // {fee=123457 VND, nextReview=2027-05-01}
```

## Kết quả: `DecisionResult`

| Phương thức | Nội dung |
|---|---|
| `output()` | `Map<String, Object>` chứa các đầu ra, theo thứ tự khai báo trong bảng. Giá trị có kiểu `Boolean`, `Long`, `String`, `BigDecimal`, `Money`, `LocalDate` hoặc `List`. Với `FIRST` và `UNIQUE`, map chỉ chứa các đầu ra do luật khớp đặt; với `COLLECT`, mọi đầu ra đều là danh sách. |
| `reasonCodes()` | Mã lý do của (các) luật khớp. |
| `matched()` | Có luật nào khớp hay không. |
| `matchedRules()` | Id của các luật khớp, theo thứ tự. |
| `trace()` | Từng luật đã được đánh giá kèm việc nó có khớp hay không, dưới dạng `TraceStep(ruleId, matched)`. |
| `ruleVersion()` | `<version>@sha256:<hash>`, xác định chính xác nội dung luật đã dùng. |
| `decisionId()` | Id của quyết định. |

Với mỗi quyết định nghiệp vụ, hãy lưu ít nhất `decisionId`, `ruleVersion`, `output`, `reasonCodes` và `trace`. Cùng với đầu vào, chúng cho phép bạn giải thích và tái lập quyết định. Không giá trị nào trong số này chứa giá trị đầu vào, nên có thể ghi log an toàn; bản thân đầu vào thì cần được xử lý theo quy trình bảo vệ dữ liệu thông thường của bạn.

Với `FIRST` hoặc `UNIQUE`, luôn xử lý trường hợp "không có luật nào khớp":

```java
if (!result.matched()) {
    // the table has no rule for this case
}
```

hoặc kết thúc bảng bằng một luật `"when": "true"` để mọi trường hợp đều có câu trả lời.

## Lỗi

Mọi ngoại lệ đều kế thừa `RuleStoneException`, một unchecked exception.

| Ngoại lệ | Khi nào | Cách xử lý |
|---|---|---|
| `InvalidDecisionException` (core) | Khi nạp tệp hoặc tạo engine. `problems()` liệt kê mọi lỗi kèm vị trí. | Sửa định nghĩa. Lúc khởi động, hãy dừng ngay (fail fast); khi nạp lại, hãy giữ engine trước đó (xem bên dưới). |
| `DecisionNotFoundException` (api) | Không có quyết định với id đó, không có phiên bản đó, hoặc không có phiên bản nào có hiệu lực vào ngày đó. | Lỗi cấu hình: kiểm tra các phiên bản đã đăng ký và ngày hiệu lực của chúng. |
| `DecisionEvaluationException` (api) | Đầu vào không hợp lệ (thiếu, sai kiểu, số dấu phẩy động cho một decimal, một fact tên `businessDate`), một biểu thức lỗi lúc chạy, hoặc một bảng `UNIQUE` có nhiều luật cùng khớp. `decisionId()` và `ruleVersion()` xác định quyết định. | Coi như lỗi hệ thống đối với hồ sơ này, ví dụ chuyển sang xem xét thủ công. **Không thay bằng một quyết định mặc định.** |

Thông báo ngoại lệ nêu tên quyết định, luật, đầu vào hoặc trường liên quan, không bao giờ nêu giá trị đầu vào. Hãy ghi log chúng như mọi lỗi khác.

## Nạp lại luật

Engine là bất biến. Để thay đổi luật lúc đang chạy, hãy tạo engine mới rồi hoán đổi một cách nguyên tử:

```java
private final AtomicReference<RuleStoneEngine> engine = new AtomicReference<>(loadRules());

void reload() {
    try {
        engine.set(loadRules());          // new requests use the new rules
    } catch (InvalidDecisionException e) {
        log.error("Rules not reloaded; still using the previous version:\n{}", e.getMessage());
    }
}

DecisionResult evaluate(DecisionRef ref, Facts facts) {
    return engine.get().evaluate(ref, facts);
}
```

Vì `build()` kiểm tra mọi thứ trước, một thay đổi không hợp lệ không bao giờ thay thế được bộ luật đang chạy tốt, và một yêu cầu không bao giờ thấy tập luật nạp dở dang. Yêu cầu nào đã bắt đầu trên engine cũ thì kết thúc trên engine cũ.

## Đa luồng và hiệu năng

- Engine là **bất biến và an toàn đa luồng**. Hãy tạo một lần và dùng chung.
- `build()` thực hiện toàn bộ việc phân tích cú pháp và kiểm tra kiểu. `evaluate()` chỉ chuyển đổi đầu vào và chạy các biểu thức đã biên dịch sẵn.
- Đầu vào được chuyển đổi một lần cho mỗi lần đánh giá, rồi dùng chung cho mọi luật của bảng.
- Việc đánh giá không thực hiện I/O và không giữ khoá (lock) nào.

## Dùng biểu thức trực tiếp

`rulestone-cel` đánh giá từng biểu thức riêng lẻ mà không cần bảng quyết định, chẳng hạn để kiểm tra một công thức nhập trên màn hình quản trị:

```java
ExpressionEnvironment env = ExpressionEnvironment.builder()
        .variable("amount", ValueType.DECIMAL)
        .variable("tenorMonths", ValueType.INT)
        .build();

CelExpression fee = env.compile("(amount * decimal('0.01') / tenorMonths).round(0)", ValueType.DECIMAL);

Bindings inputs = env.bind(Map.of("amount", new BigDecimal("12000000"), "tenorMonths", 12));
BigDecimal value = (BigDecimal) fee.evaluate(inputs);   // 10000
```

`compile` ném `ExpressionException` khi có lỗi cú pháp hoặc lỗi kiểu. `bind` ném `InvalidValueException`, với `path()` cho biết đầu vào gây lỗi. Biểu thức đã biên dịch và môi trường đều bất biến và an toàn đa luồng.

`ExpressionEnvironment` không có `businessDate` ngầm định; hãy khai báo một biến `ValueType.DATE` nếu biểu thức cần đến. `ExpressionEnvironment.builder().currencyScale("XAU", 4)` cấu hình đơn vị nhỏ nhất cho `money.round()`, giống như `RuleStone.builder().currencyScale` làm với bảng quyết định. Biểu thức kiểu `ValueType.MONEY` trả về `Money`, còn kiểu `ValueType.DATE` trả về `LocalDate`.
