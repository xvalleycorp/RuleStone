---
source: camunda.md
sourceHash: 9a1bf67221a81879d6fb8ef2e610f87f350e2ccf810079222c83e4f596ddb170
---

# Tích hợp Camunda

Nhiều ngân hàng điều phối quy trình khởi tạo khoản vay, tiếp nhận khách hàng và điều tra thanh toán bằng Camunda. Hai mô-đun cho phép một quy trình BPMN đưa ra quyết định bằng RuleStone:

| Mô-đun | Dành cho | Cung cấp |
|---|---|---|
| `rulestone-camunda8` | Camunda 8 (Zeebe) | Một job worker, và một element template cho Camunda Modeler biến một service task thành "RuleStone decision" |
| `rulestone-camunda7` | Engine nhúng Camunda 7 | Một `JavaDelegate`, một process engine plugin và một expression bean |

Cả hai đều đọc đầu vào của quyết định từ các biến quy trình, đánh giá phiên bản có hiệu lực vào ngày nghiệp vụ, và ghi cùng một kết quả: `output`, `reasonCodes`, `matchedRules`, `ruleVersion` và `matched`. Cả hai đều biến dữ liệu sai thành một **BPMN error** mà một boundary event có thể bắt, và biến lỗi cấu hình, như một quyết định không xác định, thành một **incident**. Không mô-đun nào ghi log, và không thông báo nào chứa giá trị của biến.

- [Camunda 8](#camunda-8)
- [Camunda 7](#camunda-7)
- [Độ chính xác số thập phân](#độ-chính-xác-số-thập-phân)
- [RuleStone hay Camunda DMN?](#rulestone-hay-camunda-dmn)

## Camunda 8

### Thiết lập

Thêm mô-đun, cùng với Camunda Java client mà ứng dụng của bạn đang dùng. Client có scope `provided`: RuleStone không chọn phiên bản của nó cũng không phân phối lại nó. Mô-đun được build và kiểm thử với `io.camunda:camunda-client-java` 8.10.6, thư viện có giấy phép Apache-2.0 như mọi thư viện mà RuleStone phụ thuộc.

```xml
<dependency>
    <groupId>vn.io.dxa.rulestone</groupId>
    <artifactId>rulestone-camunda8</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

Đăng ký một worker. Worker cần engine và các bảng quyết định dùng để tạo engine: kiểu đầu vào của các bảng cho biết cách đọc biến quy trình.

```java
List<DecisionTable> tables;
try (Stream<Path> files = Files.list(rulesDirectory)) {
    tables = files.filter(f -> f.toString().endsWith(".stone.json"))
            .map(DecisionTableJson::read)
            .toList();
}
Clock clock = Clock.system(ZoneId.of("Asia/Ho_Chi_Minh"));
RuleStoneEngine engine = RuleStone.builder().clock(clock).decisions(tables).build();

return RuleStoneJobWorker.register(client, engine, tables,
        RuleStoneWorkerOptions.builder().clock(clock).build());
```

Worker đăng ký nhận job type `rulestone:decision`. `RuleStoneWorkerOptions` còn đặt job type, các biến cần lấy, mã BPMN error mặc định, thời gian chờ giữa các lần thử lại và tên worker. Với mọi thiết lập worker khác, hãy tự tạo worker với `new RuleStoneJobHandler(engine, tables, options)` làm handler. Hiện chưa có Spring Boot starter.

### Element template

`rulestone-camunda8/element-templates/rulestone-decision.json` là một element template cho Camunda 8. Template được kiểm tra hợp lệ theo phiên bản 0.46.0 của [JSON schema cho element template](https://github.com/camunda/element-templates-json-schema) và cần Camunda 8.6 trở lên.

- **Desktop Modeler:** chép tệp vào `resources/element-templates` trong thư mục dữ liệu người dùng của Modeler, hoặc vào thư mục `.camunda/element-templates` cạnh các tệp `.bpmn` của bạn, rồi khởi động lại Modeler.
- **Web Modeler:** tải tệp lên một project, rồi publish template để các sơ đồ có thể dùng.

Sau đó chọn một task, chọn **Change element → RuleStone decision**, và điền vào bảng thuộc tính. Template ghi job type và các custom header của task:

```xml
<bpmn:serviceTask id="CheckEligibility" name="Check eligibility"
                  zeebe:modelerTemplate="vn.io.dxa.rulestone.decision" zeebe:modelerTemplateVersion="1">
  <bpmn:extensionElements>
    <zeebe:taskDefinition type="rulestone:decision" retries="3" />
    <zeebe:taskHeaders>
      <zeebe:header key="decisionId" value="loan_eligibility" />
      <zeebe:header key="asOfVariable" value="applicationDate" />
      <zeebe:header key="resultVariable" value="decision" />
      <zeebe:header key="failOnNoMatch" value="false" />
      <zeebe:header key="errorCode" value="RULESTONE_DECISION_FAILED" />
    </zeebe:taskHeaders>
    <zeebe:ioMapping>
      <zeebe:output source="=decision" target="decision" />
      <zeebe:output source="=decision.output.decision" target="loanDecision" />
    </zeebe:ioMapping>
  </bpmn:extensionElements>
  <bpmn:incoming>Flow_ToCheck</bpmn:incoming>
  <bpmn:outgoing>Flow_ToGateway</bpmn:outgoing>
</bpmn:serviceTask>
```

| Header | Bắt buộc | Mặc định | Ý nghĩa |
|---|---|---|---|
| `decisionId` | có | | Quyết định cần đánh giá. |
| `version` | không | | Một nhãn phiên bản cụ thể, để chạy lại và điều tra. Nếu không có, dùng phiên bản có hiệu lực vào ngày nghiệp vụ. |
| `asOfVariable` | không | | Một biến quy trình chứa ngày nghiệp vụ dạng `"yyyy-MM-dd"`, như ngày nộp hồ sơ. Nếu không có, dùng ngày hôm nay theo đồng hồ của worker. |
| `modelsVariable` | không | | Một biến quy trình chứa kết quả của những mô hình mà quy trình tự chạy, ở dạng `models` của [kết quả do bên gọi cung cấp](flows.md#kết-quả-do-bên-gọi-cung-cấp). Chưa có trong element template; xem bên dưới. |
| `resultVariable` | không | `decision` | Biến nhận kết quả. |
| `failOnNoMatch` | không | `false` | `true` sẽ ném BPMN error khi không có luật nào khớp. |
| `errorCode` | không | `RULESTONE_DECISION_FAILED` | Mã của BPMN error. |

Hãy dùng `asOfVariable` mỗi khi quy trình có ngày nghiệp vụ: khi đó quyết định không đổi nếu job được thử lại, hoặc quy trình phải chờ, qua thời điểm chuyển phiên bản.

**Hiện chỉ có bảng quyết định.** Worker đánh giá bảng quyết định; [luồng quyết định](flows.md) chưa thể được đánh giá qua worker. Header `modelsVariable` dành cho các nút mô hình của luồng. Worker đọc biến này một cách chặt chẽ và báo biến bị thiếu hoặc sai định dạng bằng một BPMN error; cho tới khi worker đánh giá được luồng, một bảng quyết định sẽ từ chối kết quả mô hình, cũng bằng một BPMN error.

### Biến

Worker chỉ đọc **các đầu vào mà quyết định khai báo**, mỗi đầu vào theo kiểu đã khai báo; mọi biến khác đều bị bỏ qua. Giá trị dùng các dạng JSON của RuleStone (`FactsJson`, xem [Java API](java-api.md)):

| Kiểu | Biến quy trình | Ví dụ |
|---|---|---|
| `decimal` | một chuỗi theo cách viết thông thường, hoặc một số JSON (xem [Độ chính xác số thập phân](#độ-chính-xác-số-thập-phân)) | `"25750000.50"` |
| `money` | `"<amount> <CCY>"` | `"1500000.50 VND"` |
| `date` | `"yyyy-MM-dd"` | `"2026-11-01"` |
| `int` | một số nguyên | `35` |
| `string`, `bool` | như trong JSON | `"MASS"`, `false` |
| struct, danh sách | một đối tượng, một mảng | |

Mặc định Zeebe gửi kèm mỗi job mọi biến mà task nhìn thấy. Để gửi ít dữ liệu hơn, và giữ các dữ liệu cá nhân không liên quan tránh xa worker, hãy cho mỗi quyết định một job type riêng và chỉ lấy các đầu vào cùng biến ngày nghiệp vụ của nó:

```java
return RuleStoneJobWorker.register(client, engine, tables,
        RuleStoneWorkerOptions.builder()
                .jobType("rulestone:loan_eligibility")
                .fetchVariables("applicant", "amount", "applicationDate")
                .clock(clock)
                .build());
```

Trong mô hình, đặt **Job type** của task (trong nhóm **Worker** của template) là `rulestone:loan_eligibility`.

### Kết quả và output mapping

Job hoàn tất với một biến:

```json
{
  "decision": {
    "output": { "decision": "APPROVE", "limit": "309000000" },
    "reasonCodes": [],
    "matchedRules": ["R4"],
    "ruleVersion": "3@sha256:…",
    "matched": true
  }
}
```

Số thập phân, tiền và ngày là chuỗi, nên không bao giờ đi qua dấu phẩy động. So sánh số thập phân trong FEEL bằng `number(decision.output.limit) >= 100000000`, và so sánh văn bản trực tiếp: `decision.output.decision = "APPROVE"`.

Nếu không có output mapping, Zeebe gộp biến vào process instance. Để kiểm soát phạm vi, hoặc để chép từng giá trị riêng, hãy thêm output mapping như trong task ở trên. Khi một task có output mapping, chỉ các biến đã ánh xạ mới tới được quy trình.

### Lỗi và incident

| Tình huống | Hành vi |
|---|---|
| Một đầu vào bị thiếu hoặc sai dạng; biến ngày nghiệp vụ bị thiếu hoặc không phải ngày; một luật bị lỗi, ví dụ chia cho không; một bảng `UNIQUE` khớp hai luật; không luật nào khớp và `failOnNoMatch` là `true` | Task ném BPMN error `errorCode`. Hãy bắt nó bằng một error boundary event và chuyển hồ sơ sang xem xét thủ công. **Không thay bằng một quyết định mặc định.** |
| Quyết định hoặc phiên bản không xác định, không có phiên bản nào có hiệu lực vào ngày nghiệp vụ, hoặc một header không hợp lệ | Job thất bại và không còn lần thử lại nào, tạo ra một **incident** với thông báo cho biết cần sửa gì. Hãy sửa phần triển khai hoặc mô hình, rồi thử lại incident trong Operate. |
| Mọi trường hợp khác, như sự cố hạ tầng | Job thất bại và bớt đi một lần thử lại, sau thời gian chờ (mặc định 10 giây). Khi hết lần thử lại, Zeebe tạo một incident. |

Một boundary event cho lỗi, trong tệp mẫu `loan-approval.bpmn`:

```xml
<bpmn:boundaryEvent id="DecisionFailed" name="Decision failed" attachedToRef="CheckEligibility">
  <bpmn:outgoing>Flow_FailedToReview</bpmn:outgoing>
  <bpmn:errorEventDefinition id="ErrorDefinition_DecisionFailed" errorRef="Error_DecisionFailed" />
</bpmn:boundaryEvent>
```

Thông báo lỗi và incident nêu tên quyết định, phiên bản luật, các đầu vào và các dạng mong đợi, không bao giờ nêu giá trị. Với các ngoại lệ không lường trước, thông báo chỉ cho biết kiểu ngoại lệ và nơi nó được ném, vì thông báo gốc có thể trích dẫn dữ liệu.

## Camunda 7

### Chọn engine nào

Camunda 7 Community Edition đã hết vòng đời vào tháng 10 năm 2025; 7.24 là bản phát hành cuối cùng. Nhiều ngân hàng vẫn chạy phiên bản này, nhúng trong ứng dụng của họ, và Camunda 7 Enterprise Edition có cùng Java API. Mô-đun được biên dịch và kiểm thử với `org.camunda.bpm:camunda-engine` 7.24.0, một artifact có giấy phép Apache-2.0, với scope `provided`.

[Operaton](https://operaton.org) là một nhánh (fork) của Camunda 7 theo giấy phép Apache-2.0. API của nó giống hệt nhưng nằm dưới package `org.operaton.bpm` thay vì `org.camunda.bpm`, nên mô-đun này không nạp được trong engine Operaton. Việc chuyển đổi chỉ là đổi tên import một cách máy móc; một mô-đun riêng đang được dự kiến nếu có nhu cầu.

```xml
<dependency>
    <groupId>vn.io.dxa.rulestone</groupId>
    <artifactId>rulestone-camunda7</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

### Thiết lập

Tạo một `RuleStoneDecisionService` từ engine và các bảng, rồi thêm plugin vào cấu hình process engine:

```java
RuleStoneEngine rules = RuleStone.builder().clock(clock).decisions(tables).build();
RuleStoneDecisionService decisions = new RuleStoneDecisionService(rules, tables, clock);

configuration.getProcessEnginePlugins().add(new RuleStoneProcessEnginePlugin(decisions));
return configuration.buildProcessEngine();
```

Plugin đăng ký service dưới tên của process engine, nơi `RuleStoneDelegate` tìm thấy nó, và thêm nó vào các expression bean của engine với tên `rulestone`.

**Với Spring** (kể cả Camunda Spring Boot starter), hãy khai báo `RuleStoneProcessEnginePlugin` là một bean; starter áp dụng mọi bean `ProcessEnginePlugin`. Biểu thức Spring phân giải các bean của Spring chứ không phải bean riêng của engine, nên hãy khai báo thêm `RuleStoneDecisionService` là một bean tên `rulestone`. Giữ `camunda:class` cho delegate. Nếu bạn thích dùng `camunda:delegateExpression`, hãy tạo delegate bằng `new RuleStoneDelegate(decisions)` dưới dạng bean **prototype**: Camunda inject các field vào bean, điều này không an toàn đa luồng với một singleton.

### Delegate

Cấu hình delegate bằng các field, mỗi field là một chuỗi hoặc một biểu thức:

```xml
<bpmn:serviceTask id="CheckEligibility" name="Check eligibility"
                  camunda:class="vn.io.dxa.rulestone.camunda7.RuleStoneDelegate">
  <bpmn:extensionElements>
    <camunda:field name="decisionId" stringValue="loan_eligibility" />
    <camunda:field name="asOfVariable" stringValue="applicationDate" />
    <camunda:field name="resultVariable" stringValue="decision" />
    <camunda:field name="errorCode" stringValue="RULESTONE_DECISION_FAILED" />
  </bpmn:extensionElements>
</bpmn:serviceTask>
<bpmn:boundaryEvent id="DecisionFailed" attachedToRef="CheckEligibility">
  <bpmn:errorEventDefinition errorRef="Error_DecisionFailed" camunda:errorMessageVariable="decisionError" />
</bpmn:boundaryEvent>
```

Các field giống các header của Camunda 8: `decisionId` (bắt buộc), `version`, `asOfVariable`, `modelsVariable`, `resultVariable` (mặc định `decision`), `failOnNoMatch` (mặc định `false`) và `errorCode` (mặc định `RULESTONE_DECISION_FAILED`). Như ở Camunda 8, delegate hiện chỉ đánh giá bảng quyết định, và `modelsVariable` dành cho các nút mô hình của luồng. Sau đó các gateway đọc kết quả bằng JUEL:

```xml
<bpmn:sequenceFlow id="Flow_Approve" sourceRef="Outcome" targetRef="Disburse">
  <bpmn:conditionExpression xsi:type="bpmn:tFormalExpression">${decision.output.decision == 'APPROVE'}</bpmn:conditionExpression>
</bpmn:sequenceFlow>
```

### Biểu thức

Để quyết định trực tiếp trong điều kiện của gateway hoặc trong input mapping, hãy gọi bean `rulestone`. `evaluate(decisionId, execution)` dùng ngày hôm nay theo đồng hồ của service; `evaluateAsOf(decisionId, asOfVariable, execution)` dùng ngày trong một biến:

```xml
<bpmn:conditionExpression xsi:type="bpmn:tFormalExpression">${rulestone.evaluateAsOf('loan_eligibility', 'applicationDate', execution).output.decision == 'APPROVE'}</bpmn:conditionExpression>
```

Cả hai đều trả về map kết quả mà không lưu lại. Lỗi được ném ra như từ delegate, nhưng gateway không có boundary event để bắt BPMN error, nên hãy ưu tiên dùng delegate khi quyết định có thể thất bại do dữ liệu sai.

### Biến và kết quả

Giống Camunda 8, chỉ các đầu vào đã khai báo được đọc. Một biến có thể chứa `Boolean`, `String`, `Integer` hoặc `Long`, `BigDecimal`, `Money`, `LocalDate`, enum, hoặc một `Map` hay `Collection` của các kiểu này; văn bản theo các dạng JSON ở trên (`"25750000.50"`, `"1500000.50 VND"`, `"2026-11-01"`); hoặc một giá trị JSON, như một biến Spin `JSON`, được đọc từ văn bản đã tuần tự hoá của nó. **`Double` và `Float` bị từ chối** bằng một BPMN error nêu tên biến, ví dụ `applicant.monthlyIncome: floating-point numbers are not supported; use BigDecimal`. Biến ngày nghiệp vụ chứa một `LocalDate` hoặc một chuỗi `"yyyy-MM-dd"`.

Kết quả là một `Map` với các đầu ra có kiểu `Boolean`, `Long`, `String`, `BigDecimal`, `LocalDate` hoặc danh sách của các kiểu này, nên JUEL so sánh số thập phân một cách chính xác: `${decision.output.limit >= 100000000}`. Tiền được chuyển thành dạng văn bản, `"30000.0100 VND"`. Delegate lưu map dưới dạng **đối tượng tuần tự hoá Java**, bất kể định dạng tuần tự hoá mặc định của engine: định dạng JSON của Spin sẽ đọc lại số thập phân thành `Double`. Map chỉ chứa các kiểu của JDK, nên mọi ứng dụng đều đọc được.

### Lỗi và incident

| Tình huống | Hành vi |
|---|---|
| Dữ liệu sai, một luật bị lỗi, hoặc không có luật nào khớp khi bật `failOnNoMatch` | `BpmnError` với `errorCode` của task, để một boundary event bắt. `camunda:errorMessageVariable` nhận thông báo lỗi. |
| Quyết định hoặc phiên bản không xác định, không có phiên bản nào có hiệu lực vào ngày đó, một field không hợp lệ, hoặc chưa đăng ký service | `RuleStoneConfigurationException`, một `ProcessEngineException`. |
| Mọi trường hợp khác | Ngoại lệ được lan truyền tiếp. |

Một lỗi cấu hình trong task đồng bộ làm thất bại lời gọi API đã chạy nó, như `startProcessInstanceByKey`, và rollback transaction. Trong task bất đồng bộ, job thất bại và được thử lại theo retry cycle của nó; hãy đặt cycle chỉ một lần thử để incident xuất hiện ngay:

```xml
<bpmn:serviceTask id="DecideAsync" camunda:asyncBefore="true"
                  camunda:class="vn.io.dxa.rulestone.camunda7.RuleStoneDelegate">
  <bpmn:extensionElements>
    <camunda:failedJobRetryTimeCycle>R1/PT0S</camunda:failedJobRetryTimeCycle>
```

## Độ chính xác số thập phân

Camunda 8 lưu biến quy trình dưới dạng MessagePack. Một số JSON có phần thập phân sẽ trở thành số dấu phẩy động nhị phân 64 bit ở đó, vốn chỉ giữ chính xác một số thập phân tới 15 chữ số có nghĩa. Vì vậy RuleStone:

- ghi số thập phân, tiền và ngày dưới dạng **chuỗi** trong kết quả, để chúng được giữ nguyên chính xác khi đọc lại;
- đọc đầu vào thập phân từ chuỗi một cách chính xác, và từ số JSON theo văn bản của chúng;
- từ chối bằng một BPMN error mọi số JSON có phần thập phân với hơn 15 chữ số có nghĩa, vì Zeebe có thể đã làm thay đổi nó. Hãy gửi các giá trị như vậy, và tốt nhất là mọi số tiền, dưới dạng chuỗi.

Số nguyên được Zeebe lưu chính xác, dưới dạng số nguyên 64 bit.

Camunda 7 giữ các đối tượng Java, nên các biến `BigDecimal` và `Money` là chính xác; chỉ `Double` và `Float` bị từ chối. Kết quả được tuần tự hoá Java cũng vì lý do đó.

## RuleStone hay Camunda DMN?

Cả hai phiên bản Camunda đều có sẵn một DMN engine cho business rule task. Hãy giữ DMN cho các bảng đơn giản do người mô hình hoá quy trình quản lý, khi bạn muốn có decision requirement diagram hoặc cần chính chuẩn DMN. Hãy chọn RuleStone khi:

| Nhu cầu | Camunda DMN | RuleStone |
|---|---|---|
| Tiền chính xác | Số FEEL là số thập phân, nhưng Camunda 8 lưu biến có phần thập phân dưới dạng dấu phẩy động, và Camunda 7 đưa biến `Double` vào bảng | Kiểu `decimal` và `money`; số dấu phẩy động bị từ chối; kết quả luôn chính xác |
| Hiệu lực theo ngày | Quyết định được chọn theo lần triển khai, phiên bản mới nhất hoặc version tag, không theo ngày nghiệp vụ | Mỗi phiên bản có `effectiveFrom`/`effectiveTo`; phiên bản có hiệu lực vào ngày as-of được chọn, và các phiên bản chồng lấn bị từ chối |
| Chứng minh luật nào đã quyết định | Lần triển khai và phiên bản của quyết định | `ruleVersion` nêu chính xác nội dung luật: `3@sha256:…`, ổn định qua các lần triển khai lại |
| Phát hiện lỗi sớm | FEEL chủ yếu được kiểm tra lúc đánh giá | Biểu thức CEL được kiểm tra kiểu khi tạo engine, và mọi lỗi được báo cáo cùng lúc |
| Dùng lại bên ngoài quy trình | Quyết định chạy bên trong Camunda | Cùng các bảng đó chạy nhúng trong các dịch vụ, trong workflow Temporal hoặc trong kiểm thử với `rulestone-test` |

Hai công cụ có thể làm việc song song: một quy trình có thể dùng bảng DMN để định tuyến và dùng quyết định RuleStone cho chính sách tín dụng.
