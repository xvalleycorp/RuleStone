---
source: explanations.md
sourceHash: 5aca562fb571a39fab0049e1409c7f216b0e463537a38032b5fa564ee4b783fe
---

# Giải thích quyết định

Mọi quyết định của RuleStone đều có một [bản ghi quyết định](java-api.md#bản-ghi-quyết-định-và-kiểm-toán), và mỗi lý do của quyết định có một mã lý do ổn định, ví dụ `DTI_HIGH`. Phần giải thích (explanation) chuyển bản ghi thành lời: kết quả, các lý do kèm nội dung đã được duyệt cho từng mã, và căn cứ ra quyết định, bằng ngôn ngữ của khách hàng. Trang này mô tả danh mục mã lý do chứa các nội dung đó, cách engine kiểm tra danh mục, và phần giải thích mà engine dựng ra.

Giải thích quyết định là bước 4 của [Ra quyết định AI lai](hybrid-ai.md#9-kế-hoạch-triển-khai). Tính năng này có trong bộ máy luật Java và trong bộ máy luật trên trình duyệt của [SDK trình soạn thảo](editor-sdk.md); hai bộ máy luật cho cùng một phần giải thích, giống nhau đến từng byte.

- [Danh mục mã lý do](#danh-mục-mã-lý-do)
- [Kiểm tra danh mục khi tạo engine](#kiểm-tra-danh-mục-khi-tạo-engine)
- [Giải thích một quyết định](#giải-thích-một-quyết-định)
- [Nội dung của phần giải thích](#nội-dung-của-phần-giải-thích)
- [Khách hàng và nhân viên](#khách-hàng-và-nhân-viên)
- [Trên trình duyệt](#trên-trình-duyệt)
- [Kiểm thử phần giải thích](#kiểm-thử-phần-giải-thích)
- [Giới hạn](#giới-hạn)

## Danh mục mã lý do

Danh mục mã lý do (reason-code catalog) là một tệp JSON, theo quy ước đặt tên `reason-codes.json`, nằm cùng repository với luật và được review như luật. Đoạn trích dưới đây lấy từ [`examples/reason-codes.json`](../../examples/reason-codes.json), tệp có nội dung cho mọi mã lý do của các [ví dụ](examples.md):

```json
{
  "version": "1",
  "description": "Messages for the reason codes of the examples, reviewed by Credit Risk and Compliance",
  "locales": ["en", "vi"],
  "codes": {
    "DTI_HIGH": {
      "en": "Your monthly debt payments are too high compared with your income.",
      "vi": "Tổng số tiền trả nợ hằng tháng của quý khách quá cao so với thu nhập."
    },
    "HIGH_UTILISATION": {
      "en": "Your credit utilisation is high compared with your limits.",
      "vi": "Mức sử dụng hạn mức tín dụng của quý khách đang cao so với hạn mức được cấp."
    },
    "MODEL_UNAVAILABLE": {
      "en": "Staff: the risk model was not available, so the application was referred for manual review.",
      "vi": "Nội bộ: không có kết quả từ mô hình rủi ro, nên hồ sơ được chuyển xem xét thủ công."
    }
  }
}
```

| Trường | Ý nghĩa |
|---|---|
| `version` | Bắt buộc. Nhãn phiên bản, như của bảng: chữ cái, chữ số, `.`, `_` hoặc `-`. |
| `description` | Không bắt buộc. Văn bản tự do, ví dụ ai đã duyệt nội dung và khi nào. |
| `locales` | Bắt buộc. Các ngôn ngữ (locale) của nội dung, viết bằng thẻ ngôn ngữ như `en`, `vi` hoặc `en-GB`, mỗi thẻ một lần. |
| `codes` | Bắt buộc. Mã lý do, đến locale, đến nội dung. |

Danh mục được đọc chặt chẽ như bảng, và mọi vấn đề được báo cùng lúc, kèm vị trí:

- mỗi mã viết dạng UPPER_SNAKE_CASE, như trong bảng và luồng;
- mỗi mã có nội dung cho **đúng** các locale đã khai báo: thiếu một locale hay có locale chưa khai báo đều là vấn đề;
- nội dung là một dòng văn bản thuần, không rỗng, không có dấu cách ở đầu hoặc cuối và không có ký tự điều khiển;
- nội dung không chứa `{` hoặc `}`. Hai ký tự này được dành cho placeholder, để phiên bản sau có thể thêm placeholder mà không đổi nghĩa của danh mục hiện có. Nội dung không bao giờ chứa giá trị.
- trường lạ và khoá trùng là vấn đề.

Như bảng và bộ tham số, danh mục có phiên bản chính xác: nhãn phiên bản, sau đó là SHA-256 của dạng chuẩn, ví dụ `1@sha256:fccd…`. Mỗi phần giải thích ghi phiên bản chính xác của danh mục mà nội dung được lấy ra, nên một thông báo gửi khách hàng luôn truy được về văn bản đã duyệt. Muốn sửa một nội dung, hãy sửa tệp và `version` của nó, rồi đưa thay đổi đi review. Danh mục không có ngày hiệu lực: engine dùng danh mục mà nó được tạo cùng.

```java
ReasonCodeCatalog catalog = ReasonCodeCatalogJson.read(Path.of("rules/reason-codes.json"));
ReasonCodeCatalogJson.exactVersion(catalog);   // "1@sha256:fccd…"
catalog.message("DTI_HIGH", "vi");             // Optional[Tổng số tiền trả nợ hằng tháng …]
```

## Kiểm tra danh mục khi tạo engine

Đăng ký danh mục với engine. Khi được tạo, engine kiểm tra rằng mọi mã lý do mà một bảng hay một luồng đã đăng ký có thể đưa ra đều có nội dung, trong mọi locale của danh mục: `reasonCode` của mọi luật, và trong luồng là bảng `reasonCodes` cùng mã lý do của `onFailure` ở mọi [nút mô hình](flows.md#nút-mô-hình).

```java
RuleStoneEngine engine = RuleStone.builder()
        .decisions(tables)
        .flows(flows)
        .reasonCodeCatalog(catalog)
        .requireReasonCodeCatalog()   // fail unless a catalog is registered
        .build();
```

Danh mục là không bắt buộc: không có danh mục, engine hoạt động như trước nhưng không giải thích được bản ghi. `requireReasonCodeCatalog()` làm danh mục trở thành bắt buộc, để mọi quyết định của engine đều giải thích được bằng văn bản đã duyệt, như thông báo từ chối (adverse action) đòi hỏi. Một mã không có nội dung được báo cùng các vấn đề khác của bảng và luồng, với vị trí theo cùng cách:

```text
invalid decision definition (1 problem):
  - credit_limit_increase@1 rules[DTI_HIGH].reasonCode: reason code 'DTI_HIGH' is not in the reason-code catalog
```

Danh mục có thể chứa nhiều mã hơn số mã mà các bảng của engine dùng, nên một danh mục có thể phục vụ nhiều engine.

## Giải thích một quyết định

`engine.explain(record, locale)` giải thích bất kỳ bản ghi quyết định nào bằng một locale của danh mục. Locale là một chuỗi bình thường: không có gì phụ thuộc vào locale hay múi giờ mặc định của JVM.

```java
DecisionResult result = engine.evaluate(
        DecisionRef.of("credit_limit_increase").asOf(LocalDate.of(2026, 12, 1)).correlationId("CLI-2026-000318"),
        facts);

Explanation explanation = engine.explain(result.record(), "vi");
explanation.reasonCodes();                       // [DTI_HIGH]
explanation.reasons().get(0).message();          // Optional[Tổng số tiền trả nợ hằng tháng …]
String notice = Explanations.text(explanation);  // the text below
String json = Explanations.write(explanation);   // one line of JSON
```

`Explanations.explain(record, catalog, locale)` làm điều tương tự mà không cần engine, ví dụ trong một dịch vụ soạn thông báo từ các bản ghi đã lưu. Engine không có danh mục ném `UnsupportedOperationException`, và locale mà danh mục không có gây ra `IllegalArgumentException`.

`Explanations.text` viết mỗi mục trên một dòng, với tiêu đề bằng ngôn ngữ của phần giải thích:

```text
Quyết định: credit_limit_increase 1@sha256:192cf62ada772414ce5857bc657ca2a61f2587650f45a7ac0007808f32054444
Ngày nghiệp vụ: 2026-12-01
Kết quả: decision = "REJECT"
Lý do:
  1. Tổng số tiền trả nợ hằng tháng của quý khách quá cao so với thu nhập. (DTI_HIGH, credit_limit_increase)
Căn cứ quyết định:
  - credit_limit_increase: table 1@sha256:192cf62ada772414ce5857bc657ca2a61f2587650f45a7ac0007808f32054444; khớp DTI_HIGH
Danh mục mã lý do: 1@sha256:fccd049e783da1c1ee3bd1ff85d2b38ff479b13db06ed5494c5b3ceb48d1ec71
```

```text
Decision: credit_limit_increase 1@sha256:192cf62ada772414ce5857bc657ca2a61f2587650f45a7ac0007808f32054444
Business date: 2026-12-01
Outcome: decision = "REJECT"
Reasons:
  1. Your monthly debt payments are too high compared with your income. (DTI_HIGH, credit_limit_increase)
Decided by:
  - credit_limit_increase: table 1@sha256:192cf62ada772414ce5857bc657ca2a61f2587650f45a7ac0007808f32054444; matched DTI_HIGH
Reason-code catalog: 1@sha256:fccd049e783da1c1ee3bd1ff85d2b38ff479b13db06ed5494c5b3ceb48d1ec71
```

Tiêu đề có sẵn cho tiếng Anh và tiếng Việt. Danh mục có thể khai báo locale khác; nội dung của các locale đó được dùng nguyên văn, với tiêu đề tiếng Anh. Với một luồng, mỗi nút đi qua là một dòng: nút `switch` ghi nhánh đã chọn, nút `split` ghi nhóm (bucket), còn nút mô hình ghi phiên bản đã ghim, trạng thái và các attribution (mức đóng góp của đặc trưng) theo thứ hạng và chiều, kèm mã lý do mà mỗi attribution đưa ra:

```text
  - pd_behaviour: model retail_pd_behaviour 2.4.0; OK
      1. utilisation: increases risk (HIGH_UTILISATION)
      2. dpd30Count12m: decreases risk
```

`Explanations.write` cho cùng nội dung dưới dạng JSON, với các trường theo thứ tự cố định và bỏ qua trường không có, như bản ghi quyết định:

```json
{
  "recordId": "6f1c0f3e-5b1d-4f0e-9a52-1d3a3f9c2b7e",
  "correlationId": "CLI-2026-000318",
  "decision": "credit_limit_increase",
  "version": "1@sha256:192cf62ada772414ce5857bc657ca2a61f2587650f45a7ac0007808f32054444",
  "businessDate": "2026-12-01",
  "status": "OK",
  "locale": "en",
  "catalog": "1@sha256:fccd049e783da1c1ee3bd1ff85d2b38ff479b13db06ed5494c5b3ceb48d1ec71",
  "outcome": { "decision": "REJECT" },
  "reasons": [
    { "code": "DTI_HIGH", "source": "credit_limit_increase",
      "message": "Your monthly debt payments are too high compared with your income." }
  ],
  "steps": [
    { "node": "credit_limit_increase", "type": "table",
      "version": "1@sha256:192cf62ada772414ce5857bc657ca2a61f2587650f45a7ac0007808f32054444",
      "matchedRules": ["DTI_HIGH"] }
  ]
}
```

Việc dựng phần giải thích là một hàm thuần (pure) của bản ghi, danh mục và locale: không có đồng hồ, không có ngẫu nhiên, nên cùng bản ghi, danh mục và locale luôn cho cùng văn bản và cùng JSON, giống nhau đến từng byte, trong Java và trên trình duyệt. Bản ghi của một lần đánh giá thất bại cũng được giải thích: kết quả của nó là `no decision (ERROR)` (tiếng Việt: `chưa có quyết định (ERROR)`).

## Nội dung của phần giải thích

Phần giải thích không chứa gì mà bản ghi không chứa, ngoài nội dung của danh mục. Vì bản ghi không bao giờ chứa giá trị đầu vào, phần giải thích cũng vậy, và có thể được lưu và ghi log như bản ghi.

| Phần | Nội dung |
|---|---|
| Phần đầu | Id bản ghi, mã tương quan (correlation id), quyết định, phiên bản chính xác, ngày nghiệp vụ, trạng thái, locale và phiên bản chính xác của danh mục |
| `outcome` | Các đầu ra của quyết định, như trong bản ghi |
| `reasons` | Từng mã lý do của bản ghi, theo thứ tự, kèm nút đã đưa ra mã và nội dung của mã |
| `steps` | Mọi nút đã đi qua: phiên bản bảng và các luật khớp, nhánh và nhóm đã chọn, và với nút mô hình là id mô hình, phiên bản đã ghim, trạng thái và các attribution theo thứ hạng và chiều |
| `params` | Các phiên bản bộ tham số đã dùng, nếu có |

Phần giải thích cũng bỏ qua những gì người chịu ảnh hưởng của quyết định không cần: hash của đầu vào, thời điểm đánh giá, đầu ra và độ tin cậy của mô hình, phương pháp attribution, độ trễ và chi tiết lỗi. Mức đóng góp và giá trị đặc trưng không bao giờ có trong bản ghi, nên cũng không thể có trong phần giải thích.

Đầu ra được hiển thị như trong bản ghi. Nếu một bảng chép một đầu vào sang một đầu ra, phần giải thích sẽ hiện đầu ra đó; đừng đưa đầu vào vào những đầu ra mà khách hàng nhìn thấy.

## Khách hàng và nhân viên

Một danh mục phục vụ cả hai đối tượng, với một nội dung cho mỗi mã và mỗi locale. Hãy viết mỗi nội dung cho đúng người sẽ đọc nó:

- **Quyết định tín dụng** được giải thích cho khách hàng, ví dụ trong thông báo từ chối. Viết các nội dung này bằng ngôn ngữ dễ hiểu, nói với khách hàng, và nêu lý do chứ không nêu ngưỡng. `DTI_HIGH` nói rằng số tiền trả nợ quá cao so với thu nhập; nó không nêu hạn mức.
- **Mã về gian lận, phòng, chống rửa tiền (AML) và lỗi mô hình** dành cho nhân viên. Cho khách hàng biết một giao dịch đã gây cảnh báo nghi ngờ hoặc trùng với danh sách cấm vận có thể là hành vi tiết lộ thông tin (tipping off), điều mà pháp luật về phòng, chống rửa tiền cấm. Danh mục ví dụ bắt đầu các nội dung này bằng "Staff:" ("Nội bộ:") để người review thấy ngay rằng chúng không được đưa vào thư gửi khách hàng.

Engine không chọn đối tượng đọc: ứng dụng gửi thông báo quyết định những quyết định nào được giải thích cho khách hàng, và người review kiểm tra nội dung trước khi phát hành, như với mọi thay đổi luật.

## Trên trình duyệt

Bộ máy luật trên trình duyệt đọc cùng danh mục và cho cùng phần giải thích:

```js
import { createEngine, explanationText, parseReasonCodeCatalog } from '@rulestone/engine'

const engine = await createEngine({
  tables,
  reasonCodeCatalog: parseReasonCodeCatalog(catalogText),
  requireReasonCodeCatalog: true,
})
const result = await engine.evaluateJson({ id: 'credit_limit_increase', asOf: '2026-12-01' }, facts)
console.log(explanationText(engine.explain(result.record, 'vi')))
```

`writeExplanation`, `explainDecision` (không cần engine) và `reasonCodeCatalogVersionOf` tương ứng với các phương thức Java. `<rulestone-try-it>` và `<rulestone-table-editor>` nhận một danh mục qua thuộc tính `reasonCodes`; khi đó bảng Chạy thử liệt kê nội dung các lý do của mỗi lần chạy bằng ngôn ngữ của bảng. [Sân thử](https://rulestone.dxa.io.vn/vi/playground) dùng danh mục của các ví dụ. Xem [SDK trình soạn thảo](editor-sdk.md).

## Kiểm thử phần giải thích

Một ca kiểm thử `.stone-test.json` có thể kỳ vọng phần giải thích của bản ghi bằng một locale: các mã lý do, mỗi mã phải có nội dung; các nội dung; hoặc cả hai. Xem [Kiểm thử phần giải thích](testing.md#kiểm-thử-phần-giải-thích).

```json
"expect": {
  "output": { "decision": "REJECT" },
  "explanation": {
    "locale": "vi",
    "reasons": ["DTI_HIGH"],
    "messages": ["Tổng số tiền trả nợ hằng tháng của quý khách quá cao so với thu nhập."]
  }
}
```

## Giới hạn

- **Không có placeholder.** Nội dung là văn bản cố định. Placeholder cho một giá trị đầu ra, ví dụ hạn mức được đề nghị, có thể được thêm sau; `{` và `}` được dành cho việc đó.
- **Mỗi engine một danh mục,** không có ngày hiệu lực. Nội dung cho sản phẩm mới được thêm vào cùng tệp.
- **Diễn đạt lại.** Sau này một LLM có thể diễn đạt lại phần giải thích cho phù hợp giọng văn, chỉ dùng phần giải thích làm đầu vào. Các mã và nội dung đã duyệt vẫn là nguồn gốc chính thức.
