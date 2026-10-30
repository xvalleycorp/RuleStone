---
source: java-api.md
sourceHash: 73a4fbb7e258dbcb87c63762069a30d5af6c5581dc9c9becdf9952f329d81a4e
---

# Java API

Trang này giải thích cách nhúng RuleStone vào một ứng dụng Java: tạo engine, truyền đầu vào, đọc kết quả, xử lý lỗi và nạp lại luật. Để xem đầy đủ chữ ký phương thức, hãy xem Javadoc (`mvn javadoc:javadoc`, hoặc tệp `-javadoc.jar` của từng mô-đun).

- [Các mô-đun](#các-mô-đun)
- [Tạo engine](#tạo-engine)
- [Đánh giá một quyết định](#đánh-giá-một-quyết-định)
- [Thời gian chờ tối đa](#thời-gian-chờ-tối-đa)
- [Đầu vào: `Facts`](#đầu-vào-facts)
- [Tiền, ngày và ngày nghiệp vụ](#tiền-ngày-và-ngày-nghiệp-vụ)
- [Kết quả: `DecisionResult`](#kết-quả-decisionresult)
- [Bản ghi quyết định và kiểm toán](#bản-ghi-quyết-định-và-kiểm-toán)
- [Fact và kết quả dạng JSON: `FactsJson`](#fact-và-kết-quả-dạng-json-factsjson)
- [Lỗi](#lỗi)
- [Nạp lại luật](#nạp-lại-luật)
- [Phiên bản canary và phiên bản bóng](#phiên-bản-canary-và-phiên-bản-bóng)
- [Đa luồng và hiệu năng](#đa-luồng-và-hiệu-năng)
- [Dùng biểu thức trực tiếp](#dùng-biểu-thức-trực-tiếp)

## Các mô-đun

| Artifact | Package | Công dụng |
|---|---|---|
| `rulestone-api` | `vn.io.dxa.rulestone.api` | Các kiểu mà mã ứng dụng của bạn gọi tới: `RuleStoneEngine`, `DecisionRef`, `Facts`, `DecisionResult` và các ngoại lệ. Chỉ phụ thuộc JDK. |
| `rulestone-core` | `vn.io.dxa.rulestone.core` | Engine nhúng, bảng quyết định và định dạng `.stone.json`. |
| `rulestone-cel` | `vn.io.dxa.rulestone.cel` | Ngôn ngữ biểu thức. Được `rulestone-core` sử dụng; chỉ dùng trực tiếp khi cần đánh giá các biểu thức độc lập. |
| `rulestone-audit-kafka` | `vn.io.dxa.rulestone.audit.kafka` | Audit sink đẩy bản ghi quyết định lên Kafka; xem [Đẩy bản ghi lên Kafka](#đẩy-bản-ghi-lên-kafka). |
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
| `flow(DecisionFlow)` / `flows(Collection)` | Đăng ký [luồng quyết định](flows.md), đọc bằng `DecisionFlowJson.read(...)`. Hãy đăng ký các bảng mà luồng dùng với cùng builder. |
| `maxIterations(int)` | Giới hạn trên của số vòng lặp trong mọi hàm trên danh sách, ở mọi luật. Mặc định 10.000. |
| `currencyScale(String, int)` | Số chữ số sau dấu thập phân mà `money.round()` dùng cho một loại tiền tệ, ghi đè ISO 4217. Bắt buộc với các mã không có đơn vị nhỏ nhất trong ISO 4217, như `XAU`. Ném `IllegalArgumentException` nếu mã không phải mã ISO 4217 viết hoa, hoặc số chữ số nằm ngoài khoảng 0 đến 18. |
| `auditSink(AuditSink)` | Nhận `DecisionRecord` của mọi lần đánh giá, dù thành công hay thất bại. Có thể gọi nhiều lần; các sink chạy theo thứ tự. Xem [Bản ghi quyết định và kiểm toán](#bản-ghi-quyết-định-và-kiểm-toán). |
| `factsHashKey(byte[])` | Biến `DecisionRecord.factsHash()` thành HMAC-SHA256 với khoá này (tối thiểu 16 byte; nên dùng 32 byte ngẫu nhiên từ kho khoá của bạn). Nếu không có khoá, hash là SHA-256 thông thường. |
| `recordIds(Supplier<String>)` | Nguồn sinh id cho bản ghi. Mặc định là UUID ngẫu nhiên. |
| `timeout(Duration)` | Thời gian chờ tối đa mặc định của mọi lần đánh giá, cho các yêu cầu không tự đặt; xem [Thời gian chờ tối đa](#thời-gian-chờ-tối-đa). Mặc định không có. |
| `canary(String, String, double)` / `shadow(String, String)` | Chuyển một phần yêu cầu của một quyết định sang phiên bản mới, hoặc đánh giá phiên bản mới ở chế độ bóng trên mọi yêu cầu; xem [Phiên bản canary và phiên bản bóng](#phiên-bản-canary-và-phiên-bản-bóng). |
| `shadowExecutor(Executor)` | Chạy các lần đánh giá bóng trên executor này thay vì trên luồng của bên gọi. |
| `build()` | Biên dịch và kiểm tra kiểu mọi luật và mọi luồng, kiểm tra các phiên bản trùng lặp và ngày tháng mâu thuẫn (xem [Phiên bản và ngày hiệu lực](decision-tables.md#phiên-bản-và-ngày-hiệu-lực)) và việc mỗi canary và phiên bản bóng đều trỏ tới một phiên bản đã đăng ký, rồi trả về một engine bất biến. Ném `InvalidDecisionException` liệt kê mọi lỗi. |

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
| `DecisionRef.of("loan_eligibility").version("2")` | Đúng phiên bản `2`, bất kể ngày: kể cả một phiên bản chỉ có hiệu lực về sau, hoặc đã hết hiệu lực. Dùng để chạy lại, điều tra, và thử một phiên bản trước ngày hiệu lực của nó. |
| `….correlationId("LOAN-2026-000123")` | Ghi mã giao dịch nghiệp vụ của bạn (mã tương quan) vào bản ghi quyết định, để có thể đối chiếu với dữ liệu của bạn. Mã này không được chứa dữ liệu cá nhân. Dùng kết hợp được với mọi dòng ở trên. |
| `….timeout(Duration.ofMillis(2))` | Làm việc đánh giá thất bại với `DecisionTimeoutException` nếu nó kéo dài hơn mức này; xem [Thời gian chờ tối đa](#thời-gian-chờ-tối-đa). Dùng kết hợp được với mọi dòng ở trên. |

Hãy dùng ngày nghiệp vụ của giao dịch cho `asOf`: ngày nộp hồ sơ với khoản vay, ngày giá trị với khoản thanh toán. Khi đó đánh giá lại cùng một hồ sơ về sau vẫn cho cùng câu trả lời. Ngày này cũng được chuyển tới các luật qua đầu vào ngầm định `businessDate`; nếu không có `asOf`, đó là ngày hôm nay theo đồng hồ của engine, kể cả khi bạn chỉ định phiên bản.

## Thời gian chờ tối đa

Một lần đánh giá thường chỉ mất vài micro giây, nhưng một bảng có thể chạy lâu bất ngờ: một danh sách lớn trong fact, một `filter` trên lịch sử dài, một bảng `COLLECT` có nhiều luật. Khi bên gọi có hạn chót riêng, chẳng hạn một khoản thanh toán phải được quyết định trong deadline gRPC của nó, hãy giao cho việc đánh giá phần thời gian còn lại:

```java
Duration left = Duration.between(clock.instant(), deadline);
if (left.isNegative() || left.isZero()) {
    return fallback.decide(payment);
}
try {
    DecisionResult result = engine.evaluate(
            DecisionRef.of("gate_decision").asOf(valueDate).correlationId(paymentId).timeout(left),
            facts);
    return Decision.from(result);
} catch (DecisionTimeoutException e) {
    return fallback.decide(payment);   // e.trace() lists the rules examined in time
}
```

- Thời gian chờ được tính từ lúc gọi `evaluate`, theo đồng hồ đơn điệu (`System.nanoTime()`), không bao giờ theo đồng hồ của engine: nó giới hạn thời gian một lần đánh giá chạy, và không bao giờ làm thay đổi kết quả.
- Engine kiểm tra thời gian trước mỗi luật. Nó không cần luồng riêng và không bao giờ ngắt một luật giữa chừng, nên một lần đánh giá có thể vượt thời gian chờ thêm đúng thời gian của một luật. `maxIterations` giới hạn khoảng đó.
- Một lần đánh giá hết thời gian sẽ thất bại với `DecisionTimeoutException`, một `DecisionEvaluationException`, với `timeout()` và `trace()` cho biết giới hạn là bao nhiêu và những luật nào đã được xét. Bản ghi quyết định của nó có trạng thái `TIMEOUT` và cho thấy các luật đã khớp tới lúc đó. Bản ghi vẫn được gửi tới mọi audit sink, nên thời gian của các sink được cộng thêm.
- `RuleStone.builder().timeout(...)` đặt giá trị mặc định cho các yêu cầu không tự đặt. `timeout` của một yêu cầu thay thế giá trị đó, kể cả khi dài hơn.

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

Constructor và các phương thức factory từ chối số tiền `null` và mã tiền tệ không phải mã ISO 4217 viết hoa. Các mã hợp lệ và đơn vị nhỏ nhất của chúng lấy từ `Iso4217`, một bảng cố định (ISO 4217 đến bản sửa đổi 177, cùng các mã đã ngừng sử dụng mà Java vẫn chấp nhận từ lâu), không lấy từ `java.util.Currency`, vốn có danh sách mã khác nhau giữa các phiên bản JDK. `Money` không bao giờ làm tròn. Giống như `BigDecimal`, `equals` của nó phân biệt scale (`1.0 VND` không bằng `1.00 VND`); hãy so sánh số tiền bằng `compareTo` khi điều đó quan trọng. Bên trong luật, `==` so sánh theo giá trị số.

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
| `record()` | `DecisionRecord` của lần đánh giá này, cũng chính là bản ghi mà mọi audit sink đã nhận. Xem bên dưới. |

Với mỗi quyết định nghiệp vụ, hãy lưu bản ghi quyết định, hoặc cấu hình một audit sink làm việc đó. Cùng với đầu vào, bản ghi cho phép bạn giải thích và tái lập quyết định. Bản ghi không chứa giá trị đầu vào nào, nên có thể ghi log an toàn; bản thân đầu vào thì cần được xử lý theo quy trình bảo vệ dữ liệu thông thường của bạn.

Với `FIRST` hoặc `UNIQUE`, luôn xử lý trường hợp "không có luật nào khớp":

```java
if (!result.matched()) {
    // the table has no rule for this case
}
```

hoặc kết thúc bảng bằng một luật `"when": "true"` để mọi trường hợp đều có câu trả lời.

## Bản ghi quyết định và kiểm toán

Mọi lần đánh giá đều tạo ra một `DecisionRecord` (bản ghi quyết định), dù thành công hay thất bại. Bản ghi cho biết đã quyết định gì, bằng chính xác những luật nào và vì sao. Engine chuyển bản ghi cho mọi audit sink (nơi nhận bản ghi kiểm toán) trước khi trả kết quả hoặc ném exception, và một kết quả thành công cũng mang theo bản ghi qua `result.record()`.

```java
RuleStoneEngine engine = RuleStone.builder()
        .decisions(tables)
        .factsHashKey(factsHashKey)   // 32 random bytes from the bank's key store
        .auditSink(record -> auditLog.add(DecisionRecordJson.write(record)))
        .build();

DecisionResult result = engine.evaluate(
        DecisionRef.of("card_fee").asOf(LocalDate.of(2026, 11, 1)).correlationId("CARD-2026-000123"),
        Facts.of("customer", Map.of("birthDate", LocalDate.of(2002, 1, 1)),
                 "spend", Money.of("123456789", "VND")));
```

`auditLog` đại diện cho nơi bạn lưu bản ghi: một tệp log, một bảng cơ sở dữ liệu hay một Kafka topic. `DecisionRecordJson.write` chuyển bản ghi thành một dòng JSON. Với bảng `card_fee` trong mục [Tiền, ngày và ngày nghiệp vụ](#tiền-ngày-và-ngày-nghiệp-vụ), bản ghi của lần đánh giá này là:

```json
{
  "recordId": "3f6c2a8e-5b1d-4e7a-9c0f-2d8b6e4a1c75",
  "correlationId": "CARD-2026-000123",
  "decision": "card_fee",
  "version": "1@sha256:e38b29d8a5086a1e73ed2f1b54bba1a3fc32167000eebab77ca579516e4a198e",
  "businessDate": "2026-11-01",
  "evaluatedAt": "2026-11-01T02:15:04.218Z",
  "engine": "rulestone 0.1.0",
  "status": "OK",
  "factsHash": "hmac-sha256:46a89c481ab66d5224883eabd26d39e01df82bc0a44d1eed8aa82dfd9f505459",
  "output": { "fee": "0 VND", "nextReview": "2027-11-01" },
  "reasonCodes": [ { "code": "YOUTH_WAIVER", "source": "card_fee" } ],
  "path": [
    {
      "node": "card_fee",
      "type": "table",
      "version": "1@sha256:e38b29d8a5086a1e73ed2f1b54bba1a3fc32167000eebab77ca579516e4a198e",
      "matchedRules": ["YOUTH"]
    }
  ]
}
```

| Trường | Nội dung |
|---|---|
| `recordId` | Id duy nhất, là UUID ngẫu nhiên nếu bạn không đặt `recordIds`. |
| `correlationId` | Mã giao dịch nghiệp vụ của bạn, lấy từ `DecisionRef.correlationId`. Bị bỏ qua nếu bạn không truyền. |
| `decision`, `version` | Quyết định được yêu cầu và phiên bản chính xác đã dùng. `version` bị bỏ qua nếu không tìm thấy phiên bản nào. |
| `businessDate`, `evaluatedAt` | Ngày mà quyết định được đưa ra cho, và thời điểm bắt đầu đánh giá, đều lấy từ đồng hồ của engine, trừ khi `asOf` đã cho ngày. |
| `engine` | Phiên bản RuleStone, lấy từ manifest của jar; là `rulestone dev` khi chạy từ các lớp chưa đóng gói. |
| `status` | `OK`; `ERROR` nếu bên gọi nhận về một exception; `TIMEOUT` nếu việc đánh giá hết thời gian (xem [Thời gian chờ tối đa](#thời-gian-chờ-tối-đa)). Một quyết định không khớp luật nào vẫn là `OK`, không có reason code. |
| `factsHash` | Hash của các đầu vào đã khai báo; xem bên dưới. Bị bỏ qua nếu đầu vào không hợp lệ. |
| `output` | Các đầu ra, viết theo cách của `FactsJson`. Rỗng khi trạng thái không phải `OK`. |
| `reasonCodes` | Từng reason code kèm nút đã tạo ra nó. |
| `path` | Từng nút đã được đánh giá. Với một bảng quyết định, đó là bảng kèm phiên bản và các luật đã khớp; với một [luồng quyết định](flows.md#bản-ghi-quyết-định), đó là mọi nút đã đi qua, theo thứ tự. Khi việc đánh giá lỗi giữa chừng trong một bảng, trường này cho thấy các luật đã khớp trước khi lỗi. |
| `error` | Chỉ có khi trạng thái không phải `OK`: thông điệp của exception, không bao giờ chứa giá trị đầu vào. Với exception không phải `RuleStoneException`, chỉ kiểu exception được ghi lại. |
| `routing` | Chỉ có khi một phiên bản khác với phiên bản đang có hiệu lực được đánh giá, dưới dạng canary hoặc phiên bản bóng; xem [Phiên bản canary và phiên bản bóng](#phiên-bản-canary-và-phiên-bản-bóng). |

**Không có giá trị đầu vào.** Bản ghi không bao giờ chứa giá trị đầu vào. Thay vào đó, `factsHash` định danh các đầu vào: nó được tính trên các đầu vào đã khai báo ở dạng chuẩn hoá, nên các đầu vào tương đương luôn cho cùng một hash. Ở dạng chuẩn hoá này:

- các khoá được sắp xếp;
- số thập phân và số tiền không có số 0 thừa ở cuối, nên `1.0` và `1.00` cho cùng một hash, giống như luật coi chúng là bằng nhau;
- các fact không được khai báo và `businessDate` bị bỏ qua.

Để kiểm chứng một quyết định về sau, hãy tính lại hash từ đầu vào bạn đã lưu và so với bản ghi.

**Hãy dùng khoá.** Nếu không có `factsHashKey`, hash là SHA-256 thông thường. Khi đó, ai có được bản ghi có thể thử đoán các đầu vào ít biến thể, như ngày sinh, rồi so với hash. Khi có khoá, hash là HMAC-SHA256 (`hmac-sha256:…`), và chỉ người giữ khoá mới làm được việc đó. Hãy dùng cùng một khoá ở mọi nơi kiểm chứng bản ghi.

**Sink phải thành công.** Các sink chạy đồng bộ trên luồng đang đánh giá. Nếu một sink ném lỗi, việc đánh giá thất bại với `AuditException` và bên gọi không nhận được kết quả: một quyết định không bao giờ được trả về khi bản ghi của nó chưa được chấp nhận. Sink nào không được làm chậm quyết định có thể chuyển bản ghi vào hàng đợi riêng của nó. Khi việc đánh giá đã thất bại và một sink cũng lỗi, bên gọi nhận exception ban đầu, kèm `AuditException` dưới dạng exception bị nén (suppressed).

### Đẩy bản ghi lên Kafka

`rulestone-audit-kafka` đẩy bản ghi lên một topic Kafka, viết bằng `DecisionRecordJson` và dùng mã tương quan làm khoá, để các bản ghi của một giao dịch luôn theo đúng thứ tự trên cùng một partition. Nó đủ nhanh cho luồng xử lý trực tiếp (inline): nó chuyển mỗi bản ghi vào bộ đệm của producer và không chờ broker. Bạn tự cung cấp Kafka client; mô-đun được biên dịch với `kafka-clients` 4.4 và không phân phối lại thư viện này.

```xml
<dependency>
    <groupId>vn.io.dxa.rulestone</groupId>
    <artifactId>rulestone-audit-kafka</artifactId>
</dependency>
<dependency>
    <groupId>org.apache.kafka</groupId>
    <artifactId>kafka-clients</artifactId>
    <version>4.4.0</version>
</dependency>
```

```java
Producer<String, String> producer = new KafkaProducer<>(kafkaConfig, new StringSerializer(), new StringSerializer());

RuleStoneEngine engine = RuleStone.builder()
        .decisions(tables)
        .auditSink(KafkaAuditSink.builder(producer, "rulestone.decisions")
                .failWhenBufferBelow(1 << 20)   // fail at once when less than 1 MiB of buffer is free
                .onDeliveryFailure((recordId, e) -> undelivered.add(recordId))
                .build())
        .build();
```

Với một sink bất đồng bộ, "đã chấp nhận" nghĩa là producer đã nhận bản ghi:

- **Việc đánh giá thất bại** với `AuditException` khi producer từ chối bản ghi ngay lập tức: bộ đệm đã đầy, không lấy được metadata của topic kịp thời, hoặc producer đã đóng. `KafkaProducer` báo bộ đệm đầy qua một `Future` đã thất bại chứ không ném exception; sink kiểm tra điều này, nên một bản ghi như vậy không bao giờ được tính là đã chấp nhận.
- **Khi bộ đệm đầy dần**, vì broker không theo kịp hoặc không kết nối được, mặc định sink thất bại ngay: nó từ chối bản ghi khi còn trống ít hơn `failWhenBufferBelow` byte (mặc định 1 MiB; hãy dùng ít nhất bằng `batch.size` của producer). `blockWhenBufferFull()` thì để producer chờ có chỗ, tối đa `max.block.ms` của nó. Trên luồng xử lý trực tiếp, thất bại ngay an toàn hơn: phương án dự phòng của bạn quyết định, thay vì khoản thanh toán phải chờ nhật ký kiểm toán. Dù chọn cách nào, `max.block.ms` cũng là thời gian lâu nhất một lần ghi có thể bị chặn, kể cả khi producer lấy metadata của topic sau khi khởi động, nên hãy đặt nó theo mức bên gọi chịu được.
- **Một bản ghi producer đã nhận nhưng không gửi được**, sau các lần thử lại và `delivery.timeout.ms`, được báo cho `onDeliveryFailure` kèm id bản ghi, và được đếm bởi `deliveryFailures()`. Lúc đó quyết định đã được trả về, nên hãy giám sát cả hai. Hãy giữ các giá trị mặc định `acks=all` và `enable.idempotence=true` của producer.

Sink không đóng producer: hãy đóng nó khi ứng dụng dừng, sau khi engine không còn được dùng, để các bản ghi còn trong bộ đệm được gửi đi.

## Fact và kết quả dạng JSON: `FactsJson`

Khi fact đến dưới dạng JSON, ví dụ từ một REST request, một workflow engine hay một trình soạn luật, `FactsJson` (core) chuyển chúng thành các giá trị Java mà quyết định nhận, dựa trên các kiểu mà bảng khai báo. JSON không có kiểu tiền, ngày hay số thập phân chính xác, nên dùng các dạng sau:

| Kiểu | JSON | Java |
|---|---|---|
| `bool` | `true`, `false` | `Boolean` |
| `int` | một số nguyên, ví dụ `30` | `Long` |
| `string` | một chuỗi | `String` |
| `decimal` | một số, ví dụ `25750000.50`, hoặc một chuỗi theo cách viết thông thường, ví dụ `"0.0980"` | `BigDecimal`, giữ nguyên scale như khi viết |
| `money` | `"<amount> <CCY>"`, ví dụ `"1500000.50 VND"`, đúng dạng của `Money.toString()` | `Money` |
| `date` | `"yyyy-MM-dd"`, ví dụ `"2026-11-01"` | `LocalDate` |
| danh sách | một mảng | `List` |
| struct | một đối tượng | `Map` |

Với bảng `card_fee` ở trên:

```java
DecisionTable cardFee = DecisionTableJson.read(Path.of("rules/card_fee.stone.json"));

Facts facts = FactsJson.readFacts("""
        { "customer": { "birthDate": "1990-05-20" }, "spend": "123456789 VND" }""", cardFee.inputs());
DecisionResult result = engine.evaluate(DecisionRef.of("card_fee").asOf(LocalDate.of(2026, 11, 1)), facts);

String json = FactsJson.write(result.output());   // {"fee":"123457 VND","nextReview":"2027-05-01"}
```

Việc đọc là chặt chẽ. Mọi lỗi được báo cáo cùng lúc trong một `InvalidFactsException`, với `problems()` cho biết vị trí và dạng mong đợi nhưng không bao giờ nêu giá trị, ví dụ `customer.birthDate: expected a date as "yyyy-MM-dd"` hoặc `spend: expected money as "<amount> <CCY>", e.g. "1500000 VND"`. Các đầu vào và trường struct mà bảng không khai báo cũng là lỗi. Một trường struct vắng mặt, hoặc `null`, được coi là chưa đặt, nên luật nhận biết nó qua `has()`; một đầu vào vắng mặt được để engine xử lý, và engine sẽ báo là thiếu. Số không bao giờ đi qua dấu phẩy động: `readFacts(String, …)` phân tích số thập phân một cách chính xác, còn một `JsonNode` bạn tự phân tích phải được đọc với `DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS`, nếu không các số dấu phẩy động trong đó sẽ bị từ chối.

Khi ghi, kết quả có cùng các dạng đó, với số thập phân là chuỗi theo cách viết thông thường (`"0.0980"`, không bao giờ là `9.8E-2`), nên giá trị đọc lại không bị thay đổi:

| Phương thức | Chuyển đổi |
|---|---|
| `readFacts(String json, Map<String, ValueType> inputs)`, `readFacts(JsonNode, Map<String, ValueType>)`, `readFacts(JsonNode, DecisionTable)` | một đối tượng JSON chứa các đầu vào thành `Facts` |
| `readValues(JsonNode json, Map<String, ValueType> types)` | một đối tượng JSON chứa các giá trị có tên thành một `Map`, ví dụ các đầu ra kỳ vọng với kiểu lấy từ `resultTypes(table)` |
| `readValue(JsonNode json, ValueType type)` | một giá trị |
| `resultTypes(DecisionTable table)` | kiểu của các giá trị trong `DecisionResult.output()`: các đầu ra đã khai báo, mỗi đầu ra là một danh sách khi dùng `COLLECT` |
| `writeValue(Object value)`, `writeValues(Map<String, ?> values)` | giá trị Java thành một `JsonNode` hoặc một `ObjectNode` |
| `write(Map<String, ?> values)` | giá trị Java thành văn bản JSON dạng gọn |

## Lỗi

Mọi ngoại lệ đều kế thừa `RuleStoneException`, một unchecked exception.

| Ngoại lệ | Khi nào | Cách xử lý |
|---|---|---|
| `InvalidDecisionException` (core) | Khi nạp tệp hoặc tạo engine. `problems()` liệt kê mọi lỗi kèm vị trí. | Sửa định nghĩa. Lúc khởi động, hãy dừng ngay (fail fast); khi nạp lại, hãy giữ engine trước đó (xem bên dưới). |
| `InvalidFactsException` (core) | Khi đọc fact hoặc giá trị JSON bằng `FactsJson`. `problems()` liệt kê mọi lỗi kèm vị trí. | Từ chối yêu cầu vì đầu vào không hợp lệ, ví dụ trả về HTTP 400 kèm danh sách lỗi. |
| `DecisionNotFoundException` (api) | Không có quyết định với id đó, không có phiên bản đó, hoặc không có phiên bản nào có hiệu lực vào ngày đó. | Lỗi cấu hình: kiểm tra các phiên bản đã đăng ký và ngày hiệu lực của chúng. |
| `AuditException` (api) | Một audit sink ném lỗi khi nhận bản ghi quyết định; exception của sink là nguyên nhân, và `recordId()` cho biết bản ghi nào. | Coi như lỗi hệ thống: quyết định đã không được trả về và không được giả định. |
| `DecisionTimeoutException` (api) | Việc đánh giá kéo dài hơn thời gian chờ tối đa. Là một `DecisionEvaluationException`, nên hãy bắt nó trước. `timeout()` và `trace()` cho biết giới hạn là bao nhiêu và những luật nào đã được xét. | Áp dụng phương án dự phòng cho một quyết định không đưa ra kịp. |
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

## Phiên bản canary và phiên bản bóng

Trước khi một phiên bản mới của quyết định thay thế phiên bản cũ, bạn có thể chạy nó trên lưu lượng thật mà không làm thay đổi bất kỳ câu trả lời nào (phiên bản bóng), rồi để nó trả lời cho một phần nhỏ yêu cầu (canary):

```java
RuleStoneEngine engine = RuleStone.builder()
        .decisions(tables)                     // versions 1, 2 and 3 of gate_decision
        .canary("gate_decision", "2", 0.05)    // 5% of correlation ids get version 2
        .shadow("gate_decision", "3")          // version 3 is evaluated on every request, only to be recorded
        .shadowExecutor(shadowPool)            // off the caller's thread
        .auditSink(auditSink)
        .build();
```

Việc định tuyến áp dụng cho các yêu cầu không chỉ định phiên bản; yêu cầu có `DecisionRef.version` nhận đúng phiên bản đó. Cả canary lẫn phiên bản bóng đều không cần đang có hiệu lực: một phiên bản có `effectiveFrom` ở tương lai vẫn có thể được thử trên lưu lượng hôm nay, với `businessDate` của hôm nay.

**Canary.** Một yêu cầu được chuyển sang canary khi mã tương quan của nó rơi vào phần lưu lượng của canary: 64 bit đầu của SHA-256 của `"<decision id>\n<correlation id>"`, đọc như một phân số, nhỏ hơn tỷ lệ của canary. Nhờ vậy một khoản thanh toán được thử lại luôn nhận cùng phiên bản, và hai quyết định có canary chia lưu lượng độc lập với nhau. Yêu cầu không có mã tương quan luôn nhận phiên bản đang có hiệu lực. Bên gọi nhận kết quả của canary, và bản ghi của nó cho biết lý do:

```json
"routing": { "kind": "CANARY", "champion": "1@sha256:9b1d…", "share": 0.05 }
```

`version` là phiên bản canary; `champion` là phiên bản đang có hiệu lực mà yêu cầu lẽ ra đã dùng. Khi tới `effectiveFrom` của canary, nó có hiệu lực cho mọi yêu cầu, và các bản ghi không còn `routing` nữa.

**Phiên bản bóng.** Sau mỗi yêu cầu cho quyết định, engine đánh giá phiên bản bóng trên cùng các fact, với cùng `businessDate`, và gửi bản ghi của nó tới các audit sink. Bên gọi không bao giờ thấy kết quả của phiên bản bóng, và một phiên bản bóng thất bại, hoặc có bản ghi bị một sink từ chối, không bao giờ ảnh hưởng tới yêu cầu. Bản ghi của phiên bản bóng liên kết tới bản ghi của yêu cầu:

```json
"routing": { "kind": "SHADOW", "champion": "1@sha256:9b1d…", "shadowOf": "3f6c2a8e-5b1d-4e7a-9c0f-2d8b6e4a1c75" }
```

Để so sánh champion và challenger, hãy nối các bản ghi bóng qua `shadowOf` với các bản ghi có `recordId` mà nó trỏ tới, rồi so sánh `output` và `reasonCodes`. Hai bản ghi có cùng `factsHash` khi hai phiên bản khai báo cùng các đầu vào. Mặc định phiên bản bóng chạy trên luồng của bên gọi sau lần đánh giá của chính yêu cầu, nên thời gian của nó cộng vào mọi yêu cầu; với `shadowExecutor` nó chạy trên executor của bạn, và phiên bản bóng nào bị executor từ chối thì bị bỏ qua. Khi đó các fact không được thay đổi sau khi `evaluate` trả về.

Phiên bản bóng bị bỏ qua khi yêu cầu chỉ định phiên bản, khi không tìm thấy phiên bản nào cho yêu cầu, và khi yêu cầu đã dùng chính phiên bản bóng, chẳng hạn làm canary. Để đổi tỷ lệ, hoặc nâng canary thành phiên bản chính, hãy tạo engine mới rồi hoán đổi, như trong [Nạp lại luật](#nạp-lại-luật).

Canary và phiên bản bóng hoạt động y hệt với [luồng quyết định](flows.md): hãy dùng id của luồng. Bên trong một luồng, các nút `table` luôn dùng phiên bản bảng đang có hiệu lực, hoặc phiên bản mà nút ghim; canary và phiên bản bóng của các bảng đó chỉ áp dụng khi các bảng được đánh giá riêng lẻ.

## Đa luồng và hiệu năng

- Engine là **bất biến và an toàn đa luồng**. Hãy tạo một lần và dùng chung.
- `build()` thực hiện toàn bộ việc phân tích cú pháp và kiểm tra kiểu. `evaluate()` chỉ chuyển đổi đầu vào và chạy các biểu thức đã biên dịch sẵn.
- Đầu vào được chuyển đổi một lần cho mỗi lần đánh giá, rồi dùng chung cho mọi luật của bảng.
- Việc đánh giá không thực hiện I/O và không giữ khoá (lock) nào.

### Benchmark

`rulestone-benchmark` đo một lần gọi `evaluate` bằng [JMH](https://github.com/openjdk/jmh), tính trọn mọi bước: chọn phiên bản, chuyển đổi đầu vào, hash của đầu vào, các luật và bản ghi quyết định, không có audit sink. Các bảng của nó có 10, 100 hoặc 1.000 luật cộng một luật bắt mọi trường hợp; mỗi luật so một trường `money` hoặc `decimal` của một struct với một ngưỡng, hoặc duyệt một danh sách 20 struct bằng `exists`. Với `FIRST`, một nửa số luật được xét; với `COLLECT`, tất cả. Xem chi tiết trong [README của mô-đun](../../rulestone-benchmark/README.md). Hãy chạy trên phần cứng của chính bạn:

```sh
mvn -pl rulestone-benchmark -am package -DskipTests
java -jar rulestone-benchmark/target/benchmarks.jar -prof gc -t 4
```

Kết quả trên một máy ảo đám mây dùng chung: 4 vCPU (Intel Xeon, 2,8 GHz), OpenJDK 21.0.12, G1 với heap 2 GB, một fork, khởi động 15 giây và đo 10 giây cho mỗi bảng. Độ trễ tính bằng µs; lượng cấp phát tính bằng KB cho mỗi lần đánh giá.

| Hit policy | Đầu vào | Số luật | p50, 1 luồng | p99, 1 luồng | p50, 4 luồng | p99, 4 luồng | p99,9, 4 luồng | Cấp phát |
|---|---|--:|--:|--:|--:|--:|--:|--:|
| `FIRST` | `MONEY` | 10 | 27,3 | 86,7 | 21,2 | 93,7 | 489 | 44 |
| `FIRST` | `MONEY` | 100 | 65,0 | 210 | 64,8 | 198 | 612 | 138 |
| `FIRST` | `MONEY` | 1.000 | 559 | 1.252 | 541 | 1.556 | 5.391 | 1.047 |
| `FIRST` | `DECIMAL` | 10 | 18,6 | 75,4 | 20,4 | 88,2 | 370 | 44 |
| `FIRST` | `DECIMAL` | 100 | 61,6 | 162 | 64,4 | 190 | 733 | 133 |
| `FIRST` | `DECIMAL` | 1.000 | 589 | 1.319 | 566 | 1.452 | 4.974 | 997 |
| `FIRST` | `LIST` | 10 | 182 | 480 | 189 | 517 | 3.148 | 313 |
| `FIRST` | `LIST` | 100 | 1.628 | 3.530 | 1.624 | 5.816 | 9.706 | 2.405 |
| `FIRST` | `LIST` | 1.000 | 15.876 | 31.642 | 19.235 | 43.062 | 67.809 | 27.543 |
| `COLLECT` | `MONEY` | 10 | 25,5 | 106 | 27,5 | 123 | 509 | 62 |
| `COLLECT` | `MONEY` | 100 | 146 | 389 | 152 | 412 | 3.261 | 343 |
| `COLLECT` | `MONEY` | 1.000 | 1.473 | 3.257 | 1.595 | 5.063 | 9.749 | 3.054 |
| `COLLECT` | `DECIMAL` | 10 | 25,1 | 102 | 32,7 | 145 | 663 | 63 |
| `COLLECT` | `DECIMAL` | 100 | 153 | 347 | 147 | 404 | 3.273 | 346 |
| `COLLECT` | `DECIMAL` | 1.000 | 1.585 | 3.664 | 1.647 | 5.751 | 9.870 | 3.007 |
| `COLLECT` | `LIST` | 10 | 334 | 741 | 371 | 897 | 4.361 | 512 |
| `COLLECT` | `LIST` | 100 | 3.342 | 7.872 | 3.043 | 6.802 | 10.845 | 4.799 |
| `COLLECT` | `LIST` | 1.000 | 36.635 | 79.299 | 34.341 | 69.615 | 84.482 | 52.833 |

Các con số cho thấy:

- **Một bảng nhỏ mất khoảng 20 µs**, phần lớn là chi phí cố định: chuyển đổi đầu vào, hash của đầu vào và bản ghi. Mỗi luật so tiền hoặc số thập phân thêm vào khoảng 1 µs, và khoảng 1,5 µs với `COLLECT`, vì nó còn tính đầu ra của nửa số luật khớp.
- **Hàm trên danh sách chiếm phần lớn thời gian.** `exists` trên 20 struct tốn khoảng 30 µs cho mỗi luật, tức khoảng 1,5 µs cho mỗi phần tử. Hãy tránh đưa các comprehension trên danh sách dài vào bảng lớn, hoặc tính sẵn giá trị tổng hợp trước khi ra quyết định, và đặt [thời gian chờ tối đa](#thời-gian-chờ-tối-đa) cho bên gọi trực tiếp.
- **Một engine mở rộng tốt theo số luồng.** Trung vị với 4 luồng trên một engine chênh không đáng kể so với một luồng: việc đánh giá không dùng chung trạng thái thay đổi được. Phần đuôi tăng khi chạy 4 luồng trên 4 vCPU, vì các luồng benchmark khi đó tranh lõi với JIT và bộ thu gom rác.
- **Cấp phát bộ nhớ là nguyên nhân chính của phần đuôi.** Một lần đánh giá cấp phát khoảng 44 KB, cộng 2 đến 3 KB cho mỗi luật được xét, và khoảng 50 KB cho mỗi luật duyệt danh sách 20 phần tử. Hãy định cỡ young generation theo lưu lượng yêu cầu, và ưu tiên bộ thu gom có thời gian dừng thấp (Generational ZGC) khi p99,9 quan trọng.

Theo [OpenFRAML](https://github.com/xvalleycorp/OpenFRAML), nơi đánh giá trực tiếp một bảng `COLLECT` 12 luật cho mỗi khoản thanh toán: trên Apple M2 với JDK 22 và G1, 4 luồng, toàn bộ một quyết định của cổng thanh toán mất 16,8 µs ở p50, 50,6 µs ở p99 và 129 µs ở p99,9.

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
