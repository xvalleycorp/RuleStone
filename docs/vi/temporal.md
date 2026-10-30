---
source: temporal.md
sourceHash: 7ebe14c77a952ebffb4c3f351416d633b94970fbad316d60855a4ec9ea85267e
---

# Tích hợp Temporal

[Temporal](https://temporal.io) chạy các quy trình nghiệp vụ kéo dài, như khởi tạo khoản vay hay điều tra thanh toán, dưới dạng workflow bền vững (durable). Mô-đun `rulestone-temporal` cho phép các workflow đó đưa ra quyết định bằng RuleStone theo cách luôn tái lập được qua các lần thử lại, các lần khởi động lại worker và các lần phát hành luật.

- [Hai cách dùng](#hai-cách-dùng)
- [Thiết lập](#thiết-lập)
- [Đánh giá trong workflow](#đánh-giá-trong-workflow)
- [Đánh giá bằng activity](#đánh-giá-bằng-activity)
- [Payload: số thập phân, tiền và ngày](#payload-số-thập-phân-tiền-và-ngày)
- [Lỗi và thử lại](#lỗi-và-thử-lại)
- [Quy tắc về tính tất định và phiên bản](#quy-tắc-về-tính-tất-định-và-phiên-bản)
- [Search attribute](#search-attribute)
- [Danh sách kiểm tra vận hành](#danh-sách-kiểm-tra-vận-hành)

## Hai cách dùng

| | Trong workflow: `RuleStoneWorkflow` | Activity: `RuleStoneActivities` |
|---|---|---|
| Luật chạy ở đâu | Trong mã workflow, trên workflow worker | Trong một activity, trên bất kỳ worker nào có các luật |
| History ghi lại gì | Ngày nghiệp vụ và chính xác phiên bản luật (`3@sha256:…`), vài trăm byte | Toàn bộ yêu cầu (fact) và kết quả, cùng các sự kiện của activity |
| Độ trễ | Không có: không cần gọi tới dịch vụ Temporal | Một activity: lên lịch, worker poll và hoàn tất |
| Ngôn ngữ viết workflow | Java | Bất kỳ: payload là JSON thông thường |
| Xem kết quả | Chỉ khi workflow trả về, lưu hoặc upsert kết quả | Trong kết quả activity, trên giao diện Temporal |
| Replay workflow cũ | Cần mọi phiên bản luật mà chúng đã dùng vẫn được đăng ký | Không cần gì: kết quả đã ghi được dùng lại |

Hãy dùng cách đánh giá **trong workflow** cho các workflow Java đưa ra nhiều quyết định hoặc cần quyết định nhanh, như tính giá cho từng kỳ trả góp của một lịch trả nợ. Hãy dùng **activity** khi workflow được viết bằng ngôn ngữ khác, khi chỉ những worker chuyên dụng mới được nạp luật, hoặc khi kiểm toán viên muốn thấy đầu vào và kết quả của từng quyết định trong history của workflow.

Việc đánh giá là thuần (pure), nên cách đánh giá trong workflow chỉ cần lưu luật *nào* đã được dùng, không cần lưu kết quả: chạy lại cùng phiên bản luật vào cùng ngày với cùng các fact cho cùng kết quả. Cái giá phải trả là yêu cầu về replay ở dòng cuối của bảng.

## Thiết lập

Thêm mô-đun. Temporal SDK có scope `provided`: mô-đun dùng phiên bản `io.temporal:temporal-sdk` mà ứng dụng của bạn đang dùng. Mô-đun được build và kiểm thử với Temporal Java SDK 1.40.0.

```xml
<dependency>
    <groupId>vn.io.dxa.rulestone</groupId>
    <artifactId>rulestone-temporal</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

Đăng ký cả hai cách dùng trên một worker. Hãy tạo một engine từ mọi phiên bản luật mà các workflow đang chạy có thể còn replay, và đưa cho activity cùng các bảng đó:

```java
RuleStoneEngine engine = RuleStone.builder()
        .clock(clock)
        .decisions(tables)       // every version that running workflows may replay
        .build();

Worker worker = factory.newWorker(TASK_QUEUE);

// Activity style: the activity reads facts with the input types of these tables.
worker.registerActivitiesImplementations(new RuleStoneActivitiesImpl(engine, tables, clock));
worker.registerWorkflowImplementationTypes(LoanActivityWorkflowImpl.class);

// In-workflow style: the workflow needs the engine, so register a factory.
worker.registerWorkflowImplementationFactory(LoanWorkflow.class,
        () -> new LoanWorkflowImpl(engine, zone));
```

`clock` là múi giờ nghiệp vụ của ngân hàng bạn, ví dụ `Clock.system(ZoneId.of("Asia/Ho_Chi_Minh"))`, và `zone` là `clock.getZone()`.

## Đánh giá trong workflow

`RuleStoneWorkflow.of(engine, businessZone)` trả về một đối tượng hỗ trợ cho mã workflow. `evaluate(DecisionRef, Facts)` trả về một [`DecisionOutcome`](#payload-số-thập-phân-tiền-và-ngày):

```java
public class LoanWorkflowImpl implements LoanWorkflow {

    private final RuleStoneWorkflow rules;

    public LoanWorkflowImpl(RuleStoneEngine engine, ZoneId businessZone) {
        this.rules = RuleStoneWorkflow.of(engine, businessZone);
    }

    @Override
    public DecisionOutcome decide(LoanApplication application) {
        DecisionOutcome outcome = rules.evaluate(
                DecisionRef.of("loan_eligibility"),
                Facts.of("applicant", application.applicant(), "amount", application.amount()));
        RuleStoneSearchAttributes.upsert(outcome, "decision");
        return outcome;
    }
}
```

### Ngày nghiệp vụ

Nếu không có `DecisionRef.asOf`, ngày nghiệp vụ là `Workflow.currentTimeMillis()` quy đổi thành ngày theo múi giờ nghiệp vụ. Đồng hồ hệ thống và đồng hồ của engine không bao giờ được đọc. Trong kiểm thử có tua nhanh thời gian (time skipping), một workflow ngủ qua một ngày hiệu lực sẽ chuyển sang phiên bản luật mới, đúng như trên production. Hãy truyền `asOf` khi ngày nghiệp vụ là một dữ kiện của hồ sơ, như ngày nộp hồ sơ vay.

### Cách ghim phiên bản

Ở **lần thực thi đầu tiên** của mỗi lần gọi `evaluate`:

1. Ngày nghiệp vụ được xác định như trên.
2. Bên trong `Workflow.sideEffect`, engine chọn phiên bản có hiệu lực vào ngày đó. Id quyết định, ngày nghiệp vụ và chính xác phiên bản luật, `<version>@sha256:<hash>`, được ghi vào history của workflow dưới dạng một marker JSON nhỏ. Các fact không được ghi lại.
3. Quyết định được đánh giá với đúng phiên bản và ngày đó: `DecisionRef.of(id).version(label).asOf(date)`.

Khi **replay**, bước 2 không chạy. Marker đã ghi được đọc lại và bước 3 đánh giá phiên bản đã ghi vào ngày đã ghi. Một phiên bản có hiệu lực trong khoảng thời gian đó, một múi giờ bị thay đổi hay một đồng hồ khác đều không thể làm thay đổi kết quả. Sau đó mã băm nội dung hiện tại của engine cho nhãn phiên bản đó được so sánh với mã băm đã ghi.

`sideEffect` được dùng thay cho các phương án khác vì nó ghi một giá trị một lần và trả về đúng giá trị đó ở mọi lần replay, không cần gọi tới dịch vụ Temporal. `mutableSideEffect` dành cho các giá trị thay đổi trong một lần chạy, được định danh bằng một id dùng chung giữa các lần gọi. Một local activity sẽ thêm thời gian chờ tối đa, cơ chế thử lại và một luồng worker thứ hai cho một công việc thuần CPU không thể lỗi tạm thời. Các marker được ghép với lời gọi theo thứ tự, nên marker còn lưu id quyết định, và một lần replay đi tới một quyết định khác ở vị trí đó sẽ thất bại.

### Khi replay không tái lập được quyết định

Nếu phiên bản đã ghi không còn được đăng ký, hoặc mã băm nội dung của nó khác đi (có người sửa nội dung luật mà không đổi nhãn phiên bản), `evaluate` ném `RuleVersionMismatchException`. Thông báo lỗi nêu tên quyết định và cả hai phiên bản:

```text
RuleStone replay mismatch for decision 'loan_eligibility': workflow history recorded rule version 3@sha256:2008…, but version '3' now registered in the engine is 3@sha256:77ba…. The rule content changed without a new version label. Register the original content of version '3' again (publish changes under a new label); the workflow task is retried until then.
```

Ngoại lệ này là một `NonDeterministicException` của Temporal. Temporal làm thất bại *workflow task*, hiển thị thông báo trên giao diện và thử lại task đó. Workflow vẫn mở: nó không hoàn tất, cũng không thất bại với một kết quả mà bộ luật ban đầu sẽ không tạo ra. Hãy đăng ký lại nội dung luật ban đầu và workflow sẽ tiếp tục. Nếu bạn muốn làm thất bại các workflow như vậy, hãy thêm `NonDeterministicException.class` vào `WorkflowImplementationOptions.setFailWorkflowExceptionTypes`, như Temporal khuyến nghị cho các trường hợp không tất định khác.

### Fact trong mã workflow

Fact phải là dữ liệu workflow tất định: tham số của workflow, payload của signal và update, và kết quả của activity. Không bao giờ tạo fact từ đồng hồ, số ngẫu nhiên, biến môi trường, cơ sở dữ liệu hay các trường static có thể thay đổi. Các record có trường `BigDecimal`, `Money` và `LocalDate`, như `LoanApplication` ở trên, là tham số workflow an toàn với data converter mặc định của Temporal.

## Đánh giá bằng activity

Kiểu activity là `RuleStoneEvaluate`. Workflow gửi một `DecisionRequest` và nhận về một `DecisionOutcome`:

```java
static final ActivityOptions RULESTONE_OPTIONS = ActivityOptions.newBuilder()
        // Evaluation does no I/O: a short timeout detects a lost worker quickly.
        .setStartToCloseTimeout(Duration.ofSeconds(10))
        .setRetryOptions(RetryOptions.newBuilder()
                .setInitialInterval(Duration.ofSeconds(1))
                .setMaximumInterval(Duration.ofMinutes(1))
                // Bounded: an unexpected error is a bug or an outage; do not retry forever.
                .setMaximumAttempts(10)
                // RuleStone failures are non-retryable already; this states it in the policy.
                .setDoNotRetry(RuleStoneFailures.DECISION_NOT_FOUND,
                        RuleStoneFailures.DECISION_EVALUATION_FAILED,
                        RuleStoneFailures.INVALID_REQUEST)
                .build())
        .build();

private final RuleStoneActivities rules =
        Workflow.newActivityStub(RuleStoneActivities.class, RULESTONE_OPTIONS);

@Override
public DecisionOutcome decide(LoanApplication application) {
    DecisionRequest request = DecisionRequest
            .of("loan_eligibility",
                    Facts.of("applicant", application.applicant(), "amount", application.amount()))
            .withAsOf(application.applicationDate());
    try {
        return rules.evaluate(request);
    } catch (ActivityFailure e) {
        if (e.getCause() instanceof ApplicationFailure failure
                && RuleStoneFailures.DECISION_EVALUATION_FAILED.equals(failure.getType())) {
            // Never substitute a default decision: hand the case to a person.
            return manualReview(application, failure);
        }
        throw e;
    }
}
```

Hãy truyền `withAsOf` mỗi khi hồ sơ có ngày nghiệp vụ. Nếu không, activity dùng ngày hôm nay theo đồng hồ của chính nó, truyền ngày đó một cách tường minh cho engine và ghi lại trong `DecisionOutcome.businessDate()`. Vì kết quả được ghi trong history, một workflow được replay sẽ nhận kết quả ban đầu kể cả khi luật đã thay đổi.

**Hiện chỉ có bảng quyết định.** Activity đánh giá bảng quyết định. [Luồng quyết định](flows.md) chưa thể được đánh giá qua activity. `DecisionRequest.withModels(models)` đã mang được kết quả của những mô hình mà bên gọi tự chạy, ở dạng `models` của [kết quả do bên gọi cung cấp](flows.md#kết-quả-do-bên-gọi-cung-cấp), cho các nút mô hình của luồng. Activity đọc chúng một cách chặt chẽ và báo một kết quả sai định dạng là `INVALID_REQUEST`; cho tới khi activity đánh giá được luồng, một bảng quyết định sẽ từ chối chúng với `DECISION_EVALUATION_FAILED`.

## Payload: số thập phân, tiền và ngày

`DecisionRequest.facts()` và `DecisionOutcome.output()` là các đối tượng JSON theo các dạng của `FactsJson` (xem [Java API](java-api.md)), nên chính xác và đọc được từ mọi ngôn ngữ:

| Kiểu | JSON | Ví dụ |
|---|---|---|
| `decimal` | một chuỗi theo cách viết thông thường; số JSON được chấp nhận ở đầu vào | `"25750000.50"` |
| `money` | `"<amount> <CCY>"` | `"1500000.50 VND"` |
| `date` | `"yyyy-MM-dd"` | `"2026-11-01"` |
| `int` | một số nguyên | `30` |
| `string`, `bool` | như trong JSON | `"RETAIL"`, `false` |
| struct, danh sách | một đối tượng, một mảng | |

`DecisionRequest.of(decisionId, facts)` chuyển các giá trị Java, kể cả record, sang các dạng này và từ chối `Double` và `Float`. Một payload từ một client viết bằng ngôn ngữ khác trông như sau:

```json
{
  "decisionId": "loan_eligibility",
  "version": null,
  "asOf": "2026-11-15",
  "facts": {
    "applicant": {
      "age": 30,
      "monthlyIncome": "25750000.50",
      "monthlyDebt": "1000000",
      "segment": "RETAIL",
      "blacklisted": false
    },
    "amount": 100000000.00
  }
}
```

Activity đọc các fact theo kiểu đầu vào của phiên bản quyết định mà nó đánh giá. Tên chưa khai báo, giá trị sai dạng và giá trị `null` khiến toàn bộ yêu cầu bị từ chối, kèm danh sách mọi lỗi. Số được đọc chính xác từ văn bản của chúng, không bao giờ qua `double`.

Hãy đọc đầu ra bằng các phương thức truy cập có kiểu: `outcome.text("decision")`, `outcome.decimal("limit")`, `outcome.money("fee")`, `outcome.date("nextReview")`, `outcome.integer(…)`, `outcome.bool(…)`, hoặc `outcome.outputValue(name, ValueType.listOf(ValueType.MONEY))` với danh sách. Mỗi phương thức trả về một `Optional` rỗng khi quyết định không đặt đầu ra đó.

Bảo đảm về độ chính xác giữ nguyên với data converter Jackson 2 mặc định của Temporal, hoặc bất kỳ JSON converter nào, vì các giá trị chính xác được truyền dưới dạng văn bản. Nếu bạn chọn dùng converter Jackson 3 còn thử nghiệm của Temporal, các số JSON do client khác gửi sẽ không còn được bộ giải mã chính xác của RuleStone đọc nữa; hãy gửi số thập phân dưới dạng chuỗi.

## Lỗi và thử lại

| Loại lỗi | Phát sinh khi | Thử lại | Cách xử lý |
|---|---|---|---|
| `RuleStone.DecisionNotFound` | Quyết định hoặc phiên bản không xác định, hoặc không có phiên bản nào có hiệu lực vào ngày nghiệp vụ | Không bao giờ | Lỗi cấu hình: kiểm tra các phiên bản đã đăng ký và ngày hiệu lực của chúng. |
| `RuleStone.DecisionEvaluationFailed` | Đầu vào bị thiếu hoặc không hợp lệ, một biểu thức luật bị lỗi, một bảng `UNIQUE` có nhiều luật cùng khớp | Không bao giờ | Chuyển hồ sơ sang xem xét thủ công. Không thay bằng một quyết định mặc định. Chi tiết: id quyết định, phiên bản luật. |
| `RuleStone.InvalidRequest` (chỉ với activity) | Fact không theo đúng các dạng JSON mong đợi | Không bao giờ | Sửa phía gọi. |
| Mọi lỗi activity khác | Lỗi phần mềm hoặc sự cố hạ tầng | Có, theo retry options của activity | Giới hạn số lần thử lại bằng `setMaximumAttempts` và cảnh báo khi thất bại. |
| `RuleVersionMismatchException` (chỉ với cách trong workflow) | Replay không tái lập được một quyết định đã ghi | Workflow task được thử lại | Xem [Khi replay không tái lập được quyết định](#khi-replay-không-tái-lập-được-quyết-định). |

Cả ba loại `RuleStone.*` đều là `ApplicationFailure` không thử lại được; các hằng số nằm trong `RuleStoneFailures`. Từ activity, chúng tới workflow dưới dạng cause của một `ActivityFailure`; từ `RuleStoneWorkflow`, chúng được ném trực tiếp và làm thất bại workflow nếu không được bắt. Thử lại chúng là vô ích: việc đánh giá là tất định, nên cùng một yêu cầu sẽ thất bại theo cùng một cách.

Thông báo lỗi và chi tiết lỗi nêu tên quyết định, phiên bản, luật, đầu vào và trường liên quan, không bao giờ nêu giá trị đầu vào. Ngoại lệ gốc không được đính kèm, nên không có thông báo lồng nhau hay stack trace nào lọt vào history của workflow. Mô-đun không ghi log fact ở bất kỳ đâu. Tuy vậy, fact *có* được lưu trong history dưới dạng đầu vào của activity; nếu không được phép lưu chúng ở dạng rõ, hãy cấu hình một payload codec mã hoá payload.

## Quy tắc về tính tất định và phiên bản

1. **Một phiên bản luật là bất biến khi đã được dùng.** Hãy phát hành mọi thay đổi dưới một nhãn phiên bản mới. Mã băm thay đổi dưới một nhãn cũ sẽ làm các lần replay trong workflow dừng lại với `RuleVersionMismatchException`.
2. **Giữ các phiên bản cũ ở trạng thái đã đăng ký** trong engine của mọi worker chạy đánh giá trong workflow, cho tới khi mọi workflow đã dùng chúng đều đóng và đã qua thời hạn lưu giữ (retention) của namespace. Dùng `effectiveTo` để ngừng dùng một phiên bản cho các quyết định mới; đừng xoá tệp của nó.
3. **Không đổi thứ tự các lần đánh giá** trong các workflow đang chạy. Thêm, bớt hoặc di chuyển một lời gọi `evaluate` là thay đổi mã workflow; hãy bảo vệ nó bằng `Workflow.getVersion`.
4. **Fact là dữ liệu workflow**, như mô tả trong [Fact trong mã workflow](#fact-trong-mã-workflow).
5. **Chọn múi giờ nghiệp vụ một lần.** Đổi múi giờ không ảnh hưởng tới các workflow đang chạy, vốn đã ghi lại ngày, nhưng sẽ thay đổi ngày mà các workflow mới nhận được khi gần nửa đêm.

## Search attribute

`RuleStoneSearchAttributes.upsert(outcome, "decision")` đặt bốn search attribute có kiểu, để người vận hành tìm workflow theo kết quả, ví dụ `RuleStoneDecision = 'loan_eligibility' AND RuleStoneOutcome = 'REVIEW'`:

| Attribute | Kiểu | Giá trị |
|---|---|---|
| `RuleStoneDecision` | Keyword | Id quyết định |
| `RuleStoneRuleVersion` | Keyword | `<version>@sha256:<hash>` |
| `RuleStoneOutcome` | Keyword | Dạng JSON của đầu ra được chỉ định; bị xoá nếu quyết định không đặt đầu ra đó |
| `RuleStoneReasonCodes` | KeywordList | Các mã lý do; bị xoá nếu không có mã nào |

Hãy đăng ký chúng trong mọi namespace trước khi triển khai, nếu không lệnh upsert sẽ làm thất bại workflow task:

```sh
temporal operator search-attribute create --namespace bank \
    --name RuleStoneDecision --type Keyword \
    --name RuleStoneRuleVersion --type Keyword \
    --name RuleStoneOutcome --type Keyword \
    --name RuleStoneReasonCodes --type KeywordList
```

Một workflow chỉ giữ một giá trị cho mỗi attribute; khi workflow đưa ra nhiều quyết định, hãy upsert quyết định mà người vận hành dùng để tìm kiếm. `RuleStoneSearchAttributes.updates(…)` trả về các cập nhật để kết hợp với cập nhật của riêng bạn. Search attribute không được payload codec mã hoá và hiển thị với bất kỳ ai có quyền visibility. Hãy dùng một đầu ra mang tính phân loại như `decision` làm kết quả, không bao giờ dùng số tiền hay dữ liệu cá nhân.

## Danh sách kiểm tra vận hành

- [ ] Engine của worker và các bảng của activity chứa mọi phiên bản luật mà các workflow đang mở (cách trong workflow) đã dùng, cộng thêm thời hạn lưu giữ.
- [ ] Các lần phát hành luật thêm nhãn phiên bản mới và không bao giờ sửa phiên bản đã dùng. CI so sánh mã băm nội dung của các nhãn đã phát hành.
- [ ] Đồng hồ của engine, đồng hồ của activity và múi giờ của `RuleStoneWorkflow` là múi giờ nghiệp vụ của ngân hàng bạn.
- [ ] Activity options có start-to-close timeout ngắn và số lần thử lại có giới hạn.
- [ ] Workflow xử lý `RuleStone.DecisionEvaluationFailed` bằng cách chuyển sang xem xét thủ công, không dùng quyết định mặc định.
- [ ] Có cảnh báo khi workflow task thất bại với thông báo chứa `RuleStone replay mismatch`.
- [ ] Bốn search attribute đã được đăng ký trong mọi namespace có upsert chúng.
- [ ] Một payload codec mã hoá history nếu không được lưu fact ở dạng rõ.
- [ ] Trước mỗi lần phát hành, một bài kiểm thử replay chạy các history đã ghi từ production với bộ luật mới, bằng `WorkflowReplayer`.
