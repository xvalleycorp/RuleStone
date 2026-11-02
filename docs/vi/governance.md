---
source: governance.md
sourceHash: 0405a34a2b215288d0d40efeaa587ee2f0e0e3f227df9b5a05b583c77e85c726
---

# Quản trị luật

> Trạng thái: **đề xuất thiết kế** · Cập nhật lần cuối: 2026-11-02
>
> Chưa có nội dung nào trong trang này được triển khai. Đây là thiết kế cho mốc M2 của [lộ trình](architecture.md#9-lộ-trình): maker-checker, nhật ký kiểm toán liên kết chuỗi băm, kho luật JDBC và audit sink JDBC. Trang này dùng để rà soát và sẽ thay đổi trước khi phát hành.

Một ngân hàng chỉ có thể thay đổi một hạn mức tín dụng trong vài giây nếu nhiều năm sau vẫn chứng minh được ai đã viết thay đổi, ai đã phê duyệt, rằng văn bản được duyệt chính là văn bản đã chạy, và rằng từ đó tới nay không ai sửa lịch sử. RuleStone đã làm cho mọi phiên bản trở nên bất biến và được nhận diện bằng mã băm nội dung, và đã ghi lại phiên bản nào đưa ra từng quyết định. Trang này bổ sung nửa còn thiếu: một phê duyệt gắn với mã băm nội dung, một engine từ chối nội dung chưa được duyệt, và một nhật ký chống giả mạo cho mọi sự kiện quản trị (governance).

## Mục lục

1. [Nguyên tắc](#1-nguyên-tắc)
2. [Những gì được quản trị](#2-những-gì-được-quản-trị)
3. [Maker-checker](#3-maker-checker)
4. [Nhật ký kiểm toán quản trị](#4-nhật-ký-kiểm-toán-quản-trị)
5. [Lưu trữ](#5-lưu-trữ)
6. [Tích hợp với hệ thống của ngân hàng](#6-tích-hợp-với-hệ-thống-của-ngân-hàng)
7. [Bộ máy luật TypeScript tương đương](#7-bộ-máy-luật-typescript-tương-đương)
8. [Đối chiếu quy định](#8-đối-chiếu-quy-định)
9. [Ngoài phạm vi](#9-ngoài-phạm-vi)
10. [Câu hỏi mở](#10-câu-hỏi-mở)
11. [Kế hoạch triển khai](#11-kế-hoạch-triển-khai)

## 1. Nguyên tắc

1. **Phê duyệt gắn với nội dung, không gắn với tên.** Một phê duyệt nêu chính xác mã băm nội dung của mọi đối tượng mà nó bao phủ. Nội dung khác dù chỉ một ký tự là nội dung chưa được duyệt, bất kể nhãn phiên bản là gì.
2. **Engine kiểm tra, không tin tưởng mặc nhiên.** Một engine được tạo với `requireApproved()` tự tính lại mọi mã băm từ nội dung nó nạp và kiểm chứng chữ ký trên đó. Một dòng bị sửa trong cơ sở dữ liệu, hay một tệp được đẩy lên mà không qua xem xét, bị từ chối ngay lúc build, chứ không phải đợi tới khi kiểm toán mới phát hiện.
3. **Trạng thái quản trị nằm bên cạnh nội dung, không bao giờ nằm trong nội dung.** Phê duyệt, kích hoạt hay ngừng sử dụng một phiên bản không thay đổi byte nào của tệp, nên mã băm nội dung của nó, và mọi bản ghi quyết định nêu tên nó, vẫn hợp lệ.
4. **Không quản lý người dùng.** Định danh, đăng nhập và vai trò đến từ hệ thống của ngân hàng. RuleStone lưu mã định danh của chủ thể (principal) và kiểm tra các quy tắc maker-checker (người lập – người duyệt); nó không bao giờ lưu mật khẩu.
5. **Phê duyệt có thể đến từ bất cứ đâu, nhưng không thể bỏ qua các bất biến.** Một phê duyệt có thể được đưa ra trong màn hình của RuleStone, trong Camunda hay trong hệ thống quản lý thay đổi của ngân hàng. Trong mọi trường hợp, RuleStone đều kiểm tra nguyên tắc bốn mắt và việc gắn với mã băm.
6. **Quản trị không nằm trên đường ra quyết định.** Đánh giá một quyết định không đọc kho quản trị nào và không kiểm chứng chữ ký nào. Mọi kiểm tra diễn ra khi engine được tạo, nên độ trễ và tính sẵn sàng của quyết định không đổi.
7. **Hai bộ máy luật cho kết quả như nhau.** Bộ máy luật Java và bộ máy luật TypeScript kiểm chứng phê duyệt và bản phát hành giống hệt nhau, được kiểm tra bằng bộ kiểm thử tương thích.

## 2. Những gì được quản trị

### 2.1 Đối tượng quản trị

Mọi đối tượng có phiên bản mà có thể làm thay đổi một quyết định đều được quản trị:

| Loại | Tệp | Nhận diện bằng | Mã băm nội dung |
|---|---|---|---|
| `table` | `.stone.json` | `id`, `version` | [đã có](decision-tables.md#mã-băm-nội-dung) |
| `flow` | `.flow.json` | `id`, `version` | [đã có](flows.md#phiên-bản-và-mã-băm-nội-dung) |
| `params` | `.params.json` | `id`, `version` | [đã có](decision-tables.md#tệp-bộ-tham-số) |
| `catalog` | `reason-codes.json` | tên do kho luật đặt, `version` | [đã có](explanations.md#danh-mục-mã-lý-do) |

Danh mục mã lý do không có trường `id`, vì mỗi engine chỉ có một danh mục. Kho luật đặt tên cho nó, mặc định theo tên tệp (`reason-codes`), để có thể quản trị song song nhiều danh mục cho nhiều engine.

Mã băm không bao trùm sang đối tượng khác, và phê duyệt cũng vậy:

- Mã băm của một bảng bao phủ phần khai báo `params`, không bao phủ giá trị, nên **mỗi phiên bản bộ tham số cần phê duyệt riêng**. Thay đổi một hạn mức là một thay đổi, dù không luật nào thay đổi.
- Mã băm của một luồng bao phủ tệp luồng, không bao phủ các bảng mà các nút của nó dùng, trừ khi nút cố định một phiên bản. **Mỗi phiên bản bảng cần phê duyệt riêng.** Vì một phiên bản bảng mới làm thay đổi hành vi của mọi luồng dùng bảng đó mà không cần phiên bản luồng mới, phần xem xét một phiên bản bảng sẽ hiển thị các luồng dùng nó.
- Nút mô hình ghim một phiên bản mô hình chính xác bên trong mã băm của luồng, nên thay đổi mô hình là một phiên bản luồng mới và được quản trị ở đây. Việc thẩm định bản thân mô hình thuộc quy trình quản lý rủi ro mô hình của ngân hàng (xem [Ngoài phạm vi](#9-ngoài-phạm-vi)).

### 2.2 Vòng đời

```
            submit            approve (policy met)          activate
  DRAFT ─────────────▶ SUBMITTED ─────────────▶ APPROVED ─────────────▶ ACTIVE ─────▶ RETIRED
    ▲                     │                                               │
    │       withdraw      │  reject (with a reason)                       │ effectiveFrom
    └─────────────────────┤                                               ▼
                          ▼                                     in force on business dates
                       REJECTED
```

| Trạng thái | Ý nghĩa |
|---|---|
| `DRAFT` | Bản nháp đang soạn. Có thể sửa tự do và chưa phải là một phiên bản: không engine nào có `requireApproved()` nạp nó. |
| `SUBMITTED` | Được cố định để xem xét tại một mã băm nội dung. Một lần sửa đưa nó về `DRAFT` và làm vô hiệu mọi phê duyệt đã có. |
| `REJECTED` | Người duyệt đã từ chối, kèm lý do. Đây là kết thúc của lần trình duyệt đó; tác giả sửa lại thành một bản nháp mới. |
| `APPROVED` | Chính sách phê duyệt đã được đáp ứng và có một văn bản phê duyệt có chữ ký cho mã băm nội dung của nó. Engine production chưa nạp nó. |
| `ACTIVE` | Thuộc bản phát hành hiện tại: engine production nạp nó. Nó *có hiệu lực* vào những ngày nghiệp vụ mà `effectiveFrom` và `effectiveTo` của nó cho phép, đúng như hiện nay. |
| `RETIRED` | Không còn hiệu lực vào bất kỳ ngày nào từ hôm nay trở đi. Vẫn được giữ lại và vẫn nạp được, để các quyết định với ngày trong quá khứ và việc chạy lại vẫn hoạt động. |

**ACTIVE không có nghĩa là "có hiệu lực".** Việc kích hoạt quyết định engine nạp những phiên bản nào; ngày hiệu lực vẫn quyết định, theo từng yêu cầu, phiên bản nào trong số đã nạp được áp dụng. Một phiên bản có thể được kích hoạt nhiều tuần trước `effectiveFrom` của nó và có hiệu lực lúc nửa đêm mà không cần nạp lại, như hiện nay.

**Ngừng sử dụng chủ yếu là việc ghi sổ.** Một phiên bản tự động chuyển sang `RETIRED` khi nó không thể có hiệu lực được nữa: một phiên bản kế tiếp đang hoạt động đã có hiệu lực, hoặc `effectiveTo` của nó đã qua. Nó vẫn được đăng ký, vì một yêu cầu có ngày `asOf` trong quá khứ vẫn phải chọn được nó, và vì bỏ một phiên bản khỏi việc lựa chọn sẽ kéo dài hiệu lực của phiên bản trước đó (xem [Phiên bản và ngày hiệu lực](decision-tables.md#phiên-bản-và-ngày-hiệu-lực)). Để chấm dứt một chính sách đang có hiệu lực, hãy phát hành một phiên bản kế tiếp. Trường hợp ngừng sử dụng chủ động duy nhất là **huỷ** một phiên bản đang hoạt động nhưng chưa từng có hiệu lực (`effectiveFrom` của nó còn ở tương lai): việc này làm thay đổi những gì sẽ được áp dụng, nên cần phê duyệt như mọi thay đổi khác.

### 2.3 Phiên bản được thay thế, phê duyệt được gắn chặt

Quy tắc hiện có vẫn giữ nguyên: một phiên bản đã phát hành không bao giờ bị sửa, chỉ bị thay thế bởi một phiên bản mới kể từ `effectiveFrom` của phiên bản mới. Quản trị biến quy tắc này thành thứ được bắt buộc:

- **Bản nháp không phải là phiên bản.** Nhãn của nó chỉ là đề xuất và nội dung có thể thay đổi.
- **Khi được phê duyệt, `id@version` gắn vĩnh viễn với một mã băm nội dung.** Kho luật từ chối mọi nội dung khác dưới nhãn đó, kể cả sau khi ngừng sử dụng. Đây chính là kiểm tra mà [chạy lại](replay.md#phiên-bản-chưa-được-đăng-ký) đã thực hiện khi một nhãn được đăng ký với nội dung khác.
- Trước khi được phê duyệt, một nhãn đã bị từ chối hoặc rút lại có thể được trình duyệt lại với nội dung khác; mỗi lần trình duyệt là một sự kiện riêng với mã băm riêng, và phê duyệt cho nội dung trước không được chuyển sang.
- **Mọi thứ bị sửa sau khi phê duyệt đều là chưa được duyệt.** Engine so sánh mã băm nó tự tính với mã băm trong phê duyệt, nên không có cách nào sửa trực tiếp một phiên bản đã được duyệt.

### 2.4 Bộ thay đổi

Một thay đổi thực tế thường liên quan tới nhiều đối tượng: một phiên bản bảng mới đọc một tham số mới, và nội dung trong danh mục cho mã lý do mới của nó. Các đối tượng được trình duyệt, phê duyệt và kích hoạt cùng nhau thành một **bộ thay đổi** (change set):

- một lần trình duyệt và một lần xem xét cho mọi mục, hiển thị cùng nhau;
- một văn bản phê duyệt bao phủ mã băm của mọi mục;
- một lần kích hoạt, để engine không bao giờ nạp một nửa thay đổi.

Bộ thay đổi chỉ có một đối tượng là trường hợp thường gặp.

## 3. Maker-checker

### 3.1 Nguyên tắc bốn mắt

**Không người duyệt nào được là người lập.** Người lập của một bộ thay đổi là mọi chủ thể đã lưu một bản sửa của bất kỳ mục nào trong đó kể từ lần được duyệt gần nhất, cùng với người đã trình duyệt, chứ không chỉ người trình duyệt: nếu không, A có thể viết, B trình duyệt, rồi A phê duyệt. Dịch vụ quản trị ghi lại mọi lần lưu bản nháp kèm chủ thể và mã băm nội dung, nên tập người lập là một sự thật trong nhật ký, không phải một lời khai.

Nội dung do trợ lý soạn luật bằng AI soạn có hai người lập: trợ lý (`ai:<assistant id>`) và người đã yêu cầu nó. Một chủ thể AI có thể là người lập, không bao giờ là người duyệt (xem [Tích hợp AI](architecture.md#7-tích-hợp-ai-dự-kiến)).

### 3.2 Chính sách phê duyệt

Một thay đổi cần bao nhiêu phê duyệt, và từ ai, được đặt theo loại đối tượng, theo mẫu id của đối tượng và theo **cấp rủi ro**:

```yaml
# Governance policy, held by the governance service
tiers:
  standard:
    approvals: 1
    roles: [RULE_CHECKER]
  credit-policy:
    approvals: 2
    roles: [CREDIT_RISK, COMPLIANCE]   # one approver from each role
    distinctUnits: true                # approvers from different organisational units
  aml-thresholds:
    approvals: 2
    roles: [AML_OFFICER, COMPLIANCE]
assign:
  - { kind: params, id: "retail_credit", tier: credit-policy }
  - { kind: table,  id: "aml_*",         tier: aml-thresholds }
  - { kind: "*",    id: "*",             tier: standard }
```

Một bộ thay đổi nhận cấp rủi ro chặt nhất trong các mục của nó. Mỗi người duyệt là một chủ thể khác nhau. Vai trò đến từ nhà cung cấp định danh của ngân hàng (xem [Định danh](#62-định-danh)).

### 3.3 Phân tách nhiệm vụ

| Nhiệm vụ | Ai | Quy tắc |
|---|---|---|
| Lập | Chuyên viên phân tích có vai trò người lập cho đối tượng | Không được phê duyệt bộ thay đổi của chính mình. |
| Duyệt | Người giữ các vai trò mà cấp rủi ro yêu cầu | Không được là người lập của bộ thay đổi. |
| Phát hành | Dịch vụ quản trị khi phê duyệt xong, hoặc một vai trò phát hành | Bước tách riêng, không bắt buộc: người quản lý phát hành lên lịch kích hoạt nhưng không được sửa nội dung. |
| Khẩn cấp | Một nhóm nhỏ có vai trò khẩn cấp | Xem [Thay đổi khẩn cấp](#35-thay-đổi-khẩn-cấp). |
| Kiểm toán | Kiểm toán nội bộ, chỉ đọc | Đọc nhật ký và chạy kiểm chứng; không giữ nhiệm vụ nào khác. |
| Quản trị chính sách | Người quản trị quản trị (governance administrator) | Mọi thay đổi chính sách cũng được ghi vào nhật ký, và người này không được phê duyệt thay đổi luật. |

### 3.4 Xem xét, bình luận và từ chối

- Người duyệt thấy bộ thay đổi dưới dạng **so sánh khác biệt với phiên bản đang có hiệu lực**, theo từng luật, với mã băm nội dung của từng mục, các luồng bị ảnh hưởng bởi một thay đổi bảng, và kết quả của các bài kiểm thử đính kèm lần trình duyệt (độ bao phủ của `.stone-test.json`, và sau này là kiểm thử ngược).
- **Bình luận** được gắn với bộ thay đổi, có thể kèm vị trí trong một mục, viết bằng đường dẫn JSON mà trình kiểm tra hợp lệ đang dùng, ví dụ `loan_eligibility@4 $.rules[2].when`. Chúng là sự kiện quản trị và nằm trong nhật ký.
- **Từ chối phải có lý do**, và lý do được ghi vào nhật ký. Một lần từ chối kết thúc lần trình duyệt; tác giả sửa bản nháp và trình duyệt lại.
- **Rút lại**: tác giả có thể đưa một lần trình duyệt về `DRAFT` trước khi có quyết định.

Bình luận và lý do là văn bản tự do mà nhật ký giữ vĩnh viễn. Các trình soạn thảo cảnh báo không dán dữ liệu khách hàng vào đó; RuleStone không thể tự phát hiện điều này.

### 3.5 Thay đổi khẩn cấp

Khi một luật phải thay đổi ngay, ví dụ một kiểu gian lận đang diễn ra lúc nửa đêm, bộ thay đổi có thể được đánh dấu **khẩn cấp**:

- Nó vẫn cần **một người duyệt không phải người lập**, là người giữ vai trò khẩn cấp. Nguyên tắc bốn mắt được giảm bớt, không bao giờ bị bỏ: không có tự phê duyệt.
- Các bước chờ mà cấp rủi ro yêu cầu, như người duyệt thứ hai hay kiểm thử ngược bắt buộc, được hoãn lại.
- Văn bản phê duyệt mang `"emergency": true` và thời điểm `postApprovalDue`, mặc định là cuối ngày làm việc kế tiếp.
- Sau đó, toàn bộ chính sách phải **phê duyệt bổ sung** đúng các mã băm nội dung đó. Nhật ký ghi `EMERGENCY_APPROVED`, `ACTIVATED` rồi sau đó `POST_APPROVED`, hoặc `POST_APPROVAL_REJECTED`; trong trường hợp sau, phiên bản phải bị thay thế hoặc huỷ, và các quyết định đã đưa ra theo nó được tìm thấy qua trường `version` của bản ghi và được [chấm điểm lại](replay.md#chấm-điểm-lại-một-quyết-định).

Phê duyệt bổ sung quá hạn được dịch vụ quản trị đưa ra thành cảnh báo và hiển thị trong nhật ký. Engine không từ chối build vì nó: một engine đang chạy vẫn sẽ giữ phiên bản đó, và việc từ chối build chỉ chặn những lần nạp lại không liên quan.

### 3.6 Những gì engine bắt buộc

`requireApproved()` biến phê duyệt thành một yêu cầu lúc build:

```java
RuleStoneEngine engine = RuleStone.builder()
        .repository(repository)                  // tables, flows, parameter sets, catalog, approvals, release
        .requireApproved(ApprovalVerifier.builder()
                .trustedKey("gov-2026", publicKey)       // the governance service's signing key
                .minApprovers(1)                         // a floor the engine checks itself
                .build())
        .build();
```

`build()` thất bại, liệt kê mọi vấn đề như với luật không hợp lệ, trừ khi mọi bảng, luồng, bộ tham số và danh mục đã đăng ký đều:

1. được bao phủ bởi một văn bản phê duyệt có chủ đề nêu loại, id, nhãn phiên bản và **mã băm nội dung do chính engine tính**;
2. có văn bản với chữ ký kiểm chứng được bằng một khoá tin cậy, theo một thuật toán được phép;
3. có văn bản trong đó không người duyệt nào là người lập, và có ít nhất `minApprovers` người duyệt;
4. có tên trong bản phát hành mà engine nạp (xem [Bản phát hành](#38-bản-phát-hành)), khi bản phát hành là bắt buộc.

```text
invalid decision definition (2 problems):
  - loan_eligibility@4: no approval covers 4@sha256:77ba…; the approved content is 4@sha256:9f2c…
  - retail_credit@8 (parameter set): approval CHG-2026-0042 names approver u:tran.thi.b, who is also a maker
```

Engine bắt buộc những bất biến không phụ thuộc vào tổ chức của ngân hàng: gắn với mã băm, chữ ký, nguyên tắc bốn mắt và số người duyệt tối thiểu. Vai trò, cấp rủi ro và đơn vị là chính sách của dịch vụ quản trị, được chữ ký của dịch vụ xác nhận. Thông báo lỗi nêu phiên bản và chủ thể, không bao giờ nêu nội dung luật hay giá trị đầu vào.

Engine được tạo mà không có `requireApproved()` hoạt động như hiện nay, cho phát triển và kiểm thử. Dịch vụ quản trị có thể từ chối phát hành cho engine không khai báo nó, nhưng engine nhúng là mã của ngân hàng: cấu hình production là việc kiểm soát của ngân hàng, như phần [đối chiếu quy định](#8-đối-chiếu-quy-định) có nêu.

### 3.7 Văn bản phê duyệt

Một phê duyệt là một văn bản được ký trên các mã băm nội dung, lưu **tách rời** khỏi các đối tượng: dưới dạng tệp bên cạnh chúng, hoặc một dòng trong cơ sở dữ liệu. Các tệp đối tượng giữ nguyên.

```json
{
  "type": "rulestone.approval",
  "approvalId": "4a1f9c2e-7b3d-4e8a-b5c6-0d9e8f7a6b5c",
  "changeSet": "CHG-2026-0042",
  "subjects": [
    { "kind": "table", "id": "loan_eligibility", "version": "4@sha256:9f2c…" },
    { "kind": "params", "id": "retail_credit", "version": "8@sha256:41d8…" }
  ],
  "makers": ["u:nguyen.van.a"],
  "approvers": [
    { "principal": "u:tran.thi.b", "role": "CREDIT_RISK", "at": "2026-11-20T03:12:00Z" },
    { "principal": "u:le.van.c", "role": "COMPLIANCE", "at": "2026-11-20T07:41:00Z" }
  ],
  "policy": "credit-policy@3",
  "emergency": false,
  "issuer": "rulestone-governance@bank.example",
  "issuedAt": "2026-11-20T07:41:02Z",
  "logEntry": "sha256:5e0b…"
}
```

`logEntry` là mã băm của mục nhật ký kiểm toán đã phát hành văn bản này, gắn văn bản với [chuỗi](#4-nhật-ký-kiểm-toán-quản-trị).

**Định dạng chữ ký.** Đề xuất là **JWS dạng compact** (RFC 7515), với payload là văn bản ở dạng JSON chuẩn tắc của RuleStone, lưu thành `<change set>.approval.jws`:

| Phương án | Ưu điểm | Nhược điểm |
|---|---|---|
| JWS, `EdDSA` (Ed25519) hoặc `ES256` | Một chuẩn mà đội bảo mật và HSM của ngân hàng đều biết; có công cụ để xem; cả hai thuật toán đều có trong JDK (Java 17) và WebCrypto, nên không engine nào cần thêm thư viện phụ thuộc | Nhiều quy tắc phải làm đúng: engine chỉ chấp nhận `typ: "rulestone-approval+jws"`, một danh sách thuật toán được phép, khoá theo `kid` chỉ lấy từ cấu hình, và từ chối `alg: none`, khoá nhúng, `jku` và `x5u` |
| Một chữ ký Ed25519 trần trên văn bản chuẩn tắc | Định dạng và mã nhỏ nhất có thể | Không theo chuẩn; không thể đổi thuật toán, điều quan trọng khi HSM của ngân hàng chỉ hỗ trợ ECDSA hoặc RSA |
| Tin vào kho luật, không có chữ ký | Không phải xây dựng hay quản lý gì | Bất kỳ ai có quyền ghi vào kho, như DBA hay người quản trị Git, đều có thể kích hoạt nội dung chưa được duyệt |

Ai ký là một lựa chọn riêng:

- **Dịch vụ quản trị ký** bằng một khoá trong HSM hoặc KMS của ngân hàng, sau khi đã kiểm tra chính sách. Đây là mặc định: chỉ một khoá cần quản lý, và người duyệt xác thực qua SSO của ngân hàng như với mọi việc khác.
- **Mỗi người duyệt cũng ký**, bằng khoá cá nhân từ hạ tầng PKI hoặc thẻ thông minh của ngân hàng. Khi đó văn bản mang nhiều chữ ký (JWS dạng JSON) và bộ kiểm chứng có thể yêu cầu chúng. Chống chối bỏ mạnh hơn, đổi lại phải quản lý khoá cho mọi người duyệt.

Khoá được tham chiếu bằng `kid` và cấu hình trong engine. Khi xoay vòng khoá, khoá mới được thêm trước khi khoá cũ ngừng dùng; văn bản ký bằng khoá đã ngừng dùng vẫn hợp lệ cho nội dung nó bao phủ, vì nội dung không bao giờ thay đổi. Việc ký đi qua SPI `Signer` để khoá không bao giờ rời khỏi HSM.

### 3.8 Bản phát hành

Phê duyệt nói rằng nội dung được phép chạy. **Bản phát hành** nói engine nạp những phiên bản đã duyệt nào, như một tập hợp:

```json
{
  "type": "rulestone.release",
  "target": "retail-credit-prod",
  "sequence": 118,
  "previous": "sha256:c41d…",
  "artifacts": [
    { "kind": "table", "id": "loan_eligibility", "version": "3@sha256:0a7e…", "state": "RETIRED" },
    { "kind": "table", "id": "loan_eligibility", "version": "4@sha256:9f2c…", "state": "ACTIVE" },
    { "kind": "params", "id": "retail_credit", "version": "8@sha256:41d8…", "state": "ACTIVE" },
    { "kind": "catalog", "id": "reason-codes", "version": "5@sha256:fccd…", "state": "ACTIVE" }
  ],
  "routing": [
    { "decision": "loan_eligibility", "kind": "SHADOW", "version": "5@sha256:2b9e…" }
  ],
  "issuedAt": "2026-11-20T07:45:00Z"
}
```

Nó được ký như một phê duyệt. Nó giải quyết cùng lúc ba vấn đề:

- **Tính đầy đủ.** Engine nạp đúng các phiên bản được liệt kê, mọi phiên bản của một quyết định cùng nhau, nên một tệp bị thiếu không thể âm thầm giữ một phiên bản cũ ở trạng thái có hiệu lực.
- **Tấn công quay lui.** Một phiên bản đã bị huỷ vẫn có phê duyệt hợp lệ. `sequence` của bản phát hành chỉ tăng và `previous` nối các bản phát hành thành chuỗi; engine từ chối một bản phát hành cũ hơn bản nó đang chạy.
- **Định tuyến cũng được quản trị.** Phiên bản canary và phiên bản bóng (shadow) là một phần của bản phát hành, nên việc chuyển lưu lượng sang một phiên bản được duyệt như chính phiên bản đó.

Vì vậy, kích hoạt nghĩa là "phát hành một bản phát hành mới". Các mục của một bộ thay đổi vào cùng một bản phát hành, một cách nguyên tử.

## 4. Nhật ký kiểm toán quản trị

### 4.1 Sự kiện

Mỗi sự kiện quản trị là một mục trong một nhật ký chỉ ghi thêm: `DRAFT_CREATED`, `DRAFT_SAVED`, `SUBMITTED`, `WITHDRAWN`, `COMMENTED`, `APPROVED` (một mục cho mỗi người duyệt), `REJECTED`, `APPROVAL_ISSUED` (chính sách đã được đáp ứng và văn bản đã được ký), `EMERGENCY_APPROVED`, `POST_APPROVED`, `POST_APPROVAL_REJECTED`, `RELEASED`, `ACTIVATED`, `RETIRED`, `CANCELLED`, `POLICY_CHANGED`, `KEY_ADDED`, `KEY_RETIRED`, `ANCHORED`.

```json
{
  "seq": 1042,
  "eventId": "9d2b7a1e-3c4f-4a5b-8e6d-7f0a1b2c3d4e",
  "at": "2026-11-20T03:12:00.481Z",
  "type": "APPROVED",
  "actor": { "principal": "u:tran.thi.b", "via": "camunda8:rule-change/2251799813685321" },
  "changeSet": "CHG-2026-0042",
  "subjects": [ { "kind": "table", "id": "loan_eligibility", "version": "4@sha256:9f2c…" } ],
  "details": { "role": "CREDIT_RISK", "comment": "DTI limit matches the Credit Committee minutes of 2026-11-18" },
  "prev": "sha256:8c1e…",
  "hash": "sha256:5e0b…"
}
```

- `hash` là SHA-256 của dạng chuẩn tắc của mục khi bỏ `hash`, cùng dạng JSON chuẩn tắc như bảng quyết định. `prev` là `hash` của mục `seq - 1`; `prev` của mục đầu tiên là 64 chữ số 0.
- `seq` không có khoảng trống. Mỗi kho quản trị có một nhật ký, cho một thứ tự toàn phần; sự kiện quản trị hiếm, nên mỗi lúc một bên ghi là đủ.
- `via` nêu hệ thống mà thao tác đến từ đó, như trình soạn thảo, một process instance của Camunda hay phiếu của hệ thống quản lý thay đổi của ngân hàng.
- Nhật ký chứa mã băm, mã định danh chủ thể, phiên bản và văn bản tự do. Nó không chứa nội dung luật, vốn được kho luật giữ dưới cùng các mã băm, và không chứa dữ liệu khách hàng.

### 4.2 Kiểm chứng

`rulestone-governance verify` (một công cụ dòng lệnh Java, cũng gọi được như thư viện) kiểm tra những điều sau, và báo mục đầu tiên mà một kiểm tra thất bại:

- mọi `hash` tính lại được và mọi `prev` khớp, không có khoảng trống trong `seq`;
- đầu chuỗi khớp với lần neo gần nhất (xem bên dưới);
- mọi văn bản phê duyệt và bản phát hành kiểm chứng được bằng các khoá còn hiệu lực lúc chúng được phát hành, và nêu một `logEntry` có tồn tại;
- mọi phiên bản đang hoạt động hoặc đã ngừng sử dụng trong kho luật có mã băm nội dung nằm trong một sự kiện `APPROVAL_ISSUED` và một sự kiện `RELEASED`, và nội dung lưu dưới mã băm đó vẫn cho ra đúng mã băm đó;
- mọi phê duyệt thoả nguyên tắc bốn mắt khi đối chiếu với các sự kiện `DRAFT_SAVED` và `SUBMITTED` của bộ thay đổi.

Kết quả là một báo cáo mà kiểm toán viên có thể lưu giữ, bản thân nó không chứa nội dung luật.

### 4.3 Neo chuỗi

Một chuỗi chỉ chứng minh các mục không bị sửa với người đang giữ một mã băm muộn hơn. Một người nội bộ có thể ghi đè cả bảng thì cũng có thể dựng lại cả chuỗi. Việc neo (anchoring) đặt mã băm đầu chuỗi ra ngoài tầm với của người đó:

| Cách neo | Cách làm | Phù hợp với |
|---|---|---|
| **Xuất điểm kiểm có chữ ký** | Hằng ngày, dịch vụ ký `{seq, hash, at}` của đầu chuỗi và ghi vào nơi lưu trữ mà dịch vụ quản trị không thể ghi đè: object storage có khoá lưu giữ, hoặc kho lưu trữ WORM | Mọi ngân hàng; là mặc định |
| **Dấu thời gian tin cậy** | Một token dấu thời gian RFC 3161 trên mã băm đầu chuỗi, từ tổ chức cấp dấu thời gian của chính ngân hàng hoặc một tổ chức được cấp phép | Chứng minh *khi nào*, không chỉ *cái gì*; bằng chứng khi có tranh chấp |
| **Hệ thống của đội khác** | Điểm kiểm được gửi tới nền tảng nhật ký (SIEM) của kiểm toán nội bộ hoặc đội an ninh | Tách biệt khỏi đội vận hành nền tảng |
| **Sổ cái gốc của cơ sở dữ liệu** | Bảng bất biến hoặc bảng blockchain của Oracle, bảng ledger của SQL Server, như lớp bảo vệ bổ sung cho bảng nhật ký | Khi ngân hàng đã có giấy phép cho chúng; không bắt buộc |

Mỗi lần neo được ghi ngược vào chuỗi thành một sự kiện `ANCHORED`, để lần kiểm chứng sau biết phải so với đầu chuỗi nào.

### 4.4 Bản ghi quyết định là một dòng riêng

Nhật ký quản trị và [bản ghi quyết định](java-api.md#bản-ghi-quyết-định-và-kiểm-toán) được ghi qua `AuditSink` trả lời những câu hỏi khác nhau và được giữ tách biệt:

| | Nhật ký quản trị | Bản ghi quyết định |
|---|---|---|
| Câu hỏi | Ai đã thay đổi luật nào, và ai đã duyệt | Đã quyết định gì, với phiên bản nào, và vì sao |
| Khối lượng | Vài chục mục mỗi ngày | Tới hàng nghìn bản ghi mỗi giây |
| Thứ tự | Một thứ tự toàn phần | Tốt nhất là theo từng correlation id, trên nhiều instance engine |
| Bên ghi | Dịch vụ quản trị | Mọi engine, qua các sink của nó |

Chúng nối với nhau qua phiên bản chính xác: `version` của bản ghi, các phiên bản trong `path` và `params` của nó nêu mã băm nội dung, và nhật ký cho thấy ai đã duyệt và phát hành từng phiên bản. Kiểm toán viên đi từ một quyết định tới những người đã duyệt nó trong hai lần tra cứu.

**Bản ghi quyết định có nên được liên kết chuỗi không?** Phần yêu cầu của ngân hàng trong trang kiến trúc có nêu bản ghi quyết định chống giả mạo.

- *Ưu điểm:* việc xoá hay sửa một bản ghi trở nên phát hiện được, và cơ quan quản lý đánh giá cao điều này.
- *Nhược điểm:* một chuỗi duy nhất buộc mọi quyết định trên mọi instance engine phải tuần tự, điều mà luồng xử lý trực tiếp (inline) không chịu được; chuỗi theo từng instance bị đứt mỗi khi một instance khởi động lại hay mở rộng; bản ghi có dữ liệu cá nhân trong đầu ra hoặc correlation id cuối cùng phải bị xoá khi hết thời hạn lưu giữ, làm đứt chuỗi nếu việc cắt bỏ không được thiết kế sẵn; và Kafka sink theo thiết kế là bất đồng bộ.

Đề xuất là **niêm phong bản ghi theo lô, không liên kết chuỗi từng bản ghi**. Engine giữ nguyên. `rulestone-audit-jdbc` ghi bản ghi theo kiểu chỉ thêm và, nếu được bật, một tác vụ niêm phong tính cho mỗi khung thời gian và mỗi phân vùng một mã băm trên các bản ghi theo thứ tự `recordId`, nối chuỗi từ khung này sang khung kế tiếp, ghi vào nhật ký quản trị thành sự kiện `RECORDS_SEALED` và được neo cùng nhật ký. Khi đó việc xoá, thêm hay sửa một bản ghi trong một khung đã niêm phong là phát hiện được, và một khung hết thời hạn lưu giữ được xoá cùng với dấu niêm phong của nó, chỉ để lại mã băm của dấu niêm phong trong nhật ký. Với Kafka, cùng tác vụ này có thể chạy ở phía consumer.

## 5. Lưu trữ

### 5.1 SPI `RuleRepository`

Engine chỉ đọc. Giao diện kho luật, trong `rulestone-api`, chỉ đọc và trả về nội dung thô, không bao giờ trả đối tượng đã phân tích hay mã băm để tin theo, nên engine tự tính mọi mã băm:

```java
public interface RuleRepository {

    /** The current release of a target, with its content and approvals, verified by the caller. */
    Snapshot load(String target) throws RepositoryException;

    /** One exact version, for replay, also when it is retired or not in the current release. */
    Optional<Artifact> find(ArtifactKind kind, String id, String exactVersion) throws RepositoryException;

    /** Notifies the listener when a newer release of the target is issued. Optional. */
    default Subscription watch(String target, Consumer<Long> newSequence) {
        return Subscription.NONE;
    }
}

record Artifact(ArtifactKind kind, String id, String version, byte[] content) {}
record Snapshot(long sequence, byte[] release, List<Artifact> artifacts, List<byte[]> approvals) {}
```

Việc ghi bản nháp, lần trình duyệt và sự kiện là việc của dịch vụ quản trị, qua một SPI riêng, `GovernanceStore`, trong `rulestone-governance`. Ngân hàng giữ luật trong hệ thống của mình chỉ cần triển khai `RuleRepository`.

| Triển khai | Mô-đun | Trạng thái |
|---|---|---|
| Tệp và Git | `rulestone-core` | Hiện đã đọc được tệp nhưng chưa có quản trị; M2 thêm phê duyệt và bản phát hành dưới dạng tệp |
| JDBC | `rulestone-repository-jdbc` | Mục tiêu của M2: PostgreSQL, Oracle, SQL Server |
| Object storage tương thích S3 | `rulestone-repository-s3` | Sau này |

### 5.2 Tệp và Git

Cấu trúc thư mục hiện nay vẫn dùng được, có thêm hai loại tệp:

```
rules/
  loan_eligibility.v3.stone.json
  loan_eligibility.v4.stone.json
  retail_credit.v8.params.json
  reason-codes.json
  approvals/
    CHG-2026-0042.approval.jws
  releases/
    retail-credit-prod.release.jws
```

Bản nháp và lần trình duyệt nằm trong các branch và pull request, như [quy trình của trình soạn thảo web](editor.md#cách-lưu-tệp) mô tả hiện nay. Phê duyệt là văn bản có chữ ký, do dịch vụ quản trị phát hành, hoặc do một job CI kiểm tra các lượt review của pull request theo chính sách và giữ khoá ký. Vì engine kiểm chứng chữ ký, một commit được đẩy lên mà không qua review vẫn bị từ chối, kể cả khi cơ chế bảo vệ branch không hoạt động.

Ở chế độ Git, lịch sử review của máy chủ Git là bản ghi chi tiết về ai đã bình luận và phê duyệt; nhật ký quản trị ở [mục 4](#4-nhật-ký-kiểm-toán-quản-trị), với bản nháp, bình luận và nguyên tắc bốn mắt trên mọi lần lưu, cần có dịch vụ quản trị và kho của nó.

### 5.3 JDBC

`rulestone-repository-jdbc` triển khai cả `RuleRepository` và `GovernanceStore`. **Phiên bản là các dòng bất biến**: khi một nhãn đã được duyệt, dòng của nó không bao giờ bị cập nhật hay xoá. Bản nháp là dữ liệu duy nhất có thể sửa. Bản phác thảo cho PostgreSQL:

```sql
-- Approved content: insert-only. id@version is bound to one hash forever.
CREATE TABLE rs_artifact_version (
  kind            VARCHAR(16)  NOT NULL,          -- table, flow, params, catalog
  artifact_id     VARCHAR(200) NOT NULL,
  version_label   VARCHAR(100) NOT NULL,
  content_hash    CHAR(71)     NOT NULL,          -- sha256:<64 hex>
  content         TEXT         NOT NULL,          -- canonical JSON, exact bytes as UTF-8
  effective_from  DATE,
  effective_to    DATE,
  created_at      TIMESTAMPTZ  NOT NULL,
  PRIMARY KEY (kind, artifact_id, version_label)
);

-- Working copies: the only table that is updated.
CREATE TABLE rs_draft (
  draft_id        UUID         PRIMARY KEY,
  change_set_id   VARCHAR(64)  NOT NULL,
  kind            VARCHAR(16)  NOT NULL,
  artifact_id     VARCHAR(200) NOT NULL,
  version_label   VARCHAR(100) NOT NULL,
  content         TEXT         NOT NULL,
  revision        INTEGER      NOT NULL,          -- optimistic locking, like the editor's ETag
  updated_by      VARCHAR(200) NOT NULL,
  updated_at      TIMESTAMPTZ  NOT NULL
);

CREATE TABLE rs_change_set (
  change_set_id   VARCHAR(64)  PRIMARY KEY,
  title           VARCHAR(500) NOT NULL,
  tier            VARCHAR(64)  NOT NULL,
  emergency       BOOLEAN      NOT NULL,
  state           VARCHAR(16)  NOT NULL,          -- a cache of the log, checked by verification
  external_ref    VARCHAR(200)                    -- the ApprovalWorkflow's process or ticket
);

CREATE TABLE rs_approval (
  approval_id     UUID         PRIMARY KEY,
  change_set_id   VARCHAR(64)  NOT NULL REFERENCES rs_change_set,
  statement       TEXT         NOT NULL,          -- JWS compact serialization
  issued_at       TIMESTAMPTZ  NOT NULL
);

CREATE TABLE rs_release (
  target          VARCHAR(100) NOT NULL,
  sequence        BIGINT       NOT NULL,
  manifest        TEXT         NOT NULL,          -- signed release, JWS
  issued_at       TIMESTAMPTZ  NOT NULL,
  PRIMARY KEY (target, sequence)
);

-- The governance log: insert-only, one writer at a time.
CREATE TABLE rs_governance_log (
  seq             BIGINT       PRIMARY KEY,       -- no gaps: assigned as head + 1 under a lock
  entry           TEXT         NOT NULL,          -- canonical JSON of the entry
  entry_hash      CHAR(71)     NOT NULL UNIQUE,
  prev_hash       CHAR(71)     NOT NULL,
  at              TIMESTAMPTZ  NOT NULL
);

CREATE FUNCTION rs_refuse_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'table % is append-only', TG_TABLE_NAME;
END $$;

CREATE TRIGGER rs_artifact_version_immutable BEFORE UPDATE OR DELETE ON rs_artifact_version
  FOR EACH ROW EXECUTE FUNCTION rs_refuse_change();
CREATE TRIGGER rs_governance_log_immutable BEFORE UPDATE OR DELETE ON rs_governance_log
  FOR EACH ROW EXECUTE FUNCTION rs_refuse_change();
```

Các trigger tương tự bảo vệ `rs_approval` và `rs_release`. Người dùng cơ sở dữ liệu của ứng dụng chỉ được cấp quyền `INSERT` và `SELECT` trên các bảng chỉ ghi thêm; trigger là lớp bảo vệ thứ hai, và mã băm cùng chữ ký là lớp thứ ba, vì DBA có thể tắt trigger.

| Vấn đề | PostgreSQL | Oracle | SQL Server |
|---|---|---|---|
| Nội dung | `TEXT` (không dùng `JSONB`, vì nó sắp xếp lại khoá) | `CLOB` | `NVARCHAR(MAX)` |
| Thời điểm | `TIMESTAMPTZ` | `TIMESTAMP WITH TIME ZONE` | `DATETIMEOFFSET` |
| Giá trị logic | `BOOLEAN` | `NUMBER(1)` | `BIT` |
| Từ chối cập nhật và xoá | Trigger `BEFORE UPDATE OR DELETE` | Trigger với `RAISE_APPLICATION_ERROR`; có thể dùng bảng bất biến | Trigger `INSTEAD OF UPDATE, DELETE`, hoặc `DENY`; có thể dùng bảng ledger chỉ ghi thêm |
| Tuần tự hoá việc ghi nhật ký | `SELECT … FOR UPDATE` trên một bảng đầu chuỗi chỉ có một dòng | Như trên | `UPDLOCK, HOLDLOCK` trên dòng đầu chuỗi |
| Thông báo thay đổi | `LISTEN`/`NOTIFY` | Thăm dò định kỳ | Thăm dò định kỳ |

Mã băm luôn do RuleStone tính từ nội dung, không bao giờ do cơ sở dữ liệu tính, nên ba cơ sở dữ liệu cho ra mã băm giống hệt nhau. Mô-đun cung cấp DDL cho từng cơ sở dữ liệu dưới dạng script thuần, không phụ thuộc vào công cụ migration nào.

### 5.4 Nạp và nạp lại nóng

Một engine nạp một bản phát hành của đích (target) của nó:

1. `repository.load(target)` trả về bản phát hành có chữ ký, nội dung của mọi phiên bản được liệt kê và các phê duyệt.
2. `build()` kiểm chứng bản phát hành (chữ ký, `sequence` không cũ hơn bản đang chạy), tính lại mọi mã băm nội dung, đối chiếu từng mã băm với bản phát hành và các phê duyệt, rồi kiểm tra hợp lệ của luật như hiện nay.
3. Ứng dụng hoán đổi sang engine mới, như trong [Nạp lại luật](java-api.md#nạp-lại-luật).

Một lớp hỗ trợ nhỏ làm việc này khi có thông báo hoặc theo chu kỳ:

```java
RepositoryReloader reloader = RepositoryReloader.builder(repository, "retail-credit-prod")
        .engine(builder -> builder.requireApproved(verifier).auditSink(auditSink))
        .onReload(engine::set)                                  // AtomicReference<RuleStoneEngine>
        .onFailure((sequence, e) -> log.error("release {} not loaded", sequence, e))
        .pollEvery(Duration.ofSeconds(30))
        .build();
```

Một bản phát hành không qua được kiểm chứng hay kiểm tra hợp lệ không bao giờ được hoán đổi vào; engine trước đó tiếp tục chạy. Vì bản phát hành có chữ ký, bản được nạp gần nhất cũng có thể được lưu đệm trên đĩa cục bộ và dùng lúc khởi động khi không kết nối được kho luật, giữ được cam kết hiện có rằng quyết định không phụ thuộc vào việc kho luật đang hoạt động.

### 5.5 Audit sink JDBC cho bản ghi quyết định

`rulestone-audit-jdbc` ghi mỗi `DecisionRecord` thành một dòng chỉ ghi thêm:

| Cột | Lấy từ bản ghi |
|---|---|
| `record_id` (khoá chính) | `recordId` |
| `correlation_id` (có chỉ mục) | `correlationId` |
| `decision`, `version` (có chỉ mục cùng `business_date`) | `decision`, `version` |
| `business_date`, `evaluated_at` | `businessDate`, `evaluatedAt` |
| `status` | `status` |
| `record` | bản ghi đúng như `DecisionRecordJson.write` ghi, giữ nguyên từng byte |
| `seal_window` | khung niêm phong, khi bật niêm phong |

Mặc định nó ghi đồng bộ trên luồng thực thi (thread) đang đánh giá, để một quyết định không bao giờ được trả về khi bản ghi của nó chưa được nhận, như mọi sink khác. Chế độ ghi theo lô chuyển bản ghi vào một hàng đợi có giới hạn và làm lần đánh giá thất bại khi hàng đợi đầy, như Kafka sink làm với bộ đệm của nó. Thời hạn lưu giữ và việc phân vùng theo `business_date` do bộ phận quản trị cơ sở dữ liệu của ngân hàng quyết định.

## 6. Tích hợp với hệ thống của ngân hàng

### 6.1 SPI `ApprovalWorkflow`

Nhiều ngân hàng đã phê duyệt thay đổi trong một hệ thống BPM hoặc hệ thống quản lý thay đổi. Dịch vụ quản trị giao việc ra quyết định cho hệ thống đó và vẫn giữ các bất biến:

```java
public interface ApprovalWorkflow {

    /** Called once when a change set is submitted. Returns the external process or ticket id. */
    String start(ApprovalRequest request);

    /** Called when the change set is withdrawn, or edited and so voided, before a decision. */
    void cancel(String externalRef, String reason);
}

// The external system reports each decision back:
governance.decide(changeSetId, Decision.approve(principal, "CREDIT_RISK", comment));
governance.decide(changeSetId, Decision.reject(principal, reason));
```

`ApprovalRequest` mang id của bộ thay đổi, các mục cùng mã băm, cấp rủi ro, người lập và một liên kết tới màn hình xem xét. Dù hệ thống bên ngoài trả lời thế nào, dịch vụ quản trị vẫn kiểm tra mỗi người duyệt đã được xác thực, giữ một vai trò được yêu cầu và không phải người lập, trước khi tính phê duyệt đó, và ghi tham chiếu bên ngoài vào `via`.

| Triển khai | Dùng cho |
|---|---|
| `InMemoryApprovalWorkflow` | Kiểm thử |
| Có sẵn | Phê duyệt trong màn hình xem xét của chính RuleStone; lưu bằng kho JDBC |
| Camunda 8 và Camunda 7 | Khởi động một quy trình BPMN với bộ thay đổi làm biến quy trình; một user task cho mỗi phê duyệt; một job worker hoặc delegate báo quyết định trở lại. Xây trên [tích hợp Camunda](camunda.md) |
| Riêng của ngân hàng | Vài chục dòng mã gọi API của hệ thống phiếu yêu cầu hoặc BPM của ngân hàng |

### 6.2 Định danh

RuleStone không có người dùng. Ứng dụng chủ (host) cung cấp chủ thể hiện tại:

```java
public interface PrincipalResolver {
    /** The authenticated caller of the current request: a stable id, a display name and roles. */
    Principal current();
}
```

Chủ thể đến từ SSO của ngân hàng (claim OIDC hoặc SAML, nhóm Active Directory hoặc LDAP), được ánh xạ sang các vai trò quản trị trong cấu hình. Mã định danh chủ thể được lưu đúng như được cung cấp (`u:tran.thi.b`), nên hệ thống định danh của ngân hàng vẫn là nguồn xác định một người là ai. Người đã nghỉ việc vẫn giữ mã định danh trong nhật ký; phê duyệt của họ vẫn hợp lệ vì chúng hợp lệ vào lúc được đưa ra.

### 6.3 SDK trình soạn thảo

[SDK trình soạn thảo](editor-sdk.md) vẫn không tự lưu trữ: ứng dụng chủ đăng nhập người dùng và lưu dữ liệu. Để tham gia quy trình quản trị, ứng dụng chủ:

- lưu bản nháp từ `rulestone-change` (`detail.table` và `detail.ruleVersion`) qua REST API của dịch vụ quản trị, hoặc vào kho của chính nó;
- cho người duyệt thấy chính xác `ruleVersion` mà họ phê duyệt, để mã băm trong phê duyệt chính là mã băm trên màn hình;
- nếu chạy quy trình phê duyệt riêng, hoặc báo quyết định cho dịch vụ quản trị, hoặc tự ký văn bản phê duyệt bằng khoá của mình, và engine khi đó tin ứng dụng này như một bên phát hành.

SDK sẽ có thêm các phần tử cho việc xem xét: so sánh khác biệt theo từng luật giữa hai phiên bản của một bảng hoặc luồng, và bản tóm tắt bộ thay đổi kèm mã băm. Chúng cũng không gọi mạng.

### 6.4 Trình soạn thảo độc lập

[Trình soạn thảo web](editor.md) hiện không có xác thực và chỉ an toàn khi chạy trên máy của người dùng hoặc sau một proxy SSO. Để cả nhóm dùng được, nó cần:

1. **Xác thực.** Trước tiên là chế độ tin proxy: reverse proxy SSO chuyển người dùng và nhóm trong header, chỉ được chấp nhận từ địa chỉ của proxy và kèm một bí mật dùng chung hoặc TLS hai chiều giữa proxy và trình soạn thảo. Sau đó là đăng nhập OIDC có sẵn.
2. **Một kho quản trị.** Với `--governance jdbc:…`, thao tác lưu ghi một bản nháp thay vì một tệp, và trình soạn thảo có thêm màn hình trình duyệt, xem xét với so sánh khác biệt và bình luận, phê duyệt và từ chối, và nhật ký của một bộ thay đổi. Không có tuỳ chọn này, trình soạn thảo ghi tệp như hiện nay, cho một người làm việc trên một bản clone Git.
3. **Phân quyền theo từng quyết định**, từ các vai trò mà nhà cung cấp định danh đưa ra.

## 7. Bộ máy luật TypeScript tương đương

Bộ máy luật TypeScript (`@dxa/rulestone-engine`) là một engine chính thức cho dịch vụ Node.js và giao diện người dùng, nên nó phải từ chối nội dung chưa được duyệt đúng như bộ máy luật Java.

| Khả năng | Java | TypeScript |
|---|---|---|
| Dạng chuẩn tắc và mã băm nội dung của cả bốn loại | có (đã có) | có (đã có) |
| Đọc và kiểm chứng văn bản phê duyệt: JWS, `EdDSA` và `ES256`, khoá tin cậy theo `kid`, gắn với mã băm, nguyên tắc bốn mắt, `minApprovers` | có | có, bằng WebCrypto; `createEngine` vốn đã bất đồng bộ |
| `requireApproved` với cùng vấn đề và cùng thông báo | có | có |
| Đọc và kiểm chứng bản phát hành, `sequence` chỉ tăng | có | có |
| Giao diện đọc `RuleRepository` | có | có, bất đồng bộ, ví dụ qua HTTP |
| Lớp hỗ trợ nạp lại nóng | có | có |
| Dịch vụ quản trị: vòng đời, chính sách, bộ thay đổi, thay đổi khẩn cấp | có | không |
| Ghi nhật ký quản trị, công cụ kiểm chứng, neo chuỗi | có | không; kiểm chứng một bản xuất nhật ký trên trình duyệt có thể làm sau |
| Kho luật JDBC và audit sink JDBC | có | không |
| `ApprovalWorkflow`, `PrincipalResolver`, quy trình Camunda | có | không |
| Xác thực và màn hình xem xét của trình soạn thảo | trình soạn thảo độc lập | phần tử xem xét trong SDK; đăng nhập vẫn thuộc về ứng dụng chủ |

Một fixture tương thích mới, `java-approvals.json`, do bộ máy luật Java ghi ra, liệt kê các văn bản phê duyệt và bản phát hành kèm kết quả mong đợi: hợp lệ, mã băm bị sửa, khoá sai hoặc không biết, `alg: none`, khoá nhúng, người lập tự phê duyệt, thiếu người duyệt, `sequence` của bản phát hành cũ. Bộ máy luật TypeScript phải cho cùng kết luận và cùng nội dung vấn đề.

Engine trên trình duyệt kiểm chứng phê duyệt để bản xem trước hay phép kiểm tra trước dùng luật đã được duyệt, nhưng trình duyệt không phải là ranh giới tin cậy: những quyết định quan trọng được đưa ra, hoặc đưa ra lại, bởi một engine phía máy chủ.

## 8. Đối chiếu quy định

Bảng này định hướng cho thiết kế; nó không phải tư vấn pháp lý. Cần kiểm tra các văn bản hiện hành trước mọi cam kết với khách hàng.

| Yêu cầu | Được đáp ứng ở đâu |
|---|---|
| Quản lý thay đổi cho hệ thống thông tin ngân hàng: thay đổi được đề nghị, đánh giá, phê duyệt và kiểm thử trước khi đưa vào production, thay đổi khẩn cấp được ghi lại và phê duyệt sau (các quy định của Ngân hàng Nhà nước Việt Nam về an toàn hệ thống thông tin trong hoạt động ngân hàng; các biện pháp kiểm soát của ISO/IEC 27001; PCI DSS khi có xử lý dữ liệu thẻ) | Vòng đời, bộ thay đổi, kiểm thử đính kèm lần trình duyệt, quy trình khẩn cấp với hạn phê duyệt bổ sung |
| Phân tách nhiệm vụ và kiểm soát bốn mắt, như một phần của hệ thống kiểm soát nội bộ của tổ chức tín dụng (các quy định của Ngân hàng Nhà nước Việt Nam về kiểm soát nội bộ; các nguyên tắc của Uỷ ban Basel) | Người lập được xác định từ mọi lần lưu, người duyệt khác người lập, vai trò từ nhà cung cấp định danh của ngân hàng, việc quản trị chính sách được tách riêng |
| Lưu giữ nhật ký và bằng chứng của thay đổi, được bảo vệ khỏi việc sửa đổi | Nhật ký quản trị liên kết chuỗi băm, neo chuỗi ngoài tầm với của đội vận hành nền tảng, công cụ kiểm chứng |
| Quản lý rủi ro mô hình (kiểu SR 11-7): danh mục mô hình, kiểm soát thay đổi, thẩm định độc lập và phản biện hiệu quả, tài liệu | Mọi phiên bản mô hình được ghim nằm trong một phiên bản luồng được quản trị; người duyệt thuộc vai trò thẩm định; bình luận và lý do từ chối; nhật ký là lịch sử thay đổi của việc dùng từng mô hình |
| Truy vết dữ liệu rủi ro (BCBS 239) | Bản ghi quyết định → phiên bản chính xác → văn bản phê duyệt → các mục nhật ký |
| Lưu giữ hồ sơ và giám sát của con người với AI rủi ro cao, như chấm điểm tín dụng (EU AI Act, khi áp dụng) | Bản ghi quyết định luôn được tạo; AI chỉ được soạn nháp; phê duyệt do con người thực hiện |
| Tái lập một quyết định trong quá khứ khi có khiếu nại của khách hàng hoặc khi thanh tra | Phiên bản đã ngừng sử dụng vẫn nạp được; chạy lại theo phiên bản chính xác; phê duyệt chứng minh văn bản nào đã có hiệu lực |
| Bảo vệ dữ liệu cá nhân (luật và các nghị định về bảo vệ dữ liệu cá nhân của Việt Nam) | Không có dữ liệu khách hàng trong nhật ký quản trị; các khung bản ghi quyết định đã niêm phong có thể bị xoá khi hết thời hạn lưu giữ |

Có hai biện pháp kiểm soát vẫn thuộc về ngân hàng: cấu hình engine production với `requireApproved()`, vốn thuộc quy trình kiểm soát thay đổi khi triển khai của chính ngân hàng, và bảo vệ khoá ký trong HSM hoặc KMS của ngân hàng.

## 9. Ngoài phạm vi

- **Quản lý người dùng và vai trò**, trang đăng nhập và lưu mật khẩu. Chúng thuộc về nhà cung cấp định danh của ngân hàng.
- **Một hệ thống BPM hay hệ thống phiếu yêu cầu tổng quát.** RuleStone có quy trình phê duyệt sẵn cho các thay đổi của chính nó và kết nối với hệ thống của ngân hàng.
- **Thẩm định mô hình và danh mục đăng ký mô hình (model registry).** RuleStone quản trị việc luồng ghim phiên bản mô hình nào, không quản trị việc phát triển hay thẩm định mô hình.
- **Phê duyệt fact hay dữ liệu đầu vào.** Đầu vào thuộc trách nhiệm của ứng dụng gọi.
- **Huỷ bỏ quyết định đã đưa ra.** Một thay đổi khẩn cấp bị từ chối sẽ bị thay thế; các quyết định bị ảnh hưởng được tìm thấy qua bản ghi và được chấm điểm lại theo quy trình của ngân hàng.
- **Quản lý khoá.** Khoá nằm trong HSM hoặc KMS của ngân hàng, phía sau SPI `Signer`.
- **Blockchain công khai** làm nơi neo. Neo nội bộ đáp ứng nhu cầu mà không phải công bố gì ra ngoài.

## 10. Câu hỏi mở

| Câu hỏi | Đề xuất hiện tại |
|---|---|
| JWS hay chữ ký trần? | JWS dạng compact chỉ với `EdDSA` và `ES256`, đọc bằng một trình đọc nhỏ và chặt chẽ trong mỗi engine, không dùng thư viện JOSE. Thêm `PS256` nếu HSM của một ngân hàng yêu cầu RSA. |
| Chữ ký của dịch vụ hay chữ ký của từng người duyệt? | Mặc định là chữ ký của dịch vụ; chữ ký của từng người duyệt là tuỳ chọn mà bộ kiểm chứng có thể yêu cầu. |
| Phiên bản bóng có phải được duyệt không? | Có: phiên bản bóng chạy trên dữ liệu production, và bản ghi của nó nằm cùng với các quyết định thật. Phiên bản canary trả lời khách hàng, nên hiển nhiên phải được duyệt. Cả hai đều có tên trong bản phát hành. |
| Khi nào một nhãn được gắn với một mã băm? | Lúc phê duyệt. Trước đó, một nhãn có thể được trình duyệt lại với nội dung khác như một lần trình duyệt mới. |
| Phê duyệt bổ sung khẩn cấp quá hạn thì sao? | Một cảnh báo và một mục nhật ký, không làm build thất bại. Ngân hàng có thể muốn một chế độ chặt hơn, từ chối bản phát hành mới cho tới khi vấn đề được giải quyết. |
| Bản thân chính sách quản trị có phải là đối tượng được quản trị không? | Thay đổi chính sách được ghi thành `POLICY_CHANGED` và cần người quản trị quản trị. Coi chính sách là một bộ thay đổi với người duyệt riêng có thể làm sau. |
| Một nhật ký cho mỗi kho hay cho mỗi đích? | Mỗi kho một nhật ký: kiểm chứng đơn giản hơn, và khối lượng nhỏ. |
| Liên kết chuỗi bản ghi quyết định? | Niêm phong theo lô trong JDBC sink, khi được bật; không liên kết chuỗi trong engine. |
| Ở chế độ Git, làm sao chứng minh nguyên tắc bốn mắt trên mọi lần lưu? | Không thể chặt chẽ như kho quản trị: job CI đối chiếu tác giả các commit của pull request với những người review đã phê duyệt. Ngân hàng cần nhật ký đầy đủ thì dùng kho JDBC. |
| Công cụ migration cho DDL | Script thuần cho từng cơ sở dữ liệu, như DBA của ngân hàng thường yêu cầu; Flyway và Liquibase có thể dùng lại các script này. |
| Engine nạp bản phát hành nào? | Mỗi ứng dụng hoặc dịch vụ quyết định được triển khai có một `target`, để các ứng dụng khác nhau có thể chạy các tập phiên bản đã duyệt khác nhau. |

## 11. Kế hoạch triển khai

Mỗi bước là một thay đổi riêng, rà soát được, có kiểm thử và tài liệu bằng tiếng Anh và tiếng Việt. Bước 1 và 2 được làm trong cả hai engine, có fixture tương thích.

1. **Văn bản phê duyệt và `requireApproved()`.** Định dạng văn bản, kiểm chứng JWS với `EdDSA` và `ES256`, khoá tin cậy, gắn với mã băm, kiểm tra nguyên tắc bốn mắt và `minApprovers`, phê duyệt đọc từ tệp. `java-approvals.json` cho bộ máy luật TypeScript. Bước này đưa việc bắt buộc vào trước: ngân hàng có thể phát hành văn bản phê duyệt từ một job CI và từ chối nội dung chưa được duyệt trước khi có bất kỳ dịch vụ quản trị nào.
2. **Bản phát hành và SPI `RuleRepository`.** Định dạng bản phát hành với `sequence` và `previous`, định tuyến trong bản phát hành, giao diện kho luật chỉ đọc với triển khai cho tệp và Git, lớp hỗ trợ nạp lại và bộ đệm cục bộ của bản phát hành gần nhất.
3. **Phần lõi của dịch vụ quản trị** (`rulestone-governance`): vòng đời, bộ thay đổi, chính sách và cấp rủi ro, phân tách nhiệm vụ, thay đổi khẩn cấp, các SPI `PrincipalResolver` và `Signer`, một kho trong bộ nhớ, nhật ký liên kết chuỗi băm và công cụ `verify`.
4. **JDBC.** `rulestone-repository-jdbc` với DDL cho PostgreSQL, Oracle và SQL Server, được kiểm thử trên từng cơ sở dữ liệu, và `rulestone-audit-jdbc` với niêm phong theo lô tuỳ chọn.
5. **Neo chuỗi.** Xuất điểm kiểm có chữ ký, một client dấu thời gian RFC 3161 phía sau một SPI, và các sự kiện `ANCHORED`.
6. **`ApprovalWorkflow`.** SPI, quy trình có sẵn, và quy trình cho Camunda 8 và Camunda 7.
7. **Trình soạn thảo.** Xác thực và màn hình quản trị trong trình soạn thảo độc lập; phần tử so sánh khác biệt và bộ thay đổi trong SDK, kèm hướng dẫn cho ứng dụng chủ.
8. **Kiến trúc và lộ trình.** Đánh dấu M2 đã xong trong [Kiến trúc](architecture.md), và chuyển các yêu cầu của ngân hàng về phân tách nhiệm vụ và kiểm toán chống giả mạo sang trạng thái đã có.
