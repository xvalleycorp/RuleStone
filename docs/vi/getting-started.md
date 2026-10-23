---
source: getting-started.md
sourceHash: e736cfc9ee64c2f062f44ca80ac1decb5c32982787d83b159254c4db3cb9f109
---

# Bắt đầu

Hướng dẫn này đưa bạn từ một dự án trống đến lúc đánh giá được quyết định đầu tiên, trong khoảng mười phút. Bạn cần biết Java và Maven, nhưng không cần biết trước về bộ máy luật (rules engine).

Bạn sẽ:

1. thêm RuleStone vào dự án;
2. viết một bảng quyết định xét điều kiện vay tín chấp;
3. đánh giá bảng đó từ Java và đọc kết quả;
4. xem điều gì xảy ra khi một luật bị viết sai.

## 1. Thêm RuleStone vào dự án

RuleStone cần **Java 17 trở lên**.

> **Bản tiền phát hành.** RuleStone chưa được phát hành lên Maven Central. Trong thời gian này, hãy build từ mã nguồn và cài vào kho Maven cục bộ của bạn:
>
> ```sh
> git clone https://github.com/xvalleycorp/RuleStone.git
> cd RuleStone
> mvn install
> ```

Sau đó thêm thư viện phụ thuộc. Với Maven:

```xml
<dependency>
    <groupId>vn.io.dxa.rulestone</groupId>
    <artifactId>rulestone-core</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

Với Gradle (Kotlin DSL; thêm `mavenLocal()` vào danh sách repository khi dùng bản build cục bộ):

```kotlin
implementation("vn.io.dxa.rulestone:rulestone-core:0.1.0-SNAPSHOT")
```

Nếu dùng nhiều mô-đun RuleStone, hãy import `rulestone-bom` trong `<dependencyManagement>` để phiên bản của các mô-đun luôn khớp nhau.

## 2. Viết một bảng quyết định

*Bảng quyết định* là một danh sách luật biến các đầu vào có kiểu thành các đầu ra có kiểu. Lưu nội dung sau vào tệp `rules/loan_eligibility.stone.json`. Đây chính là tệp [`examples/loan_eligibility.v3.stone.json`](../../examples/loan_eligibility.v3.stone.json).

```json
{
  "id": "loan_eligibility",
  "version": "3",
  "description": "Unsecured consumer loan eligibility",
  "effectiveFrom": "2026-11-01",
  "hitPolicy": "FIRST",
  "inputs": {
    "applicant": {
      "age": "int",
      "monthlyIncome": "decimal",
      "monthlyDebt": "decimal",
      "segment": "string",
      "blacklisted": "bool"
    },
    "amount": "decimal"
  },
  "outputs": {
    "decision": "string",
    "limit": "decimal"
  },
  "rules": [
    {
      "id": "R1",
      "description": "Applicants must be adults",
      "when": "applicant.age < 18",
      "then": { "decision": "'REJECT'" },
      "reasonCode": "UNDERAGE"
    },
    {
      "id": "R2",
      "when": "applicant.blacklisted",
      "then": { "decision": "'REJECT'" },
      "reasonCode": "BLACKLISTED"
    },
    {
      "id": "R3",
      "description": "Debt-to-income above 50%",
      "when": "applicant.monthlyDebt / applicant.monthlyIncome > decimal('0.5')",
      "then": { "decision": "'REJECT'" },
      "reasonCode": "DTI_HIGH"
    },
    {
      "id": "R4",
      "description": "Up to 12 months of income, rounded down to the nearest million",
      "when": "amount <= applicant.monthlyIncome * 12",
      "then": {
        "decision": "'APPROVE'",
        "limit": "(applicant.monthlyIncome * 12 / 1000000).round(0, 'FLOOR') * 1000000"
      }
    },
    {
      "id": "R5",
      "when": "true",
      "then": { "decision": "'REVIEW'" },
      "reasonCode": "AMOUNT_ABOVE_LIMIT"
    }
  ]
}
```

Ý nghĩa của bảng này:

- **`inputs`** khai báo những gì bên gọi phải cung cấp, kèm kiểu dữ liệu. `applicant` (người vay) là một *struct* gồm năm trường có kiểu; `amount` (số tiền vay) có kiểu `decimal`, một kiểu số chính xác, phù hợp để biểu diễn tiền.
- **`outputs`** khai báo những gì các luật có thể trả về.
- **`hitPolicy: FIRST`** nghĩa là các luật được thử lần lượt từ trên xuống, và luật đầu tiên có `when` đúng sẽ quyết định kết quả. `R5` khớp với mọi trường hợp, nên nó đón tất cả những gì các luật phía trên chưa xử lý.
- **`when`** và các giá trị trong **`then`** là biểu thức [CEL](expressions.md). Giá trị văn bản được đặt trong dấu nháy đơn bên trong chuỗi JSON: `"'REJECT'"`.
- **`reasonCode`** là một mã lý do ổn định, có thể hiển thị cho khách hàng hoặc lưu lại phục vụ kiểm toán.
- **`effectiveFrom`** cho biết phiên bản này có hiệu lực từ ngày 1 tháng 11 năm 2026.

## 3. Đánh giá bảng quyết định

```java
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import vn.io.dxa.rulestone.api.DecisionRef;
import vn.io.dxa.rulestone.api.DecisionResult;
import vn.io.dxa.rulestone.api.Facts;
import vn.io.dxa.rulestone.api.RuleStoneEngine;
import vn.io.dxa.rulestone.core.DecisionTableJson;
import vn.io.dxa.rulestone.core.RuleStone;

public class LoanCheck {

    record Applicant(int age, BigDecimal monthlyIncome, BigDecimal monthlyDebt,
                     String segment, boolean blacklisted) {}

    public static void main(String[] args) {
        RuleStoneEngine engine = RuleStone.builder()
                .clock(Clock.system(ZoneId.of("Asia/Ho_Chi_Minh")))
                .decision(DecisionTableJson.read(Path.of("rules/loan_eligibility.stone.json")))
                .build();

        Applicant applicant = new Applicant(30, new BigDecimal("25750000.50"),
                new BigDecimal("5000000"), "RETAIL", false);

        DecisionResult result = engine.evaluate(
                DecisionRef.of("loan_eligibility").asOf(LocalDate.of(2026, 12, 1)),
                Facts.of("applicant", applicant, "amount", new BigDecimal("300000000")));

        System.out.println(result.output());       // {decision=APPROVE, limit=309000000}
        System.out.println(result.reasonCodes());  // []
        System.out.println(result.matchedRules()); // [R4]
        System.out.println(result.ruleVersion());  // 3@sha256:…
    }
}
```

Từng bước một:

1. **`RuleStone.builder()…build()`** nạp bảng quyết định, đồng thời **biên dịch và kiểm tra kiểu mọi luật**. Nếu có bất kỳ lỗi nào, `build()` ném ngoại lệ và không có gì được đánh giá. Hãy tạo engine một lần duy nhất khi khởi động ứng dụng rồi dùng chung: engine là bất biến và an toàn đa luồng.
2. **`.clock(...)`** đặt múi giờ dùng để xác định "hôm nay" khi yêu cầu không truyền ngày. Hãy đặt theo múi giờ của ngân hàng bạn.
3. **`DecisionRef.of("loan_eligibility").asOf(...)`** chỉ định quyết định cần chạy và ngày nghiệp vụ. Engine chọn phiên bản có hiệu lực vào ngày đó. Truyền ngày một cách tường minh giúp quyết định tái lập được: một năm sau chạy lại, engine vẫn chọn đúng phiên bản cũ.
4. **`Facts.of(...)`** cung cấp dữ liệu đầu vào. `applicant` ở đây là một Java record; dùng `Map<String, Object>` cũng được. Tiền luôn là `BigDecimal`; truyền vào một giá trị `double` sẽ bị từ chối.
5. **`DecisionResult`** chứa các đầu ra, mã lý do, các luật đã xét và chính xác phiên bản luật đã dùng. Hãy lưu `ruleVersion()` cùng với quyết định: giá trị này xác định nội dung luật đến từng byte.

Người vay có thu nhập 25.750.000,50 đồng mỗi tháng. Thu nhập mười hai tháng là 309.006.006,00, được `R4` làm tròn xuống thành 309.000.000. Số tiền đề nghị vay 300.000.000 nằm trong hạn mức đó, nên `R4` khớp.

Thử với một người vay có quá nhiều nợ:

```java
Facts.of("applicant", new Applicant(30, new BigDecimal("20000000"),
                new BigDecimal("12000000"), "RETAIL", false),
         "amount", new BigDecimal("100000000"))
```

```
output       {decision=REJECT}
reasonCodes  [DTI_HIGH]
trace        R1 ✗, R2 ✗, R3 ✓
```

Tỷ lệ nợ trên thu nhập (DTI) là 0,6, nên `R3` từ chối với mã lý do `DTI_HIGH`, còn `R4` và `R5` không bao giờ được đánh giá.

## 4. Khi một luật bị viết sai

Sửa `R1` để gõ sai tên một trường, đồng thời so sánh một số decimal với một số dấu phẩy động:

```json
{ "id": "R1", "when": "applicant.agee < 18 || applicant.monthlyIncome < 1.5", ... }
```

Lúc này `build()` ném ra `InvalidDecisionException`, liệt kê mọi lỗi kèm vị trí của chúng (danh sách các overload ứng viên đã được rút gọn ở đây):

```
invalid decision definition (1 problem):
  - loan_eligibility@3 rules[R1].when: ERROR: <input>:1:10: undefined field 'agee'
     | applicant.agee < 18 || applicant.monthlyIncome < 1.5
     | .........^
    ERROR: <input>:1:48: found no matching overload for '_<_' applied to '(decimal, double)' (candidates: …)
     | applicant.agee < 18 || applicant.monthlyIncome < 1.5
     | ...............................................^
```

`InvalidDecisionException.problems()` trả về cùng thông tin đó dưới dạng danh sách, mỗi phần tử ứng với một biểu thức của luật hoặc một vị trí trong tệp JSON.

RuleStone kiểm tra luật ngay lúc nạp, chứ không phải lúc khách hàng đang chờ. Đặc biệt, RuleStone không cho phép một giá trị `decimal` gặp một số dấu phẩy động, vì số dấu phẩy động không biểu diễn chính xác được phần lớn các số tiền.

## Bước tiếp theo

- [Bảng quyết định](decision-tables.md): mọi trường của định dạng tệp, hit policy, phiên bản và ngày hiệu lực.
- [Ngôn ngữ biểu thức](expressions.md): những gì bạn có thể viết trong `when` và `then`.
- [Java API](java-api.md): nhúng engine vào ứng dụng, các kiểu đầu vào, kết quả, lỗi và nạp lại luật.
- [Ví dụ](examples.md): thêm nhiều bảng quyết định hoàn chỉnh.
