---
source: architecture.md
sourceHash: 4a49ed6f3f01b618c417cd08dd5d54127096f0cc332eef538f0084cfbe9a5c69
---

# Kiến trúc RuleStone

> **Mỗi phiên bản khắc vào đá. Mỗi thay đổi chỉ mất vài giây.**
>
> Trạng thái: bản thảo · Cập nhật lần cuối: 2026-10-22

Tài liệu này mô tả hướng đi của RuleStone, chứ không chỉ những gì đã có hôm nay. Mỗi mô-đun và năng lực được đánh dấu **đã có** hoặc **dự kiến**; phần [lộ trình](#9-lộ-trình) cho biết thứ tự thực hiện. Để biết cách dùng những gì đã có, xem [các hướng dẫn](index.md).

**Về cái tên.** Cái tên bắt nguồn từ tấm bia đá khắc Bộ luật Hammurabi (khoảng năm 1750 trước Công nguyên), một trong những bộ luật thành văn đầu tiên, và cũng là bộ luật đã quy định về cho vay, lãi suất và tiền gửi. Mỗi phiên bản luật trong RuleStone cũng được "khắc vào đá" như vậy: bất biến, có định danh và kiểm toán được. Tuy vậy, phát hành một phiên bản mới chỉ mất vài giây và không cần triển khai lại ứng dụng.

## 1. Mục tiêu và phạm vi

RuleStone là một thư viện **quản lý luật nghiệp vụ** (business rules management) cho ngân hàng và tài chính, viết bằng Java. RuleStone hướng tới:

- **Tất định và kiểm toán được.** Cùng đầu vào và cùng phiên bản luật luôn cho cùng một đầu ra, và mọi quyết định đều để lại vết thực thi đầy đủ.
- **Dễ tích hợp.** Có thể nhúng như một thư viện thông thường, chạy như một dịch vụ, hoặc gắn vào Spring, Kafka hay các AI agent, mà không bắt bạn phải đổi công nghệ.
- **Sẵn sàng cho AI lai (hybrid).** AI có thể giúp viết luật, giải thích quyết định và đóng vai trò một nút có rào chắn trong một quyết định, nhưng quyết định cuối cùng luôn đi qua bộ máy tất định.

Tạm thời nằm ngoài phạm vi: suy diễn có trạng thái (Rete, suy diễn tiến), xử lý sự kiện phức hợp (CEP), và BPM hay workflow tổng quát.

## 2. Nguyên tắc thiết kế

1. **Lõi không phụ thuộc framework.** `rulestone-core` chỉ dùng JDK và một số ít thư viện (CEL, Jackson). Spring, Kafka, AI và những thứ tương tự nằm trong các mô-đun tuỳ chọn.
2. **Mở rộng qua SPI, không qua kế thừa.** Mọi điểm tích hợp đều là interface, được nạp qua `ServiceLoader` hoặc builder, nên từng phần có thể được thay thế độc lập.
3. **Không chạy mã tuỳ ý.** Luật không có script JavaScript hay Groovy. Biểu thức viết bằng CEL: không Turing-complete và có giới hạn. Hàm mở rộng phải được đăng ký tường minh và phải là hàm thuần (pure).
4. **JSON là định dạng chuẩn, kèm một cú pháp văn bản tương đương.** Luật được lưu trữ và trao đổi dưới dạng JSON (`.stone.json`). Một cú pháp văn bản (`.stone`) đang được dự kiến, phục vụ việc con người rà soát và việc LLM sinh luật, chuyển đổi hai chiều với JSON mà không mất thông tin.
5. **Tiền là `BigDecimal`.** Không có `double` ở bất kỳ chỗ nào liên quan đến tiền.
6. **Đặc tả đi trước.** Hành vi được đặc tả bằng các bộ kiểm thử tuân thủ (conformance test) không phụ thuộc ngôn ngữ, để các bản triển khai khác có thể làm theo.

## 3. Các mô-đun

| Mô-đun | Trách nhiệm | Phụ thuộc chính | Trạng thái |
|---|---|---|---|
| `rulestone-bom` | Đồng bộ phiên bản cho người dùng | — | đã có |
| `rulestone-api` | API công khai: `RuleStoneEngine`, `DecisionRef`, `Facts`, `DecisionResult`; sau này thêm các SPI | JDK | đã có |
| `rulestone-core` | Mô hình quyết định, bộ đánh giá, vết thực thi. Đã có bảng quyết định; đồ thị quyết định, switch và quyết định con đang dự kiến | `rulestone-api`, `rulestone-cel`, Jackson | đã có (một phần) |
| `rulestone-cel` | Tích hợp cel-java và các phần mở rộng. Đã có `decimal`; `money`, kiểu ngày và lịch làm việc đang dự kiến | cel-java | đã có (một phần) |
| `rulestone-dsl` | Cú pháp văn bản `.stone` ⇄ JSON, bộ định dạng | ANTLR runtime | dự kiến |
| `rulestone-analysis` | Phân tích tĩnh: các dòng chồng lấn, khoảng trống và các dòng không bao giờ tới được, dùng SMT solver khi có | Z3 (tuỳ chọn) | dự kiến |
| `rulestone-governance` | Quy trình maker-checker, nhật ký kiểm toán liên kết bằng chuỗi băm | `rulestone-api` | dự kiến |
| `rulestone-simulation` | Kiểm thử ngược (backtesting) và champion/challenger trên dữ liệu lịch sử | `rulestone-core` | dự kiến |
| `rulestone-repository-jdbc` / `-git` / `-s3` | Kho lưu trữ luật | client tương ứng | dự kiến |
| `rulestone-audit-jdbc` / `-kafka` | Nơi ghi bản ghi kiểm toán (audit sink) | client tương ứng | dự kiến |
| `rulestone-observability` | Số liệu đo (Micrometer) và truy vết (OpenTelemetry) | Micrometer, OTel API | dự kiến |
| `rulestone-ai` | SPI cho nút AI, trợ lý soạn luật, giải thích quyết định | chỉ `rulestone-api` (không phụ thuộc nhà cung cấp LLM nào) | dự kiến |
| `rulestone-spring-boot-starter` | Tự động cấu hình, thuộc tính cấu hình, endpoint actuator | Spring Boot | dự kiến |
| `rulestone-server` | Dịch vụ REST và gRPC kèm OpenAPI, chạy độc lập hoặc dạng sidecar | — | dự kiến |
| `rulestone-mcp` | Máy chủ MCP để AI agent dùng engine làm rào chắn | MCP Java SDK | dự kiến |
| `rulestone-test` | Tiện ích JUnit 5 để kiểm thử đơn vị cho luật | JUnit | dự kiến |

Quy tắc phụ thuộc: `core` **không bao giờ** phụ thuộc vào `ai`, `spring` hay `server`. Quy tắc này sẽ được kiểm tra bằng ArchUnit trong CI.

## 4. Tích hợp

### 4.1 Các chế độ triển khai

| Chế độ | Dùng khi | Độ trễ | Trạng thái |
|---|---|---|---|
| **Nhúng** (thư viện thông thường) | Một ứng dụng Java sẵn có cần độ trễ thấp (thanh toán, hạn mức) | µs–ms | đã có |
| **Spring Boot starter** | Các microservice Spring | µs–ms | dự kiến |
| **Dịch vụ REST/gRPC** | Hệ thống không dùng Java (.NET, Node, core banking cũ), hoặc một dịch vụ quyết định dùng chung | ms | dự kiến |
| **Bộ xử lý Kafka** | Chấm điểm và sàng lọc trên luồng sự kiện (phòng, chống rửa tiền (AML), gian lận) | ms | dự kiến |
| **Máy chủ MCP** | AI agent phải kiểm tra chính sách trước khi gọi một công cụ | ms | dự kiến |

### 4.2 Giao diện nhà cung cấp dịch vụ (SPI, dự kiến)

| SPI | Trách nhiệm | Bản triển khai đi kèm |
|---|---|---|
| `RuleRepository` | Nạp luật theo tên, phiên bản và ngày hiệu lực | File, JDBC, Git, S3 |
| `FactResolver` | Lấy thêm dữ liệu khi cần (ví dụ dư nợ hiện tại từ core banking), có bộ nhớ đệm và thời gian chờ tối đa | — (người dùng tự cung cấp) |
| `TypeAdapter` | Chuyển đầu vào thành giá trị CEL: `Map`, POJO/record, Jackson `JsonNode`, Protobuf | Đã có Map và record; Jackson và Protobuf dự kiến |
| `FunctionLibrary` | Hàm nghiệp vụ tuỳ biến, thuần và có kiểu | Decimal (đã có), Money, BusinessCalendar |
| `AuditSink` | Ghi bản ghi quyết định | Log, JDBC, Kafka |
| `DataMasker` | Che dữ liệu nhạy cảm (số thẻ, số định danh cá nhân, số tài khoản) trong vết thực thi và log | Theo annotation và theo đường dẫn |
| `ModelProvider` | Cung cấp mô hình ML hoặc LLM cho một nút AI | — (adapter riêng) |
| `ApprovalWorkflow` | Maker-checker, có thể kết nối với hệ thống phê duyệt sẵn có | Trong bộ nhớ, JDBC |

### 4.3 Nạp động

- Luật được biên dịch (phân tích cú pháp, kiểm tra kiểu, chuyển thành chương trình CEL) **một lần** khi nạp. *Đã có.*
- Nạp lại nóng (hot reload) hoán đổi toàn bộ tập luật đã biên dịch một cách nguyên tử. Không bao giờ tồn tại trạng thái nạp dở dang. *Đã có*, bằng cách tạo engine mới rồi hoán đổi; xem [Reloading rules](../java-api.md#reloading-rules) (English).
- Phát hành canary: chuyển một tỷ lệ phần trăm lưu lượng sang phiên bản mới, hoặc chạy phiên bản mới ở chế độ bóng (shadow) để so sánh mà không ảnh hưởng đến kết quả thật. *Dự kiến.*

## 5. Phác thảo API

```java
// Target API. Available today: clock(), decision(), decisions(), maxIterations().
RuleStoneEngine engine = RuleStone.builder()
    .repository(JdbcRuleRepository.of(dataSource))
    .auditSink(KafkaAuditSink.of(producer, "rulestone.audit"))
    .functions(BusinessCalendarFunctions.vietnam())
    .limits(Limits.builder()
        .maxIterations(10_000)
        .timeout(Duration.ofMillis(50))
        .build())
    .masker(DataMasker.byPaths("applicant.idNumber", "card.pan"))
    .build();

DecisionResult result = engine.evaluate(
    DecisionRef.of("loan_eligibility").asOf(LocalDate.of(2026, 11, 5)),
    Facts.of("applicant", applicant,
             "amount", new BigDecimal("300000000")));

result.output();       // {decision=APPROVE, limit=309000000}
result.reasonCodes();  // e.g. [DTI_HIGH], for adverse-action notices
result.ruleVersion();  // 3@sha256:… (version + content hash)
result.trace();        // which rules were evaluated and which matched; no input values
```

Đây là API mục tiêu; hiện đã có `clock()`, `decision()`, `decisions()` và `maxIterations()`. `reasonCodes()` phục vụ các thông báo từ chối gửi khách hàng (adverse action), `ruleVersion()` gồm phiên bản và mã băm nội dung, còn `trace()` cho biết những luật nào đã được đánh giá và luật nào khớp, không chứa giá trị đầu vào.

## 6. Yêu cầu của ngân hàng

### 6.1 Yêu cầu → cơ chế

| Yêu cầu | Cơ chế | Trạng thái |
|---|---|---|
| Tất định, tái lập được | Phiên bản bất biến kèm mã băm nội dung. Không có I/O trong biểu thức. `asOf` cố định ngày nghiệp vụ; luật không bao giờ đọc đồng hồ hệ thống. | đã có |
| Tiền chính xác | `BigDecimal` xuyên suốt; số dấu phẩy động bị từ chối trong luật và trong đầu vào. | đã có |
| Hiệu lực theo ngày | Mỗi phiên bản có `effectiveFrom`/`effectiveTo`; các phiên bản chồng lấn bị từ chối; một quyết định cho ngày X có thể chạy lại với bộ luật có hiệu lực vào ngày X. | đã có |
| Phân tách nhiệm vụ (maker-checker) | Vòng đời luật `DRAFT → SUBMITTED → APPROVED → ACTIVE → RETIRED`; người lập và người duyệt phải khác nhau, được kiểm soát ở tầng quản trị (governance). | dự kiến |
| Kiểm toán chống giả mạo | Bản ghi kiểm toán liên kết bằng chuỗi băm, có thể ký số, chứa đầu vào đã che, phiên bản luật, vết thực thi và kết quả. | dự kiến |
| Khả năng giải thích | Mã lý do tường minh cùng vết thực thi có cấu trúc; AI chỉ diễn đạt lại vết thực thi. | đã có (mã lý do, vết thực thi) |
| Bảo vệ dữ liệu cá nhân | Không có giá trị đầu vào trong vết thực thi hay thông báo ngoại lệ (đã có). `DataMasker` bật mặc định cho bản ghi kiểm toán (dự kiến). | một phần |
| An toàn khi thực thi | CEL không có reflection hay eval; các thao tác trên danh sách có giới hạn (đã có). Thời gian chờ tối đa cho mỗi quyết định (dự kiến). | một phần |
| Tính sẵn sàng cao | Chế độ nhúng không phụ thuộc mạng trên luồng xử lý chính; nếu kho luật gặp sự cố, phiên bản đã nạp vẫn tiếp tục chạy. | đã có (chế độ nhúng) |
| Kiểm thử thay đổi | Kiểm thử đơn vị cho luật (`rulestone-test`), phân tích tĩnh, và bắt buộc kiểm thử ngược trước khi một phiên bản chuyển sang `ACTIVE`. | dự kiến |

### 6.2 Chuỗi cung ứng phần mềm

| Thực hành | Trạng thái |
|---|---|
| Giấy phép Apache-2.0; `LICENSE`, `NOTICE` và `THIRD-PARTY.txt` trong mọi tệp jar; header giấy phép được kiểm tra trên mọi tệp mã nguồn | đã có |
| Danh sách giấy phép bên thứ ba được phép (Apache-2.0, MIT, BSD-2-Clause, BSD-3-Clause), được bản build kiểm tra bắt buộc | đã có |
| SBOM CycloneDX cho từng mô-đun | đã có |
| Build tái lập được (cố định dấu thời gian trong tệp lưu trữ) | đã có |
| Tệp jar mã nguồn và Javadoc | đã có |
| Đóng góp theo Developer Certificate of Origin | đã có |
| Quét lỗ hổng (OWASP Dependency-Check) và SAST trong CI | dự kiến |
| Artifact có chữ ký số, phát hành lên Maven Central | dự kiến |
| Chính sách hỗ trợ: phiên bản LTS, sửa lỗi bảo mật có cam kết thời gian phản hồi, SemVer | dự kiến |

Số thư viện phụ thuộc được giữ ở mức tối thiểu: mỗi thư viện là một phần mã mà ngân hàng phải kiểm toán.

### 6.3 Các khung pháp lý cần đối chiếu

Đây là danh sách cần rà soát trong khi đặc tả dần hoàn thiện. Phải kiểm tra lại văn bản hiện hành trước khi đưa ra bất kỳ cam kết nào với khách hàng.

- **Việt Nam:** các quy định của Ngân hàng Nhà nước Việt Nam về an toàn hệ thống thông tin trong hoạt động ngân hàng và về an toàn, bảo mật cho dịch vụ ngân hàng trực tuyến; pháp luật về bảo vệ dữ liệu cá nhân.
- **Quốc tế:** PCI DSS v4 (nếu xử lý dữ liệu thẻ); BCBS 239 (truy xuất nguồn gốc dữ liệu rủi ro); SR 11-7 và quản lý rủi ro mô hình (cho các nút AI); Đạo luật AI của EU (chấm điểm tín dụng là AI rủi ro cao, đòi hỏi khả năng giải thích, sự giám sát của con người và lưu trữ hồ sơ).

## 7. Tích hợp AI (dự kiến)

| Mẫu | Mô tả | Rào chắn |
|---|---|---|
| **Trợ lý soạn luật** | Một LLM soạn nháp luật `.stone` từ văn bản chính sách. | Bản nháp phải qua bộ phân tích cú pháp, bộ kiểm tra kiểu, phân tích tĩnh, kiểm thử đơn vị và maker-checker. AI chỉ được tạo `DRAFT`. |
| **Giải thích quyết định** | Chuyển vết thực thi thành ngôn ngữ dễ hiểu cho khách hàng hoặc kiểm toán viên. | Đầu vào chỉ là vết thực thi đã che dữ liệu; mã lý do vẫn là nguồn sự thật. |
| **Nút AI** | Một mô hình ML hoặc LLM làm một nút trong đồ thị quyết định. | Khai báo kiểu đầu ra, ngưỡng độ tin cậy, thời gian chờ tối đa và nhánh dự phòng. Mô hình có phiên bản và được ghi vào dấu vết kiểm toán. Các luật bao quanh giới hạn mức ảnh hưởng của nó. |
| **Rào chắn cho agent** | Một agent gọi `rulestone-mcp` trước khi thực hiện một hành động. | Câu trả lời của engine là tất định; agent không thể ghi đè. |
| **Sinh ca kiểm thử và đề xuất cải tiến** | Một LLM sinh các ca biên hoặc phân tích dữ liệu quyết định để đề xuất thay đổi. | Đề xuất chỉ trở thành `DRAFT`. |

`rulestone-ai` sẽ chỉ định nghĩa các SPI. Adapter cho từng nhà cung cấp (LLM tại chỗ hoặc trên đám mây) sẽ là các mô-đun riêng, để ngân hàng có thể vận hành hoàn toàn tại chỗ (on-premise).

Mọi lỗi trong một định nghĩa luật đã được báo cáo cùng lúc, mỗi lỗi kèm vị trí (*đã có*). Đây chính là phản hồi mà một trợ lý soạn luật cần để sửa bản nháp trong một lượt.

## 8. Các quyết định

### 8.1 Đã quyết định

| Vấn đề | Quyết định |
|---|---|
| Tên sản phẩm | RuleStone; tệp luật `.stone.json` (và `.stone` cho cú pháp văn bản dự kiến) |
| Giấy phép | Apache-2.0 cho dự án mã nguồn mở |
| Chủ sở hữu bản quyền | "The RuleStone Authors", theo danh sách trong `AUTHORS` |
| Đóng góp | Developer Certificate of Origin (sign-off), không dùng CLA |
| Ngôn ngữ tài liệu | Tiếng Anh |
| Phiên bản Java tối thiểu | Java 17 (tương thích Spring Boot 3) |
| Công cụ build | Maven |
| groupId / package | `vn.io.dxa.rulestone`, theo tên miền `dxa.io.vn` |
| Số học tiền tệ | `decimal` dựa trên `BigDecimal`; phép chia lấy 34 chữ số có nghĩa, HALF_EVEN; làm tròn phải tường minh; so sánh bằng giữa `decimal` và `int` là lỗi kiểu |

### 8.2 Còn để ngỏ

| Vấn đề | Đề xuất hiện tại |
|---|---|
| Phát hành lên Maven Central | Đăng ký namespace `vn.io.dxa` trên Central Portal và xác minh bằng bản ghi DNS TXT trên `dxa.io.vn`. Cần khai báo URL dự án, SCM và thông tin nhà phát triển trong POM. |
| Mô hình thương mại | Open core: engine và các adapter cơ bản giữ giấy phép Apache-2.0; tính năng quản trị nâng cao, trình soạn luật và hỗ trợ doanh nghiệp có thể được cung cấp thương mại dưới dạng sản phẩm riêng. |
| Kiểu `money` | Cần chọn bảng số chữ số thập phân theo từng loại tiền (ISO 4217 hoặc theo quy định riêng của ngân hàng) và chính sách làm tròn mặc định. |
| Tương thích GoRules JDM | Một công cụ nhập từ JDM sang RuleStone để thuận tiện chuyển đổi. |

## 9. Lộ trình

1. **M0 — Đặc tả:** mô hình quyết định, các phần mở rộng CEL (`decimal`, `money`, `date`), định dạng vết thực thi, bộ kiểm thử tuân thủ.
2. **M1 — Lõi** *(đang thực hiện)*
   - Đã xong: bảng quyết định với `FIRST`, `UNIQUE` và `COLLECT`; định dạng `.stone.json` chặt chẽ; mã băm nội dung; hiệu lực theo ngày có phát hiện chồng lấn; `decimal` trong CEL; đầu vào kiểu struct và danh sách có kiểu; vết thực thi; báo cáo cùng lúc mọi lỗi trong định nghĩa; cấp phép, SBOM và tài liệu.
   - Còn lại: kiểu `money`, kiểu `date`, mô-đun `rulestone-test`.
3. **M2 — Quản trị:** maker-checker, kiểm toán liên kết bằng chuỗi băm, kho luật JDBC.
4. **M3 — Tích hợp:** Spring Boot starter, máy chủ REST/gRPC, khả năng quan sát (observability), Kafka.
5. **M4 — Phân tích và AI:** phân tích tĩnh, cú pháp văn bản `.stone`, trợ lý soạn luật, máy chủ MCP.
6. **M5 — Mô phỏng:** kiểm thử ngược, champion/challenger, trình soạn luật trên web.
