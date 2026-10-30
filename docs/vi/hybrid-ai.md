---
source: hybrid-ai.md
sourceHash: 8c4786615bb76daa3806809f03150a021338c5219dcb528112f439c6935f6d46
---

# Ra quyết định AI lai

> Trạng thái: **đề xuất thiết kế**, đã triển khai một phần · Cập nhật lần cuối: 2026-10-30
>
> Bước 1 tới 3 của [kế hoạch triển khai](#9-kế-hoạch-triển-khai) **đã có**: mọi lần đánh giá đều tạo ra một bản ghi quyết định (xem [Bản ghi quyết định và kiểm toán](java-api.md#bản-ghi-quyết-định-và-kiểm-toán)), luồng quyết định chạy được trong bộ máy luật Java (xem [Luồng quyết định](flows.md)), và luồng dùng được mô hình qua nút mô hình ở chế độ `invoke` và `supplied` (xem [Nút mô hình](flows.md#nút-mô-hình)). Mọi phần còn lại trong trang này là đề xuất để rà soát và sẽ thay đổi trước khi phát hành.

Trang này đặc tả cách RuleStone kết hợp mô hình học máy (machine learning) với luật, và cách mọi quyết định tự giải thích. Trang đề cập ba nội dung:

1. **Đầu ra của mô hình làm đầu vào cho luật.** Mô hình chấm điểm trước; luật quyết định dựa trên điểm số.
2. **Mô hình là các nút trong một luồng quyết định.** Một luồng nối tiếp các luật chính sách, phân khúc, một hoặc nhiều mô hình, ma trận quyết định và các nhánh champion/challenger, theo phong cách của các công cụ chiến lược tín dụng như Strategy One.
3. **Một bản ghi quyết định cho mọi quyết định.** Bản ghi luôn được tạo ra, bất kể tổ hợp luật và mô hình thế nào, và giải thích điều gì đã được quyết định và vì sao.

## Mục lục

1. [Nguyên tắc](#1-nguyên-tắc)
2. [Mẫu A: đầu ra của mô hình làm đầu vào cho luật](#2-mẫu-a-đầu-ra-của-mô-hình-làm-đầu-vào-cho-luật)
3. [Mẫu B: mô hình là nút trong luồng quyết định](#3-mẫu-b-mô-hình-là-nút-trong-luồng-quyết-định)
4. [Bản ghi quyết định](#4-bản-ghi-quyết-định)
5. [Phác thảo Java API](#5-phác-thảo-java-api)
6. [Kiểm thử luồng có dùng mô hình](#6-kiểm-thử-luồng-có-dùng-mô-hình)
7. [Adapter mô hình và giấy phép](#7-adapter-mô-hình-và-giấy-phép)
8. [Đối chiếu với yêu cầu pháp lý](#8-đối-chiếu-với-yêu-cầu-pháp-lý)
9. [Kế hoạch triển khai](#9-kế-hoạch-triển-khai)
10. [Câu hỏi còn để ngỏ](#10-câu-hỏi-còn-để-ngỏ)

## 1. Nguyên tắc

Các nguyên tắc này áp dụng cho mọi mẫu trong trang.

1. **Luật quyết định, mô hình cung cấp thông tin.** Mô hình chỉ có thể tạo ra các giá trị có kiểu để các nút phía sau đọc. Đầu ra cuối cùng luôn đến từ các nút tất định. Mô hình không bao giờ ghi đè được một luật chính sách đứng trước nó, như một kết quả khớp danh sách cấm vận hay một người vay chưa đủ tuổi.
2. **Mô hình đi vào qua một hợp đồng có kiểu.** Một nút mô hình khai báo các đặc trưng (feature) nó gửi đi, các đầu ra nó trả về cùng kiểu của chúng, và khoảng giá trị mà các đầu ra đó phải nằm trong. Mọi thứ nằm ngoài hợp đồng đều được coi là lỗi của mô hình.
3. **Mọi mô hình đều được ghim phiên bản.** Một nút mô hình chỉ định chính xác một phiên bản mô hình. Phiên bản mô hình là một phần của mã băm nội dung của luồng, nên thay đổi mô hình nghĩa là phát hành một phiên bản luồng mới. Đây cũng là quy tắc áp dụng cho bảng quyết định: mỗi phiên bản đều được khắc vào đá.
4. **Mọi lỗi đều có một đường đi được khai báo.** Hết thời gian chờ, lỗi, đầu ra ngoài khoảng cho phép và độ tin cậy thấp đều dẫn tới một phương án dự phòng mà luồng nêu rõ. Một quyết định không bao giờ thất bại âm thầm và không bao giờ dùng một giá trị đoán.
5. **Mọi quyết định đều tạo ra một bản ghi quyết định.** Bản ghi được tạo cho mỗi lần đánh giá, thành công hay không. Không có công tắc nào trong engine để tắt nó.
6. **Quyết định có thể được replay.** Bản ghi lưu những gì mỗi mô hình đã trả về, nên quyết định có thể được chạy lại chính xác từ bản ghi, kể cả sau khi mô hình đã được huấn luyện lại hoặc ngừng sử dụng.
7. **Dữ liệu được tối thiểu hoá.** Mô hình chỉ nhận các đặc trưng đã khai báo. Mặc định, bản ghi quyết định không chứa giá trị đầu vào nào, chỉ có định danh, phiên bản, đường đi, mã lý do và đầu ra của mô hình. Điều này khớp với quy tắc hiện có là vết thực thi và thông báo lỗi không bao giờ chứa giá trị đầu vào.

## 2. Mẫu A: đầu ra của mô hình làm đầu vào cho luật

Mô hình chạy bên ngoài RuleStone, ví dụ trong một dịch vụ chấm điểm, một pipeline của feature store hay một nền tảng chống gian lận. Đầu ra của nó được truyền vào dưới dạng fact và luật quyết định dựa trên đó. Đây là mẫu đơn giản nhất, và thường phù hợp với kiến trúc sẵn có của ngân hàng, nơi việc chấm điểm đã diễn ra trước khi áp dụng luật.

### 2.1 Đầu vào thông thường: dùng được ngay hôm nay

Khai báo điểm số như một đầu vào có kiểu thông thường:

```json
{
  "id": "card_limit",
  "version": "1",
  "hitPolicy": "FIRST",
  "inputs":  { "pd": "decimal", "income": "money" },
  "outputs": { "decision": "string", "limit": "money" },
  "rules": [
    { "id": "HIGH_RISK", "when": "pd >= decimal('0.08')",
      "then": { "decision": "'DECLINE'", "limit": "money(0, income.currency())" }, "reasonCode": "PD_HIGH" },
    { "id": "STANDARD", "when": "true",
      "then": { "decision": "'APPROVE'", "limit": "(income * 3).round()" } }
  ]
}
```

Cách này chạy được với engine hiện nay, nhưng RuleStone không biết con số đến từ đâu. Bản ghi quyết định sẽ hiển thị `pd` như một đầu vào thông thường, không có id hay phiên bản mô hình. Hãy dùng cách này cho bản mẫu, hoặc khi nền tảng chấm điểm tự lưu dấu vết kiểm toán và liên kết với quyết định qua một correlation id.

### 2.2 Đầu vào có chứng thực: khuyến nghị

Khai báo một **nút mô hình ở chế độ `supplied`**. Bên gọi truyền kết quả mô hình cùng với nguồn gốc của nó. RuleStone không gọi mô hình, nhưng kiểm tra kết quả theo hợp đồng của nút đúng như thể chính nó đã gọi:

```json
"pd": {
  "type": "model",
  "mode": "supplied",
  "model": { "id": "retail_pd_application", "version": "3.1.0" },
  "outputs": { "pd": "decimal" },
  "valid": "pd >= decimal(0) && pd <= decimal(1)",
  "onFailure": { "to": "refer", "reasonCode": "SCORE_INVALID" },
  "next": "limit"
}
```

Bên gọi cung cấp kết quả mô hình dưới id của nút:

```json
{
  "facts": { "income": "30000000 VND" },
  "models": {
    "pd": {
      "modelId": "retail_pd_application",
      "modelVersion": "3.1.0",
      "outputs": { "pd": "0.0310" },
      "attributions": [
        { "feature": "utilisation", "contribution": "0.0120" },
        { "feature": "monthsOnBook", "contribution": "-0.0045" }
      ]
    }
  }
}
```

Engine kiểm tra rằng:

- id và phiên bản mô hình đúng là những gì luồng đã ghim;
- các đầu ra khớp với kiểu đã khai báo, được đọc bằng `FactsJson`, nên số thập phân là chính xác và không bao giờ là `double`;
- biểu thức `valid` đúng.

Nếu có bước kiểm tra nào thất bại, engine đi theo `onFailure`. Khi đó bản ghi quyết định hiển thị id và phiên bản mô hình, chế độ `supplied`, các đầu ra và các attribution (mức đóng góp của đặc trưng). Bản ghi này giống bản ghi mà Mẫu B tạo ra, nên các phân tích về sau có thể xử lý cả hai như nhau.

Mẫu A chính là Mẫu B, chỉ khác là bên gọi thực hiện việc gọi mô hình. Mọi nội dung dưới đây về hợp đồng, lỗi và bản ghi đều áp dụng cho cả hai.

## 3. Mẫu B: mô hình là nút trong luồng quyết định

### 3.1 Từ khái niệm thiết kế chiến lược đến các nút RuleStone

Các công cụ chiến lược tín dụng như Strategy One mô hình hoá một quyết định thành **cây chiến lược**: phân khúc tập khách hàng, chấm điểm mỗi phân khúc bằng mô hình phù hợp, ánh xạ điểm thành hạng, và tra hành động và hạn mức trong các ma trận. Một số nhánh được chuyển sang chiến lược challenger để thử nghiệm. RuleStone bổ sung **luồng quyết định** (decision flow): một đồ thị có hướng không chu trình, có phiên bản và có kiểu, gồm các nút diễn đạt cùng những ý tưởng đó.

| Khái niệm thiết kế chiến lược | Nút RuleStone | Ghi chú |
|---|---|---|
| Luật chính sách và loại trừ (knock-out) | `table` | Bất kỳ bảng quyết định nào hiện có. Chạy trước mô hình, nên luật cứng luôn được áp dụng. |
| Phân khúc và phân nhánh cây | `switch` | `when` khớp đầu tiên được chọn. Bắt buộc có một nhánh cuối không có `when`. |
| Đặc tính dẫn xuất | `derive` | Các giá trị CEL có tên được tính một lần, như tỷ lệ nợ trên thu nhập hay số tháng là khách hàng. |
| Scorecard hoặc mô hình ML | `model` | Chế độ `invoke` gọi mô hình; chế độ `supplied` nhận kết quả từ bên gọi (Mẫu A). |
| Ma trận quyết định (hạng × khoảng thu nhập) | `table` | Một bảng có điều kiện kiểm tra cả hai chiều. |
| Champion/challenger | `split` | Chia theo tỷ lệ phần trăm một cách tất định dựa trên một khoá ổn định, được ghi vào bản ghi quyết định. |
| Chiến lược con dùng lại được | `flow` | Gọi một phiên bản luồng khác như một nút. |
| Gán đầu ra cuối cùng | `end` | Đặt các đầu ra đã khai báo của luồng. Một luồng có thể có nhiều nút `end`. |

### 3.2 Ví dụ: phát hành thẻ tín dụng

```json
{
  "id": "card_origination",
  "version": "1",
  "inputs": {
    "applicant": { "id": "string", "age": "int", "income": "money", "existingCustomer": "bool" },
    "bureau": { "dpd30Count12m": "int", "utilisation": "decimal" }
  },
  "outputs": { "decision": "string", "limit": "money", "riskGrade": "string" },
  "start": "policy",
  "nodes": {
    "policy": {
      "type": "table", "decision": "card_policy",
      "next": [ { "when": "policy.decision == 'REJECT'", "to": "rejected" }, { "to": "segment" } ]
    },
    "segment": {
      "type": "switch",
      "cases": [ { "when": "applicant.existingCustomer", "to": "pd_behaviour" }, { "to": "pd_application" } ]
    },
    "pd_behaviour": {
      "type": "model", "mode": "invoke", "as": "score",
      "model": { "id": "retail_pd_behaviour", "version": "2.4.0" },
      "features": { "utilisation": "bureau.utilisation", "dpd30Count12m": "bureau.dpd30Count12m" },
      "outputs": { "pd": "decimal" },
      "valid": "pd >= decimal(0) && pd <= decimal(1)",
      "timeoutMs": 80,
      "reasonCodes": { "utilisation": "HIGH_UTILISATION", "dpd30Count12m": "RECENT_DELINQUENCY" },
      "onFailure": { "to": "referred", "reasonCode": "MODEL_UNAVAILABLE" },
      "next": "grade"
    },
    "pd_application": {
      "type": "model", "mode": "invoke", "as": "score",
      "model": { "id": "retail_pd_application", "version": "3.1.0" },
      "features": { "age": "applicant.age", "utilisation": "bureau.utilisation" },
      "outputs": { "pd": "decimal" },
      "valid": "pd >= decimal(0) && pd <= decimal(1)",
      "timeoutMs": 80,
      "reasonCodes": { "utilisation": "HIGH_UTILISATION" },
      "onFailure": { "to": "referred", "reasonCode": "MODEL_UNAVAILABLE" },
      "next": "grade"
    },
    "grade": {
      "type": "table", "decision": "pd_to_grade",
      "facts": { "pd": "score.pd" },
      "next": "limit_strategy"
    },
    "limit_strategy": {
      "type": "split", "key": "applicant.id", "salt": "card-limit-2026Q4",
      "buckets": [
        { "name": "champion", "percent": 90, "to": "limit_champion" },
        { "name": "challenger", "percent": 10, "to": "limit_challenger" }
      ]
    },
    "limit_champion":   { "type": "table", "decision": "card_limit_matrix",   "as": "offer", "next": "approved" },
    "limit_challenger": { "type": "table", "decision": "card_limit_matrix_b", "as": "offer", "next": "approved" },
    "approved": { "type": "end", "set": {
      "decision": "'APPROVE'", "riskGrade": "grade.riskGrade",
      "limit": "offer.limit" } },
    "rejected": { "type": "end", "set": {
      "decision": "'REJECT'", "riskGrade": "'N/A'", "limit": "money(0, applicant.income.currency())" } },
    "referred": { "type": "end", "set": {
      "decision": "'REFER'", "riskGrade": "'N/A'", "limit": "money(0, applicant.income.currency())" } }
  }
}
```

Trong luồng này:

- Các luật chính sách chạy trước. Một lần từ chối ở đó kết thúc luồng trước khi bất kỳ mô hình nào được gọi.
- Khách hàng hiện hữu được chấm điểm bằng mô hình hành vi, khách hàng mới bằng mô hình hồ sơ (application).
- Nếu một trong hai mô hình thất bại, hồ sơ được chuyển cho người xem xét. Nó không bao giờ được phê duyệt hay từ chối dựa trên phỏng đoán.
- 10% hồ sơ, được chọn một cách tất định theo id người vay, nhận ma trận hạn mức challenger. Nhóm (bucket) được ghi trong bản ghi quyết định.

### 3.3 Dữ liệu giữa các nút

- **Tên.** Đầu ra của mỗi nút có thể được các nút phía sau truy cập dưới id của nút, ví dụ `pd_behaviour.pd` hoặc `grade.riskGrade`. Đầu vào của luồng giữ nguyên tên. Các nút `switch` và `split` cung cấp `taken`, chứa nhánh hoặc nhóm đã được chọn.
- **Fact cho một bảng.** Mặc định, một nút `table` nhận các giá trị của luồng có tên trùng với đầu vào đã khai báo của bảng. `facts` ánh xạ tường minh khi tên khác nhau, như nút `grade` đã làm.
- **Tên dùng chung.** Các nút trên các nhánh thay thế nhau có thể công bố đầu ra dưới một tên chung bằng `as`, với điều kiện chúng khai báo các đầu ra giống hệt nhau. Ở trên, cả hai mô hình đều công bố dưới tên `score`, nên `grade` đọc `score.pd` dù mô hình nào đã chạy, và hai bảng hạn mức công bố dưới tên `offer`.
- **Kiểm tra kiểu khi nạp.** Mọi tên đều được gán kiểu khi nạp luồng. Một nút chỉ được đọc một tên nếu **mọi** đường đi tới nó đều qua một nút công bố tên đó. Đọc một giá trị có thể không tồn tại là lỗi lúc nạp, không phải bất ngờ lúc chạy. Bản ghi vẫn cho biết nút nào đã tạo ra giá trị.
- **Thứ tự.** Việc đánh giá diễn ra tuần tự và đi theo đúng một đường qua đồ thị, nên cùng đầu vào và cùng kết quả mô hình luôn cho cùng một đường đi.

### 3.4 Hợp đồng của nút mô hình

| Trường | Ý nghĩa |
|---|---|
| `model.id`, `model.version` | Chính xác mô hình được dùng. Cả hai đều nằm trong mã băm nội dung của luồng. |
| `mode` | `invoke`: engine gọi mô hình qua một `ModelInvoker`. `supplied`: bên gọi truyền kết quả vào (Mẫu A). |
| `features` | Tên đặc trưng ánh xạ tới biểu thức CEL. Chỉ những giá trị này được gửi tới mô hình; đây là ranh giới tối thiểu hoá dữ liệu. |
| `outputs` | Tên đầu ra ánh xạ tới kiểu. Dùng lại các kiểu của bảng: `decimal`, `int`, `string`, `bool`, `money`, `date`. |
| `valid` | Điều kiện CEL tuỳ chọn trên các đầu ra. Nếu sai, đầu ra được coi là lỗi. |
| `minConfidence` | Tuỳ chọn. Nếu mô hình báo độ tin cậy thấp hơn giá trị này, đầu ra được coi là lỗi với lý do `LOW_CONFIDENCE`. |
| `timeoutMs` | Giới hạn cứng cho `invoke`. Hết thời gian chờ được coi là lỗi; không có thử lại trên đường xử lý quyết định. |
| `reasonCodes` | Tên đặc trưng ánh xạ tới mã lý do. Áp dụng cho các attribution của mô hình; xem [4.4](#44-mã-lý-do-và-giải-thích-dễ-hiểu). |
| `onFailure` | Đi đâu khi mô hình thất bại, và với mã lý do nào. Có thể dẫn tới một bảng scorecard dự phòng, một nút kết thúc chuyển xem xét thủ công, hoặc một giá trị mặc định thận trọng. Bắt buộc phải có. |
| `shadow` | Danh sách tuỳ chọn các phiên bản mô hình khác chỉ được gọi để so sánh. Đầu ra của chúng được ghi lại nhưng không bao giờ được luồng đọc. |

Các loại lỗi được ghi lại là `TIMEOUT`, `ERROR`, `CONTRACT_VIOLATION` (sai id, phiên bản hoặc kiểu), `INVALID_OUTPUT` (điều kiện `valid` sai) và `LOW_CONFIDENCE`.

### 3.5 Champion/challenger và mô hình chạy bóng

- **`split`** gán nhóm theo `SHA-256(salt + key)`, nên cùng một khách hàng luôn rơi vào cùng một nhóm với cùng một salt. Đổi salt hoặc tỷ lệ phần trăm sẽ tạo ra một phiên bản luồng mới. Tổng tỷ lệ phần trăm của các nhóm phải bằng 100.
- **Mô hình chạy bóng** (shadow model) cho phép một phiên bản mô hình mới chạy trên lưu lượng thật mà không ảnh hưởng tới quyết định. Engine gọi mô hình chạy bóng sau mô hình chính, trong cùng ngân sách thời gian chờ. Lỗi của mô hình chạy bóng không bao giờ ảnh hưởng tới quyết định. Đầu ra của mô hình chạy bóng chỉ được ghi vào bản ghi.
- Cả hai đều cung cấp dữ liệu cho mô-đun `rulestone-simulation` đang dự kiến, mô-đun này so sánh kết quả theo từng nhóm và từng phiên bản mô hình.

### 3.6 Nút LLM

Một LLM là một nút mô hình với `"kind": "llm"`, và áp dụng các quy tắc chặt chẽ hơn:

- Đầu ra phải là giá trị có kiểu: một kiểu liệt kê như `string` với điều kiện `valid` như `category in ['A', 'B', 'C']`, một `bool`, hoặc một số. Văn bản tự do không bao giờ được nút phía sau đọc.
- Mẫu prompt là một phần của định nghĩa nút, nên nằm trong mã băm nội dung. Nhà cung cấp phải được cấu hình với các thiết lập tất định khi họ hỗ trợ.
- Một nút LLM luôn phải có `onFailure`, và không được là thứ duy nhất phân định giữa phê duyệt và từ chối. Điều này được kiểm tra khi nạp: mọi đường đi từ một nút LLM tới một nút kết thúc kiểu `APPROVE` đều phải qua một nút `table`. Những giá trị kết thúc nào được coi là phê duyệt có thể cấu hình được.

### 3.7 Kiểm tra khi nạp

Một luồng bị từ chối, với mọi lỗi được liệt kê cùng lúc như với bảng quyết định hiện nay, khi:

- đồ thị có chu trình, có nút không tới được, hoặc có đường đi không tới được nút `end`;
- một tên không xác định, sai kiểu, hoặc có thể chưa được đặt trên một đường đi nào đó (quy tắc ở trên);
- các nút dùng chung một tên `as` khai báo đầu ra khác nhau, hoặc có thể cùng chạy trên một đường đi;
- một nút `table` tham chiếu tới một quyết định chưa được đăng ký, hoặc không thể đáp ứng các đầu vào đã khai báo của nó;
- một nút `end` không đặt mọi đầu ra đã khai báo;
- một nút `model` không có `onFailure`, có phiên bản chưa được ghim, hoặc dùng một biểu thức đặc trưng không qua được kiểm tra kiểu;
- tổng tỷ lệ phần trăm của `split` không bằng 100;
- một nút LLM vi phạm quy tắc trong [3.6](#36-nút-llm).

## 4. Bản ghi quyết định

### 4.1 Luôn được tạo ra

Mỗi lần gọi `evaluate` đều tạo một `DecisionRecord`, dù chạy một bảng đơn lẻ hay một luồng, và dù thành công hay thất bại. Bản ghi được trả về qua `DecisionResult.record()` và được chuyển cho mọi `AuditSink` đã cấu hình. *Đã có cho bảng quyết định và luồng quyết định.*

- **Dùng nhúng:** bên gọi quyết định bản ghi được gửi tới đâu. `rulestone-test` có thể assert trên bản ghi.
- **`rulestone-server`, Spring Boot starter, Temporal và Camunda:** việc khởi động thất bại nếu chưa cấu hình audit sink nào, trừ khi được tắt tường minh bằng một thuộc tính có tài liệu hướng dẫn, chỉ dành cho phát triển cục bộ.
- **Lỗi:** khi việc đánh giá thất bại, bản ghi vẫn được ghi, với trạng thái `ERROR` và nút gây lỗi, trước khi ngoại lệ được ném ra.

### 4.2 Nội dung

```json
{
  "recordId": "01JB6Z9V4X3N7Q8R2T5W0YH1KM",
  "correlationId": "loan-app-2026-000123",
  "decision": "card_origination",
  "version": "1@sha256:9f2c…",
  "businessDate": "2026-11-01",
  "evaluatedAt": "2026-11-01T09:15:02.481Z",
  "engine": "rulestone 0.2.0",
  "status": "OK",
  "factsHash": "sha256:41d8…",
  "output": { "decision": "APPROVE", "limit": "60000000 VND", "riskGrade": "B" },
  "reasonCodes": [
    { "code": "HIGH_UTILISATION", "source": "pd_behaviour" }
  ],
  "path": [
    { "node": "policy", "type": "table", "version": "4@sha256:0b7e…", "matchedRules": ["PASS"] },
    { "node": "segment", "type": "switch", "taken": "pd_behaviour" },
    { "node": "pd_behaviour", "type": "model", "mode": "invoke",
      "model": { "id": "retail_pd_behaviour", "version": "2.4.0" },
      "status": "OK", "latencyMs": 23,
      "outputs": { "pd": "0.0310" },
      "attributions": [
        { "feature": "utilisation", "rank": 1, "direction": "INCREASES_RISK", "reasonCode": "HIGH_UTILISATION" },
        { "feature": "dpd30Count12m", "rank": 2, "direction": "DECREASES_RISK" }
      ] },
    { "node": "grade", "type": "table", "version": "2@sha256:77aa…", "matchedRules": ["B"] },
    { "node": "limit_strategy", "type": "split", "taken": "champion" },
    { "node": "limit_champion", "type": "table", "version": "5@sha256:c1d0…", "matchedRules": ["B_MID_INCOME"] },
    { "node": "approved", "type": "end" }
  ]
}
```

| Trường | Mục đích |
|---|---|
| `version` và `version` của từng nút | Chính xác nội dung của mọi bảng và luồng đã dùng, để quyết định có thể chạy lại với cùng các luật. |
| `factsHash` | SHA-256 của dạng `FactsJson` chuẩn tắc của các đầu vào. Giá trị này chứng minh những đầu vào nào đã được dùng, đối chiếu với bản sao riêng của ngân hàng, mà không cần lưu chúng. |
| `path` | Mọi nút đã đi qua, theo thứ tự, cùng với những gì nút đó quyết định: các luật khớp, nhánh hoặc nhóm được chọn, trạng thái của mô hình. |
| `outputs` của mô hình | Những gì mô hình đã trả về. Các giá trị này cho phép replay quyết định một cách chính xác, và cho thấy mức đóng góp của mô hình. |
| `attributions` | Những đặc trưng nào đã đẩy điểm số và theo hướng nào, chỉ theo tên và thứ hạng. Mặc định không ghi giá trị đóng góp thô và giá trị đặc trưng. |
| `reasonCodes` | Các lý do đã được sắp xếp và loại trùng, mỗi lý do kèm nút tạo ra nó. Đây là nguồn sự thật cho các thông báo từ chối (adverse action). |

### 4.3 Những gì không có trong bản ghi

Mặc định bản ghi **không chứa giá trị đầu vào và không chứa giá trị đặc trưng**. Có thể cấu hình thêm hai mức tuỳ chọn cho từng lần triển khai:

- `evidence: masked` bổ sung các giá trị đặc trưng gửi tới mỗi mô hình và các đầu vào mà mỗi bảng đã đọc, sau khi qua `DataMasker` (dự kiến), thành phần che số thẻ, số định danh cá nhân và các trường tương tự.
- `evidence: full` bổ sung chúng ở dạng không che. Mức này chỉ dành cho các sink mã hoá dữ liệu lưu trữ và giới hạn quyền truy cập, và máy chủ từ chối kết hợp nó với một sink ghi log thông thường.

Việc không đưa giá trị vào bản ghi mặc định nghĩa là bản ghi có thể được ghi vào log thông thường và chia sẻ an toàn với chuyên viên phân tích. Khi cần điều tra đầy đủ, bằng chứng có thể được ghép vào từ kho dữ liệu riêng của ngân hàng bằng `correlationId` và được kiểm chứng với `factsHash`.

### 4.4 Mã lý do và giải thích dễ hiểu

- **Từ luật:** `reasonCode` của mỗi luật khớp, như hiện nay.
- **Từ mô hình:** bảng ánh xạ `reasonCodes` của nút được áp dụng cho các attribution của mô hình. Engine lấy các attribution **làm tăng** rủi ro đứng đầu (mặc định ba, cấu hình được theo từng nút) và đưa ra các mã đã ánh xạ theo thứ hạng. Một mô hình không trả về attribution nào sẽ không tạo ra mã lý do từ mô hình, và bản ghi ghi rõ điều đó.
- **Từ lỗi:** mã lý do của `onFailure`, như `MODEL_UNAVAILABLE`.
- **Văn bản:** một danh mục mã lý do (`reason-codes.json`) chứa một thông điệp đã được rà soát cho mỗi mã, ở mỗi ngôn ngữ:

  ```json
  {
    "HIGH_UTILISATION": {
      "en": "Your credit utilisation is high compared with your limits.",
      "vi": "Mức sử dụng hạn mức tín dụng của bạn đang cao so với hạn mức được cấp."
    }
  }
  ```

  Lời giải thích cho khách hàng và nhân viên được tạo ra từ danh mục này một cách **tất định**. Về sau một LLM có thể diễn đạt lại để điều chỉnh giọng văn, chỉ dùng bản ghi đã che dữ liệu làm đầu vào, nhưng các mã vẫn là nguồn sự thật. Đây chính là mẫu "giải thích quyết định" đã có trong trang [kiến trúc](architecture.md#7-tích-hợp-ai-dự-kiến).

### 4.5 Replay và chấm điểm lại

- **Replay** chạy lại một quyết định từ bản ghi của nó. Engine nạp các phiên bản luồng và bảng đã ghi, còn bên gọi cung cấp các fact ban đầu, được kiểm chứng với `factsHash`. Mỗi nút mô hình chạy ở chế độ `supplied` với các đầu ra đã ghi. Kết quả phải khớp chính xác với bản ghi. Đây là cách một kiểm toán viên tái lập một quyết định nhiều năm sau, khi các mô hình đã thay đổi.
- **Chấm điểm lại** (re-score) chạy cùng các fact qua một phiên bản mô hình hoặc luồng khác và báo cáo các điểm khác biệt. Việc này dùng để thẩm định mô hình và kiểm thử ngược, và kết quả không bao giờ được ghi đè thành quyết định.

### 4.6 Bản ghi được gửi tới đâu

| Tích hợp | Hành vi |
|---|---|
| Nhúng | `RuleStone.builder().auditSink(…)`; các sink được gọi đồng bộ sau khi đánh giá, còn sink bất đồng bộ tự đệm dữ liệu. *Đã có.* |
| `rulestone-audit-jdbc` / `-kafka` (dự kiến) | Một kho chỉ cho phép ghi thêm (append-only). Các bản ghi được liên kết bằng chuỗi băm trong mô-đun quản trị (dự kiến), giúp mọi sự giả mạo đều lộ ra. |
| Temporal | Activity trả về id của bản ghi. Bản ghi được activity ghi, không bao giờ do mã workflow ghi, nên replay không tạo bản ghi trùng lặp. |
| Camunda | Id của bản ghi được lưu thành một biến quy trình để truy vết, và bản ghi được gửi tới sink đã cấu hình. |

## 5. Phác thảo Java API

Các SPI nằm trong `rulestone-api`, nên `rulestone-core` không phụ thuộc vào nhà cung cấp mô hình nào. Các adapter là những mô-đun riêng.

```java
/** Calls a model. Implementations must be thread-safe and must honour the deadline. */
public interface ModelInvoker {
    ModelResult invoke(ModelCall call) throws ModelException;
}

public record ModelCall(
        String modelId,
        String modelVersion,
        Map<String, Object> features,   // typed values, exact decimals, never double
        Instant deadline,
        String correlationId) {}

public record ModelResult(
        String modelId,
        String modelVersion,             // the version that actually answered
        Map<String, Object> outputs,
        Optional<BigDecimal> confidence,
        List<Attribution> attributions) {}

public record Attribution(String feature, BigDecimal contribution) {}
```

```java
RuleStoneEngine engine = RuleStone.builder()
        .decisions(tables)
        .flows(flows)
        .models(ModelRegistry.builder()
                .invoker("retail_pd_behaviour", onnxInvoker)
                .invoker("retail_pd_application", httpInvoker)
                .build())
        .auditSink(record -> auditLog.append(record))
        .build();

DecisionResult result = engine.evaluate(
        DecisionRef.of("card_origination").asOf(businessDate).correlationId(applicationId),
        facts);

result.output();          // {decision=APPROVE, limit=60000000 VND, riskGrade=B}
result.reasonCodes();     // [HIGH_UTILISATION]
result.record();          // the DecisionRecord shown in 4.2
```

Với Mẫu A, bên gọi truyền một `ModelResult` cùng với các fact: `Facts.of(...).withModelResult("pd", result)`. Bên gọi dùng JSON thì dùng đối tượng `models` như trong [2.2](#22-đầu-vào-có-chứng-thực-khuyến-nghị).

## 6. Kiểm thử luồng có dùng mô hình

Các ca kiểm thử `.stone-test.json` sẽ nhận một đối tượng `models` có cùng dạng như Mẫu A. Trong kiểm thử, mọi nút mô hình khi đó chạy ở chế độ `supplied`, nên các bài kiểm thử là tất định và không cần máy chủ mô hình:

```json
{
  "name": "existing customer with high utilisation is approved at grade B",
  "decision": "card_origination",
  "asOf": "2026-11-01",
  "facts": {
    "applicant": { "id": "C-1", "age": 35, "income": "30000000 VND", "existingCustomer": true },
    "bureau": { "dpd30Count12m": 0, "utilisation": "0.72" }
  },
  "models": { "pd_behaviour": { "modelId": "retail_pd_behaviour", "modelVersion": "2.4.0",
                                "outputs": { "pd": "0.0310" },
                                "attributions": [ { "feature": "utilisation", "contribution": "0.0120" } ] } },
  "expect": { "output": { "decision": "APPROVE", "riskGrade": "B" },
              "reasonCodes": ["HIGH_UTILISATION"],
              "path": ["policy", "segment", "pd_behaviour", "grade", "limit_strategy", "limit_champion", "approved"] }
}
```

Các bài kiểm thử cũng có thể mô phỏng lỗi, ví dụ `"pd_behaviour": { "failure": "TIMEOUT" }`. Độ bao phủ sẽ được mở rộng từ luật sang luồng: mọi nút, mọi nhánh của mỗi `switch`, và mọi đường `onFailure` đều phải được chạy qua.

## 7. Adapter mô hình và giấy phép

Adapter là các mô-đun tuỳ chọn, và mỗi adapter phải qua được danh sách giấy phép được phép của dự án (Apache-2.0, MIT, BSD).

| Adapter | Chạy | Thư viện và giấy phép (sẽ kiểm tra lại khi thêm mô-đun) |
|---|---|---|
| `rulestone-model-http` | Mọi mô hình phía sau HTTP, như KServe hay Seldon (Open Inference Protocol) hoặc một dịch vụ chấm điểm nội bộ | Chỉ `HttpClient` của JDK |
| `rulestone-model-onnx` | Mô hình xuất ra ONNX, từ scikit-learn, XGBoost, LightGBM hay PyTorch, chạy trong cùng tiến trình | ONNX Runtime (MIT) |
| `rulestone-model-scorecard` | Scorecard tính điểm định nghĩa bằng JSON, với mã lý do lấy từ số điểm bị mất | Không có; thuần RuleStone |

PMML được cố ý loại ra. Bộ đánh giá PMML phổ biến cho Java có giấy phép AGPL, giấy phép mà danh sách được phép loại trừ.

Các adapter chạy trong cùng tiến trình chỉ được chuyển số thập phân chính xác sang kiểu số của mô hình tại ranh giới adapter, và phải ghi rõ điều này trong tài liệu. Đầu ra của mô hình được chuyển ngược lại thành `decimal` với một scale đã khai báo.

## 8. Đối chiếu với yêu cầu pháp lý

Bảng này định hướng cho thiết kế; đây không phải tư vấn pháp lý. Phải kiểm tra lại văn bản hiện hành.

| Yêu cầu | Được đáp ứng ở đâu |
|---|---|
| Quản lý rủi ro mô hình (kiểu SR 11-7): danh mục, phiên bản, thẩm định, giám sát | `model.id` và `model.version` được ghim trong luồng có mã băm, mô hình chạy bóng, chấm điểm lại, mô phỏng |
| Lưu giữ hồ sơ cho AI rủi ro cao (EU AI Act; chấm điểm tín dụng là rủi ro cao) | Bản ghi quyết định luôn được tạo ra, với phiên bản mô hình, đầu ra và đường đi |
| Minh bạch và giải thích cho người chịu tác động | Mã lý do từ luật và từ attribution của mô hình, cùng danh mục văn bản đã rà soát bằng EN/VI |
| Sự giám sát của con người | `onFailure` chuyển sang xem xét thủ công, các giới hạn với LLM, và quy tắc nút chính sách đứng trước mô hình |
| Bảo vệ dữ liệu cá nhân (luật và nghị định về bảo vệ dữ liệu cá nhân của Việt Nam, GDPR khi liên quan) | Tối thiểu hoá dữ liệu qua đặc trưng đã khai báo, không có giá trị trong bản ghi mặc định, các mức bằng chứng có che dữ liệu |
| Tái lập phục vụ kiểm toán | Replay từ bản ghi với đầu ra mô hình `supplied` và kiểm chứng `factsHash` |

## 9. Kế hoạch triển khai

Mỗi bước là một thay đổi riêng, rà soát được, có kiểm thử và tài liệu riêng.

1. **Bản ghi quyết định cho bảng.** *Đã xong.* Thêm `DecisionRecord`, `DecisionResult.record()`, `AuditSink` và `correlationId` cho các bảng quyết định hiện nay, chưa có mô hình. Bước này thực hiện trước nghĩa vụ "luôn giải thích".
2. **Luồng chưa có mô hình.** *Đã xong.* Thêm định dạng luồng, các nút `table`, `switch`, `derive`, `split` và `end`, các bước kiểm tra khi nạp kể cả quy tắc chi phối (dominance), bộ thực thi, và hỗ trợ trong `rulestone-test` cho đường đi và độ bao phủ nút. Xem [Luồng quyết định](flows.md) và [Kiểm thử luồng quyết định](testing.md#kiểm-thử-luồng-quyết-định). Những điểm trang này còn để ngỏ đã được quyết định như sau:
   - **Nút `derive`** khai báo các giá trị của nó cùng kiểu trong `outputs` và đặt chúng bằng `set`, giống như bảng khai báo đầu ra và nút `end` đặt đầu ra, để có thể kiểm tra kiểu khi nạp luồng. Nút `derive` cũng được dùng `as`.
   - **Phiên bản bảng.** Mã băm nội dung của một luồng chỉ bao gồm tệp luồng. Một nút `table` dùng phiên bản của bảng có hiệu lực vào ngày nghiệp vụ, trừ khi nút ghim một phiên bản bằng `version`, và nút được kiểm tra với mọi phiên bản mà nó có thể dùng. Mỗi bước `table` trong bản ghi nêu tên bảng (`decision`) và đúng `version` của nó; các bước `switch` và `split` ghi `taken`.
   - **Nhóm của `split`** được lấy từ 64 bit đầu của SHA-256 của `"<salt>\n<key>"`, đọc như một phân số, giống cách định tuyến canary. Khoá là một `string`, và tỷ lệ phần trăm là số nguyên.
   - **Mã lý do** của một luồng được liệt kê mỗi mã một lần, cùng với nút đầu tiên đã tạo ra mã đó.
   - **Phiên bản canary và phiên bản bóng** áp dụng cho một luồng theo id của luồng; bên trong luồng, canary và phiên bản bóng của các bảng mà luồng dùng không được áp dụng.
   - **Đầu ra không luật nào đặt** thì vắng mặt, như với bảng: đọc một đầu ra như vậy làm việc đánh giá thất bại, nên luồng kiểm tra chúng bằng `has()`.
   - **Loại nút được dành riêng.** Nút `model` và `flow` bị từ chối vì chưa được hỗ trợ, nên việc bổ sung chúng về sau không thay đổi luồng hay mã băm hiện có nào. Chưa bước nào trong kế hoạch này lên lịch cho nút `flow` (luồng con).
   - Luồng dùng chung không gian `id` với bảng. Bộ máy luật trên trình duyệt chưa chạy luồng.
3. **Nút mô hình.** *Đã xong.* Thêm `ModelInvoker`, `ModelResult`, các chế độ `invoke` và `supplied`, xử lý lỗi, và mã lý do từ attribution. Thêm đối tượng `models` vào định dạng kiểm thử và cho các bên gọi JSON dựa trên `FactsJson` (Temporal, Camunda, trình soạn thảo). Xem [Nút mô hình](flows.md#nút-mô-hình) và [Luồng có nút mô hình](testing.md#luồng-có-nút-mô-hình). Những điểm trang này còn để ngỏ đã được quyết định như sau:
   - **Giao diện.** `ModelInvoker`, `ModelCall`, `ModelResult` (cùng `ModelResult.Attribution`), `ModelException`, `ModelStatus` và `SuppliedModel` nằm trong `rulestone-api`. Invoker được đăng ký theo id mô hình bằng `RuleStone.builder().modelInvoker(id, invoker)` thay cho một `ModelRegistry`. `ModelCall` cho biết thời gian được phép dưới dạng `Duration`, `timeout()`, thay cho một mốc `Instant`, vì engine đo thời gian bằng đồng hồ đơn điệu. `ModelResult` nêu tên `attributionMethod` của nó. `ModelException` là một `RuleStoneException`, như mọi ngoại lệ của RuleStone.
   - **Các trường của nút** là các trường ở [3.4](#34-hợp-đồng-của-nút-mô-hình), trừ `shadow` và `kind`, sẽ được thêm ở bước 7: tệp nào dùng chúng bị từ chối vì chưa được hỗ trợ. `features` và `timeoutMs` là bắt buộc ở chế độ `invoke` và không được dùng ở chế độ `supplied`. `onFailure` cần cả `to` và `reasonCode`. `maxReasonCodes`, từ 1 tới 10 và mặc định là 3, đặt số mã lý do tối đa mà một nút đưa ra. Một phiên bản được ghim là đúng một phiên bản: khoảng phiên bản, ký tự đại diện như `2.x`, và `latest` đều bị từ chối. Đặc trưng không có kiểu khai báo; mỗi biểu thức phải cho ra một kiểu nguyên thuỷ hoặc một danh sách của chúng. `valid` đọc các đầu ra theo tên, và không gì khác.
   - **Mã băm nội dung.** Dạng chuẩn tắc của một nút mô hình viết mọi trường đã đặt, với `minConfidence` là một chuỗi số thập phân. Các bảng và luồng hiện có có mã băm y như trước, và một bài kiểm thử kiểm tra điều đó.
   - **Lỗi.** Có sáu loại: năm loại ở 3.4 và `MISSING`, cho một nút `supplied` không có kết quả. Bên gọi có thể cung cấp `{ "failure": "TIMEOUT" }` hoặc `"ERROR"`; engine tự phát hiện các loại còn lại. Mọi lỗi đều đi theo `onFailure` và đưa ra mã lý do của nó, và lỗi của mô hình không bao giờ làm việc đánh giá thất bại; thời gian chờ tối đa của chính việc đánh giá thì vẫn làm. Ngoại lệ của invoker chỉ được ghi lại theo kiểu. Một kết quả được cung cấp cho một nút không nhận kết quả, như một nút không tồn tại, một nút `invoke` hay một bảng quyết định, sẽ làm việc đánh giá thất bại.
   - **Thời gian chờ tối đa.** Invoker chạy trên luồng thực thi (thread) đang đánh giá và nhận `timeoutMs` của nút, giới hạn bởi thời gian còn lại của việc đánh giá. Engine không ngắt invoker; một kết quả đến muộn được tính là `TIMEOUT`.
   - **Phạm vi.** Một nút mô hình lỗi không công bố gì, nên một nút đến qua `onFailure` không đọc được các đầu ra của mô hình. Đây là quy tắc sẵn có: một nút chỉ đọc được những giá trị mà mọi đường đi tới nó đều đặt.
   - **Mã lý do từ attribution.** Các attribution được xếp hạng theo độ lớn tuyệt đối của mức đóng góp. Mức đóng góp dương làm tăng rủi ro; với một điểm số mà cao hơn nghĩa là ít rủi ro hơn, adapter đổi dấu các mức đóng góp. Nút đưa ra mã của các đặc trưng làm tăng rủi ro theo thứ hạng, bỏ qua đặc trưng không có mã và đưa ra mỗi mã một lần, tối đa `maxReasonCodes` mã.
   - **Bản ghi.** Một bước mô hình ghi lại id mô hình và phiên bản đã ghim, chế độ, trạng thái, một `detail` khi lỗi, `latencyMs` khi có lần gọi, và khi thành công thì các đầu ra, độ tin cậy, phương pháp attribution và mọi attribution kèm thứ hạng, chiều và mã lý do đã đưa ra. Một nút `invoke` của engine được tạo với `suppliedModelsOnly()` ghi chế độ `supplied`. Các đầu ra được ghi lại, như [4.2](#42-nội-dung) đã nêu, để quyết định có thể được replay; đặc trưng và giá trị mức đóng góp thì không.
   - **Kiểm thử.** Ca kiểm thử `.stone-test.json` cung cấp kết quả trong `models`, như ở [mục 6](#6-kiểm-thử-luồng-có-dùng-mô-hình). `RuleStone.builder().suppliedModelsOnly()` chạy mọi nút mô hình ở chế độ `supplied`, cho kiểm thử và cho replay. Độ bao phủ tính đường `onFailure` của mỗi nút mô hình là một nhánh, được viết `pd -> referred (onFailure)`.
   - **Tính tất định và replay.** Chế độ `supplied` là tất định. Với chế độ `invoke`, replay (bước 5) cần từ mỗi bước mô hình trạng thái, các đầu ra và độ tin cậy của nó, cùng thứ hạng và chiều của các attribution, vốn cho ra cùng các mã lý do. Replay sẽ dựng lại các kết quả từ bản ghi và chạy luồng với `suppliedModelsOnly()`.
   - **Bên gọi dùng JSON.** `FactsJson.readModels` đọc đối tượng `models`. Activity của Temporal (`DecisionRequest.models`), worker của Camunda 8 và delegate của Camunda 7 (`modelsVariable`) và endpoint đánh giá của trình soạn thảo (`models`) nhận đối tượng này, nhưng vẫn chỉ đánh giá bảng quyết định. Việc đánh giá luồng qua các tích hợp này được để lại cho sau.
4. **Giải thích.** Thêm danh mục mã lý do với thông điệp EN/VI và một bộ hiển thị giải thích tất định.
5. **Replay và chấm điểm lại.** Thêm các API này, và mở rộng tích hợp Temporal và Camunda để mang theo id bản ghi.
6. **Adapter.** Làm `rulestone-model-http` trước, rồi `rulestone-model-scorecard`, rồi `rulestone-model-onnx`.
7. **Mô hình chạy bóng, nút LLM, và khung vẽ luồng trong trình soạn thảo.**

## 10. Câu hỏi còn để ngỏ

| Vấn đề | Đề xuất hiện tại |
|---|---|
| Luồng có nên dùng chung phần mở rộng `.stone.json` không? | Đã quyết định: `.flow.json`, với `id`/`version`/hiệu lực theo ngày giống hệt bảng, trong cùng repository. |
| Gọi mô hình bất đồng bộ | Trước hết giữ việc đánh giá đồng bộ với deadline. Thêm API không chặn khi mô-đun máy chủ cần đến. |
| Mặc định đưa ra bao nhiêu mã lý do từ mô hình | Ba, cấu hình được theo từng nút. Ngân hàng thường cần tới bốn cho thông báo từ chối. |
| `factsHash` có nên dùng salt không? | Đã quyết định: băm trực tiếp các đầu vào có ít entropy có thể bị dò ngược, nên `factsHashKey` biến hash thành HMAC-SHA256 với khoá do ngân hàng nắm giữ. Nếu không có khoá, hash là SHA-256 thông thường. |
| Phương pháp attribution | Do nhà cung cấp mô hình quyết định (SHAP, điểm scorecard, v.v.). Adapter phải nêu tên phương pháp, và bản ghi lưu tên đó. |
