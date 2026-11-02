---
source: replay.md
sourceHash: 03df3cf69f11f54e0398f9ecd8c941642a5491f743a97da76311addf9119ed8e
---

# Chạy lại và chấm điểm lại

Một [bản ghi quyết định](java-api.md#bản-ghi-quyết-định-và-kiểm-toán) nêu mọi thứ mà quyết định đã dùng: phiên bản chính xác của quyết định, ngày nghiệp vụ, các phiên bản bộ tham số, phiên bản của từng bảng trong luồng, và kết quả mà từng mô hình trả về. Bản ghi không chứa giá trị đầu vào nào. Với bản ghi và dữ liệu đầu vào gốc, RuleStone có thể ra lại quyết định đó:

- **Chạy lại** (replay) đánh giá lại một quyết định đã ghi đúng như lúc nó được đưa ra, và kiểm tra rằng kết quả không đổi. Kiểm toán viên dùng nó để tái lập một quyết định nhiều năm sau, khi luật, tham số và mô hình đều đã thay đổi.
- **Chấm điểm lại** (re-score) đánh giá cùng dữ liệu đầu vào với một phiên bản khác, phiên bản đang có hiệu lực hôm nay, bộ tham số khác hoặc các mô hình hiện tại, và báo cáo điều gì sẽ thay đổi. Nó được dùng để điều tra khiếu nại và đo tác động của một thay đổi về luật hoặc mô hình. Kết quả chấm điểm lại không bao giờ được ghi lại thành quyết định.

Chạy lại và chấm điểm lại là bước 5 của [Ra quyết định AI lai](hybrid-ai.md#9-kế-hoạch-triển-khai). Tính năng này có trong bộ máy luật Java.

- [Ví dụ](#ví-dụ)
- [Lưu bản ghi](#lưu-bản-ghi)
- [Chạy lại một quyết định](#chạy-lại-một-quyết-định)
- [Chấm điểm lại một quyết định](#chấm-điểm-lại-một-quyết-định)
- [Kết quả so sánh](#kết-quả-so-sánh)
- [Phiên bản chưa được đăng ký](#phiên-bản-chưa-được-đăng-ký)
- [Đọc lại bản ghi](#đọc-lại-bản-ghi)
- [Bản ghi mẫu trong kiểm thử](#bản-ghi-mẫu-trong-kiểm-thử)
- [Workflow](#workflow)
- [Giới hạn](#giới-hạn)

## Ví dụ

Bảng [loan_eligibility v4](examples.md#xét-điều-kiện-vay-thứ-tự-ưu-tiên-và-phiên-bản) đọc các hạn mức từ bộ tham số `retail_credit`. Phiên bản 1 của bộ tham số cho phép tỷ lệ nợ trên thu nhập tối đa 0,50; phiên bản 2, có hiệu lực từ ngày 1/7/2027, chỉ cho phép 0,45. Một khách hàng có tỷ lệ 0,48 được duyệt vào ngày 30/6/2027:

```java
RuleStoneEngine engine = RuleStone.builder()
        .decision(DecisionTableJson.read(Path.of("rules/loan_eligibility.v4.stone.json")))
        .paramSet(ParameterSetJson.read(Path.of("rules/retail_credit.v1.params.json")))
        .paramSet(ParameterSetJson.read(Path.of("rules/retail_credit.v2.params.json")))
        .factsHashKey(factsHashKey)
        .auditSink(record -> auditLog.add(DecisionRecordJson.write(record)))
        .build();

DecisionResult result = engine.evaluate(
        DecisionRef.of("loan_eligibility").asOf(LocalDate.of(2027, 6, 30)).correlationId("LOAN-2027-000042"),
        facts);   // decision APPROVE
```

Về sau, bản ghi được đọc lại từ kho kiểm toán và được chạy lại với dữ liệu đầu vào lấy từ kho dữ liệu của chính ngân hàng:

```java
DecisionRecord record = DecisionRecordJson.read(stored);

DecisionComparison replay = engine.replay(record, facts);
replay.matches();   // true: the same outputs, reason codes and path, from the same facts
```

Tháng 8, khách hàng khiếu nại. Theo chính sách hiện hành, quyết định có còn như vậy không?

```java
DecisionComparison rescore = engine.rescore(record, facts,
        DecisionRef.of("loan_eligibility").asOf(record.businessDate()).params("retail_credit", "2"),
        ModelSource.RECORDED);
rescore.differences();
```

```text
PARAMS: params: recorded retail_credit 1@sha256:…, now retail_credit 2@sha256:…
OUTPUT: output 'decision' differs
OUTPUT: output 'limit': recorded, not set now
REASON_CODES: reasonCodes: recorded [], now [DTI_HIGH from loan_eligibility]
PATH: path step 1 (loan_eligibility): recorded matched rules [R5], now [R4]
```

Theo phiên bản 2, hồ sơ sẽ bị luật `R4` từ chối với mã `DTI_HIGH`. `rescore.evaluated()` là bản ghi mà lần đánh giá đó sẽ tạo ra, và `rescore.result()` là kết quả của nó, với các đầu ra mới.

## Lưu bản ghi

Để chạy lại, cần hai thứ mà engine không giữ:

- **Bản ghi**, do một [audit sink](java-api.md#bản-ghi-quyết-định-và-kiểm-toán) (nơi nhận bản ghi kiểm toán) ghi, ví dụ bằng `DecisionRecordJson.write` vào một kho chỉ cho phép ghi thêm, hoặc bằng [sink Kafka](java-api.md#đẩy-bản-ghi-lên-kafka). Hãy lưu `recordId` và `correlationId` cùng với hồ sơ nghiệp vụ để tìm lại được bản ghi.
- **Dữ liệu đầu vào**, trong kho dữ liệu của chính ngân hàng, theo các biện pháp bảo vệ dữ liệu thông thường. Bản ghi chứng minh dữ liệu đầu vào nào đã được dùng bằng `factsHash` (hash của đầu vào); khi chạy lại, hãy cấu hình engine với cùng `factsHashKey`.

Hãy giữ mọi phiên bản quyết định, phiên bản bảng và phiên bản bộ tham số được đăng ký với engine dùng để chạy lại, như bạn vẫn làm cho [phiên bản và ngày hiệu lực](decision-tables.md#phiên-bản-và-ngày-hiệu-lực): việc chạy lại dùng đúng các phiên bản mà bản ghi nêu.

## Chạy lại một quyết định

`engine.replay(record, facts)` đánh giá dữ liệu đầu vào với đúng những gì bản ghi nêu, bất kể hôm nay phiên bản nào đang có hiệu lực:

| Từ bản ghi | Cách dùng |
|---|---|
| `version` | Phiên bản chính xác của quyết định, bảng hoặc luồng, kèm hash nội dung. |
| `businessDate` | Ngày nghiệp vụ, mà luật đọc dưới tên `businessDate`. |
| `params` | Phiên bản chính xác của từng bộ tham số. |
| Các bước bảng trong `path` của luồng | Phiên bản chính xác mà từng nút `table` đã dùng, kể cả khi một phiên bản đăng ký sau này đang có hiệu lực. |
| Các bước mô hình trong `path` | Kết quả của từng nút `model`: các đầu ra, độ tin cậy, và các attribution (mức đóng góp của đặc trưng) được dựng lại từ thứ hạng và chiều của chúng, cho ra cùng các mã lý do. Một lỗi đã ghi lại là cùng lỗi đó. **Không mô hình nào được gọi**, nên quyết định vẫn chạy lại được sau khi mô hình đã được huấn luyện lại hoặc đã ngừng dùng. |
| `correlationId` | Được chép sang bản ghi mới. |

Việc chạy lại kiểm tra dữ liệu đầu vào với `factsHash` của bản ghi, tính bằng `factsHashKey` của engine. Nó **không ghi gì vào audit sink**, không dùng phiên bản canary hay phiên bản bóng (shadow), không áp thời gian chờ tối đa, và chỉ đọc đồng hồ của engine để ghi thời điểm vào bản ghi của chính nó. Dữ liệu đầu vào không được mang kết quả mô hình; các kết quả đó lấy từ bản ghi.

Bản ghi của một lần đánh giá lỗi cũng chạy lại được: lần chạy lại phải lỗi với đúng thông báo lỗi đó. Bản ghi có trạng thái `TIMEOUT` là lần đánh giá bị cắt ngang, nên lần chạy lại của nó chạy đến hết và báo trạng thái là một điểm khác biệt.

## Chấm điểm lại một quyết định

`engine.rescore(record, facts, target, models)` đánh giá dữ liệu đầu vào với quyết định mà `target` chọn, đúng như `evaluate`, và so sánh kết quả với bản ghi:

| `target` | Đánh giá |
|---|---|
| `DecisionRef.of(id).asOf(record.businessDate())` | Phiên bản có hiệu lực vào ngày nghiệp vụ gốc, với các phiên bản bộ tham số có hiệu lực khi đó, theo những gì engine biết hiện nay. |
| `DecisionRef.of(id)` | Phiên bản của hôm nay, vào ngày nghiệp vụ hôm nay, với tham số hôm nay. |
| `DecisionRef.of(id).version("5").asOf(record.businessDate())` | Một phiên bản được nêu tên, ví dụ một phiên bản ứng viên trước ngày hiệu lực của nó. |
| `….params("retail_credit", "2")` | Một phiên bản bộ tham số được nêu tên. |

`target` phải nêu đúng quyết định đã ghi. Nếu `target` không có mã tương quan (correlation id) riêng, nó lấy mã của bản ghi. Không phiên bản canary hay phiên bản bóng nào được dùng, và không có gì được ghi vào audit sink.

Trước khi đánh giá, việc chấm điểm lại kiểm tra dữ liệu đầu vào với `factsHash` của bản ghi bằng phiên bản đã ghi, nên phiên bản đó vẫn phải được đăng ký. Dữ liệu đầu vào không khớp cho ra một điểm khác biệt loại `FACTS`.

`models` cho biết các nút mô hình lấy kết quả từ đâu, và luôn phải nêu rõ:

| `ModelSource` | Nút mô hình nhận | Dùng để đo |
|---|---|---|
| `RECORDED` | Kết quả được cung cấp cùng dữ liệu đầu vào cho nút đó, nếu có; nếu không, kết quả mà bản ghi cho thấy với nút đó, nếu bước đó nêu cùng id và phiên bản mô hình; nếu không thì không có kết quả, nên nút lỗi với `MISSING` và đi theo đường `onFailure`. Không mô hình nào được gọi. | Tác động của riêng một thay đổi về luật hoặc tham số, hoặc của một mô hình thách thức (challenger) mà bạn đã tính điểm ngoại tuyến và cung cấp bằng `facts.withModelResult`. |
| `LIVE` | Những gì `evaluate` sẽ cho chúng: nút `invoke` gọi invoker của nó, nút `supplied` đọc từ dữ liệu đầu vào. | Tác động của các mô hình hiện tại. Các mô hình được gọi; độ trễ của chúng tính vào thời gian chờ tối đa của yêu cầu. |

## Kết quả so sánh

Cả hai phương thức trả về một `DecisionComparison`:

| Phương thức | Nội dung |
|---|---|
| `recorded()` | Bản ghi bạn truyền vào. |
| `evaluated()` | Bản ghi của lần đánh giá mới, với record id mới và thời điểm của lần đánh giá. Bản ghi này không được gửi tới audit sink nào; đừng lưu nó như một quyết định. |
| `result()` | Kết quả của lần đánh giá mới; rỗng nếu đánh giá lỗi. |
| `differences()` | Những gì khác nhau, theo thứ tự các trường của bản ghi. |
| `matches()` | Có phải không có khác biệt nào hay không. |

Mỗi `Difference` có một `kind` và một `description`. Phần mô tả nêu tên đầu ra, nút, luật, phiên bản, ngày và mã lý do, nhưng **không bao giờ chứa giá trị đầu vào hay đầu ra**, nên có thể ghi log như bản ghi. Hãy so sánh giá trị của `recorded()` và `evaluated()` khi cần.

| Loại | Khác nhau |
|---|---|
| `FACTS` | Dữ liệu đầu vào không cho ra `factsHash` của bản ghi: dữ liệu khác, hoặc `factsHashKey` khác. |
| `STATUS` | Trạng thái, hoặc thông báo lỗi của một lần đánh giá lỗi. |
| `VERSION` | Phiên bản chính xác của quyết định. |
| `BUSINESS_DATE` | Ngày nghiệp vụ. |
| `PARAMS` | Các phiên bản bộ tham số. |
| `OUTPUT` | Giá trị của một đầu ra, hoặc đầu ra đó có được đặt hay không. Giá trị được so sánh ở dạng JSON, nên `"0.0980"` đọc lại từ bản ghi bằng số thập phân `0.0980`. |
| `REASON_CODES` | Các mã lý do, nguồn của chúng, hoặc thứ tự của chúng. |
| `PATH` | Một bước của đường đi: nút, phiên bản bảng, các luật khớp, nhánh được chọn, hoặc id, phiên bản, trạng thái, đầu ra, độ tin cậy hay attribution của một mô hình. Chế độ, độ trễ và chi tiết lỗi của mô hình cho biết kết quả được lấy bằng cách nào, không phải kết quả là gì, nên không được so sánh. |

## Phiên bản chưa được đăng ký

Nếu một phiên bản mà bản ghi nêu không được đăng ký với đúng hash nội dung đã ghi, `replay` và `rescore` lỗi với `DecisionNotFoundException`, nêu tên phiên bản và không bao giờ nêu giá trị:

```text
decision 'loan_eligibility' has no version 4@sha256:9f2c…: version '4' is registered with other content, 4@sha256:77ba…; register the content the record names
parameter set 'retail_credit' has no version 1@sha256:41d8…
```

Thông báo đầu tiên nghĩa là ai đó đã sửa luật của phiên bản `4` mà không đặt nhãn phiên bản mới. Hãy đăng ký lại nội dung gốc; phát hành thay đổi dưới một nhãn mới.

## Đọc lại bản ghi

`DecisionRecordJson.read` đọc một bản ghi do `DecisionRecordJson.write` ghi, từ một `String`, `InputStream`, `Path` hoặc `JsonNode`. Việc đọc là chặt chẽ: trường lạ và trường trùng lặp, trường bị thiếu và giá trị sai dạng được báo cùng lúc trong một `InvalidRecordException`, mà `problems()` cho biết vị trí và dạng mong đợi, không bao giờ là giá trị.

Bản ghi không mang kiểu dữ liệu, nên các giá trị đầu ra đọc lại có dạng Java tương ứng với JSON của chúng: `String` cho số thập phân, tiền hoặc ngày, ví dụ `"0.0980"`; `Long` cho số nguyên; `Boolean`; `List` hoặc `Map`. Ghi lại bản ghi cho ra đúng JSON ban đầu.

## Bản ghi mẫu trong kiểm thử

`ReplayCheck` trong `rulestone-test` khẳng định rằng các quyết định đã ghi vẫn chạy lại được, ví dụ một vài bản ghi đã được review được giữ cùng với luật, để bản build lỗi khi một bản phát hành không còn tái lập được chúng:

```java
ReplayCheck.assertReplays(engine, Path.of("golden/loan-2027-000042.record.json"), facts);
```

Nó ném `AssertionError` liệt kê các điểm khác biệt.

## Workflow

- **Temporal.** `DecisionOutcome.recordId()` nêu bản ghi của một quyết định, và `correlationId()` cho mã tương quan của nó, mà activity lấy từ `DecisionRequest.withCorrelationId`. Đánh giá trong workflow ghi bản ghi quyết định vào history của workflow và chạy lại nó ở mọi workflow task sau đó, nên audit sink chỉ nhận bản ghi một lần và kết quả luôn nêu cùng một bản ghi. Xem [Tích hợp Temporal](temporal.md#bản-ghi-quyết-định).
- **Camunda.** Biến kết quả chứa `recordId`, và `correlationId` khi tác vụ nêu một `correlationIdVariable`. Xem [Tích hợp Camunda](camunda.md#bản-ghi-quyết-định).

## Giới hạn

- Bộ máy luật TypeScript của [SDK trình soạn thảo](editor-sdk.md) cũng replay và chấm điểm lại được bản ghi (`engine.replay`, `engine.rescore`, `readDecisionRecord`), với cùng cách so sánh, và replay được bản ghi của bộ máy luật Java.
- Dữ liệu tra cứu trong lúc đánh giá không được ghi lại, vì RuleStone không tra cứu gì: mọi thứ một quyết định đọc đều nằm trong dữ liệu đầu vào.
- Chấm điểm lại với `ModelSource.LIVE` gọi các mô hình hiện tại, mà nói chung không có tính tất định; kết quả của nó là một phép đo, không phải một quyết định.
